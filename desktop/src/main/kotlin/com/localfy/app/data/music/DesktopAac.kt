package com.localfy.app.data.music

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVAudioFifo
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File

/**
 * Desktop counterpart of the phone's AacTranscoder: decodes anything FFmpeg reads (FLAC in practice)
 * and writes AAC-LC 256 kbit/s in an .m4a with FFmpeg's built-in "aac" encoder. Hi-res sources
 * (88.2–192 kHz) are resampled (swresample, band-limited) to 44.1 or 48 kHz, since AAC tops out there.
 */
object DesktopAac {
    const val BITRATE = 256_000
    const val LABEL = "AAC 256 kbps"

    class Unsupported(message: String) : Exception(message)

    data class Info(val codec: String, val sampleRate: Int, val channels: Int, val bitRate: Long, val durationMs: Long)

    init { av_log_set_level(AV_LOG_ERROR) }

    /** Output rate for a source rate: unchanged up to 48 kHz, otherwise its 44.1/48 kHz family base. */
    fun outputRate(inRate: Int): Int = when {
        inRate <= 48_000 -> inRate
        inRate % 44_100 == 0 -> 44_100
        inRate % 48_000 == 0 -> 48_000
        else -> 48_000
    }

    /** Reads codec, rate, channels, bitrate and duration of [file] (null when FFmpeg can't open it). */
    fun probe(file: File): Info? {
        val fmt = AVFormatContext(null)
        if (avformat_open_input(fmt, file.path, null, null as AVDictionary?) < 0) return null
        try {
            if (avformat_find_stream_info(fmt, null as PointerPointer<*>?) < 0) return null
            val index = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
            if (index < 0) return null
            val par = fmt.streams(index).codecpar()
            val name = avcodec_get_name(par.codec_id())?.string ?: "unknown"
            val duration = if (fmt.duration() > 0) fmt.duration() / 1000 else 0L
            val bitRate = if (par.bit_rate() > 0) par.bit_rate() else fmt.bit_rate()
            return Info(name, par.sample_rate(), par.ch_layout().nb_channels(), bitRate, duration)
        } finally { avformat_close_input(fmt) }
    }

    /**
     * Writes [out] (an .m4a); returns the converted duration in ms. Throws [Unsupported] when the source
     * can't be converted (no audio, more than two channels...) and IllegalStateException on FFmpeg errors.
     * [cancelled] is checked between packets so a cancelled download stops promptly.
     */
    fun transcode(input: File, out: File, cancelled: () -> Boolean = { false }): Long {
        val inFmt = AVFormatContext(null)
        var dec: AVCodecContext? = null
        var enc: AVCodecContext? = null
        var outFmt: AVFormatContext? = null
        var swr: SwrContext? = null
        var fifo: AVAudioFifo? = null
        val packet = av_packet_alloc()
        val outPacket = av_packet_alloc()
        val frame = av_frame_alloc()
        var ok = false
        try {
            check(avformat_open_input(inFmt, input.path, null, null as AVDictionary?) >= 0) { "Could not open the audio file." }
            check(avformat_find_stream_info(inFmt, null as PointerPointer<*>?) >= 0) { "Could not read the audio file." }
            val index = av_find_best_stream(inFmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
            if (index < 0) throw Unsupported("No audio in this file.")
            val inStream: AVStream = inFmt.streams(index)
            val decoder = avcodec_find_decoder(inStream.codecpar().codec_id()) ?: throw Unsupported("No decoder for this audio.")
            dec = avcodec_alloc_context3(decoder)
            ffCheck(avcodec_parameters_to_context(dec, inStream.codecpar()), "decoder setup")
            ffCheck(avcodec_open2(dec, decoder, null as AVDictionary?), "decoder open")
            val channels = dec.ch_layout().nb_channels()
            if (channels !in 1..2) throw Unsupported("Only mono and stereo files are converted.")
            val inRate = dec.sample_rate()
            if (inRate <= 0) throw Unsupported("Unknown sample rate.")
            val outRate = outputRate(inRate)

            val encoder = avcodec_find_encoder_by_name("aac") ?: throw Unsupported("This FFmpeg has no AAC encoder.")
            outFmt = AVFormatContext(null)
            ffCheck(avformat_alloc_output_context2(outFmt, null, "ipod", out.path), "output setup")
            enc = avcodec_alloc_context3(encoder)
            enc.sample_fmt(AV_SAMPLE_FMT_FLTP)
            enc.sample_rate(outRate)
            av_channel_layout_default(enc.ch_layout(), channels)
            enc.bit_rate(BITRATE.toLong())
            enc.profile(AV_PROFILE_AAC_LOW)
            enc.time_base(av_make_q(1, outRate))
            if (outFmt.oformat().flags() and AVFMT_GLOBALHEADER != 0) enc.flags(enc.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
            ffCheck(avcodec_open2(enc, encoder, null as AVDictionary?), "encoder open")
            val outStream = avformat_new_stream(outFmt, null) ?: error("Could not add the audio track.")
            ffCheck(avcodec_parameters_from_context(outStream.codecpar(), enc), "track setup")
            outStream.time_base(enc.time_base())
            val pb = AVIOContext(null)
            ffCheck(avio_open(pb, out.path, AVIO_FLAG_WRITE), "output open")
            outFmt.pb(pb)
            ffCheck(avformat_write_header(outFmt, null as AVDictionary?), "header")

            swr = SwrContext(null)
            ffCheck(swr_alloc_set_opts2(swr, enc.ch_layout(), AV_SAMPLE_FMT_FLTP, outRate, dec.ch_layout(), dec.sample_fmt(), inRate, 0, null), "resampler setup")
            ffCheck(swr_init(swr), "resampler init")
            val frameSize = enc.frame_size().takeIf { it > 0 } ?: 1024
            fifo = av_audio_fifo_alloc(AV_SAMPLE_FMT_FLTP, channels, frameSize * 4) ?: error("Out of memory.")
            var written = 0L

            fun encode(f: AVFrame?) {
                ffCheck(avcodec_send_frame(enc, f), "encode")
                while (true) {
                    val r = avcodec_receive_packet(enc, outPacket)
                    if (r == AVERROR_EAGAIN() || r == AVERROR_EOF) return
                    ffCheck(r, "encode")
                    av_packet_rescale_ts(outPacket, enc.time_base(), outStream.time_base())
                    outPacket.stream_index(outStream.index())
                    ffCheck(av_interleaved_write_frame(outFmt, outPacket), "write")
                }
            }
            fun drainFifo(all: Boolean) {
                while (av_audio_fifo_size(fifo) >= frameSize || (all && av_audio_fifo_size(fifo) > 0)) {
                    val n = minOf(frameSize, av_audio_fifo_size(fifo))
                    val ef = av_frame_alloc()
                    try {
                        ef.nb_samples(n); ef.format(AV_SAMPLE_FMT_FLTP); ef.sample_rate(outRate)
                        av_channel_layout_copy(ef.ch_layout(), enc.ch_layout())
                        ffCheck(av_frame_get_buffer(ef, 0), "buffer")
                        check(av_audio_fifo_read(fifo, ef.data(), n) == n) { "Audio buffer error." }
                        ef.pts(written); written += n
                        encode(ef)
                    } finally { av_frame_free(ef) }
                }
            }
            fun resample(src: AVFrame?) {
                val inSamples = src?.nb_samples() ?: 0
                while (true) {
                    val capacity = swr_get_out_samples(swr, inSamples).coerceAtLeast(frameSize)
                    val conv = av_frame_alloc()
                    try {
                        conv.nb_samples(capacity); conv.format(AV_SAMPLE_FMT_FLTP); conv.sample_rate(outRate)
                        av_channel_layout_copy(conv.ch_layout(), enc.ch_layout())
                        ffCheck(av_frame_get_buffer(conv, 0), "buffer")
                        val n = swr_convert(swr, conv.data(), capacity, src?.extended_data(), inSamples)
                        ffCheck(n, "resample")
                        if (n > 0) check(av_audio_fifo_write(fifo, conv.data(), n) == n) { "Audio buffer error." }
                        // Flushing (src == null) repeats until the resampler has nothing left.
                        if (src != null || n == 0) break
                    } finally { av_frame_free(conv) }
                }
                drainFifo(false)
            }
            fun decodeAll() {
                while (true) {
                    val r = avcodec_receive_frame(dec, frame)
                    if (r == AVERROR_EAGAIN() || r == AVERROR_EOF) return
                    ffCheck(r, "decode")
                    resample(frame)
                    av_frame_unref(frame)
                }
            }

            while (av_read_frame(inFmt, packet) >= 0) {
                try {
                    if (cancelled()) throw kotlinx.coroutines.CancellationException("Conversion cancelled")
                    if (packet.stream_index() == index) {
                        // A corrupt packet is skipped rather than failing the whole song.
                        if (avcodec_send_packet(dec, packet) >= 0) decodeAll()
                    }
                } finally { av_packet_unref(packet) }
            }
            avcodec_send_packet(dec, null as AVPacket?)
            decodeAll()
            resample(null)
            drainFifo(true)
            encode(null)
            ffCheck(av_write_trailer(outFmt), "finish")
            check(written > 0) { "The audio file contained no samples." }
            ok = true
            return written * 1000 / outRate
        } finally {
            outFmt?.let { f ->
                f.pb()?.let { if (!it.isNull) avio_closep(f.pb()) }
                avformat_free_context(f)
            }
            if (!ok) out.delete()
            fifo?.let { av_audio_fifo_free(it) }
            swr?.let { swr_free(it) }
            enc?.let { avcodec_free_context(it) }
            dec?.let { avcodec_free_context(it) }
            av_frame_free(frame)
            av_packet_free(packet)
            av_packet_free(outPacket)
            avformat_close_input(inFmt)
        }
    }

    private fun ffCheck(code: Int, what: String) {
        if (code >= 0) return
        val buf = BytePointer(256)
        try {
            av_strerror(code, buf, 256)
            throw IllegalStateException("Audio conversion failed ($what): ${buf.string}")
        } finally { buf.close() }
    }
}

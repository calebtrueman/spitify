package com.localfy.app.data.music

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVAudioFifo
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File

/**
 * Converts any audio FFmpeg can read (FLAC, WAV, MP3, ALAC, Opus, http(s) streams...) to
 * AAC-LC 256 kbit/s in an .m4a, using FFmpeg's built-in "aac" encoder. This is the desktop
 * counterpart of the Android `AacTranscoder` and what both FLAC conversion and downloads use.
 *
 * - Hi-res sources (88.2–192 kHz, or any rate AAC can't take) are resampled with swresample's
 *   low-pass filtered resampler to 44.1 kHz (44.1 kHz family) or 48 kHz (everything else).
 * - Mono stays mono; stereo stays stereo; more channels are downmixed to stereo.
 * - Tags and cover art are NOT copied; tag the output afterwards (see `FileTags.tag`).
 *
 * Usage:
 * ```
 * val durationMs = AacEncoder.transcode(File("song.flac"), File("song.m4a"))
 * AacEncoder.transcode("https://example.com/stream.flac", File("out.m4a"), cancelled = { !job.isActive })
 * ```
 * Calls are blocking and thread-safe (each call has its own FFmpeg contexts): run them on Dispatchers.IO.
 * The output file is written directly; write to a temporary file and rename it if others may see it.
 */
object AacEncoder {
    const val BITRATE = 256_000
    const val LABEL = "AAC 256 kbps"

    /** The source has no audio or can't be decoded/converted. */
    class Unsupported(message: String) : Exception(message)

    /** Thrown when [transcode]'s `cancelled` returns true. The partial output is deleted. */
    class Cancelled : Exception("Conversion cancelled")

    /** Basic facts about an audio file or URL (see [probe]). */
    data class Probe(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long,
        val bitRate: Long,
        /** Container + stream metadata, keys lower-cased (e.g. "title", "artist", "album", "track"). */
        val tags: Map<String, String>,
        /** True when the file has an attached picture (embedded cover). */
        val hasCover: Boolean,
    )

    private val AAC_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    /** Output sample rate for [inRate]: unchanged when AAC supports it, otherwise 44.1 or 48 kHz. */
    fun outputRate(inRate: Int): Int = when {
        inRate in AAC_RATES -> inRate
        inRate > 0 && inRate % 44_100 == 0 -> 44_100
        inRate in 1 until 8000 -> 8000
        else -> 48_000
    }

    init {
        av_log_set_level(AV_LOG_FATAL) // failures surface as exceptions; corrupt files in a scan would flood the console
        avformat_network_init()
    }

    /** Transcodes [input] to AAC 256k [output] (.m4a). Returns the duration in ms. */
    fun transcode(input: File, output: File, cancelled: () -> Boolean = { false }): Long =
        transcode(input.absolutePath, output, cancelled)

    /**
     * Transcodes [input] (a local path, file: URI or http(s) URL) to AAC 256k [output].
     * Returns the converted duration in ms. Throws [Unsupported] for unreadable sources,
     * [Cancelled] when [cancelled] becomes true; [output] is deleted on any failure.
     */
    fun transcode(input: String, output: File, cancelled: () -> Boolean = { false }): Long {
        val source = if (input.startsWith("file:")) runCatching { File(java.net.URI(input)).path }.getOrDefault(input) else input
        return encode(source, output, Spec("aac", listOf("ipod", "mp4"), AV_SAMPLE_FMT_FLTP, BITRATE.toLong(), ::outputRate, AV_PROFILE_AAC_LOW), cancelled)
    }

    /** What to encode to: codec, muxers to try, sample format, bit rate (0 = codec default), rate mapping. */
    internal class Spec(
        val codec: String, val muxers: List<String>, val sampleFormat: Int, val bitRate: Long,
        val rate: (Int) -> Int, val profile: Int = AV_PROFILE_UNKNOWN,
    )

    /** General decode → resample → encode pipeline (AAC for the app; FLAC etc. for tests and tools). */
    internal fun encode(source: String, output: File, spec: Spec, cancelled: () -> Boolean = { false }): Long {
        output.parentFile?.mkdirs()
        var ok = false
        try {
            val ms = Job(source, output, spec, cancelled).run()
            ok = true
            return ms
        } finally {
            if (!ok) output.delete()
        }
    }

    /** Reads codec, rate, channels, duration and tags; null when FFmpeg can't open it. */
    fun probe(input: String): Probe? {
        val fmt = AVFormatContext(null)
        if (avformat_open_input(fmt, input, null, null as AVDictionary?) < 0) return null
        try {
            if (avformat_find_stream_info(fmt, null as AVDictionary?) < 0) return null
            val index = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
            if (index < 0) return null
            val stream = fmt.streams(index)
            val par = stream.codecpar()
            val tags = LinkedHashMap<String, String>()
            readDict(fmt.metadata(), tags)
            readDict(stream.metadata(), tags)
            var cover = false
            for (i in 0 until fmt.nb_streams()) {
                if (fmt.streams(i).disposition() and AV_DISPOSITION_ATTACHED_PIC != 0) cover = true
            }
            val duration = when {
                fmt.duration() > 0 -> fmt.duration() / 1000
                stream.duration() > 0 -> av_rescale_q(stream.duration(), stream.time_base(), AVRational().num(1).den(1000))
                else -> 0
            }
            return Probe(
                codec = avcodec_get_name(par.codec_id())?.string ?: "unknown",
                sampleRate = par.sample_rate(),
                channels = par.ch_layout().nb_channels(),
                durationMs = duration,
                bitRate = if (par.bit_rate() > 0) par.bit_rate() else fmt.bit_rate(),
                tags = tags,
                hasCover = cover,
            )
        } finally {
            avformat_close_input(fmt)
        }
    }

    fun probe(file: File): Probe? = probe(file.absolutePath)

    private fun readDict(dict: AVDictionary?, into: MutableMap<String, String>) {
        if (dict == null || dict.isNull) return
        var entry: AVDictionaryEntry? = null
        while (true) {
            entry = av_dict_get(dict, "", entry, AV_DICT_IGNORE_SUFFIX)
            if (entry == null || entry.isNull) break
            val key = entry.key()?.getString(Charsets.UTF_8)?.lowercase() ?: continue
            val value = entry.value()?.getString(Charsets.UTF_8) ?: continue
            into.putIfAbsent(key, value)
        }
    }

    private fun check(code: Int, what: String): Int {
        if (code < 0) {
            val buf = ByteArray(256)
            av_strerror(code, buf, buf.size.toLong())
            throw Unsupported("$what failed: ${String(buf).trimEnd('\u0000')}")
        }
        return code
    }

    /** One conversion; owns all native objects and frees them in [run]'s finally block. */
    private class Job(val input: String, val output: File, val spec: Spec, val cancelled: () -> Boolean) {
        var inFmt: AVFormatContext? = null
        var outFmt: AVFormatContext? = null
        var dec: AVCodecContext? = null
        var enc: AVCodecContext? = null
        var swr: SwrContext? = null
        var fifo: AVAudioFifo? = null
        val packet: AVPacket = av_packet_alloc()
        val outPacket: AVPacket = av_packet_alloc()
        val frame: AVFrame = av_frame_alloc()
        val outLayout = AVChannelLayout().zero<AVChannelLayout>()
        var nextPts = 0L
        var streamIndex = -1
        var headerWritten = false
        var swrInRate = 0

        fun run(): Long {
            try {
                open()
                val dec = dec!!
                while (true) {
                    if (cancelled()) throw Cancelled()
                    val r = av_read_frame(inFmt, packet)
                    if (r < 0) break
                    try {
                        if (packet.stream_index() == streamIndex) {
                            // A corrupt packet in a long file shouldn't fail the whole conversion.
                            if (avcodec_send_packet(dec, packet) >= 0) drainDecoder()
                        }
                    } finally {
                        av_packet_unref(packet)
                    }
                }
                avcodec_send_packet(dec, null as AVPacket?)
                drainDecoder()
                flushResampler()
                encodeFromFifo(final = true)
                check(avcodec_send_frame(enc, null as AVFrame?), "Finishing the AAC stream")
                drainEncoder()
                check(av_write_trailer(outFmt), "Finishing the file")
                if (nextPts == 0L) throw Unsupported("No audio could be decoded.")
                return nextPts * 1000 / enc!!.sample_rate()
            } finally {
                release()
            }
        }

        private fun open() {
            val inFmt = AVFormatContext(null).also { this.inFmt = it }
            check(avformat_open_input(inFmt, input, null, null as AVDictionary?), "Opening the source")
            check(avformat_find_stream_info(inFmt, null as AVDictionary?), "Reading the source")
            streamIndex = av_find_best_stream(inFmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
            if (streamIndex < 0) throw Unsupported("No audio in this file.")
            val par = inFmt.streams(streamIndex).codecpar()
            val decoder = avcodec_find_decoder(par.codec_id()) ?: throw Unsupported("No decoder for this audio.")
            val dec = avcodec_alloc_context3(decoder).also { this.dec = it }
            check(avcodec_parameters_to_context(dec, par), "Reading the codec")
            check(avcodec_open2(dec, decoder, null as AVDictionary?), "Opening the decoder")

            val inChannels = par.ch_layout().nb_channels().takeIf { it > 0 } ?: 2
            val outChannels = if (inChannels == 1) 1 else 2
            val outRate = spec.rate(par.sample_rate())
            av_channel_layout_default(outLayout, outChannels)

            val encoder = avcodec_find_encoder_by_name(spec.codec) ?: throw Unsupported("FFmpeg has no ${spec.codec} encoder.")
            val enc = avcodec_alloc_context3(encoder).also { this.enc = it }
            enc.sample_fmt(spec.sampleFormat)
            enc.sample_rate(outRate)
            check(av_channel_layout_copy(enc.ch_layout(), outLayout), "Setting the channel layout")
            if (spec.bitRate > 0) enc.bit_rate(spec.bitRate)
            if (spec.profile != AV_PROFILE_UNKNOWN) enc.profile(spec.profile)
            enc.time_base(AVRational().num(1).den(outRate))

            // For AAC, "ipod" writes an M4A (brand M4A); fall back to plain MP4 if this FFmpeg lacks it.
            var outFmt: AVFormatContext? = null
            for (muxer in spec.muxers) {
                val candidate = AVFormatContext(null)
                if (avformat_alloc_output_context2(candidate, null, muxer, output.absolutePath) >= 0 && !candidate.isNull) { outFmt = candidate; break }
            }
            if (outFmt == null) throw Unsupported("Could not create ${output.name}.")
            this.outFmt = outFmt
            if (outFmt.oformat().flags() and AVFMT_GLOBALHEADER != 0) enc.flags(enc.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
            check(avcodec_open2(enc, encoder, null as AVDictionary?), "Opening the AAC encoder")

            val stream = avformat_new_stream(outFmt, null) ?: throw Unsupported("Could not add the audio track.")
            check(avcodec_parameters_from_context(stream.codecpar(), enc), "Setting up the audio track")
            stream.time_base(enc.time_base())
            val pb = AVIOContext(null)
            check(avio_open(pb, output.absolutePath, AVIO_FLAG_WRITE), "Creating ${output.name}")
            outFmt.pb(pb)
            check(avformat_write_header(outFmt, null as AVDictionary?), "Writing the M4A header")
            headerWritten = true

            fifo = av_audio_fifo_alloc(spec.sampleFormat, outChannels, 4096) ?: throw Unsupported("Out of memory.")
        }

        /** The resampler is created on the first decoded frame: only then is the real input format known. */
        private fun resampler(src: AVFrame): SwrContext {
            swr?.let { return it }
            val ctx = SwrContext(null)
            val inLayout = AVChannelLayout().zero<AVChannelLayout>()
            if (src.ch_layout().nb_channels() > 0) av_channel_layout_copy(inLayout, src.ch_layout())
            else av_channel_layout_default(inLayout, dec!!.ch_layout().nb_channels().coerceAtLeast(1))
            swrInRate = src.sample_rate().takeIf { it > 0 } ?: dec!!.sample_rate()
            check(swr_alloc_set_opts2(ctx, outLayout, spec.sampleFormat, enc!!.sample_rate(), inLayout, src.format(), swrInRate, 0, null), "Setting up the resampler")
            av_channel_layout_uninit(inLayout)
            check(swr_init(ctx), "Starting the resampler")
            swr = ctx
            return ctx
        }

        private fun drainDecoder() {
            while (true) {
                val r = avcodec_receive_frame(dec, frame)
                if (r == AVERROR_EAGAIN() || r == AVERROR_EOF || r < 0) return
                try {
                    if (cancelled()) throw Cancelled()
                    val swr = resampler(frame)
                    val outCount = av_rescale_rnd(swr_get_delay(swr, swrInRate.toLong()) + frame.nb_samples(), enc!!.sample_rate().toLong(), swrInRate.toLong(), AV_ROUND_UP).toInt()
                    convert(swr, frame.extended_data(), frame.nb_samples(), outCount)
                } finally {
                    av_frame_unref(frame)
                }
                encodeFromFifo(final = false)
            }
        }

        private fun flushResampler() {
            val swr = swr ?: return
            val pending = swr_get_delay(swr, enc!!.sample_rate().toLong()).toInt() + 32
            convert(swr, null, 0, pending)
        }

        private fun convert(swr: SwrContext, input: PointerPointer<*>?, inCount: Int, outCapacity: Int) {
            if (outCapacity <= 0) return
            val tmp = av_frame_alloc()
            try {
                tmp.format(spec.sampleFormat)
                tmp.sample_rate(enc!!.sample_rate())
                av_channel_layout_copy(tmp.ch_layout(), outLayout)
                tmp.nb_samples(outCapacity)
                check(av_frame_get_buffer(tmp, 0), "Allocating audio")
                val converted = check(swr_convert(swr, tmp.extended_data(), outCapacity, input, inCount), "Resampling")
                if (converted > 0) {
                    if (av_audio_fifo_write(fifo, tmp.extended_data(), converted) < converted) throw Unsupported("Out of memory.")
                }
            } finally {
                av_frame_free(tmp)
            }
        }

        private fun encodeFromFifo(final: Boolean) {
            val enc = enc!!
            val frameSize = enc.frame_size().takeIf { it > 0 } ?: 1024
            while (true) {
                val available = av_audio_fifo_size(fifo)
                if (available <= 0 || (!final && available < frameSize)) return
                val n = minOf(frameSize, available)
                val out = av_frame_alloc()
                try {
                    out.nb_samples(n)
                    out.format(enc.sample_fmt())
                    out.sample_rate(enc.sample_rate())
                    av_channel_layout_copy(out.ch_layout(), enc.ch_layout())
                    check(av_frame_get_buffer(out, 0), "Allocating audio")
                    if (av_audio_fifo_read(fifo, out.extended_data(), n) < n) throw Unsupported("Audio buffer error.")
                    out.pts(nextPts)
                    nextPts += n
                    check(avcodec_send_frame(enc, out), "Encoding AAC")
                } finally {
                    av_frame_free(out)
                }
                drainEncoder()
            }
        }

        private fun drainEncoder() {
            val stream = outFmt!!.streams(0)
            while (true) {
                val r = avcodec_receive_packet(enc, outPacket)
                if (r == AVERROR_EAGAIN() || r == AVERROR_EOF) return
                check(r, "Encoding AAC")
                av_packet_rescale_ts(outPacket, enc!!.time_base(), stream.time_base())
                outPacket.stream_index(0)
                check(av_interleaved_write_frame(outFmt, outPacket), "Writing ${output.name}")
                av_packet_unref(outPacket)
            }
        }

        private fun release() {
            runCatching { swr?.let { swr_free(it) } }
            runCatching { fifo?.let { av_audio_fifo_free(it) } }
            runCatching { av_frame_free(frame) }
            runCatching { av_packet_free(packet) }
            runCatching { av_packet_free(outPacket) }
            runCatching { dec?.let { avcodec_free_context(it) } }
            runCatching { enc?.let { avcodec_free_context(it) } }
            runCatching { inFmt?.let { if (!it.isNull) avformat_close_input(it) } }
            runCatching {
                outFmt?.let { fmt ->
                    if (!fmt.isNull) {
                        fmt.pb()?.let { pb -> if (!pb.isNull) avio_closep(pb) }
                        avformat_free_context(fmt)
                    }
                }
            }
            runCatching { av_channel_layout_uninit(outLayout) }
        }
    }
}

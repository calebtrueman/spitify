package com.localfy.app.playback

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_network_init
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.avutil.AVERROR_DECODER_NOT_FOUND
import org.bytedeco.ffmpeg.global.avutil.AVERROR_DEMUXER_NOT_FOUND
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EXIT
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_BAD_REQUEST
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_FORBIDDEN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_NOT_FOUND
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_OTHER_4XX
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_SERVER_ERROR
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_TOO_MANY_REQUESTS
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_UNAUTHORIZED
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.bytedeco.ffmpeg.global.avutil.AVERROR_PROTOCOL_NOT_FOUND
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.ffmpeg.global.avutil.AV_TIME_BASE
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_delay
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes one track with FFmpeg on its own thread into a PCM ring buffer (48 kHz stereo float).
 * The audio thread pulls from it ([read]) without ever touching FFmpeg, so a slow network read
 * shows up as an empty buffer (buffering) instead of a stalled output. Seeks are requested here
 * and carried out on the decode thread; everything native is freed when that thread exits.
 */
internal class TrackDecoder(
    val track: EngineTrack,
    startMs: Long,
    /** Called (from the decode thread) whenever data, an error or the end becomes available. */
    private val onProgress: () -> Unit,
) : PcmSource {

    enum class Status { OPENING, READY, FAILED }

    @Volatile var status = Status.OPENING; private set
    @Volatile var error: EngineError? = null; private set
    /** Track length, 0 until known. */
    @Volatile var durationMs = 0L; private set

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val capacity = (if (track.isRemote) REMOTE_BUFFER_SECONDS else LOCAL_BUFFER_SECONDS) * SAMPLE_RATE
    private val ring = FloatArray(capacity * CHANNELS)
    private var head = 0 // next frame to read
    private var count = 0 // frames buffered
    private var readFrame = startMs * SAMPLE_RATE / 1000 // source position of [head]
    private var generation = 0
    private var pendingSeekMs = startMs.takeIf { it > 0 } ?: -1L
    private var decodedAll = false
    @Volatile private var closed = false

    private val thread = Thread(::run, "spitify-decode-${track.uid}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }

    fun start() = thread.start()

    /** Source position (48 kHz frames) of the next frame [read] returns. */
    val positionFrames: Long get() = lock.withLock { readFrame }
    val bufferedFrames: Int get() = lock.withLock { count }
    /** True once everything has been decoded and read. */
    val finished: Boolean get() = lock.withLock { decodedAll && count == 0 && pendingSeekMs < 0 }
    val decodedToEnd: Boolean get() = lock.withLock { decodedAll && pendingSeekMs < 0 }

    override fun read(dst: FloatArray, offset: Int, frames: Int): Int = lock.withLock {
        if (count == 0) {
            return if (status == Status.FAILED || (decodedAll && pendingSeekMs < 0)) -1 else 0
        }
        val n = min(frames, count)
        val first = min(n, capacity - head)
        System.arraycopy(ring, head * 2, dst, offset * 2, first * 2)
        if (n > first) System.arraycopy(ring, 0, dst, (offset + first) * 2, (n - first) * 2)
        head = (head + n) % capacity
        count -= n
        readFrame += n
        changed.signalAll()
        n
    }

    fun seek(ms: Long) = lock.withLock {
        generation++
        pendingSeekMs = ms.coerceAtLeast(0)
        head = 0; count = 0
        readFrame = pendingSeekMs * SAMPLE_RATE / 1000
        decodedAll = false
        changed.signalAll()
    }

    /** Stops decoding; native resources are released by the decode thread as it exits. */
    fun close() {
        closed = true
        lock.withLock { changed.signalAll() }
    }

    // ------------------------------------------------------------ decode thread

    private var fmt: AVFormatContext? = null
    private var codecCtx: AVCodecContext? = null
    private var swr: SwrContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var outBuf: FloatPointer? = null
    private var outPtrs: PointerPointer<Pointer>? = null
    private var outCapacity = 0
    private var scratch = FloatArray(0)
    private var streamIndex = -1
    private var timeBase = 0.0
    private var swrFormat = -1; private var swrRate = -1; private var swrChannels = -1
    /** After a seek: output frames before this source frame are dropped (precise seeking). */
    private var discardUntil = -1L
    private var decodePos = -1L

    private val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
        override fun call(opaque: Pointer?): Int = if (closed) 1 else 0
    }

    private fun run() {
        try {
            open()
            if (closed) return
            status = Status.READY
            onProgress()
            loop()
        } catch (e: DecodeFailure) {
            fail(e.error)
        } catch (e: Throwable) {
            fail(EngineError(EngineError.Kind.OTHER, e.message ?: e.javaClass.simpleName))
        } finally {
            freeNative()
        }
    }

    private fun fail(e: EngineError) {
        if (closed) return
        error = e
        status = Status.FAILED
        lock.withLock { changed.signalAll() }
        onProgress()
    }

    private class DecodeFailure(val error: EngineError) : Exception(error.message)

    private fun open() {
        ensureNetwork()
        val url = runBlocking { withTimeoutOrNull(30_000) { track.url() } }
            ?: throw DecodeFailure(EngineError(EngineError.Kind.NETWORK, "No stream available"))
        try {
            openUrl(url)
        } catch (e: DecodeFailure) {
            // Streams are opened unchecked (checking first costs a whole extra request); a source that
            // turns out not to play is reported, and the track offers the next one.
            val report = track.failed
            if (closed || report == null || !(url.startsWith("http://") || url.startsWith("https://"))) throw e
            val next = runBlocking { report(url); withTimeoutOrNull(30_000) { track.url() } }
            if (closed || next == null || next == url) throw e
            freeOpened()
            openUrl(next)
        }
    }

    private fun freeOpened() {
        runCatching { frame?.let { av_frame_free(it) } }; frame = null
        runCatching { packet?.let { av_packet_free(it) } }; packet = null
        runCatching { codecCtx?.let { avcodec_free_context(it) } }; codecCtx = null
        runCatching { fmt?.let { avformat_close_input(it) } }; fmt = null
    }

    private fun openUrl(url: String) {
        val target = ffmpegLocation(url)
        val remote = target.startsWith("http://") || target.startsWith("https://")
        if (!remote && !target.contains("://") && !File(target).isFile) throw DecodeFailure(EngineError(EngineError.Kind.OTHER, "File not found"))

        val ctx = avformat_alloc_context() ?: throw DecodeFailure(EngineError(EngineError.Kind.OTHER, "Out of memory"))
        ctx.interrupt_callback().callback(interrupt)
        fmt = ctx
        val opts = AVDictionary(null as Pointer?)
        if (remote) {
            av_dict_set(opts, "reconnect", "1", 0)
            av_dict_set(opts, "reconnect_streamed", "1", 0)
            av_dict_set(opts, "reconnect_on_network_error", "1", 0)
            av_dict_set(opts, "reconnect_delay_max", "8", 0)
            av_dict_set(opts, "rw_timeout", "20000000", 0)
            av_dict_set(opts, "user_agent", "Spitify Desktop", 0)
            // Start from the first bytes, like the phones: the default probe reads megabytes of a
            // lossless stream before the first sample plays.
            av_dict_set(opts, "probesize", "65536", 0)
            av_dict_set(opts, "analyzeduration", "0", 0)
        }
        val ret = avformat_open_input(ctx, target, null, opts)
        av_dict_free(opts)
        if (ret < 0) {
            fmt = null // FFmpeg frees a user-supplied context on failure
            throw DecodeFailure(classify(ret, remote, opening = true))
        }
        if (closed) return
        if (avformat_find_stream_info(ctx, null as PointerPointer<*>?) < 0) throw DecodeFailure(EngineError(EngineError.Kind.UNSUPPORTED, "No stream info"))
        streamIndex = av_find_best_stream(ctx, AVMEDIA_TYPE_AUDIO, -1, -1, null as AVCodec?, 0)
        if (streamIndex < 0) throw DecodeFailure(EngineError(EngineError.Kind.UNSUPPORTED, "No audio stream"))
        for (i in 0 until ctx.nb_streams()) if (i != streamIndex) ctx.streams(i).discard(avcodec_discard_all)
        val stream = ctx.streams(streamIndex)
        timeBase = av_q2d(stream.time_base())
        val par = stream.codecpar()
        val codec = avcodec_find_decoder(par.codec_id()) ?: throw DecodeFailure(EngineError(EngineError.Kind.UNSUPPORTED, "No decoder"))
        val cc = avcodec_alloc_context3(codec) ?: throw DecodeFailure(EngineError(EngineError.Kind.OTHER, "Out of memory"))
        codecCtx = cc
        if (avcodec_parameters_to_context(cc, par) < 0 || avcodec_open2(cc, codec, null as AVDictionary?) < 0) {
            throw DecodeFailure(EngineError(EngineError.Kind.UNSUPPORTED, "Decoder failed to open"))
        }
        durationMs = when {
            ctx.duration() != AV_NOPTS_VALUE && ctx.duration() > 0 -> ctx.duration() * 1000 / AV_TIME_BASE
            stream.duration() != AV_NOPTS_VALUE && stream.duration() > 0 -> (stream.duration() * timeBase * 1000).toLong()
            else -> 0L
        }
        packet = av_packet_alloc()
        frame = av_frame_alloc()
    }

    private fun loop() {
        val fmt = fmt ?: return
        val pkt = packet ?: return
        while (!closed) {
            val (seekMs, gen) = lock.withLock {
                val s = pendingSeekMs
                pendingSeekMs = -1
                s to generation
            }
            if (seekMs >= 0) doSeek(seekMs)
            // Wait for room (or the end), keeping CPU idle while the buffer is full.
            lock.withLock {
                while (!closed && pendingSeekMs < 0 && (decodedAll || capacity - count < MIN_FREE_FRAMES)) changed.await()
            }
            if (closed) return
            if (lock.withLock { pendingSeekMs >= 0 }) continue
            val ret = av_read_frame(fmt, pkt)
            if (ret == AVERROR_EOF || (ret < 0 && fmt.pb()?.eof_reached() == 1)) {
                avcodec_send_packet(codecCtx, null as AVPacket?)
                drainFrames(gen)
                flushResampler(gen)
                lock.withLock { if (gen == generation) decodedAll = true; changed.signalAll() }
                onProgress()
                continue
            }
            if (ret < 0) {
                if (ret == AVERROR_EXIT || closed) return
                if (ret == eagain) { Thread.sleep(10); continue }
                throw DecodeFailure(classify(ret, track.isRemote, opening = false))
            }
            try {
                if (pkt.stream_index() == streamIndex) {
                    val sent = avcodec_send_packet(codecCtx, pkt)
                    if (sent < 0 && sent != eagain && sent != AVERROR_INVALIDDATA) throw DecodeFailure(classify(sent, track.isRemote, opening = false))
                    drainFrames(gen)
                }
            } finally {
                av_packet_unref(pkt)
            }
        }
    }

    private fun doSeek(ms: Long) {
        val fmt = fmt ?: return
        val ts = ms * (AV_TIME_BASE / 1000)
        av_seek_frame(fmt, -1, ts, AVSEEK_FLAG_BACKWARD)
        avcodec_flush_buffers(codecCtx)
        swr?.let { swr_init(it) } // drops the resampler's history
        discardUntil = ms * SAMPLE_RATE / 1000
        decodePos = -1
        lock.withLock { decodedAll = false }
    }

    private fun drainFrames(gen: Int) {
        val cc = codecCtx ?: return
        val f = frame ?: return
        while (!closed) {
            val r = avcodec_receive_frame(cc, f)
            if (r < 0) return // EAGAIN / EOF / error: wait for the next packet
            try {
                val pts = f.best_effort_timestamp()
                if (decodePos < 0) {
                    decodePos = if (pts != AV_NOPTS_VALUE) (pts * timeBase * SAMPLE_RATE).toLong().coerceAtLeast(0) else max(discardUntil, 0)
                }
                val n = resample(f)
                if (n > 0) push(gen, n)
            } finally {
                av_frame_unref(f)
            }
        }
    }

    private fun resample(f: AVFrame): Int {
        val channels = f.ch_layout().nb_channels().takeIf { it > 0 } ?: codecCtx?.ch_layout()?.nb_channels() ?: 2
        if (swr == null || f.format() != swrFormat || f.sample_rate() != swrRate || channels != swrChannels) setupResampler(f, channels)
        val s = swr ?: return 0
        val want = (swr_get_delay(s, SAMPLE_RATE.toLong()) + f.nb_samples().toLong() * SAMPLE_RATE / max(1, f.sample_rate()) + 256).toInt()
        ensureOut(want)
        val n = swr_convert(s, outPtrs, outCapacity, f.extended_data(), f.nb_samples())
        if (n < 0) throw DecodeFailure(EngineError(EngineError.Kind.UNSUPPORTED, "Resampling failed"))
        return n
    }

    private fun flushResampler(gen: Int) {
        val s = swr ?: return
        ensureOut(max(4_096, swr_get_delay(s, SAMPLE_RATE.toLong()).toInt() + 256))
        val n = swr_convert(s, outPtrs, outCapacity, null as PointerPointer<*>?, 0)
        if (n > 0) push(gen, n)
    }

    private fun setupResampler(f: AVFrame, channels: Int) {
        swr?.let { swr_free(it) }
        swr = null
        val inLayout = AVChannelLayout().zero<AVChannelLayout>()
        // Zeroed, then defaulted: copy() uninits its destination, and fresh native memory isn't zeroed on Linux/Windows.
        av_channel_layout_default(inLayout, channels)
        if (f.ch_layout().nb_channels() > 0) av_channel_layout_copy(inLayout, f.ch_layout())
        val outLayout = AVChannelLayout().zero<AVChannelLayout>()
        av_channel_layout_default(outLayout, CHANNELS)
        // Must start out null: swr_alloc_set_opts2 reuses (and frees) any context already in the slot.
        val holder = PointerPointer<SwrContext>(1L).put(0L, null as Pointer?)
        try {
            val r = swr_alloc_set_opts2(holder, outLayout, AV_SAMPLE_FMT_FLT, SAMPLE_RATE, inLayout, f.format(), f.sample_rate(), 0, null)
            val ctx = holder.get(SwrContext::class.java, 0)
            if (r < 0 || ctx == null || ctx.isNull || swr_init(ctx) < 0) throw DecodeFailure(EngineError(EngineError.Kind.UNSUPPORTED, "Unsupported sample format"))
            swr = ctx
            swrFormat = f.format(); swrRate = f.sample_rate(); swrChannels = channels
        } finally {
            av_channel_layout_uninit(inLayout); av_channel_layout_uninit(outLayout)
            inLayout.close(); outLayout.close(); holder.close()
        }
    }

    private fun ensureOut(frames: Int) {
        if (frames <= outCapacity) return
        outBuf?.close()
        outCapacity = Integer.highestOneBit(frames) * 2
        outBuf = FloatPointer(outCapacity.toLong() * CHANNELS)
        // Not PointerPointer(outBuf): that constructor reinterprets the buffer itself as the pointer array.
        outPtrs?.close()
        outPtrs = PointerPointer<Pointer>(1L).put(0, outBuf)
        scratch = FloatArray(outCapacity * CHANNELS)
    }

    /** Copies [frames] resampled frames into the ring, dropping what precedes a seek target. */
    private fun push(gen: Int, frames: Int) {
        val buf = outBuf ?: return
        buf.position(0).get(scratch, 0, frames * CHANNELS)
        var from = 0
        var n = frames
        if (discardUntil >= 0) {
            val drop = (discardUntil - decodePos).coerceIn(0, n.toLong()).toInt()
            from += drop; n -= drop
            if (n > 0) discardUntil = -1
        }
        decodePos += frames
        while (n > 0 && !closed) {
            lock.withLock {
                while (!closed && gen == generation && pendingSeekMs < 0 && count == capacity) changed.await()
                if (closed || gen != generation || pendingSeekMs >= 0) return
                val tailIndex = (head + count) % capacity
                val take = min(n, min(capacity - count, capacity - tailIndex))
                System.arraycopy(scratch, from * 2, ring, tailIndex * 2, take * 2)
                count += take; from += take; n -= take
            }
            onProgress()
        }
    }

    private fun classify(code: Int, remote: Boolean, opening: Boolean): EngineError {
        val text = errorText(code)
        val http = code == AVERROR_HTTP_BAD_REQUEST || code == AVERROR_HTTP_UNAUTHORIZED || code == AVERROR_HTTP_FORBIDDEN ||
            code == AVERROR_HTTP_NOT_FOUND || code == AVERROR_HTTP_TOO_MANY_REQUESTS || code == AVERROR_HTTP_OTHER_4XX || code == AVERROR_HTTP_SERVER_ERROR
        val format = code == AVERROR_INVALIDDATA || code == AVERROR_DECODER_NOT_FOUND || code == AVERROR_DEMUXER_NOT_FOUND || code == AVERROR_PROTOCOL_NOT_FOUND
        return when {
            format -> EngineError(EngineError.Kind.UNSUPPORTED, text)
            http -> EngineError(EngineError.Kind.OTHER, text)
            remote -> EngineError(EngineError.Kind.NETWORK, text)
            else -> EngineError(if (opening) EngineError.Kind.OTHER else EngineError.Kind.UNSUPPORTED, text)
        }
    }

    private fun freeNative() {
        runCatching { frame?.let { av_frame_free(it) } }; frame = null
        runCatching { packet?.let { av_packet_free(it) } }; packet = null
        runCatching { swr?.let { swr_free(it) } }; swr = null
        runCatching { codecCtx?.let { avcodec_free_context(it) } }; codecCtx = null
        runCatching { fmt?.let { avformat_close_input(it) } }; fmt = null
        runCatching { outPtrs?.close() }; outPtrs = null
        runCatching { outBuf?.close() }; outBuf = null
        runCatching { interrupt.close() }
    }

    companion object {
        const val LOCAL_BUFFER_SECONDS = 4
        const val REMOTE_BUFFER_SECONDS = 30
        private const val MIN_FREE_FRAMES = SAMPLE_RATE / 4
        private val avcodec_discard_all = org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_ALL
        private val eagain = -(if (System.getProperty("os.name").lowercase().contains("mac")) 35 else 11)

        @Volatile private var networkReady = false
        private fun ensureNetwork() {
            if (networkReady) return
            synchronized(this) {
                if (!networkReady) { avutil.av_log_set_level(avutil.AV_LOG_ERROR); avformat_network_init(); networkReady = true }
            }
        }

        fun errorText(code: Int): String = BytePointer(256L).use { buf ->
            if (av_strerror(code, buf, 256) == 0) buf.string else "error $code"
        }

        /** FFmpeg's file protocol doesn't decode %20 etc, so file: URIs become plain paths. */
        fun ffmpegLocation(uri: String): String = when {
            uri.startsWith("file:") -> runCatching { File(URI(uri)).path }.getOrElse { uri.removePrefix("file://") }
            else -> uri
        }
    }
}

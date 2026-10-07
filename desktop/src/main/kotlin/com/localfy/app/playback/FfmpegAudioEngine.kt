package com.localfy.app.playback

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The desktop audio engine: FFmpeg decoders ([TrackDecoder], one thread each) feed a single audio
 * thread that runs the effect chain (skip silence -> tempo -> normalisation -> crossfade mix ->
 * EQ -> volume -> limiter) and writes 16-bit PCM to an [AudioSink].
 *
 * All state that the audio thread touches is confined to it: public methods only enqueue
 * commands. Listener callbacks go out on a separate event thread.
 */
class FfmpegAudioEngine(private val sink: AudioSink = JavaSoundSink()) : AudioEngine {

    @Volatile override var listener: AudioEngineListener? = null

    // ---- published state (read from any thread)
    @Volatile private var state = PlaybackState.IDLE
    @Volatile private var wantPlay = false
    @Volatile private var uid: Long? = null
    @Volatile private var duration = 0L
    @Volatile private var writtenPosMs = 0L
    @Volatile private var minPosMs = 0L
    @Volatile private var speed = 1f

    override val playbackState get() = state
    override val playWhenReady get() = wantPlay
    override val currentUid get() = uid
    override val durationMs get() = duration
    override val positionMs: Long
        get() {
            pendingPos.let { if (it >= 0) return it }
            val queuedMs = if (sinkOpen) (runCatching { sink.queuedFrames() }.getOrDefault(0) * 1000L / SAMPLE_RATE) else 0L
            val p = max(minPosMs, writtenPosMs - (queuedMs * speed).toLong())
            // Never step backwards within one stretch of playback (the device buffer fills in bursts).
            val floor = reported
            if (floor.first == epoch && p < floor.second && floor.second - p < 250) return floor.second
            reported = epoch to p
            return p
        }
    /** Bumped on every jump (load, seek, track change) so [positionMs] may go backwards then. */
    @Volatile private var epoch = 0
    /** Target of a load/seek the audio thread hasn't carried out yet. */
    @Volatile private var pendingPos = -1L
    @Volatile private var reported = -1 to 0L

    // ---- effect settings (read by the audio thread)
    @Volatile private var skipSilence = false
    @Volatile private var normalize = true
    @Volatile private var volume = 1f
    @Volatile private var sleepGain = 1f
    @Volatile private var eq: EqState = EqState()

    private val commands = LinkedBlockingQueue<() -> Unit>()
    private val wakePending = AtomicBoolean(false)
    private val events: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "spitify-player-events").apply { isDaemon = true } }
    @Volatile private var released = false
    private val thread = Thread(::audioLoop, "spitify-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }

    init { thread.start() }

    // ------------------------------------------------------------ public API (enqueue only)

    override fun load(track: EngineTrack, startMs: Long, play: Boolean) {
        // Visible right away, before the audio thread gets to the command.
        val start = startMs.coerceAtLeast(0)
        pendingPos = start; uid = track.uid; duration = 0
        command { loadNow(track, start, play) }
    }

    private fun loadNow(track: EngineTrack, startMs: Long, play: Boolean) {
        fadeOutIfAudible()
        dropAll()
        wantPlay = play
        current = Voice(track, startMs.coerceAtLeast(0)).also { it.start() }
        uid = track.uid
        duration = 0
        writtenPosMs = startMs.coerceAtLeast(0); minPosMs = writtenPosMs; epoch++
        appliedGain = 0f
        pendingPos.let { if (it == writtenPosMs) pendingPos = -1 }
        setState(PlaybackState.BUFFERING)
    }

    override fun setNext(track: EngineTrack?, crossfadeMs: Int) = command {
        if (track?.uid == nextTrack?.uid && crossfadeMs == nextCrossfadeMs) return@command
        if (nextVoice != null && nextVoice?.track?.uid != track?.uid) { nextVoice?.close(); nextVoice = null }
        nextTrack = track
        nextCrossfadeMs = crossfadeMs.coerceIn(0, 12_000)
        // The previous track ended before its successor was known: carry on now.
        if (track != null && current == null && state == PlaybackState.ENDED && wantPlay) advance()
    }

    override fun play() = command {
        wantPlay = true
        pausing = false
        if (current != null && state == PlaybackState.READY) publish()
    }

    override fun pause() = command {
        if (!wantPlay) return@command
        wantPlay = false
        dropTail()
        // Fade the next block out, then drain and stop the line (see audioLoop).
        pausing = sinkRunning && current != null
        publish()
    }

    override fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0).let { if (duration > 0) min(it, max(0, duration - 1)) else it }
        if (uid != null) pendingPos = target
        command { seekNow(target) }
    }

    private fun seekNow(target: Long) {
        if (pendingPos == target) pendingPos = -1
        val v = current ?: return
        fadeOutIfAudible()
        dropTail()
        v.seek(target)
        writtenPosMs = target; minPosMs = target; epoch++
        appliedGain = 0f
        if (state == PlaybackState.READY) setState(PlaybackState.BUFFERING)
    }

    override fun stop() = command {
        pendingPos = -1
        dropAll()
        wantPlay = false
        uid = null; duration = 0; writtenPosMs = 0; minPosMs = 0; epoch++
        stopSink(flush = true)
        setState(PlaybackState.IDLE)
    }

    override fun setSpeed(speed: Float) { this.speed = speed.coerceIn(0.25f, 4f); wake() }
    override fun setSkipSilence(enabled: Boolean) { skipSilence = enabled }
    override fun setNormalize(enabled: Boolean) { normalize = enabled }
    override fun setVolume(volume: Float) { this.volume = safeGain(volume) }
    override fun setSleepGain(gain: Float) { sleepGain = safeGain(gain) }
    override fun setEq(state: EqState) { eq = state }

    override fun release() {
        if (released) return
        command {
            dropAll()
            runCatching { sink.close() }
            sinkOpen = false; sinkRunning = false
            released = true
        }
        events.shutdown()
    }

    private fun command(block: () -> Unit) {
        if (released) return
        commands.offer(block)
    }

    /** Called by decoders when data/end/error arrives; collapses into one wake-up. */
    private fun wake() {
        if (wakePending.compareAndSet(false, true)) commands.offer { wakePending.set(false) }
    }

    private fun post(block: (AudioEngineListener) -> Unit) {
        val l = listener ?: return
        if (!events.isShutdown) runCatching { events.execute { runCatching { block(l) } } }
    }

    // ------------------------------------------------------------ audio thread

    private inner class Voice(val track: EngineTrack, startMs: Long) {
        val decoder = TrackDecoder(track, startMs) { wake() }
        val normalizer = Normalizer(decoder) { normalize }
        val skipper = SilenceSkipper(normalizer) { skipSilence }
        val stretcher = TimeStretcher(skipper)
        var durationReported = false
        fun start() = decoder.start()
        fun read(dst: FloatArray, offset: Int, frames: Int): Int { stretcher.speed = speed; return stretcher.read(dst, offset, frames) }
        val positionMs: Long get() = ((decoder.positionFrames - stretcher.bufferedSourceFrames).coerceAtLeast(0) * 1000 / SAMPLE_RATE)
        val ready: Boolean get() = decoder.status == TrackDecoder.Status.READY && (decoder.bufferedFrames > 0 || decoder.decodedToEnd)
        fun seek(ms: Long) { decoder.seek(ms); normalizer.reset(); skipper.reset(); stretcher.reset() }
        fun close() = decoder.close()
    }

    private var current: Voice? = null
    private var tail: Voice? = null
    private var nextVoice: Voice? = null
    private var nextTrack: EngineTrack? = null
    private var nextCrossfadeMs = 0
    private var fadeTotal = 0
    private var fadeDone = 0
    private var pausing = false
    private var endOfQueue = false
    private var appliedGain = 0f
    @Volatile private var sinkOpen = false
    private var sinkRunning = false
    private val eqProcessor = EqProcessor()

    private val mix = FloatArray(BLOCK * CHANNELS)
    private val scratch = FloatArray(BLOCK * CHANNELS)
    private val pcm = ByteArray(BLOCK * CHANNELS * 2)

    private fun audioLoop() {
        while (!released) {
            try {
                val active = rendering()
                var cmd = if (active) commands.poll() else commands.poll(1, TimeUnit.SECONDS)
                while (cmd != null) { cmd(); cmd = commands.poll() }
                if (released) break
                checkVoices()
                if (!rendering()) continue
                val n = render()
                if (n > 0) {
                    if (state == PlaybackState.BUFFERING || state == PlaybackState.IDLE) setState(PlaybackState.READY)
                    output(n)
                    if (pausing && appliedGain <= 0f) finishPause()
                }
                if (endOfQueue) {
                    endOfQueue = false
                    onEnded()
                } else if (n == 0 && current != null) {
                    // Starved: decoders wake us when data arrives (or after a short timeout).
                    if (state == PlaybackState.READY) setState(PlaybackState.BUFFERING)
                    commands.poll(20, TimeUnit.MILLISECONDS)?.invoke()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                // Never let one bad buffer kill playback: report and drop the track.
                val failed = uid
                dropAll()
                setState(PlaybackState.IDLE)
                if (failed != null) post { it.onError(failed, EngineError(EngineError.Kind.OTHER, e.message ?: e.javaClass.simpleName)) }
            }
        }
        dropAll()
        runCatching { sink.close() }
    }

    private fun rendering(): Boolean = (current != null || endOfQueue) && (wantPlay || pausing) && state != PlaybackState.ENDED

    /** Errors, durations and READY-while-paused, outside of rendering. */
    private fun checkVoices() {
        val v = current ?: return
        if (v.decoder.status == TrackDecoder.Status.FAILED) {
            val err = v.decoder.error ?: EngineError(EngineError.Kind.OTHER, "Playback failed")
            dropAll()
            setState(PlaybackState.IDLE)
            post { it.onError(v.track.uid, err) }
            return
        }
        if (!v.durationReported && v.decoder.durationMs > 0) {
            v.durationReported = true
            duration = v.decoder.durationMs
            post { it.onDuration(v.track.uid, v.decoder.durationMs) }
        }
        if (!wantPlay && !pausing) {
            val s = if (v.ready) PlaybackState.READY else PlaybackState.BUFFERING
            if (s != state) setState(s)
        }
        tail?.let { if (it.decoder.status == TrackDecoder.Status.FAILED) dropTail() }
    }

    /** Fills [mix] with up to [BLOCK] frames; returns frames produced (0 = starved). */
    private fun render(): Int {
        var fill = 0
        while (fill < BLOCK) {
            val cur = current ?: break
            maybePreload(cur)
            if (fill == 0) maybeStartCrossfade(cur)
            val v = current ?: break
            val r = v.read(mix, fill, BLOCK - fill)
            when {
                r > 0 -> fill += r
                r == 0 -> break
                v.decoder.status == TrackDecoder.Status.FAILED -> break // reported by checkVoices
                else -> { if (!advance()) break }
            }
        }
        if (fill > 0) {
            writtenPosMs = current?.positionMs ?: writtenPosMs
            mixTail(fill)
        }
        return fill
    }

    private fun maybePreload(cur: Voice) {
        val next = nextTrack ?: return
        if (nextVoice != null) return
        val remaining = if (duration > 0) duration - cur.positionMs else Long.MAX_VALUE
        if (cur.decoder.decodedToEnd || remaining < nextCrossfadeMs + PRELOAD_MS) {
            nextVoice = Voice(next, 0).also { it.start() }
        }
    }

    private fun maybeStartCrossfade(cur: Voice) {
        val cf = nextCrossfadeMs
        if (cf <= 0 || tail != null || !wantPlay || pausing || nextTrack == null) return
        val dur = duration
        if (dur <= 0 || dur < cf * 2L + 2_000) return
        val remaining = dur - cur.positionMs
        if (remaining > cf) return
        val next = nextVoice ?: return
        if (!next.ready) return
        tail = cur
        fadeTotal = max(SAMPLE_RATE / 2, (remaining * SAMPLE_RATE / 1000 / speed).roundToInt())
        fadeDone = 0
        current = null
        advance()
    }

    /** Moves on to the queued next track (gapless). Returns false at the end of the queue. */
    private fun advance(): Boolean {
        val old = current
        val next = nextTrack
        if (old != null && old !== tail) old.close()
        current = null
        if (next == null) {
            nextVoice?.close(); nextVoice = null
            endOfQueue = true // finished after this block is written
            return false
        }
        val v = nextVoice?.takeIf { it.track.uid == next.uid } ?: Voice(next, 0).also { it.start() }
        nextVoice = null
        nextTrack = null
        current = v
        uid = v.track.uid
        duration = v.decoder.durationMs
        v.durationReported = false
        writtenPosMs = 0; minPosMs = 0; epoch++
        post { it.onAutoTransition(v.track.uid) }
        return true
    }

    private fun onEnded() {
        dropTail()
        if (sinkRunning) { runCatching { sink.drain() }; stopSink(flush = false) }
        writtenPosMs = duration.takeIf { it > 0 } ?: writtenPosMs
        setState(PlaybackState.ENDED)
    }

    /** Mixes the outgoing track of a crossfade under [fill] frames of the incoming one. */
    private fun mixTail(fill: Int) {
        val t = tail ?: return
        var got = 0
        while (got < fill) {
            val r = t.read(scratch, got, fill - got)
            if (r <= 0) break
            got += r
        }
        if (got < fill) java.util.Arrays.fill(scratch, got * 2, fill * 2, 0f)
        for (i in 0 until fill) {
            val (gIn, gOut) = crossfadeGains((fadeDone + i).toFloat() / fadeTotal)
            mix[i * 2] = mix[i * 2] * gIn + scratch[i * 2] * gOut
            mix[i * 2 + 1] = mix[i * 2 + 1] * gIn + scratch[i * 2 + 1] * gOut
        }
        fadeDone += fill
        if (fadeDone >= fadeTotal || got < fill) dropTail()
    }

    /** EQ, volume (smoothed, so pause/resume/seek never click), limiter, then to the sink. */
    private fun output(frames: Int) {
        eqProcessor.configure(eq)
        eqProcessor.process(mix, 0, frames)
        val target = if (pausing) 0f else volume * sleepGain
        var g = appliedGain
        val maxStep = 1f / FADE_FRAMES
        for (i in 0 until frames) {
            g = if (abs(target - g) <= maxStep) target else g + if (target > g) maxStep else -maxStep
            val l = (mix[i * 2] * g).coerceIn(-1f, 1f)
            val r = (mix[i * 2 + 1] * g).coerceIn(-1f, 1f)
            val sl = (l * 32767f).roundToInt()
            val sr = (r * 32767f).roundToInt()
            pcm[i * 4] = sl.toByte(); pcm[i * 4 + 1] = (sl shr 8).toByte()
            pcm[i * 4 + 2] = sr.toByte(); pcm[i * 4 + 3] = (sr shr 8).toByte()
        }
        appliedGain = g
        if (!ensureSink()) return
        sink.write(pcm, frames * 4)
    }

    private fun ensureSink(): Boolean {
        if (!sinkOpen) {
            try { sink.open(); sinkOpen = true } catch (e: Exception) {
                val failed = uid
                dropAll()
                wantPlay = false
                setState(PlaybackState.IDLE)
                if (failed != null) post { it.onError(failed, EngineError(EngineError.Kind.OTHER, "No audio output: ${e.message}")) }
                return false
            }
        }
        if (!sinkRunning) { sink.start(); sinkRunning = true }
        return true
    }

    private fun finishPause() {
        pausing = false
        runCatching { sink.drain() }
        stopSink(flush = false)
        writtenPosMs = current?.positionMs ?: writtenPosMs
        publish()
    }

    private fun stopSink(flush: Boolean) {
        if (!sinkOpen) return
        runCatching { sink.stop(); if (flush) sink.flush() }
        sinkRunning = false
    }

    /** Before a jump (new track / seek) while audible: a 10 ms fade of the old audio, not a cut. */
    private fun fadeOutIfAudible() {
        val v = current ?: return
        if (!sinkRunning || !wantPlay || appliedGain <= 0f) return
        val n = v.read(mix, 0, FADE_FRAMES).coerceAtLeast(0)
        if (n == 0) return
        mixTail(n)
        pausing = true
        output(n)
        pausing = false
    }

    private fun dropTail() {
        tail?.close(); tail = null
        fadeTotal = 0; fadeDone = 0
    }

    private fun dropAll() {
        dropTail()
        current?.close(); current = null
        nextVoice?.close(); nextVoice = null
        pausing = false
        endOfQueue = false
    }

    private fun setState(s: Int) {
        state = s
        publish()
    }

    private var lastPublished: Pair<Int, Boolean>? = null
    private fun publish() {
        val now = state to wantPlay
        if (now == lastPublished) return
        lastPublished = now
        post { it.onStateChanged(now.first, now.second) }
    }

    companion object {
        private const val BLOCK = 1_024
        private const val FADE_FRAMES = SAMPLE_RATE / 100 // 10 ms
        /** Open the next track this long before the current one ends (plus the crossfade). */
        private const val PRELOAD_MS = 20_000L
    }
}

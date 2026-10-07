package com.localfy.app.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The engine mixes everything at 48 kHz interleaved stereo float. */
internal const val SAMPLE_RATE = 48_000
internal const val CHANNELS = 2

/**
 * A pull stage of the audio pipeline. [read] fills [dst] (interleaved stereo) from frame [offset]
 * and returns frames written, 0 when nothing is available right now (starved), or -1 at the end.
 */
internal interface PcmSource {
    fun read(dst: FloatArray, offset: Int, frames: Int): Int
}

// ---------------------------------------------------------------- crossfade / gains

/**
 * Equal-power crossfade gains at progress [p] (0..1): incoming = sin, outgoing = cos, so
 * in^2 + out^2 = 1 and uncorrelated songs keep a constant loudness through the fade.
 */
internal fun crossfadeGains(p: Float): Pair<Float, Float> {
    val x = p.coerceIn(0f, 1f) * (PI.toFloat() / 2f)
    return sin(x) to cos(x)
}

internal fun safeGain(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
internal fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

// ---------------------------------------------------------------- biquads / EQ

/** Normalised biquad coefficients (a0 = 1): y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2. */
internal data class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** Magnitude response in dB at [hz] (used by tests and to sanity-check presets). */
    fun gainDbAt(hz: Double, fs: Double = SAMPLE_RATE.toDouble()): Double {
        val w = 2 * PI * hz / fs
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2; val ni = -(b1 * sw + b2 * s2)
        val dr = 1 + a1 * cw + a2 * c2; val di = -(a1 * sw + a2 * s2)
        return 10 * kotlin.math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        /** RBJ cookbook peaking EQ. */
        fun peaking(fs: Double, f0: Double, q: Double, gainDb: Double): BiquadCoefficients {
            if (abs(gainDb) < 1e-6) return IDENTITY
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * f0 / fs
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val a0 = 1 + alpha / a
            return BiquadCoefficients((1 + alpha * a) / a0, (-2 * cw) / a0, (1 - alpha * a) / a0, (-2 * cw) / a0, (1 - alpha / a) / a0)
        }

        /** RBJ cookbook low shelf (shelf slope 1). */
        fun lowShelf(fs: Double, f0: Double, gainDb: Double): BiquadCoefficients {
            if (abs(gainDb) < 1e-6) return IDENTITY
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * f0 / fs
            val cw = cos(w0)
            val alpha = sin(w0) / 2 * sqrt(2.0)
            val sa = 2 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * cw + sa
            return BiquadCoefficients(
                a * ((a + 1) - (a - 1) * cw + sa) / a0,
                2 * a * ((a - 1) - (a + 1) * cw) / a0,
                a * ((a + 1) - (a - 1) * cw - sa) / a0,
                -2 * ((a - 1) + (a + 1) * cw) / a0,
                ((a + 1) + (a - 1) * cw - sa) / a0,
            )
        }
    }
}

/** A stereo biquad (transposed direct form II). */
internal class StereoBiquad(var c: BiquadCoefficients = BiquadCoefficients.IDENTITY) {
    private var z1l = 0.0; private var z2l = 0.0; private var z1r = 0.0; private var z2r = 0.0

    fun process(buf: FloatArray, offset: Int, frames: Int) {
        if (c === BiquadCoefficients.IDENTITY) return
        val b0 = c.b0; val b1 = c.b1; val b2 = c.b2; val a1 = c.a1; val a2 = c.a2
        var i = offset * 2
        val end = (offset + frames) * 2
        while (i < end) {
            val l = buf[i].toDouble()
            val yl = b0 * l + z1l
            z1l = b1 * l - a1 * yl + z2l
            z2l = b2 * l - a2 * yl
            buf[i] = yl.toFloat()
            val r = buf[i + 1].toDouble()
            val yr = b0 * r + z1r
            z1r = b1 * r - a1 * yr + z2r
            z2r = b2 * r - a2 * yr
            buf[i + 1] = yr.toFloat()
            i += 2
        }
        // Flush denormals so a long silence doesn't make the filters crawl.
        if (abs(z1l) < 1e-25) z1l = 0.0; if (abs(z2l) < 1e-25) z2l = 0.0
        if (abs(z1r) < 1e-25) z1r = 0.0; if (abs(z2r) < 1e-25) z2r = 0.0
    }

    fun reset() { z1l = 0.0; z2l = 0.0; z1r = 0.0; z2r = 0.0 }
}

/**
 * The desktop effect chain driven by [EqStore]: 10 peaking bands at [EqFrequencies] (one octave
 * wide), a low-shelf bass boost, mid/side widening for "surround", pre-gain and a peak limiter.
 */
internal class EqProcessor {
    private val bands = List(EqFrequencies.size) { StereoBiquad() }
    private val bassShelf = StereoBiquad()
    private var state = EqState()
    private var preGain = 1f
    private val limiter = PeakLimiter()

    fun configure(s: EqState) {
        if (s == state) return
        val wasEnabled = state.enabled
        state = s
        val fs = SAMPLE_RATE.toDouble()
        EqFrequencies.forEachIndexed { i, f ->
            bands[i].c = if (s.enabled) BiquadCoefficients.peaking(fs, f.toDouble(), BAND_Q, s.gains.getOrElse(i) { 0f }.toDouble()) else BiquadCoefficients.IDENTITY
        }
        bassShelf.c = if (s.enabled && s.bass > 0f) BiquadCoefficients.lowShelf(fs, 100.0, (s.bass * MAX_BASS_DB).toDouble()) else BiquadCoefficients.IDENTITY
        // Pre-gain for "loudness", minus headroom for boosted bands (Android's formula).
        preGain = if (s.enabled) dbToLinear(s.loudnessDb - max(0f, s.gains.maxOrNull() ?: 0f) * 0.5f) else 1f
        limiter.thresholdDb = if (s.enabled && s.limiter) -1f else 0f
        if (!wasEnabled && s.enabled) { bands.forEach { it.reset() }; bassShelf.reset() }
    }

    fun process(buf: FloatArray, offset: Int, frames: Int) {
        val s = state
        if (s.enabled) {
            bassShelf.process(buf, offset, frames)
            for (b in bands) b.process(buf, offset, frames)
            val width = 1f + s.surround
            val g = preGain
            if (width != 1f || g != 1f) {
                var i = offset * 2
                val end = (offset + frames) * 2
                while (i < end) {
                    val mid = (buf[i] + buf[i + 1]) * 0.5f
                    val side = (buf[i] - buf[i + 1]) * 0.5f * width
                    buf[i] = (mid + side) * g
                    buf[i + 1] = (mid - side) * g
                    i += 2
                }
            }
        }
        limiter.process(buf, offset, frames)
    }

    companion object {
        /** One-octave bandwidth. */
        const val BAND_Q = 1.414
        const val MAX_BASS_DB = 12f
    }
}

/** Instant-attack, 60 ms release peak limiter followed by a hard clip at full scale. */
internal class PeakLimiter {
    var thresholdDb = 0f
    private var gain = 1f
    private val release = (1.0 - kotlin.math.exp(-1.0 / (0.060 * SAMPLE_RATE))).toFloat()

    fun process(buf: FloatArray, offset: Int, frames: Int) {
        val thr = dbToLinear(thresholdDb)
        var i = offset * 2
        val end = (offset + frames) * 2
        while (i < end) {
            val peak = max(abs(buf[i]), abs(buf[i + 1]))
            val target = if (peak * gain > thr) thr / peak else 1f
            gain = if (target < gain) target else gain + (target - gain) * release
            buf[i] = (buf[i] * gain).coerceIn(-1f, 1f)
            buf[i + 1] = (buf[i + 1] * gain).coerceIn(-1f, 1f)
            i += 2
        }
    }
}

// ---------------------------------------------------------------- loudness normalisation

/** A running RMS estimate; attenuation only, with no gain recovery that could cause pumping (Android port). */
internal class NormalizationLevel {
    private var energy = 0.0
    private var count = 0L
    private var gain = 1.0
    fun measure(blockEnergy: Double, samples: Int, peak: Double = 0.0): Double {
        if (!blockEnergy.isFinite() || blockEnergy < 0 || samples <= 0) return 0.0
        energy += blockEnergy; count += samples
        val rms = sqrt(energy / count)
        if (rms > 0) gain = min(gain, TARGET_RMS / rms).coerceIn(0.0, 1.0)
        // A quiet intro must not hide a sudden noisy block inside a long running average.
        val blockRms = sqrt(blockEnergy / samples)
        if (blockRms > 0) gain = min(gain, MAX_SHORT_RMS / blockRms)
        if (peak > 0) gain = min(gain, MAX_PEAK / peak)
        return gain
    }
    fun reset() { energy = 0.0; count = 0; gain = 1.0 }
    companion object {
        private const val TARGET_RMS = 0.12589254117941673 // -18 dBFS
        private const val MAX_SHORT_RMS = 0.251188643150958 // -12 dBFS
        private const val MAX_PEAK = 0.8912509381337456 // -1 dBFS
    }
}

/** Measures decoded audio and turns loud recordings down (never boosts), ramping between blocks. */
internal class Normalizer(private val upstream: PcmSource, private val enabled: () -> Boolean) : PcmSource {
    private val level = NormalizationLevel()
    private var lastGain = 1f

    override fun read(dst: FloatArray, offset: Int, frames: Int): Int {
        val n = upstream.read(dst, offset, frames)
        if (n <= 0) return n
        val target = if (enabled()) {
            var energy = 0.0
            var peak = 0.0
            for (i in offset * 2 until (offset + n) * 2) { val s = dst[i].toDouble(); energy += s * s; peak = max(peak, abs(s)) }
            level.measure(energy, n * 2, peak).toFloat()
        } else 1f
        val from = lastGain
        if (from == 1f && target == 1f) return n
        val step = (target - from) / n
        var g = from
        var i = offset * 2
        for (f in 0 until n) { g += step; dst[i] *= g; dst[i + 1] *= g; i += 2 }
        lastGain = target
        return n
    }

    fun reset() { level.reset(); lastGain = 1f }
}

// ---------------------------------------------------------------- skip silence

/**
 * Drops near-silent stretches (spoken word pauses). The first [MIN_SILENCE_FRAMES] of every
 * silence are kept so speech keeps its rhythm; the rest is skipped until sound returns.
 */
internal class SilenceSkipper(private val upstream: PcmSource, private val enabled: () -> Boolean) : PcmSource {
    private val chunk = FloatArray(CHUNK * CHANNELS)
    private var chunkLen = 0
    private var chunkPos = 0
    private var silentRun = 0
    /** Frames dropped so far (the decoder's position already includes them). */
    var skippedFrames = 0L; private set

    override fun read(dst: FloatArray, offset: Int, frames: Int): Int {
        if (!enabled() && chunkPos >= chunkLen) { silentRun = 0; return upstream.read(dst, offset, frames) }
        var written = 0
        while (written < frames) {
            if (chunkPos >= chunkLen) {
                val n = upstream.read(chunk, 0, CHUNK)
                if (n <= 0) return if (written > 0) written else n
                chunkLen = n; chunkPos = 0
                var peak = 0f
                for (i in 0 until n * 2) peak = max(peak, abs(chunk[i]))
                if (peak < THRESHOLD && enabled()) {
                    silentRun += n
                    if (silentRun > MIN_SILENCE_FRAMES) { skippedFrames += n; chunkLen = 0; continue }
                } else silentRun = 0
            }
            val take = min(frames - written, chunkLen - chunkPos)
            System.arraycopy(chunk, chunkPos * 2, dst, (offset + written) * 2, take * 2)
            chunkPos += take; written += take
        }
        return written
    }

    fun reset() { chunkLen = 0; chunkPos = 0; silentRun = 0; skippedFrames = 0 }

    companion object {
        const val CHUNK = SAMPLE_RATE / 100 // 10 ms
        const val MIN_SILENCE_FRAMES = SAMPLE_RATE * 150 / 1000
        /** Media3's default silence level (1024 / 32768, about -30 dBFS). */
        const val THRESHOLD = 1024f / 32768f
    }
}

// ---------------------------------------------------------------- tempo (WSOLA)

/**
 * Pitch-preserving speed change by WSOLA: Hann-windowed frames overlap-added at a fixed output
 * hop, each taken from the input near where [speed] says it should be, nudged (within
 * [TOLERANCE]) to the offset that best continues the previous frame's waveform.
 */
internal class TimeStretcher(private val upstream: PcmSource) : PcmSource {
    @Volatile var speed = 1f

    private var input = FloatArray(16_384 * CHANNELS)
    private var inLen = 0 // frames held in [input]
    private var pos = 0.0 // analysis position of the next frame, in frames of [input]
    private var prevStart = -1
    private val tail = FloatArray(HOP * CHANNELS)
    private val out = FloatArray(HOP * CHANNELS)
    private var outLen = 0
    private var outPos = 0
    private var upstreamEnded = false
    private var active = false
    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / FRAME)).toFloat() }

    /** Source frames read from upstream that haven't been heard yet (for position reporting). */
    val bufferedSourceFrames: Long
        get() = if (!active) 0 else ((inLen - pos) + (outLen - outPos) * speed).toLong().coerceAtLeast(0)

    override fun read(dst: FloatArray, offset: Int, frames: Int): Int {
        if (!active && abs(speed - 1f) < 0.001f) return upstream.read(dst, offset, frames)
        active = true
        var written = 0
        while (written < frames) {
            if (outPos >= outLen) {
                val r = step()
                if (r <= 0) return if (written > 0) written else r
            }
            val take = min(frames - written, outLen - outPos)
            System.arraycopy(out, outPos * 2, dst, (offset + written) * 2, take * 2)
            outPos += take; written += take
        }
        return written
    }

    /** Produces the next [HOP] output frames into [out]; 0 if starved, -1 at the end. */
    private fun step(): Int {
        val nominal = pos.roundToInt()
        val need = nominal + TOLERANCE + FRAME
        while (inLen < need && !upstreamEnded) {
            ensureCapacity(need)
            val n = upstream.read(input, inLen, min(need - inLen, input.size / 2 - inLen))
            if (n < 0) upstreamEnded = true
            else if (n == 0) return 0
            else inLen += n
        }
        if (inLen < need) {
            // End of input: let the last frame's tail fade out, then finish.
            if (prevStart < 0 && inLen == 0) return -1
            if (prevStart == -2) return -1
            System.arraycopy(tail, 0, out, 0, HOP * 2)
            outLen = HOP; outPos = 0
            prevStart = -2
            return HOP
        }
        val start = if (prevStart < 0) nominal else bestOffset(nominal)
        for (i in 0 until HOP) {
            val w = window[i]
            out[i * 2] = tail[i * 2] + input[(start + i) * 2] * w
            out[i * 2 + 1] = tail[i * 2 + 1] + input[(start + i) * 2 + 1] * w
            val w2 = window[HOP + i]
            tail[i * 2] = input[(start + HOP + i) * 2] * w2
            tail[i * 2 + 1] = input[(start + HOP + i) * 2 + 1] * w2
        }
        outLen = HOP; outPos = 0
        prevStart = start
        pos += HOP * speed.coerceIn(0.25f, 4f)
        compact()
        return HOP
    }

    /** The start near [nominal] whose first half best matches what naturally followed the previous frame. */
    private fun bestOffset(nominal: Int): Int {
        val target = prevStart + HOP
        var best = nominal
        var bestScore = Double.NEGATIVE_INFINITY
        var d = -TOLERANCE
        while (d <= TOLERANCE) {
            val s = nominal + d
            if (s >= 0) {
                var corr = 0.0
                var energy = 1e-9
                var i = 0
                while (i < HOP) {
                    val a = input[(target + i) * 2] + input[(target + i) * 2 + 1]
                    val b = input[(s + i) * 2] + input[(s + i) * 2 + 1]
                    corr += a * b; energy += b * b
                    i += 4
                }
                val score = corr / sqrt(energy)
                if (score > bestScore) { bestScore = score; best = s }
            }
            d += 2
        }
        return best
    }

    private fun ensureCapacity(frames: Int) {
        if (frames * 2 <= input.size) return
        input = input.copyOf(Integer.highestOneBit(frames * 2) * 2)
    }

    private fun compact() {
        val drop = min(prevStart, pos.toInt() - TOLERANCE)
        if (drop < FRAME * 4) return
        System.arraycopy(input, drop * 2, input, 0, (inLen - drop) * 2)
        inLen -= drop; pos -= drop; prevStart -= drop
    }

    fun reset() {
        inLen = 0; pos = 0.0; prevStart = -1; outLen = 0; outPos = 0
        tail.fill(0f); upstreamEnded = false; active = false
    }

    companion object {
        const val FRAME = 1_536 // 32 ms
        const val HOP = FRAME / 2
        const val TOLERANCE = 384 // 8 ms
    }
}

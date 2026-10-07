package com.localfy.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    @Test fun crossfadeIsEqualPower() {
        assertEquals(0f, crossfadeGains(0f).first, 1e-6f)
        assertEquals(1f, crossfadeGains(0f).second, 1e-6f)
        assertEquals(1f, crossfadeGains(1f).first, 1e-6f)
        assertEquals(0f, crossfadeGains(1f).second, 1e-6f)
        val (i, o) = crossfadeGains(0.5f)
        assertEquals(sqrt(0.5f), i, 1e-5f)
        assertEquals(i, o, 1e-6f)
        var last = -1f
        for (k in 0..100) {
            val (gi, go) = crossfadeGains(k / 100f)
            assertEquals(1f, gi * gi + go * go, 1e-5f)
            assertTrue(gi >= last)
            last = gi
        }
        // Out-of-range progress is clamped.
        assertEquals(1f, crossfadeGains(3f).first, 1e-6f)
    }

    @Test fun peakingBiquadHitsItsGainAtTheCentreAndIsFlatFarAway() {
        for (f in EqFrequencies) {
            val c = BiquadCoefficients.peaking(48_000.0, f.toDouble(), EqProcessor.BAND_Q, 6.0)
            assertEquals("gain at $f", 6.0, c.gainDbAt(f.toDouble()), 0.05)
            val far = if (f < 1_000) f * 16.0 else f / 16.0
            assertTrue("flat far from $f", abs(c.gainDbAt(far)) < 0.6)
            // Stable: poles inside the unit circle.
            assertTrue(abs(c.a2) < 1 && abs(c.a1) < 1 + c.a2)
        }
        val cut = BiquadCoefficients.peaking(48_000.0, 1_000.0, EqProcessor.BAND_Q, -9.0)
        assertEquals(-9.0, cut.gainDbAt(1_000.0), 0.05)
        assertTrue(BiquadCoefficients.peaking(48_000.0, 1_000.0, 1.0, 0.0) === BiquadCoefficients.IDENTITY)
    }

    @Test fun lowShelfBoostsBassOnly() {
        val c = BiquadCoefficients.lowShelf(48_000.0, 100.0, 12.0)
        assertEquals(12.0, c.gainDbAt(10.0), 0.3)
        assertEquals(0.0, c.gainDbAt(5_000.0), 0.1)
    }

    @Test fun presetsHaveTenBands() {
        assertEquals(10, EqFrequencies.size)
        EqPresets.forEach { assertEquals(it.name, 10, it.gains.size) }
        assertEquals(3f, curveAt(EqPresets.first { it.name == "Jazz" }.gains, 31f), 1e-6f)
        assertEquals(0f, curveAt(EqPresets.first().gains, 440f), 1e-6f)
    }

    @Test fun eqProcessorAppliesBandGainToASine() {
        val eq = EqProcessor()
        eq.configure(EqState(enabled = true, gains = List(10) { if (it == 5) 6f else 0f }, limiter = false))
        val n = 48_000
        val buf = FloatArray(n * 2) { val t = (it / 2).toDouble(); (0.1 * sin(2 * PI * 1_000 * t / 48_000)).toFloat() }
        eq.process(buf, 0, n)
        // Ignore the first 100 ms (filter settling); preGain subtracts half the max boost (3 dB).
        val rms = sqrt((n / 10 until n).sumOf { (buf[it * 2] * buf[it * 2]).toDouble() } / (n - n / 10))
        val expected = 0.1 / sqrt(2.0) * dbToLinear(6f - 3f)
        assertEquals(expected, rms, expected * 0.03)
    }

    @Test fun limiterKeepsPeaksBelowFullScale() {
        val eq = EqProcessor()
        val buf = FloatArray(2_000) { if (it % 2 == 0) 1.8f else -1.5f }
        eq.process(buf, 0, 1_000)
        assertTrue(buf.all { abs(it) <= 1f })
    }

    @Test fun normalizationOnlyAttenuates() {
        val level = NormalizationLevel()
        assertEquals(1.0, level.measure(0.0001 * 1000, 1000, 0.01), 1e-9) // quiet: untouched
        val loud = NormalizationLevel()
        val g = loud.measure(0.5 * 0.5 * 1000, 1000, 0.5)
        assertTrue(g < 1.0)
        assertEquals(0.12589254117941673 / 0.5, g, 1e-6)
    }

    @Test fun silenceSkipperDropsLongPausesOnly() {
        // 1 s tone, 1 s silence, 1 s tone.
        val frames = 48_000 * 3
        val data = FloatArray(frames * 2) { i -> val f = i / 2; if (f in 48_000 until 96_000) 0f else 0.5f }
        val source = ArraySource(data)
        var on = true
        val skipper = SilenceSkipper(source) { on }
        val out = drain(skipper)
        val expected = 96_000 + SilenceSkipper.MIN_SILENCE_FRAMES
        assertTrue("got $out", abs(out - expected) <= SilenceSkipper.CHUNK)
        on = false
        assertEquals(frames, drain(SilenceSkipper(ArraySource(data)) { on }))
    }

    @Test fun timeStretcherChangesLengthNotPitch() {
        val frames = 48_000 * 2
        val data = FloatArray(frames * 2) { val t = (it / 2).toDouble(); (0.5 * sin(2 * PI * 440 * t / 48_000)).toFloat() }
        val stretcher = TimeStretcher(ArraySource(data)).apply { speed = 1.5f }
        val out = ArrayList<Float>()
        val buf = FloatArray(1_000 * 2)
        while (true) {
            val n = stretcher.read(buf, 0, 1_000)
            if (n < 0) break
            for (i in 0 until n * 2) out += buf[i]
        }
        val outFrames = out.size / 2
        assertEquals(frames / 1.5, outFrames.toDouble(), frames * 0.03)
        // Pitch check: count zero crossings in the steady middle of the output.
        val mid = (outFrames / 4 until outFrames * 3 / 4)
        var crossings = 0
        for (f in mid) if ((out[f * 2] >= 0) != (out[(f + 1) * 2] >= 0)) crossings++
        val hz = crossings / 2.0 / (mid.count() / 48_000.0)
        assertEquals(440.0, hz, 15.0)
    }

    @Test fun timeStretcherAtNormalSpeedIsAPassThrough() {
        val data = FloatArray(4_000) { it.toFloat() }
        val s = TimeStretcher(ArraySource(data))
        val buf = FloatArray(4_000)
        assertEquals(2_000, s.read(buf, 0, 2_000))
        assertTrue(data.contentEquals(buf))
    }

    private fun drain(src: PcmSource): Int {
        val buf = FloatArray(997 * 2)
        var total = 0
        while (true) { val n = src.read(buf, 0, 997); if (n < 0) return total; total += n }
    }

    private class ArraySource(private val data: FloatArray) : PcmSource {
        private var pos = 0
        override fun read(dst: FloatArray, offset: Int, frames: Int): Int {
            val left = data.size / 2 - pos
            if (left <= 0) return -1
            val n = minOf(frames, left)
            System.arraycopy(data, pos * 2, dst, offset * 2, n * 2)
            pos += n
            return n
        }
    }
}

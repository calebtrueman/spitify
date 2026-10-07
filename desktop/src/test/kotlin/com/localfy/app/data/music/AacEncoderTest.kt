package com.localfy.app.data.music

import com.localfy.app.data.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

class AacEncoderTest {
    private val dir = TestAudio.tempDir("aac")

    @Test fun wavToAac256() {
        val wav = TestAudio.wav(File(dir, "a.wav"), 3.0)
        val out = File(dir, "a.m4a")
        val ms = AacEncoder.transcode(wav, out)
        assertTrue(abs(ms - 3000) < 100)
        val p = AacEncoder.probe(out)
        assertNotNull(p); p!!
        assertEquals("aac", p.codec)
        assertEquals(44_100, p.sampleRate)
        assertEquals(2, p.channels)
        assertTrue("duration ${p.durationMs}", abs(p.durationMs - 3000) < 150)
        // 256 kbit/s target (a pure sine needs less, but the encoder must have been asked for 256k).
        assertTrue("bit rate ${p.bitRate}", p.bitRate in 64_000..300_000)
    }

    @Test fun hiResFlacIsResampled() {
        val flac96 = TestAudio.flac(File(dir, "hi.flac"), 1.5, rate = 96_000)
        assertEquals("flac", AacEncoder.probe(flac96)!!.codec)
        assertEquals(96_000, AacEncoder.probe(flac96)!!.sampleRate)
        val out = File(dir, "hi.m4a")
        AacEncoder.transcode(flac96, out)
        val p = AacEncoder.probe(out)!!
        assertEquals("aac", p.codec)
        assertEquals(48_000, p.sampleRate)
        assertTrue(abs(p.durationMs - 1500) < 150)

        val flac88 = TestAudio.flac(File(dir, "hi88.flac"), 1.0, rate = 88_200)
        val out88 = File(dir, "hi88.m4a")
        AacEncoder.transcode("file:" + flac88.absolutePath, out88)
        assertEquals(44_100, AacEncoder.probe(out88)!!.sampleRate)
    }

    @Test fun monoStaysMono() {
        val wav = TestAudio.wav(File(dir, "mono.wav"), 1.0, rate = 22_050, channels = 1)
        val out = File(dir, "mono.m4a")
        AacEncoder.transcode(wav, out)
        val p = AacEncoder.probe(out)!!
        assertEquals(1, p.channels)
        assertEquals(22_050, p.sampleRate)
    }

    @Test fun garbageIsUnsupportedAndLeavesNoOutput() {
        val bad = File(dir, "bad.flac").apply { writeBytes(ByteArray(5000) { (it * 31).toByte() }) }
        val out = File(dir, "bad.m4a")
        try {
            AacEncoder.transcode(bad, out); fail("expected Unsupported")
        } catch (_: AacEncoder.Unsupported) {}
        assertFalse(out.exists())
        assertEquals(null, AacEncoder.probe(File(dir, "missing.flac")))
    }

    @Test fun cancelDeletesOutput() {
        val wav = TestAudio.wav(File(dir, "c.wav"), 2.0)
        val out = File(dir, "c.m4a")
        try {
            AacEncoder.transcode(wav, out, cancelled = { true }); fail("expected Cancelled")
        } catch (_: AacEncoder.Cancelled) {}
        assertFalse(out.exists())
    }

    @Test fun outputRates() {
        assertEquals(44_100, AacEncoder.outputRate(44_100))
        assertEquals(48_000, AacEncoder.outputRate(48_000))
        assertEquals(44_100, AacEncoder.outputRate(176_400))
        assertEquals(48_000, AacEncoder.outputRate(192_000))
        assertEquals(48_000, AacEncoder.outputRate(37_800))
    }
}

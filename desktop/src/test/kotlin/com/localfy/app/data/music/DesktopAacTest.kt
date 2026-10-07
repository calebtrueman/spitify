package com.localfy.app.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Shared fixture: the hi-res FLAC the Android instrumentation tests use. */
internal val hiresFlac: File = listOf("app/src/androidTest/assets/playback/hires-96k-24.flac", "../app/src/androidTest/assets/playback/hires-96k-24.flac")
    .map(::File).first { it.isFile }

class DesktopAacTest {
    @Test fun convertsHiResFlacToAac48k() {
        val source = FlacInfo.read(hiresFlac)
        val out = File.createTempFile("aac-test-", ".m4a")
        try {
            val ms = DesktopAac.transcode(hiresFlac, out)
            val info = DesktopAac.probe(out)!!
            assertEquals("aac", info.codec)
            assertEquals(48_000, info.sampleRate)
            assertTrue("bitrate ${info.bitRate}", info.bitRate == 0L || info.bitRate in 120_000..300_000)
            assertTrue("duration $ms vs ${source.durationMs}", kotlin.math.abs(ms - source.durationMs) < 100)
            assertTrue("probe ${info.durationMs}", kotlin.math.abs(info.durationMs - source.durationMs) < 150)
            assertEquals(AudioContainer.M4A, AudioContainer.detect(out.readBytes().copyOf(16)))
        } finally { out.delete() }
    }

    @Test fun rejectsNonAudioAndLeavesNoFile() {
        val junk = File.createTempFile("junk-", ".flac").apply { writeText("not audio at all") }
        val out = File.createTempFile("aac-test-", ".m4a")
        try {
            assertTrue(runCatching { DesktopAac.transcode(junk, out) }.isFailure)
            assertFalse(out.exists())
        } finally { junk.delete(); out.delete() }
    }

    @Test fun hiResRatesDropToTheirFamilyBase() {
        assertEquals(44_100, DesktopAac.outputRate(44_100))
        assertEquals(48_000, DesktopAac.outputRate(96_000))
        assertEquals(44_100, DesktopAac.outputRate(176_400))
        assertEquals(48_000, DesktopAac.outputRate(192_000))
    }
}

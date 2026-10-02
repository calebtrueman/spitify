package com.localfy.app.data.music

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FlacInfoTest {
    @Test fun rejectsHtmlAndTruncatedAudio() {
        val file = File.createTempFile("spitify-flac", ".flac")
        try {
            file.writeText("<html>Access denied</html>")
            assertThrows(Exception::class.java) { FlacInfo.read(file) }
            file.writeBytes(header())
            val info = FlacInfo.read(file, 10_000)
            assertEquals(44100, info.sampleRate)
            assertEquals(16, info.bits)
            assertEquals(10000L, info.durationMs)
            assertThrows(Exception::class.java) { FlacInfo.read(file, 60_000) }
            file.writeBytes(header().dropLast(3).toByteArray())
            assertThrows(Exception::class.java) { FlacInfo.read(file) }
        } finally { file.delete() }
    }

    @Test fun idsCannotBecomePathsOrLoseDigits() {
        assertEquals("https://tracks.monochrome.st/track/156361611655778304", Monochrome.audioUrl("156361611655778304"))
        assertThrows(IllegalArgumentException::class.java) { Monochrome.audioUrl("../other") }
        assertThrows(IllegalArgumentException::class.java) { Monochrome.audioUrl("") }
    }

    private fun header(): ByteArray {
        val stream = ByteArray(34)
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or 441000L
        for (i in 0..7) stream[10 + i] = (packed ushr (56 - i * 8)).toByte()
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + stream + byteArrayOf(0xff.toByte(), 0xf8.toByte())
    }
}

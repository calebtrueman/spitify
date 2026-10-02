package com.localfy.app.data.art

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CoverDownloadTest {
    @Test fun retriesThenUsesSmallerCopyOfSameCover() {
        val calls = mutableListOf<String>()
        val source = "https://is1-ssl.mzstatic.com/image/album/1000x1000bb.jpg"
        val bytes = CoverDownload.load(source) { url ->
            calls += url
            if (url.contains("1000x1000")) throw IOException("404")
            byteArrayOf(1, 2, 3)
        }
        assertEquals(listOf(source, source, source.replace("1000x1000", "600x600")), calls)
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
    }

    @Test fun retriesTemporaryFailureAndKeepsUsefulError() {
        var calls = 0
        assertArrayEquals(byteArrayOf(1), CoverDownload.load("https://example.com/cover.jpg") {
            if (++calls == 1) throw IOException("503")
            byteArrayOf(1)
        })
        try {
            CoverDownload.load("https://example.com/cover.jpg") { throw IOException("The image server returned 403.") }
            fail("Expected a useful download error")
        } catch (error: IOException) { assertTrue(error.message!!.contains("403")) }
    }
}

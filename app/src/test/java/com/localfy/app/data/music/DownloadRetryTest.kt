package com.localfy.app.data.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadRetryTest {
    @Test fun retriesTemporaryResponsesWithCappedDelay() {
        listOf(408, 429, 500, 502, 521, 1000, 1002, 1004, 1005).forEach { assertTrue(DownloadRetry.isTemporary(it)) }
        listOf(401, 403, 404, 1001, 1006, 1007).forEach { assertFalse(DownloadRetry.isTemporary(it)) }
        assertEquals(listOf(2000L, 5000L, 15000L, 60000L, 300000L, 300000L), listOf(1, 2, 3, 4, 5, 30).map(DownloadRetry::delayMillis))
    }
    @Test fun matchingCopyPreservesAlbumAndDoesNotRepeatFailedSource() = runBlocking {
        val original = OnlineTrack("1", "Carmen", "Lana Del Rey", "Born To Die (Bonus Track Version)", "10", 248000, 9, 1, null, false)
        val other = original.copy(id = "2", album = "Born To Die", releaseId = "20", playable = true)
        val copy = AudioFallback.monochromeCopy(original, search = { listOf(other) }, album = { listOf(other) })!!
        assertEquals("https://tracks.monochrome.st/track/2", copy.audioURL)
        assertEquals(original.album, copy.album)
        assertEquals(original.id, copy.id)
        assertNull(AudioFallback.monochromeCopy(copy, search = { listOf(other) }, album = { listOf(other) }))
        assertFalse(AudioFallback.sameRelease("Love", "Abbey Road"))
        assertTrue(AudioFallback.validAudioURL("https://tracks.monochrome.st/track/2"))
        assertFalse(AudioFallback.validAudioURL("https://tracks.monochrome.st/track/../secret"))
        assertFalse(AudioFallback.validAudioURL("https://tracks.monochrome.st.attacker.test/track/2"))
    }
}

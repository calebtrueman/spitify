package com.localfy.app.data.music

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTest {
    @Test fun knownProgressSurvivesMissingTotalsAndDoesNotGoBackwards() {
        val halfway = DownloadProgress.measured(null, 50, 100)
        assertEquals(0.5f, halfway)
        assertEquals(halfway, DownloadProgress.measured(halfway, 55, -1))
        assertEquals(halfway, DownloadProgress.measured(halfway, 40, 100))
        assertNull(DownloadProgress.measured(null, 20, -1))
        assertEquals(0f, DownloadProgress.measured(null, 0, 100))
    }
    @Test fun checkingAndImportKeepRingWithoutClaimingCompletion() {
        assertEquals(1f, DownloadProgress.fraction("checking", null))
        assertEquals(1f, DownloadProgress.fraction("complete", null))
        assertNull(DownloadProgress.fraction("queued", 0.9f))
        assertNull(DownloadProgress.fraction("waiting", 0.9f))
        assertEquals(0.375f, DownloadProgress.album(listOf(1f, 0.5f, null, null)))
        assertEquals(0.5f, DownloadProgress.album(listOf(1f, 1f, null, null)))
        assertNull(DownloadProgress.album(listOf(null, null)))
    }
}

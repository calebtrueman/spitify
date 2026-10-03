package com.localfy.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.music.Monochrome
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumSourceTest {
    @Test fun corruptedLiveAlbumFixtureDoesNotAcquireTheOtherAlbumsSong() {
        // Metadata captured from the public Punisher response on 2026-10-03. No audio.
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("music/punisher-source.json").bufferedReader().use { it.readText() }
        val album = JSONObject(text)
        val tracks = Monochrome.parseAlbum(album, "155408274068344832")
        assertEquals(10, tracks.size)
        assertEquals("DVD Menu", tracks.first().title)
        assertFalse(tracks.any { it.id == "155408325708615680" || it.artist.contains("Tay-K") })
        assertTrue(tracks.all { it.album == "Punisher" && it.albumArtist == "Phoebe Bridgers" })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11), tracks.map { it.track })
        try {
            Monochrome.parseAlbum(album, "155408274206756864")
            fail("A different album response must fail")
        } catch (_: IllegalStateException) { }
    }
}

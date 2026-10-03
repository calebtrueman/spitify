package com.localfy.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localfy.app.data.music.Monochrome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistMetadataTest {
    @Test fun separateArtistsSurviveSavingWithoutLosingTheFullCredit() {
        val item = JSONObject().put("trackId", "123").put("title", "Tomorrow Never Came")
            .put("artistNames", JSONArray(listOf("Lana Del Rey", "Sean Ono Lennon")))
        val track = requireNotNull(Monochrome.parseTrack(item))
        val saved = requireNotNull(Monochrome.parseTrack(JSONObject(track.json())))
        assertEquals(listOf("Lana Del Rey", "Sean Ono Lennon"), saved.artistNames)
        assertEquals("Lana Del Rey", saved.primaryArtist)
        assertEquals("Lana Del Rey, Sean Ono Lennon", saved.artist)
    }

    @Test fun oldSavedCreditCanUseTheAlbumArtistWithoutSplittingANewBandName() {
        val old = JSONObject().put("trackId", "123").put("title", "Tomorrow Never Came")
            .put("artistNames", JSONArray(listOf("Lana Del Rey, Sean Ono Lennon")))
            .put("albumArtist", "Lana Del Rey").put("audioExtension", "flac")
        val legacy = requireNotNull(Monochrome.parseTrack(old))
        assertNull(legacy.artistNames)
        assertEquals("Lana Del Rey", legacy.primaryArtist)
        val band = JSONObject().put("trackId", "456").put("title", "Song")
            .put("artistNames", JSONArray(listOf("Earth, Wind & Fire")))
        val restored = requireNotNull(Monochrome.parseTrack(JSONObject(requireNotNull(Monochrome.parseTrack(band)).json())))
        assertEquals(listOf("Earth, Wind & Fire"), restored.artistNames)
        assertEquals("Earth, Wind & Fire", restored.primaryArtist)
    }
}

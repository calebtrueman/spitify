package com.localfy.app.data.meta

import org.junit.Assert.*
import org.junit.Test

class MissingMetadataTest {
    @Test fun fillsEachGapAndKeepsExistingAndSavedChoices() {
        val stored = MetadataEdit(title = "Original title", artist = "Original artist", album = "Original album", track = 4)
        val saved = MetadataEdit(title = "Cached title", genre = "My genre", year = 2020)
        val online = MetadataEdit(title = "Wrong title", artist = "Wrong artist", album = "Wrong album", albumArtist = "Band", genre = "Online genre", year = 2026, track = 7, disc = 2)
        assertEquals(MetadataEdit(albumArtist = "Band", genre = "My genre", year = 2020, disc = 2), MissingMetadata.fill(stored, saved, online))
        assertTrue(MissingMetadata.incomplete(stored, saved))
        assertEquals(MetadataEdit(), MissingMetadata.fill(online, saved, stored))
    }
}

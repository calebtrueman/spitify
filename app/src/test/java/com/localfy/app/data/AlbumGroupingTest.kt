package com.localfy.app.data

import org.junit.Assert.*
import org.junit.Test

class AlbumGroupingTest {
    private fun song(id: Long, artist: String, album: String = "Nectar") = Song(id, "Track $id", artist, album, id, artist, 180000, id.toInt(), 1, 2020, null, "Music/Nectar", 0, 100, "audio/flac")
    @Test fun guestCreditsDoNotSplitAnAlbumOrChangeTrackCredits() {
        val tracks = listOf(song(1, "Joji"), song(2, "Joji; Omar Apollo"), song(3, "Joji feat. Diplo"), song(4, "Joji, Lil Yachty"), song(5, "Other Artist"))
        val merged = AlbumGrouping.merge(tracks)
        assertEquals(listOf(1L, 1L, 1L, 1L, 5L), merged.map { it.albumId })
        assertEquals(tracks.map { it.artist }, merged.map { it.artist })
        assertEquals(listOf("Joji", "Joji", "Joji", "Joji", "Other Artist"), merged.map { it.albumArtist })
    }
    @Test fun commasInsideArtistNamesStayIntact() {
        assertEquals("Tyler, The Creator", AlbumGrouping.merge(listOf(song(1, "Tyler, The Creator"))).single().albumArtist)
    }
    @Test fun structuredCreditsGroupAGuestSongWithTheMainAlbum() {
        val tracks = listOf(song(1, "Lana Del Rey"), song(2, "Lana Del Rey, Sean Ono Lennon")
            .copy(artistNames = listOf("Lana Del Rey", "Sean Ono Lennon")))
        val merged = AlbumGrouping.merge(tracks)
        assertEquals(listOf(1L, 1L), merged.map { it.albumId })
        assertEquals(listOf("Lana Del Rey", "Lana Del Rey"), merged.map { it.albumArtist })
        assertEquals(listOf("Lana Del Rey", "Sean Ono Lennon"), merged.last().creditedArtists)
    }

}

class PrimaryArtistCreditsTest {
    @org.junit.Test fun albumArtistSeparatesAnAndCredit() {
        org.junit.Assert.assertEquals(listOf("Justin Bieber", "Glup Shitto"), ArtistCredits.names("Justin Bieber AND Glup Shitto", albumArtist = "Justin Bieber"))
        org.junit.Assert.assertEquals("Justin Bieber", ArtistCredits.primary("Justin Bieber AND Glup Shitto", albumArtist = "Justin Bieber"))
    }
    @org.junit.Test fun bandNamesStayWholeWithoutEvidence() {
        listOf("Florence and the Machine", "Earth, Wind & Fire", "Simon & Garfunkel").forEach { org.junit.Assert.assertEquals(listOf(it), ArtistCredits.names(it)) }
        org.junit.Assert.assertEquals("Foobar", ArtistCredits.primary("Foobar", albumArtist = "Foo"))
    }
    @org.junit.Test fun explicitGuestsRemainInTheCredit() {
        org.junit.Assert.assertEquals(listOf("Main", "Guest"), ArtistCredits.names("Main feat. Guest"))
        org.junit.Assert.assertEquals("Main", ArtistCredits.primary("Main feat. Guest"))
    }
}

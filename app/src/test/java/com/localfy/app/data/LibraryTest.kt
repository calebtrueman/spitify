package com.localfy.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {

    private fun song(
        id: Long, title: String, artist: String, album: String, albumId: Long,
        track: Int = 0, disc: Int = 1, genre: String? = null, folder: String = "Music/",
    ) = Song(id, title, artist, album, albumId, artist, 180_000, track, disc, 2024, genre, folder, id, 1_000, "audio/mpeg", "$title.mp3")

    @Test
    fun albumsAreOrderedByDiscThenTrack() {
        val lib = Library.from(
            listOf(
                song(1, "C", "A", "LP", 10, track = 1, disc = 2),
                song(2, "B", "A", "LP", 10, track = 2, disc = 1),
                song(3, "A", "A", "LP", 10, track = 1, disc = 1),
            ),
        )
        assertEquals(listOf(3L, 2L, 1L), lib.albumById.getValue(10).songs.map { it.id })
    }

    @Test
    fun artistsIncludeAlbumsTheyFeatureOn() {
        val lib = Library.from(
            listOf(
                song(1, "Solo", "Ana", "Ana LP", 1),
                song(2, "Duet", "Ana", "Comp", 2),
                song(3, "Other", "Ben", "Comp", 2),
            ),
        )
        assertEquals(setOf(1L, 2L), lib.artistByName.getValue("Ana").albums.map { it.id }.toSet())
    }

    @Test
    fun genresSortedBySizeAndBlankGenresIgnored() {
        val lib = Library.from(
            listOf(
                song(1, "a", "x", "l", 1, genre = "Jazz"),
                song(2, "b", "x", "l", 1, genre = "Rock"),
                song(3, "c", "x", "l", 1, genre = "Rock"),
                song(4, "d", "x", "l", 1, genre = " "),
            ),
        )
        assertEquals(listOf("Rock", "Jazz"), lib.genres.map { it.name })
    }

    @Test
    fun foldersGroupByRelativePath() {
        val lib = Library.from(listOf(song(1, "a", "x", "l", 1, folder = "Music/A/"), song(2, "b", "x", "l", 1, folder = "Download/")))
        assertEquals(listOf("Download", "A"), lib.folders.map { it.name })
    }
    @Test
    fun featuredArtistsHaveSeparatePagesAndKeepFullSongCredits() {
        val duet = song(1, "Tomorrow Never Came", "Lana Del Rey, Sean Ono Lennon", "Lust for Life", 1)
            .copy(albumArtist = "Lana Del Rey", artistNames = listOf("Lana Del Rey", "Sean Ono Lennon"))
        val lib = Library.from(listOf(duet))
        assertEquals(setOf("Lana Del Rey", "Sean Ono Lennon"), lib.artists.map { it.name }.toSet())
        assertEquals(listOf(1L), lib.artistByName.getValue("Sean Ono Lennon").songs.map { it.id })
        assertEquals("Lana Del Rey", lib.albums.single().artist)
        assertEquals("Lana Del Rey, Sean Ono Lennon", lib.songs.single().artist)
    }

    @Test
    fun olderCreditsUseKnownNamesAndDoNotSplitBands() {
        val lib = Library.from(listOf(
            song(1, "Solo", "Lana Del Rey", "A", 1),
            song(2, "Duet", "Lana Del Rey, Sean Ono Lennon", "B", 2),
            song(3, "Band", "Earth, Wind & Fire", "C", 3),
            song(4, "Rapper", "Tyler, The Creator", "D", 4),
            song(5, "Feature", "Lana Del Rey (feat. Father John Misty)", "E", 5).copy(albumArtist = "Lana Del Rey"),
        ))
        assertEquals(3, lib.artistByName.getValue("Lana Del Rey").songs.size)
        assertEquals(setOf("Lana Del Rey", "Sean Ono Lennon", "Earth, Wind & Fire", "Tyler, The Creator", "Father John Misty"), lib.artistByName.keys)
        assertEquals("Lana Del Rey, Sean Ono Lennon", lib.songById.getValue(2L).artist)
        assertEquals(listOf("Broadcast (UK)"), ArtistCredits.names("Broadcast (UK)"))
        assertEquals(listOf("Simon & Garfunkel"), ArtistCredits.names("Simon & Garfunkel"))
    }

}

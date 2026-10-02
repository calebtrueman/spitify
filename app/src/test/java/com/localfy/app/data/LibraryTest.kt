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
}

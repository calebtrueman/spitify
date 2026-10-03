package com.localfy.app.data.music

import org.junit.Assert.*
import org.junit.Test

class AlbumSourceSafetyTest {
    private fun track(id: String, number: Int, vararg names: String, release: String = "100", disc: Int = 1) =
        OnlineTrack(id, id, names.joinToString(", "), "Album", release, 180_000, number, disc, null, true, artistNames = names.toList())

    @Test fun unrelatedRowCannotReplaceTheAlbumArtistAtTheSameNumber() {
        val wrong = track("1", 1, "Tay-K", "21 Savage", "Young Nudy")
        val real = track("2", 1, "Phoebe Bridgers")
        val next = track("3", 2, "Phoebe Bridgers")
        assertEquals(listOf(real, next), Monochrome.validateAlbumTracks(listOf(wrong, real, next), "100", listOf("Phoebe Bridgers")))
    }

    @Test fun guestsCompilationsAndDifferentDiscsAreKept() {
        val main = track("1", 1, "Main artist")
        val feature = track("2", 2, "Main artist", "Guest")
        val guestOnly = track("3", 3, "Guest")
        val otherDisc = track("4", 1, "Guest", disc = 2)
        val tracks = listOf(main, feature, guestOnly, otherDisc)
        assertEquals(tracks, Monochrome.validateAlbumTracks(tracks, "100", listOf("Main artist")))
        val compilation = listOf(main, track("5", 1, "Other artist"))
        assertEquals(compilation, Monochrome.validateAlbumTracks(compilation, "100", listOf("Main artist"), compilation = true))
    }

    @Test fun anotherReleaseIsNeverStampedWithTheRequestedAlbum() {
        val right = track("1", 1, "Main artist")
        val wrong = track("2", 2, "Main artist", release = "999")
        assertEquals(listOf(right), Monochrome.validateAlbumTracks(listOf(right, wrong, right), "100", listOf("Main artist")))
    }

    @Test fun missingCreditsDoNotCauseAnAlbumTrackToDisappear() {
        val unknown = track("1", 1)
        val real = track("2", 1, "Artist")
        assertEquals(listOf(unknown, real), Monochrome.validateAlbumTracks(listOf(unknown, real), "100", listOf("Artist")))
    }
}

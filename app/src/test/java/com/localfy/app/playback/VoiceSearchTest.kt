package com.localfy.app.playback

import com.localfy.app.data.Song
import org.junit.Assert.*
import org.junit.Test

class VoiceSearchTest {
    private fun song(id: Long, title: String, artist: String, album: String, track: Int) = Song(id, title, artist, album, 1, artist, 1000, track, 1, 2026, "Rock", "", 0, 0, "audio/flac", "$id.flac")
    private val a = song(1, "First Light", "Café", "Dawn", 1)
    private val b = song(2, "Long Road", "Café", "Dawn", 2)
    private val c = song(3, "First Light", "Other", "Night", 1)
    private fun pick(r: VoiceRequest) = VoiceSearch.select(r, listOf(b, a, c), listOf("Road trip" to listOf(c, a)), listOf("Space news" to listOf(b.copy(isPodcast = true))))

    @Test fun recognizesSongsAlbumsArtistsPlaylistsAndShows() {
        assertEquals(listOf(a, b), pick(VoiceRequest("Dawn")).songs)
        assertEquals(listOf(b, a), pick(VoiceRequest("Cafe")).songs)
        assertEquals(listOf(c, a), pick(VoiceRequest("Road trip")).songs)
        assertTrue(pick(VoiceRequest("Space news")).songs.single().isPodcast)
        assertEquals(listOf(a), pick(VoiceRequest(title = "First Light", artist = "Cafe")).songs)
        assertEquals(listOf(a), pick(VoiceRequest("First Light by Cafe")).songs)
        assertTrue(pick(VoiceRequest("No such song")).songs.isEmpty())
    }
}

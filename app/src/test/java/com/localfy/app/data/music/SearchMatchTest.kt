package com.localfy.app.data.music

import org.junit.Assert.*
import org.junit.Test

class SearchMatchTest {
    @Test fun ranksExactTitleAbovePartialTitleAndArtistMatch() {
        val exact = SearchMatch.score("hello", "Hello", "Adele")!!
        assertTrue(exact > SearchMatch.score("hello", "Hello Again", "Other")!!)
        assertTrue(exact > SearchMatch.score("hello", "Another Song", "Hello")!!)
        assertNotNull(SearchMatch.score("joji high hopes", "High Hopes", "Joji, Omar Apollo"))
        assertNull(SearchMatch.score("joji nectar", "High Hopes", "Someone", "Other"))
        assertEquals(SearchMatch.score("beyonce", "Beyoncé", "Artist"), 1000)
    }
    @Test fun duplicatesNeedMatchingCreditsAndDuration() {
        assertTrue(SearchMatch.sameSong("High Hopes", "Joji", 183000, "High Hopes", "Joji", 184000))
        assertFalse(SearchMatch.sameSong("High Hopes", "Joji", 183000, "High Hopes", "Joji", 240000))
        assertFalse(SearchMatch.sameSong("High Hopes", "Joji", 0, "High Hopes", "Joji", 0))
    }
    @Test fun alternateRecordingRejectsVersionsAndUntrustedURLs() {
        val track = OnlineTrack("1", "High Hopes", "Joji", "Nectar", "2", 183000, 1, 1, null, false)
        assertTrue(AudioFallback.matches(track, "Joji - High Hopes (Official Audio)", "Joji", 183000))
        assertFalse(AudioFallback.matches(track, "High Hopes live", "Joji", 183000))
        assertFalse(AudioFallback.matches(track, "High Hopes", "Joji", 240000))
        assertFalse(AudioFallback.matches(track, "High Hopes", "Unrelated", 183000))
        assertTrue(AudioFallback.validAudioURL("https://rr1.googlevideo.com/videoplayback?id=test"))
        assertFalse(AudioFallback.validAudioURL("https://rr1.googlevideo.com.attacker.test/audio"))
        assertFalse(AudioFallback.validAudioURL("https://user@rr1.googlevideo.com/audio"))
        assertFalse(AudioFallback.validAudioURL("http://rr1.googlevideo.com/audio"))
    }
}

package com.localfy.app.data.social

import org.junit.Assert.*
import org.junit.Test

class SpotifyLinkTest {
    @Test fun verifiesHandshakeAndTargetParsing() {
        assertEquals("287082", SpotifyCodeLookup.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59))
        assertEquals("playlist", SpotifyCodeTarget.parse("spotify:user:owner:playlist:37i9dQZF1DXcBWIGoYBM5M")?.kind)
        for (kind in listOf("track", "album", "artist", "show", "episode", "audiobook", "user")) assertEquals(kind, SpotifyCodeTarget.parse("spotify:$kind:abc123")?.kind)
        assertNull(SpotifyCodeTarget.parse("https://attacker.example"))
        assertNull(SpotifyCodeTarget.parse("spotify:track:../../bad"))
    }

    @Test fun readsCodesLocallyAndRejectsDamage() {
        val photo = listOf(0,6,6,0,7,6,0,2,2,3,1,7,0,7,6,4,6,1,4,7,4,1,0)
        assertEquals(26560102031L, SpotifyCodeDecoder.decode(photo))
        assertEquals(67775490487L, SpotifyCodeDecoder.decode(listOf(0,2,6,7,1,7,0,0,0,0,4,7,1,7,3,4,2,7,5,6,5,6,0)))
        assertNull(SpotifyCodeDecoder.decode(emptyList()))
        for (i in 1 until 22) if (i != 11) {
            val damaged = photo.toMutableList(); damaged[i] = (damaged[i] + 1) % 8
            assertNull(SpotifyCodeDecoder.decode(damaged))
        }
    }

    @Test fun acceptsPublicPlaylistLinksAndOfficialShortLinks() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertTrue(SpotifyPlaylists.accepts("  https://open.spotify.com/intl-en/playlist/$id?si=example  "))
        assertTrue(SpotifyPlaylists.accepts("spotify:playlist:$id"))
        assertTrue(SpotifyPlaylists.accepts("https://spotify.link/example"))
        listOf("", "https://spotify.link.attacker.test/example", "https://someone@spotify.link/example", "http://spotify.link/example", "https://open.spotify.com/track/$id", "https://open.spotify.com/other/playlist/$id", "https://open.spotify.com:9999/playlist/$id").forEach { assertFalse(it, SpotifyPlaylists.accepts(it)) }
    }
}

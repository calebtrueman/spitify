package com.localfy.app.data.social

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Packets and playlists must read exactly as the phones write them (core models are the shared contract). */
class SocialCompatTest {
    private val owner = "a".repeat(64)
    private val friend = "b".repeat(64)

    @Test fun packetRoundTripMatchesPhoneEncoding() {
        val body = JSONObject().put("id", owner).put("name", "Zoë ✓")
        val packet = SocialPacket("profile", body)
        val json = packet.json()
        assertEquals(1, json.getInt("v")); assertEquals("profile", json.getString("type"))
        // body travels as standard base64 of the UTF-8 JSON text (Android Base64.getEncoder, iOS base64EncodedString).
        assertEquals(body.toString(), String(Base64.getDecoder().decode(json.getString("body")), Charsets.UTF_8))
        val parsed = SocialPacket.parse(JSONObject(json.toString()))
        assertEquals("profile", parsed.type); assertEquals("Zoë ✓", parsed.body.getString("name"))
    }

    @Test fun parsesPacketWrittenByThePhones() {
        // As produced by the iPhone app (JSONEncoder field order differs; values are what matter).
        val inner = """{"name":"Mix","id":"p1","owner":"$owner","tracks":[{"id":"t1","title":"Song","artist":"Band","album":"","durationMs":1000,"sourceID":"123"}],"revision":3,"updatedAt":5,"isPublic":true,"kind":"playlist","editors":[],"partial":false,"description":""}"""
        val wire = """{"type":"playlist","v":1,"body":"${Base64.getEncoder().encodeToString(inner.toByteArray())}"}"""
        val playlist = SharedPlaylist.parse(SocialPacket.parse(JSONObject(wire)).body)
        assertTrue(playlist.valid())
        assertEquals("Mix", playlist.name); assertEquals(3, playlist.revision); assertEquals("123", playlist.tracks.single().sourceID); assertNull(playlist.image)
    }

    @Test fun playlistAndStateRoundTrip() {
        val playlist = SharedPlaylist(owner = owner, name = "Road trip", description = "Summer", image = "https://i.scdn.co/image/abc", sourceURL = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", sourceName = "Spotify", sourceCount = 2, partial = true,
            editors = listOf(friend), tracks = listOf(SharedTrack(title = "A", artist = "B", durationMs = 1234, spotifyID = "xyz", isrc = "USUM71703861"), SharedTrack(title = "C", artist = "D")), revision = 7, isPublic = true)
        assertTrue(playlist.valid())
        assertEquals(playlist, SharedPlaylist.parse(JSONObject(SocialPacket("playlist", playlist.json()).json().toString()).let(SocialPacket::parse).body))
        val state = SocialState().apply { following += friend; playlists[playlist.key] = playlist; recipients[playlist.key] = mutableSetOf(friend); profiles[friend] = FriendProfile(friend, "Bea") }
        val back = SocialState.parse(JSONObject(state.json().toString()))
        assertEquals(state.following, back.following); assertEquals(state.playlists, back.playlists); assertEquals(state.profiles, back.profiles); assertEquals(state.recipients, back.recipients)
    }

    @Test fun roomPacketsRoundTrip() {
        val room = ListeningRoom(host = owner, name = "Friday", members = listOf(friend), queue = listOf(SharedTrack(title = "A", artist = "B")), playing = true, speed = 1.25f, revision = 4)
        assertEquals(room, ListeningRoom.parse(JSONObject(room.json().toString())))
        val request = RoomRequest(roomID = room.id, host = owner, action = "control", positionMs = 5000, playing = false)
        assertEquals(request, RoomRequest.parse(JSONObject(request.json().toString())))
    }

    @Test fun links() {
        assertEquals(SocialLink("person", owner), SocialLink.parse(owner))
        assertEquals(SocialLink("playlist", owner, "p1"), SocialLink.parse("spitify://playlist/$owner/p1"))
        assertNull(SocialLink.parse("spitify://person/notakey"))
        assertNull(SocialLink.parse("https://evil.example/$owner"))
    }
}

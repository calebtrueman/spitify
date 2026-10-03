package com.localfy.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.social.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nostrdevkit.sdk.Keys
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SocialTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = "a".repeat(64)
    private val guest = "b".repeat(64)
    private val stranger = "c".repeat(64)
    private fun wire(event: JSONObject) = JSONArray().put("EVENT").put("test").put(event).toString()
    private fun events(storage: String) = JSONArray(context.getSharedPreferences(storage, Context.MODE_PRIVATE).getString("events", "[]"))
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Test fun friendPictureCodeReadsBackItsOwnImage() {
        val link = SocialLink(type = "person", owner = host).url
        val image = FriendPictureCode.make(link)
        try { assertEquals(link, FriendPictureCode.decode(image)) } finally { image.recycle() }
    }

    @Test fun profilePhotoStaysInOneReplaceableEvent() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = scope(); val storage = "photo-test-${UUID.randomUUID()}"
            try {
                val sender = PeerRelay(context, Keys.parse("1".repeat(64)), scope, storage, true)
                val receiver = PeerRelay(context, Keys.parse("2".repeat(64)), scope, "photo-receiver-${UUID.randomUUID()}", true)
                val photo = android.util.Base64.encodeToString(ByteArray(2000) { 42 }, android.util.Base64.NO_WRAP)
                val hd = android.util.Base64.encodeToString(ByteArray(18000) { 7 }, android.util.Base64.NO_WRAP)
                val body = JSONObject().put("id", sender.publicKey).put("name", "🎵".repeat(40)).put("about", "🎵".repeat(250)).put("photo", photo).put("photoHD", hd).put("isPublic", true).put("updatedAt", 1)
                var received: String? = null
                receiver.onPacket = { _, packet, _ -> received = packet.body.optString("photoHD") }
                sender.send(SocialPacket("profile", body), "profile")
                val saved = events(storage); assertEquals(1, saved.length())
                receiver.receive(wire(JSONObject(saved.getJSONObject(0).getString("json"))))
                assertEquals(hd, received)
                sender.send(SocialPacket("profile", body), "profile", receiver.publicKey)
                assertEquals(2, events(storage).length())
                received = null
                val privateEvent = events(storage).getJSONObject(1).getString("json")
                assertTrue(privateEvent.toByteArray().size < 65536)
                receiver.receive(wire(JSONObject(privateEvent)))
                assertEquals(hd, received)
            } finally { scope.cancel(); context.deleteSharedPreferences(storage) }
        }
    }

    @Test fun sharedPhotoKeepsDetailWithinMessageBudget() {
        val source = android.graphics.Bitmap.createBitmap(1536, 1536, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(source)
        val paint = android.graphics.Paint()
        for (y in 0 until 1536 step 24) for (x in 0 until 1536 step 24) {
            paint.color = android.graphics.Color.HSVToColor(floatArrayOf(((x + y) % 1536) / 1536f * 360f, 0.8f, 0.9f))
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + 24).toFloat(), (y + 24).toFloat(), paint)
        }
        try {
            val hd = android.util.Base64.decode(requireNotNull(ProfilePhotos.shared(source)), android.util.Base64.DEFAULT)
            assertTrue(hd.size <= 18000)
            val image = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(hd, 0, hd.size))
            assertTrue(image.width >= 384); image.recycle()
            val preview = android.util.Base64.decode(requireNotNull(ProfilePhotos.preview(source)), android.util.Base64.DEFAULT)
            assertTrue(preview.size <= 2000)
        } finally { source.recycle() }
    }

    @Test fun importedPlaylistPreservesSafeSongArtworkAndSourceOrder() {
        val rows = JSONArray()
            .put(JSONObject().put("id", "first").put("title", "One").put("artist", "Singer").put("artwork", "https://images.example.com/one.jpg"))
            .put(JSONObject().put("id", "second").put("title", "Two").put("artist", "Singer").put("thumbnail", "https://images.example.com/two.jpg"))
            .put(JSONObject().put("id", "third").put("title", "Three").put("artist", "Singer").put("artwork", "file:///private/image.jpg"))
        val playlist = SpotifyPlaylists.parse(JSONObject().put("name", "Evening").put("total_tracks", 3).put("tracks", rows), "37i9dQZF1DXcBWIGoYBM5M", host)
        assertEquals(listOf("One", "Two", "Three"), playlist.tracks.map { it.title })
        assertEquals(listOf("first", "second", "third"), playlist.tracks.map { it.spotifyID })
        assertEquals("https://images.example.com/one.jpg", playlist.tracks[0].artwork)
        assertEquals("https://images.example.com/two.jpg", playlist.tracks[1].artwork)
        assertNull(playlist.tracks[2].artwork)
        assertFalse(playlist.partial)
        assertEquals(playlist, SharedPlaylist.parse(playlist.json()))
    }

    @Test fun streamingLookupUsesIndexForLargeSavedCatalogue() {
        val storage = "lookup-test-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences(storage, Context.MODE_PRIVATE)
        try {
            val rows = JSONArray()
            repeat(2000) { i -> rows.put(JSONObject().put("trackId", "$i").put("title", "Song $i").put("artistNames", JSONArray().put("Artist")).put("albumTitle", "Album").put("releaseId", "1").put("duration", 180000)) }
            prefs.edit().putString("tracks", rows.toString()).commit()
            val streams = com.localfy.app.data.music.MusicStreams(context, storage)
            val id = com.localfy.app.data.music.MusicStreams.streamId("1999")
            val tracks = streams.knownTracks()
            repeat(20) { assertEquals("Song 1999", streams.lookup(id)?.title) }
            val indexedStart = System.nanoTime()
            repeat(100) { assertEquals("Song 1999", streams.lookup(id)?.title) }
            val indexed = System.nanoTime() - indexedStart
            val scanStart = System.nanoTime()
            repeat(100) { assertNotNull(tracks.firstOrNull { com.localfy.app.data.music.MusicStreams.streamId(it.id) == id }) }
            val scanned = System.nanoTime() - scanStart
            android.util.Log.i("SpitifyLookupTest", "100 lookups: indexed=${indexed / 1000000.0}ms oldScan=${scanned / 1000000.0}ms")
            assertTrue("Direct lookup should avoid the full catalogue scan", indexed < scanned)
        } finally { context.deleteSharedPreferences(storage) }
    }

    @Test fun officialArtistVideoTitlesNeedNoOfficialLabel() {
        for (title in listOf("Video Games", "Born To Die")) {
            assertTrue(com.localfy.app.data.music.MusicVideoLookup.matches(title, "Lana Del Rey", 282000, "Lana Del Rey - $title", "Lana Del Rey", 287000))
            assertTrue(com.localfy.app.data.music.MusicVideoLookup.matches(title, "Lana Del Rey", 282000, "Lana Del Rey - $title", "LanaDelReyVEVO", 287000))
            assertTrue(com.localfy.app.data.music.MusicVideoLookup.matches(title, "Lana Del Rey", 282000, "Lana Del Rey - $title", "Lana Del Rey Fan Videos", 287000))
        }
    }
    @Test fun roomRequiresExplicitJoinAndRejectsLateUpdatesAfterLeaving() {
        val state = RoomState()
        val room = ListeningRoom(host = host, name = "Room", members = listOf(guest))
        assertFalse(state.accept(room, host, guest, true))
        state.requestedKey = room.key
        assertFalse(state.accept(room, stranger, guest, true)); assertFalse(state.accept(room, host, guest, false))
        assertTrue(state.accept(room, host, guest, true)); assertEquals(room.key, state.activeKey)
        state.activeKey = null
        assertFalse(state.accept(room.copy(revision = 2), host, guest, true))
        state.requestedKey = room.key
        assertTrue(state.accept(room.copy(revision = 2, ended = true), host, guest, true)); assertNull(state.activeKey)
        state.requestedKey = room.key
        assertFalse(state.accept(room.copy(revision = 3), host, guest, true))
    }
    @Test fun editsNeedPermissionAndApplyOnce() {
        val state = SocialState()
        val playlist = SharedPlaylist(owner = host, name = "Test", editors = listOf(guest))
        state.playlists[playlist.key] = playlist
        val edit = SharedEdit(playlistID = playlist.id, owner = host, action = "add", tracks = listOf(SharedTrack(title = "One", artist = "Artist")))
        assertNull(state.apply(edit, stranger, host)); assertEquals(1, state.apply(edit, guest, host)?.tracks?.size)
        assertNull(state.apply(edit, guest, host))
        state.playlists[playlist.key] = state.playlists.getValue(playlist.key).copy(editors = emptyList())
        assertNull(state.apply(edit.copy(id = "new-edit"), guest, host))
    }
    @Test fun roomClockAndMixOrderingMatchIOS() {
        val one = SharedTrack(title = "One", artist = "Artist", durationMs = 100_000)
        val two = SharedTrack(title = "Two", artist = "Artist")
        val three = SharedTrack(title = "Three", artist = "Artist")
        assertEquals(listOf("One", "Three", "Two"), SocialRules.mix(listOf(listOf(one, two), listOf(three, one))).map { it.title })
        val room = ListeningRoom(host = host, name = "Test", queue = listOf(one), currentID = one.id, positionMs = 40_000, playing = true, observedAt = 1000)
        assertEquals(43_000L, RoomState.expectedPosition(room, 4000)); assertEquals(100_000L, RoomState.expectedPosition(room, 100_000))
        assertEquals(40_000L, RoomState.expectedPosition(room.copy(playing = false), 4000))
    }
    @Test fun privateLargeTransferSurvivesRestartAndWaitsForEveryPart() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = scope(); val storage = "relay-test-${UUID.randomUUID()}"
            try {
                val a = PeerRelay(context, Keys.parse("1".repeat(64)), scope, storage, true)
                val b = PeerRelay(context, Keys.parse("2".repeat(64)), scope, "test-${UUID.randomUUID()}", true)
                val other = PeerRelay(context, Keys.generate(), scope, "test-${UUID.randomUUID()}", true)
                val playlist = SharedPlaylist(owner = a.publicKey, name = "Large", tracks = (0 until 1200).map { SharedTrack(id = "track-$it", title = "Song $it", artist = "Artist", durationMs = 120_000) })
                var received: SharedPlaylist? = null; var count = 0
                b.onPacket = { author, packet, encrypted -> assertEquals(a.publicKey, author); assertTrue(encrypted); received = SharedPlaylist.parse(packet.body); count++ }
                other.onPacket = { _, _, _ -> fail("Wrong recipient received private music") }
                a.send(SocialPacket("playlist", playlist.json()), "playlist:test", b.publicKey)
                val saved = events(storage); assertTrue(saved.length() > 1)
                val reopened = PeerRelay(context, Keys.parse("1".repeat(64)), scope, storage, true)
                assertEquals(saved.length(), reopened.pendingCount)
                for (i in (0 until saved.length() - 1).reversed()) { val event = JSONObject(saved.getJSONObject(i).getString("json")); other.receive(wire(event)); b.receive(wire(event)) }
                assertNull(received)
                val last = JSONObject(saved.getJSONObject(saved.length() - 1).getString("json")); b.receive(wire(last)); b.receive(wire(last))
                assertEquals(playlist, received); assertEquals(1, count)
                for (i in 0 until saved.length()) reopened.receive(JSONArray().put("OK").put(saved.getJSONObject(i).getString("id")).put(true).put("saved").toString())
                assertEquals(0, reopened.pendingCount)
                val tampered = JSONObject(saved.getJSONObject(0).getString("json")).put("content", "changed")
                other.receive(wire(tampered))
            } finally { scope.cancel(); context.getSharedPreferences(storage, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }
    @Test fun readsSwiftSignedFixtureAndExportsAndroidReply() = runBlocking {
        val input = File(context.filesDir, "swift-wire-fixture.json")
        assumeTrue("Copy the Swift test fixture into the test app first", input.isFile)
        withContext(Dispatchers.Main) {
            val scope = scope(); val storage = "cross-wire-${UUID.randomUUID()}"
            try {
                val fixture = JSONObject(input.readText())
                val a = PeerRelay(context, Keys.parse("2".repeat(64)), scope, storage, true)
                var playlist: SharedPlaylist? = null
                a.onPacket = { author, packet, encrypted -> assertEquals(fixture.getString("sender"), author); assertTrue(encrypted); playlist = SharedPlaylist.parse(packet.body) }
                val source = fixture.getJSONArray("events")
                for (i in (0 until source.length()).reversed()) a.receive(wire(source.getJSONObject(i)))
                assertEquals("Swift to Android", playlist?.name); assertEquals(500, playlist?.tracks?.size)
                val reply = playlist!!.copy(owner = a.publicKey, name = "Android to Swift")
                a.send(SocialPacket("playlist", reply.json()), "playlist:cross-platform", fixture.getString("sender"))
                val outgoing = events(storage)
                val messages = JSONArray(); for (i in 0 until outgoing.length()) messages.put(JSONObject(outgoing.getJSONObject(i).getString("json")))
                File(context.filesDir, "android-wire-fixture.json").writeText(JSONObject().put("sender", a.publicKey).put("events", messages).toString())
            } finally { scope.cancel(); context.getSharedPreferences(storage, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }
}

package com.localfy.app.data.social

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.nostrdevkit.sdk.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class PeerRelayTest {
    private val dir: File = Files.createTempDirectory("relay-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relays = mutableListOf<PeerRelay>()
    private data class Got(val author: String, val packet: SocialPacket, val encrypted: Boolean)

    private fun relay(server: FakeRelay, name: String, keys: Keys = Keys.generate()): Pair<PeerRelay, MutableList<Got>> {
        val got = CopyOnWriteArrayList<Got>()
        val relay = PeerRelay(keys, scope, socketFactory = server, outboxFile = File(dir, "$name.json"))
        relay.onPacket = { author, packet, encrypted -> got += Got(author, packet, encrypted) }
        relays += relay
        return relay to got
    }

    @After fun tearDown() { relays.forEach { it.close() }; scope.cancel(); dir.deleteRecursively() }

    @Test fun publicPacketReachesFollower() = runBlocking {
        val server = FakeRelay()
        val (alice, _) = relay(server, "a"); val (bob, bobGot) = relay(server, "b")
        alice.start(listOf("wss://relay.test"), emptySet()); bob.start(listOf("wss://relay.test"), setOf(alice.publicKey))
        eventually { alice.connectedCount == 1 && bob.connectedCount == 1 }
        alice.send(SocialPacket("profile", JSONObject().put("id", alice.publicKey).put("name", "Alice")), "profile")
        eventually { bobGot.any { it.packet.type == "profile" } }
        val got = bobGot.first { it.packet.type == "profile" }
        assertEquals(alice.publicKey, got.author); assertFalse(got.encrypted); assertEquals("Alice", got.packet.body.getString("name"))
        // The relay accepted it (OK), so nothing is left waiting.
        eventually { alice.pendingCount == 0 }
        // Wire format: kind 30078, profile identifier, spitify tag, expiration, plain packet JSON.
        val event = server.events.first { it.getString("pubkey") == alice.publicKey }
        assertEquals(30078, event.getInt("kind"))
        val tags = event.getJSONArray("tags").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { b -> (0 until b.length()).map(b::getString) } } }
        assertTrue(listOf("d", "spitify:v1:profile") in tags); assertTrue(listOf("t", "spitify") in tags); assertTrue(tags.any { it[0] == "expiration" })
        assertEquals("profile", JSONObject(event.getString("content")).getString("type"))
    }

    @Test fun largePrivatePlaylistIsChunkedEncryptedAndReassembled() = runBlocking {
        val server = FakeRelay()
        val (alice, aliceGot) = relay(server, "a"); val (bob, bobGot) = relay(server, "b"); val (eve, eveGot) = relay(server, "e")
        listOf(alice, bob, eve).forEach { it.start(listOf("wss://relay.test"), emptySet()) }
        eventually { listOf(alice, bob, eve).all { it.connectedCount == 1 } }
        val tracks = (0 until 300).map { SharedTrack(title = "Song number $it with a fairly long title", artist = "Artist $it", album = "Album", durationMs = 200_000L + it) }
        val playlist = SharedPlaylist(owner = alice.publicKey, name = "Big", tracks = tracks)
        assertTrue(playlist.valid())
        alice.send(SocialPacket("playlist", playlist.json()), "playlist:${playlist.id}", bob.publicKey)
        eventually { bobGot.any { it.packet.type == "playlist" } }
        val got = bobGot.single { it.packet.type == "playlist" }
        assertTrue(got.encrypted); assertEquals(alice.publicKey, got.author)
        assertEquals(playlist, SharedPlaylist.parse(got.packet.body))
        val sent = server.events.filter { it.getString("pubkey") == alice.publicKey }
        assertTrue("expected several parts", sent.size > 1)
        sent.forEach { e ->
            assertFalse("content must be encrypted", e.getString("content").contains("\"type\""))
            assertTrue(e.getJSONArray("tags").toString().contains("\"encrypted\",\"nip44\""))
        }
        delay(300)
        assertTrue("other people never see private shares", eveGot.isEmpty())
        assertTrue(aliceGot.isEmpty())
    }

    @Test fun badEventsAreIgnoredWithoutCrashing() = runBlocking {
        val server = FakeRelay()
        val (alice, _) = relay(server, "a"); val (bob, bobGot) = relay(server, "b")
        listOf(alice, bob).forEach { it.start(listOf("wss://relay.test"), emptySet()) }
        eventually { bob.connectedCount == 1 }
        val keys = alice.keys
        fun signed(content: String, tags: List<List<String>>) = JSONObject(EventBuilder(Kind(30078u), content).tags(tags.map(Tag::parse)).finalize(keys).asJson())
        val good = signed(SocialPacket("profile", JSONObject().put("name", "x")).json().toString(), listOf(listOf("d", "spitify:v1:profile"), listOf("t", "spitify")))
        val tampered = JSONObject(good.toString()).put("content", SocialPacket("profile", JSONObject().put("name", "evil")).json().toString())
        val noTag = signed(SocialPacket("profile", JSONObject()).json().toString(), listOf(listOf("d", "spitify:v1:profile")))
        val expired = signed(SocialPacket("profile", JSONObject()).json().toString(), listOf(listOf("d", "spitify:v1:profile"), listOf("t", "spitify"), listOf("expiration", "1000")))
        val fakeEncrypted = signed("not-a-ciphertext", listOf(listOf("d", "spitify:v1:x:0"), listOf("t", "spitify"), listOf("p", bob.publicKey), listOf("encrypted", "nip44")))
        listOf("garbage", "[]", "[\"EVENT\"]", "[\"EVENT\",\"s\",{}]", "{\"a\":1}", "x".repeat(200_000),
            JSONArray().put("EVENT").put("s").put(tampered).toString(), JSONArray().put("EVENT").put("s").put(noTag).toString(),
            JSONArray().put("EVENT").put("s").put(expired).toString(), JSONArray().put("EVENT").put("s").put(fakeEncrypted).toString(),
        ).forEach(bob::receive)
        bob.receive(JSONArray().put("EVENT").put("s").put(good).toString())
        bob.receive(JSONArray().put("EVENT").put("s").put(good).toString()) // duplicate
        eventually { bobGot.isNotEmpty() }
        delay(200)
        assertEquals(1, bobGot.size); assertEquals("x", bobGot.single().packet.body.getString("name"))
    }

    @Test fun reconnectsWithBackoffAndFlushesOutbox() = runBlocking {
        val server = FakeRelay().apply { up = false }
        val (alice, _) = relay(server, "a")
        alice.start(listOf("wss://relay.test"), emptySet())
        alice.send(SocialPacket("profile", JSONObject().put("name", "A")), "profile")
        assertEquals(1, alice.pendingCount)
        delay(2500)
        // 1 s then 2 s backoff: a handful of attempts, never a busy loop.
        assertTrue("attempts=${server.opened.get()}", server.opened.get() in 2..3)
        server.up = true
        eventually(15_000) { alice.connectedCount == 1 && alice.pendingCount == 0 }
        assertEquals(1, server.events.size)
        // A dropped connection comes back by itself.
        server.dropAll(); eventually { alice.connectedCount == 0 }
        eventually(5_000) { alice.connectedCount == 1 }
    }

    @Test fun outboxSurvivesRestart() = runBlocking {
        val server = FakeRelay().apply { up = false }
        val keys = Keys.generate()
        val first = PeerRelay(keys, scope, socketFactory = server, outboxFile = File(dir, "o.json"))
        first.send(SocialPacket("profile", JSONObject().put("name", "A")), "profile")
        first.close(); delay(500)
        val second = PeerRelay(keys, scope, socketFactory = server, outboxFile = File(dir, "o.json")).also { relays += it }
        assertEquals(1, second.pendingCount)
    }

    @Test fun rejectsBadRecipientWithReadableMessage() = runBlocking {
        val (alice, _) = relay(FakeRelay(), "a")
        val error = runCatching { alice.send(SocialPacket("x", JSONObject()), "x", "not-a-key") }.exceptionOrNull()
        assertEquals("That friend code is not valid.", error?.message)
    }

    @Test fun identityFileIsPrivateAndStable() {
        val file = File(dir, "id/peer_identity.key")
        val first = PeerIdentity.load(file); val again = PeerIdentity.load(file)
        assertEquals(first.publicKey().toHex(), again.publicKey().toHex())
        if (!System.getProperty("os.name").lowercase().contains("win"))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        val other = Keys.generate()
        PeerIdentity.restore(other.secretKey().toHex(), file)
        assertEquals(other.publicKey().toHex(), PeerIdentity.load(file).publicKey().toHex())
    }
}

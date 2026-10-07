package com.localfy.app.data.social

import com.localfy.app.data.Song
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.nostrdevkit.sdk.Keys
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/** Two (or three) desktop users talking through an in-memory relay, end to end. */
class SocialRepositoryTest {
    private val dir: File = Files.createTempDirectory("social").toFile()
    private val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private val server = FakeRelay()
    private val repos = mutableListOf<SocialRepository>()
    private val relayUrl = listOf("wss://relay.test")

    private fun user(name: String): SocialRepository {
        val folder = File(dir, name).apply { mkdirs() }
        val relay = PeerRelay(Keys.generate(), scope, socketFactory = server, outboxFile = File(folder, "outbox.json"))
        return SocialRepository(scope, folder, relay).also { repos += it }
    }
    private suspend fun <T> onUi(block: suspend () -> T): T = withContext(ui) { block() }
    @After fun tearDown() { repos.forEach { it.close() }; scope.cancel(); ui.close(); dir.deleteRecursively() }

    private fun song(id: Long, title: String) = Song(id, title, "Band", "Album", 1, "Band", 200_000, 1, 1, 2020, null, "/m", 0, 0, null)

    @Test fun followShareEditAndProfile() = runBlocking {
        val alice = onUi { user("alice") }; val bob = onUi { user("bob") }
        onUi { alice.configure(true, relays = relayUrl); bob.configure(true, relays = relayUrl) }
        onUi { alice.publishProfile("Alice", "hi"); bob.follow(alice.publicKey) }
        eventually { onUiBlocking { bob.connected == 1 && bob.state.profiles[alice.publicKey]?.name == "Alice" } }

        // Public share reaches a follower.
        val playlist = onUi { alice.create("Road trip", listOf(song(1, "One"), song(2, "Two"))) }
        onUi { alice.share(playlist) }
        eventually { onUiBlocking { bob.state.playlists[playlist.key]?.isPublic == true } }
        assertEquals(listOf("One", "Two"), onUi { bob.state.playlists[playlist.key]!!.tracks.map { it.title } })

        // Alice lets Bob edit; Bob's encrypted edit is applied by Alice and comes back to him.
        onUi { alice.follow(bob.publicKey); alice.setEditor(bob.publicKey, playlist, true) }
        eventually { onUiBlocking { bob.publicKey in bob.state.playlists[playlist.key]!!.editors } }
        onUi { bob.edit(SharedEdit(playlistID = playlist.id, owner = alice.publicKey, action = "add", tracks = listOf(SharedTrack(title = "Three", artist = "Band")))) }
        eventually { onUiBlocking { bob.state.playlists[playlist.key]!!.tracks.size == 3 } }
        assertEquals(3, onUi { alice.state.playlists[playlist.key]!!.tracks.size })

        // State persists across restarts.
        val saved = onUi { alice.state.playlists[playlist.key] }
        onUi { alice.close() }
        val again = onUi { SocialRepository(scope, File(dir, "alice"), PeerRelay(Keys.generate(), scope, socketFactory = FakeRelay(), outboxFile = File(dir, "x.json"))).also { repos += it } }
        assertEquals(saved, onUi { again.state.playlists[playlist.key] })
        assertTrue(onUi { again.enabled })
    }

    @Test fun privateShareOnlyReachesTheRecipient() = runBlocking {
        val alice = onUi { user("alice") }; val bob = onUi { user("bob") }; val carol = onUi { user("carol") }
        onUi { listOf(alice, bob, carol).forEach { it.configure(true, relays = relayUrl) } }
        onUi { bob.follow(alice.publicKey); carol.follow(alice.publicKey); alice.follow(bob.publicKey) }
        val playlist = onUi { alice.create("Secret", listOf(song(1, "One"))) }
        onUi { alice.share(playlist, bob.publicKey) }
        eventually { onUiBlocking { playlist.key in bob.state.playlists } }
        delay(300)
        assertFalse(onUi { playlist.key in carol.state.playlists })
        assertEquals("Follow this person before sending a private share.", onUi { runCatching { alice.share(playlist, carol.publicKey) }.exceptionOrNull()?.message })
    }

    @Test fun roomJoinApproveAndFollowPlayback() = runBlocking {
        val host = onUi { user("host") }; val guest = onUi { user("guest") }
        onUi { host.configure(true, relays = relayUrl); guest.configure(true, relays = relayUrl) }
        val hostPlayer = FakePlayer(mutableListOf(song(1, "One"), song(2, "Two")))
        val guestPlayer = FakePlayer(mutableListOf())
        val library = listOf(song(1, "One"), song(2, "Two"))
        val hostRooms = onUi { ListeningRooms(host, hostPlayer, scope, { library }, { error("no streams") }) }
        val guestRooms = onUi { ListeningRooms(guest, guestPlayer, scope, { library }, { error("no streams") }) }
        val room = onUi { hostRooms.host("Friday"); hostRooms.room!! }
        onUi { guest.joinRoom(SocialLink("room", host.publicKey, room.id)) }
        eventually { onUiBlocking { host.rooms.requests.any { it.sender == guest.publicKey } } }
        onUi { hostRooms.approve(host.rooms.requests.first(), true) }
        hostPlayer.on = true
        eventually { onUiBlocking { guestRooms.room != null && guestPlayer.queue.firstOrNull()?.title == "One" && guestPlayer.on } }
        assertEquals("Room: Friday", guestPlayer.source)
        hostRooms.close(); guestRooms.close()
    }

    private fun onUiBlocking(block: () -> Boolean): Boolean = runBlocking(ui) { block() }

    class FakePlayer(override val queue: MutableList<Song?>) : RoomPlayer {
        override var currentIndex = 0
        @Volatile var on = false
        override val isPlaying get() = on
        override val speed = 1f
        override var source: String? = null
        override var positionMs = 0L
        override fun setPlaying(playing: Boolean) { on = playing }
        override fun setRoomPlayback(speed: Float?) {}
        override fun next() { currentIndex++ }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun removeAt(index: Int) { queue.removeAt(index) }
        override fun playSongs(songs: List<Song>, shuffle: Boolean, source: String) { queue.clear(); queue += songs; currentIndex = 0; this.source = source }
        override fun appendFromSource(songs: List<Song>) { queue += songs }
    }
}

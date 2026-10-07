package com.localfy.app.data.social

import com.localfy.app.data.Song
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.playback.PlayerUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.nostrdevkit.sdk.Keys
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Two computers' DeviceSyncRepositories (separate data folders and keys) linked through the
 * in-memory relay, with friend sharing off on both: pairing, the group list, playback state,
 * remote control, Listen here, handoff, continue, unlinking and saving.
 */
class DeviceSyncRepositoryTest {
    private val dir: File = Files.createTempDirectory("devices").toFile()
    private val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private val server = FakeRelay()
    private val socials = mutableListOf<SocialRepository>()

    private class Computer(val social: SocialRepository, val devices: DeviceSyncRepository, val player: FakeDevicePlayer, val notes: MutableList<String>)

    private fun song(id: Long, title: String) = Song(id, title, "Band", "Album", 1, "Band", 200_000, id.toInt(), 1, 2020, null, "/m", 0, 0, null)
    private val library = (1L..5L).map { song(it, "Song $it") }

    private fun computer(name: String, host: String, platform: String, keys: Keys = Keys.generate()): Computer {
        val folder = File(dir, name).apply { mkdirs() }
        val relay = PeerRelay(keys, scope, socketFactory = server, outboxFile = File(folder, "outbox.json"))
        val social = SocialRepository(scope, folder, relay).also { socials += it }
        val player = FakeDevicePlayer()
        // Listen here finds the same song in this computer's library.
        val devices = DeviceSyncRepository(social, player, scope, folder, resolveTrack = { t -> library.first { it.title == t.title } }, platform = platform, hostName = { host })
        val notes = CopyOnWriteArrayList<String>()
        scope.launch { devices.messages.collect { notes += it } }
        return Computer(social, devices, player, notes)
    }

    private suspend fun <T> onUi(block: suspend () -> T): T = withContext(ui) { block() }
    private fun onUiBlocking(block: () -> Boolean): Boolean = runBlocking(ui) { block() }
    @After fun tearDown() { socials.forEach { it.close() }; scope.cancel(); ui.close(); dir.deleteRecursively() }

    private suspend fun link(): Pair<Computer, Computer> {
        val mac = onUi { computer("mac", "Calebs-MacBook.local", "macos") }
        val pc = onUi { computer("pc", "GAMING-PC", "windows") }
        eventually { onUiBlocking { mac.devices.name == "Calebs-MacBook" && pc.devices.name == "GAMING-PC" } }
        assertFalse("sharing stays off", onUi { mac.social.enabled || pc.social.enabled })

        val code = onUi { mac.devices.showCode(); (mac.devices.pairing as DevicePairing.Showing).code }
        // Typed in lower case with a dash: still the same code.
        onUi { pc.devices.enterCode(DeviceSyncState.displayCode(code).lowercase().replace(' ', '-')) }
        eventually { onUiBlocking { mac.devices.requests.isNotEmpty() } }
        assertEquals(DevicePairing.Waiting("Calebs-MacBook"), onUi { pc.devices.pairing })
        val (author, request) = onUi { mac.devices.requests.single() }
        assertEquals(pc.devices.me, author); assertEquals("GAMING-PC", request.name); assertEquals("windows", request.platform)
        assertTrue("waits for Allow", onUi { mac.devices.devices.isEmpty() })
        onUi { mac.devices.approve(author) }
        eventually { onUiBlocking { pc.devices.pairing == DevicePairing.Linked("Calebs-MacBook") } }
        assertEquals(listOf(mac.devices.me), onUi { pc.devices.devices.map { it.id } })
        assertEquals(listOf("GAMING-PC"), onUi { mac.devices.devices.map { it.name } })
        assertTrue(pc.notes.contains("Linked with Calebs-MacBook"))
        return mac to pc
    }

    @Test fun pairPlayControlHandOffAndUnlink() = runBlocking {
        val (mac, pc) = link()
        // With sharing off the relay only reads its own inbox and publishes no profile.
        val requests = server.received.filter { it.startsWith("[\"REQ\",\"spitify-v1\"") }
        assertTrue(requests.isNotEmpty() && requests.all { JSONArray(it).length() == 3 && it.contains("#p") })
        assertTrue(server.events.none { it.getJSONArray("tags").toString().contains("spitify:v1:profile") })
        // The code's public offer carries its lookup tag.
        assertTrue(server.events.any { e -> e.getJSONArray("tags").toString().contains("spitify-link-") })

        // The Mac plays: the PC sees "Playing on Calebs-MacBook".
        onUi { mac.player.playSongs(library.take(3), "Liked Songs", 30_000) }
        eventually { onUiBlocking { pc.devices.shown()?.current?.title == "Song 1" } }
        val shown = onUi { pc.devices.shown()!! }
        assertEquals("Liked Songs", shown.source); assertEquals(3, shown.queue.size); assertTrue(shown.playing)
        assertTrue(onUi { pc.devices.position(shown) } >= 30_000)

        // Pause from the PC.
        onUi { pc.devices.control(mac.devices.me, "pause") }
        eventually { onUiBlocking { !mac.player.state.value.isPlaying } }
        eventually { onUiBlocking { pc.devices.playback(mac.devices.me)?.let { !it.playing && it.revision > shown.revision } == true } }
        assertEquals("still shown while paused from here", mac.devices.me, onUi { pc.devices.shown()?.device })

        // Paused elsewhere and newer than anything here: offer to continue, once.
        onUi { pc.devices.checkContinue() }
        assertEquals(mac.devices.me, onUi { pc.devices.continueOffer?.device })
        onUi { pc.devices.dismissContinue(); pc.devices.checkContinue() }
        assertNull(onUi { pc.devices.continueOffer })

        // Next and play from the PC.
        onUi { pc.devices.control(mac.devices.me, "next"); pc.devices.control(mac.devices.me, "play") }
        eventually { onUiBlocking { mac.player.state.value.isPlaying } }
        eventually { onUiBlocking { pc.devices.shown()?.let { it.playing && it.current?.title == "Song 2" } == true } }

        // Starting music on the PC moves playback there: the Mac pauses and says so.
        onUi { pc.player.playSongs(listOf(library[4]), "Search", 0) }
        eventually { onUiBlocking { !mac.player.state.value.isPlaying } }
        eventually { mac.notes.contains("Now playing on GAMING-PC") }
        eventually { onUiBlocking { mac.devices.playback(pc.devices.me)?.current?.title == "Song 5" } }

        // Listen here on the Mac takes the PC's queue back at its position and pauses the PC.
        onUi { pc.player.seekTo(42_000) }
        eventually { onUiBlocking { (mac.devices.playback(pc.devices.me)?.positionMs ?: 0) >= 42_000 } }
        onUi { mac.devices.listenHere(pc.devices.me) }
        eventually { onUiBlocking { mac.player.state.value.isPlaying && mac.player.current?.title == "Song 5" } }
        assertTrue(onUi { mac.player.positionMs.value } >= 42_000)
        assertEquals("Search", onUi { mac.player.state.value.source })
        eventually { onUiBlocking { !pc.player.state.value.isPlaying } }

        // Unlinking reaches the removed device too.
        onUi { mac.devices.remove(pc.devices.me) }
        eventually { onUiBlocking { pc.devices.devices.isEmpty() } }
        assertTrue(onUi { mac.devices.devices.isEmpty() })
    }

    @Test fun linkedDevicesAndLastStateSurviveARestart() = runBlocking {
        val (mac, pc) = link()
        onUi { mac.player.playSongs(library.take(2), "Album", 0) }
        eventually { onUiBlocking { pc.devices.playback(mac.devices.me) != null } }
        onUi { pc.devices.setName("Office PC") }
        eventually { onUiBlocking { mac.devices.devices.single().name == "Office PC" } }
        onUi { pc.devices.flush() }
        val again = onUi { DeviceSyncRepository(pc.social, FakeDevicePlayer(), scope, File(dir, "pc"), resolveTrack = { error("unused") }, platform = "windows", hostName = { "GAMING-PC" }) }
        assertEquals(listOf(mac.devices.me), onUi { again.devices.map { it.id } })
        assertEquals("Office PC", onUi { again.name })
        assertNotNull(onUi { again.receivedAt(mac.devices.me) })
        assertEquals("Song 1", onUi { again.playback(mac.devices.me)?.current?.title })
    }

    @Test fun wrongCodeFailsAndNothingIsSentWithoutDevices() = runBlocking {
        val mac = onUi { computer("mac", "Mac.local", "macos") }
        onUi { mac.devices.enterCode("abc") }
        assertTrue(onUi { mac.devices.pairing } is DevicePairing.Failed)
        onUi { mac.player.playSongs(library.take(1), "Album", 0) }
        delay(DeviceSyncState.DEBOUNCE + 500)
        assertTrue(server.events.isEmpty())
        assertEquals("no relay without devices", 0, server.opened.get())
    }

    @Test fun sharedStateIsAValidWindowAroundTheCurrentSong() = runBlocking {
        val mac = onUi { computer("mac", "Mac.local", "macos") }
        val many = (1L..80L).map { song(it, "Track $it") }
        onUi { mac.player.playSongs(many, "Everything", 1_000); mac.player.state.value = mac.player.state.value.copy(currentIndex = 30) }
        val state = onUi { mac.devices.currentState()!! }
        assertTrue(state.valid())
        assertEquals(DevicePlayback.MAX_BEFORE + 1 + DevicePlayback.MAX_AFTER, state.queue.size)
        assertEquals("Track 31", state.current?.title)
        assertEquals("Mac", state.name); assertEquals("macos", state.platform)
        val next = onUi { mac.devices.currentState()!! }
        assertTrue(next.revision > state.revision || next.revision >= SocialRules.now - 1000)
        assertEquals("stable track ids between states", state.queue.map { it.id }, next.queue.map { it.id })
    }

    @Test fun names() {
        assertEquals("Calebs-MacBook-Pro", DeviceSyncRepository.cleanName(" Calebs-MacBook-Pro.local\n"))
        assertNull(DeviceSyncRepository.cleanName("  "))
        assertEquals(60, DeviceSyncRepository.cleanName("x".repeat(80))!!.length)
        assertEquals("Windows PC", DeviceSyncRepository.fallbackName("windows"))
        assertTrue(Regex("[0-9a-f]{16}").matches(DeviceSyncRepository.randomId()))
    }

    /** Plays "songs" by updating its state flows the way PlayerConnection does. */
    class FakeDevicePlayer : DevicePlayer {
        override val state = MutableStateFlow(PlayerUiState())
        override val positionMs = MutableStateFlow(0L)
        private val songs = mutableMapOf<Long, Song>()
        val current get() = state.value.currentId?.let(songs::get)
        override fun song(id: Long) = songs[id]
        override fun online(song: Song): OnlineTrack? = null
        override fun setPlaying(playing: Boolean) { if (state.value.hasMedia) state.value = state.value.copy(isPlaying = playing) }
        override fun next() { if (state.value.currentIndex + 1 < state.value.queue.size) { state.value = state.value.copy(currentIndex = state.value.currentIndex + 1); positionMs.value = 0 } }
        override fun previous() { state.value = state.value.copy(currentIndex = (state.value.currentIndex - 1).coerceAtLeast(0)); positionMs.value = 0 }
        override fun seekTo(positionMs: Long) { this.positionMs.value = positionMs }
        override fun playSongs(songs: List<Song>, source: String, startPositionMs: Long) {
            songs.forEach { this.songs[it.id] = it }
            state.value = state.value.copy(queue = songs.map { it.id }, currentIndex = 0, isPlaying = true, source = source)
            positionMs.value = startPositionMs
        }
        override fun appendFromSource(songs: List<Song>) { songs.forEach { this.songs[it.id] = it }; state.value = state.value.copy(queue = state.value.queue + songs.map { it.id }) }
    }
}

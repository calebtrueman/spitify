package com.localfy.app.data.sync

import com.localfy.app.data.DataFile
import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.SmartCollection
import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.db.PlayEventEntity
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.music.ArtistFollows
import com.localfy.app.data.music.MusicStreams
import com.localfy.app.data.music.OnlineArtist
import com.localfy.app.data.music.OnlineSearch
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.data.podcast.ParsedEpisode
import com.localfy.app.data.podcast.ParsedFeed
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.data.social.DevicePairing
import com.localfy.app.data.social.DeviceSyncRepository
import com.localfy.app.data.social.DeviceSyncRepositoryTest
import com.localfy.app.data.social.DeviceSyncState
import com.localfy.app.data.social.FakeRelay
import com.localfy.app.data.social.PeerRelay
import com.localfy.app.data.social.RelaySocketFactory
import com.localfy.app.data.social.SocialRepository
import com.localfy.app.data.social.eventually
import com.localfy.app.data.taste.ProfileRepository
import com.localfy.app.data.taste.TasteRepository
import com.localfy.app.desktop.JsonStore
import com.localfy.app.desktop.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nostrdevkit.sdk.Keys
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * Computers with real libraries, taste, podcasts and streams (each in its own data folder, with its
 * own key), linked through the device-sync pairing flow over the in-memory relay: their
 * LibrarySyncRepositories keep the libraries the same. Songs are catalogue streams saved to each
 * library (no audio files needed); a song one computer doesn't have arrives as the stream named by
 * its catalogue id.
 */
class LibrarySyncRepositoryTest {
    private val dir: File = Files.createTempDirectory("library-sync").toFile()
    private val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val server = FakeRelay()
    /** The relay connections: the in-memory relay, or the real public relays for [LiveLibrarySyncCheck]. */
    internal var sockets: RelaySocketFactory = server
    private var timeout = 20_000L
    private val computers = mutableListOf<Computer>()
    private val words = listOf("One", "Two", "Three", "Four")
    private val catalogue = (1..60).map { OnlineTrack("${1000 + it}", words.getOrNull(it - 1) ?: "Song $it", "Band", "Album", "500", 200_000, it, 1, null, true) }
    private val findable = java.util.concurrent.CopyOnWriteArrayList<OnlineTrack>()
    private fun track(title: String) = catalogue.first { it.title == title }
    private var feed = ParsedFeed("Show", "Host", "About", null, listOf(
        ParsedEpisode("a", "Episode A", "", "https://cdn.example/a.mp3", "audio/mpeg", 2_000_000, 600_000, null),
        ParsedEpisode("b", "Episode B", "", "https://cdn.example/b.mp3", "audio/mpeg", 1_000_000, 600_000, null),
    ))

    /** A fake settings store: two synced names, defaults 0 / "Dark". */
    class FakeSettings : SyncedSettings {
        val state = MutableStateFlow<Map<String, Any>>(mapOf("crossfadeMs" to 0, "themeMode" to "Dark"))
        override val changes = state
        override fun values() = state.value
        override fun defaults() = mapOf<String, Any>("crossfadeMs" to 0, "themeMode" to "Dark")
        override fun apply(name: String, value: Any) { if (name in state.value) state.value = state.value + (name to value) }
    }

    inner class Computer(val name: String, val host: String, val keys: Keys = Keys.generate(), streamsName: String = "music_streams") {
        val folder = File(dir, name).apply { mkdirs() }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + ui)
        private val bg = CoroutineScope(job + Dispatchers.Default)
        private val cache = File(folder, "cache")
        val social = SocialRepository(scope, folder, PeerRelay(keys, scope, socketFactory = sockets, outboxFile = File(folder, "outbox.json")))
        val devices = DeviceSyncRepository(social, DeviceSyncRepositoryTest.FakeDevicePlayer(), scope, folder, resolveTrack = { error("unused") }, platform = "macos", hostName = { host })
        val streams = MusicStreams(folder, streamsName, probe = { true })
        val library = LibraryRepository(bg, MetadataRepository(bg, OnlineArtRepository(cache, folder).apply { setEnabled(false) }, folder, cache), streams.saved, streams::lookup,
            folder, cache, listOf(File(folder, "Music").apply { mkdirs() }), watchFolders = false)
        val profiles = ProfileRepository(bg, folder)
        val taste = TasteRepository(bg, library, profiles, folder)
        val podcasts = PodcastRepository(bg, folder, File(folder, "podcasts"), readFeed = { feed })
        val follows = ArtistFollows(Prefs(File(folder, "prefs-artist_follows.json")), artistPage = { OnlineSearch() })
        val settings = FakeSettings()
        val sync = LibrarySyncRepository(scope, social, devices, library, taste, profiles, podcasts, streams, follows, settings,
            resolveTrack = { t -> (catalogue + findable).firstOrNull { it.title == t.title }?.let(streams::register) ?: error("No matching copy of “${t.title}” was found.") },
            dir = folder, reportDelay = 150, sendDelay = 200)

        fun save(vararg titles: String) = streams.save(titles.map(::track))
        fun id(title: String) = MusicStreams.streamId(track(title).id)
        fun song(id: Long) = library.library.value.songById[id] ?: streams.lookup(id)
        fun liked() = library.likedEntries.value.mapNotNull { song(it.songId)?.title }.toSet()
        fun playlist(name: String) = library.playlistDb.value.let { db ->
            val p = db.playlists.firstOrNull { it.name == name } ?: return@let null
            db.entries.filter { it.playlistId == p.id }.sortedBy { it.position }.mapNotNull { song(it.songId)?.title }
        }
        fun close() { runCatching { sync.flush(); social.close() }; job.cancel() }
    }

    private suspend fun <T> onUi(block: suspend () -> T): T = withContext(ui) { block() }
    private fun onUiBlocking(block: () -> Boolean): Boolean = runBlocking(ui) { block() }

    private suspend fun computer(name: String, keys: Keys = Keys.generate(), streamsName: String = "music_streams"): Computer {
        val c = onUi { Computer(name, name.replaceFirstChar(Char::uppercase), keys, streamsName) }
        computers += c
        c.library.refresh(); c.library.awaitScan()
        onUi { c.sync.start() }
        return c
    }

    /** Links [joining] to [owner]'s group the way a person does: a code shown on one, typed on the other, Allow. */
    private suspend fun link(owner: Computer, joining: Computer) {
        val code = onUi { owner.devices.showCode(); (owner.devices.pairing as DevicePairing.Showing).code }
        onUi { joining.devices.enterCode(DeviceSyncState.displayCode(code)) }
        eventually(timeout) { onUiBlocking { owner.devices.requests.isNotEmpty() } }
        onUi { owner.devices.approve(owner.devices.requests.single().first) }
        eventually(timeout) { onUiBlocking { joining.devices.pairing is DevicePairing.Linked } }
    }

    @After fun tearDown() {
        computers.forEach { it.close() }
        ui.close(); dir.deleteRecursively()
    }

    @Test fun likesPlaylistsHistoryAndRemovalsFollowAcrossComputers() = runBlocking {
        val mac = computer("mac"); val pc = computer("pc")
        // Before linking: different likes, and a playlist only on the Mac.
        mac.save("One", "Two"); pc.save("Two", "Three", "Four")
        mac.library.setLiked(mac.id("One"), true); mac.library.setLiked(mac.id("Two"), true)
        pc.library.setLiked(pc.id("Two"), true); pc.library.setLiked(pc.id("Three"), true)
        mac.library.createPlaylist("Road trip", listOf(mac.id("One"), mac.id("Two")))
        onUi { mac.settings.state.value = mac.settings.state.value + ("crossfadeMs" to 3000) }
        mac.profiles.setName("Caleb")

        link(mac, pc)
        // The libraries combine: every like on both, the playlist with its songs (One arrives on the PC as the stream).
        val all = setOf("One", "Two", "Three")
        runCatching { eventually(20_000) { mac.liked() == all && pc.liked() == all } }.onFailure { println("DEBUG mac=${mac.liked()} pc=${pc.liked()} macLiked=${mac.library.likedEntries.value} pcLiked=${pc.library.likedEntries.value} pcStatus=${pc.sync.status.value} macStatus=${mac.sync.status.value}"); throw it }
        eventually(20_000) { pc.playlist("Road trip") == listOf("One", "Two") }
        // A fresh device's defaults don't reset the other's choices; the choice itself follows.
        eventually(20_000) { pc.settings.state.value["crossfadeMs"].toString() == "3000" }
        assertEquals("3000", mac.settings.state.value["crossfadeMs"].toString())
        eventually(20_000) { pc.profiles.profile.value.name == "Caleb" }

        // Unliking on the PC unlikes on the Mac, and it stays unliked.
        pc.library.setLiked(pc.id("Two"), false)
        eventually(20_000) { mac.liked() == setOf("One", "Three") }
        delay(1_000)
        assertEquals(setOf("One", "Three"), mac.liked()); assertEquals(setOf("One", "Three"), pc.liked())

        // Both add to the playlist at once: both additions stay, in the same order everywhere.
        val macList = mac.library.playlistDb.value.playlists.single { it.name == "Road trip" }.id
        val pcList = pc.library.playlistDb.value.playlists.single { it.name == "Road trip" }.id
        mac.save("Three")
        mac.library.appendToPlaylist(macList, listOf(mac.id("Three")))
        pc.library.appendToPlaylist(pcList, listOf(pc.id("Four")))
        eventually(20_000) { mac.playlist("Road trip")?.toSet() == setOf("One", "Two", "Three", "Four") && pc.playlist("Road trip")?.toSet() == setOf("One", "Two", "Three", "Four") }
        eventually(20_000) { mac.playlist("Road trip") == pc.playlist("Road trip") }

        // A listen on the PC shows up in the Mac's Recently played, counts and listening history.
        val playedAt = System.currentTimeMillis() - 60_000
        pc.taste.record(PlayEventEntity(songId = pc.id("Two"), startedAt = playedAt, listenedMs = 190_000, durationMs = 200_000, completed = true, skipped = false, source = "Album"))
        pc.library.recordPlay(pc.id("Two"))
        eventually(20_000) { mac.library.smart.value[SmartCollection.Kind.RecentlyPlayed]?.songs?.any { it.title == "Two" } == true }
        assertEquals(1, mac.library.stats.value[mac.id("Two")]?.playCount)
        assertTrue("the Mac's own counts stay its own", mac.library.ownStats.value[mac.id("Two")] == null)
        eventually(20_000) { mac.taste.events.value.any { it.songId == mac.id("Two") && it.startedAt == playedAt && it.source?.startsWith(TasteRepository.SYNC_SOURCE) == true } }

        // Deleting the playlist deletes it everywhere.
        mac.library.deletePlaylist(macList).join()
        eventually(20_000) { pc.playlist("Road trip") == null }

        // Status: nothing left to send, nothing missing.
        eventually(20_000) { onUiBlocking { mac.sync.status.value.let { it.active && it.syncing == 0 && it.lastSync > 0 && it.unmatched.isEmpty() } } }
    }

    @Test fun threeComputersAndFollowsHiddenItemsPodcastsAndProgress() = runBlocking {
        val mac = computer("mac"); val pc = computer("pc"); val laptop = computer("laptop")
        mac.save("One"); pc.save("Two"); laptop.save("Three")
        mac.library.setLiked(mac.id("One"), true)
        pc.library.setLiked(pc.id("Two"), true)
        laptop.library.setLiked(laptop.id("Three"), true)
        mac.follows.follow(OnlineArtist("77", "Band", null), emptyList())
        pc.taste.hideArtist("Some Artist")
        mac.podcasts.subscribe("https://feed.example/show")

        link(mac, pc); link(mac, laptop)
        val all = setOf("One", "Two", "Three")
        eventually(30_000) { listOf(mac, pc, laptop).all { it.liked() == all } }
        eventually(20_000) { listOf(pc, laptop).all { it.follows.contains("77") } }
        eventually(20_000) { listOf(mac, laptop).all { "Some Artist" in it.taste.hiddenArtists.value } }
        // The podcast is followed everywhere (its feed read on each computer).
        eventually(20_000) { listOf(pc, laptop).all { c -> c.podcasts.shows.value.any { it.podcast.feedUrl == "https://feed.example/show" && it.podcast.subscribedAt > 0 } } }

        // Progress: the newest position wins, even when it's earlier in the episode.
        fun Computer.episodeKey() = "ep:" + podcasts.shows.value.single().episodes.single { it.guid == "a" }.id
        fun Computer.position() = podcasts.resume.value[episodeKey()]?.positionMs
        mac.podcasts.saveProgress(mac.episodeKey(), 61_000, 600_000)
        eventually(20_000) { pc.position() == 60_000L && laptop.position() == 60_000L }
        pc.podcasts.saveProgress(pc.episodeKey(), 122_000, 600_000)
        eventually(20_000) { mac.position() == 120_000L && laptop.position() == 120_000L }
        laptop.podcasts.saveProgress(laptop.episodeKey(), 30_000, 600_000)
        eventually(20_000) { mac.position() == 30_000L && pc.position() == 30_000L }

        // Unfollowing on the laptop unfollows everywhere.
        laptop.follows.unfollow("77")
        eventually(20_000) { !mac.follows.contains("77") && !pc.follows.contains("77") }
        // Three-way digests agree once everything has settled.
        eventually(20_000) { onUiBlocking { listOf(mac, pc, laptop).all { it.sync.status.value.syncing == 0 } } }
    }

    @Test fun aLibraryThatHasntLoadedDoesntWipeTheOther() = runBlocking {
        val mac = computer("mac"); var pc = computer("pc")
        val titles = (5..44).map { "Song $it" }
        mac.save(*titles.toTypedArray())
        titles.forEach { mac.library.setLiked(mac.id(it), true) }
        link(mac, pc)
        eventually(30_000) { pc.liked().size == 40 }

        // The PC restarts with none of its songs readable yet (e.g. the stream list is missing): its likes are still there by id.
        delay(1_000)
        pc.library.flush(); JsonStore.flushAll(); DataFile.flushAll()
        val keys = pc.keys
        pc.close(); computers -= pc
        pc = computer("pc", keys, streamsName = "unreadable_streams")
        assertEquals(40, pc.library.likedEntries.value.size)
        delay(3_000)
        assertEquals("nothing removed on the Mac", 40, mac.liked().size)
        assertEquals(40, pc.library.likedEntries.value.size)
        // And it still syncs: a new like on the Mac arrives.
        mac.save("Song 45"); mac.library.setLiked(mac.id("Song 45"), true)
        eventually(20_000) { pc.library.likedEntries.value.any { it.songId == pc.id("Song 45") } }
        assertEquals(41, mac.liked().size)
    }

    @Test fun songsThatCantBeFoundAreListedAndRetried() = runBlocking {
        val mac = computer("mac"); val pc = computer("pc")
        // A song with no catalogue id that the PC can't find (yet).
        val home = OnlineTrack("home-1", "Home Recording", "Me", "Demos", "", 100_000, 1, 1, null, true)
        mac.streams.save(listOf(home))
        mac.library.setLiked(MusicStreams.streamId(home.id), true)
        link(mac, pc)
        eventually(20_000) { onUiBlocking { pc.sync.status.value.unmatched.map { it.title } == listOf("Home Recording") } }
        assertTrue(pc.liked().isEmpty())
        // Found later (Retry): it's liked here too, and it was never removed on the Mac meanwhile.
        findable += home
        onUi { pc.sync.retryUnmatched() }
        eventually(20_000) { pc.liked() == setOf("Home Recording") }
        eventually(20_000) { onUiBlocking { pc.sync.status.value.unmatched.isEmpty() } }
        assertEquals(setOf("Home Recording"), mac.liked())
    }

    /**
     * Two computers over the real relays (run by [LiveLibrarySyncCheck]): link, union of likes and a
     * playlist, an unlike, a listen. Prints how long each step took.
     */
    internal fun live() = runBlocking {
        timeout = 90_000
        try {
            val started = System.currentTimeMillis()
            fun lap(what: String) = println("LIVE $what after ${System.currentTimeMillis() - started} ms")
            val mac = computer("LiveMac"); val pc = computer("LivePC")
            mac.save("One", "Two"); pc.save("Two", "Three")
            mac.library.setLiked(mac.id("One"), true); pc.library.setLiked(pc.id("Three"), true)
            mac.library.createPlaylist("Live trip", listOf(mac.id("One"), mac.id("Two")))
            delay(3_000) // let both relays connect
            link(mac, pc); lap("linked")
            eventually(timeout) { mac.liked() == setOf("One", "Three") && pc.liked() == setOf("One", "Three") }; lap("likes merged")
            eventually(timeout) { pc.playlist("Live trip") == listOf("One", "Two") }; lap("playlist arrived")
            val unlikeAt = System.currentTimeMillis()
            pc.library.setLiked(pc.id("One"), false)
            eventually(timeout) { mac.liked() == setOf("Three") }; println("LIVE unlike arrived ${System.currentTimeMillis() - unlikeAt} ms after it was made")
            pc.taste.record(PlayEventEntity(songId = pc.id("Two"), startedAt = System.currentTimeMillis() - 1_000, listenedMs = 180_000, durationMs = 200_000, completed = true, skipped = false, source = null))
            pc.library.recordPlay(pc.id("Two"))
            eventually(timeout) { mac.library.smart.value[SmartCollection.Kind.RecentlyPlayed]?.songs?.any { it.title == "Two" } == true }; lap("listen in Recently played")
            onUi { pc.devices.leave() }
            delay(3_000)
        } finally { tearDown() }
    }
}

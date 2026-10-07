package com.localfy.app.data.sync

import com.localfy.app.data.social.SharedTrack
import com.localfy.app.data.social.SocialPacket
import com.localfy.app.data.sync.LibrarySync.Companion.HISTORY
import com.localfy.app.data.sync.LibrarySync.Companion.LIKED
import com.localfy.app.data.sync.LibrarySync.Companion.PLAYLISTS
import com.localfy.app.data.sync.LibrarySync.Companion.PROGRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Two or three devices running the real [LibrarySyncRepository] over an in-memory relay, each with a
 * fake library standing in for Room and the repositories. Debounces are zero; [settle] lets every
 * queued report, send and apply run.
 */
class LibrarySyncRepositoryTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private var now = 1_000_000L

    private class Bus { val devices = mutableMapOf<String, Device>(); val sent = mutableListOf<String>() }

    private class Link(override val me: String, val bus: Bus) : SyncLink {
        var linked = emptySet<String>()
        override fun devices() = linked
        override suspend fun send(packet: SocialPacket, logical: String, recipient: String, expiresIn: Long) {
            bus.sent += "${packet.type}:$logical"
            // Through JSON, like the relay.
            bus.devices[recipient]?.repo?.receive(me, SocialPacket.parse(JSONObject(packet.json().toString())), true)
        }
    }

    /** A library like the phone's: likes, playlists, listening progress and history, plus which songs it can find. */
    private class Library(val songs: MutableSet<String>) : LibraryHost {
        lateinit var repo: LibrarySyncRepository
        var loaded = true
        val liked = linkedMapOf<String, JSONObject>()
        val playlists = linkedMapOf<Long, Pair<String, MutableList<SharedTrack>>>()
        val progress = mutableMapOf<String, JSONObject>()
        val heard = mutableListOf<JSONObject>()
        var remote: Map<String, Map<String, JSONObject>> = emptyMap()
        var stored: String? = null
        private var nextId = 1L
        override suspend fun load() = stored
        override fun save(json: String) { stored = json }
        override val topics = setOf(LIKED, PLAYLISTS, PROGRESS)
        override fun remoteStats(byDevice: Map<String, Map<String, JSONObject>>) { remote = byDevice }
        override suspend fun localHistory() = emptyList<Pair<String, JSONObject>>()

        override suspend fun snapshot(topic: String): Map<String, Map<String, JSONObject>>? = when (topic) {
            LIKED -> if (!loaded) null else mapOf(LIKED to liked.mapValues { JSONObject(it.value.toString()) })
            PROGRESS -> mapOf(PROGRESS to progress.mapValues { JSONObject(it.value.toString()) })
            PLAYLISTS -> if (!loaded) null else buildMap {
                val ids = playlists.keys.associateWith { repo.playlistIds.global(it) }
                put(PLAYLISTS, playlists.entries.associate { (id, p) -> ids.getValue(id) to JSONObject().put("name", p.first) })
                playlists.forEach { (id, p) ->
                    val gid = ids.getValue(id)
                    val keys = LibrarySync.entryKeys(p.second)
                    val previous = repo.read { s -> s.present(LibrarySync.playlist(gid)).mapValues { it.value.optInt("pos", -1) } }
                    val pos = LibrarySyncRepository.positions(keys, previous)
                    put(LibrarySync.playlist(gid), keys.indices.associate { keys[it] to LibrarySync.entryValue(p.second[it], pos[it]) })
                }
            }
            else -> null
        }

        override suspend fun apply(collection: String, changes: List<SyncChange>, present: Map<String, JSONObject>): List<SyncChange> = when {
            collection == LIKED -> changes.filter { c ->
                if (!c.present) { liked.remove(c.key); true } else if (c.key in songs) { liked[c.key] = c.value!!; true } else false
            }
            collection == PLAYLISTS -> changes.onEach { c ->
                val local = repo.playlistIds.local(c.key)
                if (!c.present) { local?.let(playlists::remove); return@onEach }
                val id = local ?: nextId++.also { repo.playlistIds.map(c.key, it) }
                playlists[id] = c.value!!.getString("name") to (playlists[id]?.second ?: mutableListOf())
            }
            collection.startsWith("playlist:") -> {
                val id = repo.playlistIds.local(collection.removePrefix("playlist:"))
                if (id == null) emptyList() else {
                    val wanted = present.entries.sortedWith(compareBy({ it.value.optInt("pos") }, { it.key })).filter { it.key.substringBeforeLast('#') in songs }
                    playlists[id] = playlists.getValue(id).first to wanted.mapNotNull { LibrarySync.track(it.value) }.toMutableList()
                    val found = wanted.map { it.key }.toSet()
                    changes.filter { !it.present || it.key in found }
                }
            }
            collection == HISTORY -> changes.onEach { heard += it.value!! }
            collection == PROGRESS -> changes.onEach { c ->
                val local = progress[c.key]
                // Newer wins: this device's later listen stays, and goes out again as the newer one.
                if (c.present && (local == null || local.optLong("_at") <= c.value!!.optLong("_at"))) progress[c.key] = c.value!!
            }
            else -> changes
        }
    }

    private class Device(val repo: LibrarySyncRepository, val library: Library, val link: Link)

    private fun song(title: String) = SharedTrack(title = title, artist = "Band", durationMs = 200_000)
    private fun key(title: String) = LibrarySync.trackKey(song(title))
    private fun liked(vararg titles: String) = titles.associate { key(it) to LibrarySync.trackValue(song(it)).put("_addedAt", 1) }

    private fun CoroutineScope.device(bus: Bus, id: String, vararg findable: String): Device {
        val library = Library(findable.map(::key).toMutableSet())
        val link = Link(id, bus)
        val timing = LibrarySyncRepository.Timing(reportDebounce = 0, sendQuiet = 0, sendLongest = 0, saveEvery = 0, libraryRetry = 0, digestReply = 0, sendRetry = 1_000_000)
        // The clock moves on every read, like a real one.
        val repo = LibrarySyncRepository(link, library, this, { ++now }, timing, EmptyCoroutineContext)
        library.repo = repo
        return Device(repo, library, link).also { bus.devices[id] = it }
    }

    private suspend fun settle() = repeat(2_000) { yield() }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try { kotlinx.coroutines.withTimeout(30_000) { scope.block() } } finally { scope.cancel() }
    }

    /** Both devices start on their own, then link: each sends a digest and the libraries combine. */
    private suspend fun link(vararg devices: Device) {
        devices.forEach { it.repo.startNow() }
        settle()
        val ids = devices.map { it.link.me }.toSet()
        devices.forEach { d -> d.link.linked = ids - d.link.me; d.repo.devicesChanged(d.link.linked) }
        settle()
    }

    private fun Library.titles(id: Long) = playlists.getValue(id).second.map { it.title }

    @Test fun linkingCombinesLikesAndPlaylists() = test {
        val bus = Bus()
        val phone = device(bus, a, "One", "Two", "Three", "Road 1", "Road 2")
        val mac = device(bus, b, "One", "Two", "Three", "Road 1", "Road 2")
        phone.library.liked += liked("One", "Two")
        phone.library.playlists[7] = "Road trip" to mutableListOf(song("Road 1"), song("Road 2"))
        mac.library.liked += liked("Two", "Three")
        link(phone, mac)
        assertEquals(setOf(key("One"), key("Two"), key("Three")), phone.library.liked.keys)
        assertEquals(phone.library.liked.keys, mac.library.liked.keys)
        val road = mac.library.playlists.entries.single()
        assertEquals("Road trip", road.value.first)
        assertEquals(listOf("Road 1", "Road 2"), mac.library.titles(road.key))
        assertEquals(phone.repo.sync.digest(), mac.repo.sync.digest())
        // Nothing to say after converging: reporting again sends nothing.
        val before = bus.sent.size
        phone.repo.changed(LIKED); mac.repo.changed(LIKED); phone.repo.changed(PLAYLISTS); mac.repo.changed(PLAYLISTS); settle()
        assertEquals(before, bus.sent.size)
    }

    @Test fun unlikeOnOneDeviceRemovesItEverywhereAndItStaysRemoved() = test {
        val bus = Bus()
        val phone = device(bus, a, "One", "Two"); val mac = device(bus, b, "One", "Two")
        phone.library.liked += liked("One", "Two")
        link(phone, mac)
        assertTrue(key("One") in mac.library.liked)
        now += 10_000
        phone.library.liked.remove(key("One")); phone.repo.changed(LIKED); settle()
        assertEquals(setOf(key("Two")), mac.library.liked.keys)
        // The Mac reports its (now smaller) library: it doesn't bring the song back anywhere.
        mac.repo.changed(LIKED); phone.repo.changed(LIKED); settle()
        assertEquals(setOf(key("Two")), phone.library.liked.keys)
        assertEquals(setOf(key("Two")), mac.library.liked.keys)
    }

    @Test fun songsAddedToAPlaylistOnTwoDevicesBothStay() = test {
        val bus = Bus()
        val all = arrayOf("One", "Two", "Phone add", "Mac add")
        val phone = device(bus, a, *all); val mac = device(bus, b, *all)
        phone.library.playlists[1] = "Mix" to mutableListOf(song("One"), song("Two"))
        link(phone, mac)
        val macId = mac.library.playlists.keys.single()
        // Both edit before hearing from the other (offline), then reconnect.
        val links = listOf(phone, mac).associateWith { it.link.linked }
        phone.link.linked = emptySet(); mac.link.linked = emptySet()
        now += 10_000; phone.library.playlists.getValue(1).second += song("Phone add"); phone.repo.changed(PLAYLISTS)
        now += 1; mac.library.playlists.getValue(macId).second += song("Mac add"); mac.repo.changed(PLAYLISTS)
        settle()
        links.forEach { (d, l) -> d.link.linked = l }
        phone.repo.foreground(); mac.repo.foreground(); now += 60 * 60_000; phone.repo.foreground(); mac.repo.foreground(); settle()
        assertEquals(listOf("One", "Two", "Mac add", "Phone add").sorted(), phone.library.titles(1).sorted())
        assertEquals(phone.library.titles(1), mac.library.titles(macId))
        assertEquals(listOf("One", "Two"), phone.library.titles(1).take(2))
    }

    @Test fun aListenOnAnotherDeviceShowsUpInHistory() = test {
        val bus = Bus()
        val phone = device(bus, a, "One"); val mac = device(bus, b, "One")
        link(phone, mac)
        val at = now
        val value = LibrarySync.trackValue(song("One")).put("playedAt", at).put("listenedMs", 180_000).put("durationMs", 200_000).put("skipped", false)
        phone.repo.played("${a.take(8)}:$at:${key("One")}", value); settle()
        assertEquals(1, mac.library.heard.size)
        assertEquals(at, mac.library.heard.single().getLong("playedAt"))
        assertEquals(setOf("${a.take(8)}:$at:${key("One")}"), mac.repo.sync.present(HISTORY).keys)
    }

    @Test fun aLibraryThatHasntLoadedDoesntWipeAnything() = test {
        val bus = Bus()
        val titles = (1..30).map { "S$it" }.toTypedArray()
        val phone = device(bus, a, *titles); val mac = device(bus, b, *titles)
        phone.library.liked += liked(*titles)
        link(phone, mac)
        assertEquals(30, mac.library.liked.size)
        // The Mac restarts with its library not read yet: nothing is reported, so nothing is removed.
        now += 10_000
        mac.library.loaded = false; mac.library.liked.clear(); mac.repo.changed(LIKED); settle()
        assertEquals(30, phone.library.liked.size)
        // Half-loaded (a few songs read so far) is refused by the rules too.
        mac.library.loaded = true; mac.library.liked += liked("S1", "S2"); mac.repo.changed(LIKED); settle()
        assertEquals(30, phone.library.liked.size)
        assertEquals(30, phone.repo.sync.present(LIKED).size)
    }

    @Test fun newerProgressWinsEvenWhenTheOlderOneIsReportedLater() = test {
        val bus = Bus()
        val phone = device(bus, a); val mac = device(bus, b)
        val episode = "ep:https://example.com/feed.xml|guid-1"
        fun at(position: Long, time: Long) = JSONObject().put("positionMs", position).put("durationMs", 3_600_000).put("played", false).put("_at", time)
        // The phone listened most recently (to 10:00); the Mac's 2:00 is older, but the Mac starts syncing later.
        phone.library.progress[episode] = at(600_000, 5_000)
        mac.library.progress[episode] = at(120_000, 1_000)
        phone.repo.startNow(); settle()
        now += 60_000
        link(phone, mac)
        assertEquals(600_000, phone.library.progress.getValue(episode).getLong("positionMs"))
        assertEquals(600_000, mac.library.progress.getValue(episode).getLong("positionMs"))
        // A newer listen on the Mac then wins everywhere.
        now += 60_000
        mac.library.progress[episode] = at(900_000, 9_000); mac.repo.changed(PROGRESS); settle()
        assertEquals(900_000, phone.library.progress.getValue(episode).getLong("positionMs"))
    }

    @Test fun songsThatCantBeFoundStayPendingAndAreListed() = test {
        val bus = Bus()
        val phone = device(bus, a, "Common", "Rare"); val mac = device(bus, b, "Common")
        phone.library.liked += liked("Common", "Rare")
        link(phone, mac)
        assertEquals(setOf(key("Common")), mac.library.liked.keys)
        assertEquals(listOf("Rare"), mac.repo.status.value.unmatched.map { it.title })
        // Not a removal: the phone keeps it.
        mac.repo.changed(LIKED); settle()
        assertTrue(key("Rare") in phone.library.liked)
        // The song turns up here (a download finished): retried and found.
        mac.library.songs += key("Rare"); mac.repo.libraryChanged(); settle()
        assertTrue(key("Rare") in mac.library.liked)
        assertTrue(mac.repo.status.value.unmatched.isEmpty())
        assertFalse(mac.repo.status.value.syncing > 0)
    }

    @Test fun stateSurvivesARestart() = test {
        val bus = Bus()
        val phone = device(bus, a, "One"); val mac = device(bus, b, "One")
        phone.library.liked += liked("One")
        link(phone, mac)
        val saved = mac.library.stored!!
        val again = device(Bus(), b, "One").also { it.library.stored = saved; it.library.liked += mac.library.liked }
        again.repo.startNow(); settle()
        assertEquals(mac.repo.sync.digest(), again.repo.sync.digest())
        assertEquals(mac.repo.playlistIds.json().toString(), again.repo.playlistIds.json().toString())
    }

    @Test fun playlistPositionsKeepSharedOnesWhereTheOrderAgrees() {
        // Songs this device couldn't find leave gaps; adding at the end doesn't move anything.
        assertEquals(listOf(0, 2, 3), LibrarySyncRepository.positions(listOf("a", "c", "d"), mapOf("a" to 0, "b" to 1, "c" to 2)))
        // A song moved to the front gets renumbered without disturbing the rest when there's room.
        assertEquals(listOf(0, 5, 10), LibrarySyncRepository.positions(listOf("x", "a", "b"), mapOf("a" to 5, "b" to 10)))
        // No room: everything is renumbered.
        assertEquals(listOf(0, 1, 2), LibrarySyncRepository.positions(listOf("b", "a", "c"), mapOf("a" to 0, "b" to 1, "c" to 2)))
    }
}

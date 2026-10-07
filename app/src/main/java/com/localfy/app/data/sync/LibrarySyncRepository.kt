package com.localfy.app.data.sync

import com.localfy.app.data.social.SharedTrack
import com.localfy.app.data.social.SocialPacket
import com.localfy.app.data.social.SocialRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** What library sync needs from the device group and its relay. */
interface SyncLink {
    val me: String
    /** The devices linked now; sync packets are accepted from these only. */
    fun devices(): Set<String>
    suspend fun send(packet: SocialPacket, logical: String, recipient: String, expiresIn: Long)
}

/**
 * This device's library, as library sync sees it. The engine calls these on its own thread; the
 * implementation does its database and UI-state work wherever it belongs, never on the main thread
 * for I/O.
 */
interface LibraryHost {
    suspend fun load(): String?
    /** Writes off the main thread. */
    fun save(json: String)
    /**
     * What this platform reports. A topic is a collection name, except [LibrarySync.PLAYLISTS], whose
     * snapshot also holds every "playlist:<id>".
     */
    val topics: Set<String>
    /** collection → key → value for [topic] now, or null while it hasn't loaded (an unloaded library would read as "remove everything"). */
    suspend fun snapshot(topic: String): Map<String, Map<String, JSONObject>>?
    /**
     * Makes remote changes true here. [present] is everything the collection holds after merging.
     * Returns the changes done (or already true); the rest stay pending and are retried.
     */
    suspend fun apply(collection: String, changes: List<SyncChange>, present: Map<String, JSONObject>): List<SyncChange>
    /** Every other device's play counts (device → trackKey → value), whenever they change. */
    fun remoteStats(byDevice: Map<String, Map<String, JSONObject>>)
    /** This device's own listens still within the history window, as history keys and values. */
    suspend fun localHistory(): List<Pair<String, JSONObject>>
    /** Retry pressed: forget earlier failed searches for these songs. */
    fun retrying(tracks: List<SharedTrack>) {}
    /** Platform state kept in the same file (e.g. which local songs had which key). */
    fun exportState(): JSONObject = JSONObject()
    fun importState(o: JSONObject) {}
}

/** Settings › Your devices: "Library: in sync" / "syncing N items…" and the songs that couldn't be found. */
data class LibrarySyncStatus(val started: Boolean = false, val syncing: Int = 0, val lastSync: Long = 0, val unmatched: List<SharedTrack> = emptyList())

/** Global playlist id (UUID) ↔ this device's playlist id. Thread-safe. */
class PlaylistIds {
    private val toLocal = HashMap<String, Long>()
    private val toGlobal = HashMap<Long, String>()
    @Synchronized fun local(global: String): Long? = toLocal[global]
    /** The global id of a local playlist; a playlist made here gets a new one. */
    @Synchronized fun global(local: Long): String = toGlobal[local] ?: UUID.randomUUID().toString().also { map(it, local) }
    @Synchronized fun globalOrNull(local: Long): String? = toGlobal[local]
    @Synchronized fun map(global: String, local: Long) { toLocal.remove(global)?.let(toGlobal::remove); toGlobal.remove(local)?.let(toLocal::remove); toLocal[global] = local; toGlobal[local] = global }
    @Synchronized fun forget(global: String) { toLocal.remove(global)?.let(toGlobal::remove) }
    /** Playlists being created from another device right now: until they're mapped, a snapshot would give them new ids. */
    @Volatile var creating = 0; private set
    suspend fun <T> creating(block: suspend () -> T): T {
        synchronized(this) { creating++ }
        try { return block() } finally { synchronized(this) { creating-- } }
    }
    @Synchronized fun json(): JSONObject = JSONObject().also { o -> toLocal.forEach { (g, l) -> o.put(g, l) } }
    @Synchronized fun load(o: JSONObject) { toLocal.clear(); toGlobal.clear(); o.keys().forEach { g -> map(g, o.getLong(g)) } }
}

/**
 * Library sync on this device (docs/library-sync.md): reports what the library holds, sends changed
 * documents and digests to linked devices through the device relay, applies what they send, and
 * retries songs that couldn't be matched. [LibrarySync] isn't thread-safe, so every bit of state
 * lives on [context] (one background thread on Android); the host hops elsewhere for its own work.
 */
class LibrarySyncRepository(
    private val link: SyncLink,
    private val host: LibraryHost,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SocialRules.now },
    private val timing: Timing = Timing(),
    private val context: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) {
    data class Timing(
        val reportDebounce: Long = 2_000, val sendQuiet: Long = 3_000, val sendLongest: Long = 15_000, val saveEvery: Long = 2_000,
        val digestEvery: Long = 6 * 60 * 60_000L, val foregroundDigest: Long = 10 * 60_000L, val retryEvery: Long = 60 * 60_000L,
        val libraryRetry: Long = 10_000, val digestReply: Long = 60_000, val sendRetry: Long = 60_000,
    )

    val me = link.me
    val sync = LibrarySync(me, clock)
    val playlistIds = PlaylistIds()
    private val _status = MutableStateFlow(LibrarySyncStatus())
    val status: StateFlow<LibrarySyncStatus> = _status.asStateFlow()

    private var started = false
    private var starting = false
    /** Bumped when a change is applied to a topic, so a snapshot taken before it is never reported. */
    private val generations = HashMap<String, Long>()
    private val dirtyTopics = LinkedHashSet<String>()
    private val explicitTopics = HashSet<String>()
    private var reportJob: Job? = null
    private var reporting = false
    private val dirtyDocs = LinkedHashSet<String>()
    private var firstDirtyAt = 0L
    private var sendJob: Job? = null
    private var sending = false
    private var saveJob: Job? = null
    private val incoming = ArrayDeque<Pair<String, SocialPacket>>()
    private var worker: Job? = null
    private var applying = 0
    private var retryJob: Job? = null
    private var lastDigest = 0L
    private val digestReplied = HashMap<String, Long>()
    private var lastSync = 0L
    private var linked = emptySet<String>()
    /** (collection, key) of songs that couldn't be matched on the last try. */
    private val unmatched = LinkedHashMap<Pair<String, String>, SharedTrack>()

    private fun spawn(block: suspend CoroutineScope.() -> Unit) = scope.launch(context, block = block)

    // ---- Lifecycle ----

    /** Loads the saved state, reports everything once, then sends a digest. Call once devices are linked. */
    fun start() = spawn { startNow() }

    suspend fun startNow(): Unit = withContext(context) {
        if (started || starting) return@withContext
        starting = true
        val text = runCatching { host.load() }.getOrNull()
        text?.let { runCatching { restore(JSONObject(it)) } }
        sync.trim()
        linked = link.devices()
        forgetUnlinked()
        started = true; starting = false
        host.remoteStats(remoteStats())
        runCatching { host.localHistory() }.getOrDefault(emptyList()).forEach { (k, v) -> noteChanged(sync.add(LibrarySync.HISTORY, k, v)) }
        host.topics.forEach { dirtyTopics += it }
        flushReports()
        sendDigest(link.devices())
        process()
        spawn { while (isActive) { delay(timing.digestEvery); sendDigest(link.devices()) } }
        spawn { while (isActive) { delay(timing.retryEvery); retryPending() } }
        // Anything already received that couldn't be applied before (e.g. the app was killed) is retried now.
        retryPending()
        publish()
    }

    private fun restore(o: JSONObject) {
        o.optJSONObject("sync")?.let(sync::load)
        o.optJSONObject("playlists")?.let(playlistIds::load)
        o.optJSONObject("host")?.let { runCatching { host.importState(it) } }
        lastSync = o.optLong("lastSync")
    }

    fun json(): JSONObject = JSONObject().put("sync", sync.json()).put("playlists", playlistIds.json()).put("host", host.exportState()).put("lastSync", lastSync)

    /** The linked devices changed: a new one gets a digest (libraries combine), a removed one's counts are forgotten. */
    fun devicesChanged(devices: Set<String>) = spawn {
        val added = devices - linked; val removed = linked - devices
        linked = devices
        if (!started) return@spawn
        if (removed.isNotEmpty()) { forgetUnlinked(); host.remoteStats(remoteStats()); markSaved() }
        if (added.isNotEmpty()) sendDigest(added)
    }

    private fun forgetUnlinked() {
        sync.collections().filter { it.startsWith("stats:") && it != LibrarySync.stats(me) && it.removePrefix("stats:") !in linked }.forEach(sync::forget)
    }

    /** The app came to the foreground: a digest, at most every [Timing.foregroundDigest]. */
    fun foreground() = spawn { if (started && clock() - lastDigest >= timing.foregroundDigest) sendDigest(link.devices()) }

    /** The network is back, or the library changed: unmatched songs may be found now. */
    fun libraryChanged() = spawn {
        if (!started || retryJob?.isActive == true) return@spawn
        retryJob = spawn { delay(timing.libraryRetry); retryPending() }
    }

    /** Settings › Your devices › Retry. */
    fun retry() = spawn {
        host.retrying(unmatched.values.toList())
        unmatched.clear(); publish(); retryPending()
    }

    // ---- Reporting ----

    /** A topic might have changed; it's reported after [Timing.reportDebounce] of quiet. [explicit]: a user action that may remove many items. */
    fun changed(topic: String, explicit: Boolean = false) = spawn {
        dirtyTopics += topic; if (explicit) explicitTopics += topic
        if (started) scheduleReport(restart = true)
    }

    /** History: one new local listen. */
    fun played(key: String, value: JSONObject) = spawn { if (started) noteChanged(sync.add(LibrarySync.HISTORY, key, value)) }

    private fun scheduleReport(restart: Boolean) {
        // A report that's already taking its snapshot is never cancelled: its topics left the dirty set.
        if (reportJob?.isActive == true && (!restart || reporting)) return
        reportJob?.cancel()
        reportJob = spawn { delay(timing.reportDebounce); flushReports() }
    }

    /** Reports every dirty topic now. One that hasn't loaded stays dirty until the host says it changed again. */
    suspend fun flushReports(): Unit = withContext(context) {
        if (reporting) return@withContext
        reporting = true
        var raced = false
        try {
            val topics = dirtyTopics.toList(); dirtyTopics.clear()
            topics.forEach { if (report(it) == Report.Raced) raced = true }
        } finally { reporting = false }
        if (raced) { reportJob = null; scheduleReport(restart = false) }
    }

    private enum class Report { Done, NotLoaded, Raced }

    private suspend fun report(topic: String): Report {
        val generation = generations[topic] ?: 0
        val snapshot = try { host.snapshot(topic) } catch (e: Exception) { if (e is CancellationException) throw e; null }
        if (snapshot == null) { dirtyTopics += topic; return Report.NotLoaded }
        // A change applied here meanwhile would be undone by this older snapshot.
        if ((generations[topic] ?: 0) != generation) { dirtyTopics += topic; return Report.Raced }
        val explicit = explicitTopics.remove(topic)
        val playlistsBefore = if (topic == LibrarySync.PLAYLISTS) sync.present(LibrarySync.PLAYLISTS).keys else emptySet()
        val changed = HashSet<String>()
        // The playlist list goes first, so a deleted playlist's songs are forgotten rather than emptied.
        snapshot.entries.sortedBy { if (it.key == LibrarySync.PLAYLISTS) 0 else 1 }.forEach { (collection, items) ->
            val entries = collection.startsWith("playlist:")
            if (entries && collection.removePrefix("playlist:") !in sync.present(LibrarySync.PLAYLISTS)) return@forEach
            // A playlist's songs come straight from the database: emptying one is a real edit.
            changed += sync.report(collection, items, allowMassRemoval = explicit || entries)
        }
        if (topic == LibrarySync.PLAYLISTS) forgetDeletedPlaylists(playlistsBefore)
        noteChanged(changed)
        return Report.Done
    }

    private fun forgetDeletedPlaylists(before: Set<String>) {
        val gone = before - sync.present(LibrarySync.PLAYLISTS).keys
        gone.forEach { sync.forget(LibrarySync.playlist(it)); playlistIds.forget(it) }
    }

    private fun topicOf(collection: String) = if (collection.startsWith("playlist:")) LibrarySync.PLAYLISTS else collection

    // ---- Sending ----

    private fun noteChanged(docs: Set<String>) {
        if (docs.isEmpty()) return
        if (dirtyDocs.isEmpty()) firstDirtyAt = clock()
        dirtyDocs += docs; markSaved(); publish()
        if (sending) return // the send that's running picks these up
        sendJob?.cancel()
        val wait = minOf(timing.sendQuiet, maxOf(0, firstDirtyAt + timing.sendLongest - clock()))
        sendJob = spawn { delay(wait); flushSends() }
    }

    /** Sends every changed document to every linked device now. */
    suspend fun flushSends(): Unit = withContext(context) {
        if (sending) return@withContext
        val devices = link.devices()
        if (devices.isEmpty()) { dirtyDocs.clear(); publish(); return@withContext }
        sending = true
        var failed: Set<String> = emptySet()
        try {
            while (dirtyDocs.isNotEmpty() && failed.isEmpty()) {
                val names = sendOrder(dirtyDocs.toList()); dirtyDocs.clear()
                failed = sendDocs(names, devices)
            }
        } finally { sending = false }
        if (failed.isNotEmpty()) { dirtyDocs += failed; sendJob = spawn { delay(timing.sendRetry); flushSends() } }
        else lastSync = clock()
        markSaved(); publish()
    }

    /** The playlist list before playlists' songs, so a new playlist exists before its songs arrive. */
    private fun sendOrder(names: List<String>) = names.sortedBy { if (it.startsWith(LibrarySync.PLAYLISTS + "#")) 0 else if (it.startsWith("playlist:")) 2 else 1 }

    private suspend fun sendDocs(names: List<String>, devices: Set<String>): Set<String> {
        val failed = LinkedHashSet<String>()
        for (name in names) {
            val body = sync.doc(name)
            for (device in devices) {
                try { link.send(SocialPacket("syncDoc", body), "sync:$name", device, DOC_LIFETIME) }
                catch (e: Exception) { if (e is CancellationException) throw e; failed += name }
            }
        }
        return failed
    }

    private suspend fun sendDigest(devices: Set<String>) {
        if (!started || devices.isEmpty()) return
        lastDigest = clock()
        val body = JSONObject().put("docs", JSONObject(sync.digest() as Map<*, *>))
        devices.forEach { device -> runCatching { link.send(SocialPacket("syncDigest", body), "syncDigest", device, DIGEST_LIFETIME) }.onFailure { if (it is CancellationException) throw it } }
    }

    // ---- Receiving ----

    /** A "syncDoc" or "syncDigest" packet from the relay. Only encrypted packets from linked devices count. */
    fun receive(author: String, packet: SocialPacket, encrypted: Boolean) {
        if (!encrypted || author == me || packet.type != "syncDoc" && packet.type != "syncDigest") return
        spawn {
            if (incoming.size >= 2_000) return@spawn
            incoming += author to packet
            if (started) process()
        }
    }

    private fun process() {
        if (worker?.isActive == true) return
        worker = spawn {
            while (incoming.isNotEmpty()) {
                val batch = ArrayList(incoming); incoming.clear()
                val devices = link.devices()
                val mine = batch.filter { it.first in devices }
                val docs = mine.filter { it.second.type == "syncDoc" }.mapNotNull { it.second.body.takeIf { b -> LibrarySync.validCollection(b.optString("collection")) } }
                if (docs.isNotEmpty()) receiveDocs(docs)
                mine.filter { it.second.type == "syncDigest" }.forEach { (author, packet) -> answerDigest(author, packet.body) }
            }
        }
    }

    private suspend fun receiveDocs(docs: List<JSONObject>) {
        // Report first: a local edit still waiting for its debounce gets its stamp before the merge, so newer wins.
        docs.map { topicOf(it.getString("collection")) }.toSet().filter { it in host.topics }.forEach { report(it) }
        val playlistsBefore = sync.present(LibrarySync.PLAYLISTS).keys
        val changes = docs.flatMap { doc -> runCatching { sync.receive(doc) }.getOrDefault(emptyList()) }
        // Counts are informational: nothing to make true locally.
        changes.filter { it.collection.startsWith("stats:") }.forEach(sync::applied)
        if (docs.any { it.getString("collection").startsWith("stats:") }) host.remoteStats(remoteStats())
        lastSync = clock(); markSaved()
        apply(changes.filter { !it.collection.startsWith("stats:") })
        // After applying, so the host still knew which local playlist a deleted one was.
        forgetDeletedPlaylists(playlistsBefore)
        publish()
    }

    private suspend fun answerDigest(author: String, body: JSONObject) {
        val theirs = body.optJSONObject("docs") ?: return
        val mine = sync.digest()
        val differ = mine.filter { (name, print) -> theirs.optString(name) != print }.keys.toList()
        if (differ.isNotEmpty()) sendDocs(sendOrder(differ), setOf(author))
        // They have documents we lack or that differ: our digest makes them send theirs. Converges in one round.
        val theyHaveMore = theirs.keys().asSequence().any { mine[it] != theirs.optString(it) }
        if (theyHaveMore && clock() - (digestReplied[author] ?: 0) >= timing.digestReply) { digestReplied[author] = clock(); sendDigest(setOf(author)) }
    }

    private fun remoteStats(): Map<String, Map<String, JSONObject>> = sync.collections()
        .filter { it.startsWith("stats:") && it != LibrarySync.stats(me) }
        .associate { it.removePrefix("stats:") to sync.present(it) }

    // ---- Applying ----

    /** One apply at a time, so two batches never edit the same playlist at once. Reports carry on meanwhile. */
    private val applyLock = Mutex()

    private suspend fun apply(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        applying += changes.size; publish()
        try {
            applyLock.withLock {
                val groups = changes.groupBy { it.collection }.entries.sortedBy { if (it.key == LibrarySync.PLAYLISTS) 0 else if (it.key.startsWith("playlist:")) 2 else 1 }
                for ((collection, list) in groups) {
                    applying -= list.size
                    if (collection.startsWith("playlist:") && collection.removePrefix("playlist:") !in sync.present(LibrarySync.PLAYLISTS)) continue
                    val done = try { host.apply(collection, list, sync.present(collection)) } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
                    val doneKeys = done.map { it.key }.toSet()
                    val topic = topicOf(collection)
                    if (done.isNotEmpty()) generations[topic] = (generations[topic] ?: 0) + 1
                    done.forEach { sync.applied(it); unmatched.remove(it.collection to it.key) }
                    list.filter { it.key !in doneKeys && it.present && isSongCollection(it.collection) }.forEach { change ->
                        LibrarySync.track(change.value)?.let { unmatched[change.collection to change.key] = it }
                    }
                    list.filter { !it.present }.forEach { unmatched.remove(it.collection to it.key) }
                    // Report again afterwards: where this device keeps something else (newer progress, a value it
                    // can't hold exactly), that becomes a new change and the other devices follow.
                    if (done.isNotEmpty() && topic in host.topics) dirtyTopics += topic
                    publish()
                }
            }
            markSaved()
            if (dirtyTopics.isNotEmpty()) scheduleReport(restart = false)
        } finally { applying = maxOf(0, applying); publish() }
    }

    /** Items received but not made true here yet: a song that wasn't found, a show that didn't load. */
    suspend fun retryPending(): Unit = withContext(context) {
        if (!started) return@withContext
        val live = sync.present(LibrarySync.PLAYLISTS).keys
        val pending = sync.collections().filter { !it.startsWith("stats:") && (!it.startsWith("playlist:") || it.removePrefix("playlist:") in live) }
            .sortedBy { if (it == LibrarySync.PLAYLISTS) 0 else 1 }.flatMap(sync::pending)
        apply(pending)
        // Unmatched entries that no longer are wanted (removed elsewhere) drop off the list.
        unmatched.keys.retainAll(sync.collections().flatMap { c -> sync.pending(c).map { c to it.key } }.toSet())
        publish()
    }

    // ---- Saving and status ----

    private fun markSaved() {
        if (saveJob?.isActive == true) return
        saveJob = spawn { delay(timing.saveEvery); host.save(json().toString()) }
    }

    private fun publish() {
        _status.value = LibrarySyncStatus(started, dirtyDocs.size + applying, lastSync, unmatched.values.distinctBy { LibrarySync.trackKey(it) }.take(500))
    }

    /** Runs [block] with the sync state, on its thread. */
    suspend fun <T> read(block: (LibrarySync) -> T): T = withContext(context) { block(sync) }

    companion object {
        const val DOC_LIFETIME = 60L * 24 * 60 * 60_000
        const val DIGEST_LIFETIME = 2L * 24 * 60 * 60_000
        /** Collections whose items are songs that must be matched here. */
        val SONG_COLLECTIONS = setOf(LibrarySync.LIKED, LibrarySync.SAVED_TRACKS, LibrarySync.HIDDEN_SONGS)
        fun isSongCollection(collection: String) = collection in SONG_COLLECTIONS || collection.startsWith("playlist:")

        /**
         * Positions for a playlist's entries in their order here, keeping the ones already shared wherever
         * the order agrees, so songs this device couldn't find don't shift everything (docs: `pos`).
         */
        fun positions(keys: List<String>, previous: Map<String, Int>): List<Int> {
            val out = IntArray(keys.size) { -1 }
            var last = -1
            keys.forEachIndexed { i, k -> val p = previous[k] ?: -1; if (p > last) { out[i] = p; last = p } }
            var i = 0
            while (i < keys.size) {
                if (out[i] >= 0) { i++; continue }
                var j = i; while (j < keys.size && out[j] < 0) j++
                val lower = if (i == 0) -1 else out[i - 1]
                val upper = if (j == keys.size) Int.MAX_VALUE else out[j]
                if (upper.toLong() - lower - 1 < j - i) return keys.indices.toList()
                for (n in i until j) out[n] = lower + 1 + (n - i)
                i = j
            }
            return out.toList()
        }
    }
}

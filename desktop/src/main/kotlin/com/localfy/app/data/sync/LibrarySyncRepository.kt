package com.localfy.app.data.sync

import com.localfy.app.data.Images
import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.PlayStat
import com.localfy.app.data.Song
import com.localfy.app.data.db.PlayEventEntity
import com.localfy.app.data.music.ArtistFollows
import com.localfy.app.data.music.HttpGet
import com.localfy.app.data.music.MusicStreams
import com.localfy.app.data.music.OnlineArtist
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.data.music.SearchMatch
import com.localfy.app.data.podcast.KIND_AUDIOBOOK
import com.localfy.app.data.podcast.KIND_PODCAST
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.data.social.DeviceSyncRepository
import com.localfy.app.data.social.SharedTrack
import com.localfy.app.data.social.SocialPacket
import com.localfy.app.data.social.SocialRepository
import com.localfy.app.data.social.SocialRules
import com.localfy.app.data.sync.LibrarySync.Companion.FOLLOWED_ARTISTS
import com.localfy.app.data.sync.LibrarySync.Companion.FRIENDS
import com.localfy.app.data.sync.LibrarySync.Companion.HIDDEN_ARTISTS
import com.localfy.app.data.sync.LibrarySync.Companion.HIDDEN_MIXES
import com.localfy.app.data.sync.LibrarySync.Companion.HIDDEN_SONGS
import com.localfy.app.data.sync.LibrarySync.Companion.HISTORY
import com.localfy.app.data.sync.LibrarySync.Companion.LIKED
import com.localfy.app.data.sync.LibrarySync.Companion.PLAYLISTS
import com.localfy.app.data.sync.LibrarySync.Companion.PODCASTS
import com.localfy.app.data.sync.LibrarySync.Companion.PROFILE
import com.localfy.app.data.sync.LibrarySync.Companion.PROGRESS
import com.localfy.app.data.sync.LibrarySync.Companion.SAVED_SHARED
import com.localfy.app.data.sync.LibrarySync.Companion.SAVED_TRACKS
import com.localfy.app.data.sync.LibrarySync.Companion.SETTINGS
import com.localfy.app.data.taste.TasteRepository
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.abs

/**
 * Settings that follow you between devices, by their [LibrarySync.SYNCED_SETTINGS] names. The app
 * maps its theme, player and library settings onto these; tests use a map.
 */
interface SyncedSettings {
    /** Emits whenever any of them may have changed. */
    val changes: Flow<*>
    /** Current values (Boolean, Number or String) of the names this platform has. Read on the UI thread. */
    fun values(): Map<String, Any>
    /** Each name's value on a fresh install. A default nobody has synced yet isn't sent, so a new device can't reset your choices. */
    fun defaults(): Map<String, Any>
    /** Takes [value] for [name] from another device (UI thread). Unknown names and unusable values are ignored. */
    fun apply(name: String, value: Any)
}

/** Settings › Your devices: "Library: in sync" / "syncing N items…", and songs that couldn't be found here. */
data class LibrarySyncStatus(
    val active: Boolean = false,
    /** Changes waiting to be sent plus songs being looked for. */
    val syncing: Int = 0,
    /** When a change last went out or came in; 0 = never. */
    val lastSync: Long = 0,
    /** Songs from your other devices that couldn't be found on this computer. */
    val unmatched: List<SharedTrack> = emptyList(),
)

/**
 * Library sync (docs/library-sync.md): keeps likes, saved songs, playlists, follows, hidden items,
 * podcasts, listening progress, recently played, play counts, the profile and portable settings the
 * same on every linked device. Rules and merging are core's [LibrarySync]; this class reports what
 * this computer holds, applies what other devices changed, and moves documents over the device-sync
 * relay plumbing ([SocialRepository.sendDevicePacket]). Audio never moves: each device plays its own
 * file, download or stream.
 *
 * [scope] is the UI scope. All [LibrarySync] work happens under one lock on background threads;
 * friends and settings are read and changed on the UI thread. Nothing happens until a device is linked.
 */
@OptIn(FlowPreview::class)
class LibrarySyncRepository(
    private val scope: CoroutineScope,
    private val social: SocialRepository,
    private val devices: DeviceSyncRepository,
    private val library: LibraryRepository,
    private val taste: TasteRepository,
    private val profiles: com.localfy.app.data.taste.ProfileRepository,
    private val podcasts: PodcastRepository,
    private val streams: MusicStreams,
    private val follows: ArtistFollows,
    private val settings: SyncedSettings,
    /** A playable song for a track that isn't here yet (the app passes `playlistMatches.resolve`). */
    private val resolveTrack: suspend (SharedTrack) -> Song,
    /** Lets [resolveTrack] search again for tracks it failed on (Retry). */
    private val forgetFailures: (List<SharedTrack>) -> Unit = {},
    /** The catalogue track behind a song (streams; downloads when known), for `sourceID`. */
    private val online: (Song) -> OnlineTrack? = streams::track,
    /** Whether this computer is playing: progress is reported at most once a minute while it is. */
    private val playing: Flow<Boolean> = flowOf(false),
    dir: File = AppPaths.dataDir,
    private val reportDelay: Long = REPORT_DELAY,
    private val sendDelay: Long = SEND_DELAY,
) {
    val me: String = social.publicKey
    private val sync = LibrarySync(me)
    private val store = JsonStore(File(dir, "library-sync.json"))
    private val lock = Mutex()
    private val ui = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Main
    private val loaded = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) { load() }

    // Kept with the sync state, guarded by [lock].
    /** Global playlist id → this computer's playlist id. */
    private val playlistIds = HashMap<String, Long>()
    /** Playlists deleted here or elsewhere: their songs aren't taken back from other devices. */
    private val deletedPlaylists = HashSet<String>()
    /** Last known song details per local song id, so a song that's briefly unreadable isn't reported as removed. */
    private val trackCache = HashMap<Long, SharedTrack>()
    /** Songs matched to another device's key (a stream found by its catalogue id may be titled a little differently). */
    private val aliases = HashMap<Long, String>()
    /** Covers taken from another device: playlist id → (file time, that device's imageHash), so they aren't re-sent as new. */
    private val coverHashes = HashMap<Long, Pair<Long, String>>()
    private var lastSync = 0L
    private var lastDigestAt = 0L

    // Not saved.
    private val coverImages = HashMap<String, Pair<String, String>>()
    private val resolvedIds = ConcurrentHashMap<String, Long>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, SharedTrack>()
    private val subscribing = ConcurrentHashMap.newKeySet<String>()
    private val matchSlots = Semaphore(4)
    private val outgoing = LinkedHashSet<String>()
    private var sendJob: Job? = null
    private val persistQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val statsQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastProgressReport = 0L
    private val massRemoval = ConcurrentHashMap.newKeySet<String>()
    private var index: Pair<List<Song>, Map<String, List<Song>>>? = null

    @Volatile private var linked: Set<String> = emptySet()
    private val active get() = linked.isNotEmpty()
    private var started = false

    private val _status = MutableStateFlow(LibrarySyncStatus())
    val status: StateFlow<LibrarySyncStatus> = _status.asStateFlow()

    // ---- Starting

    /** Starts watching the library and the linked devices (once; call on the UI thread). */
    fun start() {
        if (started) return
        started = true
        loaded.start()
        social.onSyncPacket = { author, packet, encrypted -> receivePacket(author, packet, encrypted) }
        scope.launch { devices.revision.collect { devicesChanged() } }

        watch(combine(library.likedEntries, library.library) { _, _ -> Unit }, LIKED)
        watch(streams.saved, SAVED_TRACKS)
        watch(follows.revision, FOLLOWED_ARTISTS)
        watch(combine(taste.hiddenSongs, library.library) { _, _ -> Unit }, HIDDEN_SONGS)
        watch(taste.hiddenArtists, HIDDEN_ARTISTS)
        watch(library.hiddenMixKeys, HIDDEN_MIXES)
        watch(podcasts.shows, PODCASTS)
        watch(combine(library.playlistDb, library.library, library.playlistCovers) { _, _, _ -> Unit }, PLAYLISTS)
        watch(taste.events, HISTORY)
        watch(combine(library.ownStats, library.library) { _, _ -> Unit }, STATS)
        watch(profiles.profile, PROFILE)
        watch(social.revision, FRIENDS, SAVED_SHARED)
        watch(settings.changes, SETTINGS)
        scope.launch(Dispatchers.Default) {
            podcasts.resume.debounce(reportDelay).conflate().collect {
                val wait = lastProgressReport + PROGRESS_EVERY - SocialRules.now
                if (wait > 0 && isPlaying) delay(wait)
                report(listOf(PROGRESS))
            }
        }
        scope.launch(Dispatchers.Default) {
            playing.distinctUntilChanged().collect { isPlaying = it; if (!it) report(listOf(PROGRESS)) }
        }
        // What couldn't be applied yet: retry when the library, shows or friends' shares change, and hourly.
        scope.launch(Dispatchers.Default) { library.library.debounce(reportDelay).collect { retry(listOf(LIKED, SAVED_TRACKS, HIDDEN_SONGS, PLAYLISTS, HISTORY, PROGRESS)); queueRemoteStats() } }
        scope.launch(Dispatchers.Default) { podcasts.shows.debounce(reportDelay).collect { retry(listOf(PODCASTS, PROGRESS)) } }
        scope.launch(Dispatchers.Default) { social.revision.debounce(reportDelay).collect { retry(listOf(SAVED_SHARED)) } }
        scope.launch(Dispatchers.Default) { while (isActive) { delay(RETRY_EVERY); retry(null) } }
        scope.launch(Dispatchers.Default) { while (isActive) { delay(DIGEST_EVERY); if (active) sendDigest() } }
    }

    @Volatile private var isPlaying = false

    private fun watch(source: Flow<*>, vararg names: String) {
        scope.launch(Dispatchers.Default) { source.debounce(reportDelay).collect { report(names.toList()) } }
    }

    /** Linked devices changed (UI thread): a new one gets a digest, a removed one's play counts are forgotten. */
    private fun devicesChanged() {
        val now = devices.devices.map { it.id }.toSet()
        val added = now - linked; val removed = linked - now
        linked = now
        if (removed.isNotEmpty()) scope.launch(Dispatchers.Default) {
            withState { removed.forEach { sync.forget(LibrarySync.stats(it)) }; persistSoon() }
            queueRemoteStats()
        }
        if (added.isNotEmpty()) scope.launch(Dispatchers.Default) {
            report(ALL)
            queueRemoteStats()
            sendDigest()
        }
        publishStatus()
    }

    /** The window came back: tell the other devices what we have (at most every 10 minutes). */
    fun foreground() {
        if (!active || SocialRules.now - lastDigestAt < FOREGROUND_DIGEST) return
        scope.launch(Dispatchers.Default) { sendDigest() }
    }

    /** The user cleared a whole collection on purpose (e.g. "Show hidden recommendations again"): the next report may remove it all. */
    fun allowMassRemoval(vararg collections: String) { massRemoval += collections }

    /** Settings › Your devices › Retry: searches again for songs that weren't found. */
    fun retryUnmatched() {
        val tracks = failed.values.toList()
        failed.keys.forEach { resolvedIds.remove(it.substringAfter('|')) }
        failed.clear(); publishStatus()
        forgetFailures(tracks)
        scope.launch(Dispatchers.Default) { delay(100); retry(null) }
    }

    /** Writes the sync state now (app exit). */
    fun flush() {
        if (lock.tryLock()) try { val text = stateJson().toString(); store.save(0) { text } } finally { lock.unlock() }
        store.flush()
    }

    private suspend fun <T> withState(block: suspend () -> T): T {
        loaded.join()
        return withContext(Dispatchers.Default) { lock.withLock { block() } }
    }

    private suspend fun <T> onUi(block: () -> T): T = withContext(ui) { block() }

    // ---- Saving

    private fun load() {
        val o = runCatching { store.readObject() }.getOrNull() ?: return
        runCatching { o.optJSONObject("state")?.let(sync::load) }
        o.optJSONObject("playlists")?.let { p -> p.keys().forEach { playlistIds[it] = p.getLong(it) } }
        o.optJSONArray("deleted")?.let { a -> for (i in 0 until a.length()) deletedPlaylists += a.getString(i) }
        o.optJSONObject("tracks")?.let { t -> t.keys().forEach { k -> runCatching { trackCache[k.toLong()] = SharedTrack.parse(t.getJSONObject(k)) } } }
        o.optJSONObject("aliases")?.let { a -> a.keys().forEach { k -> runCatching { aliases[k.toLong()] = a.getString(k) } } }
        o.optJSONObject("covers")?.let { c -> c.keys().forEach { k -> runCatching { val v = c.getJSONObject(k); coverHashes[k.toLong()] = v.getLong("modified") to v.getString("hash") } } }
        lastSync = o.optLong("lastSync")
        sync.trim()
    }

    /** Must hold [lock]. */
    private fun stateJson(): JSONObject = JSONObject()
        .put("state", sync.json())
        .put("playlists", JSONObject(playlistIds as Map<*, *>))
        .put("deleted", JSONArray(deletedPlaylists.toList()))
        .put("tracks", JSONObject().also { t -> trackCache.forEach { (id, track) -> t.put(id.toString(), track.json()) } })
        .put("aliases", JSONObject().also { a -> aliases.forEach { (id, key) -> a.put(id.toString(), key) } })
        .put("covers", JSONObject().also { c -> coverHashes.forEach { (id, v) -> c.put(id.toString(), JSONObject().put("modified", v.first).put("hash", v.second)) } })
        .put("lastSync", lastSync)

    /** Writes the state at most every 2 s, building it off the UI thread. */
    private fun persistSoon() {
        if (!persistQueued.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            delay(PERSIST_EVERY)
            val text = withState { persistQueued.set(false); stateJson().toString() }
            store.save(0) { text }
        }
    }

    // ---- Reporting what this computer has

    private suspend fun report(names: Collection<String>) {
        if (!active) return
        // Friends, shared playlists and settings live on the UI thread: read them there first.
        val uiData = if (names.any { it in UI_COLLECTIONS }) onUi { uiSnapshot() } else null
        val changed = withState {
            val out = HashSet<String>()
            for (name in names) out += reportOne(name, uiData)
            if (out.isNotEmpty() || names.isNotEmpty()) persistSoon()
            out
        }
        if (changed.isNotEmpty()) queueSend(changed)
    }

    private class UiData(val following: Map<String, String?>, val shared: Map<String, String>, val settings: Map<String, Any>, val defaults: Map<String, Any>)

    private fun uiSnapshot() = UiData(
        social.state.following.associateWith { social.state.profiles[it]?.name },
        social.state.playlists.values.filter { it.owner != me }.associate { it.key to it.name },
        settings.values(), settings.defaults(),
    )

    /** Must hold [lock]. Reports [name]; returns the documents that changed. */
    private fun reportOne(name: String, uiData: UiData?): Set<String> {
        if (name in SONG_COLLECTIONS && !library.scanned.value) return emptySet()
        val mass = massRemoval.remove(name)
        return when (name) {
            HISTORY -> reportHistory()
            STATS -> sync.report(LibrarySync.stats(me), statsSnapshot(), mass)
            PLAYLISTS -> reportPlaylists(mass)
            else -> {
                val current = when (name) {
                    LIKED -> likedSnapshot()
                    SAVED_TRACKS -> savedSnapshot()
                    FOLLOWED_ARTISTS -> follows.artists.associate { it.id to JSONObject().put("_artist", JSONObject().put("id", it.id).put("name", it.name).put("artwork", it.artwork)) }
                    HIDDEN_SONGS -> songsSnapshot(taste.hiddenSongs.value) { t, _ -> LibrarySync.trackValue(t) }
                    HIDDEN_ARTISTS -> taste.hiddenArtists.value.associate { SearchMatch.fold(it) to JSONObject().put("_name", it) }
                    HIDDEN_MIXES -> library.hiddenMixKeys.value.associateWith { JSONObject() }
                    PODCASTS -> podcastsSnapshot()
                    PROGRESS -> { lastProgressReport = SocialRules.now; progressSnapshot() }
                    PROFILE -> profileSnapshot()
                    FRIENDS -> uiData?.following?.mapValues { (_, n) -> JSONObject().put("_name", n) } ?: return emptySet()
                    SAVED_SHARED -> uiData?.shared?.mapValues { (_, n) -> JSONObject().put("_name", n) } ?: return emptySet()
                    SETTINGS -> settingsSnapshot(uiData ?: return emptySet())
                    else -> return emptySet()
                }
                sync.report(name, current, mass)
            }
        }
    }

    private fun songOf(id: Long): Song? = library.library.value.songById[id] ?: streams.lookup(id)
        ?: library.localBooks.value.firstOrNull { it.id == id } ?: library.localPodcasts.value.firstOrNull { it.id == id }

    /** The song behind a local id as it travels (or as last seen, when it can't be read now). */
    private fun trackOf(id: Long): SharedTrack? {
        val song = songOf(id)
        if (song != null) {
            val track = runCatching { SharedTrack.from(song, online(song)) }.getOrNull()?.takeIf { it.valid() }
            if (track != null) { trackCache[id] = track; return track }
        }
        return trackCache[id]
    }

    private fun keyOf(id: Long, track: SharedTrack): String = aliases[id] ?: LibrarySync.trackKey(track)

    private fun songsSnapshot(ids: Collection<Long>, value: (SharedTrack, Long) -> JSONObject): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        for (id in ids) { val t = trackOf(id) ?: continue; val k = keyOf(id, t); if (k !in out) out[k] = value(t, id) }
        return out
    }

    private fun likedSnapshot(): Map<String, JSONObject> {
        val liked = library.likedEntries.value.sortedBy { it.likedAt }
        val at = liked.associate { it.songId to it.likedAt }
        return songsSnapshot(liked.map { it.songId }) { t, id -> LibrarySync.trackValue(t).put("_addedAt", at[id]) }
    }

    private fun savedSnapshot(): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        for (song in streams.saved.value) {
            val track = SharedTrack.from(song, streams.track(song)).takeIf { it.valid() } ?: continue
            out.getOrPut(keyOf(song.id, track)) { LibrarySync.trackValue(track).put("_addedAt", song.dateAddedSec * 1000) }
        }
        return out
    }

    private fun statsSnapshot(): Map<String, JSONObject> {
        val sums = HashMap<String, Triple<SharedTrack, IntArray, LongArray>>()
        for (s in library.ownStats.value.values) {
            if (s.playCount == 0 && s.skipCount == 0) continue
            val t = trackOf(s.songId) ?: continue
            val entry = sums.getOrPut(keyOf(s.songId, t)) { Triple(t, IntArray(2), LongArray(1)) }
            entry.second[0] += s.playCount; entry.second[1] += s.skipCount; entry.third[0] = maxOf(entry.third[0], s.lastPlayed)
        }
        return sums.mapValues { (_, v) -> LibrarySync.trackValue(v.first).put("plays", v.second[0]).put("skips", v.second[1]).put("lastPlayed", v.third[0]) }
    }

    private fun reportHistory(): Set<String> {
        val cutoff = SocialRules.now - (LibrarySync.HISTORY_DAYS - 1) * DAY
        val prefix = me.take(8)
        val out = HashSet<String>()
        for (e in taste.events.value) {
            if (e.startedAt < cutoff || e.source?.startsWith(TasteRepository.SYNC_SOURCE) == true) continue
            val t = trackOf(e.songId) ?: continue
            val value = LibrarySync.trackValue(t).put("playedAt", e.startedAt).put("listenedMs", e.listenedMs).put("durationMs", e.durationMs).put("skipped", e.skipped)
            out += sync.add(HISTORY, "$prefix:${e.startedAt}:${keyOf(e.songId, t)}", value)
        }
        return out
    }

    private fun podcastsSnapshot(): Map<String, JSONObject> = podcasts.shows.value.filter { it.podcast.subscribedAt > 0 }.associate { show ->
        val p = show.podcast
        p.feedUrl to JSONObject().put("_show", JSONObject().put("feedUrl", p.feedUrl).put("title", p.title).put("author", p.author).put("artwork", p.artworkUrl).put("kind", p.kind))
    }

    /** "ep:<id>" / song id → a key every device can find: the show's feed and the episode's guid, or the song's key. */
    private fun progressKeys(): Map<String, String> {
        val out = HashMap<String, String>()
        for (show in podcasts.shows.value) for (ep in show.episodes) out["ep:${ep.id}"] = shortKey("e:${show.podcast.feedUrl}#${ep.guid}")
        for (song in library.localBooks.value + library.localPodcasts.value) out[song.id.toString()] = "t:" + LibrarySync.trackKey(song.title, song.artist)
        return out
    }

    private fun progressSnapshot(): Map<String, JSONObject> {
        val keys = progressKeys()
        val out = HashMap<String, JSONObject>()
        for ((local, r) in podcasts.resume.value) {
            val key = keys[local] ?: songOf(local.toLongOrNull() ?: continue)?.let { "t:" + LibrarySync.trackKey(it.title, it.artist) } ?: continue
            out[key] = JSONObject().put("positionMs", r.positionMs / 5_000 * 5_000).put("durationMs", r.durationMs).put("played", r.played)
        }
        return out
    }

    private fun profileSnapshot(): Map<String, JSONObject> {
        val p = profiles.profile.value
        val known = sync.present(PROFILE)
        val out = HashMap<String, JSONObject>()
        if (p.name.isNotBlank() || "name" in known) out["name"] = JSONObject().put("value", p.name)
        if (p.seedArtists.isNotEmpty() || "seedArtists" in known) {
            val theirs = known["seedArtists"]?.optJSONArray("value")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            out["seedArtists"] = if (theirs == p.seedArtists) known.getValue("seedArtists") else JSONObject().put("value", JSONArray(p.seedArtists.sorted()))
        }
        if (p.onboarded || "onboarded" in known) out["onboarded"] = JSONObject().put("value", p.onboarded)
        return out
    }

    private fun settingsSnapshot(data: UiData): Map<String, JSONObject> {
        val known = sync.present(SETTINGS)
        val out = HashMap<String, JSONObject>()
        for ((name, value) in data.settings) {
            if (name !in LibrarySync.SYNCED_SETTINGS) continue
            val theirs = known[name]
            if (theirs == null && sameSetting(value, data.defaults[name])) continue
            // The same value as another device wrote it (1.12 vs 1.1200000476…): keep theirs, or devices would correct each other.
            out[name] = if (theirs != null && sameSetting(value, theirs.opt("value"))) theirs else JSONObject().put("value", jsonValue(value))
        }
        // Settings this computer doesn't have still belong to the library.
        known.forEach { (name, v) -> if (name !in data.settings) out[name] = v }
        return out
    }

    private fun reportPlaylists(mass: Boolean): Set<String> {
        val db = library.playlistDb.value
        val byLocal = playlistIds.entries.associate { (g, l) -> l to g }
        val winners = sync.present(PLAYLISTS)
        val current = HashMap<String, JSONObject>()
        val changed = HashSet<String>()
        val entriesBy = db.entries.groupBy { it.playlistId }
        for (p in db.playlists) {
            val gid = byLocal[p.id] ?: UUID.randomUUID().toString().also { playlistIds[it] = p.id }
            val theirs = winners[gid]
            val cover = coverOf(p.id)
            current[gid] = if (theirs != null && theirs.optString("name") == p.name && theirs.optString("imageHash") == (cover?.second ?: "")) theirs
            else JSONObject().put("name", p.name).put("description", theirs?.optString("description") ?: "")
                .put("imageHash", cover?.second).put("_image", cover?.first).put("_createdAt", p.createdAt)
            val songs = entriesBy[p.id].orEmpty().sortedBy { it.position }.mapNotNull { e -> trackOf(e.songId)?.let { keyOf(e.songId, it) to it } }
            val keys = occurrences(songs.map { it.first })
            val list = LibrarySync.playlist(gid)
            val have = sync.present(list)
            val order = have.entries.sortedWith(compareBy({ it.value.optInt("pos") }, { it.key })).map { it.key }
            val reuse = order.filter { it in keys } == keys.filter { it in have }
            changed += sync.report(list, keys.zip(songs).mapIndexed { i, (k, s) ->
                k to LibrarySync.entryValue(s.second, if (reuse && k in have) have.getValue(k).optInt("pos") else i)
            }.toMap(), mass)
        }
        changed += sync.report(PLAYLISTS, current, mass)
        // Deleted here: the tombstone is out, so drop the songs.
        val present = db.playlists.mapTo(HashSet()) { it.id }
        playlistIds.entries.filter { it.value !in present && it.key !in current }.toList().forEach { (gid, _) ->
            playlistIds.remove(gid); deletedPlaylists += gid; sync.forget(LibrarySync.playlist(gid))
        }
        return changed
    }

    /** "key#1", "key#2"… for the same song appearing again, as [LibrarySync.entryKeys] does. */
    private fun occurrences(keys: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return keys.map { k -> val n = (seen[k] ?: 0) + 1; seen[k] = n; "$k#$n" }
    }

    /** A playlist's cover as it travels: a ≤ 64 KB JPEG data URL and its hash; null without a cover. */
    private fun coverOf(id: Long): Pair<String, String>? {
        val file = library.playlistCover(id)
        if (!file.isFile) return null
        val modified = file.lastModified()
        val image = coverImages.getOrPut("${file.path}:$modified") { encodeCover(file) ?: return null }
        val theirs = coverHashes[id]?.takeIf { it.first == modified }?.second
        return image.first to (theirs ?: image.second)
    }

    private fun encodeCover(file: File): Pair<String, String>? {
        val image = Images.decode(file.readBytes()) ?: return null
        val small = Images.scale(image, 512, square = true)
        var quality = 0.85f
        while (true) {
            val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(Images.jpeg(small, quality))
            if (url.length <= MAX_IMAGE || quality < 0.3f) return url.takeIf { it.length <= MAX_IMAGE }?.let { it to imageHash(it) }
            quality -= 0.15f
        }
    }

    // ---- Receiving

    /** A sync packet from the relay (UI thread): only from linked devices, encrypted. */
    private fun receivePacket(author: String, packet: com.localfy.app.data.social.SocialPacket, encrypted: Boolean) {
        if (!encrypted || author !in devices.devices.map { it.id }) return
        when (packet.type) {
            "syncDigest" -> {
                val theirs = packet.body.optJSONObject("docs") ?: JSONObject()
                scope.launch(Dispatchers.Default) {
                    val docs = withState { sync.digest().filter { (name, print) -> theirs.optString(name) != print }.keys.map { it to sync.doc(it) } }
                    docs.forEach { (name, doc) -> sendDoc(name, doc, listOf(author)) }
                }
            }
            "syncDoc" -> scope.launch(Dispatchers.Default) { receiveDoc(packet.body) }
        }
    }

    private suspend fun receiveDoc(doc: JSONObject) {
        val collection = doc.optString("collection")
        if (!LibrarySync.validCollection(collection)) return
        if (collection.startsWith("stats:") && collection.removePrefix("stats:").let { it != me && it !in linked }) return
        val reportable = when {
            collection.startsWith("playlist:") -> PLAYLISTS
            collection.startsWith("stats:") || collection !in ALL -> null
            else -> collection
        }
        // What this computer changed in the last moments goes in first, so applying can't undo it.
        val uiData = if (reportable in UI_COLLECTIONS) onUi { uiSnapshot() } else null
        val sent = withState {
            if (collection.startsWith("playlist:") && collection.removePrefix("playlist:") in deletedPlaylists) return@withState emptySet()
            val mine = if (reportable != null && active) reportOne(reportable, uiData) else emptySet()
            val changes = sync.receive(doc)
            applyAll(changes)
            lastSync = SocialRules.now
            persistSoon()
            mine
        }
        if (sent.isNotEmpty()) queueSend(sent)
        if (collection.startsWith("stats:") || collection == HISTORY) queueRemoteStats()
        publishStatus()
    }

    /** Pending items of [collections] (all when null) are tried again. */
    private suspend fun retry(collections: Collection<String>?) {
        if (!active) return
        withState {
            val names = collections?.flatMap { c -> if (c == PLAYLISTS) listOf(c) + sync.collections().filter { it.startsWith("playlist:") } else listOf(c) }
                ?: sync.collections().toList()
            names.forEach { applyAll(sync.pending(it)) }
            persistSoon()
        }
        publishStatus()
    }

    // ---- Applying another device's changes (must hold [lock])

    /** Lookups shared by one batch of changes, built when first needed. */
    private inner class Batch {
        val liked by lazy { keyed(library.likedEntries.value.map { it.songId }) }
        val hidden by lazy { keyed(taste.hiddenSongs.value) }
        val saved by lazy { keyed(streams.saved.value.map { it.id }) }
        val progress by lazy { progressKeys().entries.associate { (local, key) -> key to local } }
        private fun keyed(ids: Collection<Long>): MutableMap<String, MutableList<Long>> {
            val out = HashMap<String, MutableList<Long>>()
            for (id in ids) { val t = trackOf(id) ?: continue; out.getOrPut(keyOf(id, t)) { ArrayList() } += id }
            return out
        }
    }

    private suspend fun applyAll(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        val batch = Batch()
        val listens = ArrayList<PlayEventEntity>()
        for ((collection, list) in changes.groupBy { it.collection }) {
            // Songs are matched once the library has loaded (a file here beats the stream); until then they wait.
            if (!library.scanned.value && (collection in SONG_MATCHED || collection.startsWith("playlist:"))) continue
            when {
                collection.startsWith("playlist:") -> applyEntries(collection.removePrefix("playlist:"), list)
                collection == HISTORY -> list.forEach { c -> historyListen(c)?.let(listens::add); sync.applied(c) }
                collection.startsWith("stats:") -> list.forEach(sync::applied)
                else -> for (change in list) {
                    val done = try { apply(change, batch) } catch (e: Exception) { if (e is CancellationException) throw e; false }
                    if (done) sync.applied(change)
                }
            }
        }
        if (listens.isNotEmpty()) taste.importListens(listens)
    }

    private suspend fun apply(c: SyncChange, batch: Batch): Boolean {
        val v = c.value
        return when (c.collection) {
            LIKED -> {
                val ids = batch.liked[c.key].orEmpty()
                if (!c.present) { ids.forEach { library.setLiked(it, false) }; batch.liked.remove(c.key); return true }
                if (ids.isNotEmpty()) return true
                val song = songFor(c) ?: return false
                library.setLiked(song.id, true, v?.optLong("_addedAt", SocialRules.now) ?: SocialRules.now)
                batch.liked[c.key] = mutableListOf(song.id)
                true
            }
            SAVED_TRACKS -> {
                val ids = batch.saved[c.key].orEmpty()
                if (!c.present) { streams.remove(ids.mapNotNull { id -> streams.lookup(id)?.let(streams::track) }); batch.saved.remove(c.key); return true }
                if (ids.isNotEmpty()) return true
                val track = songFor(c, streamsOnly = true)?.let(streams::track) ?: return false
                streams.save(listOf(track))
                batch.saved[c.key] = mutableListOf(MusicStreams.streamId(track.id))
                true
            }
            HIDDEN_SONGS -> {
                val ids = batch.hidden[c.key].orEmpty()
                if (!c.present) { ids.forEach(taste::unhideSong); batch.hidden.remove(c.key); return true }
                if (ids.isNotEmpty()) return true
                val song = songFor(c) ?: return false
                taste.hideSong(song.id)
                batch.hidden[c.key] = mutableListOf(song.id)
                true
            }
            HIDDEN_ARTISTS -> {
                val names = taste.hiddenArtists.value.filter { SearchMatch.fold(it) == c.key }
                if (!c.present) names.forEach(taste::unhideArtist)
                else if (names.isEmpty()) taste.hideArtist(v?.optString("_name")?.takeIf { it.isNotBlank() } ?: c.key)
                true
            }
            HIDDEN_MIXES -> { library.setMixHidden(c.key, c.present); true }
            FOLLOWED_ARTISTS -> {
                if (!c.present) { follows.unfollow(c.key); return true }
                if (follows.contains(c.key)) return true
                val a = v?.optJSONObject("_artist") ?: return true
                val artist = OnlineArtist(c.key, a.optString("name").ifBlank { return true }, a.optString("artwork").takeIf { it.isNotBlank() && it != "null" })
                background("artist:${c.key}") { follows.followFromSync(artist); retry(listOf(FOLLOWED_ARTISTS)) }
                false
            }
            PODCASTS -> applyPodcast(c)
            PROGRESS -> {
                if (!c.present || v == null) return true
                val local = batch.progress[c.key] ?: return false
                podcasts.applyResume(local, v.optLong("positionMs"), v.optLong("durationMs"), v.optBoolean("played"))
                true
            }
            PLAYLISTS -> applyPlaylist(c)
            PROFILE -> {
                val value = v?.opt("value")
                when (c.key) {
                    "name" -> (value as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it != profiles.profile.value.name) profiles.setName(it) }
                    "seedArtists" -> (value as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                        ?.let { if (it != profiles.profile.value.seedArtists) profiles.setSeedArtists(it) }
                    "onboarded" -> if (value == true && !profiles.profile.value.onboarded) profiles.completeOnboarding()
                }
                true
            }
            FRIENDS -> { onUi { social.syncFollowing(c.key, c.present) }; true }
            SAVED_SHARED -> onUi {
                val playlist = social.state.playlists[c.key]
                if (!c.present) { playlist?.let(social::remove); true } else playlist != null
            }
            SETTINGS -> { if (c.present) v?.opt("value")?.let { value -> onUi { settings.apply(c.key, value) } }; true }
            else -> true // Collections this computer doesn't have (saved albums).
        }
    }

    /**
     * The local song for a remote song item: a song here with the same key (a file before a stream,
     * the closest length), else the catalogue stream named by `sourceID`, else one found earlier by
     * [resolveTrack]. Null when it has to be searched for; that happens in the background, four at a time.
     */
    private fun songFor(c: SyncChange, key: String = c.key, streamsOnly: Boolean = false, search: Boolean = true, register: Boolean = true): Song? {
        val track = LibrarySync.track(c.value) ?: return null
        val song = resolvedIds[key]?.let(::songOf)?.takeIf { !streamsOnly || it.isStream }
            ?: localIndex()[key]?.filter { !streamsOnly || it.isStream }?.minWithOrNull(compareBy({ it.isStream }, { abs(it.durationMs - track.durationMs) }))
            ?: track.sourceID?.takeIf { register }?.let { streams.register(OnlineTrack(it, track.title, track.artist, track.album, track.releaseID.orEmpty(), track.durationMs, 0, 1, track.artwork, true)) }
        if (song != null) {
            if (LibrarySync.trackKey(song.title, song.artist) != key) aliases[song.id] = key
            return song
        }
        if (search) find(c.collection, key, track)
        return null
    }

    private fun localIndex(): Map<String, List<Song>> {
        val songs = library.library.value.songs
        index?.let { (list, map) -> if (list === songs) return map }
        return songs.groupBy { LibrarySync.trackKey(it.title, it.artist) }.also { index = songs to it }
    }

    /** Searches for [track] in the background; once found, [collection]'s pending items are applied again. */
    private fun find(collection: String, key: String, track: SharedTrack) {
        val id = "$collection|$key"
        // Found before but not usable here (a local file where a stream is needed): nothing more to find.
        if (resolvedIds.containsKey(key)) { failed[id] = track; return }
        if (failed.containsKey(id) || !resolving.add(id)) return
        publishStatus()
        scope.launch(Dispatchers.Default) {
            val song = try { matchSlots.withPermit { resolveTrack(track) } } catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (song == null) failed[id] = track else resolvedIds[key] = song.id
            resolving.remove(id)
            if (song != null) retry(listOf(if (collection.startsWith("playlist:")) PLAYLISTS else collection)) else publishStatus()
        }
    }

    /** Runs slow, network-bound applying (following an artist, a podcast's feed) once at a time per item, outside the lock. */
    private fun background(id: String, block: suspend () -> Unit) {
        if (!subscribing.add(id)) return
        scope.launch(Dispatchers.Default) {
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e } finally { subscribing.remove(id) }
        }
    }

    private suspend fun applyPodcast(c: SyncChange): Boolean {
        val show = podcasts.shows.value.firstOrNull { it.podcast.feedUrl == c.key }
        if (!c.present) {
            if (show != null && show.podcast.subscribedAt > 0) {
                if (show.podcast.kind == KIND_AUDIOBOOK) podcasts.unsubscribe(show.id).join() else podcasts.setFollowing(show.id, false).join()
            }
            return true
        }
        if (show != null && show.podcast.subscribedAt > 0) return true
        if (show != null) { podcasts.setFollowing(show.id, true).join(); return true }
        val s = c.value?.optJSONObject("_show") ?: JSONObject()
        fun text(name: String) = s.optString(name).takeIf { it.isNotBlank() && it != "null" }
        background("show:${c.key}") {
            podcasts.subscribe(c.key, text("artwork"), text("kind") ?: KIND_PODCAST, text("title"), text("author"))
            retry(listOf(PODCASTS))
        }
        return false
    }

    private suspend fun applyPlaylist(c: SyncChange): Boolean {
        val gid = c.key
        val localId = playlistIds[gid]?.takeIf { id -> library.playlistDb.value.playlists.any { it.id == id } }
        if (!c.present) {
            if (localId != null) library.deletePlaylist(localId).join()
            playlistIds.remove(gid); deletedPlaylists += gid; sync.forget(LibrarySync.playlist(gid))
            return true
        }
        val v = c.value ?: return true
        val name = v.optString("name").trim().ifEmpty { "My playlist" }
        val id = if (localId != null) {
            if (library.playlistDb.value.playlists.first { it.id == localId }.name != name) library.renamePlaylist(localId, name).join()
            localId
        } else library.createPlaylist(name).also { playlistIds[gid] = it; deletedPlaylists -= gid }
        val hash = v.optString("imageHash").takeIf { it.isNotBlank() && it != "null" }
        if (hash != coverOf(id)?.second) {
            val image = v.optString("_image")
            if (hash == null) library.setPlaylistCover(id, null)
            else if (image.startsWith("data:")) setCover(id, hash, runCatching { Base64.getDecoder().decode(image.substringAfter(",")) }.getOrNull())
            else if (image.startsWith("https://")) background("cover:$gid:$hash") {
                val bytes = withContext(Dispatchers.IO) { runCatching { HttpGet.open(image).let { conn -> try { conn.inputStream.use { it.readNBytes(MAX_COVER_BYTES) } } finally { conn.disconnect() } } }.getOrNull() }
                withState { if (sync.present(PLAYLISTS)[gid]?.optString("imageHash") == hash) setCover(id, hash, bytes) }
            }
        }
        // Its songs may have arrived first.
        applyEntries(gid, sync.pending(LibrarySync.playlist(gid)))
        return true
    }

    private suspend fun setCover(id: Long, hash: String, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val tmp = withContext(Dispatchers.IO) { File.createTempFile("spitify-cover", ".img").apply { writeBytes(bytes) } }
        try {
            if (library.setPlaylistCover(id, tmp)) coverHashes[id] = library.playlistCover(id).lastModified() to hash
        } finally { tmp.delete() }
    }

    /** Rebuilds a playlist's songs from the merged entries; entries whose songs aren't found yet stay pending. */
    private suspend fun applyEntries(gid: String, changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        val localId = playlistIds[gid]?.takeIf { id -> library.playlistDb.value.playlists.any { it.id == id } } ?: return
        val collection = LibrarySync.playlist(gid)
        val current = library.playlistDb.value.entries.filter { it.playlistId == localId }.sortedBy { it.position }
        val keyed = current.map { e -> trackOf(e.songId)?.let { keyOf(e.songId, it) } }
        val currentKeys = occurrences(keyed.map { it ?: "" })
        val byKey = HashMap<String, Long>()
        current.forEachIndexed { i, e -> if (keyed[i] != null) byKey[currentKeys[i]] = e.songId }
        val wanted = sync.present(collection).entries.sortedWith(compareBy({ it.value.optInt("pos") }, { it.key }))
        val ids = ArrayList<Long>()
        val missing = HashSet<String>()
        for ((entry, value) in wanted) {
            val id = byKey[entry] ?: songFor(SyncChange(collection, entry, true, value), key = entry.substringBeforeLast('#'))?.id
            if (id == null) missing += entry else ids += id
        }
        // Songs here that can't be described (unreadable, never seen) stay where they are.
        current.forEachIndexed { i, e -> if (keyed[i] == null) ids += e.songId }
        if (ids != current.map { it.songId }) library.reorderPlaylist(localId, ids).join()
        changes.filter { it.key !in missing }.forEach(sync::applied)
    }

    private fun historyListen(c: SyncChange): PlayEventEntity? {
        val v = c.value ?: return null
        if (c.key.startsWith(me.take(8) + ":")) return null
        val track = LibrarySync.track(v) ?: return null
        val song = songFor(c, key = LibrarySync.trackKey(track), search = false, register = false) ?: return null
        val skipped = v.optBoolean("skipped")
        return PlayEventEntity(songId = song.id, startedAt = v.optLong("playedAt"), listenedMs = v.optLong("listenedMs"), durationMs = v.optLong("durationMs"),
            completed = !skipped, skipped = skipped, source = TasteRepository.SYNC_SOURCE + c.key)
    }

    // ---- Other devices' play counts

    private fun queueRemoteStats() {
        if (!statsQueued.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            delay(500)
            val stats = withState {
                statsQueued.set(false)
                val index = localIndex()
                fun songFor(key: String) = index[key]?.minWithOrNull(compareBy { it.isStream })
                val plays = HashMap<Long, PlayStat>()
                val counted = HashSet<String>()
                for (name in sync.collections()) {
                    if (!name.startsWith("stats:") || name == LibrarySync.stats(me)) continue
                    for ((key, v) in sync.present(name)) {
                        val song = songFor(key) ?: continue
                        counted += key
                        val old = plays[song.id]
                        plays[song.id] = PlayStat(song.id, (old?.playCount ?: 0) + v.optInt("plays"), maxOf(old?.lastPlayed ?: 0, v.optLong("lastPlayed")), (old?.skipCount ?: 0) + v.optInt("skips"))
                    }
                }
                // Recently played: listens from the other devices, even before their counts arrive.
                val mine = me.take(8) + ":"
                for ((key, v) in sync.present(HISTORY)) {
                    if (key.startsWith(mine)) continue
                    val track = LibrarySync.track(v) ?: continue
                    val k = LibrarySync.trackKey(track)
                    val song = songFor(k) ?: continue
                    val old = plays[song.id]
                    val extra = if (k in counted || v.optBoolean("skipped")) 0 else 1
                    plays[song.id] = PlayStat(song.id, (old?.playCount ?: 0) + extra, maxOf(old?.lastPlayed ?: 0, v.optLong("playedAt")), old?.skipCount ?: 0)
                }
                plays
            }
            library.setRemoteStats(stats)
        }
    }

    // ---- Sending

    private fun queueSend(names: Collection<String>) {
        synchronized(outgoing) {
            outgoing += names
            sendJob?.cancel()
            sendJob = scope.launch(Dispatchers.Default) { delay(sendDelay); sendQueued() }
        }
        publishStatus()
    }

    private suspend fun sendQueued() {
        val names = synchronized(outgoing) { outgoing.toList().also { outgoing.clear() } }
        if (names.isEmpty() || !active) return
        val docs = withState { names.map { it to sync.doc(it) } }
        val failedNames = docs.filterNot { (name, doc) -> sendDoc(name, doc, linked) }.map { it.first }
        if (failedNames.isNotEmpty()) synchronized(outgoing) {
            outgoing += failedNames
            sendJob = scope.launch(Dispatchers.Default) { delay(RESEND_AFTER); sendQueued() }
        } else withState { lastSync = SocialRules.now; persistSoon() }
        publishStatus()
    }

    /** False when it should be tried again later (e.g. too much waiting to send). */
    private suspend fun sendDoc(name: String, doc: JSONObject, to: Collection<String>): Boolean {
        var ok = true
        for (device in to) {
            try { social.sendDevicePacket(SocialPacket("syncDoc", doc), "sync:$name", device, DOC_LIFETIME) }
            catch (e: IllegalArgumentException) { System.err.println("Spitify: library sync document $name is too large to send") }
            catch (e: Exception) { if (e is CancellationException) throw e; ok = false }
        }
        return ok
    }

    private suspend fun sendDigest() {
        val to = linked
        if (to.isEmpty()) return
        val body = withState { lastDigestAt = SocialRules.now; JSONObject().put("docs", JSONObject(sync.digest() as Map<*, *>)) }
        for (device in to) {
            try { social.sendDevicePacket(SocialPacket("syncDigest", body), "syncDigest", device, DIGEST_LIFETIME) }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    private fun publishStatus() {
        val waiting = synchronized(outgoing) { outgoing.size }
        _status.value = LibrarySyncStatus(active, waiting + resolving.size, lastSync, failed.values.distinctBy { LibrarySync.trackKey(it) }.sortedBy { it.title.lowercase() })
    }

    companion object {
        const val REPORT_DELAY = 2_000L
        const val SEND_DELAY = 3_000L
        private const val STATS = "stats"
        private const val DAY = 24 * 60 * 60_000L
        private const val PERSIST_EVERY = 2_000L
        private const val PROGRESS_EVERY = 60_000L
        private const val RETRY_EVERY = 60 * 60_000L
        private const val DIGEST_EVERY = 6 * 60 * 60_000L
        private const val FOREGROUND_DIGEST = 10 * 60_000L
        private const val RESEND_AFTER = 30_000L
        private const val DOC_LIFETIME = 60 * DAY
        private const val DIGEST_LIFETIME = 2 * DAY
        private const val MAX_IMAGE = 64 * 1024
        private const val MAX_COVER_BYTES = 4_000_000

        /** Every collection this computer reports ("stats" is this device's stats:<me>). */
        private val ALL = listOf(LIKED, SAVED_TRACKS, FOLLOWED_ARTISTS, HIDDEN_SONGS, HIDDEN_ARTISTS, HIDDEN_MIXES, PODCASTS, PROGRESS, PLAYLISTS, HISTORY, STATS, PROFILE, FRIENDS, SAVED_SHARED, SETTINGS)
        /** Collections of songs: reported once the library has loaded, retried when it changes. */
        private val SONG_COLLECTIONS = listOf(LIKED, HIDDEN_SONGS, PLAYLISTS, HISTORY, STATS)
        private val UI_COLLECTIONS = setOf(FRIENDS, SAVED_SHARED, SETTINGS)
        /** Remote collections whose songs are matched to this library. */
        private val SONG_MATCHED = setOf(LIKED, SAVED_TRACKS, HIDDEN_SONGS, HISTORY)

        fun imageHash(image: String) = SocialRules.hash(image.toByteArray()).take(16)

        /** Keys stay under the 400 characters a key may have. */
        private fun shortKey(key: String) = if (key.length <= 380) key else key.take(300) + "~" + SocialRules.hash(key.toByteArray()).take(32)

        private fun jsonValue(value: Any): Any = when (value) {
            is Float -> value.toString().toDouble()
            else -> value
        }

        fun sameSetting(a: Any?, b: Any?): Boolean = when {
            a == null || b == null -> a == b
            a is Number && b is Number -> abs(a.toDouble() - b.toDouble()) < 0.0005
            else -> a.toString() == b.toString()
        }
    }
}

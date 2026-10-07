package com.localfy.app.data.sync

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.db.LikedEntity
import com.localfy.app.data.db.PlayEventEntity
import com.localfy.app.data.db.ResumeEntity
import com.localfy.app.data.music.Monochrome
import com.localfy.app.data.music.OnlineArtist
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.data.music.SearchMatch
import com.localfy.app.data.podcast.KIND_PODCAST
import com.localfy.app.data.podcast.resumeKey
import com.localfy.app.data.social.DeviceSync
import com.localfy.app.data.social.PlaylistMatches
import com.localfy.app.data.social.SharedTrack
import com.localfy.app.data.social.SocialPacket
import com.localfy.app.data.social.SocialRules
import com.localfy.app.data.social.text
import com.localfy.app.playback.PlayerPrefs
import com.localfy.app.ui.theme.AccentSource
import com.localfy.app.ui.theme.AppFont
import com.localfy.app.ui.theme.ArtShape
import com.localfy.app.ui.theme.PlayerStyle
import com.localfy.app.ui.theme.TextSize
import com.localfy.app.ui.theme.ThemeMode
import com.localfy.app.ui.theme.ThemeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import kotlin.math.abs

/**
 * Wires [LibrarySyncRepository] to this phone (docs/library-sync.md): reads the library from Room and
 * the repositories, applies other devices' changes to them, and sends through the device relay.
 * Created only once a device is linked. Nothing here does I/O on the main thread: snapshots are built
 * on background threads and only small in-memory copies of main-thread state are taken there.
 */
class AndroidLibrarySync(private val app: LocalfyApp) : LibraryHost, SyncLink {
    private val file = File(app.filesDir, "library_sync.json")
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val db get() = app.database
    override val me: String = app.social.publicKey
    @Volatile private var linked: Set<String> = emptySet()
    val repo = LibrarySyncRepository(this, this, app.appScope)
    val status get() = repo.status
    private var started = false
    private val matchLimit = Semaphore(4)

    /** Local song id → the key another device knows it by (a song found by search may be titled a little differently). */
    private val aliases = HashMap<Long, String>()
    /** Local song id → its key when last seen, so a song that's briefly unreadable isn't reported as removed. */
    private val lastKeys = HashMap<Long, String>()
    /** Global playlist id → the cover that was reported or applied, so it isn't re-encoded (and doesn't look changed). */
    private val covers = HashMap<String, Cover>()
    private data class Cover(val modified: Long, val hash: String, val image: String)
    /** Songs whose search failed while offline: searched again when the network returns. */
    private val offlineFailures = HashSet<SharedTrack>()

    // ---- SyncLink ----

    override fun devices() = linked
    override suspend fun send(packet: SocialPacket, logical: String, recipient: String, expiresIn: Long) = app.social.sendDevice(packet, logical, recipient, expiresIn)

    /** From the device list: starts syncing once a device is linked. */
    fun devicesChanged(ids: Set<String>) {
        linked = ids
        if (ids.isNotEmpty() && !started) start()
        repo.devicesChanged(ids)
    }

    fun receive(author: String, packet: SocialPacket, encrypted: Boolean) = repo.receive(author, packet, encrypted)
    fun foreground() { if (started) repo.foreground() }
    fun retry() = repo.retry()

    /** A finished or skipped listen here goes into the shared history. */
    fun played(event: PlayEventEntity) {
        if (!started || event.source == SYNC_SOURCE) return
        val song = app.resolve(event.songId) ?: return
        app.appScope.launch(Dispatchers.Default) { historyItem(song, event)?.let { (k, v) -> repo.played(k, v) } }
    }

    private fun start() {
        started = true
        app.library.onRestoredAllMixes = { repo.changed(LibrarySync.HIDDEN_MIXES, explicit = true) }
        app.taste.onUnhidAll = { repo.changed(LibrarySync.HIDDEN_SONGS, explicit = true); repo.changed(LibrarySync.HIDDEN_ARTISTS, explicit = true) }
        repo.start()
        observe()
        watchNetwork()
    }

    // ---- Observing: every flow only marks its topic; the engine debounces and snapshots off the main thread ----

    private fun observe() {
        val scope = app.appScope
        val library = app.library
        fun watch(flow: Flow<*>, vararg topics: String) = scope.launch(Dispatchers.Default) { flow.distinctUntilChanged().collect { topics.forEach { repo.changed(it) } } }
        watch(library.likedIds, LibrarySync.LIKED)
        watch(library.loaded, LibrarySync.LIKED, LibrarySync.HIDDEN_SONGS, LibrarySync.PLAYLISTS, LibrarySync.PROGRESS, LibrarySync.stats(me))
        watch(library.playlists, LibrarySync.PLAYLISTS)
        watch(app.musicStreams.saved, LibrarySync.SAVED_TRACKS)
        watch(app.artistFollows.revision, LibrarySync.FOLLOWED_ARTISTS, LibrarySync.SETTINGS)
        watch(app.taste.hiddenSongs, LibrarySync.HIDDEN_SONGS)
        watch(app.taste.hiddenArtists, LibrarySync.HIDDEN_ARTISTS)
        watch(library.hiddenMixes, LibrarySync.HIDDEN_MIXES)
        watch(db.podcasts().observePodcasts(), LibrarySync.PODCASTS)
        watch(app.profiles.profile, LibrarySync.PROFILE)
        watch(app.social.revision, LibrarySync.FRIENDS, LibrarySync.SAVED_SHARED)
        watch(db.stats().observe(), LibrarySync.stats(me))
        watch(app.theme.settings, LibrarySync.SETTINGS)
        watch(library.showRecommendations, LibrarySync.SETTINGS)
        watch(library.hideShortTracks, LibrarySync.SETTINGS)
        watch(app.lyrics.onlineEnabled, LibrarySync.SETTINGS)
        watch(app.onlineArt.enabled, LibrarySync.SETTINGS)
        watch(app.metadata.autoFix, LibrarySync.SETTINGS)
        watch(app.player.state.map { listOf(it.autoplay, it.crossfadeMs, it.crossfadeKeepAlbums, it.normalizeAudio, it.skipSilence) }, LibrarySync.SETTINGS)
        playerPrefs.registerOnSharedPreferenceChangeListener(speedListener)
        // The library changing (a scan, a download landing) can let songs that weren't found match now.
        scope.launch(Dispatchers.Default) { library.library.collect { repo.libraryChanged(); restats() } }
        scope.launch(Dispatchers.Default) { db.podcasts().observeEpisodes().map { it.size }.distinctUntilChanged().collect { repo.libraryChanged() } }
        // Progress: at most once a minute while playing, soon after a pause.
        scope.launch {
            var job: Job? = null; var last = 0L
            app.podcasts.resume.collect {
                if (job?.isActive == true) return@collect
                val wait = if (app.player.state.value.playWhenReady) maxOf(0, last + 60_000 - System.currentTimeMillis()) else 0
                job = scope.launch { delay(wait); last = System.currentTimeMillis(); repo.changed(LibrarySync.PROGRESS) }
            }
        }
    }

    private val playerPrefs: SharedPreferences by lazy { app.getSharedPreferences(PlayerPrefs.FILE, 0) }
    private val speedListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == SPEED_MUSIC || key == SPEED_PODCAST) repo.changed(LibrarySync.SETTINGS) }

    private fun watchNetwork() {
        val manager = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val retry = synchronized(offlineFailures) { offlineFailures.toList().also { offlineFailures.clear() } }
                    if (retry.isNotEmpty()) app.appScope.launch { PlaylistMatches.forget(retry, app) }
                    repo.libraryChanged()
                }
            })
        }
    }

    private fun online() = runCatching { app.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null }.getOrDefault(true)

    // ---- Saving ----

    override suspend fun load(): String? = withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.readText() }
    override fun save(json: String) { app.appScope.launch(writer) { runCatching { val tmp = File(file.path + ".tmp"); tmp.writeText(json); tmp.renameTo(file) } } }

    @Synchronized override fun exportState(): JSONObject = JSONObject()
        .put("aliases", JSONObject().also { o -> aliases.forEach { (id, k) -> o.put(id.toString(), k) } })
        .put("lastKeys", JSONObject().also { o -> lastKeys.forEach { (id, k) -> o.put(id.toString(), k) } })
        .put("covers", JSONObject().also { o -> covers.forEach { (g, c) -> o.put(g, JSONObject().put("modified", c.modified).put("hash", c.hash).put("image", c.image)) } })

    @Synchronized override fun importState(o: JSONObject) {
        o.optJSONObject("aliases")?.let { a -> a.keys().forEach { k -> k.toLongOrNull()?.let { aliases[it] = a.getString(k) } } }
        o.optJSONObject("lastKeys")?.let { a -> a.keys().forEach { k -> k.toLongOrNull()?.let { lastKeys[it] = a.getString(k) } } }
        o.optJSONObject("covers")?.let { a -> a.keys().forEach { g -> val c = a.getJSONObject(g); covers[g] = Cover(c.optLong("modified"), c.getString("hash"), c.getString("image")) } }
    }

    @Synchronized private fun alias(id: Long, key: String) { aliases[id] = key; lastKeys[id] = key }
    @Synchronized private fun keyFor(id: Long, computed: String?): String? {
        val key = aliases[id] ?: computed ?: return lastKeys[id]
        lastKeys[id] = key; if (lastKeys.size > 20_000) lastKeys.keys.take(5_000).toList().forEach(lastKeys::remove)
        return key
    }

    // ---- Songs ----

    private fun libraryReady(): Boolean {
        val library = app.library
        return library.loaded.value && (library.rawSongs.value.isEmpty() || library.library.value.songs.isNotEmpty())
    }

    private fun track(song: Song): SharedTrack = DeviceSync.tidy(SharedTrack.from(song, app.musicStreams.track(song)))

    /** A local song as (key, {"track"}); null when it can't be read and was never seen. */
    private fun songItem(id: Long, known: Song? = null): Pair<String, JSONObject>? {
        val song = known ?: app.resolve(id)
        val track = song?.let(::track)
        val key = keyFor(id, track?.let(LibrarySync::trackKey)) ?: return null
        // A song that's unreadable right now keeps its key; its value isn't a change, so it never goes out.
        return key to if (track == null) JSONObject() else JSONObject().put("track", track.copy(id = key).json())
    }

    private class Index(val library: Any, val books: Any, val podcasts: Any, val byKey: Map<String, List<Song>>)
    @Volatile private var index: Index? = null

    /** trackKey → songs here: library files, downloads, saved streams, audiobook and podcast files. */
    private suspend fun index(): Map<String, List<Song>> = withContext(Dispatchers.Default) {
        val lib = app.library.library.value; val books = app.library.localBooks.value; val pods = app.library.localPodcasts.value
        index?.takeIf { it.library === lib && it.books === books && it.podcasts === pods }?.byKey ?: (lib.songs + books + pods)
            .groupBy { LibrarySync.trackKey(it.title.trim().ifEmpty { "Unknown song" }, it.artist.trim().ifEmpty { it.album.trim().ifEmpty { "Unknown artist" } }) }
            .also { index = Index(lib, books, pods, it) }
    }

    /**
     * The song here for [track] (docs/library-sync.md, Matching songs): a local copy with the same key
     * (closest length), else the catalogue stream by id, else the shared-track search. [cheap] skips
     * the search (history and counts, which may hold thousands of songs).
     */
    private suspend fun match(key: String, track: SharedTrack, cheap: Boolean): Song? {
        val candidates = index()[key].orEmpty()
        candidates.filter { !it.isStream }.minByOrNull { abs(it.durationMs - track.durationMs) }?.let { return it }
        track.sourceID?.takeIf(Monochrome::validId)?.let { id ->
            return app.musicStreams.register(OnlineTrack(id, track.title, track.artist, track.album, track.releaseID.orEmpty(), track.durationMs, 0, 1, track.artwork, true))
        }
        candidates.firstOrNull()?.let { return it }
        if (cheap) return null
        return matchLimit.withPermit {
            try { PlaylistMatches.resolve(track, app) }
            catch (e: Exception) { if (e is CancellationException) throw e; if (!online()) synchronized(offlineFailures) { offlineFailures += track }; null }
        }
    }

    override fun retrying(tracks: List<SharedTrack>) { app.appScope.launch { PlaylistMatches.forget(tracks, app) } }

    // ---- Reporting ----

    override val topics: Set<String> = setOf(
        LibrarySync.LIKED, LibrarySync.SAVED_TRACKS, LibrarySync.FOLLOWED_ARTISTS, LibrarySync.HIDDEN_SONGS, LibrarySync.HIDDEN_ARTISTS,
        LibrarySync.HIDDEN_MIXES, LibrarySync.PODCASTS, LibrarySync.PROGRESS, LibrarySync.PLAYLISTS, LibrarySync.PROFILE,
        LibrarySync.FRIENDS, LibrarySync.SAVED_SHARED, LibrarySync.SETTINGS, LibrarySync.stats(me),
    )

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    override suspend fun snapshot(topic: String): Map<String, Map<String, JSONObject>>? = withContext(Dispatchers.Default) {
        when (topic) {
            LibrarySync.LIKED -> if (!libraryReady()) null else one(topic, db.liked().all().mapNotNull { row -> songItem(row.songId)?.let { (k, v) -> k to v.put("_addedAt", row.likedAt) } })
            LibrarySync.SAVED_TRACKS -> one(topic, app.musicStreams.saved.value.mapNotNull { song -> songItem(song.id, song)?.let { (k, v) -> k to v.put("_addedAt", song.dateAddedSec * 1000) } })
            LibrarySync.FOLLOWED_ARTISTS -> one(topic, main { app.artistFollows.artists }.map { it.id to JSONObject().put("_artist", JSONObject().put("id", it.id).put("name", it.name).put("artwork", it.artwork)) })
            LibrarySync.HIDDEN_SONGS -> if (!libraryReady()) null else one(topic, app.taste.hiddenSongs.value.mapNotNull { id -> songItem(id) })
            LibrarySync.HIDDEN_ARTISTS -> one(topic, app.taste.hiddenArtists.value.map { SearchMatch.fold(it) to JSONObject().put("_name", it) }.filter { it.first.isNotEmpty() })
            LibrarySync.HIDDEN_MIXES -> one(topic, app.library.hiddenMixes.value.map { it to JSONObject() })
            LibrarySync.PODCASTS -> one(topic, db.podcasts().podcasts().filter { it.subscribedAt > 0 && it.feedUrl.length <= 400 }.map {
                it.feedUrl to JSONObject().put("_show", JSONObject().put("feedUrl", it.feedUrl).put("title", it.title).put("author", it.author).put("artwork", it.artworkUrl).put("kind", it.kind))
            })
            LibrarySync.PROGRESS -> if (!libraryReady()) null else one(topic, progress())
            // While a playlist from another device is being created it has no global id yet; reported right after.
            LibrarySync.PLAYLISTS -> if (!libraryReady() || repo.playlistIds.creating > 0) null else playlists()
            LibrarySync.PROFILE -> one(topic, profile())
            LibrarySync.FRIENDS -> one(topic, main { app.social.state.following.map { it to app.social.state.profiles[it]?.name } }.map { (id, name) -> id to JSONObject().put("_name", name) })
            LibrarySync.SAVED_SHARED -> one(topic, main { val me = app.social.publicKey; app.social.state.playlists.values.filter { it.owner != me }.map { it.key to it.name } }.map { (k, name) -> k to JSONObject().put("_name", name) })
            LibrarySync.SETTINGS -> {
                // A default is only reported once the setting is shared: a new device's defaults must not replace your choices.
                val shared = repo.read { it.present(LibrarySync.SETTINGS) }
                val mine = main { settings.mapNotNull { s -> s.read().takeIf { s.name in shared || !same(it, s.default) }?.let { s.name to JSONObject().put("value", it) } } }
                val names = settings.map { it.name }.toSet()
                // Settings this phone doesn't have pass through untouched; the same meaning keeps the shared value exactly.
                one(topic, shared.filterKeys { it !in names }.toList() + mine.map { (k, v) -> k to (shared[k]?.takeIf { LibrarySync.meaning(it) == LibrarySync.meaning(v) } ?: v) })
            }
            LibrarySync.stats(me) -> if (!libraryReady()) null else one(topic, ownStats())
            else -> null
        }
    }

    private fun one(collection: String, items: List<Pair<String, JSONObject>>) = mapOf(collection to items.filter { it.first.length <= 400 }.toMap())

    private suspend fun ownStats(): List<Pair<String, JSONObject>> = db.stats().all().mapNotNull { row ->
        songItem(row.songId)?.let { (k, v) -> Triple(k, v, row) }
    }.groupBy { it.first }.map { (key, rows) ->
        val value = rows.first().second
        key to value.put("plays", rows.sumOf { it.third.playCount }).put("skips", rows.sumOf { it.third.skipCount }).put("lastPlayed", rows.maxOf { it.third.lastPlayed })
    }

    private suspend fun progress(): List<Pair<String, JSONObject>> {
        val rows = db.podcasts().allResume()
        val episodeIds = rows.mapNotNull { it.mediaKey.removePrefix("ep:").takeIf { _ -> it.mediaKey.startsWith("ep:") }?.toLongOrNull() }
        val episodes = episodeIds.chunked(500).flatMap { db.podcasts().episodes(it) }.associateBy { it.id }
        val feeds = db.podcasts().podcasts().associate { it.id to it.feedUrl }
        return rows.mapNotNull { row ->
            val key = if (row.mediaKey.startsWith("ep:")) {
                val episode = row.mediaKey.removePrefix("ep:").toLongOrNull()?.let(episodes::get) ?: return@mapNotNull null
                episodeKey(feeds[episode.podcastId] ?: return@mapNotNull null, episode.guid)
            } else {
                val song = row.mediaKey.toLongOrNull()?.let(app::resolve) ?: return@mapNotNull null
                trackProgressKey(songItem(song.id, song)?.first ?: return@mapNotNull null)
            }
            key to JSONObject().put("positionMs", row.positionMs / 5_000 * 5_000).put("durationMs", row.durationMs).put("played", row.played).put("_at", row.updatedAt)
        }
    }

    private suspend fun playlists(): Map<String, Map<String, JSONObject>> {
        val lists = db.playlists().all()
        val entries = db.playlists().allEntries().groupBy { it.playlistId }
        val ids = repo.playlistIds
        val globals = lists.associate { it.id to ids.global(it.id) }
        val synced = repo.read { s -> s.present(LibrarySync.PLAYLISTS) to globals.values.associateWith { s.present(LibrarySync.playlist(it)) } }
        val out = HashMap<String, Map<String, JSONObject>>()
        out[LibrarySync.PLAYLISTS] = lists.associate { p ->
            val gid = globals.getValue(p.id)
            val remote = synced.first[gid]
            // Android playlists have no description: keep whatever the other devices gave it.
            val value = JSONObject().put("name", p.name).put("description", remote?.optString("description").orEmpty()).put("_createdAt", p.createdAt)
            cover(gid, p.id)?.let { value.put("imageHash", it.hash).put("_image", it.image) }
            // Same meaning as the shared value: report that exact value.
            gid to (remote?.takeIf { LibrarySync.meaning(it) == LibrarySync.meaning(value) } ?: value)
        }
        lists.forEach { p ->
            val gid = globals.getValue(p.id)
            val keys = entries[p.id].orEmpty().mapNotNull { e -> songItem(e.songId) }
            val seen = HashMap<String, Int>()
            val keyed = keys.map { (k, v) -> val n = (seen[k] ?: 0) + 1; seen[k] = n; "$k#$n" to v }
            val previous = synced.second[gid].orEmpty().mapValues { it.value.optInt("pos", -1) }
            val positions = LibrarySyncRepository.positions(keyed.map { it.first }, previous)
            out[LibrarySync.playlist(gid)] = keyed.mapIndexed { i, (k, v) -> k to v.put("pos", positions[i]) }.toMap()
        }
        return out
    }

    /** The playlist's cover as reported: re-encoded only when the file changed, so it never looks changed by itself. */
    private fun cover(gid: String, localId: Long): Cover? {
        val file = app.library.playlistCover(localId)
        val remembered = synchronized(this) { covers[gid] }
        if (!file.isFile) return remembered?.takeIf { it.modified == 0L }
        if (remembered != null && remembered.modified == file.lastModified()) return remembered
        val image = encodeCover(file) ?: return remembered
        return Cover(file.lastModified(), imageHash(image), image).also { synchronized(this) { covers[gid] = it } }
    }

    /** A JPEG data URL of at most 300 px and 24 KB, small enough that a few dozen playlists fit in one document. */
    private fun encodeCover(file: File): String? = runCatching {
        val original = BitmapFactory.decodeFile(file.path) ?: return null
        try {
            for ((size, quality) in listOf(300 to 80, 300 to 60, 256 to 60, 200 to 60)) {
                val scaled = if (original.width > size) Bitmap.createScaledBitmap(original, size, size * original.height / original.width, true) else original
                val bytes = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                if (scaled !== original) scaled.recycle()
                val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
                if (url.length <= MAX_IMAGE) return url
            }
            null
        } finally { original.recycle() }
    }.getOrNull()

    private suspend fun profile(): List<Pair<String, JSONObject>> {
        // Defaults are reported only once shared: a new device's blank profile must not replace the one you have.
        val shared = repo.read { it.present(LibrarySync.PROFILE).keys }
        val p = app.profiles.profile.value
        return buildList {
            if (p.name.isNotBlank() || "name" in shared) add("name" to JSONObject().put("value", p.name.trim()))
            if (p.seedArtists.isNotEmpty() || "seedArtists" in shared) add("seedArtists" to JSONObject().put("value", JSONArray(p.seedArtists.sorted())))
            if (p.onboarded || "onboarded" in shared) add("onboarded" to JSONObject().put("value", p.onboarded))
        }
    }

    override suspend fun localHistory(): List<Pair<String, JSONObject>> = withContext(Dispatchers.Default) {
        withTimeoutOrNull(120_000) { app.library.loaded.first { it } }
        val since = System.currentTimeMillis() - LibrarySync.HISTORY_DAYS * 86_400_000L
        db.events().since(since).filter { it.source != SYNC_SOURCE }.mapNotNull { e -> app.resolve(e.songId)?.let { historyItem(it, e) } }
    }

    private fun historyItem(song: Song, e: PlayEventEntity): Pair<String, JSONObject>? {
        val (key, value) = songItem(song.id, song) ?: return null
        if (!value.has("track")) return null
        return "${me.take(8)}:${e.startedAt}:$key" to value.put("playedAt", e.startedAt).put("listenedMs", e.listenedMs).put("durationMs", e.durationMs).put("skipped", e.skipped)
    }

    // ---- Counts from the other devices ----

    @Volatile private var lastRemote: Map<String, Map<String, JSONObject>> = emptyMap()
    private var restatsJob: Job? = null

    override fun remoteStats(byDevice: Map<String, Map<String, JSONObject>>) { lastRemote = byDevice; restats() }

    /** Other devices' counts, matched to songs here, summed into what the UI shows. */
    @Synchronized private fun restats() {
        if (lastRemote.isEmpty() && app.library.remoteStats.value.isEmpty()) return
        restatsJob?.cancel()
        restatsJob = app.appScope.launch(Dispatchers.Default) {
            delay(1_000)
            val index = index()
            val out = HashMap<Long, com.localfy.app.data.PlayStat>()
            lastRemote.values.forEach { items ->
                items.forEach { (key, v) ->
                    val song = index[key]?.minByOrNull { if (it.isStream) 1 else 0 } ?: return@forEach
                    val old = out[song.id]
                    val plays = v.optInt("plays"); val skips = v.optInt("skips"); val last = v.optLong("lastPlayed")
                    out[song.id] = com.localfy.app.data.PlayStat(song.id, (old?.playCount ?: 0) + plays, maxOf(old?.lastPlayed ?: 0, last), (old?.skipCount ?: 0) + skips)
                }
            }
            app.library.remoteStats.value = out
        }
    }

    // ---- Applying ----

    override suspend fun apply(collection: String, changes: List<SyncChange>, present: Map<String, JSONObject>): List<SyncChange> = withContext(Dispatchers.Default) {
        when {
            collection == LibrarySync.LIKED -> applyLiked(changes)
            collection == LibrarySync.SAVED_TRACKS -> applySaved(changes)
            collection == LibrarySync.FOLLOWED_ARTISTS -> applyArtists(changes)
            collection == LibrarySync.HIDDEN_SONGS -> applyHiddenSongs(changes)
            collection == LibrarySync.HIDDEN_ARTISTS -> applyHiddenArtists(changes)
            collection == LibrarySync.HIDDEN_MIXES -> changes.also { list -> main { list.forEach { if (it.present) app.library.deleteMix(it.key) else app.library.restoreMix(it.key) } } }
            collection == LibrarySync.PODCASTS -> applyPodcasts(changes)
            collection == LibrarySync.PROGRESS -> applyProgress(changes)
            collection == LibrarySync.PLAYLISTS -> applyPlaylists(changes)
            collection.startsWith("playlist:") -> applyEntries(collection.removePrefix("playlist:"), changes, present)
            collection == LibrarySync.HISTORY -> applyHistory(changes)
            collection == LibrarySync.PROFILE -> applyProfile(changes)
            collection == LibrarySync.FRIENDS -> changes.also { list -> main { list.forEach { app.social.syncFollow(it.key, it.present) } } }
            collection == LibrarySync.SAVED_SHARED -> applySavedShared(changes)
            collection == LibrarySync.SETTINGS -> applySettings(changes)
            // savedAlbums: Android has no saved online albums. Nothing to do, and nothing pending.
            collection == LibrarySync.SAVED_ALBUMS -> changes
            else -> emptyList()
        }
    }

    /** Matches the songs of [changes] four at a time; removals and unmatched songs map to null. */
    private suspend fun songsFor(changes: List<SyncChange>, cheap: Boolean = false, baseKey: (String) -> String = { it }): List<Pair<SyncChange, Song?>> = coroutineScope {
        changes.map { change ->
            async {
                val track = if (change.present) LibrarySync.track(change.value) else null
                val key = baseKey(change.key)
                // A song found by search may be titled a little differently here: it keeps the other devices' key.
                change to track?.let { match(key, it, cheap) }?.also { song -> if (LibrarySync.trackKey(track(song)) != key) alias(song.id, key) }
            }
        }.awaitAll()
    }

    /** Local song ids by their key here. */
    private fun byKey(ids: Collection<Long>): Map<String?, List<Long>> = ids.groupBy { songItem(it)?.first }

    private suspend fun applyLiked(changes: List<SyncChange>): List<SyncChange> {
        val liked = db.liked().all().map { it.songId }.toSet()
        val keyed by lazy { byKey(liked) }
        val done = ArrayList<SyncChange>()
        songsFor(changes).forEach { (change, song) ->
            if (change.present) {
                song ?: return@forEach
                if (song.id !in liked) db.liked().like(LikedEntity(song.id, change.value?.optLong("_addedAt")?.takeIf { it > 0 } ?: System.currentTimeMillis()))
                done += change
            } else { keyed[change.key].orEmpty().forEach { db.liked().unlike(it) }; done += change }
        }
        return done
    }

    private suspend fun applySaved(changes: List<SyncChange>): List<SyncChange> {
        val done = ArrayList<SyncChange>(); val save = ArrayList<OnlineTrack>(); val remove = ArrayList<OnlineTrack>()
        val saved = app.musicStreams.saved.value
        songsFor(changes).forEach { (change, song) ->
            if (change.present) { val online = song?.let(app.musicStreams::track) ?: return@forEach; save += online; done += change }
            else { saved.filter { songItem(it.id, it)?.first == change.key }.mapNotNullTo(remove, app.musicStreams::track); done += change }
        }
        if (save.isNotEmpty()) app.musicStreams.save(save)
        if (remove.isNotEmpty()) app.musicStreams.remove(remove)
        return done
    }

    private suspend fun applyArtists(changes: List<SyncChange>): List<SyncChange> = changes.filter { change ->
        if (!change.present) { main { app.artistFollows.unfollow(change.key) }; return@filter true }
        if (main { app.artistFollows.contains(change.key) }) return@filter true
        val artist = change.value?.optJSONObject("_artist") ?: return@filter false
        val name = artist.text("name") ?: return@filter false
        if (!Monochrome.validId(change.key)) return@filter false
        // Its albums are known when following, so they aren't all announced as new releases.
        val page = try { Monochrome.artistPage(change.key) } catch (e: Exception) { if (e is CancellationException) throw e; return@filter false }
        main { runCatching { app.artistFollows.follow(OnlineArtist(change.key, name, artist.text("artwork")), page.albums) }.isSuccess }
    }

    private suspend fun applyHiddenSongs(changes: List<SyncChange>): List<SyncChange> {
        val keyed by lazy { byKey(app.taste.hiddenSongs.value) }
        val done = ArrayList<SyncChange>()
        songsFor(changes).forEach { (change, song) ->
            if (change.present) { song ?: return@forEach; main { app.taste.hideSong(song.id) }; done += change }
            else { keyed[change.key].orEmpty().forEach { id -> main { app.taste.unhideSong(id) } }; done += change }
        }
        return done
    }

    private suspend fun applyHiddenArtists(changes: List<SyncChange>): List<SyncChange> = main {
        changes.onEach { change ->
            val names = app.taste.hiddenArtists.value.filter { SearchMatch.fold(it) == change.key }
            if (change.present) { if (names.isEmpty()) app.taste.hideArtist(change.value?.text("_name") ?: change.key) }
            else names.forEach(app.taste::unhideArtist)
        }
    }

    private suspend fun applyPodcasts(changes: List<SyncChange>): List<SyncChange> = changes.filter { change ->
        if (!change.present) {
            db.podcasts().byFeed(change.key)?.takeIf { it.subscribedAt > 0 }?.let { app.podcasts.setFollowing(it.id, false).join() }
            return@filter true
        }
        val show = change.value?.optJSONObject("_show")
        app.podcasts.subscribe(change.key, show?.text("artwork"), show?.text("kind") ?: KIND_PODCAST, show?.text("title"), show?.text("author")) != null
    }

    private suspend fun applyProgress(changes: List<SyncChange>): List<SyncChange> = changes.filter { change ->
        if (!change.present) return@filter true // progress is never removed here
        val value = change.value ?: return@filter true
        val key = localResumeKey(change.key) ?: return@filter false
        val at = value.optLong("_at")
        val local = db.podcasts().resume(key)
        // Newer wins: if this device listened more recently, keep it; reporting again sends it out as the newer one.
        if (local != null && at > 0 && local.updatedAt > at) return@filter true
        db.podcasts().putResume(ResumeEntity(key, value.optLong("positionMs"), value.optLong("durationMs"), value.optBoolean("played"), if (at > 0) at else System.currentTimeMillis()))
        true
    }

    private suspend fun localResumeKey(key: String): String? {
        if (key.startsWith(TRACK_PREFIX)) {
            val index = index()
            // Long keys are shortened with a hash, so find the song whose key shortens the same way.
            val songKey = if (key.length <= MAX_KEY) key.removePrefix(TRACK_PREFIX) else index.keys.firstOrNull { trackProgressKey(it) == key } ?: return null
            return index[songKey]?.firstOrNull()?.resumeKey ?: aliases(songKey)?.toString()
        }
        if (!key.startsWith(EPISODE_PREFIX)) return null
        // Feeds and guids may hold "#": the shows here are few, so compare whole keys.
        return app.podcasts.shows.value.firstNotNullOfOrNull { show ->
            show.episodes.firstOrNull { episodeKey(show.podcast.feedUrl, it.guid) == key }?.let { "ep:${it.id}" }
        }
    }

    @Synchronized private fun aliases(key: String): Long? = aliases.entries.firstOrNull { it.value == key }?.key

    private suspend fun applyPlaylists(changes: List<SyncChange>): List<SyncChange> = changes.filter { change ->
        val ids = repo.playlistIds
        val local = ids.local(change.key)?.takeIf { db.playlists().playlist(it) != null }
        if (!change.present) {
            if (local != null) { db.playlists().delete(local); app.library.playlistCover(local).delete() }
            synchronized(this) { covers.remove(change.key) }
            return@filter true
        }
        val value = change.value ?: return@filter true
        val name = value.text("name")?.take(200) ?: "My playlist"
        val id = local ?: ids.creating { app.library.createPlaylist(name).also { ids.map(change.key, it) } }
        if (local != null && db.playlists().playlist(id)?.name != name) db.playlists().rename(id, name, System.currentTimeMillis())
        applyCover(change.key, id, value)
        true
    }

    private suspend fun applyCover(gid: String, localId: Long, value: JSONObject) {
        val hash = value.text("imageHash"); val image = value.text("_image")
        val known = synchronized(this) { covers[gid] }
        if (hash == null) {
            // The cover was removed elsewhere: remove the one that came from there (or was reported from here).
            if (known != null) { app.library.setPlaylistCoverBytes(localId, null); synchronized(this) { covers.remove(gid) } }
            return
        }
        if (known?.hash == hash || image == null) return
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    image.startsWith("data:image/") -> Base64.getDecoder().decode(image.substringAfter("base64,"))
                    SocialRules.publicURL(image) -> java.net.URL(image).openStream().use { it.readNBytes(4_000_000) }
                    else -> null
                }
            }.getOrNull()
        }
        val stored = bytes != null && app.library.setPlaylistCoverBytes(localId, bytes)
        val modified = if (stored) app.library.playlistCover(localId).lastModified() else 0L
        synchronized(this) { covers[gid] = Cover(modified, hash, image) }
    }

    /** Rebuilds the playlist from the merged entries, keeping songs here that have no key (unreadable right now). */
    private suspend fun applyEntries(gid: String, changes: List<SyncChange>, present: Map<String, JSONObject>): List<SyncChange> {
        val localId = repo.playlistIds.local(gid)?.takeIf { db.playlists().playlist(it) != null } ?: return emptyList()
        val target = present.entries.sortedWith(compareBy({ it.value.optInt("pos") }, { it.key }))
        val wanted = songsFor(target.map { SyncChange(LibrarySync.playlist(gid), it.key, true, it.value) }) { it.substringBeforeLast('#') }
        val current = db.playlists().entries(localId)
        val unkeyed = current.filter { songItem(it.songId) == null }.map { it.songId }
        val ids = wanted.mapNotNull { it.second?.id } + unkeyed
        if (ids != current.map { it.songId }) db.playlists().reorder(localId, ids, System.currentTimeMillis())
        val matched = wanted.filter { it.second != null }.map { it.first.key }.toSet()
        return changes.filter { !it.present || it.key in matched }
    }

    private suspend fun applyHistory(changes: List<SyncChange>): List<SyncChange> {
        val mine = me.take(8) + ":"
        val have = db.events().bySource(SYNC_SOURCE).map { "${it.songId}:${it.startedAt}" }.toHashSet()
        val done = ArrayList<SyncChange>(); val insert = ArrayList<PlayEventEntity>()
        songsFor(changes.filter { !it.key.startsWith(mine) }, cheap = true) { it.substringAfter(':').substringAfter(':') }.forEach { (change, song) ->
            val value = change.value ?: return@forEach
            song ?: return@forEach
            val at = value.optLong("playedAt"); val listened = value.optLong("listenedMs"); val duration = value.optLong("durationMs"); val skipped = value.optBoolean("skipped")
            if (have.add("${song.id}:$at")) insert += PlayEventEntity(songId = song.id, startedAt = at, listenedMs = listened, durationMs = duration,
                completed = !skipped && duration > 0 && listened >= duration * 0.85, skipped = skipped, source = SYNC_SOURCE)
            done += change
        }
        done += changes.filter { it.key.startsWith(mine) }
        insert.chunked(500).forEach { db.events().insertAll(it) }
        return done
    }

    private suspend fun applyProfile(changes: List<SyncChange>): List<SyncChange> = main {
        val profiles = app.profiles
        changes.onEach { change ->
            if (!change.present) return@onEach // a blank or default profile field never replaces yours
            val value = change.value ?: return@onEach
            when (change.key) {
                "name" -> value.text("value")?.trim()?.take(80)?.takeIf { it.isNotEmpty() && it != profiles.profile.value.name }?.let(profiles::setName)
                "seedArtists" -> value.optJSONArray("value")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }?.takeIf { it != profiles.profile.value.seedArtists }?.let(profiles::setSeedArtists)
                "onboarded" -> if (value.optBoolean("value") && !profiles.profile.value.onboarded) profiles.completeOnboarding()
            }
        }
    }

    private suspend fun applySavedShared(changes: List<SyncChange>): List<SyncChange> = main {
        changes.filter { change ->
            val existing = app.social.state.playlists[change.key]
            if (!change.present) { existing?.let(app.social::remove); return@filter true }
            if (existing != null) return@filter true
            val owner = change.key.substringBefore(':'); val id = change.key.substringAfter(':')
            // It arrives like any share; until then it stays pending.
            app.social.requestShared(owner, id)
            false
        }
    }

    // ---- Settings ----

    private class Setting(val name: String, val default: Any, val read: () -> Any, val write: (Any) -> Unit)

    private fun same(a: Any?, b: Any?) = LibrarySync.meaning(JSONObject().put("v", a)) == LibrarySync.meaning(JSONObject().put("v", b))
    private fun <E : Enum<E>> E.wire() = name
    private fun Any.bool() = this as? Boolean ?: (this.toString() == "true")
    private fun Any.number() = (this as? Number)?.toDouble() ?: this.toString().toDoubleOrNull() ?: 0.0
    private fun round(value: Float) = Math.round(value * 100) / 100.0
    private fun theme(change: (ThemeSettings) -> ThemeSettings) = app.theme.update(change)

    private val settings: List<Setting> by lazy {
        val d = ThemeSettings()
        val t = { app.theme.settings.value }
        val player = { app.player.state.value }
        listOf(
            Setting("themeMode", d.mode.wire(), { t().mode.wire() }) { v -> ThemeMode.entries.firstOrNull { it.name.equals(v.toString(), ignoreCase = true) }?.let { m -> theme { it.copy(mode = m) } } },
            Setting("accent", d.accent, { t().accent }) { v -> (v as? Number)?.toLong()?.let { c -> theme { it.copy(accent = c) } } },
            Setting("accentFromArt", false, { t().accentSource == AccentSource.Artwork }) { v ->
                theme { if (v.bool()) it.copy(accentSource = AccentSource.Artwork) else if (it.accentSource == AccentSource.Artwork) it.copy(accentSource = AccentSource.Preset) else it }
            },
            Setting("font", d.font.wire(), { t().font.wire() }) { v -> AppFont.entries.firstOrNull { it.name.equals(v.toString(), ignoreCase = true) }?.let { f -> theme { it.copy(font = f) } } },
            Setting("textScale", round(d.textSize.scale), { round(t().textSize.scale) }) { v -> TextSize.entries.minBy { abs(it.scale - v.number()) }.let { s -> theme { it.copy(textSize = s) } } },
            Setting("artShape", d.artShape.wire(), { t().artShape.wire() }) { v -> ArtShape.entries.firstOrNull { it.name.equals(v.toString(), ignoreCase = true) }?.let { s -> theme { it.copy(artShape = s) } } },
            Setting("playerStyle", d.playerStyle.wire(), { t().playerStyle.wire() }) { v -> PlayerStyle.entries.firstOrNull { it.name.equals(v.toString(), ignoreCase = true) }?.let { s -> theme { it.copy(playerStyle = s) } } },
            Setting("artworkTint", d.artworkTint, { t().artworkTint }) { v -> theme { it.copy(artworkTint = v.bool()) } },
            Setting("blurBackdrop", d.blurBackdrop, { t().blurBackdrop }) { v -> theme { it.copy(blurBackdrop = v.bool()) } },
            Setting("reduceMotion", d.reduceMotion, { t().reduceMotion }) { v -> theme { it.copy(reduceMotion = v.bool()) } },
            Setting("showRecommendations", true, { app.library.showRecommendations.value }) { v -> app.library.setShowRecommendations(v.bool()) },
            Setting("hideShortTracks", true, { app.library.hideShortTracks.value }) { v -> if (app.library.hideShortTracks.value != v.bool()) app.library.setHideShortTracks(v.bool()) },
            Setting("autoplay", true, { player().autoplay }) { v -> if (player().autoplay != v.bool()) app.player.setAutoplay(v.bool()) },
            Setting("crossfadeMs", 0, { player().crossfadeMs }) { v -> app.player.setCrossfade(v.number().toInt().coerceIn(0, 12_000)) },
            Setting("crossfadeKeepAlbums", true, { player().crossfadeKeepAlbums }) { v -> app.player.setCrossfadeKeepAlbums(v.bool()) },
            Setting("normalizeAudio", true, { player().normalizeAudio }) { v -> app.player.setNormalizeAudio(v.bool()) },
            Setting("skipSilence", false, { player().skipSilence }) { v -> if (player().skipSilence != v.bool()) app.player.setSkipSilence(v.bool()) },
            Setting("speedMusic", 1.0, { round(playerPrefs.getFloat(SPEED_MUSIC, 1f)) }) { v -> playerPrefs.edit().putFloat(SPEED_MUSIC, v.number().toFloat().coerceIn(0.25f, 3f)).apply() },
            Setting("speedPodcast", 1.0, { round(playerPrefs.getFloat(SPEED_PODCAST, 1f)) }) { v -> playerPrefs.edit().putFloat(SPEED_PODCAST, v.number().toFloat().coerceIn(0.25f, 3f)).apply() },
            Setting("onlineLyrics", true, { app.lyrics.onlineEnabled.value }) { v -> app.lyrics.setOnlineEnabled(v.bool()) },
            Setting("onlineArt", true, { app.onlineArt.enabled.value }) { v -> app.onlineArt.setEnabled(v.bool()) },
            Setting("autoFixMetadata", true, { app.metadata.autoFix.value }) { v -> if (app.metadata.autoFix.value != v.bool()) app.metadata.setAutoFix(v.bool()) },
            Setting("releaseNotifications", false, { app.artistFollows.notifications }) { v -> app.artistFollows.setNotifications(v.bool()) },
        ).filter { it.name in LibrarySync.SYNCED_SETTINGS }
    }

    private suspend fun applySettings(changes: List<SyncChange>): List<SyncChange> = main {
        changes.onEach { change ->
            val setting = settings.firstOrNull { it.name == change.key } ?: return@onEach
            // A removal means it went back to the default there.
            val value = if (change.present) change.value?.opt("value")?.takeIf { it != JSONObject.NULL } ?: return@onEach else setting.default
            if (!same(setting.read(), value)) runCatching { setting.write(value) }
        }
    }

    companion object {
        const val SYNC_SOURCE = "sync"
        private const val TRACK_PREFIX = "t:"
        private const val EPISODE_PREFIX = "e:"
        private const val MAX_KEY = 380
        private const val SPEED_MUSIC = "speed_music"
        private const val SPEED_PODCAST = "speed_podcast"
        /** Covers made here stay well under the spec's 64 KB so a document of playlists stays small. */
        private const val MAX_IMAGE = 24_000

        /** Progress key for an episode, the same on every device: its feed and its guid. */
        fun episodeKey(feedUrl: String, guid: String) = shorten("$EPISODE_PREFIX$feedUrl#$guid")
        /** Progress key for a local audiobook or podcast file or a long track: its song key. */
        fun trackProgressKey(trackKey: String) = shorten(TRACK_PREFIX + trackKey)
        /** Keys over 380 characters keep their start plus a hash, the same on every platform. */
        fun shorten(key: String) = if (key.length <= MAX_KEY) key else key.take(300) + "~" + SocialRules.hash(key.toByteArray()).take(32)
        fun imageHash(image: String) = SocialRules.hash(image.toByteArray()).take(16)

    }
}

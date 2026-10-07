package com.localfy.app.data

import com.localfy.app.data.db.LikedEntity
import com.localfy.app.data.db.PlayStatEntity
import com.localfy.app.data.db.PlaylistEntity
import com.localfy.app.data.db.PlaylistEntryEntity
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.music.AudioFallback
import com.localfy.app.data.music.SearchMatch
import com.localfy.app.desktop.AppPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Computed shelves: things a streaming service would compute server-side, done locally. */
data class SmartCollection(val kind: Kind, val songs: List<Song>) {
    enum class Kind(val title: String, val subtitle: String) {
        AllSongs("All Songs", "Every song in your library"),
        RecentlyAdded("Recently added", "Fresh on this device"),
        MostPlayed("On repeat", "Your most played tracks"),
        RecentlyPlayed("Recently played", "Pick up where you left off"),
        Forgotten("Forgotten favourites", "Loved once, not heard lately"),
        NeverPlayed("Undiscovered", "Songs you own but never played"),
    }

    companion object {
        /** The same shelves Android builds from the library, likes and play stats. */
        fun build(lib: Library, liked: Set<Long>, stats: Map<Long, PlayStat>, now: Long = System.currentTimeMillis(), seed: Int = daySeed()): Map<Kind, SmartCollection> {
            val month = 30L * 24 * 60 * 60 * 1000
            val played = lib.songs.filter { (stats[it.id]?.playCount ?: 0) > 0 }
            val kinds = mapOf(
                Kind.AllSongs to lib.songs,
                Kind.RecentlyAdded to lib.songs.sortedByDescending { it.dateAddedSec }.take(100),
                Kind.MostPlayed to played.sortedByDescending { stats[it.id]!!.playCount }.take(50),
                Kind.RecentlyPlayed to played.sortedByDescending { stats[it.id]!!.lastPlayed }.take(50),
                Kind.Forgotten to played.filter {
                    val s = stats[it.id]!!
                    s.playCount >= 3 && now - s.lastPlayed > month
                }.sortedByDescending { stats[it.id]!!.playCount }.take(50),
                Kind.NeverPlayed to lib.songs.filter { it.id !in stats || stats[it.id]!!.playCount == 0 }
                    .shuffled(Random(seed)).take(100),
            )
            return kinds.mapValues { (k, v) -> SmartCollection(k, v) }
        }

        fun daySeed(): Int {
            val c = Calendar.getInstance()
            return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        }
    }
}

/**
 * The music library on this computer: songs found in the library folders (default: the user's
 * Music folder), plus saved online songs, with the user's metadata corrections applied; likes,
 * play stats, playlists, smart shelves and generated mixes. Desktop port of the Android
 * repository with the same public members (folders replace MediaStore; JSON files replace Room).
 *
 * Folders are watched for changes (debounced); rescans only re-read new or changed files.
 *
 * @param savedStreams saved online songs (MusicStreams.saved on Android); merged into the library.
 * @param streamLookup resolves a playlist entry's song id that isn't in the library (saved streams).
 */
class LibraryRepository(
    private val scope: CoroutineScope,
    private val metadata: MetadataRepository,
    savedStreams: Flow<List<Song>> = flowOf(emptyList()),
    private val streamLookup: (Long) -> Song? = { null },
    private val dataDir: File = AppPaths.dataDir,
    cacheDir: File = AppPaths.cacheDir,
    private val defaultFolders: List<File> = listOf(AppPaths.musicDir),
    /** Watch the folders for changes (off in tests). */
    private val watchFolders: Boolean = true,
) {
    private val scanner = MediaScanner(File(cacheDir, "library-scan.json"))
    private val prefs = PrefsFile(File(dataDir, "prefs-library.json"))
    private val likedStore = DataFile(File(dataDir, "liked.json"))
    private val statsStore = DataFile(File(dataDir, "play_stats.json"))
    private val playlistStore = DataFile(File(dataDir, "playlists.json"))
    private val coverDir = File(dataDir, "playlist_covers")
    private val dbLock = Mutex()

    // ---------- Library folders ----------

    private val _folders = MutableStateFlow(prefs.getStringList(KEY_FOLDERS)?.map(::File) ?: defaultFolders)
    /** Folders scanned for music (recursively). */
    val folders: StateFlow<List<File>> = _folders.asStateFlow()

    fun addFolder(folder: File) {
        val f = folder.absoluteFile.normalize()
        if (_folders.value.any { it.absoluteFile.normalize() == f }) return
        _folders.value = _folders.value + f
        prefs.edit { putStringList(KEY_FOLDERS, _folders.value.map { it.path }) }
        refresh()
    }

    fun removeFolder(folder: File) {
        val f = folder.absoluteFile.normalize()
        _folders.value = _folders.value.filterNot { it.absoluteFile.normalize() == f }
        prefs.edit { putStringList(KEY_FOLDERS, _folders.value.map { it.path }) }
        refresh()
    }

    // ---------- Songs ----------

    /** Songs exactly as tagged in the files; [library] is these with the user's corrections applied. */
    private val _raw = MutableStateFlow<List<Song>>(emptyList())
    val rawSongs: StateFlow<List<Song>> = _raw.asStateFlow()
    private val _rawBooks = MutableStateFlow<List<Song>>(emptyList())

    val library: StateFlow<Library> = combine(_raw, metadata.overrides, metadata.artVersions, savedStreams) { raw, o, _, saved ->
        fun key(song: Song) = SearchMatch.fold(song.title) + "|" + SearchMatch.fold(song.artist)
        val savedBySong = saved.groupBy(::key)
        val local = raw.map { metadata.apply(it, o[it.id]) }.map { original ->
            val names = savedBySong[key(original)].orEmpty().filter {
                SearchMatch.sameSong(original.title, original.artist, original.durationMs, it.title, it.artist, it.durationMs) &&
                    (original.album.isBlank() || original.album == "Unknown album" || AudioFallback.sameRelease(original.album, it.album))
            }.mapNotNull { it.artistNames }.distinct().singleOrNull()
            val song = if (original.artistNames == null && names != null) original.copy(artistNames = names) else original
            if (song.album.isNotBlank() && song.album != "Unknown album") song else {
                val matches = savedBySong[key(song)].orEmpty().filter { it.album.isNotBlank() && it.album != "Unknown album" && kotlin.math.abs(it.durationMs - song.durationMs) <= 5000 }
                if (matches.map { it.album }.distinct().size == 1) song.copy(album = matches.first().album, albumArtist = matches.first().albumArtist, artUrl = song.artUrl ?: matches.first().artUrl) else song
            }
        }
        val localBySong = local.groupBy(::key)
        val remote = saved.filter { stream -> localBySong[key(stream)].orEmpty().none { SearchMatch.sameSong(it.title, it.artist, it.durationMs, stream.title, stream.artist, stream.durationMs) && (stream.album.isBlank() || AudioFallback.sameRelease(it.album, stream.album)) } }
        Library.from(AlbumGrouping.merge(local + remote))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, Library())

    /** Audiobook files in the library folders (Audiobooks folders, .m4b, genre Audiobook), with corrections applied. */
    val localBooks: StateFlow<List<Song>> = combine(_rawBooks, metadata.overrides, metadata.artVersions) { raw, o, _ ->
        raw.map { metadata.apply(it, o[it.id]) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Podcast files in the library folders (Podcasts folders, genre Podcast), kept out of the music library. */
    private val _localPodcasts = MutableStateFlow<List<Song>>(emptyList())
    val localPodcasts: StateFlow<List<Song>> = _localPodcasts.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanned = MutableStateFlow(false)
    /** True once the first scan has finished (until then the library is still loading). */
    val scanned: StateFlow<Boolean> = _scanned.asStateFlow()

    /** Files read so far by the running scan (for a progress line on big first scans). */
    val scanProgress: StateFlow<Int> get() = scanner.progress

    /** Audio files the last scan couldn't read (corrupt/unsupported); they're skipped, not fatal. */
    private val _skippedFiles = MutableStateFlow(0)
    val skippedFiles: StateFlow<Int> = _skippedFiles.asStateFlow()

    private val _showRecommendations = MutableStateFlow(prefs.getBoolean("showRecommendations", true))
    val showRecommendations: StateFlow<Boolean> = _showRecommendations.asStateFlow()

    fun setShowRecommendations(show: Boolean) {
        prefs.edit { putBoolean("showRecommendations", show) }
        _showRecommendations.value = show
    }

    private val _hideShortTracks = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SHORT, true))
    val hideShortTracks: StateFlow<Boolean> = _hideShortTracks.asStateFlow()

    // ---------- Likes and stats ----------

    private val _liked = MutableStateFlow(loadLiked())
    /** Likes as stored, with when each was made (library sync). */
    val likedEntries: StateFlow<List<LikedEntity>> = _liked.asStateFlow()
    /** Liked songs, newest like first (like Android's `ORDER BY likedAt DESC`). */
    val likedIds: StateFlow<Set<Long>> = _liked.map(::likedOrder).stateIn(scope, SharingStarted.Eagerly, likedOrder(_liked.value))

    private fun likedOrder(list: List<LikedEntity>): Set<Long> = list.withIndex()
        .sortedWith(compareByDescending<IndexedValue<LikedEntity>> { it.value.likedAt }.thenByDescending { it.index })
        .mapTo(LinkedHashSet()) { it.value.songId }

    private val _stats = MutableStateFlow(loadStats())
    /** This computer's own play counts (what library sync reports for this device). */
    val ownStats: StateFlow<Map<Long, PlayStatEntity>> = _stats.asStateFlow()
    /** Your other devices' play counts for songs here (library sync), added to [stats]. */
    private val _remoteStats = MutableStateFlow<Map<Long, PlayStat>>(emptyMap())
    fun setRemoteStats(stats: Map<Long, PlayStat>) { _remoteStats.value = stats }

    /** Play counts shown everywhere (On repeat, Recently played…): this computer's plus your other devices'. */
    val stats: StateFlow<Map<Long, PlayStat>> = combine(_stats, _remoteStats, ::mergeStats)
        .stateIn(scope, SharingStarted.Eagerly, mergeStats(_stats.value, _remoteStats.value))

    private fun mergeStats(own: Map<Long, PlayStatEntity>, remote: Map<Long, PlayStat>): Map<Long, PlayStat> {
        val out = HashMap<Long, PlayStat>(own.size + remote.size)
        own.forEach { (id, it) -> out[id] = PlayStat(it.songId, it.playCount, it.lastPlayed, it.skipCount) }
        remote.forEach { (id, r) ->
            val s = out[id]
            out[id] = if (s == null) r.copy(songId = id) else PlayStat(id, s.playCount + r.playCount, maxOf(s.lastPlayed, r.lastPlayed), s.skipCount + r.skipCount)
        }
        return out
    }

    // ---------- Playlists ----------

    data class PlaylistDb(val playlists: List<PlaylistEntity>, val entries: List<PlaylistEntryEntity>, val nextId: Long, val nextEntryId: Long)
    private val _playlistDb = MutableStateFlow(loadPlaylists())
    /** Playlists and their entries as stored, including songs that aren't on this computer now (library sync). */
    val playlistDb: StateFlow<PlaylistDb> = _playlistDb.asStateFlow()

    private val playlistArtRevision = MutableStateFlow(0)
    /** Bumped whenever a playlist cover is set or cleared. */
    val playlistCovers: StateFlow<Int> = playlistArtRevision.asStateFlow()
    fun playlistCover(id: Long) = File(coverDir.apply { mkdirs() }, "$id.jpg")

    /** Sets (an image file) or clears (null) a playlist's custom cover. */
    suspend fun setPlaylistCover(id: Long, image: File?): Boolean = withContext(Dispatchers.IO) {
        val file = playlistCover(id)
        val saved = if (image == null) !file.exists() || file.delete() else saveSquareImage(image, file, 1000)
        if (saved) playlistArtRevision.value++
        saved
    }

    val playlists: StateFlow<List<Playlist>> = combine(_playlistDb, library, playlistArtRevision) { db, lib, _ ->
        val byPlaylist = db.entries.sortedWith(compareBy({ it.playlistId }, { it.position })).groupBy { it.playlistId }
        db.playlists.sortedByDescending { it.updatedAt }.map { p ->
            val present = byPlaylist[p.id].orEmpty().mapNotNull { e -> (lib.songById[e.songId] ?: streamLookup(e.songId))?.let { e.entryId to it } }
            val cover = playlistCover(p.id)
            Playlist(
                id = p.id,
                name = p.name,
                songs = present.map { it.second },
                entryIds = present.map { it.first },
                updatedAt = p.updatedAt,
                artwork = cover.takeIf { it.isFile }?.toURI()?.toString(),
                artVersion = cover.lastModified(),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    val smart: StateFlow<Map<SmartCollection.Kind, SmartCollection>> =
        combine(library, likedIds, stats) { lib, liked, stats -> SmartCollection.build(lib, liked, stats) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // ---------- Mixes ----------

    /** Generated playlists, published by the taste engine. */
    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())
    /** Generated playlists the user deleted; they stay gone even though the taste engine keeps making them. */
    private val _hiddenMixes = MutableStateFlow(prefs.getStringSet("hiddenMixes", emptySet()).toSet())
    /** Keys of the generated playlists the user deleted. */
    val hiddenMixKeys: StateFlow<Set<String>> = _hiddenMixes.asStateFlow()
    val hiddenMixCount: StateFlow<Int> = _hiddenMixes.map { it.size }.stateIn(scope, SharingStarted.Eagerly, _hiddenMixes.value.size)
    val mixes: StateFlow<List<Mix>> = combine(_mixes, _hiddenMixes) { list, hidden -> list.filter { it.key !in hidden } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    fun publishMixes(list: List<Mix>) { _mixes.value = list }
    fun deleteMix(key: String) { _hiddenMixes.value = _hiddenMixes.value + key; prefs.edit { putStringSet("hiddenMixes", _hiddenMixes.value) } }
    /** Deletes or brings back one generated playlist (library sync). */
    fun setMixHidden(key: String, hidden: Boolean) {
        _hiddenMixes.value = if (hidden) _hiddenMixes.value + key else _hiddenMixes.value - key
        prefs.edit { putStringSet("hiddenMixes", _hiddenMixes.value) }
    }
    fun restoreDeletedMixes() { _hiddenMixes.value = emptySet(); prefs.edit { remove("hiddenMixes") } }

    // ---------- Scanning ----------

    private var started = false
    private var pendingRescan: Job? = null
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)
    private var scanLoop: Job? = null
    private var watcher: FolderWatcher? = null

    init {
        metadata.librarySongs = { library.value.songs + localBooks.value }
        metadata.onFilesChanged = { refresh() }
    }

    /** Idempotent start (the window, the player and the tray may all ask). */
    @Synchronized fun ensureStarted() { if (!started) start() }

    @Synchronized fun start() {
        started = true
        if (watchFolders && watcher == null) watcher = runCatching { FolderWatcher() }.getOrNull()
        refresh()
    }

    /** Stops the folder watcher (app shutdown / tests). Pending data is flushed. */
    fun stop() {
        watcher?.close(); watcher = null
        flush()
    }

    /** Rescans the library folders. Calls during a scan are coalesced into one more scan. */
    fun refresh() {
        synchronized(this) {
            if (scanLoop == null) scanLoop = scope.launch(Dispatchers.IO) {
                for (request in scanRequests) scanOnce()
            }
        }
        scanRequests.trySend(Unit)
    }

    /** Waits for the current/queued scans to finish (tests, FLAC conversion). */
    suspend fun awaitScan() {
        delay(50)
        while (_scanning.value) delay(50)
    }

    private suspend fun scanOnce() {
        _scanning.value = true
        try {
            val minMs = if (_hideShortTracks.value) 30_000L else 1L
            val result = scanner.scanAll(_folders.value, minMs)
            _raw.value = result.music
            _localPodcasts.value = result.podcasts
            _rawBooks.value = result.audiobooks
            _skippedFiles.value = result.skipped
            watcher?.sync(result.directories)
            runCatching { onScanned?.invoke(_raw.value, _rawBooks.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // One unreadable file or odd folder must not crash the app on every launch.
            System.err.println("Spitify: scanning your library failed: $e")
        } finally {
            _scanning.value = false
            _scanned.value = true
        }
    }

    /** Hook for post-scan work (metadata auto-fix). */
    var onScanned: ((List<Song>, List<Song>) -> Unit)? = null

    fun setHideShortTracks(hide: Boolean) {
        _hideShortTracks.value = hide
        prefs.edit { putBoolean(KEY_HIDE_SHORT, hide) }
        refresh()
    }

    // ---------- Likes / stats / playlists (the Room DAOs on Android) ----------

    fun toggleLike(songId: Long) = scope.launch {
        dbLock.withLock {
            _liked.update { list -> if (list.any { it.songId == songId }) list.filterNot { it.songId == songId } else list + LikedEntity(songId, System.currentTimeMillis()) }
            saveLiked()
        }
    }

    /** Likes or unlikes [songId] (not a toggle), keeping [likedAt] for a new like. */
    suspend fun setLiked(songId: Long, liked: Boolean, likedAt: Long = System.currentTimeMillis()) = dbLock.withLock {
        if (_liked.value.any { it.songId == songId } == liked) return@withLock
        _liked.update { list -> if (liked) list + LikedEntity(songId, likedAt) else list.filterNot { it.songId == songId } }
        saveLiked()
    }

    fun recordPlay(songId: Long) = scope.launch {
        dbLock.withLock {
            val now = System.currentTimeMillis()
            _stats.update { m ->
                val s = m[songId]
                m + (songId to (s?.copy(playCount = s.playCount + 1, lastPlayed = now) ?: PlayStatEntity(songId, 1, now, 0)))
            }
            saveStats()
        }
    }

    fun recordSkip(songId: Long) = scope.launch {
        dbLock.withLock {
            _stats.update { m ->
                val s = m[songId]
                m + (songId to (s?.copy(skipCount = s.skipCount + 1) ?: PlayStatEntity(songId, 0, 0, 1)))
            }
            saveStats()
        }
    }

    suspend fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Long = dbLock.withLock {
        val now = System.currentTimeMillis()
        var id = 0L
        _playlistDb.update { db ->
            id = db.nextId
            val withPlaylist = db.copy(playlists = db.playlists + PlaylistEntity(id, name.trim().ifEmpty { "My playlist" }, now, now), nextId = db.nextId + 1)
            appendTo(withPlaylist, id, songIds, now)
        }
        savePlaylists()
        id
    }

    suspend fun appendToPlaylist(playlistId: Long, songIds: List<Long>) = dbLock.withLock {
        _playlistDb.update { appendTo(it, playlistId, songIds, System.currentTimeMillis()) }
        savePlaylists()
    }

    fun addToPlaylist(playlistId: Long, songIds: List<Long>) = scope.launch { appendToPlaylist(playlistId, songIds) }

    /** Removes by entry id: list positions don't match the stored entries when some songs are missing. */
    fun removeFromPlaylist(playlistId: Long, entryId: Long) = scope.launch {
        dbLock.withLock {
            _playlistDb.update { db -> touch(db.copy(entries = db.entries.filterNot { it.entryId == entryId }), playlistId, System.currentTimeMillis()) }
            savePlaylists()
        }
    }

    fun reorderPlaylist(playlistId: Long, songIds: List<Long>) = scope.launch {
        dbLock.withLock {
            _playlistDb.update { db ->
                var next = db.nextEntryId
                val fresh = songIds.mapIndexed { i, s -> PlaylistEntryEntity(next++, playlistId, s, i) }
                touch(db.copy(entries = db.entries.filterNot { it.playlistId == playlistId } + fresh, nextEntryId = next), playlistId, System.currentTimeMillis())
            }
            savePlaylists()
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) = scope.launch {
        dbLock.withLock {
            val now = System.currentTimeMillis()
            _playlistDb.update { db -> db.copy(playlists = db.playlists.map { if (it.id == playlistId) it.copy(name = name.trim(), updatedAt = now) else it }) }
            savePlaylists()
        }
    }

    fun deletePlaylist(playlistId: Long) = scope.launch {
        dbLock.withLock {
            _playlistDb.update { db -> db.copy(playlists = db.playlists.filterNot { it.id == playlistId }, entries = db.entries.filterNot { it.playlistId == playlistId }) }
            savePlaylists()
        }
        withContext(Dispatchers.IO) { playlistCover(playlistId).delete() }
    }

    private fun appendTo(db: PlaylistDb, id: Long, songIds: List<Long>, now: Long): PlaylistDb {
        if (songIds.isEmpty()) return touch(db, id, now)
        val start = (db.entries.filter { it.playlistId == id }.maxOfOrNull { it.position } ?: -1) + 1
        var next = db.nextEntryId
        val added = songIds.mapIndexed { i, s -> PlaylistEntryEntity(next++, id, s, start + i) }
        return touch(db.copy(entries = db.entries + added, nextEntryId = next), id, now)
    }

    private fun touch(db: PlaylistDb, id: Long, now: Long) =
        db.copy(playlists = db.playlists.map { if (it.id == id) it.copy(updatedAt = now) else it })

    /**
     * Follows songs to their new ids after a file was replaced (e.g. converted to AAC): likes,
     * play stats and playlist entries move over; an existing like/stat on the new id wins.
     */
    suspend fun remapSongs(ids: Map<Long, Long>) = dbLock.withLock {
        if (ids.isEmpty()) return@withLock
        _liked.update { list ->
            val present = list.mapTo(HashSet()) { it.songId }
            list.mapNotNull { e -> val n = ids[e.songId]; if (n == null) e else if (n in present) null else e.copy(songId = n) }
        }
        _stats.update { m ->
            val out = HashMap(m)
            for ((old, new) in ids) { val s = out.remove(old) ?: continue; if (new !in out) out[new] = s.copy(songId = new) }
            out
        }
        _playlistDb.update { db -> db.copy(entries = db.entries.map { e -> ids[e.songId]?.let { e.copy(songId = it) } ?: e }) }
        saveLiked(); saveStats(); savePlaylists()
    }

    /** Writes pending changes to disk now. */
    fun flush() { likedStore.flush(); statsStore.flush(); playlistStore.flush(); prefs.flush() }

    // ---------- persistence ----------

    private fun loadLiked(): List<LikedEntity> {
        val arr = likedStore.readArray() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o -> runCatching { LikedEntity(o.getLong("songId"), o.optLong("likedAt")) }.getOrNull() } }
            .distinctBy { it.songId }
    }

    private fun saveLiked() = likedStore.save {
        JSONArray().apply { _liked.value.forEach { put(JSONObject().put("songId", it.songId).put("likedAt", it.likedAt)) } }.toString()
    }

    private fun loadStats(): Map<Long, PlayStatEntity> {
        val arr = statsStore.readArray() ?: return emptyMap()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { PlayStatEntity(o.getLong("songId"), o.optInt("playCount"), o.optLong("lastPlayed"), o.optInt("skipCount")) }.getOrNull() }
        }.associateBy { it.songId }
    }

    private fun saveStats() = statsStore.save {
        JSONArray().apply {
            _stats.value.values.forEach { put(JSONObject().put("songId", it.songId).put("playCount", it.playCount).put("lastPlayed", it.lastPlayed).put("skipCount", it.skipCount)) }
        }.toString()
    }

    private fun loadPlaylists(): PlaylistDb {
        val o = playlistStore.readObject() ?: return PlaylistDb(emptyList(), emptyList(), 1, 1)
        val playlists = o.optJSONArray("playlists")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { p -> runCatching { PlaylistEntity(p.getLong("id"), p.optString("name"), p.optLong("createdAt"), p.optLong("updatedAt")) }.getOrNull() } }
        }.orEmpty()
        val entries = o.optJSONArray("entries")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { e -> runCatching { PlaylistEntryEntity(e.getLong("entryId"), e.getLong("playlistId"), e.getLong("songId"), e.optInt("position")) }.getOrNull() } }
        }.orEmpty()
        val nextId = maxOf(o.optLong("nextId", 1), (playlists.maxOfOrNull { it.id } ?: 0) + 1)
        val nextEntryId = maxOf(o.optLong("nextEntryId", 1), (entries.maxOfOrNull { it.entryId } ?: 0) + 1)
        return PlaylistDb(playlists, entries, nextId, nextEntryId)
    }

    private fun savePlaylists() = playlistStore.save {
        val db = _playlistDb.value
        JSONObject().apply {
            put("nextId", db.nextId); put("nextEntryId", db.nextEntryId)
            put("playlists", JSONArray().apply { db.playlists.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)) } })
            put("entries", JSONArray().apply { db.entries.forEach { put(JSONObject().put("entryId", it.entryId).put("playlistId", it.playlistId).put("songId", it.songId).put("position", it.position)) } })
        }.toString()
    }

    // ---------- folder watching ----------

    /** Watches every library directory; changes trigger a debounced rescan (files settle while copying). */
    private inner class FolderWatcher {
        private val service: WatchService = FileSystems.getDefault().newWatchService()
        private val keys = ConcurrentHashMap<Path, WatchKey>()
        private val thread = Thread(::loop, "spitify-folder-watch").apply { isDaemon = true; start() }

        fun sync(dirs: List<File>) {
            val wanted = dirs.asSequence().take(MAX_WATCHED_DIRS).map { it.toPath().toAbsolutePath().normalize() }.toHashSet()
            keys.keys.filter { it !in wanted }.forEach { keys.remove(it)?.cancel() }
            for (dir in wanted) if (!keys.containsKey(dir)) register(dir)
        }

        private fun register(dir: Path) {
            runCatching {
                keys[dir] = dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY)
            }
        }

        private fun loop() {
            try {
                while (true) {
                    val key = service.take()
                    var relevant = false
                    for (event in key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) { relevant = true; continue }
                        val name = (event.context() as? Path)?.fileName?.toString() ?: continue
                        if (name.startsWith(".")) continue // our staged tag writes, OS metadata files
                        val dir = key.watchable() as? Path
                        val isDir = dir?.resolve(name)?.toFile()?.isDirectory == true
                        if (isDir && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) dir?.resolve(name)?.let(::register)
                        if (isDir || MediaScanner.isAudio(name) || event.kind() == StandardWatchEventKinds.ENTRY_DELETE) relevant = true
                    }
                    if (!key.reset()) keys.entries.removeIf { it.value == key }
                    if (relevant) scheduleRescan()
                }
            } catch (_: ClosedWatchServiceException) {
            } catch (_: InterruptedException) {
            }
        }

        fun close() { runCatching { service.close() }; thread.interrupt() }
    }

    private fun scheduleRescan() {
        synchronized(this) {
            pendingRescan?.cancel()
            pendingRescan = scope.launch { delay(1500); refresh() }
        }
    }

    companion object {
        private const val KEY_HIDE_SHORT = "hide_short"
        private const val KEY_FOLDERS = "folders"
        private const val MAX_WATCHED_DIRS = 20_000
    }
}

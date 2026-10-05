package com.localfy.app.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.edit
import com.localfy.app.data.db.LikedEntity
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.db.PlaylistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
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
}

/** A generated playlist (Daily Mix, Discover Weekly, daylist, radio...). */
data class Mix(
    val key: String,
    val title: String,
    val description: String,
    val songs: List<Song>,
    /** Which Home shelf it belongs to. */
    val section: MixSection = MixSection.MadeForYou,
    val style: CoverStyle = CoverStyle.Collage,
    /** ARGB colour for the generated cover. */
    val accent: Long = 0xFF1ED760,
    /** "Because you listen to ..." - shown on the playlist page. */
    val why: String? = null,
    val refresh: String? = null,
) {
    val cover: Song get() = songs.first()
}

enum class MixSection(val title: String) {
    MadeForYou("Made for you"), Discover("Discover"), YourMixes("Your mixes"), Throwbacks("Throwbacks & favourites")
}

enum class CoverStyle { Collage, Bold }

class LibraryRepository(
    private val context: Context,
    private val db: LocalfyDatabase,
    private val scope: CoroutineScope,
    private val metadata: com.localfy.app.data.meta.MetadataRepository,
) {
    private val scanner = MediaScanner(context)
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** Songs exactly as tagged in the files; [library] is these with the user's corrections applied. */
    private val _raw = MutableStateFlow<List<Song>>(emptyList())
    val rawSongs: StateFlow<List<Song>> = _raw.asStateFlow()
    private val _rawBooks = MutableStateFlow<List<Song>>(emptyList())

    val library: StateFlow<Library> = combine(_raw, metadata.overrides, metadata.artVersions, (context.applicationContext as com.localfy.app.LocalfyApp).musicStreams.saved) { raw, o, _, saved ->
        fun key(song: Song) = com.localfy.app.data.music.SearchMatch.fold(song.title) + "|" + com.localfy.app.data.music.SearchMatch.fold(song.artist)
        val savedBySong = saved.groupBy(::key)
        val local = raw.map { metadata.apply(it, o[it.id]) }.map { original ->
            val names = savedBySong[key(original)].orEmpty().filter {
                com.localfy.app.data.music.SearchMatch.sameSong(original.title, original.artist, original.durationMs, it.title, it.artist, it.durationMs) &&
                    (original.album.isBlank() || original.album == "Unknown album" || com.localfy.app.data.music.AudioFallback.sameRelease(original.album, it.album))
            }.mapNotNull { it.artistNames }.distinct().singleOrNull()
            val song = if (original.artistNames == null && names != null) original.copy(artistNames = names) else original
            if (song.album.isNotBlank() && song.album != "Unknown album") song else {
                val matches = savedBySong[key(song)].orEmpty().filter { it.album.isNotBlank() && it.album != "Unknown album" && kotlin.math.abs(it.durationMs - song.durationMs) <= 5000 }
                if (matches.map { it.album }.distinct().size == 1) song.copy(album = matches.first().album, albumArtist = matches.first().albumArtist, artUrl = song.artUrl ?: matches.first().artUrl) else song
            }
        }
        val localBySong = local.groupBy(::key)
        val remote = saved.filter { stream -> localBySong[key(stream)].orEmpty().none { com.localfy.app.data.music.SearchMatch.sameSong(it.title, it.artist, it.durationMs, stream.title, stream.artist, stream.durationMs) && (stream.album.isBlank() || com.localfy.app.data.music.AudioFallback.sameRelease(it.album, stream.album)) } }
        Library.from(mergeAlbums(local + remote))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, Library())

    /** A corrected song whose album already exists on the device joins that album instead of duplicating it. */
    private fun mergeAlbums(songs: List<Song>): List<Song> = AlbumGrouping.merge(songs)

    /** Audiobook files on the device (Audiobooks folders, .m4b, IS_AUDIOBOOK), with corrections applied. */
    val localBooks: StateFlow<List<Song>> = combine(_rawBooks, metadata.overrides, metadata.artVersions) { raw, o, _ ->
        raw.map { metadata.apply(it, o[it.id]) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Podcast files already on the device (MediaStore IS_PODCAST), kept out of the music library. */
    private val _localPodcasts = MutableStateFlow<List<Song>>(emptyList())
    val localPodcasts: StateFlow<List<Song>> = _localPodcasts.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _showRecommendations = MutableStateFlow(prefs.getBoolean("showRecommendations", true))
    val showRecommendations: StateFlow<Boolean> = _showRecommendations.asStateFlow()

    fun setShowRecommendations(show: Boolean) {
        prefs.edit { putBoolean("showRecommendations", show) }
        _showRecommendations.value = show
    }

    private val _hideShortTracks = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SHORT, true))
    val hideShortTracks: StateFlow<Boolean> = _hideShortTracks.asStateFlow()

    val likedIds: StateFlow<Set<Long>> = db.liked().observe()
        .map { list -> list.mapTo(LinkedHashSet()) { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val stats: StateFlow<Map<Long, PlayStat>> = db.stats().observe()
        .map { list -> list.associate { it.songId to PlayStat(it.songId, it.playCount, it.lastPlayed, it.skipCount) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val playlistArtRevision = MutableStateFlow(0)
    private fun playlistCover(id: Long) = java.io.File(java.io.File(context.filesDir, "playlist_covers").apply { mkdirs() }, "$id.jpg")
    suspend fun setPlaylistCover(id: Long, uri: android.net.Uri?): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val file = playlistCover(id)
        val saved = if (uri == null) !file.exists() || file.delete() else
            saveSquareImage(android.graphics.ImageDecoder.createSource(context.contentResolver, uri), file, 1000)
        if (saved) playlistArtRevision.value++
        saved
    }

    val playlists: StateFlow<List<Playlist>> = combine(
        db.playlists().observePlaylists(),
        db.playlists().observeEntries(),
        library, playlistArtRevision,
    ) { playlists, entries, lib, _ ->
        val byPlaylist = entries.groupBy { it.playlistId }
        playlists.map { p ->
            val present = byPlaylist[p.id].orEmpty().mapNotNull { e -> (lib.songById[e.songId] ?: (context.applicationContext as com.localfy.app.LocalfyApp).musicStreams.lookup(e.songId))?.let { e.entryId to it } }
            Playlist(
                id = p.id,
                name = p.name,
                songs = present.map { it.second },
                entryIds = present.map { it.first },
                updatedAt = p.updatedAt,
                artwork = playlistCover(p.id).takeIf { it.isFile }?.let { android.net.Uri.fromFile(it).buildUpon().appendQueryParameter("v", it.lastModified().toString()).build().toString() },
                artVersion = playlistCover(p.id).lastModified(),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val smart: StateFlow<Map<SmartCollection.Kind, SmartCollection>> =
        combine(library, likedIds, stats) { lib, liked, stats -> buildSmart(lib, liked, stats) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Generated playlists, published by the taste engine. */
    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())
    /** Generated playlists the user deleted; they stay gone even though the taste engine keeps making them. */
    private val _hiddenMixes = MutableStateFlow(prefs.getStringSet("hiddenMixes", emptySet())!!.toSet())
    val hiddenMixCount: StateFlow<Int> = _hiddenMixes.map { it.size }.stateIn(scope, SharingStarted.Eagerly, _hiddenMixes.value.size)
    val mixes: StateFlow<List<Mix>> = combine(_mixes, _hiddenMixes) { list, hidden -> list.filter { it.key !in hidden } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    fun publishMixes(list: List<Mix>) { _mixes.value = list }
    fun deleteMix(key: String) { _hiddenMixes.value = _hiddenMixes.value + key; prefs.edit().putStringSet("hiddenMixes", _hiddenMixes.value).apply() }
    fun restoreDeletedMixes() { _hiddenMixes.value = emptySet(); prefs.edit().remove("hiddenMixes").apply() }

    private var observerRegistered = false
    private var pendingRescan: Job? = null

    private var started = false

    /** Idempotent start (the activity, the service and Android Auto may all ask). */
    fun ensureStarted() { if (!started) start() }

    fun start() {
        started = true
        refresh()
        if (!observerRegistered) {
            observerRegistered = true
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        // MediaStore fires bursts of changes while copying files; settle first.
                        pendingRescan?.cancel()
                        pendingRescan = scope.launch { delay(1500); refresh() }
                    }
                },
            )
        }
    }

    fun refresh() {
        scope.launch {
            _scanning.value = true
            try {
                val minMs = if (_hideShortTracks.value) 30_000L else 1L
                _raw.value = scanner.scan(minMs)
                _localPodcasts.value = scanner.scan(1L, podcasts = true)
                _rawBooks.value = scanner.scan(1L, kind = MediaScanner.Kind.Audiobooks)
                onScanned?.invoke(_raw.value, _rawBooks.value)
            } catch (_: SecurityException) {
                // Permission not granted yet; the UI will prompt and call start() again.
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // One unreadable file or odd MediaStore row must not crash the app on every launch.
                com.localfy.app.CrashReport.recordNonFatal(context, "Scanning your library", e)
            } finally {
                _scanning.value = false
            }
        }
    }

    /** Hook for post-scan work (metadata auto-fix). */
    var onScanned: ((List<Song>, List<Song>) -> Unit)? = null

    fun setHideShortTracks(hide: Boolean) {
        _hideShortTracks.value = hide
        prefs.edit { putBoolean(KEY_HIDE_SHORT, hide) }
        refresh()
    }

    fun toggleLike(songId: Long) = scope.launch {
        if (songId in likedIds.value) db.liked().unlike(songId)
        else db.liked().like(LikedEntity(songId, System.currentTimeMillis()))
    }

    fun recordPlay(songId: Long) = scope.launch { db.stats().recordPlay(songId, System.currentTimeMillis()) }
    fun recordSkip(songId: Long) = scope.launch { db.stats().recordSkip(songId) }

    suspend fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Long {
        val now = System.currentTimeMillis()
        val id = db.playlists().insert(PlaylistEntity(name = name.trim().ifEmpty { "My playlist" }, createdAt = now, updatedAt = now))
        if (songIds.isNotEmpty()) db.playlists().append(id, songIds, now)
        return id
    }

    suspend fun appendToPlaylist(playlistId: Long, songIds: List<Long>) {
        db.playlists().append(playlistId, songIds, System.currentTimeMillis())
    }

    fun addToPlaylist(playlistId: Long, songIds: List<Long>) = scope.launch {
        db.playlists().append(playlistId, songIds, System.currentTimeMillis())
    }

    /** Removes by entry id: list positions don't match the database when some songs are missing. */
    fun removeFromPlaylist(playlistId: Long, entryId: Long) = scope.launch {
        db.playlists().removeEntry(playlistId, entryId, System.currentTimeMillis())
    }

    fun reorderPlaylist(playlistId: Long, songIds: List<Long>) = scope.launch {
        db.playlists().reorder(playlistId, songIds, System.currentTimeMillis())
    }

    fun renamePlaylist(playlistId: Long, name: String) = scope.launch {
        db.playlists().rename(playlistId, name.trim(), System.currentTimeMillis())
    }

    fun deletePlaylist(playlistId: Long) = scope.launch { db.playlists().delete(playlistId); playlistCover(playlistId).delete() }

    private fun buildSmart(lib: Library, liked: Set<Long>, stats: Map<Long, PlayStat>): Map<SmartCollection.Kind, SmartCollection> {
        val now = System.currentTimeMillis()
        val month = 30L * 24 * 60 * 60 * 1000
        val played = lib.songs.filter { (stats[it.id]?.playCount ?: 0) > 0 }
        val kinds = mapOf(
            SmartCollection.Kind.AllSongs to lib.songs,
            SmartCollection.Kind.RecentlyAdded to lib.songs.sortedByDescending { it.dateAddedSec }.take(100),
            SmartCollection.Kind.MostPlayed to played.sortedByDescending { stats[it.id]!!.playCount }.take(50),
            SmartCollection.Kind.RecentlyPlayed to played.sortedByDescending { stats[it.id]!!.lastPlayed }.take(50),
            SmartCollection.Kind.Forgotten to played.filter {
                val s = stats[it.id]!!
                s.playCount >= 3 && now - s.lastPlayed > month
            }.sortedByDescending { stats[it.id]!!.playCount }.take(50),
            SmartCollection.Kind.NeverPlayed to lib.songs.filter { it.id !in stats || stats[it.id]!!.playCount == 0 }
                .shuffled(Random(daySeed())).take(100),
        )
        return kinds.mapValues { (k, v) -> SmartCollection(k, v) }
    }

    private fun daySeed(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    companion object {
        private const val KEY_HIDE_SHORT = "hide_short"
    }
}

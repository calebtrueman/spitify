package com.localfy.app.data.meta

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.localfy.app.data.Song
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.db.MetadataOverrideEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs

/** A possible match from an online catalogue. */
data class MetadataCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val year: Int?,
    val genre: String?,
    val track: Int?,
    val disc: Int?,
    val durationMs: Long,
    val artUrl: String?,
    val source: String,
)

/** Fields a user (or the auto-fixer) can set; null = keep the file's value. */
data class MetadataEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val disc: Int? = null,
)

/**
 * Keeps library edits in sync with file tags. Manual saves report write failures;
 * online fixes also try to update files when Android already allows it.
 */
class MetadataRepository(
    private val context: Context,
    private val db: LocalfyDatabase,
    private val scope: CoroutineScope,
) {
    private val dao = db.metadata()
    private val prefs = context.getSharedPreferences("metadata", Context.MODE_PRIVATE)
    private val artDir = File(context.filesDir, "custom_art").apply { mkdirs() }

    val overrides: StateFlow<Map<Long, MetadataOverrideEntity>> = dao.observe()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Album key -> version; bumping it busts image caches after the art changes. */
    private val _artVersions = MutableStateFlow(loadArtVersions())
    val artVersions: StateFlow<Map<Long, Long>> = _artVersions.asStateFlow()

    private val _autoFix = MutableStateFlow(prefs.getBoolean("autoFix", true))
    val autoFix: StateFlow<Boolean> = _autoFix.asStateFlow()

    private val _fixing = MutableStateFlow(false)
    val fixing: StateFlow<Boolean> = _fixing.asStateFlow()
    private var fixJob: Job? = null
    private data class PendingWrite(val song: Song, val edit: MetadataEdit, val cover: File?)
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, PendingWrite>()
    private val _pendingWrites = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val pendingWrites: StateFlow<List<android.net.Uri>> = _pendingWrites.asStateFlow()
    private var latestSongs: List<Song> = emptyList()


    /** Songs whose automatic tag write the user declined; never ask again for them (until auto-fix is turned back on). */
    private val declinedWrites: MutableSet<Long> = java.util.Collections.synchronizedSet(
        prefs.getStringSet("declinedWrites", emptySet())!!.mapNotNull { it.toLongOrNull() }.toMutableSet(),
    )

    fun setAutoFix(on: Boolean) {
        if (on && !_autoFix.value) { declinedWrites.clear(); prefs.edit { remove("declinedWrites") } }
        _autoFix.value = on
        prefs.edit { putBoolean("autoFix", on) }
        if (on) autoFixAll(latestSongs)
    }

    // ---------- Applying overrides ----------

    fun apply(song: Song, o: MetadataOverrideEntity?): Song {
        if (o == null || o.fileName != song.fileName) return song.withArtVersion()
        val album = o.album ?: song.album
        val albumArtist = o.albumArtist ?: o.artist?.takeIf { o.album != null } ?: song.albumArtist
        val regrouped = o.album != null || o.albumArtist != null
        return song.copy(
            title = o.title ?: song.title,
            artist = o.artist ?: song.artist,
            album = album,
            albumArtist = albumArtist,
            // Renamed albums regroup by name; untouched ones keep MediaStore's album id.
            albumId = if (regrouped) syntheticAlbumId(album, albumArtist) else song.albumId,
            genre = o.genre ?: song.genre,
            year = o.year ?: song.year,
            track = o.track ?: song.track,
            disc = o.disc ?: song.disc,
        ).withArtVersion()
    }

    private fun Song.withArtVersion() = _artVersions.value[albumId]?.let { copy(artVersion = it) } ?: this

    fun syntheticAlbumId(album: String, albumArtist: String): Long =
        (norm(album) + "\u0000" + norm(albumArtist)).hashCode().toLong().let { if (it > 0) -it else it } - 10_000_000_000L

    // ---------- Edits ----------

    fun save(songs: List<Song>, edit: MetadataEdit, source: String = SOURCE_USER) = scope.launch(Dispatchers.IO) {
        songs.forEach { song ->
            val existing = dao.get(song.id)?.takeIf { it.fileName == song.fileName }
            if (source == SOURCE_ONLINE && existing?.source == SOURCE_USER) return@forEach
            if (source == SOURCE_ONLINE) runCatching { FileTags.write(context, song, edit, onlyMissing = true) }
            dao.put(
                MetadataOverrideEntity(
                    songId = song.id, fileName = song.fileName,
                    title = edit.title ?: existing?.title, artist = edit.artist ?: existing?.artist,
                    album = edit.album ?: existing?.album, albumArtist = edit.albumArtist ?: existing?.albumArtist,
                    genre = edit.genre ?: existing?.genre, year = edit.year ?: existing?.year,
                    track = edit.track ?: existing?.track, disc = edit.disc ?: existing?.disc,
                    source = source, updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun prepareArtwork(source: String): String = withContext(Dispatchers.IO) {
        val image = if (source.startsWith("http")) {
            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(com.localfy.app.data.art.CoverDownload.load(source)))
        } else android.graphics.ImageDecoder.createSource(context.contentResolver, Uri.parse(source))
        val dir = File(context.cacheDir, "selected-covers").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val file = File.createTempFile("cover-", ".jpg", dir)
        try {
            check(com.localfy.app.data.saveSquareImage(image, file, 1200)) { "The cover image could not be read." }
            Uri.fromFile(file).toString()
        } catch (error: Exception) { file.delete(); throw error }
    }

    suspend fun saveFiles(songs: List<Song>, edit: MetadataEdit, artSource: String?) = withContext(Dispatchers.IO) {
        // Ask for permission before changing any file in a batch.
        for (song in songs) checkNotNull(context.contentResolver.openFileDescriptor(song.uri, "rw")).close()
        val image = if (artSource != null) {
            val source = if (artSource.startsWith("http")) {
                val bytes = com.localfy.app.data.art.CoverDownload.load(artSource)
                check(bytes.size <= 20_000_000) { "The cover image is too large." }
                android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            } else android.graphics.ImageDecoder.createSource(context.contentResolver, Uri.parse(artSource))
            val temp = File.createTempFile("cover-", ".jpg", context.cacheDir)
            try {
                check(com.localfy.app.data.saveSquareImage(source, temp, 1200)) { "The cover image could not be read." }
                temp.readBytes()
            } finally { temp.delete() }
        } else {
            val app = context.applicationContext as com.localfy.app.LocalfyApp
            val cached = customArt(songs.first().albumId) ?: app.onlineArt.cached(songs.first().albumId)
            cached?.let {
                val temp = File.createTempFile("cover-", ".jpg", context.cacheDir)
                try {
                    check(com.localfy.app.data.saveSquareImage(android.graphics.ImageDecoder.createSource(it), temp, 1200)) { "The cover image could not be read." }
                    temp.readBytes()
                } finally { temp.delete() }
            }
        }
        for (song in songs) {
            FileTags.write(context, song, edit, image)
            save(listOf(song), edit).join()
        }
        if (image != null) {
            val first = songs.first()
            val target = if (edit.album != null || edit.albumArtist != null) syntheticAlbumId(edit.album ?: first.album, edit.albumArtist ?: edit.artist ?: first.albumArtist) else first.albumId
            File(artDir, "$target.jpg").writeBytes(image)
            bumpArt(target)
        }
        withContext(Dispatchers.Main) { (context.applicationContext as com.localfy.app.LocalfyApp).library.refresh() }
    }

    fun reset(songs: List<Song>) = scope.launch(Dispatchers.IO) { songs.forEach { dao.delete(it.id) } }

    // ---------- Artwork ----------

    fun customArt(albumId: Long): File? = File(artDir, "$albumId.jpg").takeIf { it.isFile && it.length() > 0 }

    /** Sets album art from a picked image (content Uri) or a downloaded candidate (http URL). */
    fun setArt(albumId: Long, source: String) = scope.launch(Dispatchers.IO) {
        val image = if (source.startsWith("http")) {
            val bytes = runCatching { com.localfy.app.data.art.CoverDownload.load(source) }.getOrNull() ?: return@launch
            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        } else android.graphics.ImageDecoder.createSource(context.contentResolver, Uri.parse(source))
        if (!com.localfy.app.data.saveSquareImage(image, File(artDir, "$albumId.jpg"), 1200)) return@launch
        bumpArt(albumId)
        embedArt(albumId, File(artDir, "$albumId.jpg"))
    }

    suspend fun embedArt(albumId: Long, image: File) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as com.localfy.app.LocalfyApp
        val songs = app.library.library.value.songs + app.library.localBooks.value
        songs.filter { it.albumId == albumId }.forEach { song ->
            runCatching {
                if (FileTags.read(context, song).artwork == null) writeAutomatic(song, MetadataEdit(), image)
            }
        }
    }

    fun removeArt(albumId: Long) = scope.launch(Dispatchers.IO) {
        File(artDir, "$albumId.jpg").delete()
        bumpArt(albumId)
    }

    private fun bumpArt(albumId: Long) {
        _artVersions.value = _artVersions.value + (albumId to System.currentTimeMillis())
        prefs.edit { putString("artVersions", _artVersions.value.entries.joinToString(";") { "${it.key}=${it.value}" }) }
    }

    private fun loadArtVersions(): Map<Long, Long> = prefs.getString("artVersions", null)?.split(';')
        ?.mapNotNull { e -> e.split('=').takeIf { it.size == 2 }?.let { (k, v) -> k.toLongOrNull()?.let { kk -> v.toLongOrNull()?.let { kk to it } } } }
        ?.toMap().orEmpty()

    // ---------- Online lookup ----------

    /** Does this file look untagged? (no artist, folder-name album, or a filename-like title) */
    fun needsFix(song: Song): Boolean {
        val base = song.fileName.substringBeforeLast('.')
        val titleLooksLikeFile = song.title == base || Regex("^\\d{1,3}[ ._-]").containsMatchIn(song.title) || song.title.contains('_')
        val noArtist = song.artist.startsWith("Unknown", ignoreCase = true)
        val folderAlbum = song.album.equals(song.folder.trimEnd('/').substringAfterLast('/'), ignoreCase = true) ||
            song.album.startsWith("Unknown", ignoreCase = true)
        return noArtist || (folderAlbum && titleLooksLikeFile)
    }

    /** Builds a search phrase from the tags we trust plus a cleaned-up file name. */
    fun queryFor(song: Song): String {
        val base = song.fileName.substringBeforeLast('.')
            .replace(Regex("^\\d{1,3}[ ._-]+"), "")
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val artist = song.artist.takeUnless { it.startsWith("Unknown", true) }
        return listOfNotNull(artist, if (song.title != song.fileName.substringBeforeLast('.')) song.title else base).joinToString(" ")
    }

    suspend fun search(query: String, durationMs: Long = 0): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = ArrayList<MetadataCandidate>()
        runCatching {
            val data = JSONObject(get("https://api.deezer.com/search/track?limit=15&q=$q") ?: "{}").optJSONArray("data")
            if (data != null) for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                out += MetadataCandidate(
                    title = o.optString("title"), artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                    album = o.optJSONObject("album")?.optString("title").orEmpty(), year = null, genre = null,
                    track = null, disc = null, durationMs = o.optLong("duration") * 1000,
                    artUrl = o.optJSONObject("album")?.optString("cover_xl"), source = "Deezer",
                )
            }
        }
        runCatching {
            val res = JSONObject(get("https://itunes.apple.com/search?entity=song&limit=15&term=$q") ?: "{}").optJSONArray("results")
            if (res != null) for (i in 0 until res.length()) {
                val o = res.getJSONObject(i)
                out += MetadataCandidate(
                    title = o.optString("trackName"), artist = o.optString("artistName"), album = o.optString("collectionName"),
                    year = o.optString("releaseDate").take(4).toIntOrNull(), genre = o.optString("primaryGenreName").ifBlank { null },
                    track = o.optInt("trackNumber").takeIf { it > 0 }, disc = o.optInt("discNumber").takeIf { it > 0 },
                    durationMs = o.optLong("trackTimeMillis"),
                    artUrl = o.optString("artworkUrl100").takeIf { it.startsWith("http") }?.replace("100x100bb", "1000x1000bb"),
                    source = "iTunes",
                )
            }
        }
        // Closest length first: the strongest signal that it's really the same recording.
        if (durationMs > 0) out.sortedBy { if (it.durationMs > 0) abs(it.durationMs - durationMs) else Long.MAX_VALUE } else out
    }

    /** Picks a candidate only when length and title agree, so auto-fix never guesses. */
    fun confidentMatch(song: Song, candidates: List<MetadataCandidate>): MetadataCandidate? {
        val words = norm(queryFor(song)).split(' ').filter { it.length > 1 }.toSet()
        val knownArtist = song.artist.takeUnless { it.isBlank() || it.startsWith("Unknown", true) }
        val knownAlbum = song.album.takeUnless { it.isBlank() || it.startsWith("Unknown", true) || it == song.folder.trimEnd('/').substringAfterLast('/') }
        return candidates.filter { c ->
            val lengthOk = song.durationMs > 0 && c.durationMs > 0 && abs(c.durationMs - song.durationMs) <= 3_000
            val titleWords = norm(c.title).split(' ').filter { it.length > 1 }
            val artistOk = knownArtist?.let { norm(it) == norm(c.artist) }
                ?: norm(c.artist).split(' ').filter { it.length > 1 }.let { it.isNotEmpty() && it.all(words::contains) }
            lengthOk && artistOk && titleWords.isNotEmpty() && titleWords.all(words::contains) &&
                (knownAlbum == null || norm(knownAlbum) == norm(c.album))
        }.maxByOrNull { listOf(it.year, it.track, it.disc).count { n -> n != null && n > 0 } + if (it.genre.isNullOrBlank()) 0 else 1 }
    }

    /** Fill only absent fields. Run for every scan, including files added after launch. */
    fun autoFixAll(songs: List<Song>) {
        latestSongs = songs
        if (fixJob?.isActive == true) return
        val app = context.applicationContext as com.localfy.app.LocalfyApp
        if (!_autoFix.value && !app.onlineArt.enabled.value) return
        fixJob = scope.launch(Dispatchers.IO) {
            _fixing.value = true
            try {
                var pass = songs
                do {
                    for (raw in pass.filter { it.playable && (!it.isPodcast || it.isAudiobook) }) {
                        runCatching { fillMissing(raw, app) }
                    }
                    val next = latestSongs
                    if (next == pass) break
                    pass = next
                } while (true)
            } finally { _fixing.value = false }
        }
    }

    private fun com.localfy.app.data.db.MetadataOverrideEntity?.edit() = MetadataEdit(
        title = this?.title, artist = this?.artist, album = this?.album, albumArtist = this?.albumArtist,
        genre = this?.genre, year = this?.year, track = this?.track, disc = this?.disc,
    )

    private suspend fun fillMissing(raw: Song, app: com.localfy.app.LocalfyApp) {
        val saved = dao.get(raw.id)?.takeIf { it.fileName == raw.fileName }
        val song = apply(raw, saved)
        val snapshot = FileTags.read(context, raw)
        var suggested = MetadataEdit()
        var coverURL: String? = null
        val key = "missing-v2:${raw.id}:${raw.fileName}:${raw.sizeBytes}"
        if (_autoFix.value && MissingMetadata.incomplete(snapshot.fields, saved.edit()) &&
            System.currentTimeMillis() - prefs.getLong(key, 0) >= 7L * 24 * 60 * 60 * 1000) {
            if (song.isAudiobook) {
                val guess = song.album.replace('_', ' ')
                val hit = searchBooks(guess).firstOrNull { norm(it.title) == norm(guess) }
                if (hit != null) {
                    suggested = MetadataEdit(album = hit.title, artist = hit.author, albumArtist = hit.author, genre = "Audiobook", year = hit.year)
                    coverURL = hit.coverUrl
                }
            } else {
                val match = confidentMatch(song, search(queryFor(song), song.durationMs))
                if (match != null) {
                    suggested = MetadataEdit(match.title, match.artist, match.album, match.artist, match.genre, match.year, match.track, match.disc)
                    coverURL = match.artUrl
                }
            }
            prefs.edit { putLong(key, System.currentTimeMillis()) }
            delay(1_100)
        }
        val edit = if (_autoFix.value) MissingMetadata.fill(snapshot.fields, saved.edit(), suggested) else MetadataEdit()
        if (edit != MetadataEdit()) {
            dao.put(MetadataOverrideEntity(
                songId = raw.id, fileName = raw.fileName,
                title = saved?.title ?: edit.title, artist = saved?.artist ?: edit.artist,
                album = saved?.album ?: edit.album, albumArtist = saved?.albumArtist ?: edit.albumArtist,
                genre = saved?.genre ?: edit.genre, year = saved?.year ?: edit.year,
                track = saved?.track ?: edit.track, disc = saved?.disc ?: edit.disc,
                source = saved?.source ?: SOURCE_ONLINE, updatedAt = System.currentTimeMillis(),
            ))
        }
        val updated = apply(raw, dao.get(raw.id))
        var cover: File? = null
        if (snapshot.artwork == null && app.onlineArt.enabled.value) {
            cover = customArt(updated.albumId) ?: customArt(raw.albumId) ?: app.onlineArt.cached(updated.albumId)
            if (cover == null && coverURL != null) {
                // Save only into an empty app cover slot. File writes also check for an existing cover.
                setArt(updated.albumId, coverURL).join()
                cover = customArt(updated.albumId)
            }
            if (cover == null) cover = app.onlineArt.fetch(updated.albumId, updated.albumArtist, updated.album)
        }
        if (edit != MetadataEdit() || cover != null) writeAutomatic(raw, edit, cover)
    }

    private suspend fun writeAutomatic(song: Song, edit: MetadataEdit, cover: File?) {
        try {
            val jpeg = cover?.let {
                val temp = File.createTempFile("cover-", ".jpg", context.cacheDir)
                try {
                    check(com.localfy.app.data.saveSquareImage(android.graphics.ImageDecoder.createSource(it), temp, 1200))
                    temp.readBytes()
                } finally { temp.delete() }
            }
            FileTags.write(context, song, edit, jpeg, onlyMissing = true)
            pending.remove(song.id)
        } catch (error: SecurityException) {
            if (song.uri.authority == android.provider.MediaStore.AUTHORITY && song.id !in declinedWrites) pending[song.id] = PendingWrite(song, MissingMetadata.complete(edit, pending[song.id]?.edit ?: MetadataEdit()), cover ?: pending[song.id]?.cover)
        }
        _pendingWrites.value = pending.values.map { it.song.uri }.distinct()
    }

    fun finishAutomaticWrites(uris: List<android.net.Uri>, allowed: Boolean) = scope.launch(Dispatchers.IO) {
        if (!allowed) {
            // Without this, every launch re-detected the same files and showed Android's prompt again.
            val ids = pending.values.filter { it.song.uri in uris }.map { it.song.id }
            declinedWrites += ids
            ids.forEach { pending.remove(it) }
            prefs.edit { putStringSet("declinedWrites", synchronized(declinedWrites) { declinedWrites.map(Long::toString).toSet() }) }
            _pendingWrites.value = pending.values.map { it.song.uri }.distinct()
            return@launch
        }
        for (item in pending.values.toList().filter { it.song.uri in uris }) {
            runCatching { writeAutomatic(item.song, item.edit, item.cover) }
        }
        if (allowed) withContext(Dispatchers.Main) { (context.applicationContext as com.localfy.app.LocalfyApp).library.refresh() }
    }

    suspend fun searchBooks(query: String) = withContext(Dispatchers.IO) {
        runCatching { com.localfy.app.data.podcast.OpenLibrary.search(query) }.getOrDefault(emptyList())
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun get(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000; conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Spitify/1.0 (Android local music player)")
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_ONLINE = "online"
    }
}

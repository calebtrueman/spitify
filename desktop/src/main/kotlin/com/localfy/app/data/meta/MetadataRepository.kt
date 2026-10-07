package com.localfy.app.data.meta

import com.localfy.app.data.DataFile
import com.localfy.app.data.PrefsFile
import com.localfy.app.data.Song
import com.localfy.app.data.art.CoverDownload
import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.db.MetadataOverrideEntity
import com.localfy.app.data.file
import com.localfy.app.data.optIntOrNull
import com.localfy.app.data.optStringOrNull
import com.localfy.app.data.saveSquareImage
import com.localfy.app.data.writeAtomically
import com.localfy.app.desktop.AppPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
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

/** An Open Library hit for an audiobook (same fields as the Android `OpenLibrary.Book`). */
data class BookCandidate(val title: String, val author: String, val year: Int?, val coverUrl: String?)

/**
 * Keeps library edits in sync with file tags (desktop port of the Android repository).
 * Overrides live in `metadata_overrides.json`; custom covers in `custom_art/<albumId>.jpg`.
 * Manual saves write the real files (and report failures); automatic fixes only fill tags that
 * are missing, and only in files Spitify is allowed to change.
 *
 * Wire-up (done by [com.localfy.app.data.LibraryRepository]'s init): [librarySongs] and [onFilesChanged].
 */
class MetadataRepository(
    private val scope: CoroutineScope,
    private val onlineArt: OnlineArtRepository,
    dataDir: File = AppPaths.dataDir,
    private val cacheDir: File = AppPaths.cacheDir,
) {
    private val prefs = PrefsFile(File(dataDir, "prefs-metadata.json"))
    /** "Looked this file up online at" times (Android keeps them in the same prefs; split out here to keep prefs small). */
    private val lookups = PrefsFile(File(dataDir, "metadata-lookups.json"))
    private val store = DataFile(File(dataDir, "metadata_overrides.json"))
    private val artDir = File(dataDir, "custom_art").apply { mkdirs() }

    /** Every library song + local audiobook (set by LibraryRepository); used by [embedArt]. */
    @Volatile var librarySongs: () -> List<Song> = { emptyList() }
    /** Called after files were rewritten so the library rescans them (set by LibraryRepository). */
    @Volatile var onFilesChanged: () -> Unit = {}

    private val _overrides = MutableStateFlow(loadOverrides())
    val overrides: StateFlow<Map<Long, MetadataOverrideEntity>> = _overrides.asStateFlow()

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
    /** Files an automatic fix couldn't write (read-only); the UI may offer to retry ([finishAutomaticWrites]). */
    private val _pendingWrites = MutableStateFlow<List<File>>(emptyList())
    val pendingWrites: StateFlow<List<File>> = _pendingWrites.asStateFlow()
    @Volatile private var latestSongs: List<Song> = emptyList()
    /** Files written automatically this session (path|size), so a write can never loop with the folder watcher. */
    private val autoWritten = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Songs whose automatic tag write the user declined; never ask again for them (until auto-fix is turned back on). */
    private val declinedWrites: MutableSet<Long> = java.util.Collections.synchronizedSet(
        prefs.getStringSet("declinedWrites", emptySet()).mapNotNull { it.toLongOrNull() }.toMutableSet(),
    )

    fun setAutoFix(on: Boolean) {
        if (on && !_autoFix.value) { declinedWrites.clear(); prefs.edit { remove("declinedWrites") } }
        _autoFix.value = on
        prefs.edit { putBoolean("autoFix", on) }
        if (on) autoFixAll(latestSongs)
    }

    // ---------- Persistence ----------

    private fun loadOverrides(): Map<Long, MetadataOverrideEntity> {
        val arr = store.readArray() ?: return emptyMap()
        val out = HashMap<Long, MetadataOverrideEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                MetadataOverrideEntity(
                    songId = o.getLong("songId"), fileName = o.optString("fileName"),
                    title = o.optStringOrNull("title"), artist = o.optStringOrNull("artist"), album = o.optStringOrNull("album"),
                    albumArtist = o.optStringOrNull("albumArtist"), genre = o.optStringOrNull("genre"), year = o.optIntOrNull("year"),
                    track = o.optIntOrNull("track"), disc = o.optIntOrNull("disc"),
                    source = o.optString("source", SOURCE_USER), updatedAt = o.optLong("updatedAt"),
                )
            }.getOrNull()?.let { out[it.songId] = it }
        }
        return out
    }

    private fun persist() {
        store.save {
            val arr = JSONArray()
            _overrides.value.values.forEach { e ->
                arr.put(JSONObject().apply {
                    put("songId", e.songId); put("fileName", e.fileName)
                    e.title?.let { put("title", it) }; e.artist?.let { put("artist", it) }; e.album?.let { put("album", it) }
                    e.albumArtist?.let { put("albumArtist", it) }; e.genre?.let { put("genre", it) }; e.year?.let { put("year", it) }
                    e.track?.let { put("track", it) }; e.disc?.let { put("disc", it) }
                    put("source", e.source); put("updatedAt", e.updatedAt)
                })
            }
            arr.toString()
        }
    }

    private fun put(entity: MetadataOverrideEntity) { _overrides.update { it + (entity.songId to entity) }; persist() }
    private fun delete(songId: Long) { _overrides.update { it - songId }; persist() }
    private fun get(songId: Long): MetadataOverrideEntity? = _overrides.value[songId]

    /** Follows songs to new ids after their file was replaced (FLAC → AAC); [fileNames] are the new file names. */
    fun remapSongs(ids: Map<Long, Long>, fileNames: Map<Long, String> = emptyMap()) {
        if (_overrides.value.keys.none { it in ids }) return
        _overrides.update { current ->
            val out = HashMap(current)
            for ((old, new) in ids) {
                val e = out.remove(old) ?: continue
                if (new !in out) out[new] = e.copy(songId = new, fileName = fileNames[old] ?: e.fileName)
            }
            out
        }
        persist()
    }

    /** Writes pending changes now (shutdown/tests). */
    fun flush() { store.flush(); prefs.flush(); lookups.flush() }

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
            // Renamed albums regroup by name; untouched ones keep the scanner's album id.
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
            val existing = get(song.id)?.takeIf { it.fileName == song.fileName }
            if (source == SOURCE_ONLINE && existing?.source == SOURCE_USER) return@forEach
            if (source == SOURCE_ONLINE) runCatching { FileTags.write(song, edit, onlyMissing = true) }
            put(
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

    /** Loads an image source (http(s) URL, file: URI or path) as raw bytes. */
    private fun loadImage(source: String): ByteArray {
        if (source.startsWith("http://") || source.startsWith("https://")) return CoverDownload.load(source)
        val file = if (source.startsWith("file:")) File(URI(source)) else File(source)
        check(file.isFile) { "The cover image could not be found." }
        check(file.length() <= 40_000_000) { "The cover image is too large." }
        return file.readBytes()
    }

    /** Normalizes a picked/downloaded cover into a square JPEG in the cache; returns its file: URI. */
    suspend fun prepareArtwork(source: String): String = withContext(Dispatchers.IO) {
        val bytes = loadImage(source)
        val dir = File(cacheDir, "selected-covers").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val file = File.createTempFile("cover-", ".jpg", dir)
        try {
            check(saveSquareImage(bytes, file, 1200)) { "The cover image could not be read." }
            file.toURI().toString()
        } catch (error: Exception) { file.delete(); throw error }
    }

    private fun squareJpeg(bytes: ByteArray): ByteArray {
        val temp = File.createTempFile("cover-", ".jpg", cacheDir.apply { mkdirs() })
        try {
            check(saveSquareImage(bytes, temp, 1200)) { "The cover image could not be read." }
            return temp.readBytes()
        } finally { temp.delete() }
    }

    /** Writes [edit] (and the cover) into every file, then records the overrides. Throws with a readable message on failure. */
    suspend fun saveFiles(songs: List<Song>, edit: MetadataEdit, artSource: String?) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        // Check every file first, so a batch never stops half way because one file is read-only.
        for (song in songs) {
            val f = song.file ?: error("${song.title} isn't a file on this computer.")
            check(f.isFile) { "${f.name} no longer exists." }
            check(f.canWrite()) { "Spitify isn't allowed to change ${f.name}." }
        }
        val image = if (artSource != null) {
            val bytes = loadImage(artSource)
            check(bytes.size <= 20_000_000) { "The cover image is too large." }
            squareJpeg(bytes)
        } else {
            val first = songs.first()
            (customArt(first.albumId) ?: onlineArt.cached(first.albumId))?.let { squareJpeg(it.readBytes()) }
        }
        for (song in songs) {
            FileTags.write(song, edit, image)
            save(listOf(song), edit).join()
        }
        if (image != null) {
            val first = songs.first()
            val target = if (edit.album != null || edit.albumArtist != null) syntheticAlbumId(edit.album ?: first.album, edit.albumArtist ?: edit.artist ?: first.albumArtist) else first.albumId
            writeAtomically(File(artDir, "$target.jpg"), image)
            bumpArt(target)
        }
        onFilesChanged()
    }

    fun reset(songs: List<Song>) = scope.launch(Dispatchers.IO) { songs.forEach { delete(it.id) } }

    // ---------- Artwork ----------

    fun customArt(albumId: Long): File? = File(artDir, "$albumId.jpg").takeIf { it.isFile && it.length() > 0 }

    /** Sets album art from a picked image (path / file: URI) or a downloaded candidate (http URL). */
    fun setArt(albumId: Long, source: String) = scope.launch(Dispatchers.IO) {
        val bytes = runCatching { loadImage(source) }.getOrNull() ?: return@launch
        if (!saveSquareImage(bytes, File(artDir, "$albumId.jpg"), 1200)) return@launch
        bumpArt(albumId)
        embedArt(albumId, File(artDir, "$albumId.jpg"))
    }

    /** Embeds [image] into the album's files that have no cover yet. */
    suspend fun embedArt(albumId: Long, image: File) = withContext(Dispatchers.IO) {
        librarySongs().filter { it.albumId == albumId }.forEach { song ->
            runCatching {
                val f = song.file ?: return@runCatching
                if (FileTags.artwork(f) == null) writeAutomatic(song, MetadataEdit(), image)
            }
        }
    }

    fun removeArt(albumId: Long) = scope.launch(Dispatchers.IO) {
        File(artDir, "$albumId.jpg").delete()
        bumpArt(albumId)
    }

    private fun bumpArt(albumId: Long) {
        _artVersions.update { it + (albumId to System.currentTimeMillis()) }
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
        if (!_autoFix.value && !onlineArt.enabled.value) return
        fixJob = scope.launch(Dispatchers.IO) {
            _fixing.value = true
            try {
                var pass = songs
                do {
                    for (raw in pass.filter { it.playable && it.file != null && (!it.isPodcast || it.isAudiobook) }) {
                        try { fillMissing(raw) } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
                    }
                    val next = latestSongs
                    if (next == pass) break
                    pass = next
                } while (true)
            } finally { _fixing.value = false }
        }
    }

    private fun MetadataOverrideEntity?.edit() = MetadataEdit(
        title = this?.title, artist = this?.artist, album = this?.album, albumArtist = this?.albumArtist,
        genre = this?.genre, year = this?.year, track = this?.track, disc = this?.disc,
    )

    private suspend fun fillMissing(raw: Song) {
        val saved = get(raw.id)?.takeIf { it.fileName == raw.fileName }
        val song = apply(raw, saved)
        val snapshot = FileTags.read(raw)
        var suggested = MetadataEdit()
        var coverURL: String? = null
        val key = "missing-v2:${raw.id}:${raw.fileName}:${raw.sizeBytes}"
        if (_autoFix.value && MissingMetadata.incomplete(snapshot.fields, saved.edit()) &&
            System.currentTimeMillis() - lookups.getLong(key, 0) >= 7L * 24 * 60 * 60 * 1000) {
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
            lookups.edit { putLong(key, System.currentTimeMillis()) }
            delay(1_100)
        }
        val edit = if (_autoFix.value) MissingMetadata.fill(snapshot.fields, saved.edit(), suggested) else MetadataEdit()
        if (edit != MetadataEdit()) {
            put(MetadataOverrideEntity(
                songId = raw.id, fileName = raw.fileName,
                title = saved?.title ?: edit.title, artist = saved?.artist ?: edit.artist,
                album = saved?.album ?: edit.album, albumArtist = saved?.albumArtist ?: edit.albumArtist,
                genre = saved?.genre ?: edit.genre, year = saved?.year ?: edit.year,
                track = saved?.track ?: edit.track, disc = saved?.disc ?: edit.disc,
                source = saved?.source ?: SOURCE_ONLINE, updatedAt = System.currentTimeMillis(),
            ))
        }
        val updated = apply(raw, get(raw.id))
        var cover: File? = null
        if (snapshot.artwork == null && onlineArt.enabled.value) {
            cover = customArt(updated.albumId) ?: customArt(raw.albumId) ?: onlineArt.cached(updated.albumId)
            if (cover == null && coverURL != null) {
                // Save only into an empty app cover slot. File writes also check for an existing cover.
                setArt(updated.albumId, coverURL).join()
                cover = customArt(updated.albumId)
            }
            if (cover == null) cover = onlineArt.fetch(updated.albumId, updated.albumArtist, updated.album)
        }
        if (edit != MetadataEdit() || cover != null) writeAutomatic(raw, edit, cover)
    }

    private suspend fun writeAutomatic(song: Song, edit: MetadataEdit, cover: File?) {
        val file = song.file ?: return
        val key = file.path + "|" + file.length()
        if (key in autoWritten) return
        try {
            if (!file.canWrite() || file.parentFile?.canWrite() != true) throw SecurityException("read-only")
            val jpeg = cover?.let { squareJpeg(it.readBytes()) }
            FileTags.write(song, edit, jpeg, onlyMissing = true)
            autoWritten += file.path + "|" + file.length()
            pending.remove(song.id)
        } catch (error: SecurityException) {
            if (song.id !in declinedWrites) pending[song.id] = PendingWrite(song, MissingMetadata.complete(edit, pending[song.id]?.edit ?: MetadataEdit()), cover ?: pending[song.id]?.cover)
        } finally {
            autoWritten += key
        }
        _pendingWrites.value = pending.values.mapNotNull { it.song.file }.distinct()
    }

    /** Retry ([allowed]) or give up on ([allowed] = false) the automatic writes for [files]. */
    fun finishAutomaticWrites(files: List<File>, allowed: Boolean) = scope.launch(Dispatchers.IO) {
        val chosen = pending.values.filter { it.song.file in files }
        if (!allowed) {
            val ids = chosen.map { it.song.id }
            declinedWrites += ids
            ids.forEach { pending.remove(it) }
            prefs.edit { putStringSet("declinedWrites", synchronized(declinedWrites) { declinedWrites.map(Long::toString).toSet() }) }
            _pendingWrites.value = pending.values.mapNotNull { it.song.file }.distinct()
            return@launch
        }
        for (item in chosen) {
            item.song.file?.let { f -> autoWritten.removeIf { it.startsWith(f.path + "|") } }
            runCatching { writeAutomatic(item.song, item.edit, item.cover) }
        }
        onFilesChanged()
    }

    suspend fun searchBooks(query: String): List<BookCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val body = get("https://openlibrary.org/search.json?q=$q&limit=8&fields=title,author_name,first_publish_year,cover_i") ?: return@runCatching emptyList()
            val docs = JSONObject(body).optJSONArray("docs") ?: return@runCatching emptyList()
            (0 until docs.length()).map { i ->
                val d = docs.getJSONObject(i)
                BookCandidate(
                    d.optString("title"),
                    d.optJSONArray("author_name")?.optString(0).orEmpty(),
                    d.optInt("first_publish_year").takeIf { it > 0 },
                    d.optInt("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun get(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000; conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_ONLINE = "online"
        internal const val USER_AGENT = "Spitify/1.0 (desktop local music player)"
    }
}

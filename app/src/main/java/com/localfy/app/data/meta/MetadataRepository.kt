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
 * Metadata corrections and custom artwork, layered over the files' own tags inside Localfy.
 * Files are never modified, so "Reset" always restores the original tags.
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

    fun setAutoFix(on: Boolean) {
        _autoFix.value = on
        prefs.edit { putBoolean("autoFix", on) }
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

    fun reset(songs: List<Song>) = scope.launch(Dispatchers.IO) { songs.forEach { dao.delete(it.id) } }

    // ---------- Artwork ----------

    fun customArt(albumId: Long): File? = File(artDir, "$albumId.jpg").takeIf { it.isFile && it.length() > 0 }

    /** Sets album art from a picked image (content Uri) or a downloaded candidate (http URL). */
    fun setArt(albumId: Long, source: String) = scope.launch(Dispatchers.IO) {
        val bytes = runCatching {
            if (source.startsWith("http")) getBytes(source)
            else context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytes() }
        }.getOrNull() ?: return@launch
        File(artDir, "$albumId.jpg").writeBytes(bytes)
        bumpArt(albumId)
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
        return candidates.firstOrNull { c ->
            val lengthOk = song.durationMs > 0 && c.durationMs > 0 && abs(c.durationMs - song.durationMs) <= 3_000
            val titleWords = norm(c.title).split(' ').filter { it.length > 1 }
            lengthOk && titleWords.isNotEmpty() && titleWords.count { it in words } >= (titleWords.size + 1) / 2
        }
    }

    /** Background pass over untagged files (rate-limited). */
    fun autoFixAll(songs: List<Song>, onArt: (Long, String) -> Unit) {
        if (!_autoFix.value || fixJob?.isActive == true) return
        val tried = prefs.getStringSet("tried", emptySet()).orEmpty()
        val todo = songs.filter { !it.isPodcast && it.playable && needsFix(it) && "${it.id}:${it.fileName}" !in tried && overrides.value[it.id] == null }
        if (todo.isEmpty()) return
        fixJob = scope.launch(Dispatchers.IO) {
            _fixing.value = true
            val done = tried.toMutableSet()
            for (song in todo.take(200)) {
                val match = confidentMatch(song, search(queryFor(song), song.durationMs))
                if (match != null) {
                    save(listOf(song), MetadataEdit(match.title, match.artist, match.album, match.artist, match.genre, match.year, match.track, match.disc), SOURCE_ONLINE).join()
                    match.artUrl?.let { url -> onArt(syntheticAlbumId(match.album, match.artist), url) }
                }
                done += "${song.id}:${song.fileName}"
                prefs.edit { putStringSet("tried", done) }
                delay(1_100) // be polite to the free APIs
            }
            _fixing.value = false
        }
    }

    /** Your own audiobooks: if the "album" is just a folder name or the author is missing, ask Open Library. */
    fun autoFixBooks(chapters: List<Song>) {
        if (!_autoFix.value) return
        val tried = prefs.getStringSet("triedBooks", emptySet()).orEmpty()
        val books = chapters.groupBy { it.albumId }.filter { (id, ch) ->
            val first = ch.first()
            "$id" !in tried && ch.none { overrides.value[it.id]?.source == SOURCE_USER } &&
                (first.artist.startsWith("Unknown", true) || first.album.equals(first.folder.trimEnd('/').substringAfterLast('/'), true))
        }
        if (books.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val done = tried.toMutableSet()
            for ((albumId, ch) in books) {
                val first = ch.first()
                val guess = first.album.replace('_', ' ').replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()
                val hit = runCatching { com.localfy.app.data.podcast.OpenLibrary.search(guess) }.getOrDefault(emptyList())
                    .firstOrNull { norm(it.title).let { t -> t.isNotEmpty() && (norm(guess).contains(t) || t.contains(norm(guess))) } }
                if (hit != null) {
                    save(ch, MetadataEdit(album = hit.title, artist = hit.author.ifBlank { null }, albumArtist = hit.author.ifBlank { null }, year = hit.year, genre = "Audiobook"), SOURCE_ONLINE).join()
                    hit.coverUrl?.let { setArt(syntheticAlbumId(hit.title, hit.author.ifBlank { first.albumArtist }), it) }
                }
                done += "$albumId"
                prefs.edit { putStringSet("triedBooks", done) }
                delay(1_100)
            }
        }
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

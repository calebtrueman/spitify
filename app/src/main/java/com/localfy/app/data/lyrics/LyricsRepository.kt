package com.localfy.app.data.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import com.localfy.app.data.Song
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.db.LyricsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

enum class LyricsSource(val label: String) { Embedded("Embedded in file"), LrcFile(".lrc file"), Lrclib("LRCLIB") }

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: LyricsSource,
    val offsetMs: Int = 0,
)

sealed interface LyricsState {
    data object Loading : LyricsState
    /** Nothing local. [searchedOnline] tells the UI whether offering an online search makes sense. */
    data class NotFound(val searchedOnline: Boolean) : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

/**
 * Finds lyrics for a song, in order: embedded tags → .lrc sidecar in the user's lyrics folder →
 * LRCLIB (free, open lyrics database) when online lookup is enabled or explicitly requested.
 * Results, including misses, are cached in Room.
 */
class LyricsRepository(
    private val context: Context,
    private val db: LocalfyDatabase,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("lyrics", Context.MODE_PRIVATE)
    private val tagReader = TagLyricsReader(context)

    private val _states = MutableStateFlow<Map<Long, LyricsState>>(emptyMap())
    val states: StateFlow<Map<Long, LyricsState>> = _states.asStateFlow()

    private val _onlineEnabled = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, true))
    val onlineEnabled: StateFlow<Boolean> = _onlineEnabled.asStateFlow()

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    @Volatile private var lrcIndex: Map<String, Uri>? = null
    private val offlineMisses = java.util.Collections.synchronizedSet(HashSet<Long>())

    init {
        // When the internet comes back, forget offline misses so those songs get looked up again.
        context.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val ids = synchronized(offlineMisses) { offlineMisses.toList().also { offlineMisses.clear() } }
                if (ids.isNotEmpty()) _states.update { it - ids.toSet() }
            }
        })
    }

    fun setOnlineEnabled(enabled: Boolean) {
        _onlineEnabled.value = enabled
        prefs.edit { putBoolean(KEY_ONLINE, enabled) }
        if (enabled) scope.launch { db.lyrics().clearMisses(); _states.value = emptyMap() }
    }

    fun setFolder(uri: Uri?) {
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        _folder.value = uri
        prefs.edit { putString(KEY_FOLDER, uri?.toString()) }
        lrcIndex = null
        scope.launch { db.lyrics().clearMisses(); _states.value = emptyMap() }
    }

    /** Ensures lyrics for [song] are loading/loaded; safe to call repeatedly. */
    fun request(song: Song, forceOnline: Boolean = false) {
        if (song.isPodcast || song.isAudiobook) return // spoken word: no lyrics, and no LRCLIB lookups
        val current = _states.value[song.id]
        if (!forceOnline && current != null) return
        if (current == LyricsState.Loading) return
        _states.update { it + (song.id to LyricsState.Loading) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { load(song, forceOnline) }
            _states.update { it + (song.id to result) }
        }
    }

    fun setOffset(song: Song, offsetMs: Int) {
        val found = _states.value[song.id] as? LyricsState.Found ?: return
        _states.update { it + (song.id to LyricsState.Found(found.lyrics.copy(offsetMs = offsetMs))) }
        scope.launch { db.lyrics().setOffset(song.id, offsetMs) }
    }

    fun forget(song: Song) {
        scope.launch {
            db.lyrics().delete(song.id)
            _states.update { it - song.id }
        }
    }

    private suspend fun load(song: Song, forceOnline: Boolean): LyricsState {
        val cached = db.lyrics().get(song.id)
        val online = forceOnline || _onlineEnabled.value
        if (cached != null && !(cached.notFound && forceOnline)) {
            if (!cached.notFound) return found(cached.synced ?: cached.plain.orEmpty(), LyricsSource.valueOf(cached.source), cached.offsetMs)
            if (!online || cached.source == LyricsSource.Lrclib.name) return LyricsState.NotFound(searchedOnline = cached.source == LyricsSource.Lrclib.name)
        }

        tagReader.read(song.uri)?.let { return save(song, it, LyricsSource.Embedded) }
        readSidecar(song)?.let { return save(song, it, LyricsSource.LrcFile) }
        if (online) {
            val result = runCatching { fetchLrclib(song) }
            // No connection: don't remember a miss, try again when we're back online.
            if (result.isFailure) { offlineMisses += song.id; return LyricsState.NotFound(searchedOnline = false) }
            val fetched = result.getOrNull()
            if (fetched != null) return save(song, fetched, LyricsSource.Lrclib)
            db.lyrics().put(LyricsEntity(song.id, null, null, LyricsSource.Lrclib.name, 0, System.currentTimeMillis(), notFound = true))
            return LyricsState.NotFound(searchedOnline = true)
        }
        db.lyrics().put(LyricsEntity(song.id, null, null, LyricsSource.Embedded.name, 0, System.currentTimeMillis(), notFound = true))
        return LyricsState.NotFound(searchedOnline = false)
    }

    private suspend fun save(song: Song, text: String, source: LyricsSource): LyricsState {
        val synced = Lrc.isSynced(text)
        db.lyrics().put(
            LyricsEntity(song.id, if (synced) text else null, if (synced) null else text, source.name, 0, System.currentTimeMillis(), false),
        )
        return found(text, source, 0)
    }

    private fun found(text: String, source: LyricsSource, offset: Int): LyricsState {
        val synced = Lrc.isSynced(text)
        val lines = if (synced) Lrc.parse(text) else Lrc.plainLines(text)
        if (lines.isEmpty()) return LyricsState.NotFound(searchedOnline = source == LyricsSource.Lrclib)
        return LyricsState.Found(Lyrics(lines, synced, source, offset))
    }

    // ---- .lrc sidecars in a user-picked folder (Storage Access Framework) ----

    private fun readSidecar(song: Song): String? {
        val root = _folder.value ?: return null
        val index = lrcIndex ?: buildIndex(root).also { lrcIndex = it }
        val keys = listOf(
            song.fileName.substringBeforeLast('.'),
            "${song.artist} - ${song.title}",
            song.title,
        ).map(::norm)
        val uri = keys.firstNotNullOfOrNull { index[it] } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun buildIndex(root: Uri): Map<String, Uri> {
        val out = HashMap<String, Uri>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull() ?: return out
        val stack = ArrayDeque(listOf(rootId))
        var visited = 0
        while (stack.isNotEmpty() && visited < 5_000) {
            val docId = stack.removeLast()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, docId)
            runCatching {
                context.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) stack.addLast(id)
                        else if (name.endsWith(".lrc", ignoreCase = true)) {
                            out[norm(name.substringBeforeLast('.'))] = DocumentsContract.buildDocumentUriUsingTree(root, id)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // ---- LRCLIB ----

    private fun fetchLrclib(song: Song): String? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val seconds = song.durationMs / 1000
        val exact = httpGet(
            "https://lrclib.net/api/get?artist_name=${enc(song.artist)}&track_name=${enc(song.title)}" +
                "&album_name=${enc(song.album)}&duration=$seconds",
        )?.let(::JSONObject)
        exact?.let { pick(it) }?.let { return it }

        val results = httpGet("https://lrclib.net/api/search?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}")
            ?.let(::JSONArray) ?: return null
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }
            .sortedBy { abs(it.optDouble("duration", 0.0) - seconds) }
            .filter { abs(it.optDouble("duration", 0.0) - seconds) <= 5 }
        return candidates.firstNotNullOfOrNull(::pick)
    }

    private fun pick(o: JSONObject): String? {
        if (o.optBoolean("instrumental")) return "[00:00.00]♪ Instrumental"
        return o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Returns null for "not found" (HTTP 404 etc.); throws on network errors. */
    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Spitify/1.0 (Android local music player)")
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val KEY_ONLINE = "online"
        private const val KEY_FOLDER = "folder"
    }
}

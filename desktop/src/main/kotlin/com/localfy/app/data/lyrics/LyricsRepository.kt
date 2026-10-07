package com.localfy.app.data.lyrics

import com.localfy.app.data.DataFile
import com.localfy.app.data.PrefsFile
import com.localfy.app.data.Song
import com.localfy.app.data.db.LyricsEntity
import com.localfy.app.data.file
import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.optStringOrNull
import com.localfy.app.desktop.AppPaths
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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
 * Finds lyrics for a song, in order: embedded tags → .lrc sidecar next to the file or in the
 * user's lyrics folder → LRCLIB (free, open lyrics database) when online lookup is enabled or
 * explicitly requested. Results, including misses, are cached in `lyrics.json`.
 * Podcasts and audiobooks are skipped.
 */
class LyricsRepository(
    private val scope: CoroutineScope,
    dataDir: File = AppPaths.dataDir,
    /** LRCLIB lookup; replaceable in tests. Returns null for "not found", throws when offline. */
    private val onlineLookup: ((Song) -> String?)? = null,
) {
    private val prefs = PrefsFile(File(dataDir, "prefs-lyrics.json"))
    private val store = DataFile(File(dataDir, "lyrics.json"))
    private val tagReader = TagLyricsReader()
    private val cache = ConcurrentHashMap<Long, LyricsEntity>(loadCache())

    private val _states = MutableStateFlow<Map<Long, LyricsState>>(emptyMap())
    val states: StateFlow<Map<Long, LyricsState>> = _states.asStateFlow()

    private val _onlineEnabled = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, true))
    val onlineEnabled: StateFlow<Boolean> = _onlineEnabled.asStateFlow()

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(::File))
    /** Extra folder searched (recursively) for .lrc files; null = only next to each song. */
    val folder: StateFlow<File?> = _folder.asStateFlow()

    @Volatile private var lrcIndex: Map<String, File>? = null
    /** Songs whose online lookup failed for lack of a connection, and when; retried after a minute. */
    private val offlineMisses = ConcurrentHashMap<Long, Long>()

    fun setOnlineEnabled(enabled: Boolean) {
        _onlineEnabled.value = enabled
        prefs.edit { putBoolean(KEY_ONLINE, enabled) }
        if (enabled) scope.launch { clearMisses(); _states.value = emptyMap() }
    }

    fun setFolder(folder: File?) {
        _folder.value = folder
        prefs.edit { putString(KEY_FOLDER, folder?.absolutePath) }
        lrcIndex = null
        scope.launch { clearMisses(); _states.value = emptyMap() }
    }

    /** Ensures lyrics for [song] are loading/loaded; safe to call repeatedly. */
    fun request(song: Song, forceOnline: Boolean = false) {
        if (song.isPodcast || song.isAudiobook) return // spoken word: no lyrics, and no LRCLIB lookups
        // No desktop network callback: retry songs that failed offline once a minute has passed.
        offlineMisses[song.id]?.let { at ->
            if (System.currentTimeMillis() - at > 60_000) { offlineMisses.remove(song.id); _states.update { it - song.id } }
        }
        val current = _states.value[song.id]
        if (!forceOnline && current != null) return
        if (current == LyricsState.Loading) return
        _states.update { it + (song.id to LyricsState.Loading) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { load(song, forceOnline) }.getOrElse { LyricsState.NotFound(searchedOnline = false) }
            }
            _states.update { it + (song.id to result) }
        }
    }

    fun setOffset(song: Song, offsetMs: Int) {
        val found = _states.value[song.id] as? LyricsState.Found ?: return
        _states.update { it + (song.id to LyricsState.Found(found.lyrics.copy(offsetMs = offsetMs))) }
        cache[song.id]?.let { cache[song.id] = it.copy(offsetMs = offsetMs); persist() }
    }

    fun forget(song: Song) {
        scope.launch {
            cache.remove(song.id); persist()
            _states.update { it - song.id }
        }
    }

    /** Moves cached lyrics to new song ids after files were replaced (FLAC → AAC). */
    fun remapSongs(ids: Map<Long, Long>) {
        var changed = false
        for ((old, new) in ids) {
            val e = cache.remove(old) ?: continue
            cache.putIfAbsent(new, e.copy(songId = new)); changed = true
        }
        if (changed) { persist(); _states.update { m -> m.mapKeys { (k, _) -> ids[k] ?: k } } }
    }

    fun flush() { store.flush(); prefs.flush() }

    private fun clearMisses() {
        if (cache.values.removeIf { it.notFound }) persist()
    }

    private fun load(song: Song, forceOnline: Boolean): LyricsState {
        val cached = cache[song.id]
        val online = forceOnline || _onlineEnabled.value
        if (cached != null && !(cached.notFound && forceOnline)) {
            if (!cached.notFound) return found(cached.synced ?: cached.plain.orEmpty(), runCatching { LyricsSource.valueOf(cached.source) }.getOrDefault(LyricsSource.Embedded), cached.offsetMs)
            if (!online || cached.source == LyricsSource.Lrclib.name) return LyricsState.NotFound(searchedOnline = cached.source == LyricsSource.Lrclib.name)
        }

        song.file?.let { f ->
            (tagReader.read(f) ?: FileTags.lyrics(f))?.let { return save(song, it, LyricsSource.Embedded) }
        }
        readSidecar(song)?.let { return save(song, it, LyricsSource.LrcFile) }
        if (online) {
            val result = runCatching { (onlineLookup ?: ::fetchLrclib)(song) }
            // No connection: don't remember a miss, try again when we're back online.
            if (result.isFailure) { offlineMisses[song.id] = System.currentTimeMillis(); return LyricsState.NotFound(searchedOnline = false) }
            val fetched = result.getOrNull()
            if (fetched != null) return save(song, fetched, LyricsSource.Lrclib)
            put(LyricsEntity(song.id, null, null, LyricsSource.Lrclib.name, 0, System.currentTimeMillis(), notFound = true))
            return LyricsState.NotFound(searchedOnline = true)
        }
        put(LyricsEntity(song.id, null, null, LyricsSource.Embedded.name, 0, System.currentTimeMillis(), notFound = true))
        return LyricsState.NotFound(searchedOnline = false)
    }

    private fun save(song: Song, text: String, source: LyricsSource): LyricsState {
        val synced = Lrc.isSynced(text)
        put(LyricsEntity(song.id, if (synced) text else null, if (synced) null else text, source.name, 0, System.currentTimeMillis(), false))
        return found(text, source, 0)
    }

    private fun found(text: String, source: LyricsSource, offset: Int): LyricsState {
        val synced = Lrc.isSynced(text)
        val lines = if (synced) Lrc.parse(text) else Lrc.plainLines(text)
        if (lines.isEmpty()) return LyricsState.NotFound(searchedOnline = source == LyricsSource.Lrclib)
        return LyricsState.Found(Lyrics(lines, synced, source, offset))
    }

    private fun put(e: LyricsEntity) { cache[e.songId] = e; persist() }

    // ---------- .lrc sidecars ----------

    private fun readSidecar(song: Song): String? {
        // 1. Next to the audio file: "Song.lrc" (any case).
        song.file?.let { audio ->
            val base = audio.nameWithoutExtension
            val dir = audio.parentFile
            val direct = File(dir, "$base.lrc")
            val match = if (direct.isFile) direct else dir?.listFiles { f -> f.isFile && f.name.equals("$base.lrc", ignoreCase = true) }?.firstOrNull()
            match?.let { f -> readText(f)?.let { return it } }
        }
        // 2. The user's lyrics folder, matched by file name, "Artist - Title" or title.
        val root = _folder.value ?: return null
        val index = lrcIndex ?: buildIndex(root).also { lrcIndex = it }
        val keys = listOf(
            song.fileName.substringBeforeLast('.'),
            "${song.artist} - ${song.title}",
            song.title,
        ).map(::norm)
        val file = keys.firstNotNullOfOrNull { index[it] } ?: return null
        return readText(file)
    }

    private fun readText(file: File): String? = runCatching {
        if (file.length() > 2_000_000) return null
        val bytes = file.readBytes()
        // UTF-8 (with or without BOM) is by far the most common; fall back to Latin-1 for old files.
        val utf8 = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        if ('�' in utf8) String(bytes, Charsets.ISO_8859_1) else utf8
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun buildIndex(root: File): Map<String, File> {
        val out = HashMap<String, File>()
        if (!root.isDirectory) return out
        var visited = 0
        root.walkTopDown().onEnter { visited++ < 5_000 && !it.name.startsWith(".") }.forEach { f ->
            if (f.isFile && f.name.endsWith(".lrc", ignoreCase = true)) out.putIfAbsent(norm(f.name.substringBeforeLast('.')), f)
        }
        return out
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // ---------- LRCLIB ----------

    private fun fetchLrclib(song: Song): String? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val seconds = song.durationMs / 1000
        val exact = httpGet(
            "https://lrclib.net/api/get?artist_name=${enc(song.artist)}&track_name=${enc(song.title)}" +
                "&album_name=${enc(song.album)}&duration=$seconds",
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
        exact?.let { pick(it) }?.let { return it }

        val results = httpGet("https://lrclib.net/api/search?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}")
            ?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
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
            conn.setRequestProperty("User-Agent", "Spitify/1.0 (desktop local music player)")
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- cache persistence ----------

    private fun loadCache(): Map<Long, LyricsEntity> {
        val arr = store.readArray() ?: return emptyMap()
        val out = HashMap<Long, LyricsEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                LyricsEntity(
                    o.getLong("songId"), o.optStringOrNull("synced"), o.optStringOrNull("plain"), o.optString("source", LyricsSource.Embedded.name),
                    o.optInt("offsetMs"), o.optLong("fetchedAt"), o.optBoolean("notFound"),
                )
            }.getOrNull()?.let { out[it.songId] = it }
        }
        return out
    }

    private fun persist() = store.save {
        JSONArray().apply {
            cache.values.forEach { e ->
                put(JSONObject().apply {
                    put("songId", e.songId); e.synced?.let { put("synced", it) }; e.plain?.let { put("plain", it) }
                    put("source", e.source); put("offsetMs", e.offsetMs); put("fetchedAt", e.fetchedAt); put("notFound", e.notFound)
                })
            }
        }.toString()
    }

    companion object {
        private const val KEY_ONLINE = "online"
        private const val KEY_FOLDER = "folder"
    }
}

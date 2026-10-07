package com.localfy.app.data.art

import com.localfy.app.data.PrefsFile
import com.localfy.app.data.writeAtomically
import com.localfy.app.desktop.AppPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds cover art for albums whose files have none embedded: Deezer first (1000×1000 covers),
 * then the iTunes Search API. Results are cached under `<cache>/art/`, and misses are remembered
 * for a week so we don't keep asking. Only the artist and album names are sent.
 */
class OnlineArtRepository(
    cacheDir: File = AppPaths.cacheDir,
    dataDir: File = AppPaths.dataDir,
) {
    var onDownloaded: (suspend (Long, File) -> Unit)? = null
    private val prefs = PrefsFile(File(dataDir, "prefs-online_art.json"))
    private val dir = File(cacheDir, "art").apply { mkdirs() }
    private val inFlight = ConcurrentHashMap<Long, CompletableDeferred<File?>>()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit { putBoolean(KEY_ENABLED, on) }
    }

    fun cached(albumId: Long): File? = File(dir, "$albumId.jpg").takeIf { it.isFile && it.length() > 0 }

    fun downloadedCount(): Int = dir.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit { prefs.keys().filter { it.startsWith("miss_") }.forEach { remove(it) } }
    }

    /** Returns the cached file, fetching it first if needed (network on Dispatchers.IO). */
    suspend fun fetch(albumId: Long, artist: String, album: String): File? {
        cached(albumId)?.let { return it }
        if (!_enabled.value || !worthLookingUp(artist, album)) return null
        val missAt = prefs.getLong("miss_$albumId", 0)
        if (System.currentTimeMillis() - missAt < WEEK_MS) return null

        val mine = CompletableDeferred<File?>()
        val existing = inFlight.putIfAbsent(albumId, mine)
        if (existing != null) return existing.await()
        try {
            val result = withContext(Dispatchers.IO) { runCatching { lookup(artist, album)?.let { download(it, albumId) } }.getOrNull() }
            if (result == null) prefs.edit { putLong("miss_$albumId", System.currentTimeMillis()) }
            if (result != null) runCatching { onDownloaded?.invoke(albumId, result) }
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(albumId)
        }
    }

    private fun worthLookingUp(artist: String, album: String): Boolean {
        val a = artist.lowercase(); val b = album.lowercase()
        if (a.startsWith("unknown") || b.startsWith("unknown") || b.isBlank()) return false
        // Untagged files get their folder name as the album - not worth a lookup.
        return b !in setOf("download", "downloads", "music", "audio", "recordings", "whatsapp audio", "telegram", "desktop", "documents", "itunes", "spitify")
    }

    private fun lookup(artist: String, album: String): String? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        // Deezer
        get("https://api.deezer.com/search/album?limit=8&q=${enc("$artist $album")}")?.let { body ->
            val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            if (data != null) for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                if (matches(o.optString("title"), album) && matches(o.optJSONObject("artist")?.optString("name").orEmpty(), artist)) {
                    o.optString("cover_xl").takeIf { it.startsWith("http") }?.let { return it }
                }
            }
        }
        // iTunes
        get("https://itunes.apple.com/search?entity=album&limit=10&term=${enc("$artist $album")}")?.let { body ->
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            if (results != null) for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                if (matches(o.optString("collectionName"), album) && matches(o.optString("artistName"), artist)) {
                    o.optString("artworkUrl100").takeIf { it.startsWith("http") }?.let { return it.replace("100x100bb", "1000x1000bb") }
                }
            }
        }
        return null
    }

    /** Loose match: accents/case/punctuation ignored, and "(Deluxe Edition)"-style suffixes allowed. */
    private fun matches(candidate: String, wanted: String): Boolean {
        val c = norm(candidate); val w = norm(wanted)
        if (c.isEmpty() || w.isEmpty()) return false
        return c == w || c.startsWith(w) || w.startsWith(c)
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun download(url: String, albumId: Long): File? {
        val bytes = CoverDownload.load(url)
        if (bytes.size < 1024) return null
        val out = File(dir, "$albumId.jpg")
        writeAtomically(out, bytes)
        return out
    }

    private fun get(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Spitify/1.0 (desktop local music player)")
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}

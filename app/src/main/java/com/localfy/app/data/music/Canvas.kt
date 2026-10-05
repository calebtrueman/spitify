package com.localfy.app.data.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Canvas: a short, silent loop from the song's official music video behind the player.
 * Clips come from Apple's public iTunes Search API (music-video previews, 1080p for most
 * modern videos), so they're tied to the exact song and play natively, no web view.
 */
object CanvasLookup {
    /** Part of the 30 s preview used as the loop (previews already start inside the video). */
    const val LOOP_START_MS = 6_000L
    const val LOOP_END_MS = 16_000L
    private const val HIT_TTL = 30L * 86_400_000
    private const val MISS_TTL = 86_400_000L

    private val memory = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 200
    }

    /** Words that mark a different recording or a non-video upload. */
    private val variants = setOf("live", "remix", "acoustic", "lyric", "lyrics", "karaoke", "instrumental", "cover", "sped", "slowed", "visualizer", "session", "sessions", "version", "demo")

    private fun clean(text: String): String = SearchMatch.fold(
        text.replace(Regex("\\((feat|ft|with)\\.?[^)]*\\)|\\[(feat|ft|with)\\.?[^]]*]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(feat|ft|featuring)\\b.*$", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\((official|music|video|hd|4k|remaster(ed)?|\\d{4} remaster(ed)?)[^)]*\\)", RegexOption.IGNORE_CASE), " "),
    ).replace(Regex("\\s+"), " ").trim()

    /** Same song by the same artist, and not a live/remix/lyric variant the song itself isn't. */
    fun matches(title: String, artist: String, foundTitle: String, foundArtist: String): Boolean {
        val wanted = clean(title); val found = clean(foundTitle)
        if (wanted.isEmpty() || found.isEmpty()) return false
        val wantedWords = wanted.split(' ').toSet(); val foundWords = found.split(' ').toSet()
        if ((foundWords intersect variants).any { it !in wantedWords }) return false
        // "One More Time (Radio Edit)" / "Song - 2009 Remaster" still match the video "One More Time".
        fun core(text: String) = clean(text.substringBefore(" - ").substringBefore('(').substringBefore('['))
        if (found != wanted && core(foundTitle) != core(title)) return false
        val primary = clean(artist.split(';', ',', '&').first())
        val credited = clean(foundArtist)
        return primary.isNotEmpty() && (credited == primary || credited.split(" ", "&", ",").filter { it.isNotBlank() }.containsAll(primary.split(' ')))
    }

    private fun key(title: String, artist: String) = clean(title) + "|" + clean(artist.split(';', ',', '&').first())

    /** The preview URL for this song's music video, or null when it has none. Never throws. */
    suspend fun find(context: Context, title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val key = key(title, artist)
        synchronized(memory) { memory[key] }?.let { return@withContext it.ifEmpty { null } }
        val prefs = context.getSharedPreferences("canvas", Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { saved ->
            val at = saved.substringBefore(' ').toLongOrNull() ?: 0
            val url = saved.substringAfter(' ', "")
            if (System.currentTimeMillis() - at < if (url.isEmpty()) MISS_TTL else HIT_TTL) {
                synchronized(memory) { memory[key] = url }
                return@withContext url.ifEmpty { null }
            }
        }
        val url = try { search(title, artist) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@withContext null // offline: try again next time instead of remembering a miss
        }
        synchronized(memory) { memory[key] = url.orEmpty() }
        prefs.edit().putString(key, "${System.currentTimeMillis()} ${url.orEmpty()}").apply()
        url
    }

    private fun search(title: String, artist: String): String? {
        val term = URLEncoder.encode("${artist.split(';', ',').first()} ${clean(title)}", "UTF-8")
        val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val connection = URI("https://itunes.apple.com/search?term=$term&entity=musicVideo&limit=15&country=$country").toURL().openConnection() as HttpURLConnection
        val body = try {
            connection.connectTimeout = 8_000; connection.readTimeout = 10_000
            check(connection.responseCode == 200) { "Canvas search failed (${connection.responseCode})" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val hits = (0 until results.length()).map { results.getJSONObject(it) }.filter {
            it.optString("previewUrl").startsWith("https://") && matches(title, artist, it.optString("trackName"), it.optString("artistName"))
        }
        // Prefer the HD encodes ("…1920w…") over older 640×480 ones.
        return hits.sortedByDescending { if ("1920w" in it.optString("previewUrl")) 1 else 0 }.firstOrNull()?.optString("previewUrl")
    }
}

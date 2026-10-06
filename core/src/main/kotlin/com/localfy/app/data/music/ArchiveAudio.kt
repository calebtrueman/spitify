package com.localfy.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/** Only public files from an album with matching artist and title may enter the queue. */
object ArchiveAudio {
    data class Candidate(val url: String, val title: String, val durationMs: Long?, val ext: String)
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, List<Candidate>>>()
    fun albumName(value: String): String = SearchMatch.fold(value.replace(
        Regex("\\s*[\\(\\[](?:(?:19|20)\\d{2}|bonus track version|deluxe(?: edition| version)?|special version)[\\)\\]]", RegexOption.IGNORE_CASE), ""))
    fun songName(value: String, artist: String): String {
        val folded = SearchMatch.fold(value.replace(Regex("^\\s*\\d{1,3}[.\\s_-]+"), ""))
        return folded.removePrefix(SearchMatch.fold(artist) + " ")
    }
    private fun validID(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}").matches(value) && value != ".."
    fun validURL(value: String): Boolean = runCatching {
        val u = URI(value); val parts = u.path.split('/').filter { it.isNotEmpty() }
        u.scheme == "https" && u.host == "archive.org" && u.userInfo == null && u.query == null && u.fragment == null &&
            parts.size >= 3 && parts[0] == "download" && validID(parts[1]) && ".." !in parts &&
            u.path.substringAfterLast('.').lowercase() in listOf("flac", "m4a", "mp3")
    }.getOrDefault(false)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun strings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).map { value.optString(it) }
        else -> emptyList()
    }
    private fun restricted(value: Any?) = value == true || value?.toString()?.lowercase() in listOf("true", "1")
    private fun get(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 25_000
            check(c.responseCode == 200) { "The backup catalogue is unavailable right now." }
            val data = c.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 5_000_000) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(data.size <= 5_000_000) { "The backup catalogue response is too large." }
            return data.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }
    suspend fun resolve(track: OnlineTrack): OnlineTrack? = withContext(Dispatchers.IO) {
        if (track.durationMs <= 0 || track.album.isBlank()) return@withContext null
        val key = SearchMatch.fold(track.artist) + "|" + albumName(track.album)
        val choices = lock.withLock {
            cache[key]?.takeIf { System.currentTimeMillis() - it.first < 900_000 }?.second ?: lookup(track).also {
                if (cache.size > 40) cache.clear()
                cache[key] = System.currentTimeMillis() to it
            }
        }
        coroutineContext.ensureActive()
        val choice = choices.firstOrNull {
            it.url !in track.attemptedSources && songName(it.title, track.artist) == SearchMatch.fold(track.title) &&
                (it.durationMs == null || kotlin.math.abs(it.durationMs - track.durationMs) <= 3000)
        } ?: return@withContext null
        track.copy(audioURL = choice.url, audioExtension = choice.ext, playable = true, fallbackTried = false,
            attemptedSources = (track.attemptedSources + choice.url).distinct(), retryCount = 0, retryAtMillis = 0)
    }
    private suspend fun lookup(track: OnlineTrack): List<Candidate> {
        val artist = SearchMatch.fold(track.artist); val album = albumName(track.album)
        if (artist.isEmpty() || album.isEmpty()) return emptyList()
        val query = "mediatype:audio AND creator:($artist) AND title:($album)"
        val docs = JSONObject(get("https://archive.org/advancedsearch.php?q=${encode(query)}&output=json&rows=12&fl%5B%5D=identifier,title,creator"))
            .optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val result = mutableListOf<Candidate>()
        for (i in 0 until docs.length()) {
            coroutineContext.ensureActive()
            val doc = docs.getJSONObject(i); val id = doc.optString("identifier")
            if (!validID(id) || strings(doc.opt("creator")).none { SearchMatch.fold(it) == artist }) continue
            if (albumName(doc.optString("title")) !in listOf(album, "$artist $album")) continue
            val item = JSONObject(get("https://archive.org/metadata/$id")); val metadata = item.optJSONObject("metadata") ?: continue
            if (restricted(item.opt("is_dark")) || restricted(item.opt("is_restricted")) || restricted(metadata.opt("access-restricted-item")) ||
                strings(metadata.opt("creator")).none { SearchMatch.fold(it) == artist }) continue
            val files = item.optJSONArray("files") ?: continue
            val publicFiles = (0 until files.length()).map { files.getJSONObject(it) }.filter { !restricted(it.opt("private")) }
            val base = "https://archive.org/download/$id/"
            for (file in publicFiles) {
                val name = file.optString("name"); val ext = name.substringAfterLast('.').lowercase()
                if (ext !in listOf("flac", "m4a", "mp3")) continue
                val url = base + encode(name)
                if (!validURL(url)) continue
                result.add(Candidate(url, file.optString("title").ifEmpty { name.substringAfterLast('/').substringBeforeLast('.') },
                    file.optString("length").toDoubleOrNull()?.times(1000)?.toLong(), ext))
            }
            if (result.isEmpty()) {
                publicFiles.firstOrNull { it.optString("name").lowercase().endsWith(".zip") }?.let { zip ->
                    val prefix = base + encode(zip.getString("name")) + "/"
                    val html = get(prefix)
                    for (match in Regex("href=\"(//archive\\.org/download/[^\"]+)\"").findAll(html)) {
                        val url = "https:" + match.groupValues[1].replace("&amp;", "&")
                        if (!url.startsWith(prefix) || !validURL(url)) continue
                        val name = URI(url).path.substringAfterLast('/')
                        result.add(Candidate(url, name.substringBeforeLast('.'), null, name.substringAfterLast('.').lowercase()))
                    }
                }
            }
            if (result.isNotEmpty()) break
        }
        return result.sortedBy { if (it.ext == "flac") 0 else 1 }
    }
}

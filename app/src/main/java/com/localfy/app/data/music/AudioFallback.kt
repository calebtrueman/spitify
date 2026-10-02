package com.localfy.app.data.music

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs on the phone. Uses only public, directly readable audio; never handles login challenges. */
object AudioFallback {
    private const val VERSION = "2.20260708.00.00"
    fun matches(track: OnlineTrack, title: String, author: String, durationMs: Long): Boolean {
        if (track.durationMs <= 0 || kotlin.math.abs(track.durationMs - durationMs) > 3_000) return false
        fun clean(value: String) = SearchMatch.fold(value).replace(Regex("\\b(feat|ft|featuring)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val wanted = clean(track.title); val found = clean(title)
        val artist = clean(track.artist.split(';', ',').first())
        val changed = listOf("live", "cover", "remix", "slowed", "sped", "nightcore", "instrumental", "karaoke", "432hz", "528hz", "clean")
        if (changed.any { found.split(' ').contains(it) && !wanted.split(' ').contains(it) }) return false
        return artist.isNotBlank() && (clean(author).contains(artist) || found.contains(artist)) && wanted.split(' ').all { found.split(' ').contains(it) }
    }
    fun validAudioURL(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host?.endsWith(".googlevideo.com") == true && u.userInfo == null
    }.getOrDefault(false)

    suspend fun resolve(track: OnlineTrack): OnlineTrack? = withContext(Dispatchers.IO) {
        val search = request("search", JSONObject().put("query", "${track.artist} ${track.title} official audio"))
        val candidates = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> { value.optJSONObject("videoRenderer")?.let(candidates::add); value.keys().forEach { visit(value.opt(it)) } }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(search)
        fun text(o: JSONObject?) = o?.optString("simpleText")?.takeIf { it.isNotBlank() } ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }.orEmpty()
        val match = candidates.firstOrNull { v ->
            val duration = text(v.optJSONObject("lengthText")).split(':').fold(0L) { sum, part -> sum * 60 + (part.toLongOrNull() ?: 0) } * 1000
            matches(track, text(v.optJSONObject("title")), text(v.optJSONObject("ownerText")), duration)
        } ?: return@withContext null
        val id = match.optString("videoId")
        if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return@withContext null
        val player = request("player", JSONObject().put("videoId", id))
        if (player.optJSONObject("playabilityStatus")?.optString("status") != "OK") return@withContext null
        val details = player.optJSONObject("videoDetails") ?: return@withContext null
        if (!matches(track, details.optString("title"), details.optString("author"), details.optLong("lengthSeconds") * 1000)) return@withContext null
        val formats = player.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return@withContext null
        val audio = (0 until formats.length()).map { formats.getJSONObject(it) }
            .filter { it.optString("mimeType").startsWith("audio/mp4") && validAudioURL(it.optString("url")) }
            .maxByOrNull { it.optInt("bitrate") } ?: return@withContext null
        track.copy(playable = true, audioURL = audio.getString("url"), audioExtension = "m4a", fallbackTried = true)
    }

    private fun request(path: String, body: JSONObject): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", VERSION)))
        val c = URI("https://www.youtube.com/youtubei/v1/$path").toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            check(c.responseCode == 200) { "Could not check another recording. Please try again later." }
            val data = c.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 5_000_000) { "The audio search response was too large." }
                    output.write(buffer, 0, count)
                }; output.toByteArray()
            }
            check(data.size <= 5_000_000) { "The audio search response was too large." }
            return JSONObject(data.toString(Charsets.UTF_8))
        } finally { c.disconnect() }
    }
}

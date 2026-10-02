package com.localfy.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class OnlineTrack(
    val id: String, val title: String, val artist: String, val album: String,
    val releaseId: String, val durationMs: Long, val track: Int, val disc: Int,
    val artwork: String?, val playable: Boolean,
) {
    fun json(): String = JSONObject().apply {
        put("trackId", id); put("title", title); put("artistNames", org.json.JSONArray(listOf(artist)))
        put("albumTitle", album); put("releaseId", releaseId); put("duration", durationMs)
        put("trackNumber", track); put("discNumber", disc); put("artwork", artwork); put("playable", playable)
    }.toString()
}

data class OnlineAlbum(val id: String, val title: String, val artist: String)

object Monochrome {
    const val BASE = "https://tracks.monochrome.st"
    fun validId(id: String) = id.isNotEmpty() && id.all { it in '0'..'9' }
    fun audioUrl(id: String): String {
        require(validId(id)) { "This song has an invalid source ID." }
        return "$BASE/track/$id"
    }

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI("$BASE/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            check(code == 200) {
                when (code) {
                    401, 403, 428 -> "Monochrome is asking for access approval. Try its website."
                    429 -> "Monochrome is busy. Wait a little before retrying."
                    404 -> "This item is no longer available."
                    else -> "Monochrome could not complete the request ($code)."
                }
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    suspend fun search(query: String): List<OnlineTrack> {
        val items = get("search/tracks?q=${URLEncoder.encode(query, "UTF-8")}&limit=30").getJSONArray("tracks")
        return (0 until items.length()).mapNotNull { parseTrack(items.getJSONObject(it)) }
    }

    suspend fun albums(query: String): List<OnlineAlbum> {
        val items = get("search/releases?q=${URLEncoder.encode(query, "UTF-8")}&limit=30").getJSONArray("releases")
        return (0 until items.length()).mapNotNull {
            val item = items.getJSONObject(it)
            val id = item.optString("releaseId", item.optString("id"))
            if (!validId(id)) null else OnlineAlbum(id, item.optString("title", "Unknown album"), artist(item))
        }
    }

    suspend fun album(id: String): List<OnlineTrack> {
        require(validId(id))
        val album = get("releases/$id")
        val tracks = album.getJSONArray("tracks")
        return (0 until tracks.length()).mapNotNull { parseTrack(tracks.getJSONObject(it), album) }
            .sortedWith(compareBy({ it.disc }, { it.track }))
    }

    fun parseTrack(item: JSONObject, album: JSONObject? = null): OnlineTrack? {
        val id = item.optString("trackId", item.optString("id"))
        if (!validId(id) || !item.has("title")) return null
        val artist = artist(item).let { if (it == "Unknown artist" && album != null) artist(album) else it }
        return OnlineTrack(id, item.getString("title"), artist, album?.optString("title") ?: item.optString("albumTitle"),
            item.optString("releaseId", album?.optString("releaseId") ?: ""), item.optLong("duration"),
            item.optInt("trackNumber", 0), item.optInt("discNumber", 1),
            item.optString("artwork", album?.optString("artwork") ?: "").takeIf { it.startsWith("https://") },
            item.optBoolean("playable", true))
    }

    private fun artist(item: JSONObject): String {
        val names = item.optJSONArray("artistNames")
        if (names != null && names.length() > 0) return (0 until names.length()).joinToString(", ") { names.getString(it) }
        val artists = item.optJSONArray("artists") ?: return "Unknown artist"
        return (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name", "Unknown artist") }.ifEmpty { "Unknown artist" }
    }
}

data class FlacInfo(val sampleRate: Int, val bits: Int, val durationMs: Long) {
    val label: String get() = "FLAC · $bits-bit · ${sampleRate / 1000.0} kHz"
    companion object {
        fun read(file: File, expectedDurationMs: Long = 0): FlacInfo = RandomAccessFile(file, "r").use { input ->
            check(input.length() > 42 && input.readInt() == 0x664C6143) { "The download is not a complete FLAC audio file." }
            var info: FlacInfo? = null
            var last = false
            while (!last) {
                val header = input.readInt()
                last = header < 0
                val type = (header ushr 24) and 0x7f
                val length = header and 0xffffff
                val offset = input.filePointer
                check(offset + length <= input.length()) { "The audio download is incomplete." }
                if (info == null) {
                    check(type == 0 && length == 34) { "The audio header is invalid." }
                    input.skipBytes(10)
                    val packed = input.readLong()
                    val rate = (packed ushr 44).toInt()
                    val bits = ((packed ushr 36) and 31).toInt() + 1
                    val samples = packed and 0xFFFFFFFFFL
                    check(rate > 0 && samples > 0) { "The audio file has no duration." }
                    info = FlacInfo(rate, bits, samples * 1000 / rate)
                }
                input.seek(offset + length)
            }
            check(input.filePointer < input.length()) { "The download contains no audio." }
            val result = checkNotNull(info)
            check(expectedDurationMs <= 0 || kotlin.math.abs(result.durationMs - expectedDurationMs) <= 5000) { "The song's length does not match. It was not added." }
            result
        }
    }
}

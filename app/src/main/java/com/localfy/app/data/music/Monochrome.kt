package com.localfy.app.data.music

import com.localfy.app.data.ArtistCredits
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
    val artwork: String?, val playable: Boolean, val albumArtist: String? = null,
    val audioURL: String? = null, val audioExtension: String = "flac", val fallbackTried: Boolean = false, val attemptedSources: List<String> = emptyList(), val retryCount: Int = 0, val retryAtMillis: Long = 0, val explicit: Boolean? = null,
    val artistNames: List<String>? = null,
) {
    val primaryArtist: String get() = ArtistCredits.names(artist, artistNames, albumArtist).firstOrNull() ?: artist
    fun json(): String = JSONObject().apply {
        put("trackId", id); put("title", title); put("artistNames", org.json.JSONArray(artistNames ?: listOf(artist))); put("separateArtistNames", artistNames != null)
        put("albumTitle", album); put("releaseId", releaseId); put("duration", durationMs)
        put("explicit", explicit)
        put("retryCount", retryCount); put("retryAtMillis", retryAtMillis); put("attemptedSources", org.json.JSONArray(attemptedSources)); put("audioURL", audioURL); put("audioExtension", audioExtension); put("fallbackTried", fallbackTried); put("albumArtist", albumArtist); put("trackNumber", track); put("discNumber", disc); put("artwork", artwork); put("playable", playable)
    }.toString()
}

data class OnlineAlbum(val id: String, val title: String, val artist: String, val artwork: String? = null, val explicit: Boolean? = null, val releaseDate: String? = null)
data class OnlineArtist(val id: String, val name: String, val artwork: String? = null)
data class OnlineSearch(val tracks: List<OnlineTrack> = emptyList(), val albums: List<OnlineAlbum> = emptyList(), val artists: List<OnlineArtist> = emptyList())

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
                    401, 403, 428 -> "Online search is temporarily unavailable. Please try again later."
                    429 -> "Online search is busy. Wait a little before retrying."
                    404 -> "This item is no longer available."
                    else -> "Online search could not complete the request ($code)."
                }
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    suspend fun search(query: String): List<OnlineTrack> {
        val items = get("search/tracks?q=${URLEncoder.encode(query, "UTF-8")}&limit=30").getJSONArray("tracks")
        return (0 until items.length()).mapNotNull { parseTrack(items.getJSONObject(it)) }
    }

    suspend fun searchAll(query: String): OnlineSearch {
        val obj = get("search?q=${URLEncoder.encode(query, "UTF-8")}&limit=30")
        fun rows(key: String): List<JSONObject> = obj.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        return OnlineSearch(rows("tracks").mapNotNull { parseTrack(it) }, rows("releases").mapNotNull { item ->
            val id = item.optString("releaseId", item.optString("id"))
            if (!validId(id)) null else OnlineAlbum(id, item.optString("title"), artist(item), item.optString("artwork").takeIf { it.startsWith("https://") }, item.flag("explicit"))
        }, rows("artists").mapNotNull { item ->
            val id = item.optString("artistId", item.optString("id"))
            if (!validId(id)) null else OnlineArtist(id, item.optString("displayName", item.optString("name")), item.optString("avatar").takeIf { it.startsWith("https://") })
        })
    }

    suspend fun artistPage(id: String): OnlineSearch {
        require(validId(id))
        val obj = get("artists/$id")
        fun rows(key: String) = obj.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        return OnlineSearch(rows("topTracks").mapNotNull { parseTrack(it) }, listOf("releases", "albums", "singles", "compilations").flatMap { rows(it) }.distinctBy { it.optString("releaseId", it.optString("id")) }.mapNotNull { item ->
            val release = item.optString("releaseId", item.optString("id"))
            if (!validId(release)) null else OnlineAlbum(release, item.optString("title"), artist(item), item.optString("artwork").takeIf { it.startsWith("https://") }, item.flag("explicit"), item.optString("releaseDate").takeIf { it.isNotBlank() })
        })
    }

    private fun JSONObject.flag(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null

    suspend fun albums(query: String): List<OnlineAlbum> {
        val items = get("search/releases?q=${URLEncoder.encode(query, "UTF-8")}&limit=30").getJSONArray("releases")
        return (0 until items.length()).mapNotNull {
            val item = items.getJSONObject(it)
            val id = item.optString("releaseId", item.optString("id"))
            if (!validId(id)) null else OnlineAlbum(id, item.optString("title", "Unknown album"), artist(item), item.optString("artwork").takeIf { it.startsWith("https://") }, item.flag("explicit"))
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
        val names = artistNames(item).ifEmpty { album?.let(::artistNames).orEmpty() }
        // Old saved entries flattened the whole credit into one array item.
        val structuredNames = names.takeUnless { names.size == 1 && item.has("audioExtension") && !item.optBoolean("separateArtistNames", false) }
        return OnlineTrack(id, item.getString("title"), artist, album?.optString("title") ?: item.optString("albumTitle"),
            item.optString("releaseId", album?.optString("releaseId") ?: ""), item.optLong("duration"),
            item.optInt("trackNumber", 0), item.optInt("discNumber", 1),
            item.optString("artwork", album?.optString("artwork") ?: "").takeIf { it.startsWith("https://") },
            item.optBoolean("playable", true),
            item.optString("albumArtist").takeIf { it.isNotBlank() && it != "null" }
                ?: album?.let { artistNames(it).firstOrNull() },
            item.optString("audioURL").takeIf { AudioFallback.validAudioURL(it) },
            item.optString("audioExtension").takeIf { it in listOf("m4a", "mp3", "opus", "ogg", "aac", "wav", "aiff") } ?: "flac", item.optBoolean("fallbackTried", false),
            item.optJSONArray("attemptedSources")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), item.optInt("retryCount", 0), item.optLong("retryAtMillis", 0), item.flag("explicit"), structuredNames?.takeIf { it.isNotEmpty() })
    }

    private fun artistNames(item: JSONObject): List<String> {
        val names = item.optJSONArray("artistNames")
        if (names != null && names.length() > 0) return (0 until names.length()).map { names.optString(it).trim() }.filter { it.isNotEmpty() }
        val artists = item.optJSONArray("artists") ?: return emptyList()
        return (0 until artists.length()).map { artists.getJSONObject(it).let { artist -> artist.optString("name", artist.optString("displayName")).trim() } }.filter { it.isNotEmpty() }
    }
    private fun artist(item: JSONObject): String = artistNames(item).joinToString(", ").ifEmpty { "Unknown artist" }

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

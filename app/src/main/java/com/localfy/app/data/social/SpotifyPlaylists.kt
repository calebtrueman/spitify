package com.localfy.app.data.social

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

data class SpotifyPlaylistResult(val id: String, val name: String, val description: String, val image: String?, val owner: String)

object SpotifyPlaylists {
    const val SERVICE = "https://spotify.xwolf.space/api"
    fun playlistID(input: String): String? = runCatching {
        val text = input.trim()
        val candidate = when {
            text.startsWith("spotify:playlist:") -> text.removePrefix("spotify:playlist:")
            text.startsWith("https://") -> {
                val uri = URI(text); require(uri.host == "open.spotify.com" && uri.userInfo == null)
                val parts = uri.path.split('/').filter(String::isNotEmpty); val index = parts.indexOf("playlist")
                require(index >= 0 && parts.size == index + 2); parts[index + 1]
            }
            else -> text
        }
        candidate.takeIf { it.matches(Regex("[a-zA-Z0-9]{22}")) }
    }.getOrNull()
    fun plain(value: String) = value.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000
            connection.setRequestProperty("User-Agent", "Spitify/1.0")
            check(connection.responseCode == 200) { "The playlist service is unavailable. Try a public Spotify playlist link." }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16_384)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 8_000_000) { "This playlist response is too large." }
                    output.write(buffer, 0, count)
                }
            }
            output.toString("UTF-8")
        } finally { connection.disconnect() }
    }
    suspend fun search(query: String): List<SpotifyPlaylistResult> {
        val obj = JSONObject(get("$SERVICE/search?q=${URLEncoder.encode(query.take(200), "UTF-8")}&type=playlist&limit=20"))
        check(obj.optBoolean("success")) { "Playlist search is unavailable. You can still paste a Spotify playlist link." }
        return obj.objects("results") { row -> SpotifyPlaylistResult(row.getString("id"), row.getString("name").take(200), plain(row.optString("description")), row.text("thumbnail")?.takeIf(SocialRules::publicURL), row.optString("owner", "Spotify")) }.filter { playlistID(it.id) != null }.take(50)
    }
    suspend fun load(input: String, owner: String): SharedPlaylist {
        val id = playlistID(input) ?: error("Paste a public Spotify playlist link.")
        return try {
            val obj = JSONObject(get("$SERVICE/playlist/$id"))
            check(obj.optBoolean("success")); parse(obj.getJSONObject("playlist"), id, owner)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            parseEmbed(get("https://open.spotify.com/embed/playlist/$id"), id, owner)
        }
    }
    fun parse(obj: JSONObject, id: String, owner: String): SharedPlaylist {
        val rows = obj.getJSONArray("tracks")
        val tracks = (0 until minOf(rows.length(), 2000)).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            SharedTrack(id = "spotify:$id:$index", title = row.optString("title"), artist = row.optString("artist"), album = row.optString("album"), durationMs = row.optLong("duration_ms"), spotifyID = row.text("id")).takeIf { it.valid() }
        }
        val count = if (obj.has("total_tracks") && !obj.isNull("total_tracks")) obj.getInt("total_tracks") else null
        val result = SharedPlaylist(id = "spotify-$id", owner = owner, name = obj.getString("name").take(200), description = plain(obj.optString("description")).take(4000), image = obj.text("thumbnail")?.takeIf(SocialRules::publicURL), sourceURL = "https://open.spotify.com/playlist/$id", sourceName = "Spotify", sourceCount = count, partial = count == null || count != tracks.size || rows.length() != tracks.size, tracks = tracks)
        check(result.valid()) { "That playlist contains invalid details." }; return result
    }
    fun parseEmbed(html: String, id: String, owner: String): SharedPlaylist {
        val data = Regex("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: error("This playlist is private, unavailable, or its page has changed.")
        val entity = JSONObject(data).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        check(entity.optString("type") == "playlist" && entity.optString("id") == id)
        val description = entity.objects("attributes") { it }.firstOrNull { it.optString("key") == "episode_description" }?.optString("value").orEmpty()
        val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.text("url")
        val rows = entity.objects("trackList") { row -> JSONObject().put("title", row.optString("title")).put("artist", row.optString("subtitle")).put("duration_ms", row.optLong("duration")).put("id", row.optString("uri").substringAfterLast(':')) }
        // The embed does not prove the total. Always retain the partial warning.
        return parse(JSONObject().put("name", entity.optString("name", entity.optString("title", "Spotify playlist"))).put("description", description).put("thumbnail", cover).put("tracks", JSONArray(rows)), id, owner)
    }
}

package com.localfy.app.data.social

import com.localfy.app.data.Song
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotEmpty() && it != "null" }
fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
fun <T> JSONObject.objects(key: String, parse: (JSONObject) -> T): List<T> = optJSONArray(key)?.let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } } ?: emptyList()

data class SharedTrack(
    val id: String = UUID.randomUUID().toString(), val title: String, val artist: String, val album: String = "", val durationMs: Long = 0,
    val sourceID: String? = null, val releaseID: String? = null, val spotifyID: String? = null, val isrc: String? = null, val artwork: String? = null,
) {
    val recordingKey: String get() = Normalizer.normalize(isrc?.takeIf { it.isNotBlank() } ?: "$title|$artist", Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
    fun valid() = id.isNotEmpty() && id.length <= 160 && title.isNotBlank() && title.length <= 500 && artist.isNotBlank() && artist.length <= 500 && album.length <= 500 && durationMs in 0..86_400_000 && (sourceID == null || sourceID.length in 1..30 && sourceID.all { it in '0'..'9' }) && (artwork == null || SocialRules.publicURL(artwork))
    fun json() = JSONObject().put("id", id).put("title", title).put("artist", artist).put("album", album).put("durationMs", durationMs).put("sourceID", sourceID).put("releaseID", releaseID).put("spotifyID", spotifyID).put("isrc", isrc).put("artwork", artwork)
    companion object {
        fun parse(o: JSONObject) = SharedTrack(o.getString("id"), o.getString("title"), o.getString("artist"), o.optString("album"), o.optLong("durationMs"), o.text("sourceID"), o.text("releaseID"), o.text("spotifyID"), o.text("isrc"), o.text("artwork"))
        /** [online] is the catalogue track behind a streamed song, if any. */
        fun from(song: Song, online: com.localfy.app.data.music.OnlineTrack?): SharedTrack {
            return SharedTrack(title = song.title, artist = song.artist, album = song.album, durationMs = song.durationMs, sourceID = online?.id?.takeIf(com.localfy.app.data.music.Monochrome::validId), releaseID = online?.releaseId, artwork = song.artUrl?.takeIf(SocialRules::publicURL))
        }
    }
}

data class SharedPlaylist(
    val id: String = UUID.randomUUID().toString(), val owner: String, val name: String, val description: String = "", val image: String? = null,
    val sourceURL: String? = null, val sourceName: String? = null, val sourceCount: Int? = null, val partial: Boolean = false, val kind: String = "playlist",
    val editors: List<String> = emptyList(), val tracks: List<SharedTrack> = emptyList(), val revision: Long = 1, val updatedAt: Long = SocialRules.now, val isPublic: Boolean = false,
) {
    val key get() = "$owner:$id"
    fun valid() = SocialRules.key(owner) && id.length in 1..100 && name.length in 1..200 && description.length <= 4000 && tracks.size <= 2000 && tracks.map { it.id }.distinct().size == tracks.size && tracks.all { it.valid() } && editors.size <= 32 && editors.all(SocialRules::key) && revision in 1 until 9_000_000_000_000_000L && kind in listOf("playlist", "album", "song", "mix") && (image == null || SocialRules.publicURL(image)) && (sourceURL == null || SocialRules.publicURL(sourceURL))
    fun json() = JSONObject().put("id", id).put("owner", owner).put("name", name).put("description", description).put("image", image).put("sourceURL", sourceURL).put("sourceName", sourceName).put("sourceCount", sourceCount).put("partial", partial).put("kind", kind).put("editors", JSONArray(editors)).put("tracks", JSONArray(tracks.map { it.json() })).put("revision", revision).put("updatedAt", updatedAt).put("isPublic", isPublic)
    companion object {
        fun parse(o: JSONObject) = SharedPlaylist(o.getString("id"), o.getString("owner"), o.getString("name"), o.optString("description"), o.text("image"), o.text("sourceURL"), o.text("sourceName"), if (o.has("sourceCount") && !o.isNull("sourceCount")) o.getInt("sourceCount") else null, o.optBoolean("partial"), o.optString("kind", "playlist"), o.strings("editors"), o.objects("tracks", SharedTrack::parse), o.optLong("revision", 1), o.optLong("updatedAt"), o.optBoolean("isPublic"))
    }
}

data class FriendProfile(val id: String, val name: String, val about: String = "", val image: String? = null, val updatedAt: Long = SocialRules.now, val photo: String? = null, val isPublic: Boolean = true, val photoHD: String? = null) {
    fun valid() = (photoHD == null || photoHD.length <= 24000 && runCatching { java.util.Base64.getDecoder().decode(photoHD) }.isSuccess) && (photo == null || photo.length <= 22000 && runCatching { java.util.Base64.getDecoder().decode(photo) }.isSuccess) && SocialRules.key(id) && name.length in 1..80 && about.length <= 500 && (image == null || SocialRules.publicURL(image))
    fun json() = JSONObject().put("id", id).put("name", name).put("about", about).put("image", image).put("updatedAt", updatedAt).put("photo", photo).put("photoHD", photoHD).put("isPublic", isPublic)
    companion object { fun parse(o: JSONObject) = FriendProfile(o.getString("id"), o.getString("name"), o.optString("about"), o.text("image"), o.optLong("updatedAt"), o.text("photo"), o.optBoolean("isPublic", true), o.text("photoHD")) }
}

data class SharedEdit(val id: String = UUID.randomUUID().toString(), val playlistID: String, val owner: String, val action: String, val tracks: List<SharedTrack> = emptyList(), val trackIDs: List<String> = emptyList(), val name: String? = null, val description: String? = null, val createdAt: Long = SocialRules.now) {
    fun valid() = id.length <= 100 && SocialRules.key(owner) && playlistID.length <= 100 && tracks.size <= 100 && tracks.all { it.valid() } && trackIDs.size <= 2000 && (name?.length ?: 0) <= 200 && (description?.length ?: 0) <= 4000 && action in listOf("add", "remove", "reorder", "rename", "mix")
    fun json() = JSONObject().put("id", id).put("playlistID", playlistID).put("owner", owner).put("action", action).put("tracks", JSONArray(tracks.map { it.json() })).put("trackIDs", JSONArray(trackIDs)).put("name", name).put("description", description).put("createdAt", createdAt)
    companion object { fun parse(o: JSONObject) = SharedEdit(o.getString("id"), o.getString("playlistID"), o.getString("owner"), o.getString("action"), o.objects("tracks", SharedTrack::parse), o.strings("trackIDs"), o.text("name"), o.text("description"), o.optLong("createdAt")) }
}

data class ListeningRoom(val id: String = UUID.randomUUID().toString(), val host: String, val name: String, val members: List<String> = emptyList(), val queue: List<SharedTrack> = emptyList(), val currentID: String? = null, val positionMs: Long = 0, val playing: Boolean = false, val observedAt: Long = SocialRules.now, val expiresAt: Long = SocialRules.now + 43_200_000, val revision: Long = 1, val allowControls: Boolean = false, val ended: Boolean = false, val speed: Float = 1f) {
    val key get() = "$host:$id"
    val live get() = !ended && expiresAt > SocialRules.now
    fun valid() = SocialRules.key(host) && id.length <= 100 && name.length <= 200 && members.size <= 32 && members.all(SocialRules::key) && queue.size <= 200 && queue.all { it.valid() } && queue.map { it.id }.distinct().size == queue.size && positionMs in 0..86_400_000 && revision in 1 until 9_000_000_000_000_000L && observedAt <= SocialRules.now + 300_000 && speed.isFinite() && speed in 0.25f..3f
    fun json() = JSONObject().put("id", id).put("host", host).put("name", name).put("members", JSONArray(members)).put("queue", JSONArray(queue.map { it.json() })).put("currentID", currentID).put("positionMs", positionMs).put("playing", playing).put("observedAt", observedAt).put("expiresAt", expiresAt).put("revision", revision).put("allowControls", allowControls).put("ended", ended).put("speed", speed)
    companion object { fun parse(o: JSONObject) = ListeningRoom(o.getString("id"), o.getString("host"), o.getString("name"), o.strings("members"), o.objects("queue", SharedTrack::parse), o.text("currentID"), o.optLong("positionMs"), o.optBoolean("playing"), o.optLong("observedAt"), o.optLong("expiresAt"), o.optLong("revision", 1), o.optBoolean("allowControls"), o.optBoolean("ended"), o.optDouble("speed", 1.0).toFloat()) }
}

data class RoomRequest(val id: String = UUID.randomUUID().toString(), val roomID: String, val host: String, val action: String, val name: String = "", val tracks: List<SharedTrack> = emptyList(), val trackID: String? = null, val positionMs: Long? = null, val playing: Boolean? = null, val createdAt: Long = SocialRules.now) {
    fun valid() = id.length <= 100 && roomID.length <= 100 && SocialRules.key(host) && name.length <= 80 && tracks.size <= 100 && tracks.all { it.valid() } && (positionMs == null || positionMs in 0..86_400_000) && action in listOf("join", "leave", "add", "remove", "control", "next")
    fun json() = JSONObject().put("id", id).put("roomID", roomID).put("host", host).put("action", action).put("name", name).put("tracks", JSONArray(tracks.map { it.json() })).put("trackID", trackID).put("positionMs", positionMs).put("playing", playing).put("createdAt", createdAt)
    companion object { fun parse(o: JSONObject) = RoomRequest(o.getString("id"), o.getString("roomID"), o.getString("host"), o.getString("action"), o.optString("name"), o.objects("tracks", SharedTrack::parse), o.text("trackID"), if (o.has("positionMs") && !o.isNull("positionMs")) o.getLong("positionMs") else null, if (o.has("playing") && !o.isNull("playing")) o.getBoolean("playing") else null, o.optLong("createdAt")) }
}

data class IncomingRoomRequest(val sender: String, val request: RoomRequest)

data class SocialLink(val type: String, val owner: String, val id: String? = null) {
    val url get() = "spitify://$type/$owner" + (id?.let { "/$it" } ?: "")
    companion object {
        fun parse(value: String): SocialLink? = runCatching {
            val text = value.trim()
            if (SocialRules.key(text)) return SocialLink("person", text)
            val uri = URI(text); val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            if (uri.scheme != "spitify" || uri.host !in listOf("person", "playlist", "room") || parts.size != (if (uri.host == "person") 1 else 2) || !SocialRules.key(parts.firstOrNull().orEmpty()) || parts.any { it.length > 100 }) null
            else SocialLink(uri.host, parts[0], parts.getOrNull(1))
        }.getOrNull()
    }
}

object SocialRules {
    val now get() = System.currentTimeMillis()
    fun key(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }
    fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    fun publicURL(value: String): Boolean = runCatching {
        val uri = URI(value); val host = uri.host?.lowercase() ?: return false
        if (value.length > 2000 || uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) || !host.contains('.') || host.endsWith(".local") || host.endsWith(".localhost") || host.contains(':')) return false
        val ns = host.split('.').mapNotNull { it.toIntOrNull() }
        ns.size != 4 || !(ns[0] in listOf(0, 10, 127, 169) || ns[0] == 192 && ns[1] == 168 || ns[0] == 172 && ns[1] in 16..31)
    }.getOrDefault(false)
    fun mix(contributions: List<List<SharedTrack>>): List<SharedTrack> {
        val seen = mutableSetOf<String>(); val out = mutableListOf<SharedTrack>()
        repeat(contributions.maxOfOrNull { it.size } ?: 0) { i -> contributions.forEach { list -> list.getOrNull(i)?.let { if (seen.add(it.recordingKey)) out += it.copy(id = UUID.randomUUID().toString()) }; if (out.size >= 200) return out } }
        return out
    }
}

package com.localfy.app.data.music

import com.localfy.app.desktop.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ReleaseNotice(val artist: OnlineArtist, val album: OnlineAlbum)

/**
 * Followed artists and their new releases (desktop port). Instead of an Android notification,
 * [onNewReleases] receives releases from the last 14 days when notifications are on.
 */
class ArtistFollows(
    private val prefs: Prefs = Prefs("artist_follows"),
    private val onNewReleases: (OnlineArtist, List<OnlineAlbum>) -> Unit = { _, _ -> },
    private val artistPage: suspend (String) -> OnlineSearch = Monochrome::artistPage,
) {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val following = linkedMapOf<String, OnlineArtist>()
    private val known = mutableSetOf<String>()
    private val notices = mutableListOf<ReleaseNotice>()
    val artists: List<OnlineArtist> get() = synchronized(this) { following.values.toList() }.sortedBy { it.name }
    val releases: List<ReleaseNotice> get() = synchronized(this) { notices.toList() }
    @Volatile var message: String? = null; private set
    @Volatile var notifications = prefs.getBoolean("notifications", false); private set
    fun setNotifications(enabled: Boolean) { notifications = enabled; prefs.edit { putBoolean("notifications", enabled) }; changes.value += 1 }
    @Volatile private var refreshing = false

    init {
        runCatching {
            val data = JSONObject(prefs.getString("data", "{}")!!)
            val a = data.optJSONArray("artists") ?: JSONArray()
            for (i in 0 until a.length()) { val v = a.getJSONObject(i); val artist = OnlineArtist(v.getString("id"), v.getString("name"), v.optString("artwork").takeIf { it.isNotEmpty() && it != "null" }); following[artist.id] = artist }
            val k = data.optJSONArray("known") ?: JSONArray(); for (i in 0 until k.length()) known += k.getString(i)
            val n = data.optJSONArray("notices") ?: JSONArray()
            for (i in 0 until n.length()) {
                val v = n.getJSONObject(i); val artist = following[v.getString("artist")] ?: continue
                notices += ReleaseNotice(artist, OnlineAlbum(v.getString("id"), v.getString("title"), artist.name, v.optString("artwork").takeIf { it.isNotEmpty() && it != "null" },
                    if (v.has("explicit") && !v.isNull("explicit")) v.optBoolean("explicit") else null, v.optString("date").takeIf { it.isNotEmpty() && it != "null" }))
            }
        }
    }

    @Synchronized fun contains(id: String) = following.containsKey(id)

    @Synchronized fun follow(artist: OnlineArtist, albums: List<OnlineAlbum>) {
        check(following.size < 128 || contains(artist.id)) { "You can follow up to 128 artists." }
        following[artist.id] = artist
        albums.forEach { known += "${artist.id}:${it.id}" }
        notices.removeAll { it.artist.id == artist.id }
        notices.addAll(0, albums.take(10).map { ReleaseNotice(artist, it) }); persist()
    }

    /**
     * Follows [artist] because you followed it on another device (library sync). Its current albums
     * count as known, so they aren't announced as new releases; when they can't be read now, the
     * next refresh announces only the last two weeks' releases, as for any follow.
     */
    suspend fun followFromSync(artist: OnlineArtist) {
        if (contains(artist.id)) return
        val albums = try { artistPage(artist.id).albums } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
        follow(artist, albums)
    }

    @Synchronized fun unfollow(id: String) { following.remove(id); notices.removeAll { it.artist.id == id }; known.removeAll { it.startsWith("$id:") }; persist() }

    suspend fun refresh() {
        synchronized(this) { if (refreshing) return; refreshing = true }
        message = null
        try {
            for (artist in artists) {
                try {
                    val albums = artistPage(artist.id).albums
                    val fresh = synchronized(this) {
                        if (!contains(artist.id)) return@synchronized null
                        albums.filter { known.add("${artist.id}:${it.id}") }.also { fresh ->
                            notices.addAll(0, fresh.map { ReleaseNotice(artist, it) }); persist()
                        }
                    } ?: continue
                    notifyNew(artist, fresh)
                } catch (e: Exception) { if (e is CancellationException) throw e; message = "Some artists could not be checked. Reopen this screen to retry." }
            }
        } finally { refreshing = false; changes.value += 1 }
    }

    private fun notifyNew(artist: OnlineArtist, albums: List<OnlineAlbum>) {
        if (!notifications) return
        val today = java.time.LocalDate.now()
        val recent = albums.filter { album -> runCatching { val date = java.time.LocalDate.parse(album.releaseDate?.take(10)); !date.isAfter(today) && !date.isBefore(today.minusDays(14)) }.getOrDefault(false) }
        if (recent.isNotEmpty()) runCatching { onNewReleases(artist, recent) }
    }

    private fun persist() {
        while (notices.size > 500) notices.removeAt(notices.lastIndex)
        val data = JSONObject().put("artists", JSONArray(following.values.map { JSONObject().put("id", it.id).put("name", it.name).put("artwork", it.artwork) }))
            .put("known", JSONArray(known.toList())).put("notices", JSONArray(notices.map { JSONObject().put("artist", it.artist.id).put("id", it.album.id).put("title", it.album.title).put("artwork", it.album.artwork).put("date", it.album.releaseDate).put("explicit", it.album.explicit) }))
        prefs.edit { putString("data", data.toString()) }; changes.value += 1
    }
}

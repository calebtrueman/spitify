package com.localfy.app.data.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class ReleaseNotice(val artist: OnlineArtist, val album: OnlineAlbum)
class ArtistFollows(private val context: Context) {
    private val prefs = context.getSharedPreferences("artist_follows", Context.MODE_PRIVATE)
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val following = linkedMapOf<String, OnlineArtist>()
    private val known = mutableSetOf<String>()
    private val notices = mutableListOf<ReleaseNotice>()
    val artists get() = following.values.toList().sortedBy { it.name }
    val releases get() = notices.toList()
    var message: String? = null; private set
    var notifications = prefs.getBoolean("notifications", false); private set
    fun setNotifications(enabled: Boolean) { notifications = enabled; prefs.edit().putBoolean("notifications", enabled).apply(); changes.value += 1 }
    private var refreshing = false
    init {
        runCatching {
            val data = JSONObject(prefs.getString("data", "{}")!!)
            val a = data.optJSONArray("artists") ?: JSONArray()
            for (i in 0 until a.length()) { val v = a.getJSONObject(i); val artist = OnlineArtist(v.getString("id"), v.getString("name"), v.optString("artwork").takeIf { it.isNotEmpty() }); following[artist.id] = artist }
            val k = data.optJSONArray("known") ?: JSONArray(); for (i in 0 until k.length()) known += k.getString(i)
            val n = data.optJSONArray("notices") ?: JSONArray()
            for (i in 0 until n.length()) { val v = n.getJSONObject(i); val artist = following[v.getString("artist")] ?: continue; notices += ReleaseNotice(artist, OnlineAlbum(v.getString("id"), v.getString("title"), artist.name, v.optString("artwork").takeIf { it.isNotEmpty() }, v.optBoolean("explicit"), v.optString("date").takeIf { it.isNotEmpty() })) }
        }
    }
    fun contains(id: String) = following.containsKey(id)
    fun follow(artist: OnlineArtist, albums: List<OnlineAlbum>) {
        check(following.size < 128 || contains(artist.id)) { "You can follow up to 128 artists." }
        following[artist.id] = artist
        albums.forEach { known += "${artist.id}:${it.id}" }
        notices.removeAll { it.artist.id == artist.id }
        notices.addAll(0, albums.take(10).map { ReleaseNotice(artist, it) }); persist()
    }
    fun unfollow(id: String) { following.remove(id); notices.removeAll { it.artist.id == id }; known.removeAll { it.startsWith("$id:") }; persist() }
    suspend fun refresh() {
        if (refreshing) return
        refreshing = true; message = null
        try {
            for (artist in artists) {
                try {
                    val albums = Monochrome.artistPage(artist.id).albums
                    if (!contains(artist.id)) continue
                    val fresh = albums.filter { known.add("${artist.id}:${it.id}") }
                    notifyNew(artist, fresh)
                    notices.addAll(0, fresh.map { ReleaseNotice(artist, it) }); persist()
                } catch (e: Exception) { if (e is CancellationException) throw e; message = "Some artists could not be checked. Pull down or reopen this screen to retry." }
            }
        } finally { refreshing = false; changes.value += 1 }
    }
    private fun notifyNew(artist: OnlineArtist, albums: List<OnlineAlbum>) {
        if (!notifications || android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val today = java.time.LocalDate.now()
        val recent = albums.filter { album -> runCatching { val date = java.time.LocalDate.parse(album.releaseDate?.take(10)); !date.isAfter(today) && !date.isBefore(today.minusDays(14)) }.getOrDefault(false) }
        if (recent.isEmpty()) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("artist_releases", "Artist releases", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val intent = android.content.Intent(context, com.localfy.app.MainActivity::class.java).putExtra("open_releases", true)
        val pending = android.app.PendingIntent.getActivity(context, 407, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = androidx.core.app.NotificationCompat.Builder(context, "artist_releases")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("New from ${artist.name}")
            .setContentText(recent.take(3).joinToString { it.title }).setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(artist.id.hashCode(), notification)
    }

    private fun persist() {
        while (notices.size > 500) notices.removeAt(notices.lastIndex)
        val data = JSONObject().put("artists", JSONArray(following.values.map { JSONObject().put("id", it.id).put("name", it.name).put("artwork", it.artwork) }))
            .put("known", JSONArray(known.toList())).put("notices", JSONArray(notices.map { JSONObject().put("artist", it.artist.id).put("id", it.album.id).put("title", it.album.title).put("artwork", it.album.artwork).put("date", it.album.releaseDate).put("explicit", it.album.explicit) }))
        prefs.edit().putString("data", data.toString()).apply(); changes.value += 1
    }
}

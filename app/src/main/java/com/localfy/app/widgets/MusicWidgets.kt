package com.localfy.app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.localfy.app.LocalfyApp
import com.localfy.app.MainActivity
import com.localfy.app.R
import com.localfy.app.data.Song
import com.localfy.app.data.SmartCollection
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.artKey
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

open class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { updateInBackground(context) }
    private fun updateInBackground(context: Context) {
        val pending = goAsync()
        (context.applicationContext as LocalfyApp).appScope.launch {
            try { withTimeoutOrNull(8500) { refresh(context)?.join() } } finally { pending?.finish() }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { updateInBackground(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION) return
        val action = intent.getStringExtra("command") ?: return
        val pending = goAsync()
        val app = context.applicationContext as LocalfyApp
        app.appScope.launch {
            try {
                withTimeout(8500) {
                    app.library.ensureStarted(); app.player.connect()
                    app.player.state.first { it.connected }
                    when (action) {
                        "toggle" -> if (app.player.state.value.hasMedia) app.player.togglePlay() else {
                            val songs = app.library.library.first { !it.isEmpty }.songs
                            app.player.playSongs(songs, source = "All songs")
                        }
                        "previous" -> app.player.previous()
                        "next" -> app.player.next()
                        else -> {
                            // Wait for the saved library on a cold start before resolving a tile.
                            app.library.library.first { !it.isEmpty }
                            val row = withTimeoutOrNull(5000) {
                                combine(app.library.library, app.library.playlists, app.library.smart, app.library.likedIds) { _, _, _, _ ->
                                    rows(app, action.substringBefore(':'), Int.MAX_VALUE).firstOrNull { it.action == action }
                                }.filterNotNull().first()
                            }
                            if (row != null && row.songs.isNotEmpty()) app.player.playSongs(row.songs, source = row.title)
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                android.widget.Toast.makeText(app, "Open Spitify to load your music, then try again.", android.widget.Toast.LENGTH_SHORT).show()
            } finally { pending.finish(); refresh(app) }
        }
    }

    companion object {
        private const val ACTION = "com.localfy.app.WIDGET_PLAYBACK"
        private val providers = listOf(MusicWidgetProvider::class.java, LibraryWidgetProvider::class.java, AlbumsWidgetProvider::class.java,
            MostPlayedWidgetProvider::class.java, RecentlyPlayedWidgetProvider::class.java, RecentlyAddedWidgetProvider::class.java,
            LikedWidgetProvider::class.java, FriendsWidgetProvider::class.java)
        private var refreshJob: Job? = null
        private var started = false
        fun observe(app: LocalfyApp) {
            if (started) return
            started = true
            app.appScope.launch {
                combine(app.library.library, app.library.playlists, app.library.smart, app.library.likedIds) { _, _, _, _ -> Unit }
                    .collect { refresh(app) }
            }
        }
        fun refresh(context: Context): Job? {
            val app = context.applicationContext as LocalfyApp
            val manager = AppWidgetManager.getInstance(app)
            val installed = providers.associateWith { manager.getAppWidgetIds(ComponentName(app, it)) }.filterValues { it.isNotEmpty() }
            if (installed.isEmpty()) return null
            app.library.ensureStarted()
            refreshJob?.cancel()
            refreshJob = app.appScope.launch {
                delay(150)
                installed.forEach { (type, ids) ->
                    val view = render(app, type)
                    ids.forEach { manager.updateAppWidget(it, view) }
                }
            }
            return refreshJob
        }
        private fun open(context: Context, value: String) = PendingIntent.getActivity(context, value.hashCode(),
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("spitify://widget/$value"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun play(context: Context, value: String) = PendingIntent.getBroadcast(context, value.hashCode(),
            Intent(context, MusicWidgetProvider::class.java).setAction(ACTION).setData(Uri.parse("spitify://widget-control/$value")).putExtra("command", value),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        internal data class Row(val action: String, val title: String, val subtitle: String, val songs: List<Song>, val art: ArtKey?)
        internal fun rows(app: LocalfyApp, kind: String, limit: Int = 4): List<Row> {
            val lib = app.library.library.value
            fun songRows(songs: List<Song>) = songs.take(limit).map { Row("$kind:${it.id}", it.title, it.artist, listOf(it), it.artKey) }
            return when (kind) {
                "playlists" -> app.library.playlists.value.sortedByDescending { it.updatedAt }.take(limit).map { Row("playlists:${it.id}", it.name, "${it.songs.size} songs", it.songs, it.artKey) }
                "albums" -> lib.albums.sortedByDescending { it.songs.maxOfOrNull { s -> s.dateAddedSec } ?: 0 }.take(limit).map { Row("albums:${it.id}", it.title, it.artist, it.songs, it.cover.artKey) }
                "most" -> songRows(app.library.smart.value[SmartCollection.Kind.MostPlayed]?.songs.orEmpty())
                "played" -> songRows(app.library.smart.value[SmartCollection.Kind.RecentlyPlayed]?.songs.orEmpty())
                "added" -> songRows(app.library.smart.value[SmartCollection.Kind.RecentlyAdded]?.songs.orEmpty())
                "liked" -> songRows(lib.songs.filter { it.id in app.library.likedIds.value })
                else -> emptyList()
            }
        }
        private suspend fun picture(app: LocalfyApp, key: ArtKey?): android.graphics.Bitmap? {
            if (key == null) return null
            return try {
                val request = ImageRequest.Builder(app).data(key.model).size(240).allowHardware(false)
                    .memoryCacheKey("widget:${key.albumId}:${key.url}:${key.version}").build()
                (SingletonImageLoader.get(app).execute(request) as? SuccessResult)?.image?.toBitmap()
            } catch (error: Exception) { if (error is CancellationException) throw error; null }
        }
        internal suspend fun render(app: LocalfyApp, type: Class<*>): RemoteViews {
            if (type == MusicWidgetProvider::class.java) {
                val prefs = app.getSharedPreferences("widget_state", 0)
                val song = app.player.state.value.currentId?.let(app::resolve) ?: app.resolve(prefs.getLong("songId", 0))
                val playing = app.player.state.value.isPlaying
                val view = RemoteViews(app.packageName, R.layout.now_playing_widget)
                view.setTextViewText(R.id.widget_title, song?.title ?: prefs.getString("title", "Ready to listen"))
                view.setTextViewText(R.id.widget_subtitle, song?.artist ?: prefs.getString("artist", "Choose music in Spitify"))
                picture(app, song?.artKey)?.let { view.setImageViewBitmap(R.id.widget_art, it) }
                view.setImageViewResource(R.id.widget_play, if (playing) R.drawable.widget_pause else R.drawable.widget_play)
                view.setContentDescription(R.id.widget_play, if (playing) "Pause" else "Play")
                view.setOnClickPendingIntent(R.id.widget_previous, play(app, "previous"))
                view.setOnClickPendingIntent(R.id.widget_play, play(app, "toggle"))
                view.setOnClickPendingIntent(R.id.widget_next, play(app, "next"))
                view.setOnClickPendingIntent(R.id.widget_art, open(app, "player"))
                view.setOnClickPendingIntent(R.id.widget_title, open(app, "player"))
                return view
            }
            if (type == FriendsWidgetProvider::class.java) {
                val view = RemoteViews(app.packageName, R.layout.music_widget)
                view.setTextViewText(R.id.widget_title, "Music together")
                view.setTextViewText(R.id.widget_subtitle, "Friends · codes · listening rooms")
                listOf(R.id.widget_one, R.id.widget_two, R.id.widget_three, R.id.widget_four).forEachIndexed { i, id ->
                    view.setTextViewText(id, listOf("Friends", "My code", "Listen together", "My library")[i])
                    view.setOnClickPendingIntent(id, open(app, listOf("friends", "code", "rooms", "library")[i]))
                }
                return view
            }
            val (kind, title) = when (type) {
                AlbumsWidgetProvider::class.java -> "albums" to "Albums"
                MostPlayedWidgetProvider::class.java -> "most" to "Most played"
                RecentlyPlayedWidgetProvider::class.java -> "played" to "Recently played"
                RecentlyAddedWidgetProvider::class.java -> "added" to "Recently added"
                LikedWidgetProvider::class.java -> "liked" to "Liked songs"
                else -> "playlists" to "Playlists"
            }
            val view = RemoteViews(app.packageName, R.layout.library_shelf_widget)
            view.setTextViewText(R.id.widget_title, title)
            view.setOnClickPendingIntent(R.id.widget_title, open(app, "library"))
            val rows = rows(app, kind)
            view.setTextViewText(R.id.widget_empty, when (kind) { "most", "played" -> "Play some music to fill this widget"; "liked" -> "Like songs in Spitify to see them here"; "playlists" -> "Create a playlist in Spitify to see it here"; else -> "Add music in Spitify to see it here" })
            view.setViewVisibility(R.id.widget_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
            view.removeAllViews(R.id.widget_shelf_top); view.removeAllViews(R.id.widget_shelf_bottom)
            rows.forEachIndexed { index, row ->
                val tile = RemoteViews(app.packageName, R.layout.widget_shelf_item)
                tile.setTextViewText(R.id.widget_item_title, row.title)
                tile.setTextViewText(R.id.widget_item_subtitle, row.subtitle)
                tile.setContentDescription(R.id.widget_item, "Play ${row.title}")
                picture(app, row.art)?.let { tile.setImageViewBitmap(R.id.widget_art, it) }
                tile.setOnClickPendingIntent(R.id.widget_item, play(app, row.action))
                view.addView(if (index < 2) R.id.widget_shelf_top else R.id.widget_shelf_bottom, tile)
            }
            return view
        }
    }
}
class LibraryWidgetProvider : MusicWidgetProvider()
class AlbumsWidgetProvider : MusicWidgetProvider()
class MostPlayedWidgetProvider : MusicWidgetProvider()
class RecentlyPlayedWidgetProvider : MusicWidgetProvider()
class RecentlyAddedWidgetProvider : MusicWidgetProvider()
class LikedWidgetProvider : MusicWidgetProvider()
class FriendsWidgetProvider : MusicWidgetProvider()

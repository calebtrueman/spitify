package com.localfy.app

import android.content.ComponentName
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.localfy.app.widgets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WidgetAndCarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as LocalfyApp
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun waitFor(label: String, block: () -> Boolean) {
        val end = System.currentTimeMillis() + 12_000
        while (!main(block) && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(label, main(block))
    }

    @Test fun systemMediaBrowserFindsAppAndLoadsCarTabsWithoutOpeningAnActivity() {
        val pm = app.packageManager
        val services = pm.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService").setPackage(app.packageName), 0)
        assertTrue("Android Auto can discover the media service", services.any { it.serviceInfo.name.endsWith("PlaybackService") && it.serviceInfo.exported })
        val info = pm.getApplicationInfo(app.packageName, android.content.pm.PackageManager.GET_META_DATA)
        val description = info.metaData.getInt("com.google.android.gms.car.application")
        assertTrue(description != 0)
        val connected = CountDownLatch(1)
        var failed = false
        lateinit var browser: MediaBrowser
        main {
            browser = MediaBrowser(app, ComponentName(app, com.localfy.app.playback.PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() { connected.countDown() }
                override fun onConnectionFailed() { failed = true; connected.countDown() }
            }, null)
            browser.connect()
        }
        try {
            assertTrue("Car connection finished", connected.await(15, TimeUnit.SECONDS))
            assertFalse("Car connection accepted", failed)
            val loaded = CountDownLatch(1)
            var titles = emptyList<String>()
            main {
                browser.subscribe(browser.root, object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) { titles = children.map { it.description.title.toString() }; loaded.countDown() }
                    override fun onError(parentId: String) { loaded.countDown() }
                })
            }
            assertTrue("Car tabs loaded", loaded.await(15, TimeUnit.SECONDS))
            assertEquals(listOf("For you", "Library", "Podcasts", "Books"), titles)
        } finally { main { browser.disconnect() } }
    }

    @Test fun widgetControlsWorkInBackgroundAndPlaylistCoverSurvivesReload() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.READ_MEDIA_AUDIO)
        val resolver = app.contentResolver
        val token = System.nanoTime().toString()
        val uris = mutableListOf<Uri>()
        var playlistID: Long? = null
        val imageFile = File(app.cacheDir, "widget-cover-$token.png")
        val oldAutoplay = app.player.state.value.autoplay
        try {
            // Real files give playback and the scanner the same songs the widget sees.
            for (i in 1..3) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "Widget-$token-$i.wav")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.TITLE, "Widget song $i")
                    put(MediaStore.Audio.Media.ARTIST, "Widget test")
                    put(MediaStore.Audio.Media.ALBUM, "Widget album $token")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SpitifyWidgetChecks")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)); uris += uri
                val size = 44100 * 2 * 30
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
                resolver.openOutputStream(uri)!!.use { it.write(header.array()); it.write(ByteArray(size)) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            main { app.library.refresh(); app.player.setAutoplay(false); app.player.connect() }
            val ids = uris.map { android.content.ContentUris.parseId(it) }
            waitFor("Widget songs scanned") { ids.all { it in app.library.library.value.songById } && app.player.state.value.connected }
            val songs = ids.map { app.library.library.value.songById.getValue(it) }
            val id = app.library.createPlaylist("Widget playlist $token", ids); playlistID = id
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(230, 30, 60)) }
            imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(app.library.setPlaylistCover(id, Uri.fromFile(imageFile)))
            waitFor("Playlist has chosen art") { app.library.playlists.value.any { it.id == id && it.artwork != null } }
            assertTrue(File(app.filesDir, "playlist_covers/$id.jpg").isFile)
            val playlists = withContext(Dispatchers.Main) { MusicWidgetProvider.render(app, LibraryWidgetProvider::class.java) }
            val shelf = main { playlists.apply(app, FrameLayout(app)) }
            assertEquals("Playlists", main { shelf.findViewById<TextView>(R.id.widget_title).text.toString() })
            val picture = main { shelf.findViewById<ImageView>(R.id.widget_art).drawable as android.graphics.drawable.BitmapDrawable }.bitmap
            assertTrue("The custom red cover reaches the widget", Color.red(picture.getPixel(picture.width / 2, picture.height / 2)) > 180)
            main { app.player.playSongs(songs, source = "Widget checks"); app.player.togglePlay() }
            waitFor("Queue ready") { app.player.state.value.queue.size == 3 }
            val controls = withContext(Dispatchers.Main) { MusicWidgetProvider.render(app, MusicWidgetProvider::class.java) }
            val view = main { controls.apply(app, FrameLayout(app)) }
            main { view.findViewById<View>(R.id.widget_next).performClick() }
            waitFor("Widget next changes song") { app.player.state.value.currentId == ids[1] }
            main { view.findViewById<View>(R.id.widget_previous).performClick() }
            waitFor("Widget previous changes song") { app.player.state.value.currentId == ids[0] }
            val wasPlaying = app.player.state.value.isPlaying
            main { view.findViewById<View>(R.id.widget_play).performClick() }
            waitFor("Widget play/pause changes playback") { app.player.state.value.isPlaying != wasPlaying }
            main { app.player.next() }
            waitFor("Second song before playlist tap") { app.player.state.value.currentId == ids[1] }
            main { shelf.findViewById<View>(R.id.widget_item).performClick() }
            waitFor("Playlist artwork starts its complete playlist") { app.player.state.value.currentId == ids[0] && app.player.state.value.queue == ids }
            assertTrue("Widget controls did not open the app", main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isEmpty() })
            // Exercise every RemoteViews layout and save native renders for inspection.
            for ((type, name) in listOf(MusicWidgetProvider::class.java to "playing", LibraryWidgetProvider::class.java to "playlists", AlbumsWidgetProvider::class.java to "albums", MostPlayedWidgetProvider::class.java to "most", RecentlyPlayedWidgetProvider::class.java to "played", RecentlyAddedWidgetProvider::class.java to "added", LikedWidgetProvider::class.java to "liked")) {
                val remote = withContext(Dispatchers.Main) { MusicWidgetProvider.render(app, type) }
                main {
                    val widget = remote.apply(app, FrameLayout(app))
                    val scale = app.resources.displayMetrics.density
                    val width = (360 * scale).toInt(); val height = ((if (name == "playing") 144 else 210) * scale).toInt()
                    widget.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); widget.layout(0, 0, width, height)
                    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); widget.draw(Canvas(output))
                    File(app.cacheDir, "widget-$name.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            assertTrue(app.library.setPlaylistCover(id, null))
            waitFor("Original cover restored") { app.library.playlists.value.firstOrNull { it.id == id }?.artwork == null }
        } finally {
            main { app.player.togglePlay(); app.player.setAutoplay(oldAutoplay) }
            playlistID?.let { app.library.deletePlaylist(it).join() }
            uris.forEach { resolver.delete(it, null, null) }; imageFile.delete()
            main { app.library.refresh() }
        }
    }
}

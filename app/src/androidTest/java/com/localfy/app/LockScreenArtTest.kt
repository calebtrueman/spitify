package com.localfy.app

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.playback.LockScreenArt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on the disposable test emulator with All files access granted to the test app. */
@RunWith(AndroidJUnit4::class)
class LockScreenArtTest {
    @Test fun originalWallpaperReturnsAndUserChangesWin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Grant All files access on the temporary emulator before this test", Environment.isExternalStorageManager())
        val manager = WallpaperManager.getInstance(context)
        fun bitmap(color: Int) = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        fun color(): Int = manager.getWallpaperFile(WallpaperManager.FLAG_LOCK)!!.use { descriptor ->
            val image = BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
            image.getPixel(image.width / 2, image.height / 2).also { image.recycle() }
        }
        val art = LockScreenArt(context)
        art.setEnabled(true)
        manager.setBitmap(bitmap(Color.BLUE), null, false, WallpaperManager.FLAG_LOCK)
        art.show(bitmap(Color.RED), "red")
        assertEquals(Color.RED, color())
        art.restore()
        assertEquals(Color.BLUE, color())
        art.show(bitmap(Color.RED), "red-again")
        manager.setBitmap(bitmap(Color.GREEN), null, false, WallpaperManager.FLAG_LOCK)
        art.restore()
        assertEquals(Color.GREEN, color())
        // A fresh object recovers the saved wallpaper after the app restarts.
        art.show(bitmap(Color.RED), "restart")
        LockScreenArt(context).restore()
        assertEquals(Color.GREEN, color())
        manager.clear(WallpaperManager.FLAG_LOCK)
        art.show(bitmap(Color.RED), "shared")
        art.restore()
        assertNull(manager.getWallpaperFile(WallpaperManager.FLAG_LOCK))
    }
    @Test fun playingAndPausedKeepArtButStopRestoresWallpaper() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LocalfyApp
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun waitFor(check: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 12000
            while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(check())
        }
        val manager = WallpaperManager.getInstance(app)
        val background = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        manager.setBitmap(background, null, false, WallpaperManager.FLAG_LOCK)
        val originalId = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)
        val art = java.io.File(app.cacheDir, "wallpaper-test.png")
        background.eraseColor(Color.RED)
        art.outputStream().use { background.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val audio = java.io.File(app.cacheDir, "wallpaper-test.flac")
        instrumentation.context.assets.open("tags/sample.flac").use { input -> audio.outputStream().use(input::copyTo) }
        val control = main {
            androidx.media3.session.MediaController.Builder(app, androidx.media3.session.SessionToken(app,
                android.content.ComponentName(app, com.localfy.app.playback.PlaybackService::class.java))).buildAsync()
        }.get(20, java.util.concurrent.TimeUnit.SECONDS)
        try {
            main {
                app.lockScreenArt.setEnabled(true); app.lockScreenArt.refreshPermission(); app.player.connect()
                control.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                control.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("987654321")
                    .setUri(android.net.Uri.fromFile(audio)).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                        .setTitle("Wallpaper test").setArtworkUri(android.net.Uri.fromFile(art)).build()).build())
                control.prepare(); control.play()
            }
            waitFor { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) != originalId }
            val playingId = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)
            main { control.pause() }
            Thread.sleep(800)
            assertEquals(playingId, manager.getWallpaperId(WallpaperManager.FLAG_LOCK))
            main { control.stop() }
            waitFor { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) != playingId }
            manager.getWallpaperFile(WallpaperManager.FLAG_LOCK)!!.use {
                val image = BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
                assertEquals(Color.BLUE, image.getPixel(image.width / 2, image.height / 2)); image.recycle()
            }
        } finally {
            main { control.stop(); control.clearMediaItems(); control.release(); app.player.disconnect() }
            runBlocking { app.lockScreenArt.restore() }
            audio.delete(); art.delete(); background.recycle()
        }
    }

}

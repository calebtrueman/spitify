package com.localfy.app

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.playback.LockScreenArt
import com.localfy.app.playback.WallpaperAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

class WallpaperRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bitmap(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun bytes(color: Int) = ByteArrayOutputStream().also { bitmap(color).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private inner class Phone : WallpaperAccess {
        var home = bytes(Color.BLUE); var lock = bytes(Color.GREEN)
        var homeId = 1; var lockId = 2; var next = 10
        var changesHome = false; var failRestore = false; var unreadable = false; var writes = 0
        override fun allowed() = true
        override fun supported() = true
        override fun live() = false
        override fun id(which: Int) = if (which == WallpaperManager.FLAG_SYSTEM) homeId else lockId
        override fun open(which: Int): InputStream? = if (unreadable) null else (if (which == WallpaperManager.FLAG_SYSTEM) home else lock).inputStream()
        override fun apply(bitmap: Bitmap, which: Int): Int {
            writes++; lock = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); lockId = next++
            if (changesHome) { home = bytes(Color.BLACK); homeId = next++ }
            return lockId
        }
        override fun restore(input: InputStream, which: Int): Int {
            if (failRestore) error("Temporary failure")
            writes++
            if (which == WallpaperManager.FLAG_SYSTEM) { home = input.readBytes(); homeId = next++; return homeId }
            lock = input.readBytes(); lockId = next++; return lockId
        }
        fun color(data: ByteArray) = BitmapFactory.decodeByteArray(data, 0, data.size).getPixel(0, 0)
    }
    @Test fun phoneChangingBothScreensRestoresBothAndTurnsFeatureOff() = runBlocking {
        val phone = Phone().apply { changesHome = true }
        val art = LockScreenArt(context, phone, "both-${System.nanoTime()}")
        art.setEnabled(true); art.show(bitmap(Color.RED), "song")
        assertEquals(Color.BLUE, phone.color(phone.home)); assertEquals(Color.GREEN, phone.color(phone.lock))
        assertFalse(art.enabled.value)
    }
    @Test fun failedRestoreKeepsBackupsForNextLaunch() = runBlocking {
        val phone = Phone(); val name = "retry-${System.nanoTime()}"
        val art = LockScreenArt(context, phone, name)
        art.setEnabled(true); art.show(bitmap(Color.RED), "song")
        phone.failRestore = true; art.restore()
        assertEquals(Color.RED, phone.color(phone.lock))
        phone.failRestore = false; LockScreenArt(context, phone, name).restore()
        assertEquals(Color.GREEN, phone.color(phone.lock)); assertEquals(Color.BLUE, phone.color(phone.home))
    }
    @Test fun unreadableOriginalMakesNoWallpaperChanges() = runBlocking {
        val phone = Phone().apply { unreadable = true }
        val art = LockScreenArt(context, phone, "unreadable-${System.nanoTime()}")
        art.setEnabled(true); art.show(bitmap(Color.RED), "song")
        assertEquals(0, phone.writes)
    }
    @Test fun newHomeWallpaperIsNeverReplacedWithOldBackup() = runBlocking {
        val phone = Phone(); val art = LockScreenArt(context, phone, "user-${System.nanoTime()}")
        art.setEnabled(true); art.show(bitmap(Color.RED), "song")
        phone.home = bytes(Color.YELLOW); phone.homeId = 99
        art.show(bitmap(Color.WHITE), "next")
        assertEquals(Color.YELLOW, phone.color(phone.home)); assertEquals(Color.GREEN, phone.color(phone.lock))
        assertFalse(art.enabled.value)
    }
    @Test fun oldSharedWallpaperWithoutBackupNeverClearsEitherScreen() = runBlocking {
        val phone = Phone(); val name = "legacy-${System.nanoTime()}"
        val folder = java.io.File(context.noBackupFilesDir, name).apply { mkdirs() }
        java.io.File(folder, "state.json").writeText("""{"separate":false,"applied":2}""")
        val art = LockScreenArt(context, phone, name)
        art.restore(force = true)
        assertEquals(0, phone.writes); assertFalse(art.enabled.value)
        assertTrue(art.message.value!!.contains("did not save"))
    }

}

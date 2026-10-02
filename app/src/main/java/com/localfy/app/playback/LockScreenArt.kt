package com.localfy.app.playback

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Keeps the original image on disk before any change, and restores only our own wallpaper. */
class LockScreenArt(private val context: Context) {
    private val wallpaper = WallpaperManager.getInstance(context)
    private val prefs = context.getSharedPreferences("lock-screen-art", Context.MODE_PRIVATE)
    private val folder = File(context.noBackupFilesDir, "lock-screen-art").apply { mkdirs() }
    private val original = File(folder, "original")
    private val record = AtomicFile(File(folder, "state.json"))
    private val mutex = Mutex()
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled = _enabled.asStateFlow()
    private val _allowed = MutableStateFlow(Environment.isExternalStorageManager())
    val allowed = _allowed.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var lastKey: String? = null

    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply(); _enabled.value = value }
    fun refreshPermission() { _allowed.value = Environment.isExternalStorageManager() }

    suspend fun show(bitmap: Bitmap, key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_enabled.value || !Environment.isExternalStorageManager() || key == lastKey) return@withLock
            if (!wallpaper.isWallpaperSupported || !wallpaper.isSetWallpaperAllowed) {
                _message.value = "This phone does not allow wallpaper changes."; return@withLock
            }
            try {
                var state = read()
                if (state != null && state.optInt("applied", -1) != wallpaper.getWallpaperId(WallpaperManager.FLAG_LOCK)) {
                    // The user changed their wallpaper while music was playing. Keep that choice.
                    if (state.optBoolean("pending")) {
                        _message.value = "A wallpaper update was interrupted. Your original is saved; use Restore saved wallpaper below."
                        return@withLock
                    }
                    discard(); lastKey = key; _message.value = "Your new wallpaper was kept. Album art will return with the next song."; return@withLock
                }
                if (state == null) {
                    val live = if (Build.VERSION.SDK_INT >= 34) wallpaper.getWallpaperInfo(WallpaperManager.FLAG_LOCK) else wallpaper.wallpaperInfo
                    if (live != null) { _message.value = "Your live wallpaper is kept. Temporary album art needs a still wallpaper."; return@withLock }
                    val descriptor = wallpaper.getWallpaperFile(WallpaperManager.FLAG_LOCK)
                    val separate = descriptor != null
                    if (descriptor != null) {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            original.outputStream().use { output ->
                                val bytes = ByteArray(32 * 1024); var count = 0L
                                while (true) {
                                    val n = input.read(bytes); if (n < 0) break
                                    count += n; check(count <= 128L * 1024 * 1024) { "Wallpaper is too large to save safely." }
                                    output.write(bytes, 0, n)
                                }
                                output.fd.sync()
                            }
                        }
                        check(original.length() > 0) { "Could not save the current wallpaper." }
                    }
                    state = JSONObject().put("separate", separate).put("applied", wallpaper.getWallpaperId(WallpaperManager.FLAG_LOCK))
                    save(state)
                }
                state.put("pending", true); save(state)
                val id = wallpaper.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)
                state.put("applied", id).put("pending", false); save(state)
                lastKey = key; _message.value = null
            } catch (error: Exception) {
                _message.value = "Could not show album art: ${error.message ?: "wallpaper access was blocked"}"
            }
        }
    }

    suspend fun restore(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastKey = null
            try {
                val state = read() ?: return@withLock
                if (!force && state.optInt("applied", -1) != wallpaper.getWallpaperId(WallpaperManager.FLAG_LOCK)) {
                    // Never overwrite a wallpaper selected outside Spitify.
                    if (state.optBoolean("pending")) {
                        _message.value = "A wallpaper update was interrupted. Your original is saved; use Restore saved wallpaper below."
                        return@withLock
                    }
                    discard(); return@withLock
                }
                if (state.getBoolean("separate")) {
                    check(original.exists() && original.length() > 0) { "The saved wallpaper could not be read." }
                    original.inputStream().use { wallpaper.setStream(it, null, false, WallpaperManager.FLAG_LOCK) }
                } else { wallpaper.clear(WallpaperManager.FLAG_LOCK) }
                discard(); _message.value = null
            } catch (error: Exception) {
                _message.value = "Could not restore your wallpaper yet. Open Spitify again to retry."
            }
        }
    }

    private fun read(): JSONObject? = if (!record.baseFile.exists()) null else JSONObject(record.openRead().bufferedReader().use { it.readText() })
    private fun save(value: JSONObject) {
        val output = record.startWrite()
        try { output.write(value.toString().toByteArray()); record.finishWrite(output) }
        catch (error: Exception) { record.failWrite(output); throw error }
    }
    private fun discard() { record.delete(); original.delete() }
}

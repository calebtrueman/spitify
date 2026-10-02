package com.localfy.app.playback

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** Kept separate so restoration failures and phone-specific side effects can be tested. */
interface WallpaperAccess {
    fun allowed(): Boolean
    fun supported(): Boolean
    fun live(): Boolean
    fun id(which: Int): Int
    fun open(which: Int): InputStream?
    fun apply(bitmap: Bitmap, which: Int): Int
    fun restore(input: InputStream, which: Int): Int
}

private class AndroidWallpaperAccess(context: Context) : WallpaperAccess {
    private val manager = WallpaperManager.getInstance(context)
    override fun allowed() = Environment.isExternalStorageManager()
    override fun supported() = manager.isWallpaperSupported && manager.isSetWallpaperAllowed
    override fun live() = if (Build.VERSION.SDK_INT >= 34) {
        manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK) != null || manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM) != null
    } else manager.wallpaperInfo != null
    override fun id(which: Int) = manager.getWallpaperId(which)
    override fun open(which: Int): InputStream? = manager.getWallpaperFile(which)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    override fun apply(bitmap: Bitmap, which: Int) = manager.setBitmap(bitmap, null, false, which)
    override fun restore(input: InputStream, which: Int) = manager.setStream(input, null, false, which)
}

/** Never changes wallpaper until both original images have been saved and checked. */
class LockScreenArt(
    private val context: Context,
    private val wallpaper: WallpaperAccess = AndroidWallpaperAccess(context),
    storageName: String = "lock-screen-art",
) {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val folder = File(context.noBackupFilesDir, storageName).apply { mkdirs() }
    private val original = File(folder, "original")
    private val home = File(folder, "original-home")
    private val record = AtomicFile(File(folder, "state.json"))
    private val mutex = Mutex()
    // Older versions did not keep a home-screen backup. Recover first, then require opting in again.
    private val upgraded = prefs.getInt("backupVersion", 0) < 2
    private val _enabled = MutableStateFlow(!upgraded && prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    private val _allowed = MutableStateFlow(wallpaper.allowed())
    val allowed = _allowed.asStateFlow()
    private val _message = MutableStateFlow<String?>(if (upgraded && prefs.getBoolean("enabled", false)) "Lock-screen art is off while your saved wallpaper is recovered. You can turn it on again after choosing your wallpaper." else null)
    val message = _message.asStateFlow()
    private var lastKey: String? = null

    init { if (upgraded) prefs.edit().putBoolean("enabled", false).putInt("backupVersion", 2).commit() }
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply(); _enabled.value = value }
    fun refreshPermission() { _allowed.value = wallpaper.allowed() }

    suspend fun show(bitmap: Bitmap, key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_enabled.value || !wallpaper.allowed() || key == lastKey) return@withLock
            if (!wallpaper.supported()) { _message.value = "This phone does not allow wallpaper changes."; return@withLock }
            try {
                var state = read()
                if (state != null && state.optInt("version") != 2) {
                    _message.value = "Restore your saved wallpaper before turning album art on again."; return@withLock
                }
                if (state != null && state.optInt("applied", -1) != wallpaper.id(WallpaperManager.FLAG_LOCK)) {
                    if (state.optBoolean("pending")) {
                        _message.value = "A wallpaper update was interrupted. Your backups are kept; use Restore saved wallpaper."; return@withLock
                    }
                    finish(); setEnabled(false)
                    _message.value = "Your new wallpaper was kept. Lock-screen art is now off."; return@withLock
                }
                if (state == null) {
                    if (wallpaper.live()) { _message.value = "Your live wallpaper is kept. Temporary album art needs still wallpapers."; return@withLock }
                    val homeId = wallpaper.id(WallpaperManager.FLAG_SYSTEM)
                    val lockId = wallpaper.id(WallpaperManager.FLAG_LOCK)
                    val homeInput = wallpaper.open(WallpaperManager.FLAG_SYSTEM)
                        ?: error("Android did not provide your home wallpaper for backup.")
                    backup(homeInput, home)
                    val lockInput = wallpaper.open(WallpaperManager.FLAG_LOCK)
                    val separate = lockInput != null
                    backup(lockInput ?: home.inputStream(), original)
                    check(homeId == wallpaper.id(WallpaperManager.FLAG_SYSTEM) && lockId == wallpaper.id(WallpaperManager.FLAG_LOCK)) { "Your wallpaper changed during backup. Please try again." }
                    state = JSONObject().put("version", 2).put("separate", separate)
                        .put("homeOriginal", homeId).put("applied", lockId).put("homeChanged", false)
                    save(state)
                }
                if (wallpaper.id(WallpaperManager.FLAG_SYSTEM) != state.getInt("homeOriginal")) {
                    setEnabled(false); restoreSaved(state, force = false)
                    _message.value = "Your new home wallpaper was kept. Lock-screen art is now off."
                    return@withLock
                }
                state.put("pending", true); save(state)
                val id = wallpaper.apply(bitmap, WallpaperManager.FLAG_LOCK)
                val homeAfter = wallpaper.id(WallpaperManager.FLAG_SYSTEM)
                state.put("homeApplied", homeAfter).put("homeChanged", homeAfter != state.getInt("homeOriginal")); save(state)
                check(id > 0 && wallpaper.id(WallpaperManager.FLAG_LOCK) == id) { "Android did not confirm the wallpaper change." }
                state.put("applied", id).put("pending", false); save(state)
                if (state.getBoolean("homeChanged")) {
                    setEnabled(false)
                    restoreSaved(state, force = false)
                    _message.value = "This phone also changed Home when setting Lock. Your saved wallpapers were restored and lock-screen art was turned off."
                } else { lastKey = key; _message.value = null }
            } catch (error: Exception) {
                setEnabled(false)
                runCatching { read()?.let { state ->
                    if (state.optBoolean("homeChanged")) restoreSaved(state, force = false)
                } }
                _message.value = "Could not show album art: ${error.message ?: "wallpaper access was blocked"}. Your backups are kept."
            }
        }
    }

    suspend fun restore(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastKey = null
            try { read()?.let { restoreSaved(it, force) } }
            catch (_: Exception) { _message.value = "Could not restore your wallpaper yet. The saved images are kept. Use Restore saved wallpaper to retry." }
        }
    }

    private fun restoreSaved(state: JSONObject, force: Boolean) {
        if (state.optBoolean("homeChanged") && state.has("homeApplied") && wallpaper.id(WallpaperManager.FLAG_SYSTEM) == state.getInt("homeApplied")) {
            restoreFile(home, WallpaperManager.FLAG_SYSTEM)
            state.put("homeChanged", false); save(state)
        }
        if (!force && state.optInt("applied", -1) != wallpaper.id(WallpaperManager.FLAG_LOCK)) {
            if (state.optBoolean("pending")) {
                _message.value = "A wallpaper update was interrupted. Your backups are kept; use Restore saved wallpaper."; return
            }
            finish(); setEnabled(false); _message.value = "Your new wallpaper was kept. Lock-screen art is now off."; return
        }
        if (state.optInt("version") != 2 && !state.optBoolean("separate")) {
            // The old version saved no image for a shared wallpaper. Do not clear either screen.
            _message.value = "The previous version did not save the shared wallpaper image. Choose your wallpaper again in Android Settings. Album art stays off."
            setEnabled(false); return
        }
        restoreFile(original, WallpaperManager.FLAG_LOCK)
        finish(); _message.value = null
    }

    private fun restoreFile(file: File, which: Int) {
        check(validImage(file)) { "The saved wallpaper could not be read." }
        val id = file.inputStream().use { wallpaper.restore(it, which) }
        check(id > 0 && wallpaper.id(which) == id) { "Android did not confirm restoration." }
    }
    private fun backup(input: InputStream, file: File) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var finished = false
        try {
            input.use { source ->
                val buffer = ByteArray(32 * 1024); var count = 0L
                while (true) { val n = source.read(buffer); if (n < 0) break; count += n; check(count <= 128L * 1024 * 1024) { "Wallpaper is too large to save safely." }; output.write(buffer, 0, n) }
            }
            atomic.finishWrite(output); finished = true
            check(validImage(file)) { "Android did not provide a readable wallpaper image." }
        } catch (error: Exception) { if (!finished) atomic.failWrite(output); throw error }
    }
    private fun validImage(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
    private fun read(): JSONObject? = if (!record.baseFile.exists()) null else JSONObject(record.openRead().bufferedReader().use { it.readText() })
    private fun save(value: JSONObject) {
        val output = record.startWrite()
        try { output.write(value.toString().toByteArray()); record.finishWrite(output) }
        catch (error: Exception) { record.failWrite(output); throw error }
    }
    // Keep the last original images for recovery, even after a successful restore or a user change.
    private fun finish() { record.delete(); lastKey = null }
}

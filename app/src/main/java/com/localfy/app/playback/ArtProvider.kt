package com.localfy.app.playback

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Serves album / podcast artwork to other apps - Android Auto, the lock screen, Bluetooth head
 * units - which can't read our private caches or remote URLs. Read-only and image-only.
 */
class ArtProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val dir = File(ctx.cacheDir, "shared_art").apply { mkdirs() }
        val file = when (uri.pathSegments.firstOrNull()) {
            "album" -> {
                val albumId = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
                val songId = uri.getQueryParameter("song")?.toLongOrNull() ?: 0
                val version = uri.getQueryParameter("v") ?: "0"
                File(dir, "a_${albumId}_$version.jpg").also { f -> if (!f.isFile) albumBitmap(ctx, albumId, songId)?.let { write(it, f) } }
            }
            "remote" -> {
                val url = uri.getQueryParameter("u") ?: return null
                // Stored downscaled: feed artwork is often 3000 px, far more than any car or lock screen shows.
                File(dir, "r2_${sha1(url)}.jpg").also { f ->
                    if (!f.isFile) download(url)?.let { bytes -> com.localfy.app.ui.art.decodeSampled(bytes, 720)?.let { write(it, f) } }
                }
            }
            else -> return null
        }
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun albumBitmap(ctx: Context, albumId: Long, songId: Long): Bitmap? {
        val app = ctx.applicationContext as LocalfyApp
        app.metadata.customArt(albumId)?.let { return com.localfy.app.ui.art.decodeSampled(it, 720) }
        if (songId > 0) runCatching {
            return ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId), Size(512, 512), null)
        }
        if (albumId > 0) runCatching {
            return ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId), Size(512, 512), null)
        }
        app.onlineArt.cached(albumId)?.let { return com.localfy.app.ui.art.decodeSampled(it, 720) }
        // No embedded art: try the online lookup (we're on a binder thread, blocking is fine).
        val album = app.library.library.value.albumById[albumId]
        if (album != null) {
            kotlinx.coroutines.runBlocking { app.onlineArt.fetch(albumId, album.artist, album.title) }?.let { return com.localfy.app.ui.art.decodeSampled(it, 720) }
        }
        return placeholder(album?.title ?: app.library.localBooks.value.firstOrNull { it.albumId == albumId }?.album ?: "♪", albumId)
    }

    /** Same look as the in-app fallback: a gradient tile with the album's initial. */
    private fun placeholder(title: String, seed: Long): Bitmap {
        val colors = intArrayOf(0xFF8E44AD.toInt(), 0xFF1E88E5.toInt(), 0xFFE5533D.toInt(), 0xFF00897B.toInt(), 0xFFF4A300.toInt(), 0xFFD81B60.toInt(), 0xFF3949AB.toInt(), 0xFF43A047.toInt())
        val base = colors[(kotlin.math.abs(seed) % colors.size).toInt()]
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 512f, 512f, base, android.graphics.Color.BLACK, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 512f, 512f, paint)
        paint.shader = null
        paint.color = android.graphics.Color.WHITE
        paint.alpha = 220
        paint.textSize = 220f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        c.drawText(title.trim().firstOrNull()?.uppercase() ?: "♪", 256f, 256f - (paint.descent() + paint.ascent()) / 2, paint)
        return bmp
    }

    private fun write(bitmap: Bitmap, file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(file)
    }

    private fun download(url: String): ByteArray? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000; conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Spitify/1.0 (Android)")
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally { conn.disconnect() }
    }.getOrNull()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        private fun authority(ctx: Context) = "${ctx.packageName}.art"

        fun uriFor(ctx: Context, song: Song): Uri = song.artUrl?.let { remote(ctx, it) }
            ?: Uri.Builder().scheme("content").authority(authority(ctx)).appendPath("album").appendPath(song.albumId.toString())
                .appendQueryParameter("song", song.id.toString()).appendQueryParameter("v", song.artVersion.toString()).build()

        fun remote(ctx: Context, url: String): Uri =
            Uri.Builder().scheme("content").authority(authority(ctx)).appendPath("remote").appendQueryParameter("u", url).build()
    }
}

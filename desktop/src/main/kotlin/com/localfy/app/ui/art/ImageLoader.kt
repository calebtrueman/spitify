package com.localfy.app.ui.art

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.localfy.app.LocalfyApp
import com.localfy.app.desktop.AppPaths
import com.localfy.app.ui.BundledResources
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Desktop image pipeline (the phone uses Coil + MediaStore thumbnails): decodes off the main thread
 * with Skia, downsamples to the size actually drawn, and keeps an LRU of ready-to-draw bitmaps so
 * scrolling a long list never decodes the same cover twice.
 *
 * Models: [ArtKey] (album art via [com.localfy.app.data.art.ArtworkStore], or its URL),
 * http(s) URLs (disk-cached), file: URIs / paths, `res:` bundled resources, [File] and [ByteArray].
 */
object ImageLoader {
    /** Size steps keep the cache small: a 50 dp row and a 52 dp row share one bitmap. */
    private val buckets = intArrayOf(64, 128, 256, 512, 1024, 1600)
    fun bucket(px: Int): Int = buckets.firstOrNull { it >= px } ?: buckets.last()

    @Volatile var container: LocalfyApp? = null

    private const val MAX_BYTES = 192L * 1024 * 1024
    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {}
    private var memoryBytes = 0L
    /** Largest bitmap per model, so a re-shown row draws instantly while a sharper one loads. */
    private val bestBucket = HashMap<String, Int>()
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<ImageBitmap?>>()
    private val loads = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val decodeSlots = Semaphore(4)
    private val networkSlots = Semaphore(6)
    private val failures = HashMap<String, Long>()

    private val http: HttpClient by lazy {
        // HTTP/1.1: parallel HTTP/2 streams to one host can stall mid-body in the JDK client, which left covers blank.
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(12)).build()
    }
    /** One download per URL, shared by every size that wants it. */
    private val downloads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ByteArray?>>()
    private val diskDir: File by lazy { AppPaths.cache("images") }

    /** Stable identity of a model, independent of the drawn size. */
    fun modelKey(model: Any): String = when (model) {
        is ArtKey -> model.url?.let { "u:$it" } ?: "a:${model.albumId}:${model.version}"
        is File -> "f:${model.path}:${model.lastModified()}"
        is ByteArray -> "b:${model.contentHashCode()}:${model.size}"
        else -> "s:$model"
    }

    private fun cacheKey(modelKey: String, bucket: Int) = "$modelKey@$bucket"

    /** Any already-decoded bitmap for [modelKey] (the best one), without loading. */
    @Synchronized fun peek(modelKey: String): ImageBitmap? {
        val best = bestBucket[modelKey] ?: return null
        return memory[cacheKey(modelKey, best)]
    }

    @Synchronized private fun peekExact(modelKey: String, bucket: Int): ImageBitmap? {
        // A larger cached copy is just as good.
        for (b in buckets) if (b >= bucket) memory[cacheKey(modelKey, b)]?.let { return it }
        return null
    }

    @Synchronized private fun put(modelKey: String, bucket: Int, image: ImageBitmap) {
        val key = cacheKey(modelKey, bucket)
        memory.put(key, image)?.let { memoryBytes -= it.width.toLong() * it.height * 4 }
        memoryBytes += image.width.toLong() * image.height * 4
        if ((bestBucket[modelKey] ?: 0) < bucket) bestBucket[modelKey] = bucket
        val iterator = memory.entries.iterator()
        while (memoryBytes > MAX_BYTES && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            memoryBytes -= eldest.value.width.toLong() * eldest.value.height * 4
            iterator.remove()
            val model = eldest.key.substringBeforeLast('@')
            if (bestBucket[model] == eldest.key.substringAfterLast('@').toIntOrNull()) bestBucket.remove(model)
        }
    }

    /** Drops every cached bitmap of [modelKey] (e.g. after the user picks new artwork). */
    @Synchronized fun invalidate(modelKey: String) {
        memory.keys.filter { it.startsWith("$modelKey@") }.forEach { memory.remove(it)?.let { b -> memoryBytes -= b.width.toLong() * b.height * 4 } }
        bestBucket.remove(modelKey)
    }

    /** Loads [model] at least [px] pixels on its longest side. Null when there's no image. */
    suspend fun load(model: Any, px: Int, modelKey: String = modelKey(model)): ImageBitmap? {
        val bucket = bucket(px.coerceAtLeast(1))
        peekExact(modelKey, bucket)?.let { return it }
        val key = cacheKey(modelKey, bucket)
        // Loads run in the loader's own scope: a row scrolling away (or a re-measure) cancels only its wait,
        // never the shared load other callers are waiting on.
        val job = synchronized(this) {
            failures[modelKey]?.let { if (System.currentTimeMillis() - it < 60_000) return null }
            inFlight.getOrPut(key) {
                loads.async {
                    val result = try {
                        val bytes = fetch(model, bucket)
                        if (bytes == null) null else decodeSlots.withPermit { decode(bytes, bucket) }
                    } catch (e: Throwable) { System.err.println("Spitify image: couldn't load $modelKey: $e"); null }
                    synchronized(this@ImageLoader) {
                        inFlight.remove(key)
                        if (result != null) put(modelKey, bucket, result) else failures[modelKey] = System.currentTimeMillis()
                    }
                    result
                }
            }
        }
        return job.await()
    }

    /** Clears the "recently failed" memory, e.g. when the library or connection changes. */
    @Synchronized fun forgetFailures() = failures.clear()

    private suspend fun fetch(model: Any, px: Int): ByteArray? = when (model) {
        is ArtKey -> model.url?.let { source(it) } ?: albumArt(model, px)
        is File -> model.takeIf { it.isFile }?.readBytes()
        is ByteArray -> model
        is String -> source(model)
        is URI -> source(model.toString())
        else -> null
    }

    private suspend fun albumArt(key: ArtKey, px: Int): ByteArray? {
        val app = container ?: return null
        val song = app.resolve(key.songId)
            ?: app.library.library.value.albumById[key.albumId]?.cover
            ?: return null
        return app.artwork.albumArt(song, px)
    }

    private suspend fun source(value: String): ByteArray? = when {
        value.startsWith("res:") -> BundledResources.bytes(value.removePrefix("res:"))
        value.startsWith("http://") || value.startsWith("https://") -> download(value)
        value.startsWith("file:") -> runCatching { File(URI(value.substringBefore('?'))).takeIf { it.isFile }?.readBytes() }.getOrNull()
        value.startsWith("data:") -> runCatching { java.util.Base64.getMimeDecoder().decode(value.substringAfter(",")) }.getOrNull()
        value.isNotBlank() -> File(value).takeIf { it.isFile }?.readBytes()
        else -> null
    }

    private suspend fun download(url: String): ByteArray? {
        val job = downloads.computeIfAbsent(url) {
            loads.async {
                try { runCatching { networkSlots.withPermit { fetchUrl(url) } }.getOrNull() } finally { downloads.remove(url) }
            }
        }
        return job.await()
    }

    private fun fetchUrl(url: String): ByteArray? {
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(diskDir, name)
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file.readBytes()
        }
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Spitify/1.0 (desktop music player)").GET().build()
        // The request timeout only covers the headers; bound the whole body too.
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).get(30, java.util.concurrent.TimeUnit.SECONDS)
        if (response.statusCode() != 200) return null
        val bytes = response.body().takeIf { it.isNotEmpty() } ?: return null
        runCatching {
            val tmp = File(diskDir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        trimDisk()
        return bytes
    }

    private var lastTrim = 0L
    private fun trimDisk() {
        val now = System.currentTimeMillis()
        if (now - lastTrim < 60_000) return
        lastTrim = now
        val files = diskDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total < 300L * 1024 * 1024) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length(); f.delete()
            if (total < 200L * 1024 * 1024) break
        }
    }

    /** Decodes and, when the source is larger than needed, downsamples with mipmapped filtering. */
    internal fun decode(bytes: ByteArray, px: Int): ImageBitmap? {
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        try {
            val longest = maxOf(image.width, image.height)
            if (longest <= 0) return null
            // Always render into a raster surface: the encoded image is decoded once, here, off the UI thread.
            val scale = minOf(1f, px.toFloat() / longest)
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            // Draw into a Bitmap that owns its pixels (a surface snapshot would die with the surface).
            val bitmap = org.jetbrains.skia.Bitmap()
            bitmap.allocN32Pixels(w, h)
            org.jetbrains.skia.Canvas(bitmap).use { canvas ->
                canvas.drawImageRect(
                    image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), Paint(), true,
                )
            }
            bitmap.setImmutable()
            return bitmap.asComposeImageBitmap()
        } finally {
            image.close()
        }
    }
}

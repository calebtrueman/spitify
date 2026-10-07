package com.localfy.app.data.art

import com.localfy.app.data.Images
import com.localfy.app.data.Song
import com.localfy.app.data.file
import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.stableSongId
import com.localfy.app.data.writeAtomically
import com.localfy.app.desktop.AppPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Album art for the desktop UI (what Coil + MediaStore thumbnails do on Android).
 * [albumArt] returns encoded JPEG/PNG bytes, looked up in this order:
 *  1. the user's custom cover ([MetadataRepository.customArt]),
 *  2. the cover embedded in the song's file (extracted once, downscaled, cached on disk),
 *  3. a cover already downloaded by [OnlineArtRepository],
 *  4. [Song.artUrl] (streams, podcasts), downloaded once and cached on disk,
 *  5. a fresh online lookup when online art is enabled.
 *
 * Results are kept in a byte-bounded in-memory LRU; concurrent requests for the same image share
 * one load; all decoding happens on Dispatchers.IO with at most 3 images in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkStore(
    private val metadata: MetadataRepository?,
    private val onlineArt: OnlineArtRepository?,
    cacheDir: File = AppPaths.cacheDir,
    /** In-memory budget for encoded images. */
    private val memoryBytes: Long = 48L * 1024 * 1024,
) {
    private val embeddedDir = File(cacheDir, "art-embedded").apply { mkdirs() }
    private val remoteDir = File(cacheDir, "art-remote").apply { mkdirs() }
    private val io = Dispatchers.IO.limitedParallelism(3)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /** LRU of encoded images keyed by request; `NONE` marks "no art" so misses are cheap too. */
    private val memory = object : LinkedHashMap<String, ByteArray>(256, 0.75f, true) {}
    private var memoryUsed = 0L

    /** Cover for [song] scaled to fit [maxPx] (rounded up to a size bucket), or null when it has none. */
    suspend fun albumArt(song: Song, maxPx: Int): ByteArray? {
        val size = bucket(maxPx)
        val key = "${song.albumId}|${song.id}|${song.artVersion}|${song.artUrl.orEmpty()}|$size"
        memoryGet(key)?.let { return if (it === NONE) null else it }
        val mine = CompletableDeferred<ByteArray?>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()
        try {
            val result = withContext(io) { runCatching { load(song, size) }.getOrNull() }
            memoryPut(key, result ?: NONE)
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    /** Drops cached images (e.g. after the user changed a cover). Disk caches are keyed by version and stay valid. */
    fun clearMemory() = synchronized(memory) { memory.clear(); memoryUsed = 0 }

    private suspend fun load(song: Song, size: Int): ByteArray? {
        metadata?.customArt(song.albumId)?.let { scaledFile(it, size)?.let { b -> return b } }
        song.file?.let { embedded(it, size)?.let { b -> return b } }
        onlineArt?.cached(song.albumId)?.let { scaledFile(it, size)?.let { b -> return b } }
        song.artUrl?.takeIf { it.startsWith("http") }?.let { remote(it, size)?.let { b -> return b } }
        song.artUrl?.takeIf { it.startsWith("file:") }?.let { url ->
            runCatching { File(java.net.URI(url)) }.getOrNull()?.let { scaledFile(it, size)?.let { b -> return b } }
        }
        if (song.file != null && onlineArt != null && onlineArt.enabled.value) {
            val fetched = onlineArt.fetch(song.albumId, song.albumArtist, song.album)
            fetched?.let { scaledFile(it, size)?.let { b -> return b } }
        }
        return null
    }

    private fun scaledFile(file: File, size: Int): ByteArray? {
        if (!file.isFile || file.length() == 0L || file.length() > 40_000_000) return null
        return Images.downscaled(file.readBytes(), size)
    }

    /** Embedded cover, extracted once per file version and size (a `.none` marker remembers files without one). */
    private fun embedded(audio: File, size: Int): ByteArray? {
        if (!audio.isFile) return null
        val stamp = stableSongId(audio.absolutePath + "|" + audio.lastModified() + "|" + audio.length())
        val cached = File(embeddedDir, "$stamp-$size.jpg")
        val none = File(embeddedDir, "$stamp.none")
        if (cached.isFile && cached.length() > 0) return runCatching { cached.readBytes() }.getOrNull()
        if (none.exists()) return null
        val raw = FileTags.artwork(audio)
        val scaled = raw?.let { Images.downscaled(it, size) }
        if (scaled == null) {
            runCatching { none.createNewFile() }
            return null
        }
        runCatching { writeAtomically(cached, scaled) }
        return scaled
    }

    private fun remote(url: String, size: Int): ByteArray? {
        val stamp = stableSongId(url)
        val original = File(remoteDir, "$stamp.img")
        val cached = File(remoteDir, "$stamp-$size.jpg")
        if (cached.isFile && cached.length() > 0) return runCatching { cached.readBytes() }.getOrNull()
        val bytes = if (original.isFile && original.length() > 0) original.readBytes() else {
            val loaded = runCatching { CoverDownload.load(url) }.getOrNull() ?: return null
            runCatching { writeAtomically(original, loaded) }
            loaded
        }
        val scaled = Images.downscaled(bytes, size) ?: return null
        runCatching { writeAtomically(cached, scaled) }
        return scaled
    }

    private fun memoryGet(key: String): ByteArray? = synchronized(memory) { memory[key] }

    private fun memoryPut(key: String, value: ByteArray) = synchronized(memory) {
        memory.put(key, value)?.let { memoryUsed -= it.size }
        memoryUsed += value.size
        val it = memory.entries.iterator()
        while (memoryUsed > memoryBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            memoryUsed -= e.value.size
            it.remove()
        }
    }

    companion object {
        private val NONE = ByteArray(0)
        private val BUCKETS = intArrayOf(64, 128, 256, 512, 1024, 2048)

        /** Rounds a requested size up to a few fixed sizes so caches stay small. */
        fun bucket(maxPx: Int): Int = BUCKETS.firstOrNull { it >= maxPx } ?: BUCKETS.last()
    }
}

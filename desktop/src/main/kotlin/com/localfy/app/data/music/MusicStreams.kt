package com.localfy.app.data.music

import com.localfy.app.data.Song
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Library membership is small saved data; listening bytes live in a separate, disposable cache.
 *
 * Desktop port of the phone's MusicStreams. Every track ever seen lives in `<storageName>-tracks.json`,
 * the last working source per track in `<storageName>-sources.json`, saved membership in
 * `<storageName>-saved.json`; each file is written atomically and coalesced on a background thread.
 *
 * Playback asks [streamUrl] for something FFmpeg can open: a local copy, a cached file, or a checked https URL.
 */
class MusicStreams(
    private val dir: File = AppPaths.dataDir,
    storageName: String = "music_streams",
    /** A downloaded copy of the same song (the desktop library supplies this); plays from disk when present. */
    @Volatile var localCopy: (OnlineTrack) -> File? = { null },
    private val sourceURL: (OnlineTrack) -> String = { it.audioURL?.takeIf(AudioFallback::validAudioURL) ?: Monochrome.audioUrl(it.id) },
    private val alternate: suspend (OnlineTrack) -> OnlineTrack? = AudioFallback::resolve,
    /** Checks the first bytes of a URL are audio; replaceable for tests. */
    private val probe: suspend (String) -> Boolean = ::probeAudio,
    /** Optional disk cache of finished streams (null disables it). */
    val listeningCache: ListeningCache? = null,
    /** When set, a resolved stream is also saved to [listeningCache] in the background for the next play. */
    private val cacheWhilePlaying: Boolean = false,
) {
    private val tracks = linkedMapOf<String, OnlineTrack>()
    private val tracksBySongId = mutableMapOf<Long, OnlineTrack>()
    private val savedIds = linkedSetOf<String>()
    private val addedAt = HashMap<String, Long>()
    /** Built Songs, so lookups from the UI and the player return the same instance instead of rebuilding one. */
    private val songs = HashMap<String, Song>()
    /** The source that last worked for each track, so a replay skips the fallback search. */
    private val sources = HashMap<String, String>()
    private val tracksStore = JsonStore(File(dir, "$storageName-tracks.json"))
    private val sourcesStore = JsonStore(File(dir, "$storageName-sources.json"))
    private val savedStore = JsonStore(File(dir, "$storageName-saved.json"))
    private val _saved = MutableStateFlow<List<Song>>(emptyList())
    val saved = _saved.asStateFlow()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One resolution per track at a time: a double-click must not run two fallback searches. */
    private val resolving = HashMap<String, Mutex>()

    init {
        runCatching {
            val entries = tracksStore.readArray() ?: JSONArray()
            for (i in 0 until entries.length()) runCatching { Monochrome.parseTrack(entries.getJSONObject(i)) }.getOrNull()?.let { tracks[it.id] = it }
        }
        runCatching { sourcesStore.readObject()?.let { o -> o.keys().forEach { sources[it] = o.getString(it) } } }
        runCatching {
            val o = savedStore.readObject() ?: JSONObject()
            val ids = o.optJSONArray("saved") ?: JSONArray()
            for (i in 0 until ids.length()) savedIds += ids.getString(i)
            o.optJSONObject("added")?.let { a -> a.keys().forEach { addedAt[it] = a.optLong(it) } }
        }
        tracks.values.forEach { tracksBySongId[streamId(it.id)] = it }
        publish()
    }

    @Synchronized fun register(incoming: OnlineTrack): Song {
        val previous = tracks[incoming.id]
        val track = if (previous == null) incoming else incoming.copy(
            album = incoming.album.ifBlank { previous.album },
            releaseId = incoming.releaseId.ifBlank { previous.releaseId },
            albumArtist = incoming.albumArtist ?: previous.albumArtist,
            artistNames = incoming.artistNames ?: previous.artistNames,
            artwork = incoming.artwork ?: previous.artwork,
        )
        if (tracks[track.id] != track) {
            tracks[track.id] = track
            tracksBySongId[streamId(track.id)] = track
            songs.remove(track.id)
            // A search page or radio refill registers dozens of tracks at once: one write for all of them.
            tracksStore.save(500) { tracksJson() }
        }
        return cachedSong(track)
    }

    /** Copies under the lock (cheap), builds JSON outside it so lookups never wait on it. */
    private fun tracksJson(): String {
        val snapshot = synchronized(this) { tracks.values.toList() }
        return JSONArray().apply { snapshot.forEach { put(JSONObject(it.json())) } }.toString()
    }
    private fun sourcesJson(): String = JSONObject(synchronized(this) { HashMap(sources) } as Map<*, *>).toString()
    private fun savedJson(): String = synchronized(this) {
        JSONObject().put("saved", JSONArray(savedIds.toList())).put("added", JSONObject(addedAt as Map<*, *>))
    }.toString()

    private fun cachedSong(track: OnlineTrack): Song = songs.getOrPut(track.id) { song(track) }
    @Synchronized fun knownTracks(): List<OnlineTrack> = tracks.values.toList()
    @Synchronized fun track(id: String): OnlineTrack? = tracks[id]
    @Synchronized fun track(song: Song): OnlineTrack? = song.sourceUri?.takeIf { song.isStream }?.substringAfterLast('/')?.let(tracks::get)
    @Synchronized fun lookup(id: Long): Song? = tracksBySongId[id]?.let(::cachedSong)
    @Synchronized fun contains(track: OnlineTrack) = track.id in savedIds
    @Synchronized fun save(items: List<OnlineTrack>) {
        items.forEach { register(it); if (savedIds.add(it.id)) { addedAt[it.id] = System.currentTimeMillis() / 1000; songs.remove(it.id) } }
        persistSaved()
    }
    @Synchronized fun remove(items: List<OnlineTrack>) { items.forEach { savedIds.remove(it.id) }; persistSaved() }
    private fun persistSaved() { savedStore.save(200) { savedJson() }; publish() }
    private fun publish() { _saved.value = savedIds.mapNotNull(tracks::get).map(::cachedSong) }

    fun song(track: OnlineTrack) = Song(
        id = streamId(track.id), title = track.title, artist = track.artist, album = track.album,
        albumId = streamId("album:" + track.releaseId), albumArtist = track.albumArtist ?: track.primaryArtist,
        durationMs = track.durationMs, track = track.track, disc = track.disc, year = 0, genre = null,
        folder = "", dateAddedSec = synchronized(this) { addedAt[track.id] ?: 0 }, sizeBytes = 0, mimeType = null,
        sourceUri = "spitify://music/${track.id}", artUrl = track.artwork, explicit = track.explicit, artistNames = track.artistNames,
    )

    fun lastSource(track: OnlineTrack): OnlineTrack = synchronized(this) { sources[track.id] }?.let {
        runCatching { Monochrome.parseTrack(JSONObject(it)) }.getOrNull()
    } ?: track

    /** Called on every stream start; only writes when the working source actually changed. */
    fun rememberSource(track: OnlineTrack) {
        val json = track.json()
        synchronized(this) {
            if (sources[track.id] == json) return
            sources[track.id] = json
        }
        sourcesStore.save(500) { sourcesJson() }
    }

    /** Source URLs that FFmpeg couldn't play, per track: the next [streamUrl] checks alternatives instead. */
    private val failedUrls = HashMap<String, MutableSet<String>>()

    /**
     * Something FFmpeg can open for [song]: a `file:` URI for a downloaded or cached copy, otherwise an
     * https URL. Like the phones, the usual source is returned straight away, unchecked: the server takes
     * seconds to answer each request, so checking first doubled the wait. When the player reports a URL
     * as unplayable ([sourceFailed]), later calls skip it and check the alternatives (another Monochrome
     * copy, Internet Archive, YouTube; up to four sources) by their first bytes. Null when nothing plays.
     * Non-stream songs return their own sourceUri.
     */
    suspend fun streamUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (!song.isStream) return@withContext song.sourceUri
        val track = track(song) ?: return@withContext null
        runCatching { localCopy(track) }.getOrNull()?.takeIf { it.isFile }?.let { return@withContext it.toURI().toString() }
        val lock = synchronized(resolving) { resolving.getOrPut(track.id) { Mutex() } }
        lock.withLock {
            val failed = synchronized(failedUrls) { failedUrls[track.id]?.toSet().orEmpty() }
            var candidate = lastSource(track)
            repeat(4) {
                val url = runCatching { sourceURL(candidate) }.getOrNull()
                if (url != null && url !in failed) {
                    listeningCache?.cachedFileFor(url)?.let { rememberSource(candidate); return@withContext it.toURI().toString() }
                    val works = failed.isEmpty() || try { probe(url) } catch (e: Exception) { if (e is CancellationException) throw e; false }
                    if (works) {
                        rememberSource(candidate)
                        if (cacheWhilePlaying) listeningCache?.let { cache ->
                            // After playback has its own connection going: a second full download at the
                            // same moment would slow the start.
                            background.launch { delay(CACHE_FILL_DELAY); runCatching { cache.fill(url) } }
                        }
                        return@withContext url
                    }
                }
                candidate = try { alternate(candidate) } catch (e: Exception) { if (e is CancellationException) throw e; null } ?: return@withContext null
            }
            null
        }
    }

    /** The player couldn't open [url] for [song]; the next [streamUrl] looks elsewhere. */
    fun sourceFailed(song: Song, url: String) {
        val track = track(song) ?: return
        synchronized(failedUrls) { failedUrls.getOrPut(track.id) { mutableSetOf() } += url }
        if (runCatching { sourceURL(lastSource(track)) }.getOrNull() == url) synchronized(this) { sources.remove(track.id) }
    }

    /** The finished cached copy of a stream URL, when the listening cache holds one. */
    fun cachedFileFor(url: String): File? = listeningCache?.cachedFileFor(url)

    /** Writes pending changes now (call on shutdown). */
    fun flush() { tracksStore.flush(); sourcesStore.flush(); savedStore.flush() }

    companion object {
        const val CACHE_LIMIT = 1024L * 1024 * 1024
        const val CACHE_FILL_DELAY = 20_000L
        fun streamId(id: String): Long {
            val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            val value = java.nio.ByteBuffer.wrap(bytes).long and 0x0fffffffffffffffL
            return -(1L shl 62) - value
        }

        /** True when [url] answers with bytes that start like a known audio container. */
        fun probeAudio(url: String): Boolean {
            val conn = try { HttpGet.open(url, mapOf("Range" to "bytes=0-127"), connectTimeout = 6_000, readTimeout = 12_000) } catch (_: IOException) { return false }
            try {
                if (conn.responseCode !in listOf(200, 206)) return false
                val head = conn.inputStream.use { input ->
                    val buf = ByteArray(128); var count = 0
                    while (count < buf.size) { val n = input.read(buf, count, buf.size - count); if (n < 0) break; count += n }
                    buf.copyOf(count)
                }
                return head.size >= 12 && AudioContainer.detect(head) != null
            } catch (_: IOException) { return false } finally { conn.disconnect() }
        }
    }
}

/**
 * Disposable cache of whole streamed files (LRU by last use, [limit] bytes). Files are only visible once
 * complete, so a reader never sees half a song. Keyed by the source URL.
 */
class ListeningCache(private val dir: File = AppPaths.cache("listening"), private val limit: Long = MusicStreams.CACHE_LIMIT,
                     private val transport: DownloadTransport = HttpDownloadTransport()) {
    private val filling = HashSet<String>()

    private fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(40)

    /** The finished file for [url], refreshed as most recently used; null when not cached. */
    fun cachedFileFor(url: String): File? {
        val f = File(dir, key(url) + ".audio")
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /** Downloads [url] into the cache (no-op when cached or already filling), then trims to [limit]. */
    suspend fun fill(url: String): File? {
        cachedFileFor(url)?.let { return it }
        val k = key(url)
        synchronized(filling) { if (!filling.add(k)) return null }
        val part = File(dir, "$k.part")
        try {
            dir.mkdirs()
            transport.fetch(url, part) { _, _ -> }
            val head = part.inputStream().use { it.readNBytes(128) }
            if (AudioContainer.detect(head) == null) { part.delete(); return null }
            val done = File(dir, "$k.audio")
            if (!part.renameTo(done)) { part.delete(); return null }
            trim()
            return done
        } catch (e: Exception) {
            if (e !is CancellationException) part.delete()
            throw e
        } finally { synchronized(filling) { filling.remove(k) } }
    }

    @Synchronized fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".audio") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) { total += f.length(); if (total > limit) f.delete() }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

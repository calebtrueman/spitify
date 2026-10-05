package com.localfy.app.data.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import com.localfy.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Library membership is small saved data. Listening bytes live in a separate, disposable cache. */
class MusicStreams(private val context: Context, storageName: String = "music_streams") {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val tracks = linkedMapOf<String, OnlineTrack>()
    private val tracksBySongId = mutableMapOf<Long, OnlineTrack>()
    private val savedIds = prefs.getStringSet("saved", emptySet())!!.toMutableSet()
    /** Built Songs, so lookups from the UI and the player return the same instance instead of rebuilding one. */
    private val songs = HashMap<String, Song>()
    /** Every track ever seen is persisted as one JSON blob; writes are coalesced off the calling (often main) thread. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var writePending = false
    private val _saved = MutableStateFlow<List<Song>>(emptyList())
    val saved = _saved.asStateFlow()
    init {
        runCatching {
            val entries = JSONArray(prefs.getString("tracks", "[]"))
            for (i in 0 until entries.length()) Monochrome.parseTrack(entries.getJSONObject(i))?.let { tracks[it.id] = it }
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
            persistTracks()
        }
        return cachedSong(track)
    }
    private fun persistTracks() {
        if (writePending) return
        writePending = true
        writer.execute {
            Thread.sleep(500) // a search page or radio refill registers dozens of tracks at once
            val json = synchronized(this) { writePending = false; tracks.values.map { it.json() } }
            prefs.edit().putString("tracks", JSONArray(json.map { JSONObject(it) }).toString()).apply()
        }
    }
    private fun cachedSong(track: OnlineTrack): Song = songs.getOrPut(track.id) { song(track) }
    @Synchronized fun knownTracks(): List<OnlineTrack> = tracks.values.toList()
    @Synchronized fun track(id: String): OnlineTrack? = tracks[id]
    @Synchronized fun track(song: Song): OnlineTrack? = song.sourceUri?.takeIf { it.scheme == "spitify" }?.lastPathSegment?.let(tracks::get)
    @Synchronized fun lookup(id: Long): Song? = tracksBySongId[id]?.let(::cachedSong)
    @Synchronized fun contains(track: OnlineTrack) = track.id in savedIds
    @Synchronized fun save(items: List<OnlineTrack>) { items.forEach { register(it); if (savedIds.add(it.id)) { prefs.edit().putLong("added:${it.id}", System.currentTimeMillis() / 1000).apply(); songs.remove(it.id) } }; persistSaved() }
    @Synchronized fun remove(items: List<OnlineTrack>) { items.forEach { savedIds.remove(it.id) }; persistSaved() }
    private fun persistSaved() { prefs.edit().putStringSet("saved", savedIds.toSet()).apply(); publish() }
    private fun publish() { _saved.value = savedIds.mapNotNull(tracks::get).map(::cachedSong) }
    fun song(track: OnlineTrack) = Song(
        id = streamId(track.id), title = track.title, artist = track.artist, album = track.album,
        albumId = streamId("album:" + track.releaseId), albumArtist = track.albumArtist ?: track.primaryArtist,
        durationMs = track.durationMs, track = track.track, disc = track.disc, year = 0, genre = null,
        folder = "", dateAddedSec = prefs.getLong("added:${track.id}", 0), sizeBytes = 0, mimeType = null,
        sourceUri = Uri.parse("spitify://music/${track.id}"), artUrl = track.artwork, explicit = track.explicit, artistNames = track.artistNames,
    )
    internal fun lastSource(track: OnlineTrack): OnlineTrack = prefs.getString("source:${track.id}", null)?.let {
        runCatching { Monochrome.parseTrack(JSONObject(it)) }.getOrNull()
    } ?: track
    internal fun rememberSource(track: OnlineTrack) { prefs.edit().putString("source:${track.id}", track.json()).apply() }
    companion object {
        const val CACHE_LIMIT = 1024L * 1024 * 1024
        fun streamId(id: String): Long {
            val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            val value = java.nio.ByteBuffer.wrap(bytes).long and 0x0fffffffffffffffL
            return -(1L shl 62) - value
        }
    }
}

/** One cache owner for all music players. Cached streams never enter MediaStore. */
@UnstableApi
object ListeningCache {
    private var instance: SimpleCache? = null
    @Synchronized fun get(context: Context): SimpleCache = instance ?: SimpleCache(
        File(context.cacheDir, "listening"), LeastRecentlyUsedCacheEvictor(MusicStreams.CACHE_LIMIT),
        StandaloneDatabaseProvider(context.applicationContext),
    ).also { instance = it }
    fun clear(context: Context) { val cache = get(context); cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } } }
    fun factory(context: Context): DataSource.Factory = DataSource.Factory { MusicStreamDataSource(context.applicationContext) }
}

/** Source changes happen only before any bytes are consumed; byte offsets from different files must never mix. */
@UnstableApi
internal class MusicStreamDataSource(private val context: Context,
    private val sourceURL: (OnlineTrack) -> String = { it.audioURL?.takeIf(AudioFallback::validAudioURL) ?: Monochrome.audioUrl(it.id) },
    private val alternate: suspend (OnlineTrack) -> OnlineTrack? = AudioFallback::resolve,
    private val upstreamFactory: DataSource.Factory = DefaultHttpDataSource.Factory().setConnectTimeoutMs(6_000).setReadTimeoutMs(12_000).setAllowCrossProtocolRedirects(true),
) : DataSource {
    private val streams get() = (context as com.localfy.app.LocalfyApp).musicStreams
    private var source: DataSource? = null
    private var prefix = ByteArray(0)
    private var prefixOffset = 0
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; source?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "spitify") return openSource(DefaultDataSource.Factory(context, plainHttp).createDataSource(), dataSpec)
        val track = streams.track(dataSpec.uri.lastPathSegment.orEmpty()) ?: throw IOException("Song is no longer available")
        val local = localCopy(track)
        if (local != null) return openSource(DefaultDataSource.Factory(context).createDataSource(), dataSpec.withUri(local))
        var candidate = streams.lastSource(track)
        var lastError: IOException? = null
        repeat(4) {
            val url = sourceURL(candidate)
            val cached = CacheDataSource.Factory().setCache(ListeningCache.get(context))
                .setUpstreamDataSourceFactory(upstreamFactory).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
            try {
                val length = openSource(cached, dataSpec.buildUpon().setUri(url).setKey(url).build())
                if (dataSpec.position == 0L) {
                    val first = ByteArray(12)
                    var count = 0
                    while (count < first.size) {
                        val n = cached.read(first, count, first.size - count)
                        if (n < 0) break
                        count += n
                    }
                    if (count < 12 || !audioHeader(first)) {
                        close(); ListeningCache.get(context).removeResource(url)
                        throw IOException("The source did not return audio")
                    }
                    prefix = first.copyOf(count); prefixOffset = 0
                }
                streams.rememberSource(candidate)
                return length
            } catch (e: IOException) {
                close(); lastError = e
                if (dataSpec.position != 0L) throw e
                candidate = runBlocking { alternate(candidate) } ?: throw e
            }
        }
        throw lastError ?: IOException("Could not play this song")
    }
    private fun openSource(next: DataSource, spec: DataSpec): Long {
        source = next; listeners.forEach(next::addTransferListener); return next.open(spec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (prefixOffset < prefix.size) {
            val count = minOf(length, prefix.size - prefixOffset)
            prefix.copyInto(buffer, offset, prefixOffset, prefixOffset + count); prefixOffset += count; return count
        }
        return source?.read(buffer, offset, length) ?: throw IOException("Stream is closed")
    }
    override fun getUri(): Uri? = source?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders ?: emptyMap()
    override fun close() { try { source?.close() } finally { source = null; prefix = ByteArray(0); prefixOffset = 0 } }
    private fun audioHeader(bytes: ByteArray): Boolean = AudioContainer.detect(bytes) != null

    /**
     * A downloaded copy of the same song plays from disk. Matching walks the whole library, and
     * ExoPlayer reopens the source on every seek and retry, so the answer is remembered per track.
     */
    private fun localCopy(track: OnlineTrack): Uri? {
        val songs = (context as com.localfy.app.LocalfyApp).library.rawSongs.value
        synchronized(localMatches) {
            if (localMatchesFor !== songs) { localMatches.clear(); localMatchesFor = songs }
            if (track.id in localMatches) return localMatches[track.id]
        }
        val match = songs.firstOrNull {
            SearchMatch.sameSong(it.title, it.artist, it.durationMs, track.title, track.artist, track.durationMs) && AudioFallback.sameRelease(it.album, track.album)
        }?.uri
        synchronized(localMatches) { if (localMatchesFor === songs) localMatches[track.id] = match }
        return match
    }

    private companion object {
        val plainHttp: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // podcast hosts and analytics prefixes redirect http -> https
            .setUserAgent("Spitify/1.0 (Android)")
        val localMatches = HashMap<String, Uri?>()
        var localMatchesFor: Any? = null
    }
}

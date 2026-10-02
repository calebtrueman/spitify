package com.localfy.app.data.social

import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.music.*
import kotlinx.coroutines.*

object PlaylistMatches {
    private val pending = mutableMapOf<String, Deferred<Song>>()
    private val warming = mutableSetOf<String>()
    fun prepare(playlist: SharedPlaylist, app: LocalfyApp) {
        val key = "${playlist.key}:${playlist.revision}"
        if (!warming.add(key)) return
        app.appScope.launch {
            try {
                for (batch in playlist.tracks.chunked(4)) coroutineScope {
                    batch.map { track -> async { try { resolve(track, app) } catch (e: Exception) { if (e is CancellationException) throw e; null } } }.awaitAll()
                }
            } finally { warming.remove(key) }
        }
    }
    suspend fun resolve(track: SharedTrack, app: LocalfyApp): Song = withContext(Dispatchers.Main.immediate) {
        fun same(title: String, artist: String, duration: Long) = SearchMatch.fold(title) == SearchMatch.fold(track.title) && SearchMatch.fold(artist) == SearchMatch.fold(track.artist) && (track.durationMs == 0L || kotlin.math.abs(duration - track.durationMs) < 5000)
        app.library.library.value.songs.firstOrNull { same(it.title, it.artist, it.durationMs) }?.let { return@withContext it }
        track.sourceID?.let { return@withContext app.musicStreams.register(OnlineTrack(it, track.title, track.artist, track.album, track.releaseID.orEmpty(), track.durationMs, 0, 1, track.artwork, true)) }
        app.musicStreams.knownTracks().firstOrNull { same(it.title, it.artist, it.durationMs) }?.let { return@withContext app.musicStreams.song(it) }
        val key = "${SearchMatch.fold(track.title)}|${SearchMatch.fold(track.artist)}|${track.durationMs}"
        val task = pending[key] ?: app.appScope.async(start = CoroutineStart.LAZY) {
            val online = Monochrome.search("${track.title} ${track.artist}").firstOrNull { same(it.title, it.artist, it.durationMs) } ?: error("No matching copy of “${track.title}” was found.")
            app.musicStreams.register(online)
        }.also { job -> pending[key] = job; job.invokeOnCompletion { app.appScope.launch { if (pending[key] === job) pending.remove(key) } } }
        task.await()
    }
}

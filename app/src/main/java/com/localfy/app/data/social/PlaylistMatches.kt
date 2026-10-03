package com.localfy.app.data.social

import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.music.*
import kotlinx.coroutines.*

object PlaylistMatches {
    val failed = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    fun key(track: SharedTrack) = "${track.recordingKey}|${track.durationMs}"
    fun choose(track: SharedTrack, song: Song, app: LocalfyApp) {
        app.getSharedPreferences("playlist_matches", 0).edit().putLong(key(track), song.id).apply()
        failed.value = failed.value - key(track); saveFailures(app)
    }

    private var restored = false
    private fun restore(app: LocalfyApp) { if (!restored) { failed.value = app.getSharedPreferences("playlist_matches", 0).getStringSet("failed", emptySet()).orEmpty().toSet(); restored = true } }
    private fun saveFailures(app: LocalfyApp) { app.getSharedPreferences("playlist_matches", 0).edit().putStringSet("failed", failed.value).apply() }
    fun retry(playlist: SharedPlaylist, app: LocalfyApp) { restore(app); failed.value = failed.value - playlist.tracks.map(::key).toSet(); saveFailures(app); prepare(playlist, app) }
    private val pending = mutableMapOf<String, Deferred<Song>>()
    private val warming = mutableSetOf<String>()
    fun prepare(playlist: SharedPlaylist, app: LocalfyApp) {
        restore(app)
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
        restore(app)
        try { resolveCopy(track, app).also { failed.value = failed.value - key(track); saveFailures(app) } }
        catch (e: Exception) { if (e !is CancellationException) { failed.value = failed.value + key(track); saveFailures(app) }; throw e }
    }
    private suspend fun resolveCopy(track: SharedTrack, app: LocalfyApp): Song = withContext(Dispatchers.Main.immediate) {
        val chosen = app.getSharedPreferences("playlist_matches", 0).getLong(key(track), Long.MIN_VALUE)
        app.library.library.value.songs.firstOrNull { it.id == chosen }?.let { return@withContext it }

        fun same(title: String, artist: String, duration: Long) = SearchMatch.fold(title) == SearchMatch.fold(track.title) && SearchMatch.fold(artist) == SearchMatch.fold(track.artist) && (track.durationMs == 0L || kotlin.math.abs(duration - track.durationMs) < 5000)
        app.library.library.value.songs.firstOrNull { same(it.title, it.artist, it.durationMs) }?.let { return@withContext it }
        track.sourceID?.let { return@withContext app.musicStreams.register(OnlineTrack(it, track.title, track.artist, track.album, track.releaseID.orEmpty(), track.durationMs, 0, 1, track.artwork, true)) }
        app.musicStreams.knownTracks().firstOrNull { same(it.title, it.artist, it.durationMs) }?.let { return@withContext app.musicStreams.song(it) }
        check(key(track) !in failed.value) { "Choose a local copy or try matching again." }
        val key = "${SearchMatch.fold(track.title)}|${SearchMatch.fold(track.artist)}|${track.durationMs}"
        val task = pending[key] ?: app.appScope.async(start = CoroutineStart.LAZY) {
            val online = try { Monochrome.search("${track.title} ${track.artist}").firstOrNull { it.playable && same(it.title, it.artist, it.durationMs) } } catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (online != null) app.musicStreams.register(online) else {
                val seed = OnlineTrack("external-" + SocialRules.hash(PlaylistMatches.key(track).toByteArray()), track.title, track.artist, track.album, "", track.durationMs, 0, 1, track.artwork, false)
                val alternate = try { AudioFallback.resolve(seed) } catch (e: Exception) { if (e is CancellationException) throw e; null }
                app.musicStreams.register((alternate ?: error("No matching copy of “${track.title}” was found.")).copy(playable = true))
            }
        }.also { job -> pending[key] = job; job.invokeOnCompletion { app.appScope.launch { if (pending[key] === job) pending.remove(key) } } }
        task.await()
    }
}

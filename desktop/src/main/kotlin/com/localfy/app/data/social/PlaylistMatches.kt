package com.localfy.app.data.social

import com.localfy.app.data.Song
import com.localfy.app.data.music.AudioFallback
import com.localfy.app.data.music.Monochrome
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.data.music.SearchMatch
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finds a playable copy of each shared / Spotify track: the copy the user chose, a matching
 * library song, the catalogue stream named by the share, an already-known stream, then an online
 * search and finally the audio fallbacks. Android keeps this as an object bound to the app; the
 * desktop takes its sources as functions so it has no dependency on the player or library classes.
 *
 * [librarySongs], [registerStream] and [knownTracks] are called from a background thread and must
 * be thread safe (e.g. read a StateFlow's value). [registerStream] turns a catalogue track into a
 * playable [Song] (Android: `MusicStreams.register` / `MusicStreams.song`).
 */
class PlaylistMatches(
    private val scope: CoroutineScope,
    private val librarySongs: () -> List<Song>,
    private val registerStream: (OnlineTrack) -> Song,
    private val searchOnline: suspend (String) -> List<OnlineTrack> = Monochrome::search,
    private val knownTracks: () -> List<OnlineTrack> = { emptyList() },
    private val fallback: suspend (OnlineTrack) -> OnlineTrack? = AudioFallback::resolve,
    file: File = AppPaths.data("playlist_matches.json"),
) {
    val failed = MutableStateFlow<Set<String>>(emptySet())
    private val store = JsonStore(file)
    private val chosen = mutableMapOf<String, Long>()
    /** One logical thread for the match tables; awaiting work does not hold it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val pending = mutableMapOf<String, Deferred<Song>>()
    private val warming = mutableSetOf<String>()

    init {
        store.readObject()?.let { o ->
            o.optJSONObject("chosen")?.let { c -> c.keys().forEach { k -> c.optLong(k, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { chosen[k] = it } } }
            failed.value = o.strings("failed").toSet()
        }
    }

    private fun save() {
        val text = JSONObject().put("chosen", JSONObject(chosen.toMap())).put("failed", JSONArray(failed.value.toList())).toString()
        store.save { text }
    }

    /** Remembers [song] as the copy to play for [track]. */
    fun choose(track: SharedTrack, song: Song) { scope.launch(confined) { chosen[key(track)] = song.id; failed.value = failed.value - key(track); save() } }

    fun retry(playlist: SharedPlaylist) { scope.launch(confined) { failed.value = failed.value - playlist.tracks.map(::key).toSet(); save(); prepare(playlist) } }

    /** Starts matching every track in the background, four at a time. */
    fun prepare(playlist: SharedPlaylist) {
        scope.launch(confined) {
            val key = "${playlist.key}:${playlist.revision}"
            if (!warming.add(key)) return@launch
            try {
                for (batch in playlist.tracks.chunked(4)) coroutineScope {
                    batch.map { track -> async { try { resolve(track) } catch (e: Exception) { if (e is CancellationException) throw e; null } } }.awaitAll()
                }
            } finally { warming.remove(key) }
        }
    }

    /** A playable song for [track]; throws a user-readable error when none is found. */
    suspend fun resolve(track: SharedTrack): Song = withContext(confined) {
        try { resolveCopy(track).also { if (key(track) in failed.value) { failed.value = failed.value - key(track); save() } } }
        catch (e: Exception) { if (e !is CancellationException) { failed.value = failed.value + key(track); save() }; throw e }
    }

    private suspend fun resolveCopy(track: SharedTrack): Song {
        val library = librarySongs()
        chosen[key(track)]?.let { id -> library.firstOrNull { it.id == id }?.let { return it } }
        fun same(title: String, artist: String, duration: Long) = SearchMatch.fold(title) == SearchMatch.fold(track.title) && SearchMatch.fold(artist) == SearchMatch.fold(track.artist) && (track.durationMs == 0L || kotlin.math.abs(duration - track.durationMs) < 5000)
        library.firstOrNull { same(it.title, it.artist, it.durationMs) }?.let { return it }
        track.sourceID?.let { return registerStream(OnlineTrack(it, track.title, track.artist, track.album, track.releaseID.orEmpty(), track.durationMs, 0, 1, track.artwork, true)) }
        knownTracks().firstOrNull { same(it.title, it.artist, it.durationMs) }?.let { return registerStream(it) }
        check(key(track) !in failed.value) { "Choose a local copy or try matching again." }
        val key = "${SearchMatch.fold(track.title)}|${SearchMatch.fold(track.artist)}|${track.durationMs}"
        val task = pending[key] ?: scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val online = try { searchOnline("${track.title} ${track.artist}").firstOrNull { it.playable && same(it.title, it.artist, it.durationMs) } } catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (online != null) registerStream(online) else {
                val seed = OnlineTrack("external-" + SocialRules.hash(key(track).toByteArray()), track.title, track.artist, track.album, "", track.durationMs, 0, 1, track.artwork, false)
                val alternate = try { fallback(seed) } catch (e: Exception) { if (e is CancellationException) throw e; null }
                registerStream((alternate ?: error("No matching copy of “${track.title}” was found.")).copy(playable = true))
            }
        }.also { job -> pending[key] = job; job.invokeOnCompletion { scope.launch(confined) { if (pending[key] === job) pending.remove(key) } } }
        return task.await()
    }

    companion object {
        fun key(track: SharedTrack) = "${track.recordingKey}|${track.durationMs}"
    }
}

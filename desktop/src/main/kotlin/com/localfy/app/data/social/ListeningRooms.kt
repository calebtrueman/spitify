package com.localfy.app.data.social

import com.localfy.app.data.Song
import com.localfy.app.data.music.Monochrome
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.data.music.SearchMatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a Listening Room needs from the desktop player. Android talks to its PlayerConnection
 * directly; the desktop player implements this so the room code does not depend on it.
 * Called on [ListeningRooms]'s scope (the UI thread).
 */
interface RoomPlayer {
    /** The play queue in order; null for an entry that can no longer be resolved to a song. */
    val queue: List<Song?>
    val currentIndex: Int
    val isPlaying: Boolean
    val speed: Float
    /** Label of what started the current queue, e.g. "Room: Friday". */
    val source: String?
    val positionMs: Long
    fun setPlaying(playing: Boolean)
    /** Locks playback speed to the host's [speed] while in a Room; null ends Room playback. */
    fun setRoomPlayback(speed: Float?)
    fun next()
    fun seekTo(positionMs: Long)
    fun removeAt(index: Int)
    fun playSongs(songs: List<Song>, shuffle: Boolean = false, source: String)
    fun appendFromSource(songs: List<Song>)
}

/**
 * Hosts or follows a Listening Room (desktop port of the Android class; same public members).
 * [scope] should run on the UI thread; it also carries the half-second room tick.
 */
class ListeningRooms(
    private val social: SocialRepository,
    private val player: RoomPlayer,
    private val scope: CoroutineScope,
    private val librarySongs: () -> List<Song>,
    private val registerStream: (OnlineTrack) -> Song,
    private val searchOnline: suspend (String) -> List<OnlineTrack> = Monochrome::search,
    /** The catalogue track behind a streamed song (Android: MusicStreams.track). */
    private val trackFor: (Song) -> OnlineTrack? = { null },
) {
    private var applying: Job? = null
    private var playedTrack: String? = null
    private val hostTracks = mutableMapOf<String, SharedTrack>()
    private val messages = MutableStateFlow<String?>(null)
    val message = messages.asStateFlow()
    val room get() = social.rooms.activeKey?.let { social.rooms.rooms[it] }
    val isHost get() = room?.host == social.publicKey
    private val ticker: Job
    init {
        social.onRoomUpdate = ::received; social.onRoomRequest = ::request
        ticker = scope.launch { while (isActive) { delay(500); runCatching { tick() }.onFailure { if (it is CancellationException) throw it } } }
    }
    private fun music(song: Song) = !song.isPodcast && !song.isAudiobook
    fun host(name: String) {
        hostTracks.clear()
        val songs = player.queue.filterNotNull().filter(::music).take(200)
        social.hostRoom(name, songs.map { SharedTrack.from(it, trackFor(it)) })
        scope.launch { tick() }
    }
    suspend fun approve(incoming: IncomingRoomRequest, allowed: Boolean) {
        val current = room ?: return; if (!isHost) return
        social.rooms.requests.removeAll { it.request.id == incoming.request.id }
        check(!allowed || current.members.size < 32 || incoming.sender in current.members) { "This Room already has 32 guests." }
        val members = if (allowed) (current.members + incoming.sender).distinct() else current.members
        social.sendRoom(current.copy(members = members, revision = current.revision + 1, observedAt = SocialRules.now), listOf(incoming.sender))
    }
    suspend fun setControls(allowed: Boolean) { val current = room ?: return; if (isHost) social.sendRoom(current.copy(allowControls = allowed, revision = current.revision + 1, observedAt = SocialRules.now)) }
    suspend fun leave() {
        val old = room; val pending = social.rooms.requestedKey
        social.rooms.activeKey = null; social.rooms.requestedKey = null; social.roomChanged(); playedTrack = null; applying?.cancel()
        if (old != null) {
            if (old.host == social.publicKey) runCatching { social.sendRoom(old.copy(ended = true, playing = false, revision = old.revision + 1, observedAt = SocialRules.now)) }.onFailure { messages.value = it.message }
            else { player.setPlaying(false); player.setRoomPlayback(null); runCatching { social.roomRequest(RoomRequest(roomID = old.id, host = old.host, action = "leave")) } }
        } else if (pending != null) runCatching { social.roomRequest(RoomRequest(roomID = pending.substringAfter(':'), host = pending.substringBefore(':'), action = "leave")) }
    }
    suspend fun control(playing: Boolean? = null, next: Boolean = false, position: Long? = null) {
        val current = room ?: return
        if (isHost) { if (next) player.next(); playing?.let(player::setPlaying); position?.let(player::seekTo); tick() }
        else { check(current.allowControls) { "The host has not enabled guest controls." }; social.roomRequest(RoomRequest(roomID = current.id, host = current.host, action = if (next) "next" else "control", positionMs = position, playing = playing)) }
    }
    suspend fun add(tracks: List<SharedTrack>) {
        val current = room ?: return
        if (isHost) { append(tracks); tick() } else social.roomRequest(RoomRequest(roomID = current.id, host = current.host, action = "add", tracks = tracks.take(100)))
    }
    suspend fun remove(trackID: String) {
        val current = room ?: return
        if (isHost) {
            val index = current.queue.indexOfFirst { it.id == trackID }
            val entries = player.queue.mapIndexedNotNull { i, song -> song?.takeIf(::music)?.let { i } }
            entries.getOrNull(index)?.let { player.removeAt(it); tick() }
        } else { check(current.allowControls) { "The host has not enabled guest controls." }; social.roomRequest(RoomRequest(roomID = current.id, host = current.host, action = "remove", trackID = trackID)) }
    }
    /** Stops the room tick (app exit). */
    fun close() { ticker.cancel(); applying?.cancel() }

    private suspend fun match(track: SharedTrack): Song {
        fun same(title: String, artist: String, duration: Long) = SearchMatch.fold(title) == SearchMatch.fold(track.title) && SearchMatch.fold(artist) == SearchMatch.fold(track.artist) && (track.durationMs == 0L || kotlin.math.abs(duration - track.durationMs) < 5000)
        withContext(Dispatchers.Default) { librarySongs().firstOrNull { same(it.title, it.artist, it.durationMs) } }?.let { return it }
        val online = track.sourceID?.let { OnlineTrack(it, track.title, track.artist, track.album, track.releaseID.orEmpty(), track.durationMs, 0, 1, track.artwork, true) }
            ?: searchOnline("${track.title} ${track.artist}").firstOrNull { same(it.title, it.artist, it.durationMs) }
            ?: error("No matching copy of “${track.title}” was found.")
        return registerStream(online)
    }
    private suspend fun append(tracks: List<SharedTrack>) {
        val current = room ?: return
        check(current.queue.size + tracks.size <= 200) { "A Room can hold up to 200 songs." }
        for (track in tracks) {
            val song = match(track)
            if (room?.key != current.key || !isHost) return
            if (player.queue.isEmpty()) player.playSongs(listOf(song), source = "Room: ${current.name}") else player.appendFromSource(listOf(song))
        }
    }
    private suspend fun tick() {
        val current = room ?: return
        if (!current.live || !social.enabled) { leave(); return }
        if (!isHost) { if (SocialRules.now - current.observedAt > 45_000) { if (player.isPlaying) player.setPlaying(false); messages.value = "Waiting for the host to reconnect." }; return }
        val entries = player.queue.mapIndexedNotNull { index, song -> song?.let { index to it } }.filter { music(it.second) }.take(200)
        val songs = entries.map { it.second }
        val tracks = songs.mapIndexed { index, song -> hostTracks.getOrPut("$index:${song.id}") { SharedTrack.from(song, trackFor(song)) } }
        val keys = songs.mapIndexed { index, song -> "$index:${song.id}" }.toSet(); hostTracks.keys.retainAll(keys)
        val currentID = tracks.getOrNull(entries.indexOfFirst { it.first == player.currentIndex })?.id
        val update = current.copy(queue = tracks, currentID = currentID, positionMs = player.positionMs.coerceIn(0, 86_400_000), playing = player.isPlaying && currentID != null, speed = player.speed, observedAt = SocialRules.now, revision = current.revision + 1)
        val changed = current.queue != update.queue || current.currentID != update.currentID || current.playing != update.playing || current.speed != update.speed || kotlin.math.abs(RoomState.expectedPosition(current) - update.positionMs) > 2000
        if (!changed && SocialRules.now - current.observedAt < 5000) return
        try { social.sendRoom(update) } catch (e: Exception) { if (e is CancellationException) throw e; messages.value = e.message }
    }
    private fun received(update: ListeningRoom) {
        applying?.cancel()
        if (!update.live || social.publicKey !in update.members || social.rooms.activeKey != update.key) { player.setPlaying(false); player.setRoomPlayback(null); playedTrack = null; messages.value = if (update.ended) "The host ended this Room." else "You are no longer in this Room."; return }
        messages.value = null
        val track = update.queue.firstOrNull { it.id == update.currentID } ?: run { player.setPlaying(false); return }
        applying = scope.launch {
            try {
                if (playedTrack != track.id || player.source != "Room: ${update.name}") {
                    val song = match(track); ensureActive()
                    if (social.rooms.activeKey != update.key || social.rooms.rooms[update.key]?.revision != update.revision) return@launch
                    player.setRoomPlayback(update.speed)
                    player.playSongs(listOf(song), shuffle = false, source = "Room: ${update.name}"); playedTrack = track.id
                }
                player.setRoomPlayback(update.speed)
                val position = RoomState.expectedPosition(update)
                if (kotlin.math.abs(player.positionMs - position) > 2000) player.seekTo(position)
                player.setPlaying(update.playing)
            } catch (e: Exception) { if (e is CancellationException) throw e; messages.value = e.message; player.setPlaying(false) }
        }
    }
    private fun request(incoming: IncomingRoomRequest) {
        val current = room ?: return; if (!isHost || incoming.request.roomID != current.id || incoming.request.action == "join") return
        social.rooms.requests.removeAll { it.request.id == incoming.request.id || incoming.request.action == "leave" && it.sender == incoming.sender && it.request.roomID == incoming.request.roomID }; social.roomChanged()
        scope.launch {
            try {
                when (incoming.request.action) {
                    "leave" -> room?.let { social.sendRoom(it.copy(members = it.members - incoming.sender, revision = it.revision + 1, observedAt = SocialRules.now), listOf(incoming.sender)) }
                    "add" -> { append(incoming.request.tracks); tick() }
                    "remove" -> if (room?.allowControls == true) incoming.request.trackID?.let { remove(it) }
                    "next" -> if (room?.allowControls == true) control(next = true)
                    "control" -> if (room?.allowControls == true) control(playing = incoming.request.playing, position = incoming.request.positionMs)
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; messages.value = e.message }
        }
    }
}

package com.localfy.app.data.social

import androidx.media3.common.Player
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Wires [DeviceSync] to this phone: the friend relay, the player and a small JSON file. */
class AndroidDevices(private val app: LocalfyApp) : DeviceLink, DeviceHost {
    private val social get() = app.social
    private val file = File(app.filesDir, "linked_devices.json")
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val tracks = LinkedHashMap<Long, SharedTrack>()
    private var watching: Job? = null
    val sync = DeviceSync(this, this, app.appScope, android.os.Build.MODEL.orEmpty().ifBlank { "Android" })

    init {
        social.onDevicePacket = sync::receive
        // Library sync packets wait in its queue until it has loaded; it accepts them from linked devices only.
        social.onSyncPacket = { author, packet, encrypted -> if (!sync.loaded || sync.state.devices.isNotEmpty()) app.librarySync.receive(author, packet, encrypted) }
        app.appScope.launch {
            sync.start()
            var linked: Set<String>? = null
            sync.revision.collect {
                if (sync.state.devices.isNotEmpty()) watchPlayer()
                val ids = sync.state.devices.keys.toSet()
                if (ids != linked && (ids.isNotEmpty() || app.librarySyncIfStarted != null)) app.librarySync.devicesChanged(ids)
                linked = ids
            }
        }
    }

    override val me get() = social.publicKey
    override suspend fun send(packet: SocialPacket, logical: String, recipient: String?, expiresIn: Long, extraTags: List<List<String>>) = social.sendDevice(packet, logical, recipient, expiresIn, extraTags)
    override suspend fun lookup(tag: String) = social.lookup(tag)
    override fun needRelay(needed: Boolean) { social.devicesNeedRelay = needed }

    override val platform = "android"
    override suspend fun load(): String? = withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.readText() }
    override fun save(json: String) { app.appScope.launch(writer) { runCatching { val tmp = File(file.path + ".tmp"); tmp.writeText(json); tmp.renameTo(file) } } }

    /** Follows the player only once a device is linked; state changes are already off the hot path. */
    private fun watchPlayer() {
        if (watching != null) return
        val player = app.player
        watching = app.appScope.launch {
            launch { player.seeks.drop(1).collect { sync.localChanged() } }
            var wanted: Boolean? = null
            player.state.map { Change(it.currentId, it.currentIndex, it.queue, it.playWhenReady, it.source, it.speed) }.distinctUntilChanged().collect { change ->
                val before = wanted; wanted = change.playing
                if (before == false && change.playing) sync.localStarted()
                if (before != null) sync.localChanged()
            }
        }
    }
    private data class Change(val current: Long?, val index: Int, val queue: List<Long>, val playing: Boolean, val source: String?, val speed: Float)

    override fun snapshot(): LocalPlayback? {
        val state = app.player.state.value
        val current = state.currentId?.let(app::resolve) ?: return null
        val (queue, index) = DeviceSync.window(state.queue, state.currentIndex) { id -> app.resolve(id)?.let(::track) }
        return LocalPlayback(queue, index, state.playWhenReady && state.playbackState != Player.STATE_ENDED && state.playbackState != Player.STATE_IDLE,
            app.player.livePosition(), state.speed, state.source, current.isPodcast || current.isAudiobook)
    }

    /** Same track id for the same song, so the queue only looks changed when it is. */
    private fun track(song: Song): SharedTrack {
        tracks[song.id]?.let { return it }
        if (tracks.size > 600) tracks.keys.take(300).toList().forEach(tracks::remove)
        return DeviceSync.tidy(SharedTrack.from(song, app.musicStreams.track(song))).also { tracks[song.id] = it }
    }

    override fun isPlaying() = app.player.state.value.playWhenReady
    override fun obey(action: String, positionMs: Long) {
        val player = app.player
        when (action) {
            "play" -> player.setPlaying(true)
            "pause" -> player.setPlaying(false)
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seekTo(positionMs)
        }
    }
    private fun room() = social.rooms.activeKey?.let { social.rooms.rooms[it] }
    override fun roomGuest() = room()?.let { it.host != social.publicKey } == true
    override fun inRoom() = social.rooms.activeKey != null

    override suspend fun listen(state: DevicePlayback, positionMs: Long) {
        val current = state.current ?: return
        val song = PlaylistMatches.resolve(current, app)
        val player = app.player
        player.playSongs(listOf(song), shuffle = false, source = state.source ?: "From ${state.name}", startPositionMs = positionMs)
        val version = player.queueVersion
        app.appScope.launch {
            for (track in state.queue.drop(state.currentIndex + 1)) {
                val next = try { PlaylistMatches.resolve(track, app) } catch (e: Exception) { if (e is CancellationException) throw e; continue }
                if (player.queueVersion != version) return@launch
                player.appendFromSource(listOf(next))
            }
        }
    }
}

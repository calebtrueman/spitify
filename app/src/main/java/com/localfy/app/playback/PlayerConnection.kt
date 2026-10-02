package com.localfy.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.Song
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.data.podcast.resumeKey
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PlayerUiState(
    val connected: Boolean = false,
    /** Song ids in play order (shuffle is applied to the real queue, so this is always what plays next). */
    val queue: List<Long> = emptyList(),
    val currentIndex: Int = -1,
    val manualQueueIndices: Set<Int> = emptySet(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val crossfadeMs: Int = 0,
    val crossfadeKeepAlbums: Boolean = true,
    /** What the queue was started from, e.g. "Liked Songs" - shown as "Playing from". */
    val source: String? = null,
) {
    val currentId: Long? get() = queue.getOrNull(currentIndex)
    val hasMedia: Boolean get() = currentIndex >= 0
    val upNext: List<IndexedValue<Long>>
        get() = queue.withIndex().drop((currentIndex + 1).coerceAtLeast(0))
}

sealed interface SleepTimer {
    data class At(val endsAtMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

/**
 * The UI's single handle on playback. Wraps a [MediaController] bound to [PlaybackService]
 * and exposes plain StateFlows so Compose never touches the controller directly.
 */
class PlayerConnection(
    private val context: Context,
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
    private val resolve: (Long) -> Song?,
    private val podcasts: PodcastRepository,
    /** Every finished/skipped listen goes to the taste engine. */
    private val recordListen: (com.localfy.app.data.db.PlayEventEntity) -> Unit = {},
) {
    /** One-off messages for the UI (e.g. "Can't play this file"). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val prefs = context.getSharedPreferences(PlayerPrefs.FILE, Context.MODE_PRIVATE)
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(
        PlayerUiState(
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            skipSilence = prefs.getBoolean(KEY_SKIP_SILENCE, false),
            source = prefs.getString(KEY_SOURCE, null),
            crossfadeMs = prefs.getInt(PlayerPrefs.CROSSFADE_MS, 0),
            crossfadeKeepAlbums = prefs.getBoolean(PlayerPrefs.CROSSFADE_KEEP_ALBUMS, true),
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _position.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()

    /** Original (un-shuffled) order, kept so turning shuffle off restores it like Spotify does. */
    private var unshuffledOrder: List<Long>? = prefs.getString(KEY_UNSHUFFLED, null)
        ?.split(',')?.mapNotNull { it.toLongOrNull() }

    private var countedCurrent = false
    private var ticker: Job? = null
    private var sleepJob: Job? = null

    private var connecting = false

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = MediaController.Builder(context, token)
                .setListener(object : MediaController.Listener {
                    // Service stopped (e.g. app swiped away): drop the stale controller so we reconnect next time.
                    override fun onDisconnected(controller: MediaController) {
                        this@PlayerConnection.controller = null
                        ticker?.cancel()
                        _state.value = _state.value.copy(isPlaying = false, connected = false)
                    }
                })
                .buildAsync().let { f -> runCatching { f.await() } }
                .getOrElse {
                    // The service refused or died mid-connect; never crash the app over it; onStart retries.
                    connecting = false
                    return@launch
                }
            controller = c
            connecting = false
            c.addListener(listener)
            if (c.mediaItemCount == 0) restoreQueue(c)
            applySkipSilence()
            publish()
            startTicker()
        }
    }

    /** Drops our binding to the service so it can actually stop (see PlaybackService.onTaskRemoved). */
    fun disconnect() {
        val c = controller ?: return
        controller = null
        ticker?.cancel()
        c.removeListener(listener)
        c.release()
        _state.value = _state.value.copy(isPlaying = false, connected = false)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) {
                saveQueue()
                lastSongId?.let { saveResume(it, player.currentPosition, player.duration) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = controller?.currentMediaItem?.mediaMetadata?.title ?: "this track"
            val ext = resolve(controller?.currentMediaItem?.mediaId?.toLongOrNull() ?: 0)?.fileName?.substringAfterLast('.', "")?.uppercase()
            _messages.tryEmit(
                if (error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                    "Can't play “$title”" + (ext?.let { " ($it isn't supported)" } ?: "") + " — skipping"
                else if (error.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
                    "No connection. Downloads can play offline."
                else "Couldn't play “$title”",
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) { markCurrentFinished(); endListen(auto = true) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastSongId?.let { prev -> saveResume(prev, lastPosition, lastDuration) }
            endListen(auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            listenStartedAt = System.currentTimeMillis()
            applySpeedFor(mediaItem)
            val previous = lastSongId
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && previous != null && !countedCurrent && lastPosition < 30_000) {
                repo.recordSkip(previous)
            }
            countedCurrent = false
            lastSongId = mediaItem?.mediaId?.toLongOrNull()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _sleepTimer.value == SleepTimer.EndOfTrack) {
                controller?.pause()
                controller?.seekTo(0)
                _sleepTimer.value = null
            }
            saveQueue()
        }
    }

    private var lastSongId: Long? = null
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var listenedMs = 0L
    private var listenStartedAt = System.currentTimeMillis()

    /** Closes the listen for the song that was playing (music only) and reports it. */
    private fun endListen(auto: Boolean) {
        val id = lastSongId
        val heard = listenedMs
        listenedMs = 0
        if (id == null || resolve(id)?.let { it.isPodcast || it.isAudiobook } != false || heard < 3_000) return
        val dur = lastDuration
        val completed = auto || (dur > 0 && heard >= dur * 0.85)
        val skipped = !completed && heard < minOf(30_000L, if (dur > 0) dur / 2 else 30_000L)
        recordListen(com.localfy.app.data.db.PlayEventEntity(songId = id, startedAt = listenStartedAt, listenedMs = heard, durationMs = dur, completed = completed, skipped = skipped, source = _state.value.source))
    }

    /** Episodes and anything over 20 minutes remember where you stopped. */
    private fun tracksResume(song: Song?, durationMs: Long) = song != null && (song.isPodcast || durationMs > 20 * 60_000)

    private fun saveResume(id: Long, pos: Long, dur: Long) {
        val song = resolve(id) ?: return
        if (tracksResume(song, dur) && dur > 0) podcasts.saveProgress(song.resumeKey, pos, dur)
    }

    private fun markCurrentFinished() {
        val song = lastSongId?.let(resolve) ?: return
        if (tracksResume(song, lastDuration)) podcasts.setPlayed(song.resumeKey, true, lastDuration)
    }

    private fun applySpeedFor(item: MediaItem?) {
        val podcast = item?.mediaMetadata?.mediaType.isSpoken()
        val speed = prefs.getFloat(if (podcast) KEY_SPEED_PODCAST else KEY_SPEED_MUSIC, 1f)
        if (controller?.playbackParameters?.speed != speed) controller?.setPlaybackSpeed(speed)
    }

    private fun publish() {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }
        _state.value = _state.value.copy(
            connected = true,
            playbackState = c.playbackState,
            queue = ids,
            manualQueueIndices = (0 until c.mediaItemCount).filter { c.getMediaItemAt(it).isManualQueueItem() }.toSet(),
            currentIndex = if (ids.isEmpty()) -1 else c.currentMediaItemIndex,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            repeatMode = c.repeatMode,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            speed = c.playbackParameters.speed,
        )
        _position.value = c.currentPosition
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceSave = 0L
            while (isActive) {
                val c = controller
                if (c != null) {
                    val pos = c.currentPosition
                    _position.value = pos
                    lastPosition = pos
                    val dur = c.duration
                    if (dur > 0) lastDuration = dur
                    // A play counts once you've heard 30s or half the song, whichever comes first.
                    if (!countedCurrent && c.isPlaying && dur > 0 && pos >= minOf(30_000L, dur / 2)) {
                        countedCurrent = true
                        c.currentMediaItem?.mediaId?.toLongOrNull()?.takeIf { it >= 0 }?.let(repo::recordPlay)
                    }
                    if (c.isPlaying) {
                        listenedMs += (TICK_MS * c.playbackParameters.speed).toLong()
                        sinceSave += TICK_MS
                        if (sinceSave >= 5_000) {
                            sinceSave = 0
                            saveQueue()
                            c.currentMediaItem?.mediaId?.toLongOrNull()?.let { saveResume(it, pos, dur) }
                        }
                    }
                }
                delay(if (c?.isPlaying == true) TICK_MS else 500L)
            }
        }
    }

    // ---- Transport ----

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean? = null, source: String? = null, startPositionMs: Long = 0L) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val wanted = songs.getOrNull(startIndex)
        val usable = songs.filter { it.playable }
        if (usable.isEmpty()) { _messages.tryEmit("This format isn't supported (${songs.first().fileName.substringAfterLast('.').uppercase()})"); return }
        if (usable.size != songs.size) return playSongs(usable, usable.indexOf(wanted).coerceAtLeast(0), shuffle, source, startPositionMs)
        val wantShuffle = shuffle ?: _state.value.shuffle
        val start = startIndex.coerceIn(songs.indices)
        val ordered: List<Song>
        val first: Int
        if (wantShuffle) {
            val picked = if (shuffle == true && startIndex == 0) songs.random() else songs[start]
            ordered = listOf(picked) + (songs - picked).shuffled()
            first = 0
            unshuffledOrder = songs.map { it.id }
        } else {
            ordered = songs
            first = start
            unshuffledOrder = null
        }
        setShuffleFlag(wantShuffle)
        _state.value = _state.value.copy(source = source)
        prefs.edit { putString(KEY_SOURCE, source) }
        c.setMediaItems(ordered.map { it.toMediaItem() }, first, startPositionMs)
        c.prepare()
        c.play()
    }

    /** Plays an audiobook from [startIndex] onward (chapters continue), resuming that chapter. */
    fun playBook(chapters: List<Song>, startIndex: Int, source: String?) {
        scope.launch {
            val pos = podcasts.resumePosition(chapters[startIndex].resumeKey)
            playSongs(chapters, startIndex, shuffle = false, source = source, startPositionMs = pos)
            controller?.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    /** Plays an episode (or long local file) from where you left off. */
    fun playEpisode(song: Song, source: String? = song.album) {
        scope.launch { playSongs(listOf(song), 0, shuffle = false, source = source, startPositionMs = podcasts.resumePosition(song.resumeKey)) }
    }

    fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceIn(0, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        c.seekTo(target); _position.value = target
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(songs)
        val usable = songs.filter { it.playable }
        c.addMediaItems(c.currentMediaItemIndex + 1, usable.map { it.toMediaItem().asManualQueueItem() })
        saveQueue()
    }

    fun addToQueue(songs: List<Song>): Boolean {
        val c = controller ?: run { _messages.tryEmit("Player is connecting. Please try again."); return false }
        if (songs.isEmpty()) return false
        val usable = songs.filter { it.playable }
        if (usable.isEmpty()) { _messages.tryEmit("These files aren't supported"); return false }
        if (c.mediaItemCount == 0) playSongs(usable)
        else {
            val insertion = ((c.currentMediaItemIndex + 1 until c.mediaItemCount)
                .lastOrNull { c.getMediaItemAt(it).isManualQueueItem() } ?: c.currentMediaItemIndex) + 1
            c.addMediaItems(insertion, usable.map { it.toMediaItem().asManualQueueItem() })
            saveQueue()
        }
        val count = usable.size
        val skipped = songs.size - count
        _messages.tryEmit("Added $count ${if (count == 1) "song" else "songs"} to queue" +
            if (skipped > 0) " • $skipped unsupported skipped" else "")
        return true
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
            c.play()
        }
    }

    fun next() { controller?.seekToNextMediaItem() }

    /** Spotify behaviour: restart the song if more than 3s in, otherwise go back one. */
    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms); _position.value = ms }

    fun skipTo(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) { c.seekTo(index, 0); c.play() }
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        val id = c.getMediaItemAt(index).mediaId.toLongOrNull()
        val manual = c.getMediaItemAt(index).isManualQueueItem()
        c.removeMediaItem(index)
        if (id != null && !manual) unshuffledOrder = unshuffledOrder?.let { it - id }
        saveQueue()
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
        saveQueue()
    }

    fun clearUpNext() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        unshuffledOrder = null
        saveQueue()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * Shuffle rewrites the real queue (current song stays, everything after it is shuffled),
     * so "Next up" is always truthful. Turning it off restores the original order.
     */
    fun toggleShuffle() {
        val c = controller ?: return
        val enable = !_state.value.shuffle
        setShuffleFlag(enable)
        if (c.mediaItemCount < 2) return
        val cur = c.currentMediaItemIndex
        val currentItem = c.getMediaItemAt(cur)
        val all = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val manual = all.drop(cur + 1).filter { it.isManualQueueItem() }
        val background = all.filterIndexed { i, item -> i != cur && !item.isManualQueueItem() }
        val before: List<MediaItem>
        val after: List<MediaItem>
        if (enable) {
            unshuffledOrder = all.filter { !it.isManualQueueItem() }.mapNotNull { it.mediaId.toLongOrNull() }
            before = emptyList()
            after = manual + background.shuffled()
        } else {
            // Remove one occurrence at a time: an album or playlist can repeat a song.
            val remaining = (background + if (currentItem.isManualQueueItem()) emptyList() else listOf(currentItem)).toMutableList()
            val restored = unshuffledOrder.orEmpty().mapNotNull { id ->
                val i = remaining.indexOfFirst { it.mediaId == id.toString() }
                if (i < 0) null else remaining.removeAt(i)
            } + remaining
            val current = if (currentItem.isManualQueueItem()) -1 else restored.indexOf(currentItem)
            before = if (current < 0) emptyList() else restored.take(current)
            after = manual + if (current < 0) restored else restored.drop(current + 1)
            unshuffledOrder = null
        }
        // Keep the playing item in place while replacing the items around it.
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
        if (after.isNotEmpty()) c.addMediaItems(1, after)
        if (before.isNotEmpty()) c.addMediaItems(0, before)
        saveQueue()
    }

    private fun setShuffleFlag(on: Boolean) {
        _state.value = _state.value.copy(shuffle = on)
        prefs.edit { putBoolean(KEY_SHUFFLE, on) }
    }

    /** Music and podcasts each remember their own speed. */
    fun setSpeed(speed: Float) {
        val podcast = controller?.currentMediaItem?.mediaMetadata?.mediaType.isSpoken()
        prefs.edit { putFloat(if (podcast) KEY_SPEED_PODCAST else KEY_SPEED_MUSIC, speed) }
        controller?.setPlaybackSpeed(speed)
    }

    fun setSkipSilence(enabled: Boolean) {
        _state.value = _state.value.copy(skipSilence = enabled)
        prefs.edit { putBoolean(KEY_SKIP_SILENCE, enabled) }
        applySkipSilence()
    }

    /** Read live by the service's [Crossfader]; 0 turns crossfade off. */
    fun setCrossfade(ms: Int) {
        _state.value = _state.value.copy(crossfadeMs = ms)
        prefs.edit { putInt(PlayerPrefs.CROSSFADE_MS, ms) }
    }

    fun setCrossfadeKeepAlbums(keep: Boolean) {
        _state.value = _state.value.copy(crossfadeKeepAlbums = keep)
        prefs.edit { putBoolean(PlayerPrefs.CROSSFADE_KEEP_ALBUMS, keep) }
    }

    private fun applySkipSilence() {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SKIP_SILENCE, Bundle.EMPTY),
            Bundle().apply { putBoolean(PlaybackService.EXTRA_ENABLED, _state.value.skipSilence) },
        )
    }

    // ---- Sleep timer (fades out over the last 10 seconds) ----

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        controller?.volume = 1f
        if (minutes == null) { _sleepTimer.value = null; return }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimer.value = SleepTimer.At(endsAt)
        sleepJob = scope.launch {
            while (isActive) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0) {
                    controller?.pause()
                    controller?.volume = 1f
                    _sleepTimer.value = null
                    break
                }
                if (left < FADE_MS) controller?.volume = (left / FADE_MS.toFloat()).coerceIn(0f, 1f)
                delay(200)
            }
        }
    }

    fun sleepAtEndOfTrack() {
        sleepJob?.cancel()
        controller?.volume = 1f
        _sleepTimer.value = SleepTimer.EndOfTrack
    }

    // ---- Resume where you left off ----

    /** Persist queue + resume point right away (e.g. before the service is torn down). */
    fun saveNow() {
        saveQueue()
        lastSongId?.let { saveResume(it, controller?.currentPosition ?: lastPosition, lastDuration) }
    }

    private fun saveQueue() {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).joinToString(",") { c.getMediaItemAt(it).mediaId }
        prefs.edit {
            putString(KEY_QUEUE, ids)
            putString(KEY_MANUAL, (0 until c.mediaItemCount).filter { c.getMediaItemAt(it).isManualQueueItem() }.joinToString(","))
            putInt(KEY_INDEX, c.currentMediaItemIndex)
            putLong(KEY_POSITION, c.currentPosition)
            putInt(KEY_REPEAT, c.repeatMode)
            putString(KEY_UNSHUFFLED, unshuffledOrder?.joinToString(","))
        }
    }

    private suspend fun restoreQueue(c: MediaController) {
        val ids = prefs.getString(KEY_QUEUE, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.isEmpty()) return
        if (ids.any { it >= 0 }) withTimeoutOrNull(5_000) { repo.library.first { !it.isEmpty } }
        if (ids.any { it < 0 && resolve(it) == null }) withTimeoutOrNull(3_000) { podcasts.episodeSongs.first { it.isNotEmpty() } }
        val manual = prefs.getString(KEY_MANUAL, null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty().toSet()
        val restored = ids.mapIndexedNotNull { i, id -> resolve(id)?.let { i to it } }
        val songs = restored.map { it.second }
        if (songs.isEmpty()) return
        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(songs.indices)
        c.setMediaItems(restored.map { (i, song) -> song.toMediaItem().let { if (i in manual) it.asManualQueueItem() else it } }, index, prefs.getLong(KEY_POSITION, 0))
        c.repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
        c.prepare()
    }

    companion object {
        private const val TICK_MS = 250L
        private const val FADE_MS = 10_000L
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_SKIP_SILENCE = "skip_silence"
        private const val KEY_QUEUE = "queue"
        private const val KEY_MANUAL = "manual_queue_indices"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_UNSHUFFLED = "unshuffled"
        private const val KEY_SOURCE = "source"
        private const val KEY_SPEED_MUSIC = "speed_music"
        private const val KEY_SPEED_PODCAST = "speed_podcast"
    }
}

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist)
            .setArtworkUri(ArtProvider.uriFor(ArtContext.app, this))
            .setTrackNumber(track)
            .setDiscNumber(disc)
            .setGenre(genre)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(if (isAudiobook) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER else if (isPodcast) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

/** Podcast episodes and audiobook chapters share spoken-word behaviour. */
fun Int?.isSpoken(): Boolean = this == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE || this == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER

/** Application context for building artwork URIs outside of a Context-bearing scope. */
object ArtContext { lateinit var app: android.content.Context }

private const val MANUAL_QUEUE_ITEM = "spitify.manual_queue"
private fun MediaItem.isManualQueueItem() = mediaMetadata.extras?.getBoolean(MANUAL_QUEUE_ITEM) == true
internal fun MediaItem.asManualQueueItem(): MediaItem = buildUpon().setMediaMetadata(
    mediaMetadata.buildUpon().setExtras(Bundle(mediaMetadata.extras ?: Bundle()).apply { putBoolean(MANUAL_QUEUE_ITEM, true) }).build(),
).build()

package com.localfy.app.playback

import com.localfy.app.data.Song
import com.localfy.app.data.db.PlayEventEntity
import com.localfy.app.data.podcast.resumeKey
import com.localfy.app.desktop.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The UI's single handle on playback (desktop port of Android's PlayerConnection). It owns the
 * queue (shuffle reorders the real queue, repeat, the autoplay radio continuation, manual
 * "Add to queue" items), drives an [AudioEngine], logs listens, keeps resume points and the sleep
 * timer, and exposes plain StateFlows so Compose never touches the engine.
 *
 * Thread-safe: every mutation runs under one lock; engine callbacks arrive on the engine's event
 * thread. Nothing here blocks: the engine only queues commands and prefs are written off-thread.
 */
class PlayerConnection(
    private val resolve: (Long) -> Song?,
    private val recordPlay: (Long) -> Unit,
    private val recordSkip: (Long) -> Unit,
    /** Every finished/skipped listen goes to the taste engine. */
    private val recordListen: (PlayEventEntity) -> Unit,
    /** Taste-engine radio for a seed song (Android: app.taste.songRadio). */
    private val radio: (Song) -> List<Song>,
    private val librarySongs: () -> List<Song>,
    private val resumePosition: suspend (key: String) -> Long,
    private val saveProgress: (key: String, posMs: Long, durMs: Long) -> Unit,
    private val setPlayed: (key: String, played: Boolean, durMs: Long) -> Unit,
    /** An https URL for a streamed ("spitify://music/<id>") song, or null when offline. */
    private val streamUrl: suspend (Song) -> String?,
    /** The engine couldn't open this stream URL (Android: the data source tries the next source itself). */
    private val streamFailed: (Song, String) -> Unit = { _, _ -> },
    private val scope: CoroutineScope,
    private val hiddenSongs: () -> Set<Long> = { emptySet() },
    private val hiddenArtists: () -> Set<String> = { emptySet() },
    /** Online songs for a station when a lone streamed song has too few library matches (registered, playable). */
    private val onlineStation: suspend (Song) -> List<Song> = { emptyList() },
    /** Suspends until the library has loaded (the saved queue is restored after it, up to 5 s). */
    private val awaitLibrary: suspend () -> Unit = {},
    private val engine: AudioEngine = FfmpegAudioEngine(),
    private val prefs: Prefs = Prefs(PlayerPrefs.FILE),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** One-off messages for the UI (e.g. "Can't play this file"). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _state = MutableStateFlow(
        PlayerUiState(
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            skipSilence = prefs.getBoolean(KEY_SKIP_SILENCE, false),
            source = prefs.getString(KEY_SOURCE, null),
            autoplay = prefs.getBoolean(PlayerPrefs.AUTOPLAY, true),
            normalizeAudio = prefs.getBoolean(PlayerPrefs.NORMALIZE_AUDIO, true),
            crossfadeMs = prefs.getInt(PlayerPrefs.CROSSFADE_MS, 0),
            crossfadeKeepAlbums = prefs.getBoolean(PlayerPrefs.CROSSFADE_KEEP_ALBUMS, true),
            volume = prefs.getFloat(PlayerPrefs.VOLUME, 1f),
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _position.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()

    private val lock = Any()

    // ---- the queue (guarded by [lock])
    private val entries = ArrayList<QueueEntry>()
    private var index = -1
    private var repeatMode = prefs.getInt(KEY_REPEAT, RepeatMode.OFF)
    private var playWhenReady = false
    private var engineState = PlaybackState.IDLE
    private var errored = false
    private var consecutiveErrors = 0
    private var nextUid = 1L
    private var sentNext: Pair<Long?, Int>? = null
    private var currentSpeed = 1f
    private var connected = false
    private var queueDirty = true

    /** Original (un-shuffled) order, kept so turning shuffle off restores it like Spotify does. */
    private var unshuffledOrder: List<Long>? = prefs.getString(KEY_UNSHUFFLED, null)
        ?.split(',')?.mapNotNull { it.toLongOrNull() }

    private var countedCurrent = false
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var continuationJob: Job? = null
    private var sourceSongs: List<Song> = emptyList()
    private var continuationFilter = ""
    /** The queue state a refill last came back empty for; don't rebuild a radio for it again every tick. */
    private var emptyRefillFor: String? = null
    private var clearedUpNext = false
    private val failedSongs = mutableSetOf<Long>()

    private var lastSongId: Long? = null
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var listenedMs = 0L
    private var listenStartedAt = System.currentTimeMillis()

    private var roomSpeed: Float? = null
    private var roomRepeat: Int? = null

    var queueVersion: Long = 0; private set

    init {
        engine.setSkipSilence(_state.value.skipSilence)
        engine.setNormalize(_state.value.normalizeAudio)
        engine.setVolume(_state.value.volume)
        EqStore.init()
        engine.setEq(EqStore.state.value)
        scope.launch { EqStore.state.collect { engine.setEq(it) } }
        engine.listener = object : AudioEngineListener {
            override fun onStateChanged(playbackState: Int, playWhenReady: Boolean) = onEngineState(playbackState)
            override fun onAutoTransition(uid: Long) = onEngineTransition(uid)
            override fun onDuration(uid: Long, durationMs: Long) = synchronized(lock) { publish(queueChanged = false) }
            override fun onError(uid: Long, error: EngineError) = onEngineError(uid, error)
        }
    }

    private var connecting = false

    /** Restores the last queue (paused where you left off). Safe to call more than once. */
    fun connect() {
        synchronized(lock) {
            if (connected || connecting) return
            connecting = true
        }
        scope.launch(computeDispatcher) {
            try { restoreQueue() } catch (c: CancellationException) { throw c } catch (_: Exception) {}
            synchronized(lock) { connected = true; connecting = false; publish() }
        }
    }

    /** Saves everything and pauses (the window is closing). */
    fun disconnect() {
        saveNow()
        synchronized(lock) { if (playWhenReady) { playWhenReady = false; engine.pause() } ; publish(queueChanged = false) }
    }

    /** Releases the audio engine for good (app exit). */
    fun release() {
        disconnect()
        ticker?.cancel(); sleepJob?.cancel(); continuationJob?.cancel()
        engine.release()
    }

    // ---- engine events

    private fun onEngineState(playbackState: Int) = synchronized(lock) {
        val before = engineState
        engineState = playbackState
        if (playbackState == PlaybackState.READY) { errored = false; consecutiveErrors = 0 }
        if (playbackState == PlaybackState.ENDED && before != PlaybackState.ENDED) onEnded()
        publish(queueChanged = false)
    }

    private fun onEnded() {
        lastPosition = lastDuration.takeIf { it > 0 } ?: engine.positionMs
        markCurrentFinished(); endListen(auto = true)
        if (_sleepTimer.value == SleepTimer.EndOfTrack) {
            _sleepTimer.value = null
            val n = QueueRules.autoNextIndex(entries.size, index, repeatMode)
            if (n != null && n != index) moveTo(n, 0, play = false, reason = Reason.AUTO)
            else { playWhenReady = false; engine.pause() }
            syncNext()
        } else refillQueue()
    }

    private fun onEngineTransition(uid: Long) = synchronized(lock) {
        sentNext = null
        val i = entries.indexOfFirst { it.uid == uid }
        if (i < 0) {
            // The queue changed under the engine: play what the queue says comes next.
            QueueRules.autoNextIndex(entries.size, index, repeatMode)?.let { moveTo(it, 0, playWhenReady, Reason.AUTO) } ?: engine.stop()
            return@synchronized
        }
        lastPosition = lastDuration
        val reason = if (i == index) Reason.REPEAT else Reason.AUTO
        index = i
        onTransition(entries[i], reason)
        syncNext()
        publish(queueChanged = true)
        refillQueue()
    }

    private fun onEngineError(uid: Long, error: EngineError) = synchronized(lock) {
        val song = entries.firstOrNull { it.uid == uid }?.song ?: entries.getOrNull(index)?.song
        markUnplayable(song?.id)
        errored = true
        engineState = PlaybackState.IDLE
        consecutiveErrors++
        // Unsupported/corrupt file or dropped stream: move on instead of stalling the queue.
        val next = QueueRules.nextIndex(entries.size, index, repeatMode)
        if (next != null && next != index && consecutiveErrors < entries.size) moveTo(next, 0, play = true, reason = Reason.SEEK)
        refillQueue()
        val title = song?.title ?: "this track"
        val ext = song?.fileName?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotEmpty() }
        _messages.tryEmit(
            when (error.kind) {
                EngineError.Kind.UNSUPPORTED -> "Can't play “$title”" + (ext?.let { " ($it isn't supported)" } ?: "") + " — skipping"
                EngineError.Kind.NETWORK -> "No connection. Downloads can play offline."
                EngineError.Kind.OTHER -> "Couldn't play “$title”"
            },
        )
        publish()
    }

    // ---- transitions and listen logging

    private enum class Reason { AUTO, REPEAT, SEEK, PLAYLIST_CHANGED }

    /** Switches the engine to entry [i]; the bookkeeping matches Android's onMediaItemTransition. */
    private fun moveTo(i: Int, startMs: Long, play: Boolean, reason: Reason) {
        snapshotPosition()
        index = i
        val entry = entries[i]
        onTransition(entry, reason)
        playWhenReady = play
        errored = false
        engine.load(trackFor(entry), startMs, play)
        sentNext = null
        _position.value = startMs
        syncNext()
        publish()
    }

    private fun onTransition(entry: QueueEntry?, reason: Reason) {
        lastSongId?.let { prev -> saveResume(prev, lastPosition, lastDuration) }
        endListen(auto = reason == Reason.AUTO)
        listenStartedAt = System.currentTimeMillis()
        applySpeedFor(entry?.song)
        val previous = lastSongId
        if (reason == Reason.SEEK && previous != null && !countedCurrent && lastPosition < 30_000) recordSkip(previous)
        countedCurrent = false
        lastSongId = entry?.song?.id
        lastPosition = 0
        lastDuration = entry?.song?.durationMs ?: 0
        if (reason == Reason.AUTO && _sleepTimer.value == SleepTimer.EndOfTrack) {
            playWhenReady = false
            engine.pause()
            engine.seekTo(0)
            _sleepTimer.value = null
        }
        queueDirty = true
        saveQueue()
    }

    private fun snapshotPosition() {
        if (lastSongId == null || engine.currentUid == null) return
        lastPosition = engine.positionMs
        engine.durationMs.takeIf { it > 0 }?.let { lastDuration = it }
    }

    /** Closes the listen for the song that was playing (music only) and reports it. */
    private fun endListen(auto: Boolean) {
        val id = lastSongId
        val heard = listenedMs
        listenedMs = 0
        if (id == null || resolveSong(id)?.isSpoken != false || heard < 3_000) return
        val dur = lastDuration
        val completed = auto || (dur > 0 && heard >= dur * 0.85)
        val skipped = !completed && heard < minOf(30_000L, if (dur > 0) dur / 2 else 30_000L)
        recordListen(PlayEventEntity(songId = id, startedAt = listenStartedAt, listenedMs = heard, durationMs = dur, completed = completed, skipped = skipped, source = _state.value.source))
    }

    private fun resolveSong(id: Long): Song? = resolve(id) ?: entries.firstOrNull { it.song.id == id }?.song

    /** Episodes and anything over 20 minutes remember where you stopped. */
    private fun tracksResume(song: Song?, durationMs: Long) = song != null && (song.isPodcast || durationMs > 20 * 60_000)

    private fun saveResume(id: Long, pos: Long, dur: Long) {
        val song = resolveSong(id) ?: return
        if (tracksResume(song, dur) && dur > 0) saveProgress(song.resumeKey, pos, dur)
    }

    private fun markCurrentFinished() {
        val song = lastSongId?.let(::resolveSong) ?: return
        if (tracksResume(song, lastDuration)) setPlayed(song.resumeKey, true, lastDuration)
    }

    private fun applySpeedFor(song: Song?) {
        val speed = roomSpeed ?: prefs.getFloat(if (song?.isSpoken == true) KEY_SPEED_PODCAST else KEY_SPEED_MUSIC, 1f)
        currentSpeed = speed
        engine.setSpeed(speed)
    }

    private fun trackFor(entry: QueueEntry): EngineTrack {
        val song = entry.song
        val remote = song.isStream || song.sourceScheme == "http" || song.sourceScheme == "https"
        return EngineTrack(entry.uid, song.id, song.title, spoken = song.isSpoken, isRemote = remote,
            url = { if (song.isStream) streamUrl(song) else song.sourceUri },
            failed = if (song.isStream) { url -> streamFailed(song, url) } else null)
    }

    /** Tells the engine what follows the current entry (gapless, or crossfaded). */
    private fun syncNext() {
        val cur = entries.getOrNull(index)
        val n = if (cur == null || _sleepTimer.value == SleepTimer.EndOfTrack) null else QueueRules.autoNextIndex(entries.size, index, repeatMode)
        val next = n?.let { entries[it] }
        val s = _state.value
        val cf = if (cur != null && next != null) QueueRules.crossfadeFor(cur.song, next.song, s.crossfadeMs, s.crossfadeKeepAlbums, repeatMode) else 0
        val key = next?.uid to cf
        if (key == sentNext) return
        sentNext = key
        engine.setNext(next?.let(::trackFor), cf)
    }

    private fun newEntry(song: Song, manual: Boolean = false, autoplay: Boolean = false) = QueueEntry(nextUid++, song, manual, autoplay)

    private fun publish(queueChanged: Boolean = true) {
        val old = _state.value
        val rebuild = queueChanged || queueDirty || old.queue.size != entries.size
        val ids = if (rebuild) entries.map { it.song.id } else old.queue
        val playing = playWhenReady && engineState == PlaybackState.READY
        val dur = engine.durationMs.takeIf { it > 0 } ?: entries.getOrNull(index)?.song?.durationMs ?: 0L
        _state.value = old.copy(
            connected = connected,
            playbackState = engineState,
            queue = ids,
            manualQueueIndices = if (rebuild) entries.indices.filter { entries[it].manual }.toSet() else old.manualQueueIndices,
            autoplayQueueIndices = if (rebuild) entries.indices.filter { entries[it].autoplay }.toSet() else old.autoplayQueueIndices,
            currentIndex = if (entries.isEmpty()) -1 else index,
            isPlaying = playing,
            isBuffering = engineState == PlaybackState.BUFFERING,
            repeatMode = repeatMode,
            durationMs = dur,
            speed = currentSpeed,
        )
        queueDirty = false
        if (index >= 0) _position.value = engine.positionMs
        if (dur > 0) lastDuration = dur
        if (old.isPlaying && !playing) {
            saveQueue()
            lastSongId?.let { saveResume(it, engine.positionMs, lastDuration) }
        }
        if (playing) startTicker() else { ticker?.cancel(); ticker = null }
    }

    /** Ticks every 250 ms while playing only: position, play counts, listen time, periodic saves. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        // Off the UI thread: the tick takes the queue lock and may start a radio refill.
        ticker = scope.launch(computeDispatcher) {
            var sinceSave = 0L
            while (isActive) {
                delay(TICK_MS)
                synchronized(lock) {
                    if (!(playWhenReady && engineState == PlaybackState.READY)) return@synchronized
                    refillQueue()
                    val pos = engine.positionMs
                    _position.value = pos
                    lastPosition = pos
                    val dur = engine.durationMs.takeIf { it > 0 } ?: lastDuration
                    if (dur > 0) lastDuration = dur
                    // A play counts once you've heard 30s or half the song, whichever comes first.
                    if (!countedCurrent && dur > 0 && pos >= minOf(30_000L, dur / 2)) {
                        countedCurrent = true
                        entries.getOrNull(index)?.song?.id?.takeIf { it >= 0 }?.let(recordPlay)
                    }
                    listenedMs += (TICK_MS * currentSpeed).toLong()
                    sinceSave += TICK_MS
                    if (sinceSave % 5_000 == 0L) savePosition()
                    if (sinceSave >= 15_000) {
                        sinceSave = 0
                        lastSongId?.let { saveResume(it, pos, dur) }
                    }
                }
            }
        }
    }

    // ---- Transport ----

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean? = null, source: String? = null, startPositionMs: Long = 0L) {
        if (songs.isEmpty()) return
        val wanted = songs.getOrNull(startIndex)
        val usable = songs.filter { it.playable }
        if (usable.isEmpty()) { _messages.tryEmit("This format isn't supported (${songs.first().fileName.substringAfterLast('.').uppercase()})"); return }
        if (usable.size != songs.size) return playSongs(usable, usable.indexOf(wanted).coerceAtLeast(0), shuffle, source, startPositionMs)
        synchronized(lock) {
            queueVersion += 1
            continuationJob?.cancel()
            sourceSongs = songs
            failedSongs.clear()
            clearedUpNext = false
            consecutiveErrors = 0
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
            entries.clear()
            ordered.mapTo(entries) { newEntry(it) }
            moveTo(first, startPositionMs, play = true, reason = Reason.PLAYLIST_CHANGED)
        }
    }

    /** Plays an audiobook from [startIndex] onward (chapters continue), resuming that chapter. */
    fun playBook(chapters: List<Song>, startIndex: Int, source: String?) {
        if (startIndex !in chapters.indices) return // a book whose feed had no playable chapters
        scope.launch {
            val pos = resumePosition(chapters[startIndex].resumeKey)
            playSongs(chapters, startIndex, shuffle = false, source = source, startPositionMs = pos)
            synchronized(lock) { repeatMode = RepeatMode.OFF; syncNext(); saveQueue(); publish(queueChanged = false) }
        }
    }

    /** Plays an episode (or long local file) from where you left off. */
    fun playEpisode(song: Song, source: String? = song.album) {
        scope.launch { playSongs(listOf(song), 0, shuffle = false, source = source, startPositionMs = resumePosition(song.resumeKey)) }
    }

    fun skipBy(deltaMs: Long) {
        val dur = engine.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekTo((engine.positionMs + deltaMs).coerceIn(0, dur))
    }

    fun playNext(songs: List<Song>) {
        val empty = synchronized(lock) { entries.isEmpty() }
        if (empty) return playSongs(songs)
        synchronized(lock) {
            val usable = songs.filter { it.playable }
            entries.addAll(index + 1, usable.map { newEntry(it, manual = true) })
            changedQueue()
        }
    }

    fun addToQueue(songs: List<Song>): Boolean {
        if (songs.isEmpty()) return false
        val usable = songs.filter { it.playable }
        if (usable.isEmpty()) { _messages.tryEmit("These files aren't supported"); return false }
        val empty = synchronized(lock) { entries.isEmpty() }
        if (empty) playSongs(usable)
        else synchronized(lock) {
            entries.addAll(QueueRules.addToQueueIndex(entries, index), usable.map { newEntry(it, manual = true) })
            changedQueue()
        }
        val count = usable.size
        val skipped = songs.size - count
        _messages.tryEmit("Added $count ${if (count == 1) "song" else "songs"} to queue" +
            if (skipped > 0) " • $skipped unsupported skipped" else "")
        return true
    }

    fun appendFromSource(songs: List<Song>): Unit = synchronized(lock) {
        if (entries.isEmpty()) return@synchronized
        sourceSongs = (sourceSongs + songs).distinctBy { it.id }
        entries.addAll(QueueRules.appendFromSourceIndex(entries, index), songs.filter { it.playable }.map { newEntry(it) })
        changedQueue()
    }

    fun setRoomPlayback(speed: Float?): Unit = synchronized(lock) {
        if (speed != null && roomSpeed == null) roomRepeat = repeatMode
        roomSpeed = speed
        if (speed != null) { repeatMode = RepeatMode.OFF; currentSpeed = speed; engine.setSpeed(speed) }
        else { roomRepeat?.let { repeatMode = it }; roomRepeat = null; currentSpeed = prefs.getFloat(KEY_SPEED_MUSIC, 1f); engine.setSpeed(currentSpeed) }
        syncNext()
        publish(queueChanged = false)
    }

    fun setPlaying(playing: Boolean): Unit = synchronized(lock) {
        if (playing) play() else pauseInternal()
    }

    fun togglePlay(): Unit = synchronized(lock) {
        if (playWhenReady && engineState == PlaybackState.READY) pauseInternal() else play()
    }

    private fun play() {
        if (index !in entries.indices) return
        when (engineState) {
            // Not prepared (restored queue failed, or after an error): load where we were.
            PlaybackState.IDLE -> { prepareCurrent(lastPosition.takeIf { engine.currentUid == null } ?: engine.positionMs, play = true); return }
            PlaybackState.ENDED -> { prepareCurrent(0, play = true); return }
        }
        playWhenReady = true
        engine.play()
        publish(queueChanged = false)
    }

    private fun prepareCurrent(startMs: Long, play: Boolean) {
        val entry = entries.getOrNull(index) ?: return
        playWhenReady = play
        errored = false
        engine.load(trackFor(entry), startMs, play)
        sentNext = null
        syncNext()
        publish(queueChanged = false)
    }

    private fun pauseInternal() {
        if (!playWhenReady) return
        playWhenReady = false
        engine.pause()
        publish(queueChanged = false)
    }

    fun next(): Unit = synchronized(lock) {
        QueueRules.nextIndex(entries.size, index, repeatMode)?.let { moveTo(it, 0, playWhenReady, Reason.SEEK) }
        Unit
    }

    /** Spotify behaviour: restart the song if more than 3s in, otherwise go back one. */
    fun previous(): Unit = synchronized(lock) {
        if (index !in entries.indices) return@synchronized
        val prev = QueueRules.previousIndex(entries.size, index, repeatMode)
        if (QueueRules.previousRestarts(engine.positionMs, prev != null)) seekTo(0) else moveTo(prev!!, 0, playWhenReady, Reason.SEEK)
    }

    fun seekTo(ms: Long): Unit = synchronized(lock) {
        if (index !in entries.indices) return@synchronized
        if (engineState == PlaybackState.IDLE || engineState == PlaybackState.ENDED || engine.currentUid == null) prepareCurrent(ms, playWhenReady)
        else engine.seekTo(ms)
        _position.value = ms
    }

    fun skipTo(index: Int): Unit = synchronized(lock) {
        if (index !in entries.indices) return@synchronized
        if (index == this.index && engine.currentUid == entries[index].uid) { seekTo(0); play() }
        else moveTo(index, 0, play = true, reason = Reason.SEEK)
    }

    fun removeAt(index: Int): Unit = synchronized(lock) {
        if (index !in entries.indices) return@synchronized
        val removed = entries.removeAt(index)
        if (!removed.manual) unshuffledOrder = unshuffledOrder?.let { it - removed.song.id }
        when {
            index < this.index -> this.index--
            index == this.index -> when {
                entries.isEmpty() -> {
                    snapshotPosition()
                    this.index = -1
                    onTransition(null, Reason.PLAYLIST_CHANGED)
                    playWhenReady = false
                    engine.stop()
                }
                index < entries.size -> moveTo(index, 0, playWhenReady, Reason.PLAYLIST_CHANGED)
                else -> moveTo(entries.lastIndex, 0, play = false, reason = Reason.PLAYLIST_CHANGED)
            }
        }
        changedQueue()
    }

    fun move(from: Int, to: Int): Unit = synchronized(lock) {
        if (from == to || from !in entries.indices || to !in entries.indices) return@synchronized
        entries.add(to, entries.removeAt(from))
        index = QueueRules.indexAfterMove(index, from, to)
        changedQueue()
    }

    fun clearUpNext(): Unit = synchronized(lock) {
        queueVersion += 1
        continuationJob?.cancel()
        clearedUpNext = true
        if (index + 1 < entries.size) entries.subList(index + 1, entries.size).clear()
        unshuffledOrder = null
        changedQueue()
    }

    fun cycleRepeat(): Unit = synchronized(lock) {
        continuationJob?.cancel()
        repeatMode = QueueRules.nextRepeat(repeatMode)
        removeAutoplayItems()
        refillQueue()
        changedQueue()
    }

    /**
     * Shuffle rewrites the real queue (current song stays, everything after it is shuffled),
     * so "Next up" is always truthful. Turning it off restores the original order.
     */
    fun toggleShuffle(): Unit = synchronized(lock) {
        continuationJob?.cancel()
        removeAutoplayItems()
        val enable = !_state.value.shuffle
        setShuffleFlag(enable)
        if (entries.size < 2) { publish(queueChanged = false); return@synchronized }
        val result = QueueRules.toggleShuffle(entries.toList(), index, enable, unshuffledOrder)
        entries.clear(); entries.addAll(result.entries)
        index = result.current
        unshuffledOrder = result.unshuffledOrder
        changedQueue()
    }

    private fun setShuffleFlag(on: Boolean) {
        _state.value = _state.value.copy(shuffle = on)
        prefs.edit { putBoolean(KEY_SHUFFLE, on) }
    }

    /** Music and podcasts each remember their own speed. */
    fun setSpeed(speed: Float): Unit = synchronized(lock) {
        val spoken = entries.getOrNull(index)?.song?.isSpoken == true
        prefs.edit { putFloat(if (spoken) KEY_SPEED_PODCAST else KEY_SPEED_MUSIC, speed) }
        currentSpeed = speed
        engine.setSpeed(speed)
        publish(queueChanged = false)
    }

    fun setSkipSilence(enabled: Boolean) {
        _state.value = _state.value.copy(skipSilence = enabled)
        prefs.edit { putBoolean(KEY_SKIP_SILENCE, enabled) }
        engine.setSkipSilence(enabled)
    }

    /** 0 turns crossfade off; up to 12 s. */
    fun setCrossfade(ms: Int): Unit = synchronized(lock) {
        _state.value = _state.value.copy(crossfadeMs = ms)
        prefs.edit { putInt(PlayerPrefs.CROSSFADE_MS, ms) }
        syncNext()
    }

    fun setCrossfadeKeepAlbums(keep: Boolean): Unit = synchronized(lock) {
        _state.value = _state.value.copy(crossfadeKeepAlbums = keep)
        prefs.edit { putBoolean(PlayerPrefs.CROSSFADE_KEEP_ALBUMS, keep) }
        syncNext()
    }

    fun setNormalizeAudio(enabled: Boolean) {
        prefs.edit { putBoolean(PlayerPrefs.NORMALIZE_AUDIO, enabled) }
        _state.value = _state.value.copy(normalizeAudio = enabled)
        engine.setNormalize(enabled)
    }

    /** Desktop only: the app's own output volume (0..1). */
    fun setVolume(volume: Float) {
        val v = safeGain(volume)
        prefs.edit { putFloat(PlayerPrefs.VOLUME, v) }
        _state.value = _state.value.copy(volume = v)
        engine.setVolume(v)
    }

    fun setAutoplay(enabled: Boolean): Unit = synchronized(lock) {
        continuationJob?.cancel()
        prefs.edit { putBoolean(PlayerPrefs.AUTOPLAY, enabled) }
        _state.value = _state.value.copy(autoplay = enabled)
        clearedUpNext = false
        removeAutoplayItems()
        refillQueue()
        changedQueue()
    }

    fun markUnplayable(id: Long?) { if (id != null) synchronized(lock) { failedSongs += id } }

    private fun removeAutoplayItems() {
        for (i in entries.size - 1 downTo index + 1) {
            if (entries[i].autoplay) entries.removeAt(i)
        }
        queueDirty = true
    }

    /** After any queue edit: next track, persistence, UI. */
    private fun changedQueue() {
        queueDirty = true
        syncNext()
        saveQueue()
        publish()
    }

    /** Fill ahead while the current song is still playing. */
    private fun refillQueue() {
        if (!playWhenReady || !_state.value.autoplay || clearedUpNext || roomSpeed != null ||
            repeatMode != RepeatMode.OFF || _sleepTimer.value == SleepTimer.EndOfTrack ||
            entries.getOrNull(index)?.song?.isSpoken == true || entries.isEmpty()) return
        val seed = entries.getOrNull(index)?.song ?: return
        val hiddenIds = hiddenSongs()
        val hiddenNames = hiddenArtists()
        val filter = hiddenIds.sorted().joinToString() + "|" + hiddenNames.sorted().joinToString()
        if (filter != continuationFilter) { continuationFilter = filter; continuationJob?.cancel(); removeAutoplayItems() }
        if (entries.size - index - 1 > 2 || continuationJob?.isActive == true) return
        val version = queueVersion
        val attempt = "$version:$index:${entries.size}:$filter"
        if (attempt == emptyRefillFor) return
        emptyRefillFor = attempt // cleared below once something is actually queued
        val failed = failedSongs.toSet()
        val sources = sourceSongs
        continuationJob = scope.launch {
            fun allowed(song: Song) = song.playable && !song.isPodcast && !song.isAudiobook && song.id !in failed &&
                song.id !in hiddenIds && song.creditedArtists.none { it in hiddenNames }
            // Ranking a radio scores every song in the library: keep it off the UI thread.
            var pool = withContext(computeDispatcher) {
                (radio(seed) + sources + librarySongs()).filter(::allowed).distinctBy { it.id }
            }
            // A single streamed song should also become a station, without saving picks to the library.
            if (pool.size < 4 && seed.isStream) {
                val online = try { withTimeoutOrNull(12_000) { onlineStation(seed) }.orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                pool = (pool + online.filter { it.playable }).filter(::allowed).distinctBy { it.id }
            }
            synchronized(lock) {
                if (version != queueVersion || !_state.value.autoplay || clearedUpNext ||
                    roomSpeed != null || repeatMode != RepeatMode.OFF || _sleepTimer.value == SleepTimer.EndOfTrack || index < 0) return@synchronized
                val recent = (0..index).map { entries[it].song.id }
                val queued = (index until entries.size).map { entries[it].song.id }.toSet()
                val byId = pool.associateBy { it.id }
                val candidates = if (_state.value.shuffle) pool.shuffled() else pool
                var ids = continuationIds(candidates.map { it.id }, recent, queued, 5)
                if (ids.isEmpty() && queued.size == 1 && pool.size == 1) ids = listOf(pool.single().id)
                if (ids.isEmpty()) return@synchronized
                val wasEnded = engineState == PlaybackState.ENDED || errored
                val next = entries.size
                emptyRefillFor = null
                ids.mapNotNullTo(entries) { byId[it]?.let { s -> newEntry(s, autoplay = true) } }
                queueDirty = true
                if (wasEnded && playWhenReady && next < entries.size) moveTo(next, 0, play = true, reason = Reason.SEEK)
                // Keep a recent history without allowing a radio session to grow without a bound.
                if (index > 40) { val drop = index - 30; entries.subList(0, drop).clear(); index -= drop }
                changedQueue()
            }
        }
    }

    /** The song the engine is actually on (not the cached UI state). */
    fun playingSongId(): Long? = synchronized(lock) {
        engine.currentUid?.let { u -> entries.firstOrNull { it.uid == u }?.song?.id } ?: _state.value.currentId
    }

    /**
     * Points queue items at replacement files (e.g. a FLAC converted to AAC) so they don't try to
     * play deleted files. The playing item is left alone; conversion never touches it.
     */
    fun remapSongs(ids: Map<Long, Long>): Unit = synchronized(lock) {
        for (i in entries.indices) {
            if (i == index) continue
            val e = entries[i]
            val newId = ids[e.song.id] ?: continue
            val song = resolve(newId) ?: continue
            entries[i] = e.copy(uid = nextUid++, song = song)
        }
        unshuffledOrder = unshuffledOrder?.map { ids[it] ?: it }
        changedQueue()
    }

    // ---- Sleep timer (fades out over the last 10 seconds) ----

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        engine.setSleepGain(1f)
        if (minutes == null) { _sleepTimer.value = null; synchronized(lock) { syncNext() }; return }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimer.value = SleepTimer.At(endsAt)
        synchronized(lock) { syncNext() }
        sleepJob = scope.launch(computeDispatcher) {
            while (isActive) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0) {
                    synchronized(lock) { pauseInternal() }
                    // Restore the gain once the pause fade has finished.
                    delay(300)
                    engine.setSleepGain(1f)
                    _sleepTimer.value = null
                    break
                }
                if (left < FADE_MS) engine.setSleepGain((left / FADE_MS.toFloat()).coerceIn(0f, 1f))
                delay(200)
            }
        }
    }

    fun sleepAtEndOfTrack() {
        sleepJob?.cancel()
        engine.setSleepGain(1f)
        _sleepTimer.value = SleepTimer.EndOfTrack
        synchronized(lock) { syncNext() }
    }

    // ---- Resume where you left off ----

    /** Persist queue + resume point right away (e.g. before the app closes). */
    fun saveNow(): Unit = synchronized(lock) {
        saveQueue()
        lastSongId?.let { saveResume(it, if (engine.currentUid != null) engine.positionMs else lastPosition, lastDuration) }
    }

    private fun savePosition() {
        prefs.edit {
            putInt(KEY_INDEX, index)
            putLong(KEY_POSITION, engine.positionMs)
        }
    }

    private fun saveQueue() {
        val snapshot = entries.toList()
        val pos = if (engine.currentUid != null) engine.positionMs else lastPosition
        prefs.edit {
            putString(KEY_QUEUE, snapshot.joinToString(",") { it.song.id.toString() })
            putString(KEY_AUTOPLAY_ITEMS, snapshot.indices.filter { snapshot[it].autoplay }.joinToString(","))
            putString(KEY_MANUAL, snapshot.indices.filter { snapshot[it].manual }.joinToString(","))
            putInt(KEY_INDEX, index)
            putLong(KEY_POSITION, pos)
            putInt(KEY_REPEAT, roomRepeat ?: repeatMode)
            putString(KEY_UNSHUFFLED, unshuffledOrder?.joinToString(","))
        }
    }

    private suspend fun restoreQueue() {
        val ids = prefs.getString(KEY_QUEUE, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.isEmpty()) return
        withTimeoutOrNull(5_000) { awaitLibrary() }
        val manual = prefs.getString(KEY_MANUAL, null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty().toSet()
        val autoplayItems = prefs.getString(KEY_AUTOPLAY_ITEMS, null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty().toSet()
        val restored = ids.mapIndexedNotNull { i, id -> resolve(id)?.let { i to it } }
        if (restored.isEmpty()) return
        val savedIndex = prefs.getInt(KEY_INDEX, 0)
        val position = prefs.getLong(KEY_POSITION, 0)
        synchronized(lock) {
            if (entries.isNotEmpty()) return // the user started something meanwhile
            val at = restored.indexOfFirst { it.first >= savedIndex }.let { if (it < 0) restored.lastIndex else it }
            sourceSongs = restored.filter { it.first !in autoplayItems && it.first !in manual }.map { it.second }
            restored.mapTo(entries) { (i, song) -> newEntry(song, manual = i in manual, autoplay = i !in manual && i in autoplayItems) }
            repeatMode = prefs.getInt(KEY_REPEAT, RepeatMode.OFF)
            index = at
            onTransition(entries[at], Reason.PLAYLIST_CHANGED)
            playWhenReady = false
            engine.load(trackFor(entries[at]), position, play = false)
            sentNext = null
            _position.value = position
            syncNext()
            publish()
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val FADE_MS = 10_000L
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_SKIP_SILENCE = "skip_silence"
        private const val KEY_QUEUE = "queue"
        private const val KEY_AUTOPLAY_ITEMS = "autoplay_indices"
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

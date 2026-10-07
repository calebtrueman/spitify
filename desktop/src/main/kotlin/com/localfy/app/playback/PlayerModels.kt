package com.localfy.app.playback

import com.localfy.app.data.Song

/** Repeat modes, with the same values as Media3's Player.REPEAT_MODE_*. */
object RepeatMode {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

/** Playback states, with the same values as Media3's Player.STATE_*. */
object PlaybackState {
    const val IDLE = 1
    const val BUFFERING = 2
    const val READY = 3
    const val ENDED = 4
}

/**
 * Media3-named aliases, so UI code ported from Android (`Player.STATE_ENDED`, `Player.REPEAT_MODE_ONE`)
 * only needs its import changed to `com.localfy.app.playback.Player`.
 */
object Player {
    const val STATE_IDLE = PlaybackState.IDLE
    const val STATE_BUFFERING = PlaybackState.BUFFERING
    const val STATE_READY = PlaybackState.READY
    const val STATE_ENDED = PlaybackState.ENDED
    const val REPEAT_MODE_OFF = RepeatMode.OFF
    const val REPEAT_MODE_ONE = RepeatMode.ONE
    const val REPEAT_MODE_ALL = RepeatMode.ALL
}

data class PlayerUiState(
    val connected: Boolean = false,
    /** Song ids in play order (shuffle is applied to the real queue, so this is always what plays next). */
    val queue: List<Long> = emptyList(),
    val currentIndex: Int = -1,
    val manualQueueIndices: Set<Int> = emptySet(),
    val autoplayQueueIndices: Set<Int> = emptySet(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackState: Int = PlaybackState.IDLE,
    val shuffle: Boolean = false,
    val repeatMode: Int = RepeatMode.OFF,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val autoplay: Boolean = true,
    val normalizeAudio: Boolean = true,
    val crossfadeMs: Int = 0,
    val crossfadeKeepAlbums: Boolean = true,
    /** What the queue was started from, e.g. "Liked Songs" - shown as "Playing from". */
    val source: String? = null,
    /** Desktop only: output volume 0..1 (phones use the system volume). */
    val volume: Float = 1f,
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

/** Shared keys for the "player" prefs file (same names as on Android). */
object PlayerPrefs {
    const val FILE = "player"
    const val NORMALIZE_AUDIO = "normalize_audio"
    const val AUTOPLAY = "autoplay"
    const val CROSSFADE_MS = "crossfade_ms"
    const val CROSSFADE_KEEP_ALBUMS = "crossfade_keep_albums"
    const val VOLUME = "volume"
}

/** Podcast episodes and audiobook chapters share spoken-word behaviour. */
val Song.isSpoken: Boolean get() = isPodcast || isAudiobook

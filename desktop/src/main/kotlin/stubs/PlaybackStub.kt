// UI STUB — deleted at integration
package com.localfy.app.playback

import com.localfy.app.data.Song
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Media3's Player.REPEAT_MODE_* values. */
object RepeatMode {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

data class PlayerUiState(
    val connected: Boolean = false,
    val queue: List<Long> = emptyList(),
    val currentIndex: Int = -1,
    val manualQueueIndices: Set<Int> = emptySet(),
    val autoplayQueueIndices: Set<Int> = emptySet(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = RepeatMode.OFF,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val autoplay: Boolean = true,
    val normalizeAudio: Boolean = true,
    val crossfadeMs: Int = 0,
    val crossfadeKeepAlbums: Boolean = true,
    val source: String? = null,
) {
    val currentId: Long? get() = queue.getOrNull(currentIndex)
    val hasMedia: Boolean get() = currentIndex >= 0
    val upNext: List<IndexedValue<Long>> get() = queue.withIndex().drop((currentIndex + 1).coerceAtLeast(0))
}

sealed interface SleepTimer {
    data class At(val endsAtMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

class PlayerConnection {
    val messages: SharedFlow<String> = MutableSharedFlow()
    val state: StateFlow<PlayerUiState> = MutableStateFlow(PlayerUiState())
    val positionMs: StateFlow<Long> = MutableStateFlow(0L)
    val sleepTimer: StateFlow<SleepTimer?> = MutableStateFlow(null)
    fun connect() {}
    var queueVersion: Long = 0; private set
    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean? = null, source: String? = null, startPositionMs: Long = 0L) {}
    fun playBook(chapters: List<Song>, startIndex: Int, source: String?) {}
    fun playEpisode(song: Song, source: String? = song.album) {}
    fun skipBy(deltaMs: Long) {}
    fun playNext(songs: List<Song>) {}
    fun addToQueue(songs: List<Song>): Boolean = false
    fun appendFromSource(songs: List<Song>) {}
    fun togglePlay() {}
    fun next() {}
    fun previous() {}
    fun seekTo(ms: Long) {}
    fun skipTo(index: Int) {}
    fun removeAt(index: Int) {}
    fun move(from: Int, to: Int) {}
    fun clearUpNext() {}
    fun cycleRepeat() {}
    fun toggleShuffle() {}
    fun setSpeed(speed: Float) {}
    fun setSkipSilence(enabled: Boolean) {}
    fun setCrossfade(ms: Int) {}
    fun setCrossfadeKeepAlbums(keep: Boolean) {}
    fun setNormalizeAudio(enabled: Boolean) {}
    fun setAutoplay(enabled: Boolean) {}
    fun setSleepTimer(minutes: Int?) {}
    fun sleepAtEndOfTrack() {}
    fun saveNow() {}
}

val EqFrequencies = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

data class EqPreset(val name: String, val gains: List<Float>)

val EqPresets = listOf(EqPreset("Flat", List(10) { 0f }))

data class EqCapabilities(
    val engine: String,
    val bandFrequencies: List<Float>,
    val minDb: Float,
    val maxDb: Float,
    val bassBoost: Boolean,
    val virtualizer: Boolean,
    val loudness: Boolean,
)

data class EqState(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    val gains: List<Float> = List(EqFrequencies.size) { 0f },
    val bass: Float = 0f,
    val surround: Float = 0f,
    val loudnessDb: Float = 0f,
    val limiter: Boolean = true,
)

object EqStore {
    val capabilities: StateFlow<EqCapabilities?> = MutableStateFlow(null)
    val state: StateFlow<EqState> = MutableStateFlow(EqState())
    fun update(transform: (EqState) -> EqState) {}
    fun applyPreset(p: EqPreset) {}
    fun setGain(index: Int, db: Float) {}
}

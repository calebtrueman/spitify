package com.localfy.app.playback

/**
 * Something the engine can play. [uid] is the queue slot (the engine reports transitions by it);
 * [url] is resolved on the engine's decode thread, so a stream URL lookup never blocks the UI.
 * It returns a `file:` URI, a plain path or an http(s) URL; null means "no source" (offline stream).
 */
class EngineTrack(
    val uid: Long,
    val songId: Long,
    val title: String,
    val spoken: Boolean = false,
    val isRemote: Boolean = false,
    val url: suspend () -> String?,
)

/** Why a track couldn't play; mirrors the Media3 error-code groups Android's messages use. */
data class EngineError(val kind: Kind, val message: String) {
    enum class Kind { UNSUPPORTED, NETWORK, OTHER }
}

/** Engine callbacks. They arrive on the engine's event thread, never the audio thread. */
interface AudioEngineListener {
    /** [playbackState] is a [PlaybackState] value; isPlaying = playWhenReady && READY. */
    fun onStateChanged(playbackState: Int, playWhenReady: Boolean) {}
    /** The engine moved on to the queued next track by itself (gapless or crossfade). */
    fun onAutoTransition(uid: Long) {}
    fun onDuration(uid: Long, durationMs: Long) {}
    fun onError(uid: Long, error: EngineError) {}
}

/**
 * The audio back end behind [PlayerConnection]: one current track, at most one queued next track
 * (opened ahead so the change is gapless, or crossfaded), and the effect settings. All methods are
 * non-blocking and safe from any thread.
 */
interface AudioEngine {
    var listener: AudioEngineListener?

    /** Position in the current track, accounting for audio still in the output buffer. */
    val positionMs: Long
    /** Duration of the current track, or 0 while unknown. */
    val durationMs: Long
    val playbackState: Int
    val playWhenReady: Boolean
    val isPlaying: Boolean get() = playWhenReady && playbackState == PlaybackState.READY
    /** The queue slot the engine is actually playing. */
    val currentUid: Long?

    /** Replaces whatever is playing (and the queued next track) with [track] at [startMs]. */
    fun load(track: EngineTrack, startMs: Long, play: Boolean)
    /** What follows the current track; [crossfadeMs] 0 = gapless. Null = stop at the end. */
    fun setNext(track: EngineTrack?, crossfadeMs: Int)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    /** Drops all tracks (state IDLE). */
    fun stop()

    fun setSpeed(speed: Float)
    fun setSkipSilence(enabled: Boolean)
    fun setNormalize(enabled: Boolean)
    fun setVolume(volume: Float)
    /** Sleep-timer fade gain 0..1, multiplied with the volume. */
    fun setSleepGain(gain: Float)
    fun setEq(state: EqState)

    fun release()
}

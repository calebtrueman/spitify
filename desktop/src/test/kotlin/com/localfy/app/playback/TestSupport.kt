package com.localfy.app.playback

import com.localfy.app.data.Song
import java.nio.file.Files

/** Keeps tests away from the real data folder (AppPaths reads this property once, lazily). */
object TestEnv {
    init {
        if (System.getProperty("spitify.dataDir") == null) {
            System.setProperty("spitify.dataDir", Files.createTempDirectory("spitify-test-data").toString())
            System.setProperty("spitify.cacheDir", Files.createTempDirectory("spitify-test-cache").toString())
        }
    }
    fun ensure() = Unit
}

fun song(
    id: Long,
    album: String = "Album $id",
    track: Int = 0,
    artist: String = "Artist $id",
    durationMs: Long = 200_000,
    fileName: String = "song$id.flac",
    podcast: Boolean = false,
    episodeId: Long? = null,
    sourceUri: String? = "file:///music/song$id.flac",
) = Song(
    id = id, title = "T$id", artist = artist, album = album, albumId = album.hashCode().toLong(), albumArtist = artist,
    durationMs = durationMs, track = track, disc = 1, year = 2020, genre = null, folder = "/music", dateAddedSec = 0,
    sizeBytes = 0, mimeType = null, fileName = fileName, sourceUri = sourceUri, isPodcast = podcast, episodeId = episodeId,
)

/** An [AudioEngine] that just records what it was told; tests drive its events by hand. */
class FakeEngine : AudioEngine {
    override var listener: AudioEngineListener? = null
    override var positionMs = 0L
    override var durationMs = 0L
    override var playbackState = PlaybackState.IDLE
    override var playWhenReady = false
    override var currentUid: Long? = null

    var current: EngineTrack? = null
    var currentStartMs = 0L
    var next: EngineTrack? = null
    var nextCrossfadeMs = 0
    val loads = mutableListOf<EngineTrack>()
    val seeks = mutableListOf<Long>()
    var speedSet = 1f; private set
    var sleepGainSet = 1f; private set
    var released = false

    override fun load(track: EngineTrack, startMs: Long, play: Boolean) {
        current = track; currentStartMs = startMs; currentUid = track.uid
        positionMs = startMs; playWhenReady = play; next = null; nextCrossfadeMs = 0
        playbackState = PlaybackState.BUFFERING
        loads += track
    }
    override fun setNext(track: EngineTrack?, crossfadeMs: Int) { next = track; nextCrossfadeMs = crossfadeMs }
    override fun play() { playWhenReady = true }
    override fun pause() { playWhenReady = false }
    override fun seekTo(ms: Long) { positionMs = ms; seeks += ms }
    override fun stop() { current = null; currentUid = null; playbackState = PlaybackState.IDLE }
    override fun setSpeed(speed: Float) { speedSet = speed }
    override fun setSkipSilence(enabled: Boolean) {}
    override fun setNormalize(enabled: Boolean) {}
    override fun setVolume(volume: Float) {}
    override fun setSleepGain(gain: Float) { sleepGainSet = gain }
    override fun setEq(state: EqState) {}
    override fun release() { released = true }

    /** The current track has data: READY. */
    fun ready() { playbackState = PlaybackState.READY; listener?.onStateChanged(playbackState, playWhenReady) }

    /** The current track plays to its end: gapless move to [next], or ENDED. */
    fun finishTrack() {
        val n = next
        if (n != null) {
            current = n; currentUid = n.uid; positionMs = 0; next = null
            listener?.onAutoTransition(n.uid)
        } else {
            playbackState = PlaybackState.ENDED
            listener?.onStateChanged(playbackState, playWhenReady)
        }
    }

    fun fail(kind: EngineError.Kind) {
        playbackState = PlaybackState.IDLE
        listener?.onError(currentUid ?: -1, EngineError(kind, "test"))
    }
}

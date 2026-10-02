package com.localfy.app.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfade between consecutive tracks.
 *
 * The session's [main] player always owns the real queue. A few seconds before a track ends we
 * start a throwaway "tail" player on the *outgoing* track at the same position, move [main] to the
 * next track at zero volume, and ramp the two with an equal-power curve. Notification, lock screen,
 * Bluetooth and the UI therefore see an ordinary track change - just smoother.
 */
class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val attributes: AudioAttributes,
    private val resolve: (MediaItem) -> MediaItem,
) : Player.Listener {

    private val prefs: SharedPreferences = context.getSharedPreferences(PlayerPrefs.FILE, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private var tail: ExoPlayer? = null
    private var tailForIndex = C.INDEX_UNSET
    private var fadeStartedAt = 0L
    private var fadeLength = 0L
    private var baseVolume = 1f
    /** Listener callbacks arrive asynchronously, so our own skip is marked and consumed once. */
    private var ignoreNextSeek = false

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, if (fadeLength > 0 || tail != null) 30L else 120L)
        }
    }

    fun start() {
        main.addListener(this)
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacks(tick)
        main.removeListener(this)
        dropTail()
    }

    private val crossfadeMs: Long get() = prefs.getInt(PlayerPrefs.CROSSFADE_MS, 0).toLong()
    private val keepAlbumsGapless: Boolean get() = prefs.getBoolean(PlayerPrefs.CROSSFADE_KEEP_ALBUMS, true)

    private fun step() {
        if (fadeLength > 0) return ramp()
        val cf = crossfadeMs
        if (cf <= 0 || !main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE) { dropTail(); return }
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration < cf * 2 + 2_000) return
        val next = main.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        val index = main.currentMediaItemIndex
        if (keepAlbumsGapless && isConsecutiveAlbumTrack(index, next)) return
        // Spoken word never crossfades.
        if (main.getMediaItemAt(index).mediaMetadata.mediaType.isSpoken() || main.getMediaItemAt(next).mediaMetadata.mediaType.isSpoken()) return

        val remaining = duration - main.currentPosition
        if (remaining <= cf + PRELOAD_MS && tailForIndex != index) prepareTail(index, duration - cf)
        if (remaining <= cf && tailForIndex == index && tail?.playbackState == Player.STATE_READY) beginFade(remaining)
    }

    private fun prepareTail(index: Int, startAt: Long) {
        dropTail()
        tail = buildPlayer(context)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ false)
            .build()
            .apply {
                // Same audio session as the main player so the equaliser shapes the fading tail too.
                setAudioSessionId(main.audioSessionId)
                setMediaItem(resolve(main.getMediaItemAt(index)), startAt)
                playWhenReady = false
                prepare()
            }
        tailForIndex = index
    }

    private fun beginFade(remaining: Long) {
        val t = tail ?: return
        baseVolume = main.volume.takeIf { it > 0f } ?: 1f
        fadeLength = remaining.coerceAtLeast(500)
        fadeStartedAt = SystemClock.elapsedRealtime()
        t.seekTo(main.currentPosition)
        t.volume = baseVolume
        t.play()
        ignoreNextSeek = true
        main.volume = 0f
        main.seekToNextMediaItem()
    }

    private fun ramp() {
        val p = ((SystemClock.elapsedRealtime() - fadeStartedAt) / fadeLength.toFloat()).coerceIn(0f, 1f)
        main.volume = baseVolume * sin(p * PI / 2).toFloat()
        tail?.volume = baseVolume * cos(p * PI / 2).toFloat()
        if (p >= 1f) finishFade()
    }

    private fun finishFade() {
        dropTail()
        main.volume = baseVolume
        fadeLength = 0
    }

    private fun dropTail() {
        tail?.release()
        tail = null
        tailForIndex = C.INDEX_UNSET
    }

    private fun isConsecutiveAlbumTrack(index: Int, next: Int): Boolean {
        val a = main.getMediaItemAt(index).mediaMetadata
        val b = main.getMediaItemAt(next).mediaMetadata
        val ta = a.trackNumber ?: return false
        val tb = b.trackNumber ?: return false
        return a.albumTitle != null && a.albumTitle == b.albumTitle && tb == ta + 1
    }

    // Any user interaction during a fade (pause, seek, skip) ends it instantly.
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) interrupt()
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (ignoreNextSeek) ignoreNextSeek = false else interrupt()
    }

    private fun interrupt() {
        if (fadeLength > 0) finishFade() else dropTail()
    }

    companion object {
        private const val PRELOAD_MS = 1_500L
    }
}

/** Shared keys for the "player" prefs file read by both the UI and the service. */
object PlayerPrefs {
    const val FILE = "player"
    const val CROSSFADE_MS = "crossfade_ms"
    const val CROSSFADE_KEEP_ALBUMS = "crossfade_keep_albums"
}

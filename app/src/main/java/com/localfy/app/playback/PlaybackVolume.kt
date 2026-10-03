package com.localfy.app.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** The sleep timer and crossfade share one gain. Neither changes the phone or speaker volume. */
internal class PlaybackVolume(private val player: ExoPlayer) : Player.Listener {
    private var chosen = player.volume
    private var applied = chosen
    private var sleep = 1f
    private var incoming = 1f
    private var outgoing = 0f
    private var tail: ExoPlayer? = null

    init { player.addListener(this) }

    fun setSleep(value: Float) { sleep = safeGain(value); apply() }
    fun setFade(next: Float, previous: Float, tailPlayer: ExoPlayer?) {
        incoming = safeGain(next); outgoing = safeGain(previous); tail = tailPlayer; apply()
    }
    private fun apply() {
        applied = combinedGain(chosen, sleep, incoming)
        player.volume = applied
        tail?.volume = combinedGain(chosen, sleep, outgoing)
    }
    override fun onEvents(player: Player, events: Player.Events) {
        // Read the latest value: player callbacks may be batched after several fade updates.
        if (events.contains(Player.EVENT_VOLUME_CHANGED) && player.volume != applied) {
            chosen = safeGain(player.volume)
            apply()
        }
    }
    fun release() { player.removeListener(this); tail = null }
}

internal fun safeGain(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
internal fun combinedGain(chosen: Float, sleep: Float, fade: Float) = safeGain(chosen) * safeGain(sleep) * safeGain(fade)

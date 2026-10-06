package com.localfy.app.playback

import kotlin.math.PI

object VinylScrub {
    /** Smallest signed turn, including crossing the top/left angle boundary. */
    fun delta(previous: Double, current: Double): Double {
        var value = current - previous
        while (value > PI) value -= 2 * PI
        while (value < -PI) value += 2 * PI
        return value
    }
    fun position(startMs: Long, radians: Double, durationMs: Long): Long =
        (startMs + radians / (2 * PI) * 30_000).toLong().coerceIn(0, durationMs.coerceAtLeast(0))
}

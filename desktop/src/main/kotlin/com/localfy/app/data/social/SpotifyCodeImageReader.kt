package com.localfy.app.data.social

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Reads Spotify code bar heights from pixels locally. Run off the UI thread. */
object SpotifyCodeImageReader {
    fun read(image: BufferedImage): Long? {
        val bitmap = FriendPictureCode.shrink(image, 1200)
        val width = bitmap.width; val height = bitmap.height
        val pixels = bitmap.getRGB(0, 0, width, height, null, 0, width)
        return read(pixels, width, height)
    }

    /** [pixels] are packed RGB rows, as from [BufferedImage.getRGB]. */
    fun read(pixels: IntArray, width: Int, height: Int): Long? {
        val gray = IntArray(pixels.size) { i -> val c = pixels[i]; (((c shr 16) and 255)*299 + ((c shr 8) and 255)*587 + (c and 255)*114)/1000 }
        for (rotated in listOf(false, true)) {
            val w = if (rotated) height else width; val h = if (rotated) width else height
            for (threshold in listOf(64, 128, 192)) for (inverted in listOf(false, true)) {
                fun ink(x: Int, y: Int): Boolean { val v = gray[if (rotated) x * width + y else y * width + x]; return if (inverted) v > threshold else v < threshold }
                val tried = mutableSetOf<List<Int>>()
                for (y in 0 until h step 2) {
                    val runs = mutableListOf<Pair<Int, Int>>(); var start: Int? = null
                    for (x in 0..w) {
                        if (x < w && ink(x, y)) { if (start == null) start = x }
                        else { start?.let { if (x - it >= 2) runs += it to x - 1 }; start = null }
                    }
                    if (runs.size < 23) continue
                    for (offset in 0..runs.size - 23) {
                        val bars = runs.subList(offset, offset + 23); val centers = bars.map { (it.first + it.second)/2 }
                        val spacing = (centers[22] - centers[0])/22.0
                        if (spacing < 4 || centers.zipWithNext().any { abs(it.second - it.first - spacing) >= spacing*.18 } || bars.any { it.second - it.first + 1 >= spacing*.8 }) continue
                        val lengths = centers.map { x -> var a = y; var b = y
                            while (a > 0 && ink(x, a - 1)) a--
                            while (b < h - 1 && ink(x, b + 1)) b++
                            b - a + 1
                        }
                        val small = (lengths[0] + lengths[22])/2.0; val large = lengths[11].toDouble()
                        if (large <= small*2 || abs(lengths[0] - lengths[22]) >= spacing*.4) continue
                        val levels = lengths.map { ((it - small)/(large - small)*7).roundToInt() }
                        if (!tried.add(levels)) continue
                        (SpotifyCodeDecoder.decode(levels) ?: SpotifyCodeDecoder.decode(levels.reversed()))?.let { return it }
                    }
                }
            }
        }
        return null
    }
}

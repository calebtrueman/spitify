package com.localfy.app.ui.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A small stand-in for AndroidX Palette: buckets the artwork's pixels by hue, saturation and
 * lightness and picks swatches with the same targets Palette uses (vibrant, dark/light vibrant,
 * muted, dominant). Runs on a ~96 px bitmap, so it costs well under a millisecond.
 */
class ArtPalette private constructor(
    val vibrant: Color?,
    val darkVibrant: Color?,
    val lightVibrant: Color?,
    val muted: Color?,
    val dominant: Color?,
) {
    private class Bucket { var r = 0L; var g = 0L; var b = 0L; var count = 0
        fun color() = Color((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private data class Swatch(val color: Color, val population: Int, val h: Float, val s: Float, val l: Float)

    companion object {
        fun from(image: ImageBitmap): ArtPalette {
            val w = image.width; val h = image.height
            if (w <= 0 || h <= 0) return ArtPalette(null, null, null, null, null)
            // Sample at most ~10k pixels.
            val step = max(1, (max(w, h) / 100))
            val pixels = IntArray(w * h)
            image.readPixels(pixels, 0, 0, w, h)
            val buckets = HashMap<Int, Bucket>()
            val hsl = FloatArray(3)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = pixels[y * w + x]
                    if ((p ushr 24) >= 128) {
                        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                        toHsl(r, g, b, hsl)
                        // Ignore near-white / near-black pixels like Palette's default filter.
                        if (hsl[2] in 0.05f..0.95f) {
                            val key = ((hsl[0] / 20f).toInt() * 100) + ((hsl[1] * 4.99f).toInt() * 10) + (hsl[2] * 4.99f).toInt()
                            val bucket = buckets.getOrPut(key) { Bucket() }
                            bucket.r += r; bucket.g += g; bucket.b += b; bucket.count++
                        }
                    }
                    x += step
                }
                y += step
            }
            if (buckets.isEmpty()) return ArtPalette(null, null, null, null, null)
            val swatches = buckets.values.map { bucket ->
                val c = bucket.color()
                toHsl((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsl)
                Swatch(c, bucket.count, hsl[0], hsl[1], hsl[2])
            }
            val maxPopulation = swatches.maxOf { it.population }.toFloat()
            fun pick(targetL: Float, minL: Float, maxL: Float, targetS: Float, minS: Float, maxS: Float): Color? =
                swatches.filter { it.l in minL..maxL && it.s in minS..maxS }.maxByOrNull { s ->
                    (1f - abs(s.s - targetS)) * 3f + (1f - abs(s.l - targetL)) * 6.5f + (s.population / maxPopulation) * 0.5f
                }?.color
            return ArtPalette(
                vibrant = pick(0.5f, 0.3f, 0.7f, 1f, 0.35f, 1f),
                darkVibrant = pick(0.26f, 0f, 0.45f, 1f, 0.35f, 1f),
                lightVibrant = pick(0.74f, 0.55f, 1f, 1f, 0.35f, 1f),
                muted = pick(0.5f, 0.3f, 0.7f, 0.3f, 0f, 0.4f),
                dominant = swatches.maxByOrNull { it.population }?.color,
            )
        }

        private fun toHsl(r: Int, g: Int, b: Int, out: FloatArray) {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
            val l = (mx + mn) / 2f
            val d = mx - mn
            var hue = 0f; var sat = 0f
            if (d > 0f) {
                sat = d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-4f)
                hue = when (mx) {
                    rf -> ((gf - bf) / d).mod(6f)
                    gf -> (bf - rf) / d + 2f
                    else -> (rf - gf) / d + 4f
                } * 60f
            }
            out[0] = hue.coerceIn(0f, 359.9f); out[1] = sat.coerceIn(0f, 1f); out[2] = l
        }
    }
}

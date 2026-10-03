package com.localfy.app.data.social

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** The saved photo stays large; the preview keeps older apps compatible. */
object ProfilePhotos {
    fun jpeg(source: Bitmap, sides: List<Int>, limit: Int): ByteArray? {
        val images = sides.map { side -> Bitmap.createScaledBitmap(source, side, side, true) }
        try {
            for (quality in listOf(88, 78, 68, 58, 48, 38)) {
                for (image in images) {
                    val bytes = ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
                    if (bytes.size <= limit) return bytes
                }
            }
            return null
        } finally { images.filter { it !== source }.distinct().forEach { it.recycle() } }
    }
    fun preview(source: Bitmap): String? = jpeg(source, listOf(96, 80), 2000)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    fun shared(source: Bitmap): String? = jpeg(source, listOf(768, 640, 512, 384), 18000)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
}

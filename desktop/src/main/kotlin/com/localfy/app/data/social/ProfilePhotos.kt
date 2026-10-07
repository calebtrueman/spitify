package com.localfy.app.data.social

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/** The saved photo stays large; the preview keeps older apps compatible. Run off the UI thread. */
object ProfilePhotos {
    fun load(file: File): BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

    fun jpeg(source: BufferedImage, sides: List<Int>, limit: Int): ByteArray? {
        val square = centerSquare(source)
        val images = sides.map { side -> scale(square, side) }
        for (quality in listOf(88, 78, 68, 58, 48, 38)) {
            for (image in images) {
                val bytes = encode(image, quality / 100f)
                if (bytes.size <= limit) return bytes
            }
        }
        return null
    }
    fun preview(source: BufferedImage): String? = jpeg(source, listOf(96, 80), 2000)?.let { Base64.getEncoder().encodeToString(it) }
    fun shared(source: BufferedImage): String? = jpeg(source, listOf(768, 640, 512, 384), 18000)?.let { Base64.getEncoder().encodeToString(it) }

    /** Phones store a square photo; a desktop file may not be, so crop the middle instead of stretching. */
    private fun centerSquare(source: BufferedImage): BufferedImage {
        val side = minOf(source.width, source.height)
        return if (source.width == source.height) source else source.getSubimage((source.width - side) / 2, (source.height - side) / 2, side, side)
    }

    /** Scales in halving steps so large photos stay smooth (bilinear alone would alias). */
    private fun scale(source: BufferedImage, side: Int): BufferedImage {
        var current = source; var size = source.width
        while (true) {
            size = if (size / 2 >= side) size / 2 else side
            val next = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val g = next.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.color = java.awt.Color.WHITE; g.fillRect(0, 0, size, size)
                g.drawImage(current, 0, 0, size, size, null)
            } finally { g.dispose() }
            current = next
            if (size == side) return current
        }
    }

    private fun encode(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val out = ByteArrayOutputStream()
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally { writer.dispose() }
    }
}

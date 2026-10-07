package com.localfy.app.data

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Image helpers for Spitify Desktop (the Android app uses ImageDecoder/Bitmap). */
object Images {
    /** Largest source we try to decode; bigger files are almost certainly not covers. */
    private const val MAX_SOURCE_BYTES = 40_000_000

    /** Decodes JPEG/PNG/GIF/BMP bytes; null when unreadable. */
    fun decode(bytes: ByteArray): BufferedImage? = runCatching {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return null
        ImageIO.read(ByteArrayInputStream(bytes))
    }.getOrNull()

    /** Scales [image] so neither side exceeds [maxPx] (never upscales); optionally crops to a centred square. */
    fun scale(image: BufferedImage, maxPx: Int, square: Boolean = false): BufferedImage {
        var src = image
        if (square) {
            val side = minOf(src.width, src.height)
            src = src.getSubimage((src.width - side) / 2, (src.height - side) / 2, side, side)
        }
        val ratio = minOf(1.0, maxPx.toDouble() / maxOf(src.width, src.height))
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        // JPEG needs RGB; transparent areas are flattened onto white.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            // Halve repeatedly first: one bilinear pass from a huge image looks jagged.
            var step: BufferedImage = src
            while (step.width / 2 >= w * 2 && step.height / 2 >= h * 2) {
                val half = BufferedImage(step.width / 2, step.height / 2, BufferedImage.TYPE_INT_ARGB)
                val hg = half.createGraphics()
                hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                hg.drawImage(step, 0, 0, half.width, half.height, null)
                hg.dispose()
                step = half
            }
            g.drawImage(step, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    fun jpeg(image: BufferedImage, quality: Float = 0.9f): ByteArray {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image else scale(image, maxOf(image.width, image.height))
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** [bytes] downscaled to at most [maxPx] as JPEG; returns the original when it's already small enough and a JPEG/PNG. */
    fun downscaled(bytes: ByteArray, maxPx: Int): ByteArray? {
        val image = decode(bytes) ?: return null
        if (maxOf(image.width, image.height) <= maxPx && (isJpeg(bytes) || isPng(bytes))) return bytes
        return jpeg(scale(image, maxPx))
    }

    /** Centred square JPEG of at most [size] px. */
    fun squareJpeg(bytes: ByteArray, size: Int): ByteArray? {
        val image = decode(bytes) ?: return null
        return jpeg(scale(image, size, square = true))
    }

    fun isJpeg(b: ByteArray) = b.size > 3 && (b[0].toInt() and 255) == 0xFF && (b[1].toInt() and 255) == 0xD8
    fun isPng(b: ByteArray) = b.size > 8 && (b[0].toInt() and 255) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()
}

/**
 * Decodes an image (file bytes) into a centred [size]×[size] square and saves it as JPEG, atomically.
 * Same contract as the Android helper: false when the image can't be read.
 */
fun saveSquareImage(source: ByteArray, file: File, size: Int): Boolean = runCatching {
    val jpeg = Images.squareJpeg(source, size) ?: return false
    writeAtomically(file, jpeg)
    true
}.getOrDefault(false)

fun saveSquareImage(source: File, file: File, size: Int): Boolean =
    runCatching { source.length() <= 40_000_000 && saveSquareImage(source.readBytes(), file, size) }.getOrDefault(false)

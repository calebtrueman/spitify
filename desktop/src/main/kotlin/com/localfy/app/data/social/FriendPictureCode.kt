package com.localfy.app.data.social

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** QR "picture codes" holding a spitify://person/<key> friend code (same as the phones). */
object FriendPictureCode {
    fun make(value: String): BufferedImage {
        require(SocialLink.parse(value)?.type == "person")
        val bits = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 4))
        return BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB).apply {
            val pixels = IntArray(600 * 600) { index -> if (bits[index % 600, index / 600]) 0x000000 else 0xFFFFFF }
            setRGB(0, 0, 600, 600, pixels, 0, 600)
        }
    }

    /** Reads a friend code from an image file. Run off the UI thread. */
    fun read(file: File): String? = runCatching {
        val image = ImageIO.read(file) ?: return null
        decode(image)
    }.getOrNull()

    fun decode(image: BufferedImage): String? {
        val source = shrink(image, 1600)
        val pixels = source.getRGB(0, 0, source.width, source.height, null, 0, source.width)
        val luminance = RGBLuminanceSource(source.width, source.height, pixels)
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance)), mapOf(DecodeHintType.TRY_HARDER to true)).text }.getOrNull()?.takeIf { SocialLink.parse(it)?.type == "person" }
    }

    fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    /** Desktop stand-in for the phone share sheet: save the picture where the user chose. */
    fun save(image: BufferedImage, file: File) { file.absoluteFile.parentFile?.mkdirs(); check(ImageIO.write(image, "png", file)) { "The picture could not be saved." } }

    /** Copies the picture so it can be pasted into a chat. Needs a display. */
    fun copyToClipboard(image: BufferedImage) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
        }, null)
    }

    internal fun shrink(image: BufferedImage, max: Int): BufferedImage {
        val scale = minOf(1.0, max.toDouble() / maxOf(image.width, image.height))
        if (scale >= 1.0 && image.type == BufferedImage.TYPE_INT_RGB) return image
        val w = maxOf(1, (image.width * scale).toInt()); val h = maxOf(1, (image.height * scale).toInt())
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out ->
            val g = out.createGraphics()
            try { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h); g.drawImage(image, 0, 0, w, h, null) } finally { g.dispose() }
        }
    }
}

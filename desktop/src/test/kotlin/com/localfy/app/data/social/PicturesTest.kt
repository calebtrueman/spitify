package com.localfy.app.data.social

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO

class PicturesTest {
    private val key = "0123456789abcdef".repeat(4)

    @Test fun friendCodeRoundTrip() {
        val link = SocialLink("person", key).url
        val image = FriendPictureCode.make(link)
        assertEquals(600, image.width)
        assertEquals(link, FriendPictureCode.decode(image))
        // Through a PNG file, scaled up and pasted onto a bigger photo.
        val photo = BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color.LIGHT_GRAY; fillRect(0, 0, 2400, 1800); drawImage(image, 500, 300, 1200, 1200, null); dispose() } }
        val file = File(Files.createTempDirectory("code").toFile(), "code.png")
        FriendPictureCode.save(photo, file)
        assertEquals(link, FriendPictureCode.read(file))
        file.parentFile.deleteRecursively()
    }

    @Test fun friendCodeRejectsOtherContent() {
        assertThrows(IllegalArgumentException::class.java) { FriendPictureCode.make("https://example.com") }
        val bits = com.google.zxing.qrcode.QRCodeWriter().encode("https://example.com", com.google.zxing.BarcodeFormat.QR_CODE, 300, 300)
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB).apply { for (x in 0 until 300) for (y in 0 until 300) setRGB(x, y, if (bits[x, y]) 0 else 0xFFFFFF) }
        assertNull(FriendPictureCode.decode(image))
        assertNull(FriendPictureCode.decode(BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)))
        assertNull(FriendPictureCode.read(File("/does/not/exist.png")))
    }

    @Test fun profilePhotosFitTheirLimits() {
        val photo = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics(); for (i in 0 until 60) { g.color = Color(i * 4, 255 - i * 4, (i * 37) % 255); g.fillOval(i * 25, i * 17 % 1100, 200, 160) }; g.dispose()
        }
        val preview = ProfilePhotos.preview(photo)!!; val shared = ProfilePhotos.shared(photo)!!
        assertTrue(Base64.getDecoder().decode(preview).size <= 2000); assertTrue(Base64.getDecoder().decode(shared).size <= 18000)
        val decoded = ImageIO.read(Base64.getDecoder().decode(shared).inputStream())
        assertEquals(decoded.width, decoded.height)
        assertTrue(FriendProfile(key, "Me", photo = preview, photoHD = shared).valid())
    }

    @Test fun spotifyCodeReaderFindsNothingInNoise() {
        val image = BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB).apply { val r = java.util.Random(1); for (x in 0 until 400) for (y in 0 until 100) setRGB(x, y, r.nextInt()) }
        assertNull(SpotifyCodeImageReader.read(image))
    }

    @Test fun spotifyCodeReaderReadsDrawnBars() {
        // Bars for a valid code: draw heights that SpotifyCodeDecoder accepts, then read them back.
        val levels = findValidLevels()
        val image = BufferedImage(23 * 20 + 80, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height); g.color = Color.BLACK
        levels.forEachIndexed { i, level -> val h = 20 + level * 20; g.fillRect(40 + i * 20, 100 - h / 2, 10, h) }
        g.dispose()
        assertEquals(SpotifyCodeDecoder.decode(levels), SpotifyCodeImageReader.read(image))
    }

    /** Encodes a reference the way Spotify does (inverse of [SpotifyCodeDecoder]) to get bar heights. */
    private fun findValidLevels(reference: Long = 26_560_810_473L): List<Int> {
        var crc = 0
        for (byte in 0 until 5) { crc = crc xor ((reference shr (byte * 8)) and 255).toInt(); repeat(8) { crc = ((crc shl 1) xor (if (crc and 128 != 0) 7 else 0)) and 255 } }
        val crcBits = crc xor 255
        val bits = List(45) { i -> if (i < 37) ((reference shr i) and 1).toInt() else (crcBits shr (i - 37)) and 1 }
        val full = bits.takeLast(6) + bits
        val stream = (0 until 45).flatMap { i -> listOf(0b1011011, 0b1111001).map { mask -> (0 until 7).fold(0) { n, j -> n xor (full[i + j] * ((mask shr j) and 1)) } } }
        val short = stream.filterIndexed { i, _ -> i % 3 != 2 }
        val output = List(60) { short[(it * 7) % 60] }
        val heights = (0 until 20).map { b -> val g = (output[b * 3] shl 2) or (output[b * 3 + 1] shl 1) or output[b * 3 + 2]; g xor (g shr 1) xor (g shr 2) }
        val levels = listOf(0) + heights.subList(0, 10) + listOf(7) + heights.subList(10, 20) + listOf(0)
        check(SpotifyCodeDecoder.decode(levels) == reference) { "encoder mismatch" }
        return levels
    }
}

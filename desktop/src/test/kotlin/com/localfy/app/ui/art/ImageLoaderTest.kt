package com.localfy.app.ui.art

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImageLoaderTest {
    private fun jpeg(w: Int, h: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0xFF3366CC.toInt())
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
    }

    @Test fun decodesAndDownsamples() {
        val image = ImageLoader.decode(jpeg(1080, 720), 256)
        assertNotNull(image)
        assertEquals(256, image!!.width)
        assertEquals(170, image.height)
    }

    @Test fun keepsSmallImagesAtTheirSize() {
        val image = ImageLoader.decode(jpeg(100, 100), 512)
        assertNotNull(image)
        assertEquals(100, image!!.width)
    }

    @Test fun loadsBundledResource() {
        val image = kotlinx.coroutines.runBlocking { ImageLoader.load("res:icons/spitify_launcher.png", 128) }
        assertNotNull(image)
        assertEquals(128, image!!.width)
    }

    @Test fun paletteFindsTheColour() {
        val image = ImageLoader.decode(jpeg(96, 96), 96)!!
        assertNotNull(ArtPalette.from(image).dominant)
    }
}

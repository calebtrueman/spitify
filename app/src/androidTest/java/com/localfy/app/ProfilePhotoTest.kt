package com.localfy.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.saveSquareImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfilePhotoTest {
    /** Camera-style photo: big, stored landscape (left red, right blue), EXIF says rotate 90° → displays red on top. */
    @Test
    fun pickedPhotoIsUprightSquareAndSmall() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val photo = File(dir, "camera.jpg")
        Bitmap.createBitmap(6000, 4000, Bitmap.Config.RGB_565).apply {
            Canvas(this).apply {
                drawRect(0f, 0f, 3000f, 4000f, Paint().apply { color = Color.RED })
                drawRect(3000f, 0f, 6000f, 4000f, Paint().apply { color = Color.BLUE })
            }
            photo.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 80, it) }
            recycle()
        }
        ExifInterface(photo.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }

        val out = File(dir, "avatar.jpg")
        assertTrue(saveSquareImage(ImageDecoder.createSource(photo), out, 512))
        val bmp = BitmapFactory.decodeFile(out.path)
        assertEquals(512, bmp.width)
        assertEquals(512, bmp.height)
        val top = bmp.getPixel(256, 40); val bottom = bmp.getPixel(256, 472)
        assertTrue("top should be red, was ${Integer.toHexString(top)}", Color.red(top) > 200 && Color.blue(top) < 60)
        assertTrue("bottom should be blue, was ${Integer.toHexString(bottom)}", Color.blue(bottom) > 200 && Color.red(bottom) < 60)
    }
}

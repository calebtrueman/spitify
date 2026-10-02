package com.localfy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test fun launcherLoadsFullColourArtwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        // Keep a single image: the layered icon showed a white tile on the Fold8.
        assertTrue("Launcher icon must load the complete bitmap", icon is BitmapDrawable)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(Canvas(bitmap))
        var colouredPixels = 0
        var darkPixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.green(pixel) > Color.red(pixel) + 40 && Color.alpha(pixel) > 240) colouredPixels++
            if (Color.red(pixel) < 60 && Color.green(pixel) < 60 && Color.blue(pixel) < 60) darkPixels++
        }
        assertTrue("The green bars must remain visible", colouredPixels > 1500)
        assertTrue("The dark background must remain visible", darkPixels > 6000)
        bitmap.recycle()
    }
}

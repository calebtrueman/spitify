package com.localfy.app.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File

/**
 * Decodes a picked/downloaded image into a centred [size]×[size] square and saves it as JPEG.
 * Applies EXIF rotation (camera photos are stored sideways) and decodes at a reduced size, so a
 * 200 MP Fold photo costs a few MB instead of ~800 MB. Returns false if the image can't be read.
 */
fun saveSquareImage(source: ImageDecoder.Source, file: File, size: Int): Boolean = runCatching {
    val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSampleSize((minOf(info.size.width, info.size.height) / size).coerceAtLeast(1))
    }
    val side = minOf(bmp.width, bmp.height)
    val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    val out = if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
    val tmp = File(file.path + ".tmp")
    tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    tmp.renameTo(file)
}.getOrDefault(false)

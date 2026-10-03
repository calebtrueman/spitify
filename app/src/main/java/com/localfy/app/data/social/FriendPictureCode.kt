package com.localfy.app.data.social

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

object FriendPictureCode {
    fun make(value: String): Bitmap {
        require(SocialLink.parse(value)?.type == "person")
        val bits = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 4))
        return Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(600 * 600) { index -> if (bits[index % 600, index / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            setPixels(pixels, 0, 600, 0, 0, 600, 600)
        }
    }
    fun read(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2 }
        val image = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        try { decode(image) } finally { image.recycle() }
    }.getOrNull()
    fun decode(image: Bitmap): String? {
        val pixels = IntArray(image.width * image.height); image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text }.getOrNull()?.takeIf { SocialLink.parse(it)?.type == "person" }
    }
    fun share(context: Context, image: Bitmap) {
        val folder = File(context.cacheDir, "friend-codes").apply { mkdirs() }
        val file = File(folder, "Spitify-friend-code.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.codephotos", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share friend code"))
    }
}

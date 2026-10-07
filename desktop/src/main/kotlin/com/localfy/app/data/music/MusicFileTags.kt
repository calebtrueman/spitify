package com.localfy.app.data.music

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Writes title/artist/album/numbers and a square JPEG cover into a downloaded song (m4a, flac, mp3...). */
internal object MusicFileTags {
    init { Logger.getLogger("org.jaudiotagger").level = Level.OFF }

    fun tag(file: File, title: String, artist: String, album: String?, albumArtist: String?, track: Int?, disc: Int?, artwork: ByteArray?) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, title)
        tag.setField(FieldKey.ARTIST, artist)
        album?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM, it) }
        albumArtist?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
        track?.takeIf { it > 0 }?.let { tag.setField(FieldKey.TRACK, it.toString()) }
        disc?.takeIf { it > 0 }?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
        if (artwork != null) {
            tag.deleteArtworkField()
            val art = ArtworkFactory.getNew()
            art.binaryData = artwork
            art.mimeType = "image/jpeg"
            art.pictureType = 3 // front cover
            tag.setField(art)
        }
        audio.commit()
    }

    /** Centre-cropped square JPEG no larger than [size] px, or null when the image can't be read. */
    fun squareJpeg(data: ByteArray, size: Int = 1200): ByteArray? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(data)) ?: return null
        val side = minOf(src.width, src.height)
        if (side <= 0) return null
        val target = minOf(side, size)
        val out = BufferedImage(target, target, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, target, target)
            val x = (src.width - side) / 2; val y = (src.height - side) / 2
            g.drawImage(src, 0, 0, target, target, x, y, x + side, y + side, null)
        } finally { g.dispose() }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.9f }
                writer.write(null, IIOImage(out, null, null), param)
            }
        } finally { writer.dispose() }
        bytes.toByteArray()
    }.getOrNull()
}

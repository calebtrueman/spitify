package com.localfy.app.data.meta

import org.junit.Test
import org.junit.Assert.*
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.Base64

class FileTagsTest {
    @Test fun savesTagsAndArtInCommonFormatsAndKeepsOtherTags() {
        val image = Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdAB//2Q==")
        for (extension in listOf("flac", "mp3", "m4a")) {
            val file = File.createTempFile("tags-", ".$extension")
            try {
                javaClass.getResourceAsStream("/tags/sample.$extension")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                val duration = AudioFileIO.read(file).audioHeader.trackLength
                FileTags.tag(file, MetadataEdit(title = "Café", artist = "An artist", album = "An album", albumArtist = "Album artist", track = 4, disc = 2, year = 2026), image)
                FileTags.tag(file, MetadataEdit(title = "Changed"))
                val result = AudioFileIO.read(file)
                assertEquals("Changed", result.tag.getFirst(FieldKey.TITLE))
                assertTrue("Unrelated note must survive in $extension", file.readBytes().toString(Charsets.ISO_8859_1).contains("Keep this note"))
                assertEquals(duration, result.audioHeader.trackLength)
                assertTrue(result.tag.artworkList.any { it.binaryData.contentEquals(image) })
            } finally { file.delete() }
        }
    }
}

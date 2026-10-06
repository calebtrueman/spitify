package com.localfy.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.Song
import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.meta.MetadataEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileTagsTest {
    @Test fun writesOwnedMusicFileAndRestoresAnInterruptedSave() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val name = "spitify-tags-test-${UUID.randomUUID()}.flac"
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SpitifyTests/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val song = Song(0, "Test", "Artist", "Album", 0, "Artist", 100, 1, 1, 2026, null, "", 0, 0, "audio/flac", name, uri.toString())
        try {
            val original = instrumentation.context.assets.open("tags/sample.flac").use { it.readBytes() }
            resolver.openOutputStream(uri)!!.use { it.write(original) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            FileTags.write(context, song, MetadataEdit(title = "Saved to disk"))
            val saved = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertTrue(saved.toString(Charsets.UTF_8).contains("Saved to disk"))
            val dir = File(context.filesDir, "tag_backups").apply { mkdirs() }
            val backup = File(dir, UUID.randomUUID().toString() + ".flac").apply { writeBytes(saved) }
            val journal = File(dir, backup.name + ".properties")
            journal.outputStream().use { out -> Properties().apply { setProperty("uri", uri.toString()); setProperty("backup", backup.name) }.store(out, "Test interruption") }
            resolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(1, 2, 3)) }
            FileTags.recover(context)
            assertArrayEquals(saved, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertFalse(journal.exists())
            assertFalse(backup.exists())
        } finally { resolver.delete(uri, null, null) }
    }
}

package com.localfy.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.Song
import com.localfy.app.data.db.MetadataOverrideEntity
import com.localfy.app.data.meta.FileTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomaticMetadataTest {
    @Test fun backgroundPassEmbedsSavedMissingDetailsWithoutAnEditor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LocalfyApp
        val resolver = app.contentResolver
        val name = "auto-${UUID.randomUUID()}.flac"
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SpitifyTests/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val id = uri.lastPathSegment!!.toLong()
        val song = Song(id, "Test", "Artist", "Album", 0, "Artist", 100, 1, 1, 2026, null, "", 0, 0, "audio/flac", name, uri)
        try {
            instrumentation.context.assets.open("tags/sample.flac").use { input -> resolver.openOutputStream(uri)!!.use(input::copyTo) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val before = FileTags.read(app, song)
            app.database.metadata().put(MetadataOverrideEntity(songId = id, fileName = name,
                title = "Cached title", artist = "Cached artist", album = "Cached album", albumArtist = "Cached album artist",
                genre = "Cached genre", year = 2024, track = 3, disc = 2, source = "user", updatedAt = 1))
            app.metadata.setAutoFix(true)
            app.onlineArt.setEnabled(false)
            app.metadata.autoFixAll(listOf(song))
            withTimeout(15000) {
                while (FileTags.read(app, song).fields.genre != "Cached genre") delay(50)
            }
            val after = FileTags.read(app, song)
            assertEquals(before.fields.title ?: "Cached title", after.fields.title)
            assertEquals(before.fields.artist ?: "Cached artist", after.fields.artist)
            assertEquals("Cached genre", after.fields.genre)
            assertEquals(2024, after.fields.year)
        } finally {
            app.database.metadata().delete(id)
            resolver.delete(uri, null, null)
            app.onlineArt.setEnabled(true)
        }
    }
}

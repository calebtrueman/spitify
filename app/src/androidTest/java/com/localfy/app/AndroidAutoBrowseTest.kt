package com.localfy.app

import android.content.ComponentName
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Drives PlaybackService exactly like Android Auto does: through a MediaBrowser.
 * Logs the tree (tag "AutoTree") so the structure can be eyeballed in logcat.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoBrowseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // MediaBrowser calls must start on the main thread, but we wait for results on the test thread
    // (blocking the main thread would deadlock the connection).
    private fun browser(): MediaBrowser {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return onMain { MediaBrowser.Builder(context, token).buildAsync() }.get(20, TimeUnit.SECONDS)
    }

    private fun MediaBrowser.kids(id: String): List<MediaItem> =
        onMain { getChildren(id, 0, 500, null) }.get(20, TimeUnit.SECONDS).value.orEmpty()

    @Test
    fun browseTreeSearchAndPlayback() {
        // A car would only see the library once the user granted access on the phone.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_MEDIA_AUDIO)
        val app = context.applicationContext as LocalfyApp
        app.library.refresh()
        val deadline = System.currentTimeMillis() + 20_000
        while (app.library.library.value.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(250)
        val b = browser()
        try {
            val root = onMain { b.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS)
            assertTrue(root.params?.extras?.getBoolean("android.media.browse.SEARCH_SUPPORTED") == true)

            val tabs = b.kids(root.value!!.mediaId)
            Log.i("AutoTree", "tabs: " + tabs.joinToString { it.mediaMetadata.title.toString() })
            assertEquals(listOf("For you", "Library", "Podcasts", "Books"), tabs.map { it.mediaMetadata.title.toString() })

            for (tab in tabs) {
                val items = b.kids(tab.mediaId)
                Log.i("AutoTree", "${tab.mediaMetadata.title}: " + items.take(8).joinToString { "${it.mediaMetadata.title}${if (it.mediaMetadata.isBrowsable == true) "/" else ""}" })
            }

            val albums = b.kids("lib:albums")
            assertTrue("albums listed", albums.isNotEmpty())
            val album = albums.first { b.kids(it.mediaId).size >= 3 }
            val tracks = b.kids(album.mediaId)
            Log.i("AutoTree", "album ${album.mediaMetadata.title}: ${tracks.size} tracks, art=${tracks.first().mediaMetadata.artworkUri}")
            assertTrue(tracks.all { it.mediaMetadata.isPlayable == true })

            // Artwork must be readable by other apps through our provider.
            val art = tracks.first().mediaMetadata.artworkUri!!
            context.contentResolver.openInputStream(art).use { assertTrue("artwork bytes", (it?.readBytes()?.size ?: 0) > 1000) }

            // Tapping the 2nd track queues the whole album from there - like Spotify in the car.
            onMain { b.setMediaItem(tracks[1]); b.prepare(); b.play() }
            Thread.sleep(2500)
            onMain {
                Log.i("AutoTree", "queue after tap: ${b.mediaItemCount} items, index ${b.currentMediaItemIndex}, playing=${b.isPlaying}, title=${b.mediaMetadata.title}")
                assertEquals(tracks.size, b.mediaItemCount)
                assertEquals(1, b.currentMediaItemIndex)
            }

            // Search + voice-style "play <query>".
            onMain { b.search("daft punk", null) }.get(20, TimeUnit.SECONDS)
            val results = onMain { b.getSearchResult("daft punk", 0, 50, null) }.get(10, TimeUnit.SECONDS).value.orEmpty()
            Log.i("AutoTree", "search 'daft punk': " + results.joinToString { it.mediaMetadata.title.toString() })
            assertTrue(results.isNotEmpty())

            onMain {
                b.setMediaItem(MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("daft punk").build()).build())
                b.prepare(); b.play()
            }
            Thread.sleep(2000)
            onMain {
                Log.i("AutoTree", "voice 'play daft punk' -> ${b.mediaMetadata.artist} – ${b.mediaMetadata.title} (${b.mediaItemCount} queued)")
                assertEquals("Daft Punk", b.mediaMetadata.artist.toString().substringBefore(" &"))
            }

            // Spoken-word chapter: playing a book chapter should resume & queue the rest of the book.
            val books = b.kids("tab:books")
            books.firstOrNull()?.let { book ->
                val chapters = b.kids(book.mediaId)
                Log.i("AutoTree", "book ${book.mediaMetadata.title}: ${chapters.size} chapters, type=${chapters.firstOrNull()?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER}")
            }
        } finally {
            onMain { b.pause(); b.release() }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}

package com.localfy.app

import android.media.MediaPlayer
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveAudioTest {
    @Test fun savedBackupKeepsItsFileTypeAndAlbum() {
        val track = OnlineTrack("1", "Because", "The Beatles", "Love", "2", 164631, 1, 1, null, false,
            audioURL = "https://archive.org/download/love_20220324/Love.zip/Love%2FBecause.mp3", audioExtension = "mp3")
        val restored = requireNotNull(Monochrome.parseTrack(org.json.JSONObject(track.json())))
        assertEquals("mp3", restored.audioExtension)
        assertEquals(track.audioURL, restored.audioURL)
        assertEquals("Love", restored.album)
        assertTrue((SearchMatch.score("Beatles Love", "Love", "The Beatles") ?: 0) > (SearchMatch.score("Beatles Love", "Lisa Lauren Loves The Beatles", "Lisa Lauren") ?: 0))
    }
    @Test fun liveFallbackImportsAndPlaysAffectedTracks() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("archiveLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".monochrometest"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_MEDIA_AUDIO)
        val db = Room.inMemoryDatabaseBuilder(context, LocalfyDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var lookups = 0
        val store = MusicDownloads(context, db, scope, alternate = { track -> lookups++; ArchiveAudio.resolve(track) }, downloadURL = { track ->
            track.audioURL ?: "https://archive.org/download/love_20220324/spitify-no-such-file.flac"
        })
        store.setWifiOnly(false); store.start()
        val tracks = listOf(
            OnlineTrack("154038260820086784", "Carmen", "Lana Del Rey", "Born To Die (Bonus Track Version)", "154030733734711296", 248720, 9, 1, null, true),
            OnlineTrack("154038263663824896", "Million Dollar Man", "Lana Del Rey", "Born To Die (Bonus Track Version)", "154030733734711296", 230120, 10, 1, null, true),
            OnlineTrack("166384735708897280", "Because", "The Beatles", "Love", "166082371098722304", 164631, 1, 1, null, false)
        )
        val created = mutableListOf<Uri>()
        try {
            for (track in tracks) {
                val before = lookups
                store.enqueue(listOf(track))
                val complete = withTimeout(150_000) {
                    while (true) {
                        val job = db.musicDownloads().get(track.id)
                        if (job != null && !job.active) return@withTimeout job
                        delay(200)
                    }
                    error("unreachable")
                }
                assertEquals(complete.error, "complete", complete.state)
                assertEquals(before + 1, lookups)
                assertEquals(track.album, complete.track().album)
                assertTrue(ArchiveAudio.validURL(complete.track().audioURL.orEmpty()))
                val uri = Uri.parse(complete.localUri); created.add(uri)
                val reader = android.media.MediaMetadataRetriever()
                try {
                    reader.setDataSource(context, uri)
                    assertEquals(track.title, reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE))
                    assertEquals(track.album, reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM))
                } finally { reader.release() }
                val player = withContext(Dispatchers.Main) { requireNotNull(MediaPlayer.create(context, uri)).apply { setVolume(0f, 0f); start() } }
                try { delay(1000); withContext(Dispatchers.Main) { assertTrue(player.isPlaying); assertTrue(player.currentPosition > 0) } }
                finally { withContext(Dispatchers.Main) { player.release() } }
                android.util.Log.i("ArchiveAudioTest", "LIVE PASS: ${track.title}, failed first source, backup download, tags, offline playback")
            }
        } finally {
            tracks.forEach { store.cancel(it.id).join() }
            scope.cancel(); created.forEach { context.contentResolver.delete(it, null, null) }; db.close()
        }
    }
}

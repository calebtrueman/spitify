package com.localfy.app

import android.database.sqlite.SQLiteDatabase
import android.media.MediaPlayer
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.music.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonochromeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun migrationPreservesExistingLikesAndPlaylists() = runBlocking {
        val name = "monochrome-migration-test.db"
        context.deleteDatabase(name)
        val schema = instrumentation.context.assets.open("com.localfy.app.data.db.LocalfyDatabase/5.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            db.execSQL("INSERT INTO liked(songId, likedAt) VALUES(42, 123)")
            db.execSQL("INSERT INTO playlists(id, name, createdAt, updatedAt) VALUES(1, 'Keep me', 1, 1)")
            db.version = 5
        }
        val db = Room.databaseBuilder(context, LocalfyDatabase::class.java, name).addMigrations(LocalfyDatabase.MIGRATION_5_6).build()
        try {
            assertTrue(db.musicDownloads().all().isEmpty())
            db.openHelper.readableDatabase.query("SELECT name FROM playlists WHERE id = 1").use { assertTrue(it.moveToFirst()); assertEquals("Keep me", it.getString(0)) }
            db.openHelper.readableDatabase.query("SELECT songId FROM liked").use { assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun searchDownloadImportAndPlay() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("monochromeLive") == "true")
        // Refuse to touch the user's normal app or music collection.
        assumeTrue(context.packageName.endsWith(".monochrometest"))
        val app = context.applicationContext as LocalfyApp
        val found = Monochrome.search("Kevin MacLeod Carefree").first { it.id == "156361611655778304" }
        val track = Monochrome.album(found.releaseId).first { it.id == found.id }
        app.musicDownloads.setWifiOnly(false)
        app.musicDownloads.enqueue(listOf(track, track))
        var job: MusicDownloadEntity? = null
        withTimeout(120_000) {
            while (true) {
                job = app.database.musicDownloads().get(track.id)
                if (job?.active == false) break
                delay(250)
            }
        }
        val complete = requireNotNull(job)
        assertEquals(complete.error, "complete", complete.state)
        assertEquals(1, app.database.musicDownloads().all().count { it.id == track.id })
        val uri = Uri.parse(complete.localUri)
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_MEDIA_AUDIO)
            withContext(Dispatchers.Main) { app.library.refresh() }
            val song = withTimeout(20_000) {
                while (true) {
                    val song = app.library.library.value.songs.firstOrNull { it.fileName == "${track.id}.flac" }
                    if (song != null) return@withTimeout song
                    delay(200)
                }
                error("unreachable")
            }
            assertEquals("Carefree", song.title)
            assertEquals(track.album, song.album)
            val player = withContext(Dispatchers.Main) {
                requireNotNull(MediaPlayer.create(context, uri)).apply { setVolume(0f, 0f); start() }
            }
            try {
                delay(1500)
                withContext(Dispatchers.Main) { assertTrue(player.isPlaying); assertTrue(player.currentPosition > 0) }
            } finally { withContext(Dispatchers.Main) { player.release() } }
            android.util.Log.i("MonochromeTest", "LIVE PASS: search, download, import, selected album, duplicate prevention and offline playback; ${complete.quality}")
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}

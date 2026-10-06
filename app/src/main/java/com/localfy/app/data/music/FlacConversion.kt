package com.localfy.app.data.music

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.db.LocalfyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings › Convert FLAC to AAC: re-encodes every FLAC in the library as AAC 256 kbit/s (.m4a)
 * to save space, then removes the FLAC. Each new file stays hidden (MediaStore "pending") until
 * its original is gone, so refusing Android's delete prompt leaves the library exactly as it was.
 * Likes, playlists, play counts, history, lyrics, edits and resume points move to the new file.
 */
class FlacConversion(private val context: Context, private val db: LocalfyDatabase, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Converting(val done: Int, val total: Int, val current: String) : State
        /** Converted; Android must approve deleting these originals (files Spitify didn't create). */
        data class NeedsApproval(val originals: List<Uri>) : State
        data class Finished(val converted: Int, val skipped: Int, val savedBytes: Long) : State
    }

    private data class Converted(val oldId: Long, val oldUri: Uri, val newUri: Uri, val newName: String, val savedBytes: Long)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var waiting: List<Converted> = emptyList()
    private var convertedCount = 0
    private var skippedCount = 0
    private var savedSoFar = 0L

    private val app get() = context.applicationContext as LocalfyApp

    /** FLAC files in the library (not podcasts or books). */
    fun candidates(): List<Song> = app.library.rawSongs.value.filter { it.sourceUri == null && (it.mimeType == "audio/flac" || it.fileName.endsWith(".flac", ignoreCase = true)) }

    /** Rough AAC size: 256 kbit/s ≈ 32 kB per second. */
    fun estimatedSaving(songs: List<Song>): Long = songs.sumOf { (it.sizeBytes - it.durationMs * 32).coerceAtLeast(0) }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val playing = withContext(Dispatchers.Main) { app.player.playingSongId() }
            // The song that's playing keeps its file this time; it converts on the next run.
            val songs = candidates().filter { it.id != playing }
            android.util.Log.i("FlacConversion", "Playing $playing; converting ${songs.map { "${it.id}:${it.fileName}" }}")
            convertedCount = 0; skippedCount = 0; savedSoFar = 0
            val needApproval = mutableListOf<Converted>()
            songs.forEachIndexed { i, song ->
                if (!isActive) return@launch
                _state.value = State.Converting(i, songs.size, song.title)
                val converted = try { convert(song) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    android.util.Log.w("FlacConversion", "Skipped ${song.fileName}", e); skippedCount++; null
                } ?: return@forEachIndexed
                // Files Spitify created can be removed directly; others need the user's approval.
                val removed = runCatching { context.contentResolver.delete(converted.oldUri, null, null) > 0 }.getOrDefault(false)
                if (removed) finish(listOf(converted)) else needApproval += converted
            }
            if (needApproval.isEmpty()) done()
            else { waiting = needApproval; _state.value = State.NeedsApproval(needApproval.map { it.oldUri }) }
        }
    }

    fun cancel() {
        job?.cancel()
        scope.launch(Dispatchers.IO) { waiting.forEach { discard(it) }; waiting = emptyList(); done() }
    }

    /** Result of Android's delete prompt for [State.NeedsApproval]. */
    fun approvalResult(approved: Boolean) = scope.launch(Dispatchers.IO) {
        val batch = waiting; waiting = emptyList()
        if (approved) finish(batch) else { batch.forEach { discard(it) }; skippedCount += batch.size }
        done()
    }

    private fun done() {
        _state.value = State.Finished(convertedCount, skippedCount, savedSoFar)
        scope.launch(Dispatchers.Main) { app.library.refresh() }
    }

    private suspend fun convert(song: Song): Converted? {
        val temp = File.createTempFile("convert-", ".m4a", context.cacheDir)
        try {
            AacTranscoder.transcode({ it.setDataSource(context, song.uri, null) }, temp, cancelled = { !(job?.isActive ?: false) })
            val cover = runCatching { coverOf(song.uri) }.getOrNull()
            com.localfy.app.data.meta.FileTags.tag(temp, com.localfy.app.data.meta.MetadataEdit(
                // Untagged files show placeholders like "Unknown artist"; don't write those into the new file.
                title = song.title, artist = song.artist.takeUnless(::placeholder), album = song.album.takeUnless(::placeholder),
                albumArtist = song.albumArtist.takeUnless(::placeholder),
                genre = song.genre, year = song.year.takeIf { it > 0 }, track = song.track.takeIf { it > 0 }, disc = song.disc.takeIf { it > 0 }), cover)
            val name = song.fileName.substringBeforeLast('.') + ".m4a"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, song.folder.ifEmpty { "Music/" })
                put(MediaStore.Audio.Media.IS_PENDING, 1) // hidden until the FLAC is gone
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri, "wt").use { out -> checkNotNull(out); temp.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            return Converted(song.id, song.uri, uri, name, (song.sizeBytes - temp.length()).coerceAtLeast(0))
        } finally { temp.delete() }
    }

    private fun placeholder(value: String) = value.isBlank() || value.lowercase() in setOf("unknown artist", "unknown album", "<unknown>", "unknown")

    /** The embedded cover as a JPEG the tag writer accepts. */
    private fun coverOf(uri: Uri): ByteArray? {
        val retriever = android.media.MediaMetadataRetriever()
        val picture = try { retriever.setDataSource(context, uri); retriever.embeddedPicture } finally { retriever.release() } ?: return null
        val out = File.createTempFile("cover-", ".jpg", context.cacheDir)
        return try {
            if (com.localfy.app.data.saveSquareImage(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(picture)), out, 1200)) out.readBytes() else null
        } finally { out.delete() }
    }

    private fun discard(c: Converted) { runCatching { context.contentResolver.delete(c.newUri, null, null) } }

    /** Publishes the new files and moves everything that pointed at the old songs. */
    private suspend fun finish(batch: List<Converted>) {
        val resolver = context.contentResolver
        val moved = batch.mapNotNull { c ->
            val ok = runCatching { resolver.update(c.newUri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1 }.getOrDefault(false)
            if (ok) c else null
        }
        if (moved.isEmpty()) return
        val ids = moved.associate { it.oldId to ContentUris.parseId(it.newUri) }
        db.runInTransaction {
            val sql = db.openHelper.writableDatabase
            moved.forEach { c ->
                val old = c.oldId; val new = ids.getValue(old)
                sql.execSQL("UPDATE playlist_entries SET songId = ? WHERE songId = ?", arrayOf<Any>(new, old))
                sql.execSQL("UPDATE play_events SET songId = ? WHERE songId = ?", arrayOf<Any>(new, old))
                for (table in listOf("liked", "play_stats", "lyrics", "metadata_overrides")) {
                    sql.execSQL("UPDATE OR IGNORE $table SET songId = ? WHERE songId = ?", arrayOf<Any>(new, old))
                    sql.execSQL("DELETE FROM $table WHERE songId = ?", arrayOf<Any>(old))
                }
                sql.execSQL("UPDATE metadata_overrides SET fileName = ? WHERE songId = ? AND fileName IS NOT NULL", arrayOf<Any>(c.newName, new))
                sql.execSQL("UPDATE OR IGNORE resume SET mediaKey = ? WHERE mediaKey = ?", arrayOf<Any>(new.toString(), old.toString()))
                sql.execSQL("UPDATE music_downloads SET localUri = ? WHERE localUri = ?", arrayOf<Any>(c.newUri.toString(), c.oldUri.toString()))
            }
        }
        convertedCount += moved.size
        savedSoFar += moved.sumOf { it.savedBytes }
        withContext(Dispatchers.Main) {
            // In-memory copies: hidden songs and the live queue (which also saves itself).
            app.taste.remapSongs(ids)
            app.player.remapSongs(ids)
            app.library.refresh()
        }
    }
}

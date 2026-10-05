package com.localfy.app.data.music

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import com.localfy.app.LocalfyApp
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.db.MetadataOverrideEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** States where DownloadManager has (or is about to have) work in flight. */
private val BUSY = setOf("queued", "finding", "downloading", "checking")

private class InvalidDownloadedAudio(cause: Exception) : Exception(cause.message, cause)

/** DownloadManager owns transfers; Room owns the queue; a worker publishes checked music files. */
class MusicDownloads(private val context: Context, private val db: LocalfyDatabase, private val scope: CoroutineScope,
    private val alternate: suspend (OnlineTrack) -> OnlineTrack? = AudioFallback::resolve,
    private val downloadURL: (OnlineTrack) -> String = { track -> track.audioURL?.takeIf(AudioFallback::validAudioURL) ?: Monochrome.audioUrl(track.id) }) {
    private val dao = db.musicDownloads()
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val mutex = Mutex()
    private val lookups = mutableMapOf<String, String>()
    private var started = false
    private val prefs = context.getSharedPreferences("music_downloads", Context.MODE_PRIVATE)
    val jobs = dao.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    /** Jobs by track id, so every song row can look up its own state without scanning the list. */
    val jobsById = jobs.map { list -> list.associateBy { it.id } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifiOnly", true))
    val wifiOnly = _wifiOnly.asStateFlow()

    fun setWifiOnly(value: Boolean) { prefs.edit().putBoolean("wifiOnly", value).apply(); _wifiOnly.value = value }

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                for (job in dao.all().filter { it.state == "finding" }) {
                    dao.put(job.copy(state = "queued", downloadId = null, error = null))
                }
                if (!prefs.getBoolean("recoveredDownloads110", false)) {
                    for (job in dao.all().filter { it.state == "failed" && it.error == "Could not download this song. Please try again later." }) {
                        removePending(job.localUri)
                        dao.put(job.copy(state = "queued", downloadId = null, localUri = null, error = null,
                            trackJson = job.track().copy(fallbackTried = false, retryCount = 0, retryAtMillis = 0).json()))
                    }
                    prefs.edit().putBoolean("recoveredDownloads110", true).apply()
                }
            }
            while (isActive) {
                try { reconcile() } catch (e: Exception) { if (e is CancellationException) throw e; _message.value = e.message ?: "Could not update downloads." }
                val all = dao.all()
                // Poll DownloadManager only while something is moving. A job waiting to retry used to count
                // as active, so one failed song kept this loop (and its file checks) running every second.
                if (all.any { it.state in BUSY }) delay(1_000)
                else {
                    val nextRetry = all.filter { it.state == "waiting" }.minOfOrNull { it.track().retryAtMillis }
                    val wake: suspend () -> Unit = { dao.observe().first { queue -> queue.any { it.state in BUSY } } }
                    if (nextRetry != null) withTimeoutOrNull((nextRetry - System.currentTimeMillis()).coerceAtLeast(1_000)) { wake() }
                    else dao.observe().first { queue -> queue.any { it.active } }
                }
            }
        }
    }

    suspend fun enqueue(tracks: List<OnlineTrack>): String = withContext(Dispatchers.IO) {
        var added = 0; var saved = 0; var active = 0
        mutex.withLock {
            for (requested in tracks.filter { Monochrome.validId(it.id) || Regex("external-[0-9a-f]{64}").matches(it.id) && it.audioURL?.let(AudioFallback::validAudioURL) == true }) {
                val track = requested.copy(audioURL = if (requested.id.startsWith("external-")) requested.audioURL else null, audioExtension = if (requested.id.startsWith("external-")) requested.audioExtension else "flac", fallbackTried = false, attemptedSources = emptyList(), retryCount = 0, retryAtMillis = 0)
                val old = dao.get(track.id)
                if (old?.active == true) { active++; continue }
                if (old?.state == "complete" && exists(old.localUri)) { saved++; continue }
                lookups.remove(track.id)
                _progress.update { it - track.id }
                dao.put(MusicDownloadEntity(track.id, track.json(), wifiOnly = _wifiOnly.value)); added++
            }
        }
        schedule(context)
        when {
            added > 0 -> "$added ${if (added == 1) "song" else "songs"} queued." + if (_wifiOnly.value) " Downloads use Wi-Fi." else ""
            active > 0 -> "Already in your download queue."
            saved > 0 -> "Already saved in your library."
            else -> "This album did not return any songs. Please reload it and try again."
        }
    }

    fun cancel(id: String) = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            val job = dao.get(id)?.takeIf { it.active } ?: return@withLock
            lookups.remove(id)
            _progress.update { it - id }
            job.downloadId?.let { manager.remove(it) }
            WorkManager.getInstance(context).cancelUniqueWork("music-retry-$id")
            removePending(job.localUri)
            dao.put(job.copy(state = "cancelled", downloadId = null, localUri = null, error = null))
        }
        schedule(context)
    }

    private fun exists(uri: String?): Boolean = uri != null && runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun pending(uri: Uri): Boolean = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.IS_PENDING), null, null, null)?.use {
        it.moveToFirst() && it.getInt(0) == 1
    } ?: false

    private fun removePending(uri: String?) {
        if (uri != null) runCatching { val parsed = Uri.parse(uri); if (pending(parsed)) context.contentResolver.delete(parsed, null, null) }
    }

    private fun temp(id: String) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Monochrome/$id.flac")

    /** Finished files are checked for deletion at most once a minute (each check opens the file). */
    private var lastFileCheck = 0L

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val progress = mutableMapOf<String, Float>()
            val checkFiles = System.currentTimeMillis() - lastFileCheck > 60_000
            if (checkFiles) lastFileCheck = System.currentTimeMillis()
            for (job in dao.all()) {
                if (job.state == "complete" && checkFiles && !exists(job.localUri)) {
                    dao.put(job.copy(state = "failed", localUri = null, error = "The downloaded file was moved or deleted."))
                    continue
                }
                if (job.state == "waiting") {
                    if (job.track().retryAtMillis <= System.currentTimeMillis()) dao.put(job.copy(state = "queued", error = null))
                    continue
                }
                if (!job.active || job.downloadId == null) continue
                // If the process died after publishing, finish the saved job without another copy.
                if (job.localUri != null && exists(job.localUri) && !pending(Uri.parse(job.localUri))) {
                    dao.put(job.copy(state = "complete", error = null))
                    manager.remove(job.downloadId)
                    continue
                }
                manager.query(DownloadManager.Query().setFilterById(job.downloadId)).use { cursor ->
                    if (cursor == null || !cursor.moveToFirst()) {
                        removePending(job.localUri)
                        dao.put(job.copy(state = "failed", downloadId = null, localUri = null, error = "The transfer was removed. Tap Download to retry."))
                        continue
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            progress[job.id] = 1f
                            _progress.update { it + (job.id to 1f) }
                            try { publish(job, total) }
                            catch (e: Exception) {
                                if (e is CancellationException) throw e
                                removePending(dao.get(job.id)?.localUri)
                                if (e is InvalidDownloadedAudio) failedTransfer(job.copy(localUri = null), DownloadManager.ERROR_HTTP_DATA_ERROR)
                                else dao.put(job.copy(state = "failed", localUri = null, error = e.message ?: "The audio could not be added."))
                            }
                            manager.remove(job.downloadId)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            failedTransfer(job, reason)
                            manager.remove(job.downloadId)
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            if (reason == DownloadManager.PAUSED_WAITING_TO_RETRY) {
                                // Use the same short retries and source handoff as a finished failure.
                                failedTransfer(job, DownloadManager.ERROR_HTTP_DATA_ERROR)
                                manager.remove(job.downloadId)
                            } else DownloadProgress.measured(_progress.value[job.id], done, total)?.let { progress[job.id] = it }
                        }
                        else -> DownloadProgress.measured(_progress.value[job.id], done, total)?.let { progress[job.id] = it }
                    }
                }
            }
            val transferring = dao.all().filter { it.state == "downloading" || it.state == "checking" }.mapTo(mutableSetOf()) { it.id }
            _progress.value = progress.filterKeys { it in transferring }
            var slots = 2 - dao.all().count { it.state == "downloading" || it.state == "checking" }
            for (job in dao.all().filter { it.state == "queued" }) {
                if (slots <= 0) break
                try {
                    // Recover the narrow crash window between enqueue and saving DownloadManager's ID.
                    var existing: Long? = null
                    manager.query(DownloadManager.Query()).use { c ->
                        while (c != null && c.moveToNext()) {
                            if (c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)) == "Spitify music:${job.id}") {
                                existing = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)); break
                            }
                        }
                    }
                    val track = job.track()
                    val id = existing ?: run {
                        temp(job.id).delete()
                        manager.enqueue(DownloadManager.Request(Uri.parse(downloadURL(track)))
                            .setTitle(track.title).setDescription("Spitify music:${job.id}")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                            .setAllowedOverMetered(!job.wifiOnly).setAllowedOverRoaming(false)
                            .apply { if (job.wifiOnly) setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI) }
                            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Monochrome/${job.id}.flac"))
                    }
                    dao.put(job.copy(state = "downloading", downloadId = id, error = null))
                    slots--
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    dao.put(job.copy(state = "failed", error = e.message ?: "Could not start the download."))
                }
            }
        }
    }

    private suspend fun failedTransfer(job: MusicDownloadEntity, reason: Int) {
        _progress.update { it - job.id }
        val temporary = DownloadRetry.isTemporary(reason)
        val track = job.track()
        if (temporary && (track.retryCount < 3 || track.fallbackTried)) {
            waitToRetry(job, "Download response $reason")
        } else {
            if (reason !in listOf(DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_DEVICE_NOT_FOUND) && !track.fallbackTried) {
                val token = java.util.UUID.randomUUID().toString()
                lookups[job.id] = token
                dao.put(job.copy(state = "finding", downloadId = null, error = null))
                // A catalogue request must not hold up Cancel or other songs in the queue.
                scope.launch(Dispatchers.IO) {
                    val fallback = try { alternate(track) }
                        catch (e: Exception) { if (e is CancellationException) throw e; null }
                    mutex.withLock {
                        if (lookups[job.id] != token || dao.get(job.id)?.state != "finding") return@withLock
                        lookups.remove(job.id)
                        if (fallback != null) dao.put(job.copy(trackJson = fallback.copy(retryCount = 0, retryAtMillis = 0).json(), state = "queued", downloadId = null, error = null))
                        else if (temporary) waitToRetry(job.copy(trackJson = track.copy(fallbackTried = true).json()), "Download response $reason")
                        else dao.put(job.copy(state = "failed", downloadId = null, error = "This song is not available to download right now."))
                    }
                    schedule(context)
                }
            } else if (temporary) waitToRetry(job, "Download response $reason")
            else dao.put(job.copy(state = "failed", downloadId = null, error = if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "Free some storage, then retry this song." else "This song is not available to download right now."))
        }
    }

    private suspend fun waitToRetry(job: MusicDownloadEntity, reason: String) {
        android.util.Log.w("MusicDownloads", "Retrying ${job.id}: $reason")
        val track = job.track()
        val count = track.retryCount + 1
        val delay = DownloadRetry.delayMillis(count)
        dao.put(job.copy(state = "waiting", downloadId = null, error = "Waiting to retry automatically.",
            trackJson = track.copy(retryCount = count, retryAtMillis = System.currentTimeMillis() + delay).json()))
        scheduleRetry(context, job.id, delay)
    }

    private suspend fun publish(job: MusicDownloadEntity, expectedSize: Long) {
        val originalTrack = job.track()
        val file = temp(job.id)
        val container = try { file.inputStream().use { AudioContainer.detect(it.readNBytes(128)) } }
            catch (e: Exception) { throw InvalidDownloadedAudio(e) }
            ?: throw InvalidDownloadedAudio(IllegalArgumentException("The source did not return a supported audio file."))
        val track = originalTrack.copy(audioExtension = container.extension)
        val quality = try {
        check(expectedSize <= 0 || file.length() == expectedSize) { "The audio download is incomplete." }
        if (track.audioExtension == "flac") FlacInfo.read(file, track.durationMs).label else {
            val reader = android.media.MediaMetadataRetriever()
            try {
                reader.setDataSource(file.path)
                val length = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                check(length > 0 && reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" && kotlin.math.abs(length - track.durationMs) <= 5_000) { "The downloaded audio does not match this song." }
                container.label
            } finally { reader.release() }
        }
        } catch (e: Exception) {
            android.util.Log.w("MusicDownloads", "Audio check failed for ${job.id}", e)
            throw InvalidDownloadedAudio(e)
        }
        val tagged = File.createTempFile("tagged-", ".${track.audioExtension}", context.cacheDir)
        try {
            file.copyTo(tagged, overwrite = true)
            val artwork = track.artwork?.let { url ->
                val data = com.localfy.app.data.art.CoverDownload.load(url)
                val cover = File.createTempFile("cover-", ".jpg", context.cacheDir)
                try {
                    check(com.localfy.app.data.saveSquareImage(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data)), cover, 1200)) { "Could not read the cover image." }
                    cover.readBytes()
                } finally { cover.delete() }
            }
            // The tag writer does not support Opus or raw AAC. Preserve those audio bytes and
            // keep their title/artist/cover in the library instead of rejecting a good recording.
            if (container !in setOf(AudioContainer.OPUS, AudioContainer.AAC)) com.localfy.app.data.meta.FileTags.tag(tagged, com.localfy.app.data.meta.MetadataEdit(
                title = track.title, artist = track.artist, album = track.album.ifEmpty { null }, albumArtist = track.albumArtist ?: track.primaryArtist,
                track = track.track.takeIf { it > 0 }, disc = track.disc), artwork)
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${job.id}.${track.audioExtension}")
                put(MediaStore.Audio.Media.MIME_TYPE, container.mime)
                put(MediaStore.Audio.Media.TITLE, track.title)
                put(MediaStore.Audio.Media.ARTIST, track.artist)
                put(MediaStore.Audio.Media.ALBUM, track.album)
                put(MediaStore.Audio.Media.ALBUM_ARTIST, track.albumArtist ?: track.primaryArtist)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Spitify/Monochrome/${track.releaseId.takeIf(Monochrome::validId) ?: "Singles"}/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            val uri = job.localUri?.let(Uri::parse) ?: checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) { "Could not create the music file." }
            dao.put(job.copy(state = "checking", trackJson = track.json(), localUri = uri.toString(), quality = quality))
            resolver.openOutputStream(uri, "wt").use { output ->
                checkNotNull(output) { "Could not open the music file." }
                tagged.inputStream().use { it.copyTo(output) }
            }
            val songId = android.content.ContentUris.parseId(uri)
            if (db.metadata().get(songId)?.fileName != "${job.id}.${track.audioExtension}") {
                db.metadata().put(MetadataOverrideEntity(songId = songId, fileName = "${job.id}.${track.audioExtension}", title = track.title,
                    artist = track.artist, album = track.album.ifEmpty { null }, albumArtist = track.albumArtist ?: track.primaryArtist,
                    genre = null, year = null, track = track.track.takeIf { it > 0 }, disc = track.disc,
                    source = "online", updatedAt = System.currentTimeMillis()))
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1) { "Could not finish adding the song." }
            dao.put(job.copy(state = "complete", trackJson = track.json(), localUri = uri.toString(), quality = quality, error = null))
            withContext(Dispatchers.Main) { (context.applicationContext as LocalfyApp).library.refresh() }
        } finally { tagged.delete() }
    }

    companion object {
        private fun scheduleRetry(context: Context, id: String, delayMillis: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork("music-retry-$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MusicImportWorker>().setInitialDelay(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        }
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("music-imports", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MusicImportWorker>().build())
        }
    }
}

class MusicImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as LocalfyApp).musicDownloads.reconcile()
        Result.success()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

class MusicDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext as LocalfyApp
        app.appScope.launch(Dispatchers.IO) {
            try {
                // The system download provider is a different UID. Only accept IDs in our own queue.
                if (app.database.musicDownloads().byDownloadId(id) != null) MusicDownloads.schedule(context)
            } finally { pending.finish() }
        }
    }
}

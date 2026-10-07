package com.localfy.app.data.music

import com.localfy.app.data.art.CoverDownload
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** States where a transfer is (or is about to be) in flight. */
private val BUSY = setOf("queued", "finding", "downloading", "checking")

private class InvalidDownloadedAudio(cause: Exception) : Exception(cause.message, cause)

/**
 * Desktop port of the phone's MusicDownloads. The queue lives in a JSON file; transfers run in-process
 * (at most two at once) with resume and the phone's retry/fallback policy; finished FLAC is converted to
 * AAC 256 kbit/s .m4a, tagged, and saved under [downloadsDir]/<releaseId or Singles>/<trackId>.m4a.
 *
 * Nothing polls: the scheduler sleeps until a job is queued, a transfer ends, or a retry is due.
 * Job states: queued → downloading → checking → complete, with waiting (retry later), finding
 * (looking up another source), failed and cancelled.
 */
class MusicDownloads(
    private val scope: CoroutineScope,
    /** Called (off the UI thread) with each newly saved file so the library can rescan it. */
    private val onImported: (File) -> Unit = {},
    private val alternate: suspend (OnlineTrack) -> OnlineTrack? = AudioFallback::resolve,
    private val downloadURL: (OnlineTrack) -> String = { track -> track.audioURL?.takeIf(AudioFallback::validAudioURL) ?: Monochrome.audioUrl(track.id) },
    private val transport: DownloadTransport = HttpDownloadTransport(),
    private val downloadsDir: File = AppPaths.downloadsDir,
    private val workDir: File = File(AppPaths.cacheDir, "music-downloads"),
    storeFile: File = AppPaths.data("music_downloads.json"),
    private val retryDelay: (Int) -> Long = DownloadRetry::delayMillis,
    private val loadCover: (String) -> ByteArray = { CoverDownload.load(it) },
    /** Converts a FLAC file into an .m4a; replaceable for tests. */
    private val transcode: (File, File, () -> Boolean) -> Unit = { input, out, cancelled -> DesktopAac.transcode(input, out, cancelled) },
) {
    private val store = JsonStore(storeFile)
    private val mutex = Mutex()
    private val table = LinkedHashMap<String, MusicDownloadEntity>()
    private val lookups = mutableMapOf<String, String>()
    /** Running transfer per track id; a transfer only writes state while it is still the registered one. */
    private val transfers = HashMap<String, Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private var lastFileCheck = 0L

    private val _jobs = MutableStateFlow<List<MusicDownloadEntity>>(emptyList())
    val jobs: StateFlow<List<MusicDownloadEntity>> = _jobs.asStateFlow()
    /** Jobs by track id, so every song row can look up its own state without scanning the list. */
    val jobsById: StateFlow<Map<String, MusicDownloadEntity>> = jobs.map { list -> list.associateBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _wifiOnly: MutableStateFlow<Boolean>
    /** Kept for parity with the phones; desktop can't tell Wi-Fi from other networks, so it isn't enforced. */
    val wifiOnly: StateFlow<Boolean>

    init {
        val saved = store.readObject() ?: JSONObject()
        _wifiOnly = MutableStateFlow(saved.optBoolean("wifiOnly", true))
        wifiOnly = _wifiOnly.asStateFlow()
        val rows = saved.optJSONArray("jobs") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val job = rows.optJSONObject(i)?.let(MusicDownloadEntity::fromJson) ?: continue
            if (runCatching { job.track() }.isSuccess) table[job.id] = job
        }
        publishJobs()
    }

    fun setWifiOnly(value: Boolean) { _wifiOnly.value = value; persist() }

    fun start() {
        synchronized(this) { if (started) return; started = true }
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                // A previous run ended mid-transfer: queue it again (partial bytes are kept and resumed).
                for (job in table.values.toList()) {
                    if (job.state in setOf("finding", "downloading", "checking")) put(job.copy(state = "queued", downloadId = null, error = null))
                }
            }
            while (isActive) {
                try { reconcile() } catch (e: Exception) { if (e is CancellationException) throw e; _message.value = e.message ?: "Could not update downloads." }
                // Sleep until something changes; wake early for the next retry. No polling while idle.
                val nextRetry = mutex.withLock { table.values.filter { it.state == "waiting" }.minOfOrNull { it.track().retryAtMillis } }
                if (nextRetry != null) withTimeoutOrNull((nextRetry - System.currentTimeMillis()).coerceAtLeast(20)) { wake.receive() }
                else wake.receive()
            }
        }
    }

    private fun schedule() { start(); wake.trySend(Unit) }

    suspend fun enqueue(tracks: List<OnlineTrack>): String = withContext(Dispatchers.IO) {
        var added = 0; var saved = 0; var active = 0
        mutex.withLock {
            for (requested in tracks.filter { Monochrome.validId(it.id) || Regex("external-[0-9a-f]{64}").matches(it.id) && it.audioURL?.let(AudioFallback::validAudioURL) == true }) {
                val external = requested.id.startsWith("external-")
                val track = requested.copy(audioURL = if (external) requested.audioURL else null, audioExtension = if (external) requested.audioExtension else "flac",
                    fallbackTried = false, attemptedSources = emptyList(), retryCount = 0, retryAtMillis = 0)
                val old = table[track.id]
                if (old?.active == true) { active++; continue }
                if (old?.state == "complete" && exists(old.localUri)) { saved++; continue }
                lookups.remove(track.id)
                _progress.update { it - track.id }
                removeParts(track.id)
                put(MusicDownloadEntity(track.id, track.json(), wifiOnly = _wifiOnly.value)); added++
            }
        }
        schedule()
        when {
            added > 0 -> "$added ${if (added == 1) "song" else "songs"} queued."
            active > 0 -> "Already in your download queue."
            saved > 0 -> "Already saved in your library."
            else -> "This album did not return any songs. Please reload it and try again."
        }
    }

    fun cancel(id: String) = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            val job = table[id]?.takeIf { it.active } ?: return@withLock
            lookups.remove(id)
            _progress.update { it - id }
            transfers.remove(id)?.cancel()
            removeParts(id)
            put(job.copy(state = "cancelled", downloadId = null, localUri = null, error = null))
        }
        schedule()
    }

    private fun exists(path: String?): Boolean = path != null && File(path).isFile

    /** Moves due retries back to the queue, notices deleted files, and starts transfers into free slots. */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val checkFiles = now - lastFileCheck > 60_000
            if (checkFiles) lastFileCheck = now
            for (job in table.values.toList()) {
                if (job.state == "complete" && checkFiles && !exists(job.localUri)) {
                    put(job.copy(state = "failed", localUri = null, error = "The downloaded file was moved or deleted."))
                } else if (job.state == "waiting" && job.track().retryAtMillis <= now) {
                    put(job.copy(state = "queued", error = null))
                } else if (job.state in setOf("downloading", "checking") && transfers[job.id]?.isActive != true) {
                    // Never leave a job stuck: a transfer that vanished goes back to the queue.
                    put(job.copy(state = "queued", error = null))
                }
            }
            val transferring = table.values.filter { it.state == "downloading" || it.state == "checking" }.mapTo(mutableSetOf()) { it.id }
            _progress.update { p -> p.filterKeys { it in transferring } }
            var slots = 2 - transferring.size
            for (job in table.values.filter { it.state == "queued" }) {
                if (slots <= 0) break
                val url = try { downloadURL(job.track()) } catch (e: Exception) {
                    put(job.copy(state = "failed", error = e.message ?: "Could not start the download.")); continue
                }
                put(job.copy(state = "downloading", error = null))
                val transfer = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { transfer(job.id, url) }
                transfers[job.id] = transfer
                transfer.start()
                slots--
            }
        }
    }

    private fun partFile(id: String, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        return File(workDir, "$id-$hash.part")
    }

    private fun removeParts(id: String) { workDir.listFiles { f -> f.name.startsWith("$id-") || f.name.startsWith("$id.") }?.forEach { it.delete() } }

    /** True while [id]'s registered transfer is the calling coroutine (it wasn't cancelled or replaced). */
    private fun current(id: String, me: Job?): Boolean = me != null && transfers[id] === me

    private suspend fun transfer(id: String, url: String) {
        val me = currentCoroutineContext()[Job]
        val part = partFile(id, url)
        try {
            workDir.mkdirs()
            // Partial bytes from an older source never mix with this one.
            workDir.listFiles { f -> f.name.startsWith("$id-") && f.name != part.name }?.forEach { it.delete() }
            val expected = transport.fetch(url, part) { received, total ->
                DownloadProgress.measured(_progress.value[id], received, total)?.let { f -> _progress.update { it + (id to f) } }
            }
            val job = mutex.withLock {
                val job = table[id]?.takeIf { current(id, me) } ?: return
                _progress.update { it + (id to 1f) }
                job.copy(state = "checking").also(::put)
            }
            publish(job, part, expected, me)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransferFailed) {
            mutex.withLock { table[id]?.takeIf { current(id, me) }?.let { failedTransfer(it, e.reason) } }
        } catch (e: InvalidDownloadedAudio) {
            part.delete()
            mutex.withLock { table[id]?.takeIf { current(id, me) }?.let { failedTransfer(it.copy(localUri = null), DownloadErrors.HTTP_DATA_ERROR) } }
        } catch (e: Exception) {
            part.delete()
            mutex.withLock { table[id]?.takeIf { current(id, me) }?.let { put(it.copy(state = "failed", localUri = null, error = e.message ?: "The audio could not be added.")) } }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { if (transfers[id] === me) transfers.remove(id) }
                _progress.update { it - id }
            }
            wake.trySend(Unit)
        }
    }

    /** Must hold [mutex]. Same policy as the phone: short retries, then one search for another source. */
    private fun failedTransfer(job: MusicDownloadEntity, reason: Int) {
        _progress.update { it - job.id }
        val temporary = DownloadRetry.isTemporary(reason)
        val track = job.track()
        if (temporary && (track.retryCount < 3 || track.fallbackTried)) {
            waitToRetry(job, "Download response $reason")
        } else if (reason !in listOf(DownloadErrors.FILE_ERROR, DownloadErrors.INSUFFICIENT_SPACE, DownloadErrors.DEVICE_NOT_FOUND) && !track.fallbackTried) {
            val token = java.util.UUID.randomUUID().toString()
            lookups[job.id] = token
            put(job.copy(state = "finding", downloadId = null, error = null))
            // A catalogue request must not hold up Cancel or other songs in the queue.
            scope.launch(Dispatchers.IO) {
                val fallback = try { alternate(track) } catch (e: Exception) { if (e is CancellationException) throw e; null }
                mutex.withLock {
                    if (lookups[job.id] != token || table[job.id]?.state != "finding") return@withLock
                    lookups.remove(job.id)
                    when {
                        fallback != null -> { removeParts(job.id); put(job.copy(trackJson = fallback.copy(retryCount = 0, retryAtMillis = 0).json(), state = "queued", downloadId = null, error = null)) }
                        temporary -> waitToRetry(job.copy(trackJson = track.copy(fallbackTried = true).json()), "Download response $reason")
                        else -> put(job.copy(state = "failed", downloadId = null, error = "This song is not available to download right now."))
                    }
                }
                wake.trySend(Unit)
            }
        } else if (temporary) waitToRetry(job, "Download response $reason")
        else put(job.copy(state = "failed", downloadId = null, error = if (reason == DownloadErrors.INSUFFICIENT_SPACE) "Free some storage, then retry this song." else "This song is not available to download right now."))
    }

    private fun waitToRetry(job: MusicDownloadEntity, reason: String) {
        val track = job.track()
        val count = track.retryCount + 1
        // The phone retries forever every five minutes; desktop gives up after a bounded number of tries.
        if (count > MAX_RETRIES) {
            removeParts(job.id)
            put(job.copy(state = "failed", downloadId = null, error = "Could not download this song. Please try again later."))
            return
        }
        System.err.println("MusicDownloads: retrying ${job.id}: $reason")
        put(job.copy(state = "waiting", downloadId = null, error = "Waiting to retry automatically.",
            trackJson = track.copy(retryCount = count, retryAtMillis = System.currentTimeMillis() + retryDelay(count)).json()))
    }

    private suspend fun publish(job: MusicDownloadEntity, file: File, expectedSize: Long, me: Job?) {
        val originalTrack = job.track()
        val sourceContainer = try { file.inputStream().use { AudioContainer.detect(it.readNBytes(128)) } }
            catch (e: Exception) { throw InvalidDownloadedAudio(e) }
            ?: throw InvalidDownloadedAudio(IllegalArgumentException("The source did not return a supported audio file."))
        val track = originalTrack.copy(audioExtension = sourceContainer.extension)
        val sourceQuality = try {
            check(expectedSize <= 0 || file.length() == expectedSize) { "The audio download is incomplete." }
            if (sourceContainer == AudioContainer.FLAC) FlacInfo.read(file, track.durationMs).label else {
                val info = DesktopAac.probe(file)
                check(info != null && info.durationMs > 0 && (track.durationMs <= 0 || kotlin.math.abs(info.durationMs - track.durationMs) <= 5_000)) { "The downloaded audio does not match this song." }
                sourceContainer.label
            }
        } catch (e: Exception) { throw InvalidDownloadedAudio(e) }
        // Lossless downloads are saved as AAC 256 kbit/s .m4a to save space. Anything that can't be
        // converted (e.g. surround) keeps its original format.
        val aac = if (sourceContainer == AudioContainer.FLAC) try {
            File(workDir, "${job.id}.aac.m4a").also { out -> transcode(file, out) { me?.isActive == false } }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            System.err.println("MusicDownloads: kept FLAC for ${job.id}: ${e.message}"); null
        } else null
        currentCoroutineContext().ensureActive()
        try {
            publishAs(job, if (aac != null) track.copy(audioExtension = "m4a") else track, if (aac != null) AudioContainer.M4A else sourceContainer,
                if (aac != null) DesktopAac.LABEL else sourceQuality, aac ?: file, me)
        } finally { aac?.delete(); file.delete() }
    }

    private suspend fun publishAs(job: MusicDownloadEntity, track: OnlineTrack, container: AudioContainer, quality: String, audio: File, me: Job?) {
        val tagged = File(workDir, "${job.id}.tagging.${track.audioExtension}")
        try {
            audio.copyTo(tagged, overwrite = true)
            // A missing cover never costs the song (the phone fails the job here).
            val artwork = track.artwork?.let { url -> runCatching { MusicFileTags.squareJpeg(loadCover(url), 1200) }.getOrNull() }
            // The tag writer does not support Opus or raw AAC: keep those bytes untagged rather than reject them.
            if (container !in setOf(AudioContainer.OPUS, AudioContainer.AAC)) MusicFileTags.tag(tagged, track.title, track.artist, track.album.ifEmpty { null },
                track.albumArtist ?: track.primaryArtist, track.track.takeIf { it > 0 }, track.disc, artwork)
            val folder = File(downloadsDir, track.releaseId.takeIf(Monochrome::validId) ?: "Singles").also { it.mkdirs() }
            val dest = File(folder, "${job.id}.${track.audioExtension}")
            currentCoroutineContext().ensureActive()
            val landed = mutex.withLock {
                if (!current(job.id, me) || table[job.id]?.state != "checking") return@withLock false
                val tmp = File(folder, ".${dest.name}.tmp")
                tagged.copyTo(tmp, overwrite = true)
                try { Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: Exception) { Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                // Another format of the same song from an earlier download would show up twice.
                folder.listFiles { f -> f.nameWithoutExtension == job.id && f != dest }?.forEach { it.delete() }
                put(job.copy(state = "complete", trackJson = track.json(), localUri = dest.absolutePath, quality = quality, error = null))
                true
            }
            if (landed) try { onImported(dest) } catch (e: Exception) { System.err.println("MusicDownloads: library refresh failed: ${e.message}") }
        } finally { tagged.delete() }
    }

    /** Must hold [mutex] (or be in init). */
    private fun put(job: MusicDownloadEntity) {
        table[job.id] = job
        publishJobs()
        persist()
    }

    private fun publishJobs() { _jobs.value = table.values.sortedBy { it.createdAt } }

    private fun persist() {
        store.save(300) {
            val snapshot = _jobs.value
            JSONObject().put("wifiOnly", _wifiOnly.value).put("jobs", JSONArray(snapshot.map { it.toJson() })).toString()
        }
    }

    /** Writes pending queue changes now (call on shutdown). */
    fun flush() = store.flush()

    companion object {
        const val MAX_RETRIES = 8
    }
}

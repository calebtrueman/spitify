package com.localfy.app.data.podcast

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.edit
import com.localfy.app.data.Song
import com.localfy.app.data.db.EpisodeEntity
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.db.PodcastEntity
import com.localfy.app.data.db.ResumeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Show(val podcast: PodcastEntity, val episodes: List<EpisodeEntity>) {
    val id: Long get() = podcast.id
}

class PodcastRepository(
    private val context: Context,
    private val db: LocalfyDatabase,
    private val scope: CoroutineScope,
) {
    private val dao = db.podcasts()
    private val prefs = context.getSharedPreferences("podcasts", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)

    val shows: StateFlow<List<Show>> = combine(dao.observePodcasts(), dao.observeEpisodes()) { pods, eps ->
        val byShow = eps.groupBy { it.podcastId }
        pods.map { Show(it, byShow[it.id].orEmpty()) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Resume positions keyed by media key ("ep:<id>" for episodes, MediaStore id for local files). */
    val resume: StateFlow<Map<String, ResumeEntity>> = dao.observeResume()
        .map { list -> list.associateBy { it.mediaKey } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Every episode as a playable [Song] (negative ids), so the queue/player treat them uniformly. */
    val episodeSongs: StateFlow<Map<Long, Song>> = shows.map { list ->
        list.flatMap { show -> show.episodes.map { it.toSong(show.podcast) } }.associateBy { it.id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<Long, Float>> = _downloadProgress.asStateFlow()

    private var watcher: Job? = null

    private var started = false

    fun start() {
        if (started) return
        started = true
        val last = prefs.getLong("lastRefresh", 0)
        if (System.currentTimeMillis() - last > 3 * 60 * 60 * 1000L) refreshAll()
        watchDownloads()
    }

    suspend fun search(term: String): List<PodcastSearchResult> = withContext(Dispatchers.IO) { runCatching { PodcastDirectory.search(term) }.getOrDefault(emptyList()) }

    /** Subscribe by feed URL; returns the podcast id or null if the feed couldn't be read. */
    suspend fun searchBooks(term: String): List<BookSearchResult> = withContext(Dispatchers.IO) { runCatching { LibriVox.search(term) }.getOrDefault(emptyList()) }

    suspend fun subscribe(feedUrl: String, artworkHint: String? = null, kind: String = KIND_PODCAST, titleHint: String? = null, authorHint: String? = null, descriptionHint: String? = null): Long? = withContext(Dispatchers.IO) {
        dao.byFeed(feedUrl)?.let { return@withContext it.id }
        val feed = runCatching {
            if (feedUrl.startsWith("archive:")) LibriVox.chapters(feedUrl.removePrefix("archive:"))
            else Http.open(feedUrl) { FeedParser.parse(it) }
        }.getOrNull() ?: return@withContext null
        val now = System.currentTimeMillis()
        val id = dao.insertPodcast(
            PodcastEntity(feedUrl = feedUrl, title = titleHint ?: feed.title, author = authorHint ?: feed.author,
                description = descriptionHint?.takeIf { it.isNotBlank() } ?: feed.description,
                artworkUrl = artworkHint ?: feed.artworkUrl, lastRefreshed = now, subscribedAt = now, kind = kind),
        )
        dao.insertEpisodes(feed.episodes.map { it.toEntity(id) })
        id
    }

    fun unsubscribe(id: Long) = scope.launch(Dispatchers.IO) {
        dao.episodesOf(id).forEach { ep -> ep.localPath?.let { File(it).delete() }; ep.downloadId?.let { runCatching { downloads.remove(it) } } }
        dao.deleteEpisodes(id)
        dao.deletePodcast(id)
    }

    fun refreshAll() = scope.launch(Dispatchers.IO) {
        _refreshing.value = true
        try {
            dao.podcasts().filter { !it.feedUrl.startsWith("archive:") }.forEach { pod ->
                val feed = runCatching { Http.open(pod.feedUrl) { FeedParser.parse(it) } }.getOrNull() ?: return@forEach
                dao.insertEpisodes(feed.episodes.map { it.toEntity(pod.id) }) // IGNORE keeps progress/downloads
                // Books keep the nicer LibriVox title/cover; podcasts follow their feed.
                dao.updatePodcast(if (pod.kind == KIND_AUDIOBOOK) pod.copy(lastRefreshed = System.currentTimeMillis()) else pod.copy(
                    title = feed.title, author = feed.author.ifEmpty { pod.author }, description = feed.description.ifEmpty { pod.description },
                    artworkUrl = feed.artworkUrl ?: pod.artworkUrl, lastRefreshed = System.currentTimeMillis(),
                ))
            }
            prefs.edit { putLong("lastRefresh", System.currentTimeMillis()) }
        } finally {
            _refreshing.value = false
        }
    }

    // ---- Downloads (DownloadManager -> app-specific storage, not scanned into the music library) ----

    fun download(episodeId: Long) = scope.launch(Dispatchers.IO) {
        val ep = dao.episode(episodeId) ?: return@launch
        if (ep.localPath != null) return@launch
        val ext = ep.audioUrl.substringBefore('?').substringAfterLast('.', "mp3").take(4).lowercase()
        val request = DownloadManager.Request(Uri.parse(ep.audioUrl))
            .setTitle(ep.title)
            .setDescription("Spitify podcast download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, "ep_${ep.id}.$ext")
        val id = downloads.enqueue(request)
        dao.setDownload(ep.id, id, null)
        _downloadProgress.value = _downloadProgress.value + (ep.id to 0f)
        watchDownloads()
    }

    fun downloadAll(ids: List<Long>) = ids.forEach { download(it) }

    fun deleteDownload(episodeId: Long) = scope.launch(Dispatchers.IO) {
        val ep = dao.episode(episodeId) ?: return@launch
        ep.localPath?.let { File(it).delete() }
        ep.downloadId?.let { runCatching { downloads.remove(it) } }
        dao.setDownload(ep.id, null, null)
        _downloadProgress.value = _downloadProgress.value - ep.id
    }

    private fun watchDownloads() {
        if (watcher?.isActive == true) return
        watcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = dao.pendingDownloads()
                if (pending.isEmpty()) { _downloadProgress.value = emptyMap(); break }
                val progress = HashMap<Long, Float>()
                pending.forEach { ep ->
                    val q = DownloadManager.Query().setFilterById(ep.downloadId!!)
                    downloads.query(q)?.use { c ->
                        if (!c.moveToFirst()) { dao.setDownload(ep.id, null, null); return@use }
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val path = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let { Uri.parse(it).path }
                                dao.setDownload(ep.id, ep.downloadId, path)
                            }
                            DownloadManager.STATUS_FAILED -> dao.setDownload(ep.id, null, null)
                            else -> progress[ep.id] = if (total > 0) done / total.toFloat() else 0f
                        }
                    }
                }
                _downloadProgress.value = progress
                delay(1_000)
            }
        }
    }

    // ---- Resume / played ----

    fun saveProgress(key: String, positionMs: Long, durationMs: Long) = scope.launch(Dispatchers.IO) {
        val played = durationMs > 0 && positionMs >= durationMs - 30_000
        dao.putResume(ResumeEntity(key, if (played) 0 else positionMs, durationMs, played, System.currentTimeMillis()))
    }

    fun setPlayed(key: String, played: Boolean, durationMs: Long) = scope.launch(Dispatchers.IO) {
        dao.putResume(ResumeEntity(key, 0, durationMs, played, System.currentTimeMillis()))
    }

    suspend fun resumePosition(key: String): Long = withContext(Dispatchers.IO) {
        dao.resume(key)?.takeIf { !it.played }?.positionMs ?: 0L
    }
}

const val KIND_PODCAST = "podcast"
const val KIND_AUDIOBOOK = "audiobook"

/** Resume key: episodes are "ep:<id>", local files their MediaStore id. */
val Song.resumeKey: String get() = episodeId?.let { "ep:$it" } ?: id.toString()

private fun ParsedEpisode.toEntity(podcastId: Long) = EpisodeEntity(
    podcastId = podcastId, guid = guid, title = title, description = description, audioUrl = audioUrl,
    mimeType = mimeType, pubDate = pubDate, durationMs = durationMs, artworkUrl = artworkUrl,
    downloadId = null, localPath = null, position = position,
)

fun EpisodeEntity.toSong(podcast: PodcastEntity): Song = Song(
    id = -id,
    title = title,
    artist = podcast.title,
    album = podcast.title,
    albumId = -podcast.id - 1_000_000,
    albumArtist = podcast.author,
    durationMs = durationMs,
    disc = 1,
    year = 0,
    genre = "Podcast",
    folder = "",
    dateAddedSec = pubDate / 1000,
    sizeBytes = 0,
    mimeType = mimeType,
    fileName = audioUrl.substringBefore('?').substringAfterLast('/'),
    sourceUri = localPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse(audioUrl),
    artUrl = artworkUrl ?: podcast.artworkUrl,
    isPodcast = true,
    isAudiobook = podcast.kind == KIND_AUDIOBOOK,
    track = position + 1,
    episodeId = id,
)

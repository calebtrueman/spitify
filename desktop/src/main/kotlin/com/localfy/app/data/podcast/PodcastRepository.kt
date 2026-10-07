package com.localfy.app.data.podcast

import com.localfy.app.data.Song
import com.localfy.app.data.db.EpisodeEntity
import com.localfy.app.data.db.PodcastEntity
import com.localfy.app.data.db.ResumeEntity
import com.localfy.app.data.music.DownloadRetry
import com.localfy.app.data.music.DownloadTransport
import com.localfy.app.data.music.HttpDownloadTransport
import com.localfy.app.data.music.TransferFailed
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

data class Show(val podcast: PodcastEntity, val episodes: List<EpisodeEntity>) {
    val id: Long get() = podcast.id
}

/**
 * Podcasts and LibriVox audiobooks (desktop port). Shows and episodes live in `podcasts.json`, resume
 * positions in `podcast-resume.json`; both are written atomically and coalesced. Episode downloads are
 * plain in-process HTTP transfers into [downloadsDir] (not part of the music library).
 */
class PodcastRepository(
    private val scope: CoroutineScope,
    dataDir: File = AppPaths.dataDir,
    private val downloadsDir: File = AppPaths.data("podcasts/"),
    private val transport: DownloadTransport = HttpDownloadTransport(upgradeHttp = true),
    /** Reads a feed URL ("archive:<id>" for LibriVox books); null when it can't be read. Replaceable for tests. */
    private val readFeed: (String) -> ParsedFeed? = { url ->
        if (url.startsWith("archive:")) LibriVox.chapters(url.removePrefix("archive:")) else Http.open(url) { FeedParser.parse(it) }
    },
) {
    private val store = JsonStore(File(dataDir, "podcasts.json"))
    private val resumeStore = JsonStore(File(dataDir, "podcast-resume.json"))
    private val lock = Any()
    private val podcasts = LinkedHashMap<Long, PodcastEntity>()
    private val episodes = LinkedHashMap<Long, EpisodeEntity>()
    private val episodeKeys = HashMap<Pair<Long, String>, Long>()
    private val resumes = HashMap<String, ResumeEntity>()
    private var nextPodcastId = 1L
    private var nextEpisodeId = 1L
    private var lastRefresh = 0L
    private val downloadJobs = HashMap<Long, Job>()

    private val _shows = MutableStateFlow<List<Show>>(emptyList())
    val shows: StateFlow<List<Show>> = _shows.asStateFlow()

    private val _resume = MutableStateFlow<Map<String, ResumeEntity>>(emptyMap())
    /** Resume positions keyed by media key ("ep:<id>" for episodes, the song id for local files). */
    val resume: StateFlow<Map<String, ResumeEntity>> = _resume.asStateFlow()

    /** Every episode as a playable [Song] (negative ids), so the queue/player treat them uniformly. */
    val episodeSongs: StateFlow<Map<Long, Song>> = shows.map { list ->
        list.flatMap { show -> show.episodes.map { it.toSong(show.podcast) } }.associateBy { it.id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<Long, Float>> = _downloadProgress.asStateFlow()

    private var started = false

    init {
        runCatching {
            val o = store.readObject() ?: JSONObject()
            lastRefresh = o.optLong("lastRefresh", 0)
            o.optJSONArray("podcasts")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::podcastFrom)?.let { podcasts[it.id] = it } }
            o.optJSONArray("episodes")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let(::episodeFrom)?.takeIf { it.podcastId in podcasts }?.let {
                    if (episodeKeys.putIfAbsent(it.podcastId to it.guid, it.id) == null) episodes[it.id] = it
                }
            }
            nextPodcastId = maxOf(o.optLong("nextPodcastId", 1), (podcasts.keys.maxOrNull() ?: 0) + 1)
            nextEpisodeId = maxOf(o.optLong("nextEpisodeId", 1), (episodes.keys.maxOrNull() ?: 0) + 1)
        }
        runCatching {
            val o = resumeStore.readObject() ?: JSONObject()
            o.keys().forEach { key ->
                val r = o.optJSONObject(key) ?: return@forEach
                resumes[key] = ResumeEntity(key, r.optLong("positionMs"), r.optLong("durationMs"), r.optBoolean("played"), r.optLong("updatedAt"))
            }
        }
        publishShows(); _resume.value = HashMap(resumes)
    }

    fun start() {
        synchronized(lock) { if (started) return; started = true }
        scope.launch(Dispatchers.IO) {
            // Downloads interrupted by quitting resume; a deleted file is forgotten.
            val (pending, missing) = synchronized(lock) {
                episodes.values.filter { it.downloadId != null && it.localPath == null } to episodes.values.filter { it.localPath != null && !File(it.localPath!!).isFile }
            }
            missing.forEach { setDownload(it.id, null, null) }
            pending.forEach { launchDownload(it) }
        }
        if (System.currentTimeMillis() - synchronized(lock) { lastRefresh } > 3 * 60 * 60 * 1000L) refreshAll()
    }

    suspend fun search(term: String): List<PodcastSearchResult> = withContext(Dispatchers.IO) { PodcastDirectory.search(term) }

    suspend fun searchBooks(term: String): List<BookSearchResult> = withContext(Dispatchers.IO) { LibriVox.search(term) }

    /** Subscribe by feed URL; returns the podcast id or null if the feed couldn't be read. */
    suspend fun subscribe(feedUrl: String, artworkHint: String? = null, kind: String = KIND_PODCAST, titleHint: String? = null, authorHint: String? = null, descriptionHint: String? = null, follow: Boolean = true): Long? = withContext(Dispatchers.IO) {
        byFeed(feedUrl)?.let {
            if (follow && it.subscribedAt == 0L) setFollowingNow(it.id, System.currentTimeMillis())
            return@withContext it.id
        }
        val feed = try { readFeed(feedUrl) } catch (e: Exception) { if (e is CancellationException) throw e; null } ?: return@withContext null
        val now = System.currentTimeMillis()
        synchronized(lock) {
            podcasts.values.firstOrNull { it.feedUrl == feedUrl }?.let { existing ->
                if (follow && existing.subscribedAt == 0L) podcasts[existing.id] = existing.copy(subscribedAt = now)
                changed(); return@withContext existing.id
            }
            val id = nextPodcastId++
            podcasts[id] = PodcastEntity(id = id, feedUrl = feedUrl, title = titleHint ?: feed.title, author = authorHint ?: feed.author,
                description = descriptionHint?.takeIf { it.isNotBlank() } ?: feed.description,
                artworkUrl = artworkHint ?: feed.artworkUrl, lastRefreshed = now, subscribedAt = if (follow) now else 0L, kind = kind)
            insertEpisodes(id, feed.episodes)
            changed()
            id
        }
    }

    private fun byFeed(feedUrl: String) = synchronized(lock) { podcasts.values.firstOrNull { it.feedUrl == feedUrl } }

    private fun setFollowingNow(id: Long, since: Long) = synchronized(lock) {
        podcasts[id]?.let { podcasts[id] = it.copy(subscribedAt = since); changed() }
    }

    // A zero follow date marks a cached preview; its episodes can still be played.
    fun setFollowing(id: Long, follow: Boolean) = scope.launch(Dispatchers.IO) {
        setFollowingNow(id, if (follow) System.currentTimeMillis() else 0L)
    }

    fun unsubscribe(id: Long) = scope.launch(Dispatchers.IO) {
        val removed = synchronized(lock) {
            val eps = episodes.values.filter { it.podcastId == id }
            eps.forEach { episodes.remove(it.id); episodeKeys.remove(it.podcastId to it.guid) }
            podcasts.remove(id)
            changed()
            eps
        }
        removed.forEach { ep -> cancelDownload(ep.id); ep.localPath?.let { File(it).delete() }; partFile(ep).delete() }
        _downloadProgress.update { p -> p - removed.map { it.id }.toSet() }
    }

    fun refreshAll() = scope.launch(Dispatchers.IO) {
        _refreshing.value = true
        try {
            val pods = synchronized(lock) { podcasts.values.filter { it.subscribedAt > 0L && !it.feedUrl.startsWith("archive:") } }
            for (pod in pods) {
                val feed = try { readFeed(pod.feedUrl) } catch (e: Exception) { if (e is CancellationException) throw e; null } ?: continue
                synchronized(lock) {
                    val current = podcasts[pod.id] ?: return@synchronized
                    insertEpisodes(pod.id, feed.episodes) // existing guids keep progress/downloads
                    // Books keep the nicer LibriVox title/cover; podcasts follow their feed.
                    podcasts[pod.id] = if (current.kind == KIND_AUDIOBOOK) current.copy(lastRefreshed = System.currentTimeMillis()) else current.copy(
                        title = feed.title, author = feed.author.ifEmpty { current.author }, description = feed.description.ifEmpty { current.description },
                        artworkUrl = feed.artworkUrl ?: current.artworkUrl, lastRefreshed = System.currentTimeMillis(),
                    )
                    changed()
                }
            }
            synchronized(lock) { lastRefresh = System.currentTimeMillis(); changed() }
        } finally {
            _refreshing.value = false
        }
    }

    /** Must hold [lock]. Ignores episodes already stored (same show + guid), like Room's IGNORE. */
    private fun insertEpisodes(podcastId: Long, parsed: List<ParsedEpisode>) {
        for (p in parsed) {
            val key = podcastId to p.guid
            if (key in episodeKeys) continue
            val id = nextEpisodeId++
            episodeKeys[key] = id
            episodes[id] = EpisodeEntity(id = id, podcastId = podcastId, guid = p.guid, title = p.title, description = p.description, audioUrl = p.audioUrl,
                mimeType = p.mimeType, pubDate = p.pubDate, durationMs = p.durationMs, artworkUrl = p.artworkUrl,
                downloadId = null, localPath = null, position = p.position)
        }
    }

    // ---- Downloads (in-process HTTP into app storage, not scanned into the music library) ----

    fun download(episodeId: Long) = scope.launch(Dispatchers.IO) {
        val ep = synchronized(lock) { episodes[episodeId] } ?: return@launch
        if (ep.localPath != null || ep.downloadId != null) return@launch // done or already downloading
        val scheme = runCatching { URI(ep.audioUrl).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return@launch
        setDownload(ep.id, ep.id, null)
        launchDownload(ep.copy(downloadId = ep.id))
    }

    fun downloadAll(ids: List<Long>) = ids.forEach { download(it) }

    fun deleteDownload(episodeId: Long) = scope.launch(Dispatchers.IO) {
        val ep = synchronized(lock) { episodes[episodeId] } ?: return@launch
        cancelDownload(ep.id)
        ep.localPath?.let { File(it).delete() }
        partFile(ep).delete()
        setDownload(ep.id, null, null)
        _downloadProgress.update { it - ep.id }
    }

    /** Only a real extension from the last path segment ("…/456-ep" has none; ".com/…" isn't one). */
    private fun extension(ep: EpisodeEntity): String = runCatching { URI(ep.audioUrl).path }.getOrNull()?.substringAfterLast('/')
        ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp3"

    private fun target(ep: EpisodeEntity) = File(downloadsDir, "ep_${ep.id}.${extension(ep)}")
    private fun partFile(ep: EpisodeEntity) = File(downloadsDir, "ep_${ep.id}.${extension(ep)}.part")

    private fun cancelDownload(id: Long) { synchronized(downloadJobs) { downloadJobs.remove(id) }?.cancel() }

    private fun launchDownload(ep: EpisodeEntity) {
        synchronized(downloadJobs) {
            if (downloadJobs[ep.id]?.isActive == true) return
            _downloadProgress.update { it + (ep.id to (it[ep.id] ?: 0f)) }
            val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) { runDownload(ep) }
            downloadJobs[ep.id] = job
            job.invokeOnCompletion { synchronized(downloadJobs) { if (downloadJobs[ep.id] === job) downloadJobs.remove(ep.id) } }
            job.start()
        }
    }

    private suspend fun runDownload(ep: EpisodeEntity) {
        val part = partFile(ep)
        downloadsDir.mkdirs()
        var attempt = 0
        while (true) {
            try {
                transport.fetch(ep.audioUrl, part) { received, total ->
                    if (total > 0) _downloadProgress.update { it + (ep.id to (received.toFloat() / total).coerceIn(0f, 1f)) }
                }
                val dest = target(ep)
                check(part.length() > 0) { "Empty download" }
                if (!part.renameTo(dest)) { dest.delete(); check(part.renameTo(dest)) { "Could not save the episode" } }
                // Deleted or cancelled meanwhile: drop the file instead of resurrecting the download.
                val kept = synchronized(lock) {
                    val now = episodes[ep.id]
                    if (now?.downloadId != null && now.localPath == null) { episodes[ep.id] = now.copy(localPath = dest.absolutePath); changed(); true } else false
                }
                if (!kept) dest.delete()
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val temporary = e is TransferFailed && DownloadRetry.isTemporary(e.reason) || e !is TransferFailed && e is java.io.IOException
                if (temporary && attempt <= 3) { delay(DownloadRetry.delayMillis(attempt)); continue }
                part.delete()
                setDownload(ep.id, null, null)
                break
            }
        }
        _downloadProgress.update { it - ep.id }
    }

    private fun setDownload(id: Long, downloadId: Long?, localPath: String?) = synchronized(lock) {
        episodes[id]?.let { episodes[id] = it.copy(downloadId = downloadId, localPath = localPath); changed() }
    }

    // ---- Resume / played ----

    fun saveProgress(key: String, positionMs: Long, durationMs: Long) = scope.launch(Dispatchers.IO) {
        val played = durationMs > 0 && positionMs >= durationMs - 30_000
        putResume(ResumeEntity(key, if (played) 0 else positionMs, durationMs, played, System.currentTimeMillis()))
    }

    fun setPlayed(key: String, played: Boolean, durationMs: Long) = scope.launch(Dispatchers.IO) {
        putResume(ResumeEntity(key, 0, durationMs, played, System.currentTimeMillis()))
    }

    suspend fun resumePosition(key: String): Long = synchronized(lock) { resumes[key] }?.takeIf { !it.played }?.positionMs ?: 0L

    private fun putResume(r: ResumeEntity) {
        synchronized(lock) { resumes[r.mediaKey] = r; _resume.value = HashMap(resumes) }
        resumeStore.save(1_000) {
            val snapshot = synchronized(lock) { resumes.values.toList() }
            JSONObject().apply { snapshot.forEach { put(it.mediaKey, JSONObject().put("positionMs", it.positionMs).put("durationMs", it.durationMs).put("played", it.played).put("updatedAt", it.updatedAt)) } }.toString()
        }
    }

    /** Writes pending changes now (call on shutdown). */
    fun flush() { store.flush(); resumeStore.flush() }

    // ---- Persistence ----

    /** Must hold [lock]: republish and schedule a coalesced write. */
    private fun changed() {
        publishShows()
        store.save(500) { synchronized(lock) { snapshotJson() } }
    }

    private fun publishShows() {
        val byShow = episodes.values.sortedByDescending { it.pubDate }.groupBy { it.podcastId }
        _shows.value = podcasts.values.sortedByDescending { it.subscribedAt }.map { Show(it, byShow[it.id].orEmpty()) }
    }

    private fun snapshotJson(): String = JSONObject()
        .put("lastRefresh", lastRefresh).put("nextPodcastId", nextPodcastId).put("nextEpisodeId", nextEpisodeId)
        .put("podcasts", JSONArray(podcasts.values.map { p ->
            JSONObject().put("id", p.id).put("feedUrl", p.feedUrl).put("title", p.title).put("author", p.author).put("description", p.description)
                .put("artworkUrl", p.artworkUrl).put("lastRefreshed", p.lastRefreshed).put("subscribedAt", p.subscribedAt).put("kind", p.kind)
        }))
        .put("episodes", JSONArray(episodes.values.map { e ->
            JSONObject().put("id", e.id).put("podcastId", e.podcastId).put("guid", e.guid).put("title", e.title).put("description", e.description)
                .put("audioUrl", e.audioUrl).put("mimeType", e.mimeType).put("pubDate", e.pubDate).put("durationMs", e.durationMs)
                .put("artworkUrl", e.artworkUrl).put("downloadId", e.downloadId).put("localPath", e.localPath).put("position", e.position)
        })).toString()

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

    private fun podcastFrom(o: JSONObject): PodcastEntity? = runCatching {
        PodcastEntity(o.getLong("id"), o.getString("feedUrl"), o.optString("title"), o.optString("author"), o.optString("description"),
            o.str("artworkUrl"), o.optLong("lastRefreshed"), o.optLong("subscribedAt"), o.optString("kind", KIND_PODCAST))
    }.getOrNull()

    private fun episodeFrom(o: JSONObject): EpisodeEntity? = runCatching {
        EpisodeEntity(o.getLong("id"), o.getLong("podcastId"), o.getString("guid"), o.optString("title"), o.optString("description"),
            o.getString("audioUrl"), o.str("mimeType"), o.optLong("pubDate"), o.optLong("durationMs"), o.str("artworkUrl"),
            if (o.has("downloadId") && !o.isNull("downloadId")) o.getLong("downloadId") else null, o.str("localPath"), o.optInt("position"))
    }.getOrNull()
}

const val KIND_PODCAST = "podcast"
const val KIND_AUDIOBOOK = "audiobook"

/** Resume key: episodes are "ep:<id>", local files their song id. */
val Song.resumeKey: String get() = episodeId?.let { "ep:$it" } ?: id.toString()

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
    sourceUri = localPath?.let { File(it).toURI().toString() } ?: audioUrl,
    artUrl = artworkUrl ?: podcast.artworkUrl,
    isPodcast = true,
    isAudiobook = podcast.kind == KIND_AUDIOBOOK,
    track = position + 1,
    episodeId = id,
)

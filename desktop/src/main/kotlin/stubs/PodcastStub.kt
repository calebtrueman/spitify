// UI STUB — deleted at integration
package com.localfy.app.data.podcast

import com.localfy.app.data.Song
import com.localfy.app.data.db.EpisodeEntity
import com.localfy.app.data.db.PodcastEntity
import com.localfy.app.data.db.ResumeEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Show(val podcast: PodcastEntity, val episodes: List<EpisodeEntity>) {
    val id: Long get() = podcast.id
}

data class PodcastSearchResult(val title: String, val author: String, val feedUrl: String, val artworkUrl: String?, val genre: String?, val explicit: Boolean? = null)

data class BookSearchResult(
    val id: String, val title: String, val author: String, val description: String, val totalSeconds: Long,
    val sections: Int, val rssUrl: String, val coverUrl: String?, val language: String,
)

object OpenLibrary {
    data class Book(val title: String, val author: String, val year: Int?, val coverUrl: String?)
}

const val KIND_PODCAST = "podcast"
const val KIND_AUDIOBOOK = "audiobook"

val Song.resumeKey: String get() = episodeId?.let { "ep:$it" } ?: id.toString()

fun EpisodeEntity.toSong(podcast: PodcastEntity): Song = Song(
    id = -id, title = title, artist = podcast.title, album = podcast.title, albumId = -podcast.id - 1_000_000,
    albumArtist = podcast.author, durationMs = durationMs, track = position, disc = 1, year = 0, genre = "Podcast",
    folder = "", dateAddedSec = pubDate / 1000, sizeBytes = 0, mimeType = mimeType, sourceUri = localPath ?: audioUrl,
    artUrl = artworkUrl ?: podcast.artworkUrl, isPodcast = true, isAudiobook = podcast.kind == KIND_AUDIOBOOK, episodeId = id,
)

class PodcastRepository {
    val shows: StateFlow<List<Show>> = MutableStateFlow(emptyList())
    val resume: StateFlow<Map<String, ResumeEntity>> = MutableStateFlow(emptyMap())
    val episodeSongs: StateFlow<Map<Long, Song>> = MutableStateFlow(emptyMap())
    val refreshing: StateFlow<Boolean> = MutableStateFlow(false)
    val downloadProgress: StateFlow<Map<Long, Float>> = MutableStateFlow(emptyMap())
    fun start() {}
    suspend fun search(term: String): List<PodcastSearchResult> = emptyList()
    suspend fun searchBooks(term: String): List<BookSearchResult> = emptyList()
    suspend fun subscribe(feedUrl: String, artworkHint: String? = null, kind: String = KIND_PODCAST, titleHint: String? = null, authorHint: String? = null, descriptionHint: String? = null, follow: Boolean = true): Long? = null
    fun setFollowing(id: Long, follow: Boolean): Job = Job()
    fun unsubscribe(id: Long): Job = Job()
    fun refreshAll(): Job = Job()
    fun download(episodeId: Long): Job = Job()
    fun downloadAll(ids: List<Long>) {}
    fun deleteDownload(episodeId: Long): Job = Job()
    fun setPlayed(key: String, played: Boolean, durationMs: Long): Job = Job()
}

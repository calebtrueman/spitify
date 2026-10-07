// UI STUB — deleted at integration
package com.localfy.app.data.music

import com.localfy.app.data.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class MusicStreams {
    val saved: StateFlow<List<Song>> = MutableStateFlow(emptyList())
    fun register(incoming: OnlineTrack): Song = song(incoming)
    fun track(song: Song): OnlineTrack? = null
    fun lookup(id: Long): Song? = null
    fun contains(track: OnlineTrack): Boolean = false
    fun save(items: List<OnlineTrack>) {}
    fun remove(items: List<OnlineTrack>) {}
    fun song(track: OnlineTrack) = Song(
        id = streamId(track.id), title = track.title, artist = track.artist, album = track.album, albumId = 0,
        albumArtist = track.albumArtist ?: track.artist, durationMs = track.durationMs, track = track.track, disc = track.disc,
        year = 0, genre = null, folder = "", dateAddedSec = 0, sizeBytes = 0, mimeType = null,
        sourceUri = "spitify:stream/${track.id}", artUrl = track.artwork,
    )
    companion object {
        fun streamId(id: String): Long = -(1L shl 62) - (id.hashCode().toLong() and 0x0fffffff)
    }
}

/** The streaming cache (Android: ListeningCache.clear(context)). */
object ListeningCache {
    fun clear() {}
}

data class MusicDownloadEntity(
    val id: String,
    val trackJson: String,
    val state: String = "queued",
    val downloadId: Long? = null,
    val localUri: String? = null,
    val error: String? = null,
    val quality: String? = null,
    val wifiOnly: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

fun MusicDownloadEntity.track(): OnlineTrack = requireNotNull(Monochrome.parseTrack(org.json.JSONObject(trackJson)))
val MusicDownloadEntity.active: Boolean get() = state in listOf("queued", "waiting", "finding", "downloading", "checking")

class MusicDownloads {
    val jobs: StateFlow<List<MusicDownloadEntity>> = MutableStateFlow(emptyList())
    val jobsById: StateFlow<Map<String, MusicDownloadEntity>> = MutableStateFlow(emptyMap())
    val progress: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap())
    suspend fun enqueue(tracks: List<OnlineTrack>): String = ""
    fun cancel(id: String): Job = Job()

    // Desktop-only (no Android equivalent): where downloads are saved.
    val folder: StateFlow<File> = MutableStateFlow(File(System.getProperty("user.home"), "Music/Spitify"))
    fun setFolder(folder: File) {}
}

data class ReleaseNotice(val artist: OnlineArtist, val album: OnlineAlbum)

class ArtistFollows {
    val revision: StateFlow<Long> = MutableStateFlow(0L)
    val artists: List<OnlineArtist> get() = emptyList()
    val releases: List<ReleaseNotice> get() = emptyList()
    var message: String? = null; private set
    var notifications: Boolean = false; private set
    fun setNotifications(enabled: Boolean) {}
    fun contains(id: String): Boolean = false
    fun follow(artist: OnlineArtist, albums: List<OnlineAlbum>) {}
    fun unfollow(id: String) {}
    suspend fun refresh() {}
}

class FlacConversion {
    sealed interface State {
        data object Idle : State
        data class Converting(val done: Int, val total: Int, val current: String) : State
        data class Finished(val converted: Int, val skipped: Int, val savedBytes: Long) : State
    }
    val state: StateFlow<State> = MutableStateFlow(State.Idle)
    fun candidates(): List<Song> = emptyList()
    fun estimatedSaving(songs: List<Song>): Long = 0
    fun start() {}
    fun cancel() {}
}

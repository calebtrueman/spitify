// UI STUB — deleted at integration
package com.localfy.app.data.social

import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.music.MusicStreams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.image.BufferedImage
import java.io.File

class SocialRepository {
    val state = SocialState()
    val revision: StateFlow<Long> = MutableStateFlow(0L)
    val publicKey: String get() = "0".repeat(64)
    var publicProfile: Boolean = true; private set
    var enabled: Boolean = false; private set
    var discovery: Boolean = false; private set
    var relayAddresses: List<String> = emptyList(); private set
    var connected: Int = 0; private set
    var pending: Int = 0; private set
    val rooms = RoomState()
    var message: String? = null; private set
    val playlists: List<SharedPlaylist> get() = state.playlists.values.sortedByDescending { it.updatedAt }
    fun configure(enabled: Boolean, discovery: Boolean = this.discovery, relays: List<String>? = null) {}
    fun follow(input: String) {}
    fun unfollow(id: String) {}
    fun save(playlist: SharedPlaylist) {}
    fun create(name: String, songs: List<Song>, streams: MusicStreams, kind: String = "playlist"): SharedPlaylist =
        SharedPlaylist(owner = publicKey, name = name, kind = kind)
    fun copy(playlist: SharedPlaylist): SharedPlaylist = playlist
    fun remove(playlist: SharedPlaylist) {}
    suspend fun publishProfile(name: String, about: String) {}
    fun setPublicProfile(value: Boolean) {}
    suspend fun syncProfile(force: Boolean = false) {}
    suspend fun share(playlist: SharedPlaylist, person: String? = null) {}
    suspend fun setEditor(person: String, playlist: SharedPlaylist, allowed: Boolean) {}
    suspend fun edit(edit: SharedEdit) {}
    suspend fun joinRoom(link: SocialLink) {}
}

class ListeningRooms {
    val message: StateFlow<String?> = MutableStateFlow(null)
    val room: ListeningRoom? get() = null
    val isHost: Boolean get() = false
    fun host(name: String) {}
    suspend fun approve(incoming: IncomingRoomRequest, allowed: Boolean) {}
    suspend fun setControls(allowed: Boolean) {}
    suspend fun leave() {}
    suspend fun control(playing: Boolean? = null, next: Boolean = false, position: Long? = null) {}
    suspend fun add(tracks: List<SharedTrack>) {}
    suspend fun remove(trackID: String) {}
}

object PlaylistMatches {
    val failed = MutableStateFlow<Set<String>>(emptySet())
    fun key(track: SharedTrack) = "${track.recordingKey}|${track.durationMs}"
    fun choose(track: SharedTrack, song: Song, app: LocalfyApp) {}
    fun retry(playlist: SharedPlaylist, app: LocalfyApp) {}
    fun prepare(playlist: SharedPlaylist, app: LocalfyApp) {}
    suspend fun resolve(track: SharedTrack, app: LocalfyApp): Song = error("No match")
}

/** Android uses Bitmap/Uri; desktop uses BufferedImage/File. */
object FriendPictureCode {
    fun make(value: String): BufferedImage = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
    fun read(file: File): String? = null
}

/** Android reads a Bitmap; desktop reads a BufferedImage. */
object SpotifyCodeImageReader {
    fun read(image: BufferedImage): Long? = null
}

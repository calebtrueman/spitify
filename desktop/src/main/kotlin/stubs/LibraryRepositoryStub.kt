// UI STUB — deleted at integration
package com.localfy.app.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Computed shelves (lives in LibraryRepository.kt on Android). */
data class SmartCollection(val kind: Kind, val songs: List<Song>) {
    enum class Kind(val title: String, val subtitle: String) {
        AllSongs("All Songs", "Every song in your library"),
        RecentlyAdded("Recently added", "Fresh on this device"),
        MostPlayed("On repeat", "Your most played tracks"),
        RecentlyPlayed("Recently played", "Pick up where you left off"),
        Forgotten("Forgotten favourites", "Loved once, not heard lately"),
        NeverPlayed("Undiscovered", "Songs you own but never played"),
    }
}

class LibraryRepository {
    val library: StateFlow<Library> = MutableStateFlow(Library())
    val rawSongs: StateFlow<List<Song>> = MutableStateFlow(emptyList())
    val localBooks: StateFlow<List<Song>> = MutableStateFlow(emptyList())
    val localPodcasts: StateFlow<List<Song>> = MutableStateFlow(emptyList())
    val scanning: StateFlow<Boolean> = MutableStateFlow(false)
    val showRecommendations: StateFlow<Boolean> = MutableStateFlow(true)
    fun setShowRecommendations(show: Boolean) {}
    val hideShortTracks: StateFlow<Boolean> = MutableStateFlow(true)
    fun setHideShortTracks(hide: Boolean) {}
    val likedIds: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
    val stats: StateFlow<Map<Long, PlayStat>> = MutableStateFlow(emptyMap())
    /** Android takes a content Uri; desktop takes the picked image file (null = use song artwork). */
    suspend fun setPlaylistCover(id: Long, file: File?): Boolean = false
    val playlists: StateFlow<List<Playlist>> = MutableStateFlow(emptyList())
    val smart: StateFlow<Map<SmartCollection.Kind, SmartCollection>> = MutableStateFlow(emptyMap())
    val hiddenMixCount: StateFlow<Int> = MutableStateFlow(0)
    val mixes: StateFlow<List<Mix>> = MutableStateFlow(emptyList())
    fun deleteMix(key: String) {}
    fun restoreDeletedMixes() {}
    fun ensureStarted() {}
    fun refresh() {}
    fun toggleLike(songId: Long): Job = Job()
    suspend fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Long = 0
    suspend fun appendToPlaylist(playlistId: Long, songIds: List<Long>) {}
    fun removeFromPlaylist(playlistId: Long, entryId: Long): Job = Job()
    fun renamePlaylist(playlistId: Long, name: String): Job = Job()
    fun deletePlaylist(playlistId: Long): Job = Job()

    // Desktop-only (no Android equivalent): the folders scanned for music.
    val folders: StateFlow<List<File>> = MutableStateFlow(emptyList())
    fun addFolder(folder: File) {}
    fun removeFolder(folder: File) {}
}

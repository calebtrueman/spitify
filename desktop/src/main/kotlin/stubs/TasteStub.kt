// UI STUB — deleted at integration
package com.localfy.app.data.taste

import com.localfy.app.data.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class Profile(
    val name: String = "",
    val hasPhoto: Boolean = false,
    val photoVersion: Long = 0,
    val onboarded: Boolean = false,
    val seedArtists: Set<String> = emptySet(),
    val createdAt: Long = 0,
)

class ProfileRepository {
    val photoFile: File get() = File("profile.jpg")
    val profile: StateFlow<Profile> = MutableStateFlow(Profile(onboarded = true))
    fun setName(name: String) {}
    /** Android takes a picked image Uri; desktop takes the picked image file (null removes the photo). */
    fun setPhoto(file: File?): Job = Job()
    fun setSeedArtists(artists: Set<String>) {}
    fun completeOnboarding() {}
}

class TasteRepository {
    val hiddenSongs: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
    val hiddenArtists: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    val model: StateFlow<TasteModel?> = MutableStateFlow(null)
    fun hideSong(id: Long) {}
    fun hideArtist(name: String) {}
    fun unhideAll() {}
    fun songRadio(seed: Song): List<Song> = listOf(seed)
    fun artistRadio(artist: String): List<Song> = emptyList()
}

// UI STUB — deleted at integration
package com.localfy.app.data.meta

import com.localfy.app.data.Song
import com.localfy.app.data.podcast.OpenLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class MetadataCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val year: Int?,
    val genre: String?,
    val track: Int?,
    val disc: Int?,
    val durationMs: Long,
    val artUrl: String?,
    val source: String,
)

class MetadataRepository {
    val autoFix: StateFlow<Boolean> = MutableStateFlow(true)
    val fixing: StateFlow<Boolean> = MutableStateFlow(false)
    fun setAutoFix(on: Boolean) {}
    suspend fun prepareArtwork(source: String): String = source
    suspend fun saveFiles(songs: List<Song>, edit: MetadataEdit, artSource: String?) {}
    fun reset(songs: List<Song>): Job = Job()
    fun customArt(albumId: Long): File? = null
    fun removeArt(albumId: Long): Job = Job()
    fun queryFor(song: Song): String = "${song.artist} ${song.title}"
    suspend fun search(query: String, durationMs: Long = 0): List<MetadataCandidate> = emptyList()
    suspend fun searchBooks(query: String): List<OpenLibrary.Book> = emptyList()
}

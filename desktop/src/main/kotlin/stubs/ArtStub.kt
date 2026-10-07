// UI STUB — deleted at integration
package com.localfy.app.data.art

import com.localfy.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class OnlineArtRepository {
    val enabled: StateFlow<Boolean> = MutableStateFlow(true)
    fun setEnabled(on: Boolean) {}
    fun cached(albumId: Long): File? = null
    fun downloadedCount(): Int = 0
    fun clear() {}
    suspend fun fetch(albumId: Long, artist: String, album: String): File? = null
}

/** Desktop-only: encoded album art for a song (custom → embedded → online), at most [maxPx] wide. */
class ArtworkStore {
    suspend fun albumArt(song: Song, maxPx: Int): ByteArray? = null
}

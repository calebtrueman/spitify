// UI STUB — deleted at integration
package com.localfy.app.data.lyrics

import com.localfy.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class LyricsSource(val label: String) { Embedded("Embedded in file"), LrcFile(".lrc file"), Lrclib("LRCLIB") }

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: LyricsSource, val offsetMs: Int = 0)

sealed interface LyricsState {
    data object Loading : LyricsState
    data class NotFound(val searchedOnline: Boolean) : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

class LyricsRepository {
    val states: StateFlow<Map<Long, LyricsState>> = MutableStateFlow(emptyMap())
    val onlineEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    /** Android: a document-tree Uri; desktop: the folder of .lrc files. */
    val folder: StateFlow<File?> = MutableStateFlow(null)
    fun setOnlineEnabled(enabled: Boolean) {}
    fun setFolder(folder: File?) {}
    fun request(song: Song, forceOnline: Boolean = false) {}
    fun setOffset(song: Song, offsetMs: Int) {}
}

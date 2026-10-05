package com.localfy.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.launch

/** Whether a song can play without a connection. */
sealed interface DownloadState {
    /** A file on this phone (your own music, or a finished download). */
    data object OnDevice : DownloadState
    /** Streamed only; can be downloaded. */
    data object Online : DownloadState
    data class Downloading(val progress: Float?) : DownloadState
    /** Podcasts, books and anything else that doesn't use music downloads. */
    data object NotApplicable : DownloadState
}

private fun Song.isStream() = sourceUri?.scheme == "spitify"

/** Queued, finding a source, transferring or waiting to retry. */
private val com.localfy.app.data.music.MusicDownloadEntity.inProgress: Boolean get() = state !in setOf("complete", "failed", "cancelled")

@Composable
fun rememberDownloadState(song: Song): DownloadState {
    if (song.isPodcast || song.isAudiobook) return DownloadState.NotApplicable
    if (!song.isStream()) return DownloadState.OnDevice
    val app = LocalContext.current.applicationContext as LocalfyApp
    val trackId = remember(song.id) { app.musicStreams.track(song)?.id } ?: return DownloadState.Online
    val jobs by app.musicDownloads.jobsById.collectAsStateWithLifecycle()
    val job = jobs[trackId] ?: return DownloadState.Online
    if (!job.inProgress) return DownloadState.Online // finished downloads become local songs in the library
    val progress by app.musicDownloads.progress.collectAsStateWithLifecycle()
    return DownloadState.Downloading(progress[trackId])
}

/** Saves streamed songs to the library and queues them for download, reporting the result. */
fun downloadSongs(app: LocalfyApp, songs: List<Song>) {
    val tracks: List<OnlineTrack> = songs.mapNotNull { if (it.isStream()) app.musicStreams.track(it) else null }
    if (tracks.isEmpty()) return
    app.musicStreams.save(tracks)
    app.appScope.launch {
        val message = app.musicDownloads.enqueue(tracks)
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }
}

/** Trailing control on a song row: download, progress (tap to cancel), or nothing. */
@Composable
fun SongDownloadButton(song: Song, state: DownloadState) {
    val app = LocalContext.current.applicationContext as LocalfyApp
    when (state) {
        DownloadState.Online -> IconButton(onClick = { downloadSongs(app, listOf(song)) }) {
            Icon(Icons.Rounded.ArrowCircleDown, "Download", tint = LocalfyColors.TextSecondary)
        }
        is DownloadState.Downloading -> IconButton(onClick = { app.musicStreams.track(song)?.let { app.musicDownloads.cancel(it.id) } }) {
            Box(contentAlignment = Alignment.Center) {
                val p = state.progress
                if (p != null && p > 0f) CircularProgressIndicator(progress = { p }, Modifier.size(22.dp), strokeWidth = 2.5.dp)
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        }
        else -> {}
    }
}

/** Small green mark next to the subtitle of a song that plays offline. */
@Composable
fun DownloadedMark(modifier: Modifier = Modifier) {
    Icon(Icons.Rounded.DownloadForOffline, "Downloaded", modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
}

/** Header action for a list of songs: download every streamed one, show progress, or confirm all are offline. */
@Composable
fun DownloadAllButton(songs: List<Song>) {
    val app = LocalContext.current.applicationContext as LocalfyApp
    val jobs by app.musicDownloads.jobsById.collectAsStateWithLifecycle()
    val streams = remember(songs) { songs.filter { it.isStream() && !it.isPodcast && !it.isAudiobook } }
    if (songs.none { !it.isPodcast && !it.isAudiobook }) return
    val ids = remember(streams) { streams.associateWith { app.musicStreams.track(it)?.id } }
    val waiting = streams.filter { s -> ids[s]?.let { jobs[it]?.inProgress } != true }
    val active = streams.size - waiting.size
    when {
        streams.isEmpty() -> IconButton(onClick = {}, enabled = false) {
            Icon(Icons.Rounded.DownloadDone, "All songs downloaded", tint = MaterialTheme.colorScheme.primary)
        }
        waiting.isEmpty() -> IconButton(onClick = { streams.forEach { s -> ids[s]?.let(app.musicDownloads::cancel) } }) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        }
        else -> IconButton(onClick = { downloadSongs(app, waiting) }) {
            Icon(Icons.Rounded.ArrowCircleDown, if (active > 0) "Download the other ${waiting.size}" else "Download all ${waiting.size}", tint = LocalfyColors.TextSecondary)
        }
    }
}

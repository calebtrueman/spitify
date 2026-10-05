package com.localfy.app.ui.screens

import com.localfy.app.ui.theme.LocalPalette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.Song
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.rememberArtColor
import com.localfy.app.ui.components.BigPlayButton
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.SongRow
import com.localfy.app.ui.components.formatLongDuration
import com.localfy.app.ui.components.songCount
import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.theme.LocalfyColors

val CircleArt: Shape = CircleShape

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen(
    title: String,
    kindLabel: String,
    subtitle: String,
    art: ArtKey?,
    songs: List<Song>,
    modifier: Modifier = Modifier,
    artShape: Shape = RoundedCornerShape(8.dp),
    hero: Boolean = false,
    trackNumbers: Boolean = false,
    songSubtitle: (Song) -> String = { "${it.artist} • ${it.album}" },
    extrasFor: (Int, Song) -> SongMenuExtras = { _, _ -> SongMenuExtras() },
    headerActions: @Composable () -> Unit = {},
    beforeSongs: LazyListScope.() -> Unit = {},
    afterSongs: LazyListScope.() -> Unit = {},
    emptyText: String = "Nothing here yet.",
    /** Replaces the artwork in the header (generated playlist covers). */
    cover: (@Composable (Modifier) -> Unit)? = null,
    catalogTracks: List<com.localfy.app.data.music.OnlineTrack>? = null,
) {
    val app = LocalApp.current
    val player = rememberPlayerState()
    val liked by app.repo.likedIds.collectAsStateWithLifecycle()
    val isThisPlaying = player.source == title && player.isPlaying
    val total = remember(songs, catalogTracks) { catalogTracks?.sumOf { it.durationMs } ?: songs.sumOf { it.durationMs } }
    val count = catalogTracks?.size ?: songs.size
    val play = {
        when {
            player.source == title && player.hasMedia -> app.player.togglePlay()
            songs.isNotEmpty() -> app.player.playSongs(songs, 0, shuffle = false, source = title)
        }
    }
    CollectionPage(
        title = title, kindLabel = kindLabel, subtitle = subtitle,
        summary = "${songCount(count)} • ${formatLongDuration(total)}", art = art,
        modifier = modifier, artShape = artShape, hero = hero, cover = cover,
        playing = isThisPlaying, playEnabled = songs.isNotEmpty(), onPlay = play,
        onShuffle = { app.player.playSongs(songs, shuffle = true, source = title) }, shuffleActive = player.shuffle,
        headerActions = {
            headerActions()
            // Download every streamed song here (or confirm they're all offline already).
            if (catalogTracks == null && songs.isNotEmpty()) com.localfy.app.ui.components.DownloadAllButton(songs)
            if (songs.isNotEmpty() && songs.none { it.isPodcast || it.isAudiobook }) ShareMusicButton(title, songs, if (kindLabel == "Album") "album" else if (kindLabel == "Song") "song" else "playlist")
            IconButton(onClick = { app.addToPlaylist(songs) }, enabled = songs.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add all to playlist", tint = LocalfyColors.TextSecondary)
            }
            if (catalogTracks == null) IconButton(onClick = { app.player.addToQueue(songs) }, enabled = songs.isNotEmpty()) {
                Icon(Icons.Rounded.AddToQueue, "Add all to queue", tint = LocalfyColors.TextSecondary)
            }
        },
    ) {
            beforeSongs()
            if (songs.isEmpty() && catalogTracks == null) item(key = "empty") { EmptyState("Empty", emptyText) }
            if (catalogTracks != null) {
                itemsIndexed(catalogTracks, key = { _, track -> track.id }) { i, track ->
                    OnlineMusicRow(track, trackNumber = if (trackNumbers) track.track.takeIf { it > 0 } ?: (i + 1) else null,
                        onPlay = { song -> app.player.playSongs(songs, songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0), shuffle = false, source = title) })
                }
            } else itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, song ->
                SongRow(
                    song = song,
                    onClick = { app.player.playSongs(songs, i, shuffle = false, source = title) },
                    isCurrent = player.currentId == song.id,
                    isPlaying = player.isPlaying,
                    liked = song.id in liked,
                    trackNumber = if (trackNumbers) song.track.takeIf { it > 0 } ?: (i + 1) else null,
                    subtitle = songSubtitle(song),
                    onMore = { app.openSongMenu(song, extrasFor(i, song)) },
                )
            }
            afterSongs()
    }
}

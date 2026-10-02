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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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

/**
 * Shared layout for albums, artists, playlists, mixes, genres, folders and smart lists.
 * The artwork shrinks and fades as you scroll, then a compact bar with the title and a play
 * button pins to the top (Spotify's collapsing header). [hero] gives artists a full-bleed photo.
 */
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
    headerActions: @Composable RowScope.() -> Unit = {},
    beforeSongs: LazyListScope.() -> Unit = {},
    afterSongs: LazyListScope.() -> Unit = {},
    emptyText: String = "Nothing here yet.",
    /** Replaces the artwork in the header (generated playlist covers). */
    cover: (@Composable (Modifier) -> Unit)? = null,
) {
    val app = LocalApp.current
    val player = rememberPlayerState()
    val liked by app.repo.likedIds.collectAsStateWithLifecycle()
    val artColor by rememberArtColor(art)
    val palette = LocalPalette.current
    // Light theme: a pastel version of the artwork colour keeps dark text readable.
    val color = if (palette.isDark) artColor else lerp(artColor, Color.White, 0.55f)
    val isThisPlaying = player.source == title && player.isPlaying
    val total = songs.sumOf { it.durationMs }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val play = {
        when {
            player.source == title && player.hasMedia -> app.player.togglePlay()
            songs.isNotEmpty() -> app.player.playSongs(songs, 0, shuffle = false, source = title)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 560.dp
        val headerPx = with(density) { (if (hero) 360.dp else 300.dp).toPx() }
        val collapse by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) 1f
                else (listState.firstVisibleItemScrollOffset / headerPx).coerceIn(0f, 1f)
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color, lerp(color, LocalfyColors.Background, 0.75f), LocalfyColors.Background)))) {
                    if (hero) {
                        // Keep the moving photo inside the area covered by its fade.
                        Box(Modifier.fillMaxWidth().height(if (wide) 300.dp else 360.dp).clipToBounds()) {
                            Artwork(
                                art,
                                Modifier.fillMaxSize().graphicsLayer {
                                    val s = 1f + collapse * 0.15f
                                    scaleX = s; scaleY = s; alpha = 1f - collapse
                                    translationY = listState.firstVisibleItemScrollOffset * 0.5f
                                },
                            )
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to LocalfyColors.Background.copy(alpha = 0.95f))))
                            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Text(kindLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalfyColors.TextPrimary.copy(alpha = 0.8f))
                                Text(title, style = if (wide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp))
                    } else if (wide) {
                        Row(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                            val m = Modifier.size(200.dp).graphicsLayer { alpha = 1f - collapse }.shadow(24.dp, artShape)
                            if (cover != null) cover(m.clip(artShape)) else Artwork(art, m, artShape)
                            Spacer(Modifier.width(24.dp))
                            Column {
                                Text(kindLabel.uppercase(), style = MaterialTheme.typography.labelMedium)
                                Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextPrimary.copy(alpha = 0.85f))
                                Text("${songCount(songs.size)} • ${formatLongDuration(total)}", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 52.dp, bottom = 8.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                            val m = Modifier.fillMaxWidth(0.62f).aspectRatio(1f)
                                .graphicsLayer {
                                    val s = 1f - collapse * 0.25f
                                    scaleX = s; scaleY = s; alpha = 1f - collapse * 1.2f
                                    translationY = listState.firstVisibleItemScrollOffset * 0.6f
                                }
                                .shadow(28.dp, artShape, spotColor = Color.Black)
                            if (cover != null) cover(m.clip(artShape)) else Artwork(art, m, artShape)
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextPrimary.copy(alpha = 0.85f))
                            Text(
                                "$kindLabel • ${songCount(songs.size)}, ${formatLongDuration(total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalfyColors.TextSecondary,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(onClick = { app.addToPlaylist(songs) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add all to playlist", tint = LocalfyColors.TextSecondary)
                        }
                        IconButton(onClick = { app.player.addToQueue(songs) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Rounded.AddToQueue, "Add all to queue", tint = LocalfyColors.TextSecondary)
                        }
                        headerActions()
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { app.player.playSongs(songs, shuffle = true, source = title) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Rounded.Shuffle, "Shuffle play", tint = if (player.shuffle) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary, modifier = Modifier.size(28.dp))
                        }
                        BigPlayButton(playing = isThisPlaying, onClick = play, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            beforeSongs()
            if (songs.isEmpty()) item(key = "empty") { EmptyState("Empty", emptyText) }
            itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, song ->
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

        // Pinned top bar: fades in with the title and a mini play button once the header scrolls away.
        val barAlpha = ((collapse - 0.55f) / 0.35f).coerceIn(0f, 1f)
        Row(
            Modifier
                .fillMaxWidth()
                .background(lerp(color, LocalfyColors.Background, 0.3f).copy(alpha = barAlpha))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { app.nav.popBackStack() }) {
                Box(Modifier.size(34.dp).background(LocalfyColors.Background.copy(alpha = 0.45f * (1f - barAlpha)), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = barAlpha },
            )
            AnimatedVisibility(barAlpha > 0.9f, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                BigPlayButton(isThisPlaying, play, Modifier.padding(end = 8.dp), size = 42.dp)
            }
        }
    }
}

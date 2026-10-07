package com.localfy.app.ui.components

import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.LocalContentColor
import com.localfy.app.ui.theme.LocalPalette
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.onSecondaryClick
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localfy.app.data.Song
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.theme.LocalfyColors

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatLongDuration(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h hr $m min" else "$m min"
}

fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    liked: Boolean = false,
    trackNumber: Int? = null,
    subtitle: String = "${song.artist} • ${song.album}",
    onMore: (() -> Unit)? = null,
    downloaded: Boolean = false,
    /** Off when the caller draws its own download control (online album rows). */
    showDownload: Boolean = true,
) {
    val haptics = rememberHaptics()
    val app = LocalApp.current
    val download = rememberDownloadState(song)
    var swipe by remember(song.id) { mutableFloatStateOf(0f) }
    var dragging by remember(song.id) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val animatedSwipe by animateFloatAsState(swipe, spring(stiffness = Spring.StiffnessMedium), label = "queue swipe")
    Box(modifier.fillMaxWidth().clipToBounds()) {
        if (swipe > 0f || animatedSwipe > 1f) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp))
            }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = if (dragging) swipe else animatedSwipe }
            .pointerInput(song.id, threshold) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragCancel = { dragging = false; swipe = 0f },
                    onDragEnd = {
                        if (swipe >= threshold && app.player.addToQueue(listOf(song))) {
                            haptics(HapticFeedbackType.Confirm)
                        }
                        dragging = false
                        swipe = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    swipe = (swipe + amount).coerceIn(0f, threshold * 1.6f)
                }
            }
            .graphicsLayer { alpha = if (song.playable) 1f else 0.45f }
            .onSecondaryClick(onMore)
            .combinedClickable(onClick = onClick, onLongClick = onMore?.let { { haptics(HapticFeedbackType.LongPress); it() } })
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) {
                    EqualizerBars(isPlaying, size = 14.dp)
                } else {
                    Text(trackNumber.toString(), style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
                }
            }
        } else {
            Box {
                Artwork(song.artKey, Modifier.size(50.dp), RoundedCornerShape(6.dp))
                if (isCurrent) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        EqualizerBars(isPlaying, size = 18.dp, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalfyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Every row says whether the song plays offline (Spotify's green arrow).
                if (downloaded || download == DownloadState.OnDevice) {
                    DownloadedMark()
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (song.playable) subtitle else "Unsupported format (.${song.fileName.substringAfterLast('.')})",
                    style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showDownload && !downloaded) SongDownloadButton(song, download)
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More options", tint = LocalfyColors.TextSecondary) }
        }
    }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = LocalfyColors.TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(50)).pressable(onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

data class TileData(
    val key: String,
    val title: String,
    val subtitle: String,
    val art: ArtKey?,
    val circle: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    /** Custom cover (generated playlists); defaults to the artwork for [art]. */
    val cover: (@Composable (Modifier) -> Unit)? = null,
    val onClick: () -> Unit,
)

@Composable
fun MediaTile(tile: TileData, width: Dp, modifier: Modifier = Modifier, inset: Boolean = true) {
    val shape: Shape = if (tile.circle) CircleShape else RoundedCornerShape(8.dp)
    Column(modifier.width(width).pressable(onLongClick = tile.onLongClick, onClick = tile.onClick).padding(if (inset) 4.dp else 0.dp)) {
        val coverModifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        if (tile.cover != null) tile.cover.invoke(coverModifier.clip(shape)) else Artwork(tile.art, coverModifier, shape)
        Spacer(Modifier.height(10.dp))
        val align = if (tile.circle) Modifier.align(Alignment.CenterHorizontally) else Modifier
        Text(tile.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = align)
        Text(
            tile.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = LocalfyColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = align,
        )
    }
}

/**
 * Horizontal shelf that only ever shows whole tiles: the tile width is fitted to the available
 * width (so nothing is cut off at the edge or hinge), and flings snap a full tile at a time.
 */
@Composable
fun TileShelf(
    title: String,
    tiles: List<TileData>,
    tileWidth: Dp = 148.dp,
    eyebrow: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    if (tiles.isEmpty()) return
    Column {
        SectionHeader(title, eyebrow = eyebrow, action = action, onAction = onAction)
        FittedTileRow(tiles, key = { it.key }, tileWidth = tileWidth) { it }
    }
}

/** A horizontal row of tiles sized so only whole tiles are ever visible, snapping one tile at a time. */
@Composable
fun <T> FittedTileRow(items: List<T>, key: (T) -> Any, tileWidth: Dp, tile: (T) -> TileData?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Gap == edge padding, so the next tile starts exactly at the edge: never a sliver of a half tile.
        val side = 16.dp
        val gap = side
        val usable = maxWidth - side * 2
        val count = ((usable + gap) / (tileWidth + gap)).toInt().coerceAtLeast(2)
        val fitted = (usable - gap * (count - 1)) / count
        val state = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Box(Modifier.fillMaxWidth().hoverable(hover)) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = side),
                horizontalArrangement = Arrangement.spacedBy(gap),
                flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
            ) {
                items(items, key = key) { item -> tile(item)?.let { MediaTile(it, fitted, inset = false) } }
            }
            // Mice can't swipe sideways: page the shelf a screenful at a time with arrows on hover.
            val canBack by remember(state) { derivedStateOf { state.canScrollBackward } }
            val canForward by remember(state) { derivedStateOf { state.canScrollForward } }
            ShelfArrow(hovered && canBack, Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Scroll back", Modifier.align(Alignment.CenterStart).padding(start = 4.dp, bottom = 48.dp)) {
                scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - count).coerceAtLeast(0)) }
            }
            ShelfArrow(hovered && canForward, Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Scroll forward", Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, bottom = 48.dp)) {
                scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex + count).coerceAtMost(items.lastIndex.coerceAtLeast(0))) }
            }
        }
    }
}

@Composable
private fun ShelfArrow(visible: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.size(36.dp).shadow(8.dp, CircleShape).clip(CircleShape)
                .background(LocalfyColors.SurfaceHighest).pressable(pressedScale = 0.9f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = LocalfyColors.TextPrimary) }
    }
}

/** Compact two-column shortcut used at the top of Home (Spotify's "quick picks"). */
@Composable
fun QuickTile(tile: TileData, modifier: Modifier = Modifier, playing: Boolean = false) {
    Row(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(LocalfyColors.Tint)
            .pressable(onLongClick = tile.onLongClick, onClick = tile.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.cover != null) tile.cover.invoke(Modifier.size(58.dp)) else Artwork(tile.art, Modifier.size(58.dp).shadow(6.dp))
        Text(
            tile.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        if (playing) EqualizerBars(true, Modifier.padding(end = 10.dp), size = 14.dp)
    }
}

@Composable
fun BigPlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp, container: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    Box(
        modifier
            .size(size)
            .shadow(if (size < 50.dp) 0.dp else 12.dp, CircleShape, spotColor = container)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .pressable(pressedScale = if (enabled) 0.9f else 1f) { if (enabled) { haptics(HapticFeedbackType.ContextClick); onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            playing,
            transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
            label = "play-icon",
        ) { p ->
            Icon(
                if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (p) "Pause" else "Play",
                tint = if (container.luminance() > 0.4f) Color.Black else Color.White,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) LocalPalette.current.onBrand else LocalfyColors.TextPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else LocalfyColors.Tint)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

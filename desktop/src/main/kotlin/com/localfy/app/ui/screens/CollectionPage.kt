package com.localfy.app.ui.screens

import com.localfy.app.ui.theme.LocalThemeSettings
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

/** The same artwork, title, toolbar and scrolling header for every collection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionPage(
    title: String,
    kindLabel: String,
    subtitle: String,
    summary: String,
    art: ArtKey?,
    modifier: Modifier = Modifier,
    artShape: Shape = RoundedCornerShape(8.dp),
    artAspectRatio: Float = 1f,
    hero: Boolean = false,
    playing: Boolean = false,
    playEnabled: Boolean = true,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    shuffleActive: Boolean = false,
    headerActions: @Composable () -> Unit = {},
    cover: (@Composable (Modifier) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val app = LocalApp.current
    val artColor by rememberArtColor(art)
    val reduceMotion = LocalThemeSettings.current.reduceMotion
    val palette = LocalPalette.current
    val color = if (palette.isDark) artColor else lerp(artColor, Color.White, 0.55f)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 560.dp
        val headerPx = with(density) { (if (hero) { if (wide) 300.dp else 360.dp } else { if (wide) 300.dp else maxWidth * (if (artAspectRatio < 1f) 0.46f else 0.62f) / artAspectRatio + 52.dp }).toPx() }
        val collapse by remember(listState, headerPx) {
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
                                    val s = if (reduceMotion) 1f else 1f + collapse * 0.15f
                                    scaleX = s; scaleY = s; alpha = 1f - collapse
                                    translationY = if (reduceMotion) 0f else listState.firstVisibleItemScrollOffset * 0.5f
                                },
                            )
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to LocalfyColors.Background.copy(alpha = 0.95f))))
                            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Text(kindLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalfyColors.TextPrimary.copy(alpha = 0.8f))
                                Text(title, style = if (wide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp))
                    } else if (wide) {
                        Row(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                            val m = Modifier.width(if (artAspectRatio < 1f) 164.dp else 200.dp).aspectRatio(artAspectRatio).graphicsLayer { alpha = 1f - collapse }.shadow(24.dp, artShape)
                            if (cover != null) cover(m.clip(artShape)) else Artwork(art, m, artShape)
                            Spacer(Modifier.width(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(kindLabel.uppercase(), style = MaterialTheme.typography.labelMedium)
                                Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextPrimary.copy(alpha = 0.85f))
                                Text(summary, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 52.dp, bottom = 8.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                            val m = Modifier.fillMaxWidth(if (artAspectRatio < 1f) 0.46f else 0.62f).aspectRatio(artAspectRatio)
                                .graphicsLayer {
                                    val s = if (reduceMotion) 1f else 1f - collapse * 0.25f
                                    scaleX = s; scaleY = s; alpha = 1f - collapse * 1.2f
                                    translationY = if (reduceMotion) 0f else listState.firstVisibleItemScrollOffset * 0.6f
                                }
                                .shadow(28.dp, artShape, spotColor = Color.Black)
                            if (cover != null) cover(m.clip(artShape)) else Artwork(art, m, artShape)
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextPrimary.copy(alpha = 0.85f))
                            Text(
                                listOf(kindLabel, summary).filter { it.isNotBlank() }.joinToString(" • "),
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
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            headerActions()
                        }
                        if (onShuffle != null) IconButton(onClick = onShuffle, enabled = playEnabled) {
                            Icon(Icons.Rounded.Shuffle, "Shuffle play", tint = if (shuffleActive) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary, modifier = Modifier.size(28.dp))
                        }
                        if (onPlay != null) BigPlayButton(playing = playing, onClick = onPlay, enabled = playEnabled, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            content()
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
            AnimatedVisibility(barAlpha > 0.9f && onPlay != null, enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None else scaleIn() + fadeIn(), exit = if (reduceMotion) androidx.compose.animation.ExitTransition.None else scaleOut() + fadeOut()) {
                BigPlayButton(playing, { onPlay?.invoke() }, Modifier.padding(end = 8.dp), size = 42.dp, enabled = playEnabled)
            }
        }
    }
}

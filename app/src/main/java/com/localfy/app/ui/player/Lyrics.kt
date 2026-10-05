package com.localfy.app.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.Song
import com.localfy.app.data.lyrics.Lrc
import com.localfy.app.data.lyrics.Lyrics
import com.localfy.app.data.lyrics.LyricsState
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.components.fadingEdges
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.components.shimmer
import com.localfy.app.ui.theme.LocalfyColors

@Composable
fun rememberLyrics(song: Song?): LyricsState? {
    val app = LocalApp.current
    LaunchedEffect(song?.id) { song?.let { app.lyrics.request(it) } }
    val states by app.lyrics.states.collectAsStateWithLifecycle()
    return song?.let { states[it.id] }
}

/** Full lyrics: synced lines highlight and auto-follow playback; tap any line to jump there. */
@Composable
fun LyricsView(
    song: Song,
    modifier: Modifier = Modifier,
    lineStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
    showFooter: Boolean = true,
) {
    when (val state = rememberLyrics(song)) {
        null, LyricsState.Loading -> LyricsLoading(modifier.padding(contentPadding))
        is LyricsState.NotFound -> LyricsMissing(song, state.searchedOnline, modifier.padding(contentPadding))
        is LyricsState.Found -> if (state.lyrics.synced) {
            SyncedLyrics(song, state.lyrics, modifier, lineStyle, contentPadding, showFooter)
        } else {
            PlainLyrics(state.lyrics, modifier, lineStyle, contentPadding, showFooter)
        }
    }
}

@Composable
private fun SyncedLyrics(
    song: Song,
    lyrics: Lyrics,
    modifier: Modifier,
    lineStyle: TextStyle,
    contentPadding: PaddingValues,
    showFooter: Boolean,
) {
    val app = LocalApp.current
    val position = app.player.positionMs.collectAsStateWithLifecycle()
    // Position ticks 4×/s; only recompose when the highlighted line actually changes.
    val active by remember(lyrics) { derivedStateOf { Lrc.activeIndex(lyrics.lines, position.value + lyrics.offsetMs) } }
    val listState = rememberLazyListState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start || it is DragInteraction.Stop) lastUserScroll = System.currentTimeMillis() }
    }

    BoxWithConstraints(modifier) {
        val third = with(LocalDensity.current) { (maxHeight / 3).roundToPx() }
        LaunchedEffect(active) {
            if (active >= 0 && System.currentTimeMillis() - lastUserScroll > 3_000) {
                listState.animateScrollToItem(active, scrollOffset = -third)
            }
        }
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize().fadingEdges()) {
            itemsIndexed(lyrics.lines) { i, line ->
                val isActive = i == active
                val color by animateColorAsState(
                    when {
                        isActive -> Color.White
                        i < active -> Color.White.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.32f)
                    },
                    tween(300), label = "lyric-color",
                )
                val scale by animateFloatAsState(if (isActive) 1f else 0.96f, tween(300), label = "lyric-scale")
                Text(
                    line.text.ifBlank { "♪" },
                    style = lineStyle,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .clip(RoundedCornerShape(8.dp))
                        .pressable(pressedScale = 0.98f) { app.player.seekTo((line.timeMs - lyrics.offsetMs).coerceAtLeast(0)) }
                        .padding(vertical = 8.dp),
                )
            }
            if (showFooter) item { LyricsFooter(song, lyrics) }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier, lineStyle: TextStyle, contentPadding: PaddingValues, showFooter: Boolean) {
    LazyColumn(modifier.fadingEdges(), contentPadding = contentPadding) {
        item {
            Text(
                "These lyrics aren't time-synced.",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        itemsIndexed(lyrics.lines) { _, line ->
            Text(line.text, style = lineStyle, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(vertical = 4.dp))
        }
        if (showFooter) item { Text("Source: ${lyrics.source.label}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 24.dp)) }
    }
}

@Composable
private fun LyricsFooter(song: Song, lyrics: Lyrics) {
    val app = LocalApp.current
    Column(Modifier.padding(top = 32.dp)) {
        Text("Source: ${lyrics.source.label}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Timing", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            TextButton(onClick = { app.lyrics.setOffset(song, lyrics.offsetMs - 250) }) { Text("−0.25s", color = Color.White) }
            Text("%+.2fs".format(lyrics.offsetMs / 1000f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { app.lyrics.setOffset(song, lyrics.offsetMs + 250) }) { Text("+0.25s", color = Color.White) }
        }
    }
}

@Composable
private fun LyricsLoading(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(0.8f, 0.6f, 0.9f, 0.5f, 0.7f).forEach {
            Box(Modifier.fillMaxWidth(it).height(22.dp).clip(RoundedCornerShape(6.dp)).shimmer())
        }
    }
}

@Composable
private fun LyricsMissing(song: Song, searchedOnline: Boolean, modifier: Modifier) {
    val app = LocalApp.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Lyrics, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(if (searchedOnline) "No lyrics found" else "No lyrics on this device", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (searchedOnline) "LRCLIB doesn't have this one yet. You can drop a matching .lrc file in your lyrics folder."
            else "Spitify checked the file's tags and your lyrics folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White).pressable { app.lyrics.request(song, forceOnline = true) }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CloudDownload, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (searchedOnline) "Search again" else "Find lyrics online", color = Color.Black, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Spotify-style card under the player: a live peek at the lyrics that expands to full screen. */
@Composable
fun LyricsCard(song: Song, color: Color, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val state = rememberLyrics(song)
    val position = app.player.positionMs.collectAsStateWithLifecycle()
    val found = state as? LyricsState.Found
    val activeLine by remember(found) {
        derivedStateOf { if (found != null && found.lyrics.synced) Lrc.activeIndex(found.lyrics.lines, position.value + found.lyrics.offsetMs).coerceAtLeast(0) else 0 }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .pressable(pressedScale = 0.98f, onClick = onExpand)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.OpenInFull, "Show lyrics", modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(12.dp))
        when (state) {
            is LyricsState.Found -> {
                val lines = state.lyrics.lines
                val active = activeLine
                lines.drop(active).take(4).forEachIndexed { i, l ->
                    Text(
                        l.text.ifBlank { "♪" },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (i == 0 && state.lyrics.synced) Color.White else Color.White.copy(alpha = 0.5f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
            is LyricsState.NotFound -> Text(
                if (state.searchedOnline) "No lyrics found for this song." else "No lyrics on this device — tap to search online.",
                style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f),
            )
            else -> LyricsLoading(Modifier)
        }
    }
}


package com.localfy.app.ui.player

import com.localfy.app.ui.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.Song
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.onSecondaryClick
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.EqualizerBars
import com.localfy.app.ui.components.StatusBarScrim
import com.localfy.app.ui.components.fadingEdges
import com.localfy.app.ui.components.formatDuration
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.components.songCount
import com.localfy.app.ui.theme.LocalfyColors
import kotlin.math.roundToInt

private val QueueRowHeight = 62.dp

/** Live queue with tap-to-jump, remove, and long-press drag to reorder. */
@Composable
fun QueueList(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp), showCurrent: Boolean = true) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    val lookup = rememberSongLookup()
    val current = state.currentId?.let(lookup)
    val upNext = remember(state.queue, state.currentIndex, lookup) { state.upNext.mapNotNull { (i, id) -> lookup(id)?.let { i to it } } }
    fun queueGroup(index: Int) = when (index) { in state.manualQueueIndices -> 1; in state.autoplayQueueIndices -> 2; else -> 0 }
    val rowPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(modifier, contentPadding = contentPadding) {
        if (current != null && showCurrent) {
            item(key = "now-header") { QueueHeader("Now playing") }
            item(key = "now") { QueueRow(current, isCurrent = true, playing = state.isPlaying, onClick = {}) }
        }
        item(key = "next-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QueueHeader("Next up", Modifier.weight(1f))
                if (upNext.isNotEmpty()) TextButton(onClick = app.player::clearUpNext) { Text("Clear", color = LocalfyColors.TextSecondary) }
            }
        }
        if (upNext.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing queued. Use “Play next” or “Add to queue” from any song’s menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalfyColors.TextSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        itemsIndexed(upNext, key = { _, (i, s) -> "q-$i-${s.id}" }) { pos, (queueIndex, song) ->
            if (pos == 0 || queueGroup(upNext[pos - 1].first) != queueGroup(queueIndex)) {
                QueueHeader(when (queueGroup(queueIndex)) { 1 -> "Added by you"; 2 -> "Autoplay · similar music"; else -> state.source?.let { "Next from: $it" } ?: "From your playback list" })
            }
            val dragging = dragIndex == pos
            // The drag handler outlives recompositions (it's keyed on queueIndex), but this row's
            // position in "Next up" shifts every time a song finishes; always read the latest.
            val latestPos by rememberUpdatedState(pos)
            val latestUpNext by rememberUpdatedState(upNext)
            QueueRow(
                song = song,
                isCurrent = false,
                playing = false,
                onClick = { app.player.skipTo(queueIndex) },
                onRemove = { app.player.removeAt(queueIndex) },
                modifier = Modifier
                    .onSecondaryClick { app.openSongMenu(song, SongMenuExtras("Remove from queue") { app.player.removeAt(queueIndex) }) }
                    .animateItem()
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f; scaleX = if (dragging) 1.03f else 1f; scaleY = scaleX }
                    .background(if (dragging) LocalfyColors.SurfaceHighest else Color.Transparent, RoundedCornerShape(8.dp)),
                handleModifier = Modifier.pointerInput(queueIndex) {
                    detectDragGestures(
                        onDragStart = { dragIndex = latestPos; dragOffset = 0f },
                        onDragEnd = {
                            val pos = latestPos
                            val upNext = latestUpNext
                            val steps = (dragOffset / rowPx).roundToInt()
                            val group = upNext.indices.filter { queueGroup(upNext[it].first) == queueGroup(queueIndex) }
                            val target = if (group.isEmpty()) pos else (pos + steps).coerceIn(group.first(), group.last())
                            if (target != pos && target in upNext.indices) app.player.move(queueIndex, upNext[target].first)
                            dragIndex = -1; dragOffset = 0f
                        },
                        onDragCancel = { dragIndex = -1; dragOffset = 0f },
                    ) { change, amount -> change.consume(); dragOffset += amount.y }
                },
            )
        }
    }
}

@Composable
private fun QueueHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun QueueRow(
    song: Song,
    isCurrent: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    handleModifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(song.artKey, Modifier.size(46.dp), RoundedCornerShape(6.dp))
            if (isCurrent) Box(Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                EqualizerBars(playing, color = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = LocalfyColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove from queue", tint = LocalfyColors.TextSecondary) }
            Box(handleModifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = LocalfyColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun PlayerTopBar(source: String?, onClose: () -> Unit, onMore: () -> Unit, closeIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.KeyboardArrowDown) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(closeIcon, "Close player", modifier = Modifier.size(30.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Text(source ?: "Your library", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More") }
    }
}

/**
 * Full-screen player for the cover screen. The first "page" is the player itself; scrolling down
 * reveals Spotify-style cards: live lyrics, up next, about the artist and file credits.
 * [nestedScroll] lets the root turn an over-scroll at the top into collapsing the sheet.
 */
@Composable
fun NowPlayingFull(onCollapse: () -> Unit, nestedScroll: NestedScrollConnection? = null) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    val song = rememberCurrentSong()
    var overlay by rememberSaveable { mutableStateOf<String?>(null) } // "lyrics" | "queue"
    BackHandler { if (overlay != null) overlay = null else onCollapse() }
    val tint = rememberPlayerTint(song)
    val listState = rememberLazyListState()
    val collapseDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    val fallbackScroll = remember(onCollapse, collapseDistance) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0) {
                    pulled += available.y; return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val collapse = pulled >= collapseDistance || (pulled > collapseDistance * 0.4f && available.y > 1200f)
                pulled = 0f
                if (collapse) { onCollapse(); return available }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ArtBackdrop(song)
        if (song == null) {
            Column(Modifier.statusBarsPadding()) {
                PlayerTopBar(null, onCollapse, {})
                EmptyState("Nothing playing", "Pick a song from your library.")
            }
            return@Box
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageHeight = maxHeight
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll ?: fallbackScroll),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item(key = "player") {
                    Column(Modifier.height(pageHeight).statusBarsPadding().padding(top = 8.dp).navigationBarsPadding().padding(horizontal = 20.dp)) {
                        PlayerTopBar(state.source, onCollapse, { app.openSongMenu(song, SongMenuExtras()) })
                        ArtPager(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp))
                        TitleBlock(song, onArtist = { onCollapse(); app.navigate(Routes.artist(song.primaryArtist)) })
                        Spacer(Modifier.height(6.dp))
                        SeekBar()
                        TransportControls()
                        SecondaryControls(
                            onLyrics = { overlay = "lyrics" },
                            onQueue = { overlay = "queue" },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                item(key = "theme-art") { com.localfy.app.ui.theme.ThemeScene(compact = true) }
                if (song.isPodcast) item(key = "notes") { ShowNotesCard(song, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                else item(key = "lyrics") {
                    LyricsCard(song, lerp(tint, Color.Black, 0.1f), onExpand = { overlay = "lyrics" }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                item(key = "next") { UpNextCard(onOpen = { overlay = "queue" }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                if (!song.isPodcast) item(key = "about") { AboutArtistCard(song, onOpen = { onCollapse(); app.navigate(Routes.artist(song.primaryArtist)) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                item(key = "credits") { CreditsCard(song, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
        }


        AnimatedVisibility(overlay != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Box(Modifier.fillMaxSize().background(lerp(tint, Color.Black, 0.2f))) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp).navigationBarsPadding()) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { overlay = null }) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", modifier = Modifier.size(30.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                        }
                    }
                    AnimatedContent(overlay, modifier = Modifier.weight(1f), label = "overlay") { which ->
                        if (which == "queue") QueueList(Modifier.fillMaxSize())
                        else if (song.isPodcast) EpisodeNotes(song, Modifier.fillMaxSize())
                        else LyricsView(song, Modifier.fillMaxSize())
                    }
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        SeekBar()
                        TransportControls(large = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardShell(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.07f)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) {
                Text(
                    action, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).pressable(onClick = onAction).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun UpNextCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    val lookup = rememberSongLookup()
    val next = state.upNext.take(4).mapNotNull { (i, id) -> lookup(id)?.let { i to it } }
    CardShell("Next in queue", modifier, "Open queue", onOpen) {
        if (next.isEmpty()) Text("End of the queue.", color = LocalfyColors.TextSecondary)
        next.forEach { (i, s) ->
            Row(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { app.player.skipTo(i) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(s.artKey, Modifier.size(44.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1)
                }
                Text(formatDuration(s.durationMs), style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun AboutArtistCard(song: Song, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val stats by app.repo.stats.collectAsStateWithLifecycle()
    val artist = library.artistByName[song.primaryArtist] ?: return
    val plays = artist.songs.sumOf { stats[it.id]?.playCount ?: 0 }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.07f)).pressable(pressedScale = 0.98f, onClick = onOpen)) {
        Box {
            Artwork(artist.cover.artKey, Modifier.fillMaxWidth().height(180.dp))
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
            Text("About the artist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp))
        }
        Column(Modifier.padding(20.dp)) {
            Text(artist.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${artist.albums.size} album${if (artist.albums.size == 1) "" else "s"} • ${songCount(artist.songs.size)} on this device" + if (plays > 0) " • $plays plays by you" else "",
                style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
            )
        }
    }
}

/** Full show notes for the episode that's playing (podcast equivalent of the lyrics view). */
@Composable
fun EpisodeNotes(song: Song, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val shows by app.podcasts.shows.collectAsStateWithLifecycle()
    val show = shows.firstOrNull { s -> s.episodes.any { it.id == song.episodeId } }
    val episode = show?.episodes?.firstOrNull { it.id == song.episodeId }
    val notes = episode?.description?.ifBlank { null } ?: show?.podcast?.description?.ifBlank { null }
    LazyColumn(modifier.fadingEdges(), contentPadding = PaddingValues(24.dp)) {
        item { Text(song.title, style = MaterialTheme.typography.titleLarge) }
        item { Text(song.album, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) }
        item {
            Text(
                notes ?: if (song.isAudiobook) "No description for this book." else "No notes for this episode.",
                style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun ShowNotesCard(song: Song, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val shows by app.podcasts.shows.collectAsStateWithLifecycle()
    val episode = shows.asSequence().flatMap { it.episodes }.firstOrNull { it.id == song.episodeId }
    var expanded by rememberSaveable { mutableStateOf(false) }
    CardShell("Episode notes", modifier, if (expanded) "Less" else "More", { expanded = !expanded }) {
        Text(
            episode?.description?.ifBlank { null } ?: "No notes for this episode.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalfyColors.TextSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreditsCard(song: Song, modifier: Modifier = Modifier) {
    val kbps = if (song.durationMs > 0) song.sizeBytes * 8 / song.durationMs else 0
    CardShell("Credits", modifier) {
        listOfNotNull(
            "Artist" to song.artist,
            "Album" to song.album,
            song.year.takeIf { it > 0 }?.let { "Year" to it.toString() },
            song.genre?.let { "Genre" to it },
            "Format" to ((song.mimeType?.substringAfter('/')?.uppercase() ?: "Audio") + if (kbps > 0) " • ~$kbps kbps" else ""),
            "File" to "/${song.folder}${song.fileName}",
        ).forEach { (k, v) ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(v, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(k, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun PaneTabs(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tabs: List<String> = listOf("Playing", "Lyrics", "Queue")) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.25f)).padding(4.dp)) {
        tabs.forEachIndexed { i, t ->
            Text(
                t,
                style = MaterialTheme.typography.labelLarge,
                color = if (i == selected) Color.Black else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (i == selected) Color.White else Color.Transparent)
                    .pressable(pressedScale = 0.95f) { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** Right half of the open Fold: the always-on player with Playing / Lyrics / Queue tabs. */
@Composable
fun NowPlayingPane(onHide: () -> Unit, onTheater: () -> Unit, modifier: Modifier = Modifier, lightStatusStrip: Boolean = false) {
    val app = LocalApp.current
    val song = rememberCurrentSong()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Box(modifier.fillMaxHeight()) {
        ArtBackdrop(song, strength = 0.9f)
        // Light theme uses dark status icons; give them a light strip to sit on over the dark pane.

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp).navigationBarsPadding()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PaneTabs(tab, { tab = it }, tabs = listOf("Playing", if (song?.isPodcast == true) "Notes" else "Lyrics", "Queue"))
                Spacer(Modifier.weight(1f))
                if (song != null) IconButton(onClick = onTheater) { Icon(Icons.Rounded.OpenInFull, "Dual-screen player") }
                IconButton(onClick = onHide) { Icon(Icons.Rounded.VerticalSplit, "Hide player pane") }
            }
            if (song == null) {
                com.localfy.app.ui.theme.ThemeScene()
                EmptyState("Nothing playing", "Tap any song — it plays here while you keep browsing on the other half.")
                return@Column
            }
            val state = rememberPlayerState()
            // Starting something new on the left (a different queue) crossfades the whole player in,
            // rather than the artwork pages rebuilding and snapping.
            val queueKey = (state.source ?: "") + "|" + state.queue.firstOrNull()
            AnimatedContent(
                tab to queueKey,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (initialState.first != targetState.first) fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    else (fadeIn(tween(320, delayMillis = 60)) + scaleIn(tween(360), initialScale = 0.94f)) togetherWith fadeOut(tween(160))
                },
                contentKey = { it },
                label = "pane-tab",
            ) { (t, _) ->
                when (t) {
                    1 -> if (song.isPodcast) EpisodeNotes(song, Modifier.fillMaxSize()) else LyricsView(song, Modifier.fillMaxSize(), MaterialTheme.typography.headlineSmall)
                    2 -> QueueList(Modifier.fillMaxSize())
                    else -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        ArtPager(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
                        Spacer(Modifier.height(8.dp))
                        TitleBlock(song, onArtist = { app.navigate(Routes.artist(song.primaryArtist)) })
                        SeekBar()
                        TransportControls(large = false)
                        SecondaryControls(onLyrics = { tab = 1 }, onQueue = { tab = 2 })
                    }
                }
            }
        }
    }
}

/**
 * Dual-screen player for the open Fold: the left half is the player, the right half shows
 * lyrics or the queue. The hinge gap is respected so nothing sits in the crease.
 */
@Composable
fun TheaterPlayer(hingeLeft: Dp, hingeWidth: Dp, onExit: () -> Unit) {
    val app = LocalApp.current
    val song = rememberCurrentSong()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize()) {
        ArtBackdrop(song)
        Row(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp).navigationBarsPadding()) {
            Column(Modifier.width(hingeLeft).fillMaxHeight().padding(horizontal = 28.dp, vertical = 12.dp)) {
                PlayerTopBar(app.player.state.collectAsStateWithLifecycle().value.source, onExit, { song?.let { app.openSongMenu(it, SongMenuExtras()) } }, Icons.Rounded.CloseFullscreen)
                if (song == null) return@Column
                ArtPager(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
                TitleBlock(song, large = true, onArtist = { onExit(); app.navigate(Routes.artist(song.primaryArtist)) })
                SeekBar()
                TransportControls()
                SecondaryControls()
            }
            Spacer(Modifier.width(hingeWidth))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                PaneTabs(tab, { tab = it }, Modifier.padding(16.dp), listOf("Lyrics", "Up next"))
                if (song != null) AnimatedContent(tab, modifier = Modifier.weight(1f), label = "theater-tab") { t ->
                    if (t == 0 && song.isPodcast) EpisodeNotes(song, Modifier.fillMaxSize())
                    else if (t == 0) LyricsView(song, Modifier.fillMaxSize(), MaterialTheme.typography.headlineMedium, PaddingValues(horizontal = 32.dp, vertical = 24.dp))
                    else QueueList(Modifier.fillMaxSize())
                }
            }
        }
    }
}

package com.localfy.app.ui.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BedtimeOff
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.localfy.app.data.Song
import com.localfy.app.playback.AudioSessionHolder
import com.localfy.app.playback.EqStore
import com.localfy.app.playback.PlayerUiState
import com.localfy.app.playback.SleepTimer
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.art.rememberArtColor
import com.localfy.app.ui.components.Pill
import com.localfy.app.ui.components.formatDuration
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.components.rememberHaptics
import com.localfy.app.ui.theme.DarkSurface
import com.localfy.app.ui.theme.LocalThemeSettings
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.theme.PlayerStyle
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
fun rememberPlayerState(): PlayerUiState = LocalApp.current.player.state.collectAsStateWithLifecycle().value

/** Looks up anything that can be in the queue: songs, local podcast files and episodes. */
@Composable
fun rememberSongLookup(): (Long) -> Song? {
    val app = LocalApp.current
    val streams = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.localfy.app.LocalfyApp).musicStreams
    val library by app.repo.library.collectAsStateWithLifecycle()
    val local by app.repo.localPodcasts.collectAsStateWithLifecycle()
    val episodes by app.podcasts.episodeSongs.collectAsStateWithLifecycle()
    return remember(library, local, episodes) {
        val localById = local.associateBy { it.id }
        val fn: (Long) -> Song? = { id -> if (id < 0) streams.lookup(id) ?: episodes[id] else library.songById[id] ?: localById[id] }
        fn
    }
}

@Composable
fun rememberCurrentSong(): Song? {
    val state = rememberPlayerState()
    val lookup = rememberSongLookup()
    return state.currentId?.let(lookup)
}

/** Animated tint that follows the artwork of whatever is playing. */
@Composable
fun rememberPlayerTint(song: Song?): Color {
    val target by rememberArtColor(song?.artKey)
    val animated by animateColorAsState(target, tween(700), label = "tint")
    return animated
}

@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    val song = rememberCurrentSong() ?: return
    DarkSurface { MiniPlayerContent(song, state, onExpand, modifier) }
}

@Composable
private fun MiniPlayerContent(song: Song, state: PlayerUiState, onExpand: () -> Unit, modifier: Modifier) {
    val app = LocalApp.current
    val liked = song.id in app.repo.likedIds.collectAsStateWithLifecycle().value
    val position = app.player.positionMs.collectAsStateWithLifecycle()
    val tint = rememberPlayerTint(song)
    val haptics = rememberHaptics()

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .shadow(16.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(lerp(tint, Color.Black, 0.15f))
            .pressable(pressedScale = 0.98f, onClick = onExpand),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp).swipeToSkip(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(song, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "mini-art") { s ->
                Artwork(s.artKey, Modifier.size(42.dp), RoundedCornerShape(6.dp))
            }
            Spacer(Modifier.width(10.dp))
            AnimatedContent(
                song,
                transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut()) },
                modifier = Modifier.weight(1f),
                label = "mini-title",
            ) { s ->
                Column {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { app.addToPlaylist(listOf(song)) }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") }
            IconButton(onClick = { haptics(HapticFeedbackType.ContextClick); app.player.togglePlay() }) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(state.isPlaying, transitionSpec = { scaleIn() togetherWith scaleOut() }, label = "mini-play") { p ->
                        Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause" else "Play", modifier = Modifier.size(30.dp))
                    }
                    if (state.isBuffering) CircularProgressIndicator(Modifier.size(38.dp), color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp)
                }
            }
        }
        // Progress hairline along the bottom edge.
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp).fillMaxWidth().height(2.dp)) {
            val progress = if (state.durationMs > 0) (position.value / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            drawRect(Color.White.copy(alpha = 0.2f))
            drawRect(Color.White, size = androidx.compose.ui.geometry.Size(size.width * progress, size.height))
        }
    }
}

/**
 * Progress hairline along the mini player's bottom edge. Position is read only while drawing, so
 * the 4-per-second ticks redraw this line instead of recomposing the whole mini player.
 */
@Composable
private fun MiniProgress(durationMs: Long, modifier: Modifier) {
    val position = LocalApp.current.player.positionMs.collectAsStateWithLifecycle()
    Box(
        modifier.clip(CircleShape).drawBehind {
            drawRect(Color.White.copy(alpha = 0.2f))
            val progress = if (durationMs > 0) (position.value / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            drawRect(Color.White, size = size.copy(width = size.width * progress))
        },
    )
}

/** Horizontal swipe skips tracks, with rubber-band feedback. */
@Composable
fun Modifier.swipeToSkip(threshold: Dp = 80.dp): Modifier {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val offset = remember { Animatable(0f) }
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    return this
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val v = offset.value
                    if (v < -thresholdPx) { haptics(HapticFeedbackType.GestureEnd); app.player.next() }
                    else if (v > thresholdPx) { haptics(HapticFeedbackType.GestureEnd); app.player.previous() }
                    scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.6f)) }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f) } },
            ) { change, dragAmount ->
                change.consume()
                scope.launch { offset.snapTo(offset.value + dragAmount * 0.5f) }
            }
        }
}

/**
 * Swipeable artwork carousel over the real queue: neighbours peek in as you drag, letting go on a
 * different cover plays it. The current cover breathes down a little while paused.
 */
@Composable
fun ArtPager(modifier: Modifier = Modifier, cornerRadius: Dp = 10.dp) {
    val currentSong = rememberCurrentSong()
    val app = LocalApp.current
    val state = rememberPlayerState()
    val lookup = rememberSongLookup()
    val queue = state.queue
    if (queue.isEmpty() || state.currentIndex < 0) return
    val pager = rememberPagerState(initialPage = state.currentIndex) { queue.size }
    val haptics = rememberHaptics()

    LaunchedEffect(state.currentIndex, queue.size) {
        val target = state.currentIndex.coerceIn(0, queue.lastIndex)
        if (pager.currentPage != target) {
            if ((pager.currentPage - target).absoluteValue > 4) pager.scrollToPage(target)
            else pager.animateScrollToPage(target, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { page ->
            if (page != app.player.state.value.currentIndex) {
                haptics(HapticFeedbackType.GestureEnd)
                app.player.skipTo(page)
            }
        }
    }
    val musicStyle = LocalThemeSettings.current.playerStyle
    val style = if (currentSong?.isPodcast == true || currentSong?.isAudiobook == true) PlayerStyle.Artwork else musicStyle
    val still = LocalThemeSettings.current.reduceMotion
    val playingScale by animateFloatAsState(
        if (state.isPlaying || still || style == PlayerStyle.Vinyl) 1f else 0.88f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "breathe",
    )
    BoxWithConstraints(modifier) {
        val availableSide = minOf(maxWidth, maxHeight)
        HorizontalPager(
            state = pager,
            userScrollEnabled = style != PlayerStyle.Vinyl,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            key = { "$it-${queue.getOrNull(it)}" },
            verticalAlignment = Alignment.CenterVertically,
        ) { page ->
            val song = queue.getOrNull(page)?.let(lookup)
            val pageStyle = if (song?.isPodcast == true || song?.isAudiobook == true) PlayerStyle.Artwork else musicStyle
            val side = availableSide * if (pageStyle == PlayerStyle.Minimal) 0.72f else 1f
            val pageOffset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            val isCurrent = page == state.currentIndex
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val layer = Modifier
                    .size(side)
                    .graphicsLayer {
                        val s = lerp(1f, 0.85f, pageOffset) * if (isCurrent) playingScale else 1f
                        scaleX = s; scaleY = s
                        alpha = lerp(1f, 0.5f, pageOffset)

                    }
                if (pageStyle == PlayerStyle.Vinyl) {
                    if (isCurrent) ScrubbableRecord(song, layer) else VinylRecord(song, layer)
                } else {
                    Artwork(
                        song?.artKey,
                        if (pageStyle == PlayerStyle.Minimal) layer else layer.shadow(28.dp, RoundedCornerShape(cornerRadius), spotColor = Color.Black),
                        RoundedCornerShape(cornerRadius),
                        song?.album,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrubbableRecord(song: Song?, modifier: Modifier) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    val still = LocalThemeSettings.current.reduceMotion
    val spin = remember(song?.id) { Animatable(0f) }
    var dragging by remember(song?.id) { mutableStateOf(false) }
    var dragAngle by remember(song?.id) { mutableStateOf(0f) }
    LaunchedEffect(state.isPlaying, dragging, still) {
        if (state.isPlaying && !dragging && !still) while (true) {
            // A zero system animation scale otherwise makes this endless loop run without waiting.
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { kotlinx.coroutines.delay(250); continue }
            spin.animateTo(spin.value + 360f, tween(9_000, easing = LinearEasing))
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(modifier.pointerInput(song?.id, state.durationMs) {
        var previous = 0.0
        var total = 0.0
        var start = 0L
        var baseAngle = 0f
        var lastSeek = 0L
        fun angle(p: androidx.compose.ui.geometry.Offset) = kotlin.math.atan2((p.y - size.height / 2f).toDouble(), (p.x - size.width / 2f).toDouble())
        fun finish() {
            if (dragging) {
                app.player.seekTo(com.localfy.app.playback.VinylScrub.position(start, total, state.durationMs))
                val endAngle = dragAngle
                scope.launch { spin.snapTo(endAngle); dragging = false }
            }
        }
        detectDragGestures(onDragStart = { point ->
            if (state.durationMs > 0) {
                dragging = true; previous = angle(point); total = 0.0
                start = app.player.positionMs.value; baseAngle = spin.value; dragAngle = baseAngle; lastSeek = 0
            }
        }, onDragEnd = { finish() }, onDragCancel = { finish() }) { change, _ ->
            change.consume()
            if (dragging) {
                val dx = change.position.x - size.width / 2f; val dy = change.position.y - size.height / 2f
                val current = angle(change.position)
                if (dx * dx + dy * dy > size.width * size.width * 0.01f) {
                    total += com.localfy.app.playback.VinylScrub.delta(previous, current)
                    dragAngle = baseAngle + (total * 180 / kotlin.math.PI).toFloat()
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastSeek >= 60) { app.player.seekTo(com.localfy.app.playback.VinylScrub.position(start, total, state.durationMs)); lastSeek = now }
                }
                previous = current
            }
        }
    }.semantics {
        contentDescription = "Record. ${song?.album.orEmpty()} by ${song?.primaryArtist.orEmpty()}. Turn clockwise to move forward, or counterclockwise to rewind."
        customActions = listOf(
            androidx.compose.ui.semantics.CustomAccessibilityAction("Forward 10 seconds") { app.player.skipBy(10_000); true },
            androidx.compose.ui.semantics.CustomAccessibilityAction("Back 10 seconds") { app.player.skipBy(-10_000); true },
        )
    }) {
        VinylRecord(song, Modifier.fillMaxSize(), rotation = { (if (dragging) dragAngle else spin.value) % 360f })
    }
}

/** The light stays in place while the artwork's paper label turns beneath it. */
@Composable
private fun VinylRecord(song: Song?, modifier: Modifier, rotation: () -> Float = { 0f }) {
    Box(modifier.shadow(30.dp, CircleShape, spotColor = Color.Black).clip(CircleShape).drawWithCache {
        val radius = size.minDimension / 2
        val base = Brush.radialGradient(listOf(Color(0xFF252526), Color(0xFF080809), Color(0xFF171719)), radius = radius)
        val sheen = Brush.sweepGradient(
            0f to Color.Transparent, 0.10f to Color.White.copy(alpha = 0.025f),
            0.17f to Color.White.copy(alpha = 0.18f), 0.24f to Color.Transparent,
            0.55f to Color.Transparent, 0.66f to Color.White.copy(alpha = 0.12f),
            0.74f to Color.Transparent, 1f to Color.Transparent,
        )
        onDrawBehind {
            drawCircle(base, radius)
            repeat(74) { index ->
                val ring = radius * (0.96f - index * 0.0066f)
                val color = if (index % 5 == 0) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = if (index % 3 == 0) 0.09f else 0.045f)
                drawCircle(color, ring, style = androidx.compose.ui.graphics.drawscope.Stroke(if (index % 5 == 0) 0.8.dp.toPx() else 0.5.dp.toPx()))
            }
            drawCircle(sheen, radius)
            drawCircle(Color.Black.copy(alpha = 0.65f), radius * 0.49f, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.15f), radius - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.8f), radius - 3.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize(0.45f).graphicsLayer { rotationZ = rotation() }
            .shadow(1.5.dp, CircleShape, spotColor = Color.Black).clip(CircleShape).background(Color(0xFFE9DFC5)), contentAlignment = Alignment.Center) {
            Artwork(song?.artKey, Modifier.fillMaxSize().padding(2.dp), CircleShape)
            Box(Modifier.fillMaxSize().background(Color(0xFFE7CE9B).copy(alpha = 0.12f)).drawWithCache {
                val paper = androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(VinylPaperTexture.image,
                    androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated))
                onDrawBehind {
                    drawRect(paper, alpha = 0.22f)
                    val radius = size.minDimension / 2
                    drawCircle(Color.Black.copy(alpha = 0.20f), radius - 3.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.23f), radius * 0.38f, style = androidx.compose.ui.graphics.drawscope.Stroke(0.7.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.24f), radius * 0.36f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.1.dp.toPx()))
                }
            })
        }
        Box(Modifier.fillMaxSize(0.055f).clip(CircleShape).background(Color.Black.copy(alpha = 0.16f)))
        Box(Modifier.fillMaxSize(0.023f).clip(CircleShape).background(Color(0xFF08090A)).drawWithCache {
            onDrawBehind { drawCircle(Color.White.copy(alpha = 0.30f), size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(0.7.dp.toPx())) }
        })
    }
}

/** Build the faint paper fibres once; playback only rotates the already drawn label. */
private object VinylPaperTexture {
    val image by lazy {
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        val random = java.util.Random(0x51F17)
        repeat(1300) {
            val x = random.nextFloat() * 96; val y = random.nextFloat() * 96
            paint.color = if (random.nextBoolean()) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            paint.alpha = 33 + random.nextInt(57)
            canvas.drawRect(x, y, x + 0.35f + random.nextFloat() * 0.8f, y + 0.35f + random.nextFloat() * 0.6f, paint)
        }
        bitmap.asImageBitmap()
    }
}

/** Blurred, slowly drifting album art behind the player, darkened for legibility. */
@Composable
fun ArtBackdrop(song: Song?, modifier: Modifier = Modifier, strength: Float = 1f) {
    val tint = rememberPlayerTint(song)
    Box(modifier.fillMaxSize().clipToBounds().background(LocalfyColors.Background)) {
        if (song != null && Build.VERSION.SDK_INT >= 31 && LocalThemeSettings.current.blurBackdrop) {
            AnimatedContent(song.albumId, transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(900)) }, label = "backdrop") { _ ->
                Artwork(
                    song.artKey,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = 1.6f; scaleY = 1.6f; alpha = 0.55f * strength }
                        .blur(90.dp),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.85f),
                    0.55f to lerp(tint, Color.Black, 0.6f).copy(alpha = 0.9f),
                    1f to LocalfyColors.Background,
                ),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekBar(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    val position by app.player.positionMs.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shown = dragging ?: (position / duration.toFloat()).coerceIn(0f, 1f)
    val trackHeight by animateDpAsState(if (dragging != null) 7.dp else 4.dp, label = "track")

    Column(modifier) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { app.player.seekTo((it * duration).toLong()) }
                dragging = null
            },
            thumb = {
                val size by animateDpAsState(if (dragging != null) 18.dp else 12.dp, label = "thumb")
                Box(Modifier.size(size).shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White))
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(trackHeight),
                    colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.22f)),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            modifier = Modifier.height(28.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration((shown * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text("-" + formatDuration(duration - (shown * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun TransportControls(modifier: Modifier = Modifier, large: Boolean = true) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    val haptics = rememberHaptics()
    val main = if (large) 72.dp else 58.dp
    val side = if (large) 44.dp else 36.dp
    val podcast = rememberCurrentSong()?.isPodcast == true
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (podcast) ToggleIcon(Icons.Rounded.Replay10, "Back 10 seconds", false) { haptics(HapticFeedbackType.ContextClick); app.player.skipBy(-10_000) }
        else ToggleIcon(Icons.Rounded.Shuffle, "Shuffle", state.shuffle) { haptics(HapticFeedbackType.ToggleOn); app.player.toggleShuffle() }
        Icon(
            Icons.Rounded.SkipPrevious, "Previous",
            modifier = Modifier.size(side + 8.dp).clip(CircleShape).pressable(pressedScale = 0.85f) { haptics(HapticFeedbackType.ContextClick); app.player.previous() }.padding(4.dp),
        )
        Box(
            Modifier.size(main).shadow(18.dp, CircleShape).clip(CircleShape).background(Color.White)
                .pressable(pressedScale = 0.9f) { haptics(HapticFeedbackType.ContextClick); app.player.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                state.isPlaying,
                transitionSpec = { (scaleIn(initialScale = 0.5f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut()) },
                label = "play",
            ) { p ->
                Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(main * 0.55f))
            }
            // Streamed episodes can take a moment to start or re-buffer; show it instead of looking stuck.
            if (state.isBuffering) CircularProgressIndicator(Modifier.size(main - 6.dp), color = Color.Black.copy(alpha = 0.55f), strokeWidth = 3.dp)
        }
        Icon(
            Icons.Rounded.SkipNext, "Next",
            modifier = Modifier.size(side + 8.dp).clip(CircleShape).pressable(pressedScale = 0.85f) { haptics(HapticFeedbackType.ContextClick); app.player.next() }.padding(4.dp),
        )
        if (podcast) ToggleIcon(Icons.Rounded.Forward30, "Forward 30 seconds", false) { haptics(HapticFeedbackType.ContextClick); app.player.skipBy(30_000) }
        else ToggleIcon(
            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repeat",
            state.repeatMode != Player.REPEAT_MODE_OFF,
        ) { haptics(HapticFeedbackType.ToggleOn); app.player.cycleRepeat() }
    }
}

/** Icon that turns green with a dot underneath when active (Spotify's toggle style). */
@Composable
fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(CircleShape).pressable(pressedScale = 0.85f, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f))
        Box(Modifier.padding(top = 2.dp).size(4.dp).alpha(if (active) 1f else 0f).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
fun TitleBlock(song: Song, modifier: Modifier = Modifier, large: Boolean = false, onArtist: (() -> Unit)? = null) {
    val app = LocalApp.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                song,
                transitionSpec = { (slideInHorizontally { it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut()) },
                modifier = Modifier.weight(1f),
                label = "title",
            ) { s ->
                Text(
                    s.title,
                    style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!song.isPodcast) IconButton(onClick = { app.addToPlaylist(listOf(song)) }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).then(if (onArtist != null) Modifier.pressable(pressedScale = 0.98f, onClick = onArtist) else Modifier).padding(vertical = 12.dp),
            )
            if (!song.isPodcast && !song.isAudiobook) { ArtworkStyleButton() }
        }
    }
}

/** Sleep timer, playback (speed/crossfade/skip silence), equaliser, lyrics and queue. */
@Composable
fun SecondaryControls(
    modifier: Modifier = Modifier,
    onLyrics: (() -> Unit)? = null,
    lyricsSelected: Boolean = false,
    onQueue: (() -> Unit)? = null,
    queueSelected: Boolean = false,
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val state = rememberPlayerState()
    val sleep by app.player.sleepTimer.collectAsStateWithLifecycle()
    var showSleep by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }
    val eqLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val tweaked = state.speed != 1f || state.crossfadeMs > 0 || state.skipSilence

    Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ToggleIcon(if (sleep != null) Icons.Rounded.Bedtime else Icons.Rounded.BedtimeOff, "Sleep timer", sleep != null) { showSleep = true }
        ToggleIcon(Icons.Rounded.Tune, "Playback settings", tweaked) { showPlayback = true }
        val eqOn = EqStore.state.collectAsStateWithLifecycle().value.enabled
        ToggleIcon(Icons.Rounded.Equalizer, "Equaliser", eqOn) { app.openEqualizer() }
        if (onLyrics != null) ToggleIcon(Icons.Rounded.Lyrics, "Lyrics", lyricsSelected, onLyrics)
        if (onQueue != null) ToggleIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", queueSelected, onQueue)
    }
        val output by rememberAudioOutput()
        Text("Audio: $output", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.pressable(onClick = { showAudioOutputs(context) }).padding(horizontal = 8.dp, vertical = 12.dp))
    }

    if (showSleep) SleepTimerDialog(sleep, onDismiss = { showSleep = false })
    if (showPlayback) PlaybackDialog(onDismiss = { showPlayback = false })
}

@Composable
private fun SleepTimerDialog(current: SleepTimer?, onDismiss: () -> Unit) {
    val app = LocalApp.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalfyColors.SurfaceHigh,
        confirmButton = {},
        dismissButton = { if (current != null) TextButton(onClick = { app.player.setSleepTimer(null); onDismiss() }) { Text("Turn off") } },
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (current is SleepTimer.At) {
                    val left = ((current.endsAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                    Text("Stopping in about $left min — the last 10 seconds fade out.", color = LocalfyColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                }
                listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                    Text("$m minutes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().pressable { app.player.setSleepTimer(m); onDismiss() }.padding(vertical = 12.dp))
                }
                Text(
                    "End of track",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current == SleepTimer.EndOfTrack) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    modifier = Modifier.fillMaxWidth().pressable { app.player.sleepAtEndOfTrack(); onDismiss() }.padding(vertical = 12.dp),
                )
            }
        },
    )
}

@Composable
fun PlaybackDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalfyColors.SurfaceHigh,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Playback") },
        text = { PlaybackSettings() },
    )
}

/** Speed, crossfade and silence trimming - shared by the player dialog and Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSettings(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val state = rememberPlayerState()
    Column(modifier) {
        SwitchRow("Keep music playing", "Add more music when the queue runs low; repeat and sleep settings still apply", state.autoplay, app.player::setAutoplay)
        SwitchRow("Normalize volume", "Soften louder tracks while keeping your volume setting", state.normalizeAudio, app.player::setNormalizeAudio)
        Text("Speed", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                Pill("${s}×".replace(".0×", "×"), state.speed == s, { app.player.setSpeed(s) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Crossfade", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (state.crossfadeMs == 0) "Off" else "${state.crossfadeMs / 1000}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = state.crossfadeMs / 1000f,
            onValueChange = { app.player.setCrossfade((it.roundToInt() * 1000)) },
            valueRange = 0f..12f,
            steps = 11,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
        SwitchRow("Keep albums gapless", "Don't crossfade between consecutive tracks of the same album", state.crossfadeKeepAlbums, app.player::setCrossfadeKeepAlbums)
        SwitchRow("Skip silence", "Trim silent gaps inside and between tracks", state.skipSilence, app.player::setSkipSilence)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().pressable(pressedScale = 0.99f) { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

/** Dims content drawn underneath (used for legibility over blurred art). */
fun Modifier.scrim(alpha: Float): Modifier = drawWithContent {
    drawContent()
    drawRect(Color.Black.copy(alpha = alpha))
}

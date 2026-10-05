package com.localfy.app.ui.player

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.localfy.app.data.Song
import com.localfy.app.data.music.CanvasLookup
import com.localfy.app.data.music.ListeningCache
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey

/** Toggles Canvas (a looping clip of the song's music video) behind the player. */
@Composable
fun CanvasButton() {
    val actions = LocalApp.current
    val on = actions.musicVideoEnabled.value
    IconButton(onClick = { actions.toggleMusicVideo() }) {
        Icon(if (on) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (on) "Turn off Canvas" else "Turn on Canvas",
            tint = if (on) MaterialTheme.colorScheme.primary else Color.White)
    }
}

/**
 * Full-bleed Canvas behind the player: the album art first, then (if the song has a music video)
 * a silent 10-second loop of it, cropped to fill and graded so text stays readable. Plays only
 * while the song plays and the app is on screen; no web view, no audio decoding.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasBackdrop(song: Song?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = rememberPlayerState()
    val clip by produceState<String?>(null, song?.id) {
        value = null
        if (song != null && !song.isPodcast && !song.isAudiobook) value = CanvasLookup.find(context, song.title, song.primaryArtist)
    }
    var resumed by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var aspect by remember { mutableFloatStateOf(0f) }
    var shown by remember(clip) { mutableStateOf(false) }
    val softwareOnly = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val player = remember {
        val upstream = DefaultHttpDataSource.Factory().setUserAgent("Spitify/1.0 (Android)")
        // Only the bytes for the looped seconds are fetched, and they're cached for replays.
        val cached = CacheDataSource.Factory().setCache(ListeningCache.get(context)).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Decoder fallback: if the first video decoder can't take the clip, try the next one.
        val selector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
            val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (softwareOnly.get()) all.filter { it.softwareOnly }.ifEmpty { all } else all
        }
        ExoPlayer.Builder(context, androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector(selector))
            .setMediaSourceFactory(DefaultMediaSourceFactory(cached)).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onRenderedFirstFrame() { shown = true }
            // A hardware decoder that fails mid-stream gets one retry in software; after that the artwork stays.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                shown = false
                if (error.errorCode in androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED &&
                    softwareOnly.compareAndSet(false, true)) player.prepare()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(clip) {
        val url = clip
        if (url == null) { player.stop(); player.clearMediaItems(); return@LaunchedEffect }
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(CanvasLookup.LOOP_START_MS).setEndPositionMs(CanvasLookup.LOOP_END_MS).build(),
            ).build(),
        )
        player.prepare()
    }
    val active = clip != null && state.isPlaying && resumed
    LaunchedEffect(active) { player.playWhenReady = active }
    val videoAlpha by animateFloatAsState(if (shown && clip != null) 1f else 0f, tween(600), label = "canvas")

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
        if (song != null) Artwork(song.artKey, Modifier.fillMaxSize(), contentDescription = song.album)
        if (clip != null) {
            // Cover the screen like a portrait canvas: scale the video until both sides are filled.
            val (w, h) = fill(maxWidth, maxHeight, aspect.takeIf { it > 0f } ?: (16f / 9f))
            AndroidView(
                factory = { TextureView(it).also(player::setVideoTextureView) },
                onRelease = { player.clearVideoTextureView(it) },
                modifier = Modifier.requiredSize(w, h).graphicsLayer { alpha = videoAlpha },
            )
        }
        // Grade: soft vignette plus a darker top and bottom so the controls stay legible.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
    }
}

private fun fill(width: Dp, height: Dp, aspect: Float): Pair<Dp, Dp> =
    if (width / height > aspect) width to width / aspect else height * aspect to height

package com.localfy.app.ui.player

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.music.MusicVideo
import com.localfy.app.data.music.MusicVideoLookup
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

object VideoQuality {
    var resolution by mutableStateOf("No video played yet")
}

@Composable
fun MusicVideoButton() {
    val actions = LocalApp.current
    val enabled = actions.musicVideoEnabled
    IconButton(onClick = { actions.toggleMusicVideo() }) { Icon(Icons.Rounded.Videocam, if (enabled.value) "Show album art" else "Watch music video", tint = if (enabled.value) MaterialTheme.colorScheme.primary else Color.White) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MusicVideoBackdrop() {
    val app = LocalApp.current
    val song = rememberCurrentSong()
    val playback = rememberPlayerState()
    val latestPlayback by rememberUpdatedState(playback)
    val reduceMotion = com.localfy.app.ui.theme.LocalThemeSettings.current.reduceMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()
    val latestReduceMotion by rememberUpdatedState(reduceMotion)
    val syncScript by rememberUpdatedState("if(window.spitifySync) spitifySync(${app.player.positionMs.value / 1000.0},${playback.isPlaying},${playback.speed},${latestReduceMotion});")
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val latestVisible by rememberUpdatedState(visible)
    var video by remember { mutableStateOf<MusicVideo?>(null) }
    var alternatives by remember { mutableStateOf<List<MusicVideo>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    fun tryNextVideo() {
        if (alternatives.isNotEmpty()) {
            video = alternatives.first(); alternatives = alternatives.drop(1); loading = true; message = null
        } else { loading = false; message = "This video cannot play here. Your song is still playing." }
    }
    LaunchedEffect(webView, visible) {
        val view = webView ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        app.player.positionMs.collect { position ->
            val state = latestPlayback
            view.evaluateJavascript("if(window.spitifySync) spitifySync(${position / 1000.0},${state.isPlaying},${state.speed},${latestReduceMotion});", null)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) visible = true
            if (event == Lifecycle.Event.ON_PAUSE) { visible = false; webView?.evaluateJavascript("if(window.spitifySetVisible) spitifySetVisible(false);", null) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(song?.id) {
        val current = song
        if (current == null || current.isPodcast || current.isAudiobook) { return@LaunchedEffect }
        video = null; alternatives = emptyList(); loading = true; message = null
        try {
            val found = MusicVideoLookup.findAll(current.title, current.artist, current.durationMs.takeIf { it > 0 } ?: playback.durationMs)
            alternatives = found.drop(1); video = found.firstOrNull()
            if (video == null) { loading = false; message = "No matching music video is available for this song." }
        } catch (e: Exception) { if (e is CancellationException) throw e; loading = false; message = "The video couldn't load. Your song is still playing." }
    }
    LaunchedEffect(video?.id) {
        if (video == null) return@LaunchedEffect
        delay(25000)
        if (loading) tryNextVideo()
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        song?.let { Artwork(it.artKey, Modifier.fillMaxSize(), contentDescription = it.album) }
        val clip = video
        if (clip != null && message == null && visible) {
            key(clip.id) {
                AndroidView(factory = {
                    VideoWebCache.take(context, clip.id).apply {
                        var pageHandshakeSent = false
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false; settings.allowContentAccess = false; settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript("$syncScript if(window.spitifySetVisible) spitifySetVisible($latestVisible); if(window.spitifyBeginDisplay) spitifyBeginDisplay();", null)
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.hasGesture()
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(event: ConsoleMessage): Boolean {
                                // A provider subframe can delay onPageFinished. The first bridge
                                // message also confirms that the shared page can accept our state.
                                if (!pageHandshakeSent && event.message() in listOf("SPITIFY_VIDEO_READY", "SPITIFY_VIDEO_WAITING")) {
                                    pageHandshakeSent = true
                                    evaluateJavascript("$syncScript if(window.spitifySetVisible) spitifySetVisible($latestVisible); if(window.spitifyBeginDisplay) spitifyBeginDisplay();", null)
                                }
                                if (event.message().startsWith("SPITIFY_VIDEO_QUALITY:")) {
                                    val value = event.message().substringAfter("SPITIFY_VIDEO_QUALITY:")
                                    if (value.matches(Regex("[0-9]{1,5}x[0-9]{1,5}"))) VideoQuality.resolution = value.replace("x", " × ")
                                }
                                when (event.message()) {
                                    "SPITIFY_VIDEO_READY" -> if (video?.id == clip.id) loading = false
                                    "SPITIFY_VIDEO_WAITING" -> if (video?.id == clip.id) loading = true
                                    "SPITIFY_VIDEO_ERROR" -> if (video?.id == clip.id) tryNextVideo()
                                }
                                return true
                            }
                        }
                        evaluateJavascript("$syncScript if(window.spitifySetVisible) spitifySetVisible($latestVisible); if(window.spitifyBeginDisplay) spitifyBeginDisplay();", null)
                        webView = this
                    }
                }, modifier = Modifier.fillMaxSize().then(Modifier.graphicsLayer { alpha = if (loading) 0f else 1f }), onReset = null, onRelease = { view ->
                    view.webChromeClient = null; view.webViewClient = WebViewClient()
                    VideoWebCache.store(view, clip.id)
                    if (webView === view) webView = null
                }, update = { view -> view.evaluateJavascript("$syncScript if(window.spitifySetVisible) spitifySetVisible($latestVisible);", null) })
            }
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
    }
}

@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
internal object VideoWebCache {
    private val cache = VideoSurfaceCache<WebView> { it.stopLoading(); it.destroy() }
    fun prepare(context: android.content.Context, id: String) {
        cache.prepare(id) { make(context, id) }
    }
    fun take(context: android.content.Context, id: String): WebView {
        return cache.take(id) { make(context, id) }
    }
    fun store(view: WebView, id: String) {
        view.evaluateJavascript("if(window.spitifySetVisible) spitifySetVisible(false);", null)
        cache.store(view, id)
    }
    private fun make(context: android.content.Context, id: String): WebView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false; settings.allowContentAccess = false; settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("if(window.spitifySetVisible) spitifySetVisible(false);", null)
            }
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = context.assets.open("video-controls.js").bufferedReader().use { it.readText() }
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, script, setOf("https://www.youtube.com"))
        }
        val html = context.assets.open("music-video.html").bufferedReader().use { it.readText() }.replace("__VIDEO_ID__", id)
        loadDataWithBaseURL("https://${context.packageName.lowercase()}", html, "text/html", "UTF-8", null)
    }
}

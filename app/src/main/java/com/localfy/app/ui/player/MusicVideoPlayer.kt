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

@Composable
fun MusicVideoButton() {
    val enabled = LocalApp.current.musicVideoEnabled
    IconButton(onClick = { enabled.value = !enabled.value }) { Icon(Icons.Rounded.Videocam, if (enabled.value) "Show album art" else "Watch music video", tint = if (enabled.value) MaterialTheme.colorScheme.primary else Color.White) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MusicVideoBackdrop() {
    val app = LocalApp.current
    val song = rememberCurrentSong()
    val playback = rememberPlayerState()
    val position by app.player.positionMs.collectAsStateWithLifecycle()
    val syncScript by rememberUpdatedState("if(window.spitifySync) spitifySync(${position / 1000.0},${playback.isPlaying},${playback.speed});")
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var video by remember { mutableStateOf<MusicVideo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) visible = true
            if (event == Lifecycle.Event.ON_PAUSE) { visible = false; webView?.evaluateJavascript("if(window.spitifyStop) spitifyStop();", null) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(song?.id) {
        val current = song
        if (current == null || current.isPodcast || current.isAudiobook) { return@LaunchedEffect }
        video = null; loading = true; message = null
        try {
            video = MusicVideoLookup.find(current.title, current.artist, current.durationMs.takeIf { it > 0 } ?: playback.durationMs)
            if (video == null) { loading = false; message = "No matching music video is available for this song." }
        } catch (e: Exception) { if (e is CancellationException) throw e; loading = false; message = "The video couldn't load. Your song is still playing." }
    }
    LaunchedEffect(video?.id) {
        if (video == null) return@LaunchedEffect
        delay(25000)
        if (loading) { loading = false; message = "The video couldn't load. Your song is still playing." }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        song?.let { Artwork(it.artKey, Modifier.fillMaxSize(), contentDescription = it.album) }
        val clip = video
        if (clip != null && message == null && visible) {
            key(clip.id) {
                AndroidView(factory = {
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false; settings.allowContentAccess = false; settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) { view.evaluateJavascript(syncScript, null) }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.hasGesture()
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(event: ConsoleMessage): Boolean {
                                when (event.message()) {
                                    "SPITIFY_VIDEO_READY" -> loading = false
                                    "SPITIFY_VIDEO_ERROR" -> { loading = false; message = "This video cannot play here. Your song is still playing." }
                                }
                                return true
                            }
                        }
                        val html = context.assets.open("music-video.html").bufferedReader().use { it.readText() }.replace("__VIDEO_ID__", clip.id)
                        loadDataWithBaseURL("https://${context.packageName.lowercase()}", html, "text/html", "UTF-8", null)
                        webView = this
                    }
                }, modifier = Modifier.fillMaxSize(), onReset = null, onRelease = { view ->
                    view.evaluateJavascript("if(window.spitifyStop) spitifyStop();", null); view.stopLoading(); view.loadUrl("about:blank"); view.destroy()
                    if (webView === view) webView = null
                }, update = { view -> view.evaluateJavascript("if(window.spitifySync) spitifySync(${position / 1000.0},${playback.isPlaying && visible},${playback.speed});", null) })
            }
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.25f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.2f), 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.65f).padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (loading) { CircularProgressIndicator(); Text("Finding music video…", color = Color.White) }
            message?.let { Text(it, color = Color.White) }
        }
    }
}

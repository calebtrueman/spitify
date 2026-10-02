package com.localfy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import com.localfy.app.ui.LocalfyRoot
import com.localfy.app.ui.screens.Onboarding
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.theme.LocalfyTheme
import com.localfy.app.ui.theme.AccentSource
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.art.rememberArtAccent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val app get() = application as LocalfyApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Crashed last time: show the report before loading anything that might crash again.
        CrashReport.pending(this)?.let { report ->
            crashScreen = true
            setContent { CrashScreen(report, onSend = { CrashReport.share(this, report) }, onContinue = { CrashReport.dismiss(this); crashScreen = false; recreate() }) }
            return
        }
        setContent {
            val settings by app.theme.settings.collectAsStateWithLifecycle()
            // "Accent from album art": follow whatever is playing.
            val accentKey = if (settings.accentSource == AccentSource.Artwork) {
                val st by app.player.state.collectAsStateWithLifecycle()
                val lib by app.library.library.collectAsStateWithLifecycle()
                st.currentId?.let { app.resolve(it) ?: lib.songById[it] }?.artKey
            } else null
            val artAccent = rememberArtAccent(accentKey)
            val profile by app.profiles.profile.collectAsStateWithLifecycle()
            LocalfyTheme(settings, artAccent) {
                if (!profile.onboarded) {
                    Onboarding(onDone = {})
                    return@LocalfyTheme
                }
                PermissionGate {
                    LaunchedEffect(Unit) {
                        app.library.ensureStarted()
                        app.podcasts.start()
                        app.taste // starts the recommendation engine
                        app.player.connect()
                    }
                    AutomaticTagWritePermission(app)
                    LocalfyRoot(this@MainActivity)
                }
            }
        }
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    private var crashScreen = false

    override fun onStart() {
        super.onStart()
        if (!crashScreen) app.player.connect() // no-op if already connected
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with Spitify" from a file manager or another app. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            playVoiceRequest(intent)
            return
        }
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        lifecycleScope.launch {
            val id = if (uri.authority == MediaStore.AUTHORITY) uri.lastPathSegment?.toLongOrNull() else null
            val lib = withTimeoutOrNull(8_000) { app.library.library.first { !it.isEmpty } } ?: return@launch
            val song = id?.let { lib.songById[it] } ?: return@launch
            app.player.connect()
            withTimeoutOrNull(5_000) { app.player.state.first { it.connected } }
            app.player.playSongs(listOf(song), source = "Opened file")
        }
    }
    private fun playVoiceRequest(intent: Intent) {
        lifecycleScope.launch {
            fun explain(message: String) = android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
            if (ContextCompat.checkSelfPermission(this@MainActivity, audioPermission) != PackageManager.PERMISSION_GRANTED) {
                explain("Allow Spitify to read your music first.")
                return@launch
            }
            app.player.connect()
            if (withTimeoutOrNull(8_000) { app.player.state.first { it.connected } } == null) {
                explain("Spitify could not connect to the player. Please try again.")
                return@launch
            }
            val request = com.localfy.app.playback.AutoLibrary.voiceRequest(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty(), intent.extras)
            if (request.empty && app.player.state.value.hasMedia) {
                if (!app.player.state.value.isPlaying) app.player.togglePlay()
                return@launch
            }
            val selected = com.localfy.app.playback.AutoLibrary(this@MainActivity).voice(request)
            val first = selected.songs.firstOrNull()
            if (first == null) { explain("Nothing in your Spitify library matches that request."); return@launch }
            if (first.isAudiobook) app.player.playBook(selected.songs, 0, selected.source)
            else if (first.isPodcast) app.player.playEpisode(first, selected.source)
            else app.player.playSongs(selected.songs, shuffle = request.empty, source = selected.source)
        }
    }

}

private val audioPermission =
    if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun ComponentActivity.PermissionGate(content: @Composable () -> Unit) {
    fun granted() = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted()
    }
    LifecycleResumeEffect(Unit) {
        hasPermission = granted()
        onPauseOrDispose { }
    }
    if (hasPermission) {
        content()
        return
    }
    Column(
        Modifier.fillMaxSize().background(LocalfyColors.Background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(24.dp))
        Text("Your music. Your device.", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Spitify plays the songs already on your phone — no account, no streaming, no ads. Allow access to your audio files to build your library.",
            style = MaterialTheme.typography.bodyLarge,
            color = LocalfyColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                val perms = if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPermission)
                launcher.launch(perms)
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = com.localfy.app.ui.theme.LocalPalette.current.onBrand),
        ) { Text("Allow access to music") }
    }
}

@Composable
private fun CrashScreen(report: String, onSend: () -> Unit, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Spitify crashed", style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Sorry about that. Sending the report tells the developer exactly what went wrong. It only contains the error and your phone model, nothing from your library.",
            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB3B3B3), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(report.lineSequence().take(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Color(0xFF7A7A7A), maxLines = 6)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760), contentColor = Color.Black)) { Text("Send crash report") }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = onContinue) { Text("Open Spitify", color = Color.White) }
    }
}

/** Android grants writes to other apps' music files in one batch, never one prompt per song. */
@Composable
private fun AutomaticTagWritePermission(app: LocalfyApp) {
    val pending by app.metadata.pendingWrites.collectAsStateWithLifecycle()
    val fixing by app.metadata.fixing.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val asked = remember { mutableSetOf<android.net.Uri>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        app.metadata.finishAutomaticWrites(requested, result.resultCode == android.app.Activity.RESULT_OK)
        requested = emptyList()
    }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(pending, fixing, resumed, requested) {
        if (!resumed || fixing || requested.isNotEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        val batch = pending.filter { it !in asked }.take(2000)
        if (batch.isEmpty()) return@LaunchedEffect
        asked.addAll(batch)
        try {
            requested = batch
            val request = MediaStore.createWriteRequest(app.contentResolver, batch)
            launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
        } catch (_: Exception) { requested = emptyList() }
    }
}

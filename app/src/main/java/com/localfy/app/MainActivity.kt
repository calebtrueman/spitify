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
                    LocalfyRoot(this@MainActivity)
                }
            }
        }
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.player.connect() // no-op if already connected
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with Spitify" from a file manager or another app. */
    private fun handleViewIntent(intent: Intent?) {
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

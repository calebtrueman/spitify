package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfy.app.data.social.*
import com.localfy.app.ui.DesktopImages
import com.localfy.app.ui.FilePickers
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.LocalWindow
import com.localfy.app.ui.Routes
import com.localfy.app.ui.components.PageHeader
import com.localfy.app.ui.openInBrowser
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Reads a Spotify code from a picture (screenshot or photo) on this computer. */
@Composable
fun SpotifyCodeScanScreen() {
    val app = LocalApp.current
    val window = LocalWindow.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    fun read(file: File) {
        work?.cancel(); busy = true; message = null
        work = scope.launch {
            try {
                val ref = withContext(Dispatchers.IO) {
                    val image = DesktopImages.readBuffered(file) ?: error("This picture could not be opened. Choose another image.")
                    SpotifyCodeImageReader.read(image)
                } ?: error("No clear Spotify code found. Keep all the bars level and in view, avoid glare, and try a closer picture.")
                ensureActive()
                val target = SpotifyCodeLookup.resolve(ref)
                ensureActive()
                app.navigate(if (target.kind == "playlist") Routes.spotifyPlaylist(target.id) else "spotify-item/${target.kind}/${target.id}")
            } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message ?: "The code could not be opened. Try again." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Spotify code", onBack = { app.nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Scan a Spotify code", style = MaterialTheme.typography.headlineLarge)
                Text("Choose a screenshot or photo of the code. Keep the whole row of bars visible and level. We read the picture on this computer, then ask Spotify which item it belongs to.", color = LocalfyColors.TextSecondary)
                Button(onClick = { FilePickers.pickImage(window, "Choose a picture of a Spotify code")?.let(::read) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Choose a picture") }
                if (busy) { CircularProgressIndicator(); Text("Reading your code…") }
                message?.let { Text(it, color = LocalfyColors.TextSecondary) }
            }
        }
    }
}

@Composable
fun SpotifyScannedItemScreen(kind: String, id: String) {
    val app = LocalApp.current
    val target = SpotifyCodeTarget.parse("spotify:$kind:$id") ?: return
    var title by remember(target) { mutableStateOf("") }
    var loading by remember(target) { mutableStateOf(true) }
    LaunchedEffect(target) {
        try {
            title = withContext(Dispatchers.IO) {
                val c = URI("https://open.spotify.com/oembed?url=" + URLEncoder.encode(target.url, "UTF-8")).toURL().openConnection() as HttpURLConnection
                try { c.connectTimeout = 15_000; c.readTimeout = 15_000
                    if (c.responseCode != 200) "" else c.inputStream.bufferedReader().use { JSONObject(it.readText().take(1_000_000)).optString("title").take(200) }
                } finally { c.disconnect() }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Scanned item", onBack = { app.nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title.ifBlank { "Spotify $kind" }, style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = { openInBrowser(target.url) }) { Text("Open in Spotify") }
            if (loading) CircularProgressIndicator()
            else if (title.isNotEmpty() && kind != "user") {
                Text("Find in Spitify", style = MaterialTheme.typography.titleLarge)
                Text("Choose the matching result below. Availability can differ from Spotify.", color = LocalfyColors.TextSecondary)
                MixedSearchPanel(title)
            } else Text("This code was read successfully. Open the item in Spotify using the link above.", color = LocalfyColors.TextSecondary)
        }
    }
}

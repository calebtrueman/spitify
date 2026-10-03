package com.localfy.app.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.localfy.app.data.social.*
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.components.PageHeader
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

@Composable
fun SpotifyCodeScanScreen() {
    val context = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    val capture = remember { File(context.cacheDir, "spotify-code/capture.jpg").also { it.parentFile?.mkdirs() } }
    val cameraURI = remember { FileProvider.getUriForFile(context, context.packageName + ".codephotos", capture) }
    fun read(uri: Uri) {
        work?.cancel(); busy = true; message = null
        work = scope.launch {
            try {
                val ref = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "This photo could not be opened. Choose another image." }
                    val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight)/inSampleSize > 1600) inSampleSize *= 2 }
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("This photo could not be opened.")
                    try { SpotifyCodeImageReader.read(bitmap) } finally { bitmap.recycle() }
                } ?: error("No clear Spotify code found. Keep all the bars level and in view, avoid glare, and try a closer picture.")
                ensureActive()
                val target = SpotifyCodeLookup.resolve(ref)
                ensureActive()
                app.navigate(if (target.kind == "playlist") Routes.spotifyPlaylist(target.id) else "spotify-item/${target.kind}/${target.id}")
            } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message ?: "The code could not be opened. Try again." }
            finally { busy = false; if (uri == cameraURI) capture.delete() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> read(uri) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> if (success) read(cameraURI) else capture.delete() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Spotify code", onBack = { app.nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Scan a Spotify code", style = MaterialTheme.typography.headlineLarge)
                Text("Use a photo or take a picture. Keep the whole row of bars visible and level. We read the picture on your phone, then ask Spotify which item it belongs to.", color = LocalfyColors.TextSecondary)
                Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Choose a photo") }
                OutlinedButton(onClick = { runCatching { camera.launch(cameraURI) }.onFailure { message = "No camera app is available. Choose a photo instead." } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Take a picture") }
                if (busy) { CircularProgressIndicator(); Text("Reading your code…") }
                message?.let { Text(it, color = LocalfyColors.TextSecondary) }
            }
        }
    }
}

@Composable
fun SpotifyScannedItemScreen(kind: String, id: String) {
    val app = LocalApp.current
    val context = LocalContext.current
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
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url))) } }) { Text("Open in Spotify") }
            if (loading) CircularProgressIndicator()
            else if (title.isNotEmpty() && kind != "user") {
                Text("Find in Spitify", style = MaterialTheme.typography.titleLarge)
                Text("Choose the matching result below. Availability can differ from Spotify.", color = LocalfyColors.TextSecondary)
                MixedSearchPanel(title)
            } else Text("This code was read successfully. Open the item in Spotify using the link above.", color = LocalfyColors.TextSecondary)
        }
    }
}

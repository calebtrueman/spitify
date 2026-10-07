package com.localfy.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.data.social.DevicePlayback
import com.localfy.app.data.social.DeviceSync
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.components.BigPlayButton
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun rememberDeviceSync(): DeviceSync = (LocalContext.current.applicationContext as LocalfyApp).devices

fun platformIcon(platform: String): ImageVector = when (platform) {
    "android" -> Icons.Rounded.PhoneAndroid
    "ios" -> Icons.Rounded.PhoneIphone
    "macos" -> Icons.Rounded.LaptopMac
    "windows" -> Icons.Rounded.LaptopWindows
    else -> Icons.Rounded.Computer
}

/**
 * Above the mini player (or at the top of the player pane): "Playing on MacBook" while another
 * device plays and this one doesn't, otherwise the "Continue from …" card when there is one.
 */
@Composable
fun DeviceStrip(modifier: Modifier = Modifier) {
    val sync = rememberDeviceSync()
    val revision by sync.revision.collectAsStateWithLifecycle()
    val playing = rememberPlayerState().playWhenReady
    val shown = remember(revision, playing) { if (playing || sync.state.devices.isEmpty()) null else sync.shown() }
    val offer = remember(revision, playing) { if (playing) null else sync.offer }
    var sheet by remember { mutableStateOf<String?>(null) }
    when {
        shown != null -> PlayingOnBar(shown, onOpen = { sheet = shown.device }, onToggle = { sync.control(shown.device, if (shown.playing) "pause" else "play") }, modifier = modifier)
        offer != null -> ContinueCard(offer, sync, modifier)
    }
    sheet?.let { device -> RemoteDeviceSheet(device, onDismiss = { sheet = null }) }
}

@Composable
private fun PlayingOnBar(state: DevicePlayback, onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val song = state.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(palette.brand).clickable(onClick = onOpen).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Speaker, null, tint = palette.onBrand, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Playing on ${state.name}" + (song?.let { " · ${it.title} — ${it.artist}" } ?: ""),
            style = MaterialTheme.typography.labelLarge, color = palette.onBrand, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause on ${state.name}" else "Play on ${state.name}", tint = palette.onBrand)
        }
    }
}

@Composable
private fun ContinueCard(offer: DevicePlayback, sync: DeviceSync, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val song = offer.current ?: return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(LocalfyColors.SurfaceHigh).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(ArtKey(song.recordingKey.hashCode().toLong(), song.recordingKey.hashCode().toLong(), song.artwork), Modifier.size(42.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Continue from ${offer.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.title} · ${song.artist}", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { scope.launch { listen(sync, offer.device, context) } }) { Text("Play") }
        IconButton(onClick = sync::dismissOffer) { Icon(Icons.Rounded.Close, "Dismiss") }
    }
}

private suspend fun listen(sync: DeviceSync, device: String, context: android.content.Context) {
    try { sync.listenHere(device) } catch (e: Exception) {
        if (e is CancellationException) throw e
        android.widget.Toast.makeText(context, e.message ?: "This song couldn't be found here.", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The other device's song, progress and controls, with Listen here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDeviceSheet(device: String, onDismiss: () -> Unit) {
    val sync = rememberDeviceSync()
    val revision by sync.revision.collectAsStateWithLifecycle()
    val state = remember(revision) { sync.state.playback[device] }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val song = state?.current
    if (state == null || song == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    var position by remember { mutableLongStateOf(sync.expectedPosition(state)) }
    LaunchedEffect(state) { while (true) { position = sync.expectedPosition(state); if (!state.playing) break; delay(500) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LocalfyColors.SurfaceHigh, contentColor = LocalfyColors.TextPrimary) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(platformIcon(sync.state.devices[device]?.platform ?: state.platform), null, Modifier.size(18.dp), tint = LocalPalette.current.brand)
                Spacer(Modifier.width(6.dp))
                Text((if (state.playing) "Playing on " else "Paused on ") + state.name, style = MaterialTheme.typography.labelLarge, color = LocalPalette.current.brand)
            }
            Spacer(Modifier.height(16.dp))
            Artwork(ArtKey(song.recordingKey.hashCode().toLong(), song.recordingKey.hashCode().toLong(), song.artwork), Modifier.sizeIn(maxWidth = 260.dp).fillMaxWidth().aspectRatio(1f), RoundedCornerShape(10.dp))
            Spacer(Modifier.height(16.dp))
            Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.source?.let { Text("Playing from $it", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(12.dp))
            val duration = song.durationMs
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, enabled = duration > 0,
                onValueChange = { position = (it * duration).toLong() }, onValueChangeFinished = { sync.control(device, "seek", position) },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                if (duration > 0) Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = { sync.control(device, "previous") }) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                BigPlayButton(state.playing, onClick = { sync.control(device, if (state.playing) "pause" else "play") })
                IconButton(onClick = { sync.control(device, "next") }) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            if (!state.spoken) Button(enabled = !busy, onClick = { busy = true; scope.launch { try { listen(sync, device, context); onDismiss() } finally { busy = false } } }) {
                Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (busy) "Finding this song…" else "Listen here")
            } else Text("Podcasts and audiobooks keep playing on ${state.name}.", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
        }
    }
}

private fun formatTime(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Shown anywhere in the app when a device asks to link with this code. */
@Composable
fun DeviceApprovalDialog() {
    val sync = rememberDeviceSync()
    val revision by sync.revision.collectAsStateWithLifecycle()
    val request = remember(revision) { sync.approval } ?: return
    val (author, link) = request
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(platformIcon(link.platform), null) },
        title = { Text("Link ${link.name}?") },
        text = { Text("It will see what you play and can control playback.") },
        confirmButton = { TextButton(onClick = { sync.allow(author) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { sync.deny(author) }) { Text("Don't allow") } },
    )
}

package com.localfy.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.social.DevicePlayback
import com.localfy.app.data.social.DeviceSyncRepository
import com.localfy.app.data.social.DeviceSyncState
import com.localfy.app.data.social.LinkedDevice
import com.localfy.app.data.social.SharedTrack
import com.localfy.app.data.social.SocialRules
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.LocalContainer
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.components.formatDuration
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.delay

/** Settings › Your devices. */
const val DEVICES_ROUTE = "devices"

/** The device-sync repository, recomposing whenever it changes. */
@Composable
fun rememberDevices(): DeviceSyncRepository {
    val devices = LocalContainer.current.deviceSync
    devices.revision.collectAsStateWithLifecycle().value
    return devices
}

/** The current time, ticking every [everyMs] while [running] (so idle screens don't wake up). */
@Composable
fun rememberClock(running: Boolean, everyMs: Long = 1_000): Long {
    val now by produceState(SocialRules.now, running, everyMs) {
        value = SocialRules.now
        while (running) { delay(everyMs); value = SocialRules.now }
    }
    return now
}

fun platformIcon(platform: String): ImageVector = when (platform) {
    "android" -> Icons.Rounded.PhoneAndroid
    "ios" -> Icons.Rounded.PhoneIphone
    "macos" -> Icons.Rounded.LaptopMac
    "windows" -> Icons.Rounded.DesktopWindows
    else -> Icons.Rounded.Computer
}

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
fun ago(time: Long, now: Long = SocialRules.now): String {
    val minutes = ((now - time) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** Playing (fresh and not paused) on that device, as other screens judge it. */
fun DeviceSyncRepository.isPlaying(id: String, now: Long): Boolean {
    val state = playback(id) ?: return false
    return state.playing && state.current != null && now - (receivedAt(id) ?: 0) <= DeviceSyncState.FRESH
}

/** "Playing: Song" or "Last active 3 h ago". */
fun DeviceSyncRepository.status(device: LinkedDevice, now: Long): String {
    if (isPlaying(device.id, now)) return "Playing: " + playback(device.id)?.current?.title
    val seen = receivedAt(device.id) ?: return "Linked ${ago(device.linkedAt, now)}"
    return "Last active ${ago(seen, now)}"
}

@Composable
private fun TrackArt(track: SharedTrack?, modifier: Modifier, corner: Int = 6) {
    val key = remember(track?.artwork, track?.title) { ArtKey(0, ((track?.title ?: "") + (track?.album ?: "")).hashCode().toLong(), track?.artwork) }
    Artwork(key, modifier, RoundedCornerShape(corner.dp))
}

/**
 * "Playing on MacBook · Song — Artist": a thin accent bar while another of your devices plays and
 * this one doesn't. Click it for that device's controls and Listen here.
 */
@Composable
fun PlayingOnBar(modifier: Modifier = Modifier) {
    val devices = rememberDevices()
    val localPlaying = rememberPlayerState().isPlaying
    val now = rememberClock(devices.devices.isNotEmpty() && !localPlaying)
    val shown = if (localPlaying) null else devices.shown(now)
    var open by remember { mutableStateOf(false) }
    AnimatedVisibility(shown != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        val state = shown ?: return@AnimatedVisibility
        val track = state.current
        val accent = MaterialTheme.colorScheme.primary
        val ink = MaterialTheme.colorScheme.onPrimary
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(accent)
                .clickable { open = true }.padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Speaker, null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Playing on ${state.name}" + (track?.let { " · ${it.title} — ${it.artist}" } ?: ""),
                style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { devices.control(state.device, if (state.playing) "pause" else "play") }, modifier = Modifier.size(36.dp)) {
                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause on ${state.name}" else "Play on ${state.name}", tint = ink)
            }
        }
    }
    if (open && shown != null) RemoteDeviceDialog(shown.device, onDismiss = { open = false })
}

/** Another device's song, progress and controls, with Listen here. */
@Composable
fun RemoteDeviceDialog(device: String, onDismiss: () -> Unit) {
    val devices = rememberDevices()
    val now = rememberClock(true, 500)
    val state = devices.playback(device) ?: return onDismiss()
    val track = state.current ?: return onDismiss()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = LocalfyColors.SurfaceHigh, modifier = Modifier.widthIn(min = 340.dp, max = 400.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(platformIcon(state.platform), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Playing on ${state.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                TrackArt(track, Modifier.size(220.dp), corner = 10)
                Spacer(Modifier.height(16.dp))
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.source?.let { Text("From $it", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(14.dp))
                val position = devices.position(state, now)
                val duration = track.durationMs
                LinearProgressIndicator(
                    progress = { if (duration > 0) (position / duration.toFloat()).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = LocalfyColors.TextPrimary, trackColor = LocalfyColors.TextPrimary.copy(alpha = 0.2f), drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(formatDuration(position), style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
                    Spacer(Modifier.weight(1f))
                    if (duration > 0) Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
                }
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(onClick = { devices.control(device, "previous") }) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(LocalfyColors.TextPrimary)
                            .pressable(pressedScale = 0.9f) { devices.control(device, if (state.playing) "pause" else "play") },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", tint = LocalfyColors.Background, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { devices.control(device, "next") }) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp)) }
                }
                if (state.spoken) Text("Podcasts and audiobooks stay on ${state.name}.", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
                else Button(onClick = { devices.listenHere(device); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Speaker, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Listen here")
                }
            }
        }
    }
}

/** "Continue from MacBook — Song · Artist" with Play and ✕, where the player sits. */
@Composable
fun ContinueCard(modifier: Modifier = Modifier) {
    val devices = rememberDevices()
    val offer = devices.continueOffer
    AnimatedVisibility(offer?.current != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        val state = offer ?: return@AnimatedVisibility
        val track = state.current ?: return@AnimatedVisibility
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(LocalfyColors.SurfaceHighest).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArt(track, Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue from ${state.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.title} · ${track.artist}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { devices.playContinue() }, contentPadding = ButtonDefaults.ContentPadding) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text("Play") }
            IconButton(onClick = { devices.dismissContinue() }) { Icon(Icons.Rounded.Close, "Dismiss") }
        }
    }
}

/** "Link <name>?": any screen, as soon as a device that typed our code asks. */
@Composable
fun LinkRequestDialog() {
    val devices = rememberDevices()
    val (author, request) = devices.requests.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(platformIcon(request.platform), null) },
        title = { Text("Link ${request.name}?") },
        text = { Text("It will see what you play and can control playback.") },
        confirmButton = { Button(onClick = { devices.approve(author) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { devices.decline(author) }) { Text("Don't allow") } },
    )
}

/**
 * Spotify's Connect button: this computer and your linked devices (Playing / Last active), and
 * "Link a device". [tint] suits the dark player surfaces it sits on.
 */
@Composable
fun DevicesButton(modifier: Modifier = Modifier, tint: Color = Color.White.copy(alpha = 0.85f)) {
    val app = LocalApp.current
    val devices = rememberDevices()
    var open by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<String?>(null) }
    val now = rememberClock(open || devices.devices.isNotEmpty(), 5_000)
    val anyPlaying = devices.devices.any { devices.isPlaying(it.id, now) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Devices, "Devices", tint = if (anyPlaying && !rememberPlayerState().isPlaying) MaterialTheme.colorScheme.primary else tint)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(12.dp), containerColor = LocalfyColors.SurfaceHigh) {
            Column(Modifier.widthIn(min = 280.dp, max = 340.dp).padding(vertical = 4.dp)) {
                Text("Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                val localPlaying = rememberPlayerState().isPlaying
                DeviceRow(platformIcon(devices.platform), devices.name, if (localPlaying) "Listening on this computer" else "This computer", highlight = localPlaying)
                devices.devices.forEach { device ->
                    val playing = devices.isPlaying(device.id, now)
                    DeviceRow(platformIcon(device.platform), device.name, if (playing) "Playing" else devices.status(device, now), highlight = playing,
                        onClick = if (devices.playback(device.id)?.current != null) ({ open = false; remote = device.id }) else null)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = LocalfyColors.TextPrimary.copy(alpha = 0.08f))
                DeviceRow(Icons.Rounded.Link, "Link a device", "Show or enter a code", onClick = { open = false; app.navigate(DEVICES_ROUTE) })
            }
        }
    }
    remote?.let { RemoteDeviceDialog(it, onDismiss = { remote = null }) }
}

@Composable
private fun DeviceRow(icon: ImageVector, title: String, subtitle: String, highlight: Boolean = false, onClick: (() -> Unit)? = null) {
    val green = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (highlight) green else LocalfyColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (highlight) green else LocalfyColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (highlight) green else LocalfyColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

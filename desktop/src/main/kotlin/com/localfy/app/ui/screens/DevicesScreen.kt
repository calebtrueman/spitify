package com.localfy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfy.app.data.social.DevicePairing
import com.localfy.app.data.social.DeviceSyncState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.LocalContainer
import com.localfy.app.ui.player.ago
import com.localfy.app.ui.components.PageHeader
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.insetItem
import com.localfy.app.ui.player.isPlaying
import com.localfy.app.ui.player.platformIcon
import com.localfy.app.ui.player.rememberClock
import com.localfy.app.ui.player.rememberDevices
import com.localfy.app.ui.player.status
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.typingFocus

/** Settings › Your devices: this computer's name, linked devices, and linking by code. */
@Composable
fun DevicesScreen() {
    val actions = LocalApp.current
    val devices = rememberDevices()
    val pairing = devices.pairing
    val now = rememberClock(true)
    var name by remember(devices.name) { mutableStateOf(devices.name) }
    var entering by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { PageHeader("Your devices", onBack = { actions.nav.popBackStack() }) }
        insetItem {
            Text(
                "Link your phone, tablet and other computers to see what's playing on each, control them from here, and continue where you left off.",
                style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
            )
        }

        item { SectionHeader("This device") }
        insetItem {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(platformIcon(devices.platform), null, tint = LocalfyColors.TextSecondary)
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { devices.setName(name) }),
                    modifier = Modifier.widthIn(max = 420.dp).weight(1f, fill = false).typingFocus(),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { devices.setName(name) }, enabled = name.trim() != devices.name) { Text("Save") }
            }
        }

        item { SectionHeader("Linked devices") }
        if (devices.devices.isEmpty()) insetItem {
            Text("No devices linked yet.", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
        }
        items(devices.devices, key = { "device:" + it.id }) { device ->
            val playing = devices.isPlaying(device.id, now)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(platformIcon(device.platform), null, tint = if (playing) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        devices.status(device, now), style = MaterialTheme.typography.bodyMedium,
                        color = if (playing) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { devices.remove(device.id) }) { Icon(Icons.Rounded.Close, "Remove ${device.name}") }
            }
        }

        if (devices.devices.isNotEmpty()) {
            item { SectionHeader("Library") }
            insetItem { LibrarySyncStatusRow(now) }
        }

        item { SectionHeader("Link a device") }
        insetItem {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (pairing) {
                    is DevicePairing.Showing -> {
                        val left = (pairing.expiresAt - now).coerceAtLeast(0) / 1000
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LocalfyColors.SurfaceHigh).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(DeviceSyncState.displayCode(pairing.code), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = 4.sp)
                            Text("Expires in %d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.labelLarge, color = LocalfyColors.TextSecondary)
                            Text("On your other device, open Settings › Your devices › Enter code.", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = devices::cancelPairing) { Text("Cancel") }
                    }
                    DevicePairing.Looking -> Busy("Looking for that code…")
                    is DevicePairing.Waiting -> {
                        Busy("Waiting for ${pairing.name} to allow this device…")
                        TextButton(onClick = devices::cancelPairing) { Text("Cancel") }
                    }
                    else -> {
                        when (pairing) {
                            is DevicePairing.Failed -> Text(pairing.message, color = MaterialTheme.colorScheme.error)
                            is DevicePairing.Linked -> Text("Linked with ${pairing.name}", color = MaterialTheme.colorScheme.primary)
                            else -> {}
                        }
                        if (entering) {
                            fun submit() { if (typed.isNotBlank()) { devices.enterCode(typed); entering = false; typed = "" } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    typed, { typed = it.uppercase().take(12) }, label = { Text("Code from your other device") }, singleLine = true,
                                    placeholder = { Text("K7QX M2PA") },
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { submit() }),
                                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.widthIn(max = 320.dp).typingFocus(),
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { submit() }, enabled = typed.isNotBlank()) { Text("Link") }
                                TextButton(onClick = { entering = false; typed = "" }) { Text("Cancel") }
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = devices::showCode) { Text("Link a device") }
                            OutlinedButton(onClick = { entering = true }) { Text("Enter code") }
                        }
                    }
                }
                Text(
                    "Devices you link see what you play and can control playback. Sent end-to-end encrypted through the same relays as friend shares.",
                    style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextTertiary,
                )
            }
        }
        if (devices.devices.isNotEmpty()) insetItem {
            TextButton(onClick = devices::leave, modifier = Modifier.padding(top = 16.dp)) { Text("Leave this group", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** "Library: in sync" or "syncing N items…", when it last synced, and songs that couldn't be found here. */
@Composable
private fun LibrarySyncStatusRow(now: Long) {
    val sync = LocalContainer.current.librarySync
    val status by sync.status.collectAsStateWithLifecycle()
    var showMissing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status.syncing > 0) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (status.syncing > 0) "Library: syncing ${status.syncing} ${if (status.syncing == 1) "item" else "items"}…" else "Library: in sync",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (status.lastSync > 0) "Last synced ${ago(status.lastSync, now)}" else "Likes, playlists, follows, history and settings stay the same on your devices.",
                    style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
                )
            }
        }
        if (status.unmatched.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showMissing = !showMissing }) { Text("Couldn't find on this computer (${status.unmatched.size})") }
                TextButton(onClick = sync::retryUnmatched) { Text("Retry") }
            }
            if (showMissing) status.unmatched.take(100).forEach { track ->
                Text(
                    "${track.title} · ${track.artist}", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

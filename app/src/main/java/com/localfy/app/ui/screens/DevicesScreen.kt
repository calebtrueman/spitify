package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.social.DeviceSyncState
import com.localfy.app.data.social.LinkedDevice
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.components.PageHeader
import com.localfy.app.ui.components.insetItem
import com.localfy.app.ui.player.platformIcon
import com.localfy.app.ui.player.rememberDeviceSync
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings › Your devices: this device's name, linked devices, and linking with a code. */
@Composable
fun DevicesScreen() {
    val actions = LocalApp.current
    val sync = rememberDeviceSync()
    val revision by sync.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(sync.name) }
    var entering by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<LinkedDevice?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val devices = remember(revision) { sync.devices.sortedBy { it.name.lowercase() } }
    val code = remember(revision) { sync.code }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(code, devices.size) { while (true) { now = System.currentTimeMillis(); delay(if (code != null) 1_000 else 30_000) } }
    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Your devices", onBack = { actions.nav.popBackStack() }) }
        insetItem {
            Text("This device", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                if (name.trim() != sync.name && name.isNotBlank()) TextButton(onClick = { sync.rename(name); name = sync.name }) { Text("Save") }
            }
        }
        insetItem { Text("Linked devices", style = MaterialTheme.typography.titleMedium) }
        if (devices.isEmpty()) insetItem { Text("No devices linked yet. Link your other phone, tablet or computer to see what it's playing and continue where you left off.", color = LocalfyColors.TextSecondary) }
        items(devices, key = { it.id }) { device ->
            val playback = sync.state.playback[device.id]
            val heard = sync.receivedAt[device.id]
            val playingNow = playback?.playing == true && sync.active(now)?.device == device.id
            ListItem(
                headlineContent = { Text(device.name) },
                supportingContent = {
                    Text(
                        when {
                            playingNow -> "Playing: ${playback?.current?.title.orEmpty()}"
                            heard != null -> "Last seen ${ago(now - heard)}"
                            else -> "Linked ${ago(now - device.linkedAt)}"
                        },
                        color = if (playingNow) LocalPalette.current.brand else LocalfyColors.TextSecondary,
                    )
                },
                leadingContent = { Icon(platformIcon(device.platform), null) },
                trailingContent = { IconButton(onClick = { removing = device }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove ${device.name}") } },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        if (devices.isNotEmpty()) insetItem { LibrarySyncSection(now) }
        insetItem {
            if (code != null) {
                val left = (sync.codeIssuedAt + DeviceSyncState.CODE_LIFETIME - now).coerceAtLeast(0) / 1000
                Text("Link a device", style = MaterialTheme.typography.titleMedium)
                Text(DeviceSyncState.displayCode(code), style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp), modifier = Modifier.padding(vertical = 8.dp))
                Text("On your other device, open Settings › Your devices › Enter code.")
                Text("This code works for %d:%02d.".format(left / 60, left % 60), color = LocalfyColors.TextSecondary)
                TextButton(onClick = sync::hideCode) { Text("Cancel") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { entering = false; sync.showCode() }) { Text("Link a device") }
                OutlinedButton(onClick = { entering = !entering }) { Text("Enter code") }
            }
        }
        if (entering && code == null) insetItem {
            OutlinedTextField(typed, { typed = it.take(12) }, label = { Text("Code from your other device") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
            Button(enabled = DeviceSyncState.normalizeCode(typed).length == DeviceSyncState.TOKEN_LENGTH && !sync.entering, onClick = { scope.launch { sync.enterCode(typed) } }) { Text("Link") }
        }
        sync.pairingMessage?.let { message -> insetItem { Text(message) } }
        if (devices.isNotEmpty()) insetItem { TextButton(onClick = { leaving = true }) { Text("Leave this group") } }
        insetItem { Text("Devices you link see what you play and can control playback. Sent end-to-end encrypted through the same relays as friend shares.", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary) }
    }
    removing?.let { device ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${device.name}?") }, text = { Text("It stops seeing what you play here. You can link it again with a new code.") },
            confirmButton = { TextButton(onClick = { sync.remove(device.id); removing = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("Leave this group?") }, text = { Text("This device unlinks from all your other devices.") },
        confirmButton = { TextButton(onClick = { sync.leave(); leaving = false }) { Text("Leave") } }, dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } })
}

/** "Library: in sync" or "syncing N items…", and the songs from other devices that weren't found here. */
@Composable
private fun LibrarySyncSection(now: Long) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.localfy.app.LocalfyApp
    val library = remember { app.librarySync }
    val status by library.status.collectAsStateWithLifecycle()
    var showMissing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when {
                !status.started -> "Library: getting ready…"
                status.syncing > 0 -> "Library: syncing ${status.syncing} ${if (status.syncing == 1) "item" else "items"}…"
                else -> "Library: in sync"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (status.lastSync > 0) Text("Last synced ${ago(now - status.lastSync)}", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
        if (status.unmatched.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showMissing = !showMissing }, modifier = Modifier.weight(1f)) { Text("Couldn't find on this device (${status.unmatched.size})", modifier = Modifier.fillMaxWidth()) }
                OutlinedButton(onClick = { library.retry() }) { Text("Retry") }
            }
            if (showMissing) status.unmatched.take(200).forEach { track ->
                Text("${track.title} — ${track.artist}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

private fun ago(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

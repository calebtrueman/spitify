package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.music.FlacConversion
import com.localfy.app.ui.LocalContainer
import com.localfy.app.ui.formatFileSize
import com.localfy.app.ui.theme.LocalfyColors

/** Settings › Library: convert existing FLAC files to AAC (asks first; nothing happens on its own). */
@Composable
fun FlacConversionRow() {
    val app = LocalContainer.current
    val conversion = app.flacConversion
    val state by conversion.state.collectAsStateWithLifecycle()
    val library by app.library.library.collectAsStateWithLifecycle() // recount after rescans
    val flacs = remember(library) { conversion.candidates() }
    var confirming by remember { mutableStateOf(false) }
    fun size(bytes: Long) = formatFileSize(bytes)

    when (val s = state) {
        is FlacConversion.State.Converting -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Converting FLAC to AAC", style = MaterialTheme.typography.bodyLarge)
            Text("${s.done + 1} of ${s.total}: ${s.current}", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { s.done / s.total.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth())
            TextButton(onClick = conversion::cancel) { Text("Stop") }
        }
        else -> {
            val finished = s as? FlacConversion.State.Finished
            val subtitle = when {
                flacs.isNotEmpty() -> "${flacs.size} FLAC ${if (flacs.size == 1) "song" else "songs"} • frees about ${size(conversion.estimatedSaving(flacs))}"
                finished != null && finished.converted > 0 -> "Done: ${finished.converted} converted, ${size(finished.savedBytes)} freed" + if (finished.skipped > 0) " • ${finished.skipped} kept as FLAC" else ""
                else -> "No FLAC files in your library"
            }
            SettingRow("Convert FLAC to AAC", subtitle) { if (flacs.isNotEmpty()) confirming = true }
        }
    }

    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Convert ${flacs.size} FLAC ${if (flacs.size == 1) "song" else "songs"}?") },
        text = {
            Text("Each one is re-encoded as AAC 256 kbps (.m4a), which frees about ${size(conversion.estimatedSaving(flacs))}. " +
                "The FLAC files are then deleted and can't be recovered. Likes, playlists, play counts and lyrics carry over.\n\n" +
                "The song that's playing is converted next time.")
        },
        confirmButton = { TextButton(onClick = { confirming = false; conversion.start() }) { Text("Convert and delete FLAC") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
    )
}

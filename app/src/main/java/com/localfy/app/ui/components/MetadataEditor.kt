package com.localfy.app.ui.components

import com.localfy.app.data.uri

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.meta.MetadataCandidate
import com.localfy.app.data.meta.MetadataEdit
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.launch

/**
 * Edit tags + artwork for one song, or (album mode) every track of an album/book at once.
 * Saves checked edits into the selected files, asking Android for write access when needed.
 */
@Composable
fun MetadataEditor(songs: List<Song>, albumMode: Boolean, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as LocalfyApp
    val repo = app.metadata
    val scope = rememberCoroutineScope()
    val first = songs.first()
    val book = first.isAudiobook

    var title by remember { mutableStateOf(first.title) }
    var artist by remember { mutableStateOf(first.artist) }
    var album by remember { mutableStateOf(first.album) }
    var albumArtist by remember { mutableStateOf(first.albumArtist) }
    var genre by remember { mutableStateOf(first.genre.orEmpty()) }
    var year by remember { mutableStateOf(first.year.takeIf { it > 0 }?.toString().orEmpty()) }
    var track by remember { mutableStateOf(first.track.takeIf { it > 0 }?.toString().orEmpty()) }
    var disc by remember { mutableStateOf(first.disc.takeIf { it > 1 }?.toString().orEmpty()) }
    var pendingArt by remember { mutableStateOf<String?>(null) }
    var artSource by remember { mutableStateOf<String?>(null) }
    var artLoading by remember { mutableStateOf(false) }
    var artError by remember { mutableStateOf<String?>(null) }
    var artRequest by remember { mutableStateOf(0) }
    var candidates by remember { mutableStateOf<List<MetadataCandidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var retrySave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) retrySave?.invoke()
        else { saving = false; saveError = "Permission was not granted. You can try Save again." }
        retrySave = null
    }
    fun saveFiles(edit: MetadataEdit, mayAsk: Boolean = true) {
        saving = true
        saveError = null
        scope.launch {
            try {
                repo.saveFiles(songs, edit, pendingArt)
                saving = false
                onDismiss()
            } catch (error: SecurityException) {
                saving = false
                val uris = songs.map { it.uri }.filter { it.authority == android.provider.MediaStore.AUTHORITY }
                if (mayAsk && uris.size == songs.size) {
                    try {
                        retrySave = { saveFiles(edit, false) }
                        val request = android.provider.MediaStore.createWriteRequest(context.contentResolver, uris)
                        writePermission.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                    } catch (denied: Exception) { saveError = "Could not get permission to edit these files: ${denied.message}" }
                } else saveError = "Spitify cannot write to this file. Choose a writable copy in your Music folder."
            } catch (error: Exception) {
                saving = false
                saveError = error.message ?: "Could not save the file."
            }
        }
    }
    fun selectArtwork(source: String) {
        artSource = source; artLoading = true; artError = null; pendingArt = null
        val request = ++artRequest
        scope.launch {
            try {
                val ready = repo.prepareArtwork(source)
                if (request == artRequest) pendingArt = ready
            } catch (error: Exception) {
                if (request == artRequest) artError = error.message ?: "Could not load the cover."
            } finally { if (request == artRequest) artLoading = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) selectArtwork(uri.toString()) }

    fun applyCandidate(c: MetadataCandidate) {
        if (!albumMode) { title = c.title; c.track?.let { track = it.toString() }; c.disc?.let { disc = it.toString() } }
        artist = c.artist; album = c.album; albumArtist = c.artist
        c.genre?.let { genre = it }; c.year?.let { year = it.toString() }
        c.artUrl?.takeIf { it.isNotBlank() }?.let { selectArtwork(it) }
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(LocalfyColors.Background)) {
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 120.dp)) {
                item {
                    Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Rounded.Close, "Cancel") }
                        Column(Modifier.weight(1f)) {
                            Text(if (albumMode) (if (book) "Edit book" else "Edit album") else "Edit info", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (albumMode) "Applies to ${songs.size} ${if (book) "chapters" else "tracks"} · saved into the files" else "Saved into the music file",
                                style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary,
                            )
                        }
                        TextButton(onClick = {
                            val edit = MetadataEdit(
                                title = if (albumMode) null else title.trim().ifEmpty { null },
                                artist = artist.trim().ifEmpty { null },
                                album = album.trim().ifEmpty { null },
                                albumArtist = albumArtist.trim().ifEmpty { null },
                                genre = genre.trim().ifEmpty { null },
                                year = year.toIntOrNull(),
                                track = if (albumMode) null else track.toIntOrNull(),
                                disc = if (albumMode) null else disc.toIntOrNull(),
                            )
                            saveFiles(edit)
                        }, enabled = !saving && !artLoading) { Text(if (saving) "Saving…" else "Save", style = MaterialTheme.typography.labelLarge) }
                    }
                }

                if (saveError != null) item { Text(saveError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                // Artwork
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(120.dp)) {
                            if (pendingArt != null) Artwork(ArtKey(0, 0, pendingArt), Modifier.size(120.dp), RoundedCornerShape(10.dp))
                            else Artwork(first.artKey, Modifier.size(120.dp), RoundedCornerShape(10.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionChip(Icons.Rounded.Image, "Choose image") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            if (repo.customArt(first.albumId) != null) ActionChip(Icons.Rounded.DeleteOutline, "Use file cover") { repo.removeArt(first.albumId); artRequest++; pendingArt = null; artLoading = false; artError = null }
                            if (artError != null) {
                                Text(artError!! + " You can still save the details with the current cover.", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { artSource?.let { selectArtwork(it) } }, enabled = !artLoading && !saving) { Text("Retry cover") }
                            }
                            Text(if (artLoading) "Loading cover…" else if (pendingArt != null) "New artwork — tap Save to keep it" else "Current cover — kept when you save", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
                        }
                    }
                }

                // Online lookup
                item {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionChip(Icons.Rounded.CloudDownload, if (book) "Find book info online" else "Find info online") {
                            searching = true
                            scope.launch {
                                candidates = if (book) {
                                    repo.searchBooks("$album $artist".trim()).map { b ->
                                        MetadataCandidate(first.title, b.author, b.title, b.year, "Audiobook", null, null, 0, b.coverUrl, "Open Library")
                                    }
                                } else {
                                    val q = if (albumMode) "$artist $album" else "$artist $title".trim()
                                    repo.search(q.ifBlank { repo.queryFor(first) }, if (albumMode) 0 else first.durationMs)
                                }
                                searching = false
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                candidates?.let { list ->
                    if (list.isEmpty()) item { Text("No matches — try editing the artist/title above first.", color = LocalfyColors.TextSecondary, modifier = Modifier.padding(16.dp)) }
                    items(list.take(12)) { c -> CandidateRow(c, first.durationMs.takeIf { !albumMode && !book } ?: 0) { applyCandidate(c); candidates = null } }
                }

                item { Spacer(Modifier.height(8.dp)) }
                if (!albumMode) item { Field(if (book) "Chapter title" else "Title", title) { title = it } }
                item { Field(if (book) "Author" else "Artist", artist) { artist = it } }
                item { Field(if (book) "Book" else "Album", album) { album = it } }
                if (!book) item { Field("Main artist (album artist)", albumArtist) { albumArtist = it } }
                item { Field("Genre", genre) { genre = it } }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field("Year", year, Modifier.weight(1f), numeric = true, padded = false) { year = it }
                        if (!albumMode) {
                            Field(if (book) "Chapter" else "Track", track, Modifier.weight(1f), numeric = true, padded = false) { track = it }
                            if (!book) Field("Disc", disc, Modifier.weight(1f), numeric = true, padded = false) { disc = it }
                        }
                    }
                }
                item {
                    TextButton(onClick = { repo.reset(songs); if (repo.customArt(first.albumId) != null) repo.removeArt(first.albumId); onDismiss() }, modifier = Modifier.padding(8.dp), enabled = !saving) {
                        Text("Reload tags from the file", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!albumMode) item {
                    Text("File: /${first.folder}${first.fileName}", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier, numeric: Boolean = false, padded: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (numeric) v.filter(Char::isDigit).take(4) else v) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().then(if (padded) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier.padding(vertical = 4.dp)),
    )
}

@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(LocalfyColors.Tint).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CandidateRow(c: MetadataCandidate, durationMs: Long, onPick: () -> Unit) {
    val diff = if (durationMs > 0 && c.durationMs > 0) kotlin.math.abs(c.durationMs - durationMs) / 1000 else null
    Row(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onPick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(ArtKey(c.hashCode().toLong(), c.hashCode().toLong(), c.artUrl), Modifier.size(52.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(c.artist, c.album).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(c.source, c.year?.toString(), diff?.let { if (it <= 3) "length matches" else "${it}s off" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (diff != null && diff <= 3) MaterialTheme.colorScheme.primary else LocalfyColors.TextTertiary,
            )
        }
        Text("Use", style = MaterialTheme.typography.labelLarge, color = LocalPalette.current.onBrand,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}


package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.Prefs
import com.localfy.app.ui.DesktopImages
import com.localfy.app.ui.FilePickers
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.LocalWindow
import com.localfy.app.ui.art.ArtKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Per-artist choices kept by the UI: hidden from Library, and a custom artist photo. */
object ArtistChoices {
    val revision = MutableStateFlow(0)
    private val prefs by lazy { Prefs("artist_choices") }
    fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet())
    fun hide(name: String, hidden: Boolean) {
        val values = hidden().toMutableSet(); if (hidden) values.add(name) else values.remove(name)
        prefs.edit { putStringSet("hidden", values) }; revision.value++
    }
    fun file(name: String): File {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(com.localfy.app.data.music.SearchMatch.fold(name).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(AppPaths.data("artist_covers").apply { mkdirs() }, "$key.jpg")
    }
    fun art(name: String, fallback: ArtKey?): ArtKey? {
        val file = file(name)
        return if (file.isFile) ArtKey(name.hashCode().toLong(), name.hashCode().toLong(), file.toURI().toString() + "?v=" + file.lastModified(), file.lastModified()) else fallback
    }
}

@Composable fun ArtistOptions(name: String) {
    val window = LocalWindow.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun pick() {
        val source = FilePickers.pickImage(window, "Choose an artist photo") ?: return
        scope.launch {
            val success = withContext(Dispatchers.IO) { DesktopImages.saveSquareImage(source, ArtistChoices.file(name), 1000) }
            if (success) ArtistChoices.revision.value++ else message = "Couldn't read this image."
        }
    }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Artist options") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Change artist cover") }, onClick = { menu = false; pick() })
            if (ArtistChoices.file(name).isFile) DropdownMenuItem(text = { Text("Use original cover") }, onClick = { ArtistChoices.file(name).delete(); ArtistChoices.revision.value++; menu = false })
            val hidden = name in ArtistChoices.hidden()
            DropdownMenuItem(text = { Text(if (hidden) "Show artist in Library" else "Hide artist from Library") }, onClick = { ArtistChoices.hide(name, !hidden); menu = false })
        }
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("Artist cover") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
}

@Composable fun HiddenArtistsScreen() {
    val revision by ArtistChoices.revision.collectAsState()
    val hidden = remember(revision) { ArtistChoices.hidden().sorted() }
    val actions = LocalApp.current
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item { com.localfy.app.ui.components.PageHeader("Hidden artists", onBack = { actions.nav.popBackStack() }) }
        item { Text("Hiding an artist only changes your Library list. Their songs stay saved and can still be searched and played.") }
        items(hidden) { name -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); TextButton(onClick = { ArtistChoices.hide(name, false) }) { Text("Show") } } }
        if (hidden.isEmpty()) item { Text("No hidden artists.") }
    }
}

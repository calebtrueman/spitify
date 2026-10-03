package com.localfy.app.ui.screens

import android.content.Context
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.art.ArtKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

object ArtistChoices {
    val revision = MutableStateFlow(0)
    private fun prefs(context: Context) = context.getSharedPreferences("artist_choices", 0)
    fun hidden(context: Context): Set<String> = prefs(context).getStringSet("hidden", emptySet()).orEmpty().toSet()
    fun hide(context: Context, name: String, hidden: Boolean) {
        val values = hidden(context).toMutableSet(); if (hidden) values.add(name) else values.remove(name)
        prefs(context).edit().putStringSet("hidden", values).apply(); revision.value++
    }
    fun file(context: Context, name: String): File {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(com.localfy.app.data.music.SearchMatch.fold(name).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "artist_covers").apply { mkdirs() }, "$key.jpg")
    }
    fun art(context: Context, name: String, fallback: ArtKey?): ArtKey? {
        val file = file(context, name)
        return if (file.isFile) ArtKey(name.hashCode().toLong(), name.hashCode().toLong(), android.net.Uri.fromFile(file).toString() + "?v=" + file.lastModified(), file.lastModified()) else fallback
    }
}

@Composable fun ArtistOptions(name: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val success = com.localfy.app.data.saveSquareImage(ImageDecoder.createSource(context.contentResolver, uri), ArtistChoices.file(context, name), 1000)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { if (success) ArtistChoices.revision.value++ else message = "Couldn't read this image." }
    } }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Artist options") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Change artist cover") }, onClick = { menu = false; picker.launch("image/*") })
            if (ArtistChoices.file(context, name).isFile) DropdownMenuItem(text = { Text("Use original cover") }, onClick = { ArtistChoices.file(context, name).delete(); ArtistChoices.revision.value++; menu = false })
            val hidden = name in ArtistChoices.hidden(context)
            DropdownMenuItem(text = { Text(if (hidden) "Show artist in Library" else "Hide artist from Library") }, onClick = { ArtistChoices.hide(context, name, !hidden); menu = false })
        }
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("Artist cover") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
}

@Composable fun HiddenArtistsScreen() {
    val context = LocalContext.current
    val revision by ArtistChoices.revision.collectAsState()
    val hidden = remember(revision) { ArtistChoices.hidden(context).sorted() }
    val actions = LocalApp.current
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item { com.localfy.app.ui.components.PageHeader("Hidden artists", onBack = { actions.nav.popBackStack() }) }
        item { Text("Hiding an artist only changes your Library list. Their songs stay saved and can still be searched and played.") }
        items(hidden) { name -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); TextButton(onClick = { ArtistChoices.hide(context, name, false) }) { Text("Show") } } }
        if (hidden.isEmpty()) item { Text("No hidden artists.") }
    }
}

package com.localfy.app.ui.screens

import com.localfy.app.ui.typingFocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localfy.app.data.social.SpotifyPlaylists
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.components.PageHeader
import com.localfy.app.ui.theme.LocalfyColors

@Composable
fun SpotifyImportScreen() {
    val app = LocalApp.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf("") }
    val valid = SpotifyPlaylists.accepts(input)
    fun open() { if (valid) { focus.clearFocus(); app.navigate(Routes.spotifyPlaylist(input.trim())) } }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Add from Spotify", onBack = { app.nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(Icons.Rounded.Link, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Your playlists, here.", style = MaterialTheme.typography.headlineLarge)
                Text("In Spotify, open a public playlist and choose Share → Copy link. Paste it below to see its cover and songs before saving it.", color = LocalfyColors.TextSecondary)
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, label = { Text("Playlist link") },
                    placeholder = { Text("https://open.spotify.com/playlist/…") }, modifier = Modifier.fillMaxWidth().typingFocus(),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open() }),
                    supportingText = { if (input.isNotBlank() && !valid) Text("Use a Spotify playlist link, rather than a song or album link.") }
                )
                TextButton(onClick = { clipboard.getText()?.text?.let { input = it } }) { Text("Paste playlist link") }
                Button(onClick = { open() }, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Preview playlist") }
                TextButton(onClick = { app.navigate("spotify-code") }) { Text("Scan a Spotify code") }
                Text("Only public playlists are available. Some songs may be missing. Nothing is saved until you choose Save to Your Library on the preview.", color = LocalfyColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

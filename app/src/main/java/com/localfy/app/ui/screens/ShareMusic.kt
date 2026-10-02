package com.localfy.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes

@Composable
fun ShareMusicButton(name: String, songs: List<Song>, kind: String = "playlist") {
    val context = LocalContext.current
    val app = context.applicationContext as LocalfyApp
    val actions = LocalApp.current
    IconButton(enabled = songs.isNotEmpty(), onClick = {
        runCatching { val playlist = app.social.create(name, songs, app.musicStreams, kind); actions.navigate(Routes.sharedPlaylist(playlist.key)) }
            .onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
    }) { Icon(Icons.Rounded.Share, "Share with friends") }
}

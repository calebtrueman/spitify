package com.localfy.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.localfy.app.data.Song
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.LocalContainer
import com.localfy.app.ui.Routes
import com.localfy.app.ui.Toasts

@Composable
fun ShareMusicButton(name: String, songs: List<Song>, kind: String = "playlist") {
    val app = LocalContainer.current
    val actions = LocalApp.current
    IconButton(enabled = songs.isNotEmpty(), onClick = {
        runCatching { val playlist = app.social.create(name, songs, app.musicStreams, kind); actions.navigate(Routes.sharedPlaylist(playlist.key)) }
            .onFailure { Toasts.show(it.message) }
    }) { Icon(Icons.Rounded.Share, "Share with friends") }
}

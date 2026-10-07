package com.localfy.app.ui.screens

import com.localfy.app.ui.components.*
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.ui.art.AsyncImage
import com.localfy.app.LocalfyApp
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import kotlinx.coroutines.launch

@Composable
fun ArtistReleasesScreen() {
    val follows = com.localfy.app.ui.LocalContainer.current.artistFollows
    val revision by follows.revision.collectAsStateWithLifecycle()
    val actions = LocalApp.current
    val scope = rememberCoroutineScope()
    val artists = remember(revision) { follows.artists }
    val releases = remember(revision) { follows.releases }
    LaunchedEffect(Unit) { follows.refresh() }
    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("New releases", onBack = { actions.nav.popBackStack() }) }
        insetItem { Text("Spitify checks followed artists when you open the app.", color = LocalfyColors.TextSecondary); TextButton(onClick = { scope.launch { follows.refresh() } }) { Text("Refresh") } }
        insetItem {
            Row {
                Text("Release notifications", Modifier.weight(1f))
                Switch(follows.notifications, { enabled -> follows.setNotifications(enabled) })
            }
            Text("Alerts appear when Spitify checks for releases. They are not instant push alerts.")
        }
        if (artists.isEmpty()) insetItem { Text("Follow an artist from search to see their releases here.") }
        item { SectionHeader("Following") }
        items(artists, key = { "artist:${it.id}" }) { artist ->
            MediaRow(artist.name, "Artist", artwork = { Artwork(ArtKey(artist.id.hashCode().toLong(), artist.id.hashCode().toLong(), artist.artwork), it, CircleShape) }, onClick = { actions.navigate(Routes.onlineArtist(artist)) })
        }
        item { SectionHeader("Latest releases") }
        items(releases, key = { "${it.artist.id}:${it.album.id}" }) { notice ->
            MediaRow(notice.album.title, notice.artist.name, artwork = { Artwork(ArtKey(notice.album.id.hashCode().toLong(), notice.album.id.hashCode().toLong(), notice.album.artwork), it, RoundedCornerShape(6.dp)) }, onClick = { actions.navigate(Routes.catalogAlbum(notice.album)) }, subtitleContent = { SearchSubtitle("Album", notice.artist.name, notice.album.explicit == true) })
        }
        follows.message?.let { insetItem { Text(it) } }
    }
}

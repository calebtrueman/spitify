package com.localfy.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.localfy.app.LocalfyApp
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import kotlinx.coroutines.launch

@Composable
fun ArtistReleasesScreen() {
    val context = LocalContext.current
    val follows = (context.applicationContext as LocalfyApp).artistFollows
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> follows.setNotifications(granted) }
    val revision by follows.revision.collectAsStateWithLifecycle()
    val actions = LocalApp.current
    val scope = rememberCoroutineScope()
    val artists = remember(revision) { follows.artists }
    val releases = remember(revision) { follows.releases }
    LaunchedEffect(Unit) { follows.refresh() }
    LazyColumn(contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { actions.nav.popBackStack() }) { Text("Back") }; Text("New releases", style = MaterialTheme.typography.headlineMedium); Text("Spitify checks followed artists when you open the app."); TextButton(onClick = { scope.launch { follows.refresh() } }) { Text("Refresh") } }
        item {
            Row {
                Text("Release notifications", Modifier.weight(1f))
                Switch(follows.notifications, { enabled ->
                    if (enabled && android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else follows.setNotifications(enabled)
                })
            }
            Text("Alerts appear when Spitify checks for releases. They are not instant push alerts.")
        }
        if (artists.isEmpty()) item { Text("Follow an artist from search to see their releases here.") }
        item { Text("Following", style = MaterialTheme.typography.titleLarge) }
        items(artists, key = { "artist:${it.id}" }) { artist ->
            Row(Modifier.fillMaxWidth().clickable { actions.navigate(Routes.onlineArtist(artist)) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(artist.artwork, null, Modifier.size(48.dp))
                Text(artist.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
        item { Text("Latest releases", style = MaterialTheme.typography.titleLarge) }
        items(releases, key = { "${it.artist.id}:${it.album.id}" }) { notice ->
            Row(Modifier.fillMaxWidth().clickable { actions.navigate(Routes.catalogAlbum(notice.album)) }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(notice.album.artwork, null, Modifier.size(56.dp)); Column { Text(notice.album.title); SearchSubtitle("Album", notice.artist.name, notice.album.explicit == true) }
            }
        }
        follows.message?.let { item { Text(it) } }
    }
}

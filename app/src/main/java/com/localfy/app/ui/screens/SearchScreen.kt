package com.localfy.app.ui.screens

import androidx.compose.material.icons.rounded.QrCodeScanner

import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.theme.LocalPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.Library
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.art.fallbackColor
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.MediaTile
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.FittedTileRow
import com.localfy.app.ui.components.SongRow
import com.localfy.app.ui.components.TileData
import com.localfy.app.ui.components.songCount
import com.localfy.app.ui.player.rememberPlayerState
import java.text.Normalizer

private fun String.fold(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Matches every word of the query anywhere in title/artist/album, accent- and case-insensitive. */
private fun matches(haystack: String, words: List<String>) = words.all { haystack.contains(it) }

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val liked by app.repo.likedIds.collectAsStateWithLifecycle()
    val playlists by app.repo.playlists.collectAsStateWithLifecycle()
    val player = rememberPlayerState()
    var query by rememberSaveable { mutableStateOf("") }
    var online by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val words = query.fold().split(' ').filter { it.isNotBlank() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column {
                com.localfy.app.ui.components.PageHeader("Search") {
                    androidx.compose.material3.IconButton(onClick = { app.navigate("spotify-code") }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.QrCodeScanner, "Scan Spotify code") }
                }
                com.localfy.app.ui.components.MediaSearchField(query, { query = it }, "What do you want to listen to?")
            }
        }

        if (words.isEmpty()) {
            if (library.albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "albums") {
                    Text("Your albums", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(library.albums, key = { "album-${it.id}" }) { album ->
                    BrowseTile(album.title, album.songs.size, album.cover.artKey, fallbackColor(album.id)) {
                        app.navigate(Routes.album(album.id))
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "browse") {
                Text("Browse your genres", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(library.genres, key = { "g-${it.name}" }) { g ->
                BrowseTile(g.name, g.songs.size, g.songs.first().artKey, fallbackColor(g.name.hashCode().toLong())) {
                    app.navigate(Routes.genre(g.name))
                }
            }
            return@LazyVerticalGrid
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "results") { OnlineMusicPanel(query) }
    }

}

@Composable
private fun BrowseTile(title: String, count: Int, art: com.localfy.app.ui.art.ArtKey, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(lerp(color, Color.Black, 0.4f))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(songCount(count), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color.White)
            Artwork(art, Modifier.size(56.dp).rotate(15f), RoundedCornerShape(4.dp))
        }
    }
}

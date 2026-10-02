package com.localfy.app.ui.screens

import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.theme.LocalPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
    val index = remember(library) { SearchIndex(library) }
    val words = query.fold().split(' ').filter { it.isNotBlank() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column(Modifier.statusBarsPadding().padding(16.dp)) {
                Text("Search", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("What do you want to listen to?") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.Black) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = Color.Black) }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = if (LocalPalette.current.isDark) Color.White else LocalfyColors.SurfaceHigh,
                        unfocusedContainerColor = if (LocalPalette.current.isDark) Color.White else LocalfyColors.SurfaceHigh,
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedPlaceholderColor = Color(0xFF555555),
                        unfocusedPlaceholderColor = Color(0xFF555555),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.Black,
                    ),
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 16.dp)) {
                androidx.compose.material3.FilterChip(selected = !online, onClick = { online = false }, label = { Text("On device") })
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilterChip(selected = online, onClick = { online = true }, label = { Text("Online") })
            }
        }
        if (online) {
            item(span = { GridItemSpan(maxLineSpan) }) { OnlineMusicPanel(query) }
        } else if (words.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "browse") {
                Text("Browse your genres", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(library.genres, key = { "g-${it.name}" }) { g ->
                BrowseTile(g.name, g.songs.size, g.songs.first().artKey, fallbackColor(g.name.hashCode().toLong())) {
                    app.navigate(Routes.genre(g.name))
                }
            }
            if (library.folders.size > 1) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "folders") {
                    Text("Browse folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(library.folders, key = { "f-${it.path}" }) { f ->
                    BrowseTile(f.name, f.songs.size, f.songs.first().artKey, fallbackColor(f.path.hashCode().toLong() + 3)) {
                        app.navigate(Routes.folder(f.path))
                    }
                }
            }
            return@LazyVerticalGrid
        }

        val songs = index.songs.filter { matches(it.second, words) }.map { it.first }.take(50)
        val artists = index.artists.filter { matches(it.second, words) }.map { it.first }.take(20)
        val albums = index.albums.filter { matches(it.second, words) }.map { it.first }.take(20)
        val matchedPlaylists = playlists.filter { matches(it.name.fold(), words) }

        if (songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && matchedPlaylists.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "none") { EmptyState("No results for “$query”", "Check the spelling, or try fewer words.") }
            return@LazyVerticalGrid
        }

        if (artists.isNotEmpty() || albums.isNotEmpty() || matchedPlaylists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "shelves") {
                Column {
                    if (artists.isNotEmpty()) {
                        SectionHeader("Artists")
                        FittedTileRow(artists, key = { it.name }, tileWidth = 120.dp) { a ->
                                TileData(a.name, a.name, "Artist", a.cover.artKey, circle = true) { focus.clearFocus(); app.navigate(Routes.artist(a.name)) }
}
                    }
                    if (albums.isNotEmpty()) {
                        SectionHeader("Albums")
                        FittedTileRow(albums, key = { it.id }, tileWidth = 140.dp) { a ->
                                TileData("a${a.id}", a.title, a.artist, a.cover.artKey) { focus.clearFocus(); app.navigate(Routes.album(a.id)) }
}
                    }
                    if (matchedPlaylists.isNotEmpty()) {
                        SectionHeader("Playlists")
                        FittedTileRow(matchedPlaylists, key = { it.id }, tileWidth = 140.dp) { p ->
                                TileData("p${p.id}", p.name, "Playlist", p.songs.firstOrNull()?.artKey) { app.navigate(Routes.playlist(p.id)) }
}
                    }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "songs-h") { SectionHeader("Songs") }
            items(songs.size, span = { GridItemSpan(maxLineSpan) }, key = { "s-${songs[it].id}" }) { i ->
                val s = songs[i]
                SongRow(
                    song = s,
                    onClick = { focus.clearFocus(); app.player.playSongs(songs, i, shuffle = false, source = "Search: $query") },
                    isCurrent = player.currentId == s.id,
                    isPlaying = player.isPlaying,
                    liked = s.id in liked,
                    onMore = { app.openSongMenu(s, SongMenuExtras()) },
                )
            }
        }
    }
}

private class SearchIndex(library: Library) {
    val songs = library.songs.map { it to "${it.title} ${it.artist} ${it.album} ${it.albumArtist}".fold() }
    val artists = library.artists.map { it to it.name.fold() }
    val albums = library.albums.map { it to "${it.title} ${it.artist}".fold() }
}

@Composable
private fun BrowseTile(title: String, count: Int, art: com.localfy.app.ui.art.ArtKey, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(6.dp)
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
            Text(songCount(count), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
        Artwork(
            art,
            Modifier.align(Alignment.BottomEnd).padding(end = 0.dp).size(64.dp).rotate(25f),
            RoundedCornerShape(4.dp),
        )
    }
}

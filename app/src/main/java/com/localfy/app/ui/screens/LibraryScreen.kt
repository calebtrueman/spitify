package com.localfy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.SmartCollection
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.MediaTile
import com.localfy.app.ui.components.Pill
import com.localfy.app.ui.components.SongRow
import com.localfy.app.ui.components.TileData
import com.localfy.app.ui.components.songCount
import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.theme.LocalfyColors

private enum class Filter(val label: String) { Playlists("Playlists"), Albums("Albums"), Artists("Artists"), Songs("Songs"), Genres("Genres"), Folders("Folders") }
private enum class Sort(val label: String) { Recent("Recents"), Alpha("Alphabetical"), Plays("Most played") }

private data class Entry(val key: String, val title: String, val subtitle: String, val art: ArtKey?, val shape: Shape, val sortTime: Long, val plays: Int, val onClick: () -> Unit)

@Composable
fun LibraryScreen(onCreatePlaylist: () -> Unit) {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val playlists by app.repo.playlists.collectAsStateWithLifecycle()
    val smart by app.repo.smart.collectAsStateWithLifecycle()
    val stats by app.repo.stats.collectAsStateWithLifecycle()
    val liked by app.repo.likedIds.collectAsStateWithLifecycle()
    val player = rememberPlayerState()
    var filter by rememberSaveable { mutableStateOf<Filter?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.Recent) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    fun plays(ids: List<Long>) = ids.sumOf { stats[it]?.playCount ?: 0 }
    fun lastPlayed(ids: List<Long>) = ids.maxOfOrNull { stats[it]?.lastPlayed ?: 0 } ?: 0

    val rounded = RoundedCornerShape(4.dp)
    val entries: List<Entry> = remember(library, playlists, stats, filter, sort) {
        val all = buildList {
            if (filter == null || filter == Filter.Playlists) playlists.forEach { p ->
                val ids = p.songs.map { it.id }
                add(Entry("p${p.id}", p.name, "Playlist • ${songCount(p.songs.size)}", p.songs.firstOrNull()?.artKey, rounded, maxOf(p.updatedAt, lastPlayed(ids)), plays(ids)) { app.navigate(Routes.playlist(p.id)) })
            }
            if (filter == null || filter == Filter.Albums) library.albums.forEach { a ->
                val ids = a.songs.map { it.id }
                add(Entry("a${a.id}", a.title, "Album • ${a.artist}", a.cover.artKey, rounded, lastPlayed(ids).takeIf { it > 0 } ?: (a.songs.maxOf { it.dateAddedSec } * 1000), plays(ids)) { app.navigate(Routes.album(a.id)) })
            }
            if (filter == null || filter == Filter.Artists) library.artists.forEach { a ->
                val ids = a.songs.map { it.id }
                add(Entry("ar${a.name}", a.name, "Artist", a.cover.artKey, CircleShape, lastPlayed(ids), plays(ids)) { app.navigate(Routes.artist(a.name)) })
            }
            if (filter == Filter.Genres) library.genres.forEach { g ->
                val ids = g.songs.map { it.id }
                add(Entry("g${g.name}", g.name, "Genre • ${songCount(g.songs.size)}", g.songs.first().artKey, rounded, lastPlayed(ids), plays(ids)) { app.navigate(Routes.genre(g.name)) })
            }
            if (filter == Filter.Folders) library.folders.forEach { f ->
                val ids = f.songs.map { it.id }
                add(Entry("f${f.path}", f.name, "Folder • /${f.path}", f.songs.first().artKey, rounded, lastPlayed(ids), plays(ids)) { app.navigate(Routes.folder(f.path)) })
            }
        }
        when (sort) {
            Sort.Recent -> all.sortedByDescending { it.sortTime }
            Sort.Alpha -> all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            Sort.Plays -> all.sortedByDescending { it.plays }
        }
    }
    val songs = remember(library, stats, sort, filter) {
        if (filter != Filter.Songs) emptyList() else when (sort) {
            Sort.Recent -> library.songs.sortedByDescending { stats[it.id]?.lastPlayed?.takeIf { t -> t > 0 } ?: (it.dateAddedSec * 1000) }
            Sort.Alpha -> library.songs
            Sort.Plays -> library.songs.sortedByDescending { stats[it.id]?.playCount ?: 0 }
        }
    }

    LazyVerticalGrid(
        columns = if (grid && filter != Filter.Songs) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCreatePlaylist) { Icon(Icons.Rounded.Add, "Create playlist") }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Filter.entries.toList()) { f -> Pill(f.label, filter == f, { filter = if (filter == f) null else f }) }
                }
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).clickable { sortMenu = true }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(sort.label, style = MaterialTheme.typography.labelLarge)
                        }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            Sort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (filter != Filter.Songs) IconButton(onClick = { grid = !grid }) {
                        Icon(if (grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView, if (grid) "List view" else "Grid view")
                    }
                }
            }
        }

        if (filter == null || filter == Filter.Playlists) {
            val likedCount = smart[SmartCollection.Kind.Liked]?.songs?.size ?: 0
            item(span = { GridItemSpan(if (grid) 1 else maxLineSpan) }, key = "liked") {
                PinnedEntry("Liked Songs", "Pinned • ${songCount(likedCount)}", Icons.Rounded.Favorite, Color(0xFF4B2BD6), Color(0xFF9AB8F0), grid) {
                    app.navigate(Routes.smart(SmartCollection.Kind.Liked))
                }
            }
            listOf(SmartCollection.Kind.MostPlayed, SmartCollection.Kind.RecentlyAdded, SmartCollection.Kind.Forgotten).forEach { k ->
                val c = smart[k]
                if (c != null && c.songs.isNotEmpty()) item(span = { GridItemSpan(if (grid) 1 else maxLineSpan) }, key = "smart-$k") {
                    PinnedEntry(k.title, "Smart playlist • ${songCount(c.songs.size)}", Icons.Rounded.AutoAwesome, Color(0xFF0E7A55), Color(0xFF1ED760), grid) {
                        app.navigate(Routes.smart(k))
                    }
                }
            }
        }

        if (filter == Filter.Songs) {
            items(songs.size, key = { "s${songs[it].id}" }) { i ->
                val s = songs[i]
                SongRow(
                    s, onClick = { app.player.playSongs(songs, i, shuffle = false, source = "All songs") },
                    isCurrent = player.currentId == s.id, isPlaying = player.isPlaying, liked = s.id in liked,
                    onMore = { app.openSongMenu(s, SongMenuExtras()) },
                )
            }
        } else {
            items(entries, key = { it.key }) { e ->
                if (grid) {
                    Box(Modifier.padding(4.dp)) {
                        MediaTile(TileData(e.key, e.title, e.subtitle, e.art, circle = e.shape == CircleShape, onClick = e.onClick), 160.dp, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = e.onClick).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(e.art, Modifier.size(60.dp), e.shape)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedEntry(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    from: Color,
    to: Color,
    grid: Boolean,
    onClick: () -> Unit,
) {
    val art = @Composable { m: Modifier ->
        Box(m.clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(from, to))), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (grid) 48.dp else 26.dp))
        }
    }
    if (grid) {
        Column(Modifier.padding(8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
            art(Modifier.fillMaxWidth().aspectRatio(1f))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1)
        }
    } else {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            art(Modifier.size(60.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
            }
        }
    }
}


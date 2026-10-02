package com.localfy.app.ui.screens

import com.localfy.app.ui.theme.DarkSurface
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.SmartCollection
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.BigPlayButton
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.MediaTile
import com.localfy.app.ui.components.Pill
import com.localfy.app.ui.components.QuickTile
import com.localfy.app.ui.components.TileData
import com.localfy.app.ui.components.TileShelf
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.components.shimmer
import com.localfy.app.ui.components.songCount
import com.localfy.app.ui.player.rememberCurrentSong
import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.player.rememberPlayerTint
import java.util.Calendar

private enum class HomeFilter(val label: String) { All("All"), Albums("Albums"), Artists("Artists"), Playlists("Playlists") }

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val scanning by app.repo.scanning.collectAsStateWithLifecycle()
    val liveSmart by app.repo.smart.collectAsStateWithLifecycle()
    val liveMixes by app.repo.mixes.collectAsStateWithLifecycle()
    val playlists by app.repo.playlists.collectAsStateWithLifecycle()
    val liveStats by app.repo.stats.collectAsStateWithLifecycle()
    // Snapshot the shelves while you're on Home (refreshed each time you come back), so nothing
    // reshuffles or pops in under your finger when a play gets counted mid-browse.
    val snapshot = remember(library, liveSmart.isNotEmpty(), liveMixes.isNotEmpty()) { Triple(liveSmart, liveMixes, liveStats) }
    val (smart, mixes, stats) = snapshot
    val player = rememberPlayerState()
    val current = rememberCurrentSong()
    val glow = rememberPlayerTint(current)
    var filter by rememberSaveable { mutableStateOf(HomeFilter.All) }
    val gridState = rememberLazyGridState()
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 560.dp
        val tileWidth = if (wide) 164.dp else 144.dp
        val quickColumns = if (maxWidth >= 720.dp) 4 else if (wide) 3 else 2

        // Ambient glow that takes on the colour of whatever's playing and fades as you scroll.
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .graphicsLayer {
                    alpha = if (gridState.firstVisibleItemIndex > 0) 0f else (1f - gridState.firstVisibleItemScrollOffset / 600f).coerceIn(0f, 1f)
                }
                .background(Brush.verticalGradient(listOf(lerp(glow, Color.Black, 0.2f).copy(alpha = if (LocalPalette.current.isDark) 0.9f else 0.35f), Color.Transparent))),
        )

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(if (filter == HomeFilter.All) 2000.dp else 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp),
        ) {
            full("top") {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.localfy.app.ui.components.Avatar(34.dp, Modifier.pressable { app.navigate(Routes.PROFILE) })
                        Spacer(Modifier.width(10.dp))
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(HomeFilter.entries.toList()) { f -> Pill(f.label, filter == f, { filter = f }) }
                        }
                        IconButton(onClick = { app.navigate(Routes.STATS) }) { Icon(Icons.Rounded.Insights, "Your stats") }
                    }
                    val name = app.profiles.profile.collectAsStateWithLifecycle().value.name
                    Text(if (name.isBlank()) greeting else "$greeting, $name", style = MaterialTheme.typography.headlineLarge, maxLines = 2, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
                }
            }

            if (library.isEmpty) {
                if (scanning) full("loading") { LoadingSkeleton(quickColumns) }
                else full("empty") {
                    EmptyState(
                        "No music found",
                        "Copy audio files (MP3, FLAC, M4A, OGG, WAV…) into the Music or Download folder and they’ll appear here automatically.",
                    )
                }
                return@LazyVerticalGrid
            }

            when (filter) {
                HomeFilter.Albums -> {
                    items(library.albums, key = { "ga${it.id}" }) { a ->
                        MediaTile(TileData("ga${a.id}", a.title, a.artist, a.cover.artKey) { app.navigate(Routes.album(a.id)) }, 200.dp, Modifier.padding(4.dp))
                    }
                    return@LazyVerticalGrid
                }
                HomeFilter.Artists -> {
                    items(library.artists, key = { "gr${it.name}" }) { a ->
                        MediaTile(TileData("gr${a.name}", a.name, songCount(a.songs.size), a.cover.artKey, circle = true) { app.navigate(Routes.artist(a.name)) }, 200.dp, Modifier.padding(4.dp))
                    }
                    return@LazyVerticalGrid
                }
                HomeFilter.Playlists -> {
                    val liked = smart[SmartCollection.Kind.Liked]?.songs.orEmpty()
                    item(key = "gl") { MediaTile(TileData("gl", "Liked Songs", songCount(liked.size), liked.firstOrNull()?.artKey) { app.navigate(Routes.smart(SmartCollection.Kind.Liked)) }, 200.dp, Modifier.padding(4.dp)) }
                    items(playlists, key = { "gp${it.id}" }) { p ->
                        MediaTile(TileData("gp${p.id}", p.name, songCount(p.songs.size), p.songs.firstOrNull()?.artKey) { app.navigate(Routes.playlist(p.id)) }, 200.dp, Modifier.padding(4.dp))
                    }
                    items(mixes, key = { "gm${it.key}" }) { m ->
                        MediaTile(TileData("gm${m.key}", m.title, m.description, m.cover.artKey, cover = { mod -> com.localfy.app.ui.components.MixCover(m, mod) }) { app.navigate(Routes.mix(m.key)) }, 200.dp, Modifier.padding(4.dp))
                    }
                    return@LazyVerticalGrid
                }
                HomeFilter.All -> Unit
            }

            // Continue listening hero. Always present (with a shuffle prompt when idle) so starting
            // playback never inserts a card above what you're looking at.
            if (current == null) full("hero") {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(108.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocalfyColors.Tint)
                        .pressable(pressedScale = 0.98f) { app.player.playSongs(library.songs, shuffle = true, source = "All songs") }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("START LISTENING", style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
                        Text("Shuffle your library", style = MaterialTheme.typography.titleLarge)
                        Text("${library.songs.size} songs ready to play", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
                    }
                    BigPlayButton(false, { app.player.playSongs(library.songs, shuffle = true, source = "All songs") }, size = 50.dp)
                }
            }
            if (current != null) full("hero") { DarkSurface {
                val position by app.player.positionMs.collectAsStateWithLifecycle()
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(lerp(glow, Color.Black, 0.1f), lerp(glow, Color.Black, 0.55f))))
                        .pressable(pressedScale = 0.98f, onClick = app.openPlayer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(current.artKey, Modifier.size(84.dp).shadow(12.dp, RoundedCornerShape(8.dp)), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (player.isPlaying) "NOW PLAYING" else "CONTINUE LISTENING", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                        Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        val p = if (player.durationMs > 0) (position / player.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxWidth(p).height(3.dp).background(Color.White))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    BigPlayButton(player.isPlaying, app.player::togglePlay, size = 50.dp, container = Color.White)
                }
            } }

            // Quick picks: liked songs, playlists, recent albums and mixes.
            val recentAlbums = smart[SmartCollection.Kind.RecentlyPlayed]?.songs.orEmpty()
                .map { it.albumId }.distinct().mapNotNull { library.albumById[it] }
            val quick = buildList {
                val likedSongs = smart[SmartCollection.Kind.Liked]?.songs.orEmpty()
                add(TileData("liked", "Liked Songs", songCount(likedSongs.size), likedSongs.firstOrNull()?.artKey) { app.navigate(Routes.smart(SmartCollection.Kind.Liked)) })
                playlists.take(3).forEach { p -> add(TileData("p${p.id}", p.name, "Playlist", p.songs.firstOrNull()?.artKey) { app.navigate(Routes.playlist(p.id)) }) }
                recentAlbums.take(4).forEach { a -> add(TileData("ra${a.id}", a.title, a.artist, a.cover.artKey) { app.navigate(Routes.album(a.id)) }) }
                mixes.take(4).forEach { m -> add(TileData("qm${m.key}", m.title, m.description, m.cover.artKey, cover = { mod -> com.localfy.app.ui.components.MixCover(m, mod) }) { app.navigate(Routes.mix(m.key)) }) }
                library.albums.shuffled(java.util.Random(7)).take(8).forEach { a ->
                    add(TileData("qa${a.id}", a.title, a.artist, a.cover.artKey) { app.navigate(Routes.album(a.id)) })
                }
            }.distinctBy { it.title }.take(quickColumns * if (wide) 2 else 4)

            full("quick") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.chunked(quickColumns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { QuickTile(it, Modifier.weight(1f), playing = player.isPlaying && it.title == player.source) }
                            repeat(quickColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            // Generated playlists, grouped like Spotify's Home.
            com.localfy.app.data.MixSection.entries.forEach { section ->
                val inSection = mixes.filter { it.section == section }
                if (inSection.isNotEmpty()) full("mixes-${section.name}") {
                    val name = app.profiles.profile.collectAsStateWithLifecycle().value.name
                    TileShelf(
                        if (section == com.localfy.app.data.MixSection.MadeForYou && name.isNotBlank()) "Made for $name" else section.title,
                        inSection.map { m -> TileData("m${m.key}", m.title, m.description, m.cover.artKey, cover = { mod -> com.localfy.app.ui.components.MixCover(m, mod) }) { app.navigate(Routes.mix(m.key)) } },
                        tileWidth,
                        eyebrow = if (section == com.localfy.app.data.MixSection.MadeForYou) "Learned from your listening" else null,
                    )
                }
            }
            full("jump") {
                TileShelf("Jump back in", recentAlbums.take(12).map { a ->
                    TileData("j${a.id}", a.title, a.artist, a.cover.artKey) { app.navigate(Routes.album(a.id)) }
                }, tileWidth)
            }
            full("repeat") {
                val onRepeat = smart[SmartCollection.Kind.MostPlayed]?.songs.orEmpty()
                TileShelf("On repeat", onRepeat.take(12).mapIndexed { i, s ->
                    TileData("r${s.id}", s.title, "${stats[s.id]?.playCount ?: 0} plays • ${s.artist}", s.artKey) {
                        app.player.playSongs(onRepeat, i, shuffle = false, source = SmartCollection.Kind.MostPlayed.title)
                    }
                }, tileWidth, action = "Show all") { app.navigate(Routes.smart(SmartCollection.Kind.MostPlayed)) }
            }
            full("added") {
                val added = smart[SmartCollection.Kind.RecentlyAdded]?.songs.orEmpty()
                    .map { it.albumId }.distinct().mapNotNull { library.albumById[it] }.take(12)
                TileShelf("Recently added", added.map { a ->
                    TileData("n${a.id}", a.title, a.artist, a.cover.artKey) { app.navigate(Routes.album(a.id)) }
                }, tileWidth, action = "Show all") { app.navigate(Routes.smart(SmartCollection.Kind.RecentlyAdded)) }
            }
            full("artists") {
                val top = library.artists.sortedByDescending { a -> a.songs.sumOf { stats[it.id]?.playCount ?: 0 } * 10 + a.songs.size }.take(12)
                TileShelf("Your top artists", top.map { a ->
                    TileData("ar${a.name}", a.name, "Artist", a.cover.artKey, circle = true) { app.navigate(Routes.artist(a.name)) }
                }, tileWidth)
            }
            full("forgotten") {
                val songs = smart[SmartCollection.Kind.Forgotten]?.songs.orEmpty()
                TileShelf("Forgotten favourites", songs.take(12).mapIndexed { i, s ->
                    TileData("f${s.id}", s.title, s.artist, s.artKey) { app.player.playSongs(songs, i, source = SmartCollection.Kind.Forgotten.title) }
                }, tileWidth, eyebrow = "Loved once, not heard lately")
            }
            full("undiscovered") {
                val songs = smart[SmartCollection.Kind.NeverPlayed]?.songs.orEmpty()
                TileShelf("Undiscovered in your library", songs.take(12).mapIndexed { i, s ->
                    TileData("u${s.id}", s.title, s.artist, s.artKey) { app.player.playSongs(songs, i, source = SmartCollection.Kind.NeverPlayed.title) }
                }, tileWidth, action = "Show all") { app.navigate(Routes.smart(SmartCollection.Kind.NeverPlayed)) }
            }
            full("shuffle-all") {
                Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                            .pressable { app.player.playSongs(library.songs, shuffle = true, source = "All songs") }
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Shuffle, null, tint = LocalPalette.current.onBrand)
                        Spacer(Modifier.width(8.dp))
                        Text("Shuffle all ${library.songs.size} songs", color = LocalPalette.current.onBrand, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun LoadingSkeleton(columns: Int) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(columns) { Box(Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(8.dp)).shimmer()) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.width(180.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).shimmer())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { Box(Modifier.width(140.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).shimmer()) }
        }
    }
}


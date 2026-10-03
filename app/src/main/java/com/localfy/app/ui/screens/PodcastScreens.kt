package com.localfy.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.AddCircleOutline
import com.localfy.app.ui.components.BigPlayButton
import com.localfy.app.ui.components.PageHeader
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.Song
import com.localfy.app.data.db.EpisodeEntity
import com.localfy.app.data.podcast.PodcastSearchResult
import com.localfy.app.data.podcast.Show
import com.localfy.app.data.podcast.resumeKey
import com.localfy.app.data.podcast.toSong
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.art.rememberArtColor
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.MediaTile
import com.localfy.app.ui.components.Pill
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.FittedTileRow
import com.localfy.app.ui.components.TileData
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.launch

private val PopularSearches = listOf("News", "Comedy", "True crime", "Technology", "History", "Science", "Business", "Sports")

private fun podcastArt(url: String?, id: Long) = ArtKey(id, -id - 1_000_000, url)

@Composable
fun PodcastsScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val shows = app.podcasts.shows.collectAsStateWithLifecycle().value.filter { it.podcast.kind == com.localfy.app.data.podcast.KIND_PODCAST && it.podcast.subscribedAt > 0L }
    val resume by app.podcasts.resume.collectAsStateWithLifecycle()
    val refreshing by app.podcasts.refreshing.collectAsStateWithLifecycle()
    val local by app.repo.localPodcasts.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val search = remember { com.localfy.app.data.podcast.SpokenSearch(scope, app.podcasts::search) }
    val searchState by search.state.collectAsStateWithLifecycle()
    val results = searchState.results
    val searching = searchState.searching
    var addRss by remember { mutableStateOf(false) }
    val runSearch: (String) -> Unit = { term ->
        query = term
        focus.clearFocus()
        search.search(term)
    }

    val allEpisodes = shows.flatMap { s -> s.episodes.map { it to s } }
    val inProgress = allEpisodes.filter { (e, _) -> resume["ep:${e.id}"]?.let { !it.played && it.positionMs > 0 } == true }
        .sortedByDescending { (e, _) -> resume["ep:${e.id}"]?.updatedAt ?: 0 }.take(10)
    val newEpisodes = allEpisodes.filter { (e, _) -> resume["ep:${e.id}"]?.played != true }.sortedByDescending { it.first.pubDate }.take(30)
    val downloaded = allEpisodes.filter { it.first.localPath != null }.sortedByDescending { it.first.pubDate }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
        item {
            PageHeader("Podcasts") {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { app.podcasts.refreshAll() }) { Icon(Icons.Rounded.Refresh, "Check for new episodes") }
                IconButton(onClick = { addRss = true }) { Icon(Icons.Rounded.RssFeed, "Add by RSS link") }
            }
        }
        item {
            com.localfy.app.ui.components.MediaSearchField(
                value = query, onValueChange = { query = it; search.clear() },
                placeholder = "Search all podcasts", onSearch = { if (query.isNotBlank()) runSearch(query) },
            )
        }
        if (searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        searchState.error?.let { error -> item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { runSearch(query) }) { Text("Try again") }
            }
        } }
        results?.let { list ->
            item { SectionHeader("Results for “$query”") }
            if (list.isEmpty() && !searching) item { EmptyState("No shows found", "Try another name, or add the show's RSS link.") }
            items(list, key = { it.feedUrl }) { r -> SearchResultRow(r, subscribed = shows.any { it.podcast.feedUrl == r.feedUrl }) }
            return@LazyColumn
        }

        if (shows.isEmpty() && local.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Podcasts, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Find your next show", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Search millions of podcasts, follow the ones you like, and download episodes for offline listening.",
                        style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PopularSearches) { term -> Pill(term, false, { runSearch(term) }) }
            }
        }
        if (inProgress.isNotEmpty()) {
            item { SectionHeader("Continue listening") }
            items(inProgress, key = { "c${it.first.id}" }) { (e, s) -> EpisodeRow(e.toSong(s.podcast), e, showArt = true) }
        }
        if (shows.isNotEmpty()) {
            item { SectionHeader("Your shows") }
            item {
                FittedTileRow(shows, key = { it.id }, tileWidth = 140.dp) { s ->
                        TileData("s${s.id}", s.podcast.title, s.podcast.author, podcastArt(s.podcast.artworkUrl, s.id)) { app.navigate(Routes.show(s.id)) }
}
            }
            item { SectionHeader("New episodes") }
            items(newEpisodes, key = { "n${it.first.id}" }) { (e, s) -> EpisodeRow(e.toSong(s.podcast), e, showArt = true) }
        }
        if (downloaded.isNotEmpty()) {
            item { SectionHeader("Downloaded", eyebrow = "Available offline") }
            items(downloaded, key = { "d${it.first.id}" }) { (e, s) -> EpisodeRow(e.toSong(s.podcast), e, showArt = true) }
        }
        if (local.isNotEmpty()) {
            item { SectionHeader("On this device", eyebrow = "Podcast files in your storage") }
            item {
                FittedTileRow(local.groupBy { it.album }.entries.toList(), key = { it.key }, tileWidth = 140.dp) { (album, eps) ->
                        TileData("l$album", album, "${eps.size} episodes", eps.first().artKey) { app.navigate(Routes.localShow(album)) }
}
            }
        }
    }

    if (addRss) {
        var url by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addRss = false },
            containerColor = LocalfyColors.SurfaceHigh,
            title = { Text("Add a show by RSS") },
            text = {
                Column {
                    OutlinedTextField(url, { url = it; error = null }, singleLine = true, placeholder = { Text("https://example.com/feed.xml") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && url.startsWith("http"), onClick = {
                    busy = true
                    scope.launch {
                        val id = app.podcasts.subscribe(url.trim())
                        busy = false
                        if (id == null) error = "Couldn't read that feed." else { addRss = false; app.navigate(Routes.show(id)) }
                    }
                }) { Text("Follow") }
            },
            dismissButton = { TextButton(onClick = { addRss = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SearchResultRow(r: PodcastSearchResult, subscribed: Boolean) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun open(follow: Boolean) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val id = app.podcasts.subscribe(r.feedUrl, r.artworkUrl, follow = follow)
                if (id != null) app.navigate(Routes.show(id)) else error = "Couldn't open this show. Tap to try again."
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = "Couldn't open this show. Tap to try again."
            } finally { busy = false }
        }
    }
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { open(false) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(ArtKey(r.feedUrl.hashCode().toLong(), r.feedUrl.hashCode().toLong(), r.artworkUrl), Modifier.size(64.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.author, r.genre).joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(8.dp))
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else FollowButton(subscribed) { open(true) }
    }
}

@Composable
private fun FollowButton(following: Boolean, onClick: () -> Unit) {
    Text(
        if (following) "Following" else "Follow",
        style = MaterialTheme.typography.labelLarge,
        color = if (following) LocalfyColors.TextPrimary else LocalPalette.current.onBrand,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (following) LocalfyColors.Tint else MaterialTheme.colorScheme.primary)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun relativeDate(ms: Long): String = when {
    ms <= 0 -> ""
    System.currentTimeMillis() - ms < DateUtils.DAY_IN_MILLIS * 7 -> DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
    else -> java.text.SimpleDateFormat(if (System.currentTimeMillis() - ms > DateUtils.YEAR_IN_MILLIS) "MMM d, yyyy" else "MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms))
}

private fun minutes(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60} hr ${m % 60} min" else "$m min"
}

/** One episode: date & length, title, notes preview, progress, then play / download / played. */
@Composable
fun EpisodeRow(song: Song, episode: EpisodeEntity?, showArt: Boolean, onPlay: (() -> Unit)? = null) {
    val app = LocalApp.current
    val player = rememberPlayerState()
    val resume by app.podcasts.resume.collectAsStateWithLifecycle()
    val progressMap by app.podcasts.downloadProgress.collectAsStateWithLifecycle()
    val r = resume[song.resumeKey]
    val isCurrent = player.currentId == song.id
    val dur = song.durationMs.takeIf { it > 0 } ?: r?.durationMs ?: 0
    val meta = buildList {
        relativeDate(episode?.pubDate ?: song.dateAddedSec * 1000).takeIf { it.isNotEmpty() }?.let(::add)
        when {
            r?.played == true -> add("Played")
            r != null && r.positionMs > 0 && dur > 0 -> add("${minutes(dur - r.positionMs)} left")
            dur > 0 -> add(minutes(dur))
        }
    }.joinToString(" • ")

    val play = onPlay ?: { app.player.playEpisode(song) }
    Column(Modifier.fillMaxWidth().pressable(pressedScale = 0.99f) { play() }.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            if (showArt) {
                Artwork(song.artKey, Modifier.size(56.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (showArt) Text(song.album, style = MaterialTheme.typography.labelMedium, color = LocalfyColors.TextSecondary, maxLines = 1)
                Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalfyColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        episode?.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(meta, style = MaterialTheme.typography.labelMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.weight(1f))
            if (episode != null) {
                val progress = progressMap[episode.id]
                when {
                    episode.localPath != null -> IconButton(onClick = { app.podcasts.deleteDownload(episode.id) }) {
                        Icon(Icons.Rounded.DownloadDone, "Downloaded — tap to remove", tint = MaterialTheme.colorScheme.primary)
                    }
                    progress != null || episode.downloadId != null -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    }
                    else -> IconButton(onClick = { app.podcasts.download(episode.id) }) { Icon(Icons.Rounded.Download, "Download", tint = LocalfyColors.TextSecondary) }
                }
            }
            IconButton(onClick = { app.podcasts.setPlayed(song.resumeKey, r?.played != true, dur) }) {
                Icon(
                    if (r?.played == true) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    if (r?.played == true) "Mark as unplayed" else "Mark as played",
                    tint = if (r?.played == true) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary,
                )
            }
            IconButton(onClick = { app.openSongMenu(song, SongMenuExtras()) }) { Icon(Icons.Rounded.MoreVert, "More", tint = LocalfyColors.TextSecondary) }
            BigPlayButton(playing = isCurrent && player.isPlaying, onClick = { if (isCurrent) app.player.togglePlay() else play() }, size = 44.dp)
        }
        if (r != null && !r.played && r.positionMs > 0 && dur > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (r.positionMs / dur.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
    }
}

private enum class EpisodeFilter(val label: String) { All("All"), Unplayed("Unplayed"), Downloaded("Downloaded") }

@Composable
fun PodcastShowScreen(id: Long) {
    val app = LocalApp.current
    val shows by app.podcasts.shows.collectAsStateWithLifecycle()
    val resume by app.podcasts.resume.collectAsStateWithLifecycle()
    val show: Show = shows.firstOrNull { it.id == id } ?: return EmptyState("Show not found", "It may have been unfollowed.")
    val songs = show.episodes.map { it to it.toSong(show.podcast) }
    ShowLayout(
        title = show.podcast.title,
        author = show.podcast.author,
        description = show.podcast.description,
        art = podcastArt(show.podcast.artworkUrl, show.id),
        episodes = songs,
        resume = resume.mapValues { it.value.played },
        following = show.podcast.subscribedAt > 0L,
        onFollow = { app.podcasts.setFollowing(show.id, show.podcast.subscribedAt == 0L) },
    )
}

@Composable
fun LocalShowScreen(name: String) {
    val app = LocalApp.current
    val local by app.repo.localPodcasts.collectAsStateWithLifecycle()
    val resume by app.podcasts.resume.collectAsStateWithLifecycle()
    val eps = local.filter { it.album == name }.sortedByDescending { it.dateAddedSec }
    if (eps.isEmpty()) return EmptyState("Nothing here", "These files may have been moved.")
    ShowLayout(name, eps.first().artist, "Podcast files stored on this device.", eps.first().artKey, eps.map { null to it }, resume.mapValues { it.value.played }, null, null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowLayout(
    title: String,
    author: String,
    description: String,
    art: ArtKey,
    episodes: List<Pair<EpisodeEntity?, Song>>,
    resume: Map<String, Boolean>,
    following: Boolean?,
    onFollow: (() -> Unit)?,
) {
    val app = LocalApp.current
    val player = rememberPlayerState()
    var filter by rememberSaveable { mutableStateOf(EpisodeFilter.All) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = episodes
        .filter { (e, s) ->
            when (filter) {
                EpisodeFilter.All -> true
                EpisodeFilter.Unplayed -> resume[s.resumeKey] != true
                EpisodeFilter.Downloaded -> e == null || e.localPath != null
            }
        }
        .let { list -> if (newestFirst) list else list.reversed() }

    val latest = episodes.firstOrNull()?.second
    CollectionPage(
        title = title, kindLabel = "Podcast", subtitle = author, summary = "${episodes.size} episodes", art = art,
        playing = latest != null && player.currentId == latest.id && player.isPlaying, playEnabled = latest != null,
        onPlay = { latest?.let { if (player.currentId == it.id) app.player.togglePlay() else app.player.playEpisode(it) } },
        headerActions = {
            if (following != null && onFollow != null) IconButton(onClick = onFollow) {
                Icon(if (following) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (following) "Unfollow podcast" else "Follow podcast", tint = if (following) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary)
            }
        },
    ) {
        if (description.isNotBlank()) item {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize().pressable(pressedScale = 1f) { expanded = !expanded })
        }
        item {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EpisodeFilter.entries.forEach { f -> Pill(f.label, filter == f, { filter = f }, modifier = Modifier.align(Alignment.CenterVertically)) }
                TextButton(onClick = { newestFirst = !newestFirst }) { Text(if (newestFirst) "Newest" else "Oldest", color = LocalfyColors.TextPrimary) }
            }
        }
        item { Text("${shown.size} episodes", style = MaterialTheme.typography.labelMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (shown.isEmpty()) item {
            EmptyState(
                when (filter) { EpisodeFilter.Downloaded -> "No downloaded episodes"; EpisodeFilter.Unplayed -> "You're all caught up"; EpisodeFilter.All -> "No episodes yet" },
                when (filter) { EpisodeFilter.Downloaded -> "Download an episode from All to listen offline."; EpisodeFilter.Unplayed -> "Choose All to listen again."; EpisodeFilter.All -> "Check for new episodes from Podcasts." },
            )
        }
        items(shown, key = { it.second.id }) { (e, s) -> EpisodeRow(s, e, showArt = false) }
    }
}

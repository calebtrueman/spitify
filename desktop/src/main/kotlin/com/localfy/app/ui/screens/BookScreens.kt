package com.localfy.app.ui.screens

import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.components.PageHeader
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.localfy.app.data.db.ResumeEntity
import com.localfy.app.data.podcast.BookSearchResult
import com.localfy.app.data.podcast.KIND_AUDIOBOOK
import com.localfy.app.data.podcast.resumeKey
import com.localfy.app.data.podcast.toSong
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.art.rememberArtColor
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.Pill
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.launch

private val Classics = listOf("Sherlock Holmes", "Jane Austen", "Mark Twain", "Dickens", "Tolkien", "Shakespeare", "Poe", "Jules Verne", "Grimm", "Dracula")

/** A book on this device or from LibriVox, normalised for the UI. */
private data class BookItem(
    val key: String,
    val title: String,
    val author: String,
    val art: ArtKey,
    val chapters: List<Song>,
    val episodes: List<EpisodeEntity?>,
    val route: String,
)

private fun hours(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} hr ${m % 60} min" else "$m min"
}

/** Where you are in a book: the most recently touched unfinished chapter (or the first). */
private fun bookProgress(chapters: List<Song>, resume: Map<String, ResumeEntity>): Triple<Int, Long, Float> {
    val total = chapters.sumOf { it.durationMs }.coerceAtLeast(1)
    var listened = 0L
    chapters.forEach { c -> resume[c.resumeKey]?.let { r -> listened += if (r.played) c.durationMs else r.positionMs } }
    val lastTouched = chapters.withIndex().maxByOrNull { (_, c) -> resume[c.resumeKey]?.updatedAt ?: 0 }
    val idx = when {
        lastTouched == null || resume[lastTouched.value.resumeKey] == null -> 0
        resume[lastTouched.value.resumeKey]!!.played -> (lastTouched.index + 1).coerceAtMost(chapters.lastIndex)
        else -> lastTouched.index
    }
    return Triple(idx, (total - listened).coerceAtLeast(0), (listened / total.toFloat()).coerceIn(0f, 1f))
}

@Composable
private fun rememberBooks(): List<BookItem> {
    val app = LocalApp.current
    val shows by app.podcasts.shows.collectAsStateWithLifecycle()
    val local by app.repo.localBooks.collectAsStateWithLifecycle()
    val online = shows.filter { it.podcast.kind == KIND_AUDIOBOOK }.map { s ->
        val eps = s.episodes.sortedBy { it.position }
        BookItem("lv${s.id}", s.podcast.title, s.podcast.author, ArtKey(s.id, -s.id - 1_000_000, s.podcast.artworkUrl), eps.map { it.toSong(s.podcast) }, eps, Routes.book(s.id))
    }
    val mine = local.groupBy { it.albumId }.map { (albumId, ch) ->
        val sorted = ch.sortedWith(compareBy<Song>({ it.disc }, { it.track }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
        BookItem("lb$albumId", sorted.first().album, sorted.first().albumArtist, sorted.first().artKey, sorted, sorted.map { null }, Routes.localBook(albumId))
    }
    return online + mine
}

@Composable
fun BooksScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val books = rememberBooks()
    val resume by app.podcasts.resume.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val search = remember { com.localfy.app.data.podcast.SpokenSearch(scope, app.podcasts::searchBooks) }
    val searchState by search.state.collectAsStateWithLifecycle()
    val results = searchState.results
    val searching = searchState.searching
    val runSearch: (String) -> Unit = { term ->
        query = term; focus.clearFocus(); search.search(term)
    }
    val inProgress = books.filter { b -> b.chapters.any { resume[it.resumeKey] != null } && bookProgress(b.chapters, resume).third < 0.99f }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
        item {
            PageHeader("Audiobooks")
        }
        item {
            com.localfy.app.ui.components.MediaSearchField(
                value = query, onValueChange = { query = it; search.clear() },
                placeholder = "Search books by title or author", onSearch = { if (query.isNotBlank()) runSearch(query) },
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
            item { SectionHeader("LibriVox results", eyebrow = "Free public-domain recordings") }
            if (list.isEmpty() && !searching) item { EmptyState("No books found", "LibriVox searches titles from the start, or authors by surname.") }
            items(list, key = { it.id }) { r -> BookResultRow(r) }
            return@LazyColumn
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Classics) { c -> Pill(c, false, { runSearch(c) }) }
            }
        }
        if (inProgress.isNotEmpty()) {
            item { SectionHeader("Continue listening") }
            items(inProgress, key = { "c" + it.key }) { b -> ContinueBookRow(b, resume) }
        }
        if (books.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your bookshelf is empty", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Search LibriVox for free classics, or copy your own audiobooks into an “Audiobooks” folder (MP3, M4A, M4B…). Spitify fills in missing titles, authors and covers.",
                        style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
            item { SectionHeader("Your books") }
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    books.chunked(3).forEach { row ->
                        Row {
                            row.forEach { b -> BookCover(b, Modifier.weight(1f)) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCover(b: BookItem, modifier: Modifier) {
    val app = LocalApp.current
    Column(modifier.padding(4.dp).pressable { app.navigate(b.route) }) {
        Artwork(b.art, Modifier.fillMaxWidth().aspectRatio(0.72f).shadow(10.dp, RoundedCornerShape(6.dp)), RoundedCornerShape(6.dp))
        Spacer(Modifier.height(6.dp))
        Text(b.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(b.author, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun ContinueBookRow(b: BookItem, resume: Map<String, ResumeEntity>) {
    val app = LocalApp.current
    val (idx, left, progress) = bookProgress(b.chapters, resume)
    Row(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { app.navigate(b.route) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(b.art, Modifier.size(width = 56.dp, height = 78.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Chapter ${idx + 1} of ${b.chapters.size} · ${hours(left)} left", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), drawStopIndicator = {}, gapSize = 0.dp)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).pressable { app.player.playBook(b.chapters, idx, b.title) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, "Continue", tint = LocalPalette.current.onBrand) }
    }
}

@Composable
private fun BookResultRow(r: BookSearchResult) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val shows by app.podcasts.shows.collectAsStateWithLifecycle()
    val owned = shows.firstOrNull { it.podcast.feedUrl == r.rssUrl }
    val open = {
        if (owned != null) app.navigate(Routes.book(owned.id)) else if (!busy) {
            busy = true; error = null
            scope.launch {
                try {
                    val id = app.podcasts.subscribe(r.rssUrl, r.coverUrl, KIND_AUDIOBOOK, r.title, r.author, r.description)
                    if (id != null) app.navigate(Routes.book(id)) else error = "Couldn't add this book. Tap to try again."
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    error = "Couldn't add this book. Tap to try again."
                } finally { busy = false }
            }
        }
    }
    Row(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { open() }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(ArtKey(r.id.hashCode().toLong(), r.id.hashCode().toLong(), r.coverUrl), Modifier.size(width = 56.dp, height = 78.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.author, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                listOfNotNull(r.totalSeconds.takeIf { it > 0 }?.let { hours(it * 1000) }, r.sections.takeIf { it > 0 }?.let { "$it chapters" }, r.language.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextTertiary,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Text(
            if (owned != null) "In library" else "Add",
            style = MaterialTheme.typography.labelLarge,
            color = if (owned != null) LocalfyColors.TextPrimary else LocalPalette.current.onBrand,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (owned != null) LocalfyColors.Tint else MaterialTheme.colorScheme.primary).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun BookScreen(id: Long) {
    val app = LocalApp.current
    val books = rememberBooks()
    val shows by app.podcasts.shows.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.key == "lv$id" } ?: return EmptyState("Book not found", "It may have been removed.")
    val description = shows.firstOrNull { it.id == id }?.podcast?.description.orEmpty()
    BookLayout(book, description, onRemove = { app.podcasts.unsubscribe(id); app.nav.popBackStack() }, editable = false)
}

@Composable
fun LocalBookScreen(albumId: Long) {
    val books = rememberBooks()
    val book = books.firstOrNull { it.key == "lb$albumId" } ?: return EmptyState("Book not found", "The files may have been moved.")
    BookLayout(book, "Stored on this device · ${book.chapters.size} files", onRemove = null, editable = true)
}

@Composable
private fun BookLayout(book: BookItem, description: String, onRemove: (() -> Unit)?, editable: Boolean) {
    val app = LocalApp.current
    val resume by app.podcasts.resume.collectAsStateWithLifecycle()
    val player = rememberPlayerState()
    val (idx, left, progress) = bookProgress(book.chapters, resume)
    val started = book.chapters.any { resume[it.resumeKey] != null }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val total = book.chapters.sumOf { it.durationMs }

    val isCurrentBook = book.chapters.any { it.id == player.currentId }
    CollectionPage(
        title = book.title, kindLabel = "Audiobook", subtitle = book.author,
        summary = "${book.chapters.size} chapters • ${hours(total)}", art = book.art, artAspectRatio = 0.72f,
        playing = isCurrentBook && player.isPlaying, playEnabled = book.chapters.isNotEmpty(),
        onPlay = { if (isCurrentBook && player.hasMedia) app.player.togglePlay() else app.player.playBook(book.chapters, idx, book.title) },
        headerActions = {
            if (editable) IconButton(onClick = { app.editMetadata(book.chapters, true) }) { Icon(Icons.Rounded.Edit, "Edit book info & cover", tint = LocalfyColors.TextSecondary) }
            val downloadable = book.episodes.filterNotNull().filter { it.localPath == null }
            if (downloadable.isNotEmpty()) IconButton(onClick = { app.podcasts.downloadAll(downloadable.map { it.id }) }) { Icon(Icons.Rounded.Download, "Download whole book", tint = LocalfyColors.TextSecondary) }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "Remove from library", tint = LocalfyColors.TextSecondary) }
        },
    ) {
        if (started) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), drawStopIndicator = {}, gapSize = 0.dp)
                Text("Continue chapter ${idx + 1} • ${(progress * 100).toInt()}% • ${hours(left)} left", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (description.isNotBlank()) item {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize().pressable(pressedScale = 1f) { expanded = !expanded })
        }
        item { SectionHeader("Chapters") }
        itemsIndexed(book.chapters, key = { _, c -> c.id }) { i, c ->
            EpisodeRow(c, book.episodes.getOrNull(i), showArt = false, onPlay = { app.player.playBook(book.chapters, i, book.title) })
        }
    }
}

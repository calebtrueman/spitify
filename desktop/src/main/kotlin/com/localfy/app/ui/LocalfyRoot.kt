package com.localfy.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.desktop.AppPaths
import com.localfy.app.ui.components.AddToPlaylistDialog
import com.localfy.app.ui.components.CreatePlaylistDialog
import com.localfy.app.ui.components.MetadataEditor
import com.localfy.app.ui.components.SongMenuSheet
import com.localfy.app.ui.player.ContinueCard
import com.localfy.app.ui.player.DEVICES_ROUTE
import com.localfy.app.ui.player.LinkRequestDialog
import com.localfy.app.ui.player.MiniPlayer
import com.localfy.app.ui.player.PlayingOnBar
import com.localfy.app.ui.player.NowPlayingPane
import com.localfy.app.ui.player.TheaterPlayer
import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.screens.*
import com.localfy.app.ui.theme.DarkSurface
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.theme.ThemeFrame
import java.io.File

private data class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Tab(Routes.SEARCH, "Search", Icons.Rounded.Search, Icons.Rounded.Search),
    Tab(Routes.PODCASTS, "Podcasts", Icons.Rounded.Podcasts, Icons.Outlined.Podcasts),
    Tab(Routes.BOOKS, "Books", Icons.AutoMirrored.Rounded.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    Tab(Routes.LIBRARY, "Your Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
    Tab(Routes.FRIENDS, "Friends", Icons.Rounded.People, Icons.Outlined.People),
)

/** Pinned to the bottom of the sidebar. */
private val settingsTab = Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings)

/** Keyboard shortcuts arrive at the window (Main.kt) and are routed here. */
object ShortcutRouter {
    @Volatile var handler: ((KeyEvent) -> Boolean)? = null
    fun handle(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}

/**
 * The desktop window, laid out like the unfolded Fold: navigation rail and pages on the left, the
 * always-on Now Playing pane (Playing / Lyrics / Queue) on the right. Hiding the pane brings back
 * the bottom player bar; ⤢ opens the full-window player (the Fold's dual-screen "theater" player).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LocalfyRoot() {
    val container = LocalContainer.current
    // SPITIFY_START_ROUTE opens a page at launch (handy for checking a screen).
    val nav = remember { Navigator(Routes.HOME).also { n -> System.getenv("SPITIFY_START_ROUTE")?.takeIf { it.isNotBlank() }?.let(n::navigate) } }
    val backDispatcher = remember { BackDispatcher() }
    var menu by remember { mutableStateOf<Pair<Song, SongMenuExtras>?>(null) }
    var addToPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    var editing by remember { mutableStateOf<Pair<List<Song>, Boolean>?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var theater by remember { mutableStateOf(false) }
    val paneVisible by DesktopSettings.paneVisible.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val actions = remember(nav) {
        AppActions(
            repo = container.library,
            player = container.player,
            lyrics = container.lyrics,
            podcasts = container.podcasts,
            taste = container.taste,
            profiles = container.profiles,
            nav = nav,
            openSongMenu = { s, e -> menu = s to e },
            addToPlaylist = { addToPlaylist = it },
            editMetadata = { songs, album -> if (songs.isNotEmpty()) editing = songs to album },
            openPlayer = { DesktopSettings.setPaneVisible(true) },
        ).also { it.collapsePlayer = { theater = false } }
    }

    // Player messages (unsupported file, offline stream...) and the app's short notes, as snackbars.
    LaunchedEffect(Unit) { container.player.messages.collect { Toasts.show(it) } }
    LaunchedEffect(Unit) { container.deviceSync.messages.collect { Toasts.show(it) } }
    LaunchedEffect(Unit) {
        Toasts.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
    LaunchedEffect(Unit) {
        DesktopEvents.incomingLink.collect { link ->
            if (link != null) {
                theater = false
                actions.navigate(Routes.incoming(link))
                DesktopEvents.incomingLink.value = null
            }
        }
    }
    LaunchedEffect(Unit) { DesktopEvents.droppedFiles.collect { files -> handleDrop(container, actions, files) } }

    val focusManager = LocalFocusManager.current
    fun goBack() {
        if (!backDispatcher.dispatch()) {
            if (theater) theater = false else nav.popBackStack()
        }
    }
    DisposableEffect(actions, focusManager) {
        ShortcutRouter.handler = { event -> handleShortcut(event, container, actions, focusManager, ::goBack) }
        onDispose { ShortcutRouter.handler = null }
    }

    CompositionLocalProvider(LocalApp provides actions, LocalBackDispatcher provides backDispatcher) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(LocalfyColors.Background).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let { PointerAnchor.lastPress = it.position }
                            if (event.button == PointerButton.Back) goBack()
                        }
                    }
                }
            },
        ) {
            val windowWidth = maxWidth
            val player = rememberPlayerState()
            val paneWidth = when {
                windowWidth >= 1500.dp -> 440.dp
                windowWidth >= 1100.dp -> 400.dp
                else -> 360.dp
            }
            val entry = nav.current
            val currentTab = nav.backStack.getOrNull(1)?.route?.takeIf { it in TopLevelRoutes } ?: Routes.HOME

            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = LocalfyColors.Background, header = { Spacer(Modifier.width(1.dp)) }) {
                    (tabs + settingsTab).forEach { t ->
                        if (t === settingsTab) Spacer(Modifier.weight(1f))
                        val selected = currentTab == t.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = { theater = false; actions.navigateTopLevel(t.route) },
                            icon = { Icon(if (selected) t.selected else t.unselected, t.label) },
                            label = { Text(t.label, maxLines = 1) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = LocalfyColors.SurfaceHigh,
                                selectedIconColor = LocalfyColors.TextPrimary,
                                selectedTextColor = LocalfyColors.TextPrimary,
                                unselectedIconColor = LocalfyColors.TextTertiary,
                                unselectedTextColor = LocalfyColors.TextTertiary,
                            ),
                        )
                    }
                    if (!paneVisible) {
                        NavigationRailItem(
                            selected = false,
                            onClick = { DesktopSettings.setPaneVisible(true) },
                            icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Show player") },
                            label = { Text("Player") },
                            colors = NavigationRailItemDefaults.colors(
                                unselectedIconColor = LocalfyColors.TextTertiary,
                                unselectedTextColor = LocalfyColors.TextTertiary,
                            ),
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        val holder = rememberSaveableStateHolder()
                        AnimatedContent(
                            entry,
                            transitionSpec = {
                                if (nav.poppedLast) fadeIn(tween(220)) togetherWith (fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 12 })
                                else (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }) togetherWith fadeOut(tween(180))
                            },
                            contentKey = { it.id },
                            label = "page",
                        ) { page ->
                            holder.SaveableStateProvider(page.id) { RouteContent(page.route, onCreatePlaylist = { creatingPlaylist = true }) }
                        }
                        LaunchedEffect(nav.backStack) { nav.takeDiscarded().forEach { holder.removeState(it.id) } }
                    }
                    // Your other devices: "Playing on …" and "Continue from …" sit above the player bar.
                    if (!paneVisible) { PlayingOnBar(); ContinueCard() }
                    if (!paneVisible && player.hasMedia) MiniPlayer(onExpand = { DesktopSettings.setPaneVisible(true) }, Modifier.padding(bottom = 4.dp))
                }
                if (paneVisible) {
                    DarkSurface { NowPlayingPane(onHide = { DesktopSettings.setPaneVisible(false) }, onTheater = { theater = true }, modifier = Modifier.width(paneWidth)) }
                }
            }

            ThemeFrame(Modifier.fillMaxSize())

            // Full-window player: the Fold's dual-screen player, split down the middle.
            AnimatedVisibility(
                visible = theater && player.hasMedia,
                enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.96f),
                exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.96f),
            ) {
                DarkSurface { TheaterPlayer(windowWidth / 2, 0.dp, onExit = { theater = false }) }
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (!paneVisible && player.hasMedia) 84.dp else 20.dp)) { data ->
                Snackbar(
                    data, Modifier.widthIn(max = 520.dp), shape = RoundedCornerShape(10.dp),
                    containerColor = LocalfyColors.SurfaceHighest, contentColor = LocalfyColors.TextPrimary,
                )
            }
        }

        menu?.let { (song, extras) ->
            SongMenuSheet(song, extras, onDismiss = { menu = null }, onNavigated = { theater = false })
        }
        addToPlaylist?.let { songs -> AddToPlaylistDialog(songs) { addToPlaylist = null } }
        editing?.let { (songs, album) -> MetadataEditor(songs, album) { editing = null } }
        LinkRequestDialog()
        if (creatingPlaylist) {
            CreatePlaylistDialog(onDismiss = { creatingPlaylist = false }, onCreated = { actions.navigate(Routes.playlist(it)) })
        }
    }
}

/** Every page of the app, by route ("album/12", "artist/Name"...). */
@Composable
private fun RouteContent(route: String, onCreatePlaylist: () -> Unit) {
    val args = Routes.args(route)
    fun arg(i: Int) = args.getOrNull(i).orEmpty()
    fun long(i: Int) = args.getOrNull(i)?.toLongOrNull() ?: 0L
    when (route.substringBefore('/')) {
        Routes.HOME -> HomeScreen()
        Routes.SEARCH -> SearchScreen()
        Routes.LIBRARY -> LibraryScreen(onCreatePlaylist = onCreatePlaylist)
        Routes.STATS -> StatsScreen()
        Routes.SETTINGS -> SettingsScreen()
        Routes.APPEARANCE -> AppearanceScreen()
        Routes.EQUALIZER -> EqualizerScreen()
        Routes.PODCASTS -> PodcastsScreen()
        Routes.BOOKS -> BooksScreen()
        Routes.PROFILE -> ProfileScreen()
        Routes.FRIENDS -> FriendsScreen()
        DEVICES_ROUTE -> DevicesScreen()
        "book" -> BookScreen(long(0))
        "localbook" -> LocalBookScreen(long(0))
        "show" -> PodcastShowScreen(long(0))
        "localshow" -> LocalShowScreen(arg(0))
        "album" -> AlbumScreen(long(0))
        "catalogalbum" -> CatalogPage(arg(0), false)
        "catalogsong" -> CatalogPage(arg(0), true)
        "artist-online" -> ArtistLandingScreen(arg(0))
        "onlineartist" -> OnlineArtistScreen(arg(0))
        "spotify-code" -> SpotifyCodeScanScreen()
        "spotify-item" -> SpotifyScannedItemScreen(arg(0), arg(1))
        "spotify-import" -> SpotifyImportScreen()
        "spotifyplaylist" -> PublicPlaylistScreen(arg(0), false)
        "sharedplaylist" -> PublicPlaylistScreen(arg(0), true)
        "rooms" -> RoomsScreen()
        "releases" -> ArtistReleasesScreen()
        "incoming" -> IncomingShareScreen(arg(0))
        "friend" -> FriendProfileScreen(arg(0))
        "hidden-artists" -> HiddenArtistsScreen()
        "friends-settings" -> FriendsSettingsScreen()
        "friend-code" -> FriendCodeScreen()
        "add-friend" -> AddFriendScreen()
        "new-shared-playlist" -> NewSharedPlaylistScreen()
        "artist" -> ArtistScreen(arg(0))
        "playlist" -> PlaylistScreen(long(0))
        "smart" -> SmartScreen(arg(0))
        "mix" -> MixScreen(arg(0))
        "genre" -> GenreScreen(arg(0))
        "folder" -> FolderScreen(arg(0).takeIf { it != "/" }.orEmpty())
        else -> com.localfy.app.ui.components.EmptyState("Page not found", "This link isn't something Spitify can open.")
    }
}

/**
 * Space play/pause · ←/→ seek 5 s · Cmd/Ctrl+←/→ previous/next · Cmd/Ctrl+F search · Cmd/Ctrl+L like ·
 * Esc back. Single keys are ignored while a text field has focus.
 */
private fun handleShortcut(event: KeyEvent, app: LocalfyApp, actions: AppActions, focus: FocusManager, back: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val command = if (AppPaths.isMac) event.isMetaPressed else event.isCtrlPressed
    val typing = TypingFocus.active
    val player = app.player
    return when {
        event.key == Key.Escape -> {
            if (typing) focus.clearFocus() else back()
            true
        }
        command && event.key == Key.F -> {
            if (actions.nav.currentRoute != Routes.SEARCH) actions.navigateTopLevel(Routes.SEARCH)
            DesktopEvents.focusSearch.value = DesktopEvents.focusSearch.value + 1
            true
        }
        command && event.key == Key.L -> {
            val id = player.state.value.currentId
            val song = id?.let(app::resolve)
            if (id != null && song != null && !song.isPodcast) {
                val liked = id in app.library.likedIds.value
                app.library.toggleLike(id)
                Toasts.show(if (liked) "Removed from Liked Songs" else "Added to Liked Songs")
            }
            true
        }
        typing -> false
        command && event.key == Key.DirectionLeft -> { player.previous(); true }
        command && event.key == Key.DirectionRight -> { player.next(); true }
        event.key == Key.Spacebar -> { player.togglePlay(); true }
        event.key == Key.DirectionLeft -> { player.skipBy(-5_000); true }
        event.key == Key.DirectionRight -> { player.skipBy(5_000); true }
        else -> false
    }
}

/**
 * Files dropped on the window: songs already in the library play (or join the queue when something
 * is playing); folders and new files are added to the library's folders and appear after the scan.
 */
private fun handleDrop(app: LocalfyApp, actions: AppActions, files: List<File>) {
    val folders = files.filter { it.isDirectory }
    val audio = files.filter { it.isFile && it.extension.lowercase() in FilePickers.audioExtensions }
    if (folders.isEmpty() && audio.isEmpty()) { Toasts.show("Spitify can't play those files."); return }
    folders.forEach { app.library.addFolder(it) }

    val byPath = app.library.library.value.songs.associateBy { localFileOf(it.sourceUri)?.canonicalPath }
    val known = audio.mapNotNull { byPath[it.canonicalPath] }
    val unknown = audio.filter { byPath[it.canonicalPath] == null }
    if (known.isNotEmpty()) {
        if (app.player.state.value.hasMedia) {
            if (actions.player.addToQueue(known)) Toasts.show(if (known.size == 1) "Added “${known.first().title}” to the queue" else "Added ${known.size} songs to the queue")
        } else actions.player.playSongs(known, 0, shuffle = false, source = "Dropped files")
    }
    val libraryFolders = app.library.folders.value.map { it.canonicalPath }
    val newParents = unknown.mapNotNull { it.parentFile }.distinctBy { it.canonicalPath }
        .filter { parent -> libraryFolders.none { parent.canonicalPath.startsWith(it) } }
    newParents.forEach { app.library.addFolder(it) }
    when {
        folders.isNotEmpty() || newParents.isNotEmpty() -> {
            val names = (folders + newParents).joinToString(", ") { it.name }
            Toasts.show("Adding $names to your library…")
        }
        unknown.isNotEmpty() -> {
            app.library.refresh()
            Toasts.show("Scanning for the new files…")
        }
    }
}

package com.localfy.app.ui

import androidx.compose.material.icons.rounded.People

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.ui.components.AddToPlaylistDialog
import com.localfy.app.ui.components.CreatePlaylistDialog
import com.localfy.app.ui.components.SongMenuSheet
import com.localfy.app.ui.components.MetadataEditor
import com.localfy.app.ui.components.StatusBarScrim
import com.localfy.app.ui.fold.FoldPosture
import com.localfy.app.ui.fold.rememberFoldPosture
import com.localfy.app.ui.player.MiniPlayer
import com.localfy.app.ui.player.NowPlayingFull
import com.localfy.app.ui.player.NowPlayingPane
import com.localfy.app.ui.player.TheaterPlayer
import com.localfy.app.ui.player.TabletopPlayer
import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.screens.AlbumScreen
import com.localfy.app.ui.screens.AppearanceScreen
import com.localfy.app.ui.screens.ArtistScreen
import com.localfy.app.ui.screens.EqualizerScreen
import com.localfy.app.ui.screens.LocalShowScreen
import com.localfy.app.ui.screens.PodcastShowScreen
import com.localfy.app.ui.screens.PodcastsScreen
import com.localfy.app.ui.screens.BooksScreen
import com.localfy.app.ui.screens.ProfileScreen
import com.localfy.app.ui.screens.BookScreen
import com.localfy.app.ui.screens.LocalBookScreen
import com.localfy.app.ui.screens.FolderScreen
import com.localfy.app.ui.screens.GenreScreen
import com.localfy.app.ui.screens.HomeScreen
import com.localfy.app.ui.screens.LibraryScreen
import com.localfy.app.ui.screens.MixScreen
import com.localfy.app.ui.screens.PlaylistScreen
import com.localfy.app.ui.screens.SearchScreen
import com.localfy.app.ui.screens.SettingsScreen
import com.localfy.app.ui.screens.SmartScreen
import com.localfy.app.ui.screens.StatsScreen
import com.localfy.app.ui.theme.DarkSurface
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val scrimRoutes = setOf(Routes.HOME, Routes.SEARCH, Routes.LIBRARY, Routes.STATS, Routes.SETTINGS, Routes.APPEARANCE, Routes.EQUALIZER, Routes.PODCASTS, Routes.BOOKS)

private data class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Tab(Routes.SEARCH, "Search", Icons.Rounded.Search, Icons.Rounded.Search),
    Tab(Routes.PODCASTS, "Podcasts", Icons.Rounded.Podcasts, Icons.Outlined.Podcasts),
    Tab(Routes.BOOKS, "Books", Icons.AutoMirrored.Rounded.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    Tab(Routes.LIBRARY, "Your Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
)

/** Width at which the inner screen gets a navigation rail instead of a bottom bar. */
private val RailBreakpoint = 600.dp

/** Width at which there's room for the always-visible Now Playing + queue pane. */
private val PaneBreakpoint = 720.dp

/**
 * One layout tree for every Fold state. The NavHost always sits at the same position in the
 * composition, so folding/unfolding just rearranges chrome around it and nothing is lost:
 *
 *  - Cover screen (narrow): bottom navigation + a draggable mini player that morphs into the full player.
 *  - Inner screen (wide): two halves split on the hinge - rail + library | Now Playing pane - plus a
 *    dual-screen player that spans both halves.
 *  - Book posture: the content/pane split snaps to the hinge so nothing straddles the fold.
 *  - Flex Mode / tabletop: a dedicated player with art above the hinge and controls below.
 */
@Composable
fun LocalfyRoot(activity: Activity) {
    val container = activity.application as LocalfyApp
    val nav = rememberNavController()
    val incomingLink by container.incomingSocialLink.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<Pair<Song, SongMenuExtras>?>(null) }
    var addToPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    var editing by remember { mutableStateOf<Pair<List<Song>, Boolean>?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(incomingLink) { incomingLink?.let {
        if (it == "widget-route:player") playerExpanded = true
        else nav.navigate(if (it == "releases") "releases" else if (it.startsWith("widget-route:")) it.removePrefix("widget-route:") else "incoming/" + android.net.Uri.encode(it))
        container.incomingSocialLink.value = null
    } }
    var sheet by remember { mutableFloatStateOf(0f) }
    var theater by rememberSaveable { mutableStateOf(false) }
    var paneVisible by rememberSaveable { mutableStateOf(true) }
    var tabletopDismissed by rememberSaveable { mutableStateOf(false) }
    val posture by rememberFoldPosture(activity)

    // Player messages (unsupported file, offline stream...) as short toasts.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        container.player.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // Re-arm the tabletop player each time the device is half-folded again.
    LaunchedEffect(posture.kind) { if (posture.kind != FoldPosture.Kind.Tabletop) tabletopDismissed = false }

    var wideNow by remember { mutableStateOf(false) }
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
            openPlayer = { if (wideNow) paneVisible = true else playerExpanded = true },
        ).also { it.collapsePlayer = { playerExpanded = false; theater = false; tabletopDismissed = true } }
    }

    CompositionLocalProvider(LocalApp provides actions) {
        BoxWithConstraints(Modifier.fillMaxSize().background(LocalfyColors.Background)) {
            val density = LocalDensity.current
            val palette = LocalPalette.current
            val windowWidth = maxWidth
            val windowHeight = maxHeight
            val wide = windowWidth >= RailBreakpoint
            val paneFits = windowWidth >= PaneBreakpoint
            wideNow = paneFits
            val showPane = paneFits && paneVisible
            val player = rememberPlayerState()
            val backStack by nav.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            var currentTab by rememberSaveable { mutableStateOf(Routes.HOME) }
            LaunchedEffect(route) { if (tabs.any { it.route == route }) currentTab = route!! }

            // Open Fold: split exactly on the hinge, so each half behaves like its own screen.
            val hingeAware = posture.hasVerticalFold && posture.hingeRight > 0
            val hingeWidth: Dp = if (hingeAware) with(density) { (posture.hingeRight - posture.hingeLeft).toDp() } else 0.dp
            val hingeLeft: Dp = if (hingeAware) with(density) { posture.hingeLeft.toDp() } else windowWidth / 2
            val paneWidth: Dp = when {
                hingeAware -> with(density) { windowWidth - posture.hingeRight.toDp() }
                windowWidth >= 900.dp -> 400.dp
                else -> 360.dp
            }

            // ----- Cover-screen player sheet: 0 = mini player, 1 = full player -----
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navTotal = if (wide) 0.dp else 80.dp + navInset
            var miniHeight by remember { mutableStateOf(72.dp) }
            val collapsedY = windowHeight - navTotal - miniHeight
            val collapsedPx = with(density) { collapsedY.toPx() }.coerceAtLeast(1f)
            val scope = rememberCoroutineScope()
            var settleJob by remember { mutableStateOf<Job?>(null) }
            var miniDragging by remember { mutableStateOf(false) }
            fun settle(target: Float, velocity: Float = 0f) {
                settleJob?.cancel()
                settleJob = scope.launch {
                    animate(sheet, target, velocity, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> sheet = v }
                }
            }
            LaunchedEffect(playerExpanded) { settle(if (playerExpanded) 1f else 0f) }
            val collapseDistance = with(density) { 110.dp.toPx() }
            val sheetScroll = remember(collapsedPx, collapseDistance) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (available.y < 0 && sheet < 1f && source == NestedScrollSource.UserInput) { settleJob?.cancel(); sheet = (sheet - available.y / collapsedPx).coerceIn(0f, 1f); return Offset(0f, available.y) }
                        return Offset.Zero
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (available.y > 0 && source == NestedScrollSource.UserInput) { settleJob?.cancel(); sheet = (sheet - available.y / collapsedPx).coerceIn(0f, 1f); return Offset(0f, available.y) }
                        return Offset.Zero
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (sheet < 1f) {
                            val collapse = available.y > 1200f || (available.y >= -1200f && collapsedPx * (1f - sheet) >= collapseDistance)
                            playerExpanded = !collapse
                            settle(if (collapse) 0f else 1f, -available.y / collapsedPx)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }

            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail(containerColor = LocalfyColors.Background, header = { Spacer(Modifier.width(1.dp)) }) {
                        tabs.forEach { t ->
                            NavigationRailItem(
                                selected = currentTab == t.route,
                                onClick = { actions.navigateTopLevel(t.route) },
                                icon = { Icon(if (currentTab == t.route) t.selected else t.unselected, t.label) },
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
                        NavigationRailItem(selected = currentTab == Routes.FRIENDS, onClick = { actions.navigate(Routes.FRIENDS) }, icon = { Icon(androidx.compose.material.icons.Icons.Rounded.People, "Friends") }, label = { Text("Friends") })
                        if (paneFits && !paneVisible) {
                            NavigationRailItem(
                                selected = false,
                                onClick = { paneVisible = true },
                                icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Show player") },
                                label = { Text("Player") },
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).clipToBounds()) {
                        NavHost(
                            nav,
                            startDestination = Routes.HOME,
                            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
                            exitTransition = { fadeOut(tween(180)) },
                            popEnterTransition = { fadeIn(tween(220)) },
                            popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 12 } },
                        ) {
                            composable(Routes.HOME) { HomeScreen() }
                            composable(Routes.SEARCH) { SearchScreen() }
                            composable(Routes.LIBRARY) { LibraryScreen(onCreatePlaylist = { creatingPlaylist = true }) }
                            composable(Routes.STATS) { StatsScreen() }
                            composable(Routes.SETTINGS) { SettingsScreen() }
                            composable(Routes.APPEARANCE) { AppearanceScreen() }
                            composable(Routes.EQUALIZER) { EqualizerScreen() }
                            composable(Routes.PODCASTS) { PodcastsScreen() }
                            composable(Routes.BOOKS) { BooksScreen() }
                            composable(Routes.PROFILE) { ProfileScreen() }
                            composable(Routes.BOOK, listOf(navArgument("id") { type = NavType.LongType })) { BookScreen(it.arguments!!.getLong("id")) }
                            composable(Routes.LOCAL_BOOK, listOf(navArgument("album") { type = NavType.LongType })) { LocalBookScreen(it.arguments!!.getLong("album")) }
                            composable(Routes.SHOW, listOf(navArgument("id") { type = NavType.LongType })) { PodcastShowScreen(it.arguments!!.getLong("id")) }
                            composable(Routes.LOCAL_SHOW) { LocalShowScreen(it.arguments?.getString("name").orEmpty()) }
                            composable(Routes.ALBUM, listOf(navArgument("id") { type = NavType.LongType })) {
                                AlbumScreen(it.arguments!!.getLong("id"))
                            }
                            composable(Routes.CATALOG_ALBUM) { com.localfy.app.ui.screens.CatalogPage(it.arguments?.getString("album").orEmpty(), false) }
                            composable(Routes.CATALOG_SONG) { com.localfy.app.ui.screens.CatalogPage(it.arguments?.getString("track").orEmpty(), true) }
                            composable("artist-online/{name}") { com.localfy.app.ui.screens.ArtistLandingScreen(it.arguments?.getString("name").orEmpty()) }
                            composable(Routes.ONLINE_ARTIST) { com.localfy.app.ui.screens.OnlineArtistScreen(it.arguments?.getString("artist").orEmpty()) }
                            composable("spotify-code") { com.localfy.app.ui.screens.SpotifyCodeScanScreen() }
                            composable("spotify-item/{kind}/{id}") { com.localfy.app.ui.screens.SpotifyScannedItemScreen(it.arguments?.getString("kind").orEmpty(), it.arguments?.getString("id").orEmpty()) }
                            composable("spotify-import") { com.localfy.app.ui.screens.SpotifyImportScreen() }
                            composable(Routes.SPOTIFY_PLAYLIST) { com.localfy.app.ui.screens.PublicPlaylistScreen(it.arguments?.getString("id").orEmpty(), false) }
                            composable(Routes.SHARED_PLAYLIST) { com.localfy.app.ui.screens.PublicPlaylistScreen(it.arguments?.getString("key").orEmpty(), true) }
                            composable("rooms") { com.localfy.app.ui.screens.RoomsScreen() }
                            composable("releases") { com.localfy.app.ui.screens.ArtistReleasesScreen() }
                            composable("incoming/{link}") { com.localfy.app.ui.screens.IncomingShareScreen(it.arguments?.getString("link").orEmpty()) }
                            composable("friend/{person}") { com.localfy.app.ui.screens.FriendProfileScreen(it.arguments?.getString("person").orEmpty()) }
                            composable("hidden-artists") { com.localfy.app.ui.screens.HiddenArtistsScreen() }
                            composable("friends-settings") { com.localfy.app.ui.screens.FriendsSettingsScreen() }
                            composable("friend-code") { com.localfy.app.ui.screens.FriendCodeScreen() }
                            composable("add-friend") { com.localfy.app.ui.screens.AddFriendScreen() }
                            composable("new-shared-playlist") { com.localfy.app.ui.screens.NewSharedPlaylistScreen() }
                            composable(Routes.FRIENDS) { com.localfy.app.ui.screens.FriendsScreen() }
                            composable(Routes.ARTIST) { ArtistScreen(it.arguments?.getString("name").orEmpty()) }
                            composable(Routes.PLAYLIST, listOf(navArgument("id") { type = NavType.LongType })) {
                                PlaylistScreen(it.arguments!!.getLong("id"))
                            }
                            composable(Routes.SMART) { SmartScreen(it.arguments?.getString("kind").orEmpty()) }
                            composable(Routes.MIX) { MixScreen(it.arguments?.getString("key").orEmpty()) }
                            composable(Routes.GENRE) { GenreScreen(it.arguments?.getString("name").orEmpty()) }
                            composable(Routes.FOLDER) { FolderScreen(it.arguments?.getString("path").orEmpty().takeIf { p -> p != "/" }.orEmpty()) }
                        }
                        // Collection pages draw their own pinned bar; everything else gets a status-bar scrim.
                        if (route in listOf(Routes.HOME, Routes.SEARCH, Routes.LIBRARY, Routes.STATS,
                                Routes.SETTINGS, Routes.APPEARANCE, Routes.EQUALIZER, Routes.PODCASTS,
                                Routes.BOOKS, Routes.PROFILE, Routes.FRIENDS, "rooms", "releases")) {
                            StatusBarScrim()
                        }
                    }
                    when {
                        !wide -> Spacer(Modifier.height(navTotal + if (player.hasMedia) miniHeight else 0.dp))
                        !showPane && player.hasMedia -> MiniPlayer(onExpand = { if (paneFits) paneVisible = true else playerExpanded = true }, Modifier.navigationBarsPadding())
                        else -> Spacer(Modifier.navigationBarsPadding())
                    }
                }
                if (showPane) {
                    if (hingeWidth > 0.dp) Spacer(Modifier.width(hingeWidth).fillMaxHeight().background(Color.Black))
                    DarkSurface { NowPlayingPane(onHide = { paneVisible = false }, onTheater = { theater = true }, modifier = Modifier.width(paneWidth), lightStatusStrip = !palette.isDark) }
                }
            }

            if (!wide) {
                // Draggable sheet: the mini player morphs into the full player.
                if (player.hasMedia) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, (collapsedPx * (1f - sheet)).roundToInt()) },
                    ) {
                        if (sheet > 0.001f) {
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((sheet - 0.05f) / 0.45f).coerceIn(0f, 1f) }) {
                                DarkSurface { NowPlayingFull(onCollapse = { playerExpanded = false; settle(0f) }, nestedScroll = sheetScroll) }
                            }
                        }
                        // Keep the drag target alive until release. Removing it at 30% cancelled
                        // the gesture before onDragStopped could finish opening the player.
                        if (sheet < 0.3f || miniDragging) {
                            Box(
                                Modifier
                                    .graphicsLayer { alpha = (1f - sheet * 4f).coerceIn(0f, 1f) }
                                    .draggable(
                                        orientation = Orientation.Vertical,
                                        state = rememberDraggableState { d -> settleJob?.cancel(); sheet = (sheet - d / collapsedPx).coerceIn(0f, 1f) },
                                        onDragStarted = { miniDragging = true; settleJob?.cancel() },
                                        onDragStopped = { v ->
                                            miniDragging = false
                                            val expand = v < -800f || (v <= 800f && sheet > 0.25f)
                                            playerExpanded = expand
                                            settle(if (expand) 1f else 0f, -v / collapsedPx)
                                        },
                                    ),
                            ) {
                                MiniPlayer(onExpand = { playerExpanded = true }, modifier = Modifier.onSizeChanged { size ->
                                    miniHeight = with(density) { size.height.toDp() }
                                })
                            }
                        }
                    }
                }
                NavigationBar(
                    containerColor = Color.Transparent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset { IntOffset(0, (with(density) { navTotal.toPx() } * sheet).roundToInt()) }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, LocalfyColors.Background.copy(alpha = 0.92f), LocalfyColors.Background))),
                ) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = currentTab == t.route,
                            onClick = { actions.navigateTopLevel(t.route) },
                            icon = { Icon(if (currentTab == t.route) t.selected else t.unselected, t.label) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = LocalfyColors.TextPrimary,
                                selectedTextColor = LocalfyColors.TextPrimary,
                                unselectedIconColor = LocalfyColors.TextTertiary,
                                unselectedTextColor = LocalfyColors.TextTertiary,
                            ),
                        )
                    }
                }
            }

            com.localfy.app.ui.theme.ThemeFrame(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())

            // Status/nav bar icons: dark icons on light screens, light icons whenever a dark,
            // art-backed surface fills the screen. Never white-on-white or black-on-black.
            val darkOverlay = (!wide && sheet > 0.5f) ||
                (theater && paneFits && player.hasMedia) ||
                (posture.kind == FoldPosture.Kind.Tabletop && player.hasMedia && !tabletopDismissed)
            val lightBars = !palette.isDark && !darkOverlay
            if (lightBars) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(palette.background))
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }

            // Unfolding while the full player is open: hand over to the side pane.
            // Opening up to where the side pane fits: hand the full player over to the pane.
            LaunchedEffect(paneFits) { if (paneFits && playerExpanded) { playerExpanded = false; sheet = 0f; paneVisible = true } }
            LaunchedEffect(paneFits) { if (!paneFits) theater = false }

            // Wide but no room for the pane (e.g. inner screen in portrait, split-screen):
            // the mini player opens the full player over everything.
            AnimatedVisibility(
                visible = wide && !paneFits && playerExpanded && player.hasMedia,
                enter = slideInVertically(tween(340)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
            ) {
                DarkSurface { NowPlayingFull(onCollapse = { playerExpanded = false }) }
            }

            // Inner screen: the dual-screen player spanning both halves.
            AnimatedVisibility(
                visible = theater && paneFits && player.hasMedia && posture.kind != FoldPosture.Kind.Tabletop,
                enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.96f),
                exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.96f),
            ) {
                DarkSurface { TheaterPlayer(hingeLeft, hingeWidth, onExit = { theater = false }) }
            }

            AnimatedVisibility(
                visible = posture.kind == FoldPosture.Kind.Tabletop && player.hasMedia && !tabletopDismissed,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(200)),
            ) {
                DarkSurface { TabletopPlayer(posture, onExit = { tabletopDismissed = true }) }
            }
        }

        menu?.let { (song, extras) ->
            SongMenuSheet(song, extras, onDismiss = { menu = null }, onNavigated = { playerExpanded = false })
        }
        addToPlaylist?.let { songs -> AddToPlaylistDialog(songs) { addToPlaylist = null } }
        editing?.let { (songs, album) -> MetadataEditor(songs, album) { editing = null } }
        if (creatingPlaylist) {
            CreatePlaylistDialog(onDismiss = { creatingPlaylist = false }, onCreated = { actions.navigate(Routes.playlist(it)) })
        }
    }
}

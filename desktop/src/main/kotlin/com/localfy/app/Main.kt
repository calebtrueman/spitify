package com.localfy.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import com.localfy.app.icons.AppIcons
import com.localfy.app.ui.BundledResources
import com.localfy.app.ui.DesktopEvents
import com.localfy.app.ui.DesktopSettings
import com.localfy.app.ui.LocalContainer
import com.localfy.app.ui.LocalWindow
import com.localfy.app.ui.LocalfyRoot
import com.localfy.app.ui.ShortcutRouter
import com.localfy.app.ui.WindowBounds
import com.localfy.app.ui.art.ImageLoader
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.art.rememberArtAccent
import com.localfy.app.ui.screens.Onboarding
import com.localfy.app.ui.theme.AccentSource
import com.localfy.app.ui.theme.LocalfyTheme
import com.localfy.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

/** The desktop's MainActivity: one window, the same theme and root as the phone's inner screen. */
fun main() {
    configurePlatform()
    val app = LocalfyApp()
    ImageLoader.container = app
    installOpenHandlers()
    application {
        SpitifyWindow(app)
    }
}

/** Process-wide settings that must be in place before AWT starts. */
private fun configurePlatform() {
    System.setProperty("apple.awt.application.name", "Spitify")
    System.setProperty("apple.awt.application.appearance", "system")
    // Dark title bar on macOS whenever the app theme is dark (Spitify's default).
    runCatching {
        val mode = com.localfy.app.desktop.Prefs("theme").getString("mode", ThemeMode.Dark.name)
        if (mode != ThemeMode.Light.name) System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    }
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { JsonStore.flushAll() } })
}

/** spitify:// links and "Open with Spitify" (macOS sends both through the Desktop API). */
private fun installOpenHandlers() {
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
            desktop.setOpenURIHandler { event -> DesktopEvents.incomingLink.value = event.uri.toString() }
        }
        if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler { event -> DesktopEvents.droppedFiles.tryEmit(event.files.toList()) }
        }
    }
}

private fun iconPainter(id: String): Painter? = BundledResources.bytes(AppIcons.resource(id))?.let { bytes ->
    runCatching { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()) }.getOrNull()
}

@Composable
private fun ApplicationScope.SpitifyWindow(app: LocalfyApp) {
    val saved = remember { DesktopSettings.windowBounds() }
    val state = rememberWindowState(
        placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = saved?.takeIf { it.x >= 0 && it.y >= 0 }?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
        size = DpSize((saved?.width ?: 1320).coerceAtLeast(900).dp, (saved?.height ?: 860).coerceAtLeast(600).dp),
    )
    var visible by remember { mutableStateOf(!DesktopSettings.startMinimized.value || !DesktopSettings.closeToTray.value) }
    val closeToTray by DesktopSettings.closeToTray.collectAsState()
    val iconId by AppIcons.selected.collectAsState()
    val icon = remember(iconId) { iconPainter(iconId) }
    val trayAvailable = remember { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }

    fun quit() {
        runCatching { app.player.saveNow() }
        JsonStore.flushAll()
        exitApplication()
    }

    if (closeToTray && trayAvailable && icon != null) {
        val playerState by app.player.state.collectAsState()
        Tray(
            icon = icon,
            tooltip = "Spitify",
            onAction = { visible = true },
            menu = {
                Item(if (playerState.isPlaying) "Pause" else "Play", onClick = { app.player.togglePlay() })
                Item("Next", onClick = { app.player.next() })
                Item("Previous", onClick = { app.player.previous() })
                Separator()
                Item("Show Spitify", onClick = { visible = true })
                Item("Quit Spitify", onClick = { quit() })
            },
        )
    }

    Window(
        onCloseRequest = { if (DesktopSettings.closeToTray.value && trayAvailable) visible = false else quit() },
        state = state,
        visible = visible,
        title = "Spitify",
        icon = icon,
        onPreviewKeyEvent = { ShortcutRouter.handle(it) },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(900, 600)
            window.background = java.awt.Color(0x09, 0x09, 0x0B)
            if (DesktopSettings.startMinimized.value && !DesktopSettings.closeToTray.value) window.isMinimized = true
            installDropTarget(window)
        }
        // Dock / taskbar icon follows the chosen app icon (the window icon above does too).
        LaunchedEffect(iconId) {
            runCatching {
                val bytes = BundledResources.bytes(AppIcons.resource(iconId)) ?: return@runCatching
                val image = javax.imageio.ImageIO.read(bytes.inputStream())
                if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                    java.awt.Taskbar.getTaskbar().iconImage = image
                }
            }
        }
        // Remember size and position (saved once things settle, not on every pixel of a drag).
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.placement, state.position, state.size) }
                .debounce(400)
                .collect { (placement, position, size) ->
                    val maximized = placement == WindowPlacement.Maximized
                    val previous = DesktopSettings.windowBounds()
                    val floating = placement == WindowPlacement.Floating
                    DesktopSettings.saveWindowBounds(
                        WindowBounds(
                            x = if (floating && position is WindowPosition.Absolute) position.x.value.toInt() else previous?.x ?: -1,
                            y = if (floating && position is WindowPosition.Absolute) position.y.value.toInt() else previous?.y ?: -1,
                            width = if (floating) size.width.value.toInt() else previous?.width ?: 1320,
                            height = if (floating) size.height.value.toInt() else previous?.height ?: 860,
                            maximized = maximized,
                        ),
                    )
                }
        }
        CompositionLocalProvider(LocalContainer provides app, LocalWindow provides window) {
            SpitifyContent(app)
        }
    }
}

/** MainActivity.setContent: theme (with "accent from album art"), onboarding, then the app. */
@Composable
private fun SpitifyContent(app: LocalfyApp) {
    val settings by app.theme.settings.collectAsStateWithLifecycle()
    val accentKey = if (settings.accentSource == AccentSource.Artwork) {
        // Only the current song matters here; the full player state also changes on every
        // play/pause and buffering blip, which would recompose the entire app.
        val currentId by remember { app.player.state.map { it.currentId }.distinctUntilChanged() }.collectAsStateWithLifecycle(app.player.state.value.currentId)
        val lib by app.library.library.collectAsStateWithLifecycle()
        remember(currentId, lib) { currentId?.let(app::resolve)?.artKey }
    } else null
    val artAccent = rememberArtAccent(accentKey)
    val profile by app.profiles.profile.collectAsStateWithLifecycle()
    LocalfyTheme(settings, artAccent) {
        if (!profile.onboarded) {
            Onboarding(onDone = {})
            return@LocalfyTheme
        }
        LaunchedEffect(Unit) {
            app.library.ensureStarted()
            app.podcasts.start()
            app.rooms
            app.taste // starts the recommendation engine
            app.player.connect()
            launch { runCatching { app.artistFollows.refresh() } }
        }
        LocalfyRoot()
    }
}

/** Drag audio files or folders onto the window to play them or add them to the library. */
private fun installDropTarget(window: java.awt.Window) {
    window.dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun dragEnter(event: DropTargetDragEvent) {
            if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) event.acceptDrag(DnDConstants.ACTION_COPY) else event.rejectDrag()
        }
        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
            }.getOrDefault(emptyList())
            event.dropComplete(files.isNotEmpty())
            if (files.isNotEmpty()) DesktopEvents.droppedFiles.tryEmit(files)
        }
    }, true)
}


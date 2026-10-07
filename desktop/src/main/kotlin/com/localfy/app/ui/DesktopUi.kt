package com.localfy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.localfy.app.LocalfyApp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

/** The desktop app container (the phone's `com.localfy.app.ui.LocalContainer.current`). */
val LocalContainer = staticCompositionLocalOf<LocalfyApp> { error("LocalfyApp not provided") }

/** The AWT window hosting the UI, for native file dialogs. Null in previews/tests. */
val LocalWindow = staticCompositionLocalOf<Frame?> { null }

/** Short in-app messages (the phone's Toasts), shown by the root snackbar host. */
object Toasts {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun show(message: String?) { if (!message.isNullOrBlank()) _messages.tryEmit(message) }
}

/** Requests that travel from keyboard shortcuts / the OS into the composition. */
object DesktopEvents {
    /** Cmd/Ctrl+F: the search field asks for focus when this ticks. */
    val focusSearch = MutableStateFlow(0)
    /** Files dropped onto the window. */
    val droppedFiles = MutableSharedFlow<List<File>>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** spitify:// links opened from other apps. */
    val incomingLink = MutableStateFlow<String?>(null)
}

/**
 * Tracks whether a text field has keyboard focus, so single-key shortcuts (Space, arrows) never
 * fire while you type. Every text field in the app adds [typingFocus].
 */
object TypingFocus {
    var count by mutableIntStateOf(0)
        private set
    val active: Boolean get() = count > 0
    internal fun change(delta: Int) { count = (count + delta).coerceAtLeast(0) }
}

fun Modifier.typingFocus(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (focused) TypingFocus.change(-1) } }
    onFocusChanged {
        if (it.isFocused != focused) {
            focused = it.isFocused
            TypingFocus.change(if (focused) 1 else -1)
        }
    }
}

/** Right-click (secondary button) runs [onClick]: the desktop way to open a context menu. */
fun Modifier.onSecondaryClick(onClick: (() -> Unit)?): Modifier = if (onClick == null) this else composed {
    val latest by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    latest()
                }
            }
        }
    }
}

/** Where the pointer last pressed, in root coordinates: context menus open there. */
object PointerAnchor {
    var lastPress by mutableStateOf(Offset.Zero)
}

/** Escape / mouse "back" button handlers; the newest enabled one wins, else navigation pops. */
class BackDispatcher {
    private val handlers = mutableStateListOf<BackEntry>()
    internal fun add(entry: BackEntry) { handlers += entry }
    internal fun remove(entry: BackEntry) { handlers -= entry }
    /** Runs the innermost handler; returns false when none is enabled. */
    fun dispatch(): Boolean {
        val handler = handlers.lastOrNull { it.enabled } ?: return false
        handler.onBack()
        return true
    }
}

internal class BackEntry(var enabled: Boolean, var onBack: () -> Unit)

val LocalBackDispatcher = staticCompositionLocalOf { BackDispatcher() }

/** Desktop stand-in for androidx.activity's BackHandler: Escape or the mouse back button. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    val entry = remember { BackEntry(enabled, onBack) }
    entry.enabled = enabled
    entry.onBack = onBack
    DisposableEffect(dispatcher) {
        dispatcher.add(entry)
        onDispose { dispatcher.remove(entry) }
    }
}

/** Native file pickers (java.awt.FileDialog looks native on macOS and Windows). */
object FilePickers {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    val audioExtensions = setOf("mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wma", "alac", "mka", "webm")

    fun pickImage(owner: Frame?, title: String = "Choose an image"): File? =
        pick(owner, title) { name -> name.substringAfterLast('.', "").lowercase() in imageExtensions }

    fun pick(owner: Frame?, title: String, accept: (String) -> Boolean = { true }): File? {
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> accept(name) }
        if (isMac) dialog.file = null
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return File(dialog.directory, file).takeIf { it.isFile }
    }

    fun save(owner: Frame?, title: String, suggestedName: String): File? {
        val dialog = FileDialog(owner, title, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return File(dialog.directory, file)
    }

    /** A folder chooser: the native one on macOS, Swing's elsewhere (AWT can't pick folders there). */
    fun pickFolder(owner: Frame?, title: String = "Choose a folder"): File? {
        if (isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(owner, title, FileDialog.LOAD)
                dialog.isVisible = true
                val name = dialog.file ?: return null
                return File(dialog.directory, name).takeIf { it.isDirectory }
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private val isMac = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")
}

/** UI-owned settings files. One instance per file: two Prefs on the same file would overwrite each other. */
object UiPrefs {
    val sorting by lazy { com.localfy.app.desktop.Prefs("sorting") }
    val desktop by lazy { com.localfy.app.desktop.Prefs("desktop") }
}

/** Files bundled in the jar (the phone's assets): theme catalog, theme art, icon catalog, fonts. */
object BundledResources {
    fun bytes(path: String): ByteArray? =
        (Thread.currentThread().contextClassLoader?.getResourceAsStream(path) ?: BundledResources::class.java.getResourceAsStream("/$path"))
            ?.use { it.readBytes() }
    fun text(path: String): String = bytes(path)?.toString(Charsets.UTF_8) ?: error("Missing resource $path")
}

/** Image file helpers (the phone uses ImageDecoder / Bitmap). */
object DesktopImages {
    /** Center-crops [source] to a square of [size] px and saves it as JPEG. False if it can't be read. */
    fun saveSquareImage(source: File, dest: File, size: Int): Boolean = runCatching {
        val image = org.jetbrains.skia.Image.makeFromEncoded(source.readBytes())
        try {
            val side = minOf(image.width, image.height)
            if (side <= 0) return false
            val target = minOf(size, side)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(target, target)
            val src = org.jetbrains.skia.Rect.makeXYWH(((image.width - side) / 2).toFloat(), ((image.height - side) / 2).toFloat(), side.toFloat(), side.toFloat())
            surface.canvas.drawImageRect(image, src, org.jetbrains.skia.Rect.makeWH(target.toFloat(), target.toFloat()),
                org.jetbrains.skia.FilterMipmap(org.jetbrains.skia.FilterMode.LINEAR, org.jetbrains.skia.MipmapMode.LINEAR), null, true)
            val data = surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 92) ?: return false
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".tmp")
            tmp.writeBytes(data.bytes)
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
            surface.close()
            true
        } finally { image.close() }
    }.getOrDefault(false)

    /** Reads an image file for pixel work (QR / Spotify codes). */
    fun readBuffered(file: File): java.awt.image.BufferedImage? = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull()
}

/** Copies text to the system clipboard (the desktop stand-in for Android's share sheet). */
fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

/** Opens a web link in the default browser. */
fun openInBrowser(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    }
}

/** Shows a folder in Finder / Explorer / the file manager. */
fun openFolder(folder: File) {
    runCatching {
        folder.mkdirs()
        if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(folder)
    }
}

/** "4.2 MB", like Android's Formatter.formatShortFileSize. */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}

/** File for a song's local source (file: URI or plain path), or null for streams/episodes. */
fun localFileOf(sourceUri: String?): File? {
    val source = sourceUri ?: return null
    return runCatching {
        when {
            source.startsWith("file:") -> File(java.net.URI(source))
            source.startsWith("/") || (source.length > 2 && source[1] == ':') -> File(source)
            else -> null
        }
    }.getOrNull()
}

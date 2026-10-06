package com.localfy.app.desktop

import java.io.File

/**
 * Where Spitify Desktop keeps its data, following each OS's convention:
 * macOS ~/Library/Application Support/Spitify, Windows %APPDATA%\Spitify,
 * Linux $XDG_DATA_HOME/spitify (default ~/.local/share/spitify). Caches go to the OS cache dir.
 */
object AppPaths {
    private val os = System.getProperty("os.name").lowercase()
    private val home = File(System.getProperty("user.home"))

    val dataDir: File by lazy {
        System.getProperty("spitify.dataDir")?.let(::File) ?: when {
            os.contains("mac") -> File(home, "Library/Application Support/Spitify")
            os.contains("win") -> File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "Spitify")
            else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "spitify")
        }.also { it.mkdirs() }
    }

    val cacheDir: File by lazy {
        System.getProperty("spitify.cacheDir")?.let(::File) ?: when {
            os.contains("mac") -> File(home, "Library/Caches/Spitify")
            os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path, "Spitify/Cache")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: File(home, ".cache").path, "spitify")
        }.also { it.mkdirs() }
    }

    /** Default library and download folder: the user's Music folder. */
    val musicDir: File by lazy { File(home, "Music").also { it.mkdirs() } }

    /** Downloaded online music (saved as AAC .m4a, like the phone apps). */
    val downloadsDir: File by lazy { File(musicDir, "Spitify").also { it.mkdirs() } }

    fun data(name: String): File = File(dataDir, name)
    fun cache(name: String): File = File(cacheDir, name).also { it.mkdirs() }

    val isMac get() = os.contains("mac")
    val isWindows get() = os.contains("win")
    val isLinux get() = !isMac && !isWindows
}

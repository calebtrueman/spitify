package com.localfy.app.playback

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Windows media keys (play/pause, next, previous, stop) as global hotkeys through user32, so they
 * work while another window has focus. Windows' on-screen media overlay (SMTC) needs WinRT and
 * isn't shown. If another player already owns a key, registering it fails and that key stays with it.
 */
internal class WindowsMediaKeys : MediaSessionBridge {
    private val user32 = NativeLibrary.getInstance("user32")
    private val kernel32 = NativeLibrary.getInstance("kernel32")
    @Volatile private var threadId = 0
    private var thread: Thread? = null

    override fun start(onCommand: (MediaCommand) -> Unit, onSeek: (positionMs: Long) -> Unit) {
        if (thread != null) return
        thread = Thread({
            threadId = kernel32.getFunction("GetCurrentThreadId").invokeInt(emptyArray())
            // Hotkeys registered without a window post WM_HOTKEY to the registering thread's queue.
            val registered = KEYS.keys.filter { id ->
                user32.getFunction("RegisterHotKey").invokeInt(arrayOf<Any?>(Pointer.NULL, id, MOD_NOREPEAT, KEYS.getValue(id).first)) != 0
            }
            val msg = Memory(64)
            try {
                while (user32.getFunction("GetMessageW").invokeInt(arrayOf<Any?>(msg, Pointer.NULL, 0, 0)) > 0) {
                    // MSG on x64: HWND (8), UINT message (4 + 4 padding), WPARAM (8) ...
                    if (msg.getInt(8) == WM_HOTKEY) KEYS[msg.getLong(16).toInt()]?.let { runCatching { onCommand(it.second) } }
                }
            } finally {
                registered.forEach { user32.getFunction("UnregisterHotKey").invokeInt(arrayOf<Any?>(Pointer.NULL, it)) }
            }
        }, "spitify-media-keys").apply { isDaemon = true; start() }
    }

    override fun update(info: NowPlaying?) {}

    override fun close() {
        val id = threadId
        if (id != 0) runCatching { user32.getFunction("PostThreadMessageW").invokeInt(arrayOf<Any>(id, WM_QUIT, 0L, 0L)) }
        thread = null
    }

    private companion object {
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
        const val MOD_NOREPEAT = 0x4000
        /** Hotkey id → (virtual key, command). */
        val KEYS = mapOf(
            1 to (0xB3 to MediaCommand.TOGGLE),   // VK_MEDIA_PLAY_PAUSE
            2 to (0xB0 to MediaCommand.NEXT),     // VK_MEDIA_NEXT_TRACK
            3 to (0xB1 to MediaCommand.PREVIOUS), // VK_MEDIA_PREV_TRACK
            4 to (0xB2 to MediaCommand.STOP),     // VK_MEDIA_STOP
        )
    }
}

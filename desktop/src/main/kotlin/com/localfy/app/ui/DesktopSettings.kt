package com.localfy.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Window placement remembered between launches. */
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

/** Desktop-only preferences: tray, start-up and the window's last size and position. */
object DesktopSettings {
    private val prefs get() = UiPrefs.desktop

    private val _startMinimized = MutableStateFlow(prefs.getBoolean("startMinimized", false))
    val startMinimized: StateFlow<Boolean> = _startMinimized.asStateFlow()
    fun setStartMinimized(value: Boolean) { prefs.edit { putBoolean("startMinimized", value) }; _startMinimized.value = value }

    private val _closeToTray = MutableStateFlow(prefs.getBoolean("closeToTray", false))
    val closeToTray: StateFlow<Boolean> = _closeToTray.asStateFlow()
    fun setCloseToTray(value: Boolean) { prefs.edit { putBoolean("closeToTray", value) }; _closeToTray.value = value }

    /** Whether the Now Playing pane is shown on the right. */
    private val _paneVisible = MutableStateFlow(prefs.getBoolean("paneVisible", true))
    val paneVisible: StateFlow<Boolean> = _paneVisible.asStateFlow()
    fun setPaneVisible(value: Boolean) { if (value != _paneVisible.value) { prefs.edit { putBoolean("paneVisible", value) }; _paneVisible.value = value } }

    fun windowBounds(): WindowBounds? {
        if (!prefs.contains("windowWidth")) return null
        return WindowBounds(
            prefs.getInt("windowX", -1), prefs.getInt("windowY", -1),
            prefs.getInt("windowWidth", 1280), prefs.getInt("windowHeight", 820),
            prefs.getBoolean("windowMaximized", false),
        )
    }

    fun saveWindowBounds(bounds: WindowBounds) {
        prefs.edit {
            putInt("windowX", bounds.x); putInt("windowY", bounds.y)
            putInt("windowWidth", bounds.width); putInt("windowHeight", bounds.height)
            putBoolean("windowMaximized", bounds.maximized)
        }
    }
}

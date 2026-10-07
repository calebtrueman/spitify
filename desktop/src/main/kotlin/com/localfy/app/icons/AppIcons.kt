package com.localfy.app.icons

import com.localfy.app.desktop.Prefs
import com.localfy.app.ui.BundledResources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

data class AppIconChoice(val id: String, val name: String, val group: String)

/**
 * The record you see in the Dock / taskbar and on the window. The phone swaps launcher aliases;
 * the desktop app simply swaps the window and Dock icon (installers keep the default icon).
 */
object AppIcons {
    const val DEFAULT_ID = "not_for_rent"
    private val prefs get() = com.localfy.app.ui.UiPrefs.desktop

    val choices: List<AppIconChoice> by lazy {
        runCatching {
            val rows = JSONArray(BundledResources.text("icons/icon-catalog.json"))
            (0 until rows.length()).map { i -> rows.getJSONObject(i).let { AppIconChoice(it.getString("id"), it.getString("name"), it.getString("group")) } }
        }.getOrDefault(emptyList())
    }

    private val _selected = MutableStateFlow(prefs.getString("appIcon", DEFAULT_ID) ?: DEFAULT_ID)
    val selected: StateFlow<String> = _selected.asStateFlow()

    fun selectedId(): String = _selected.value

    fun select(id: String) {
        require(choices.any { it.id == id }) { "That icon is unavailable. Please choose another." }
        prefs.edit { putString("appIcon", id) }
        _selected.value = id
    }

    /** Bundled preview / window icon image for [id]. */
    fun resource(id: String): String = "icons/vinyl_$id.png".takeIf { BundledResources.bytes(it) != null } ?: "icons/spitify_launcher.png"
}

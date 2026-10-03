package com.localfy.app.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.localfy.app.MainActivity
import org.json.JSONArray

/** The launcher owns the selected icon. Read it back instead of keeping a second preference. */
data class AppIconChoice(val id: String, val name: String, val group: String)

class AppIcons(context: Context) {
    private val context = context.applicationContext
    private val packageManager get() = context.packageManager
    val choices: List<AppIconChoice> = runCatching {
        val rows = JSONArray(context.assets.open("icon-catalog.json").bufferedReader().use { it.readText() })
        (0 until rows.length()).map { index -> rows.getJSONObject(index).let { AppIconChoice(it.getString("id"), it.getString("name"), it.getString("group")) } }
            .also { rows -> require(rows.map { it.id }.distinct().size == rows.size && rows.all { it.id.matches(Regex("[a-z0-9_]+")) }) }
    }.getOrDefault(emptyList())

    fun component(id: String): ComponentName = ComponentName(context.packageName, MainActivity::class.java.name.substringBeforeLast('.') + ".icons.Icon_$id")

    private fun isEnabled(component: ComponentName): Boolean = when (packageManager.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }

    fun enabledIds(): List<String> = choices.filter { runCatching { isEnabled(component(it.id)) }.getOrDefault(false) }.map { it.id }
    fun selectedId(): String? = enabledIds().singleOrNull()
    fun previewResource(choice: AppIconChoice): Int = context.resources.getIdentifier("vinyl_${choice.id}", "mipmap", context.packageName)

    /** Changes aliases only. MainActivity and the music service stay enabled and keep running. */
    @Synchronized fun select(id: String) {
        require(choices.any { it.id == id }) { "That icon is unavailable. Please choose another." }
        val aliases = choices.map { component(it.id) }
        aliases.forEach { alias ->
            val info = packageManager.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS)
            check(info.targetActivity == MainActivity::class.java.name) { "App icons aren't ready in this version." }
        }
        val target = component(id)
        val oldStates = aliases.associateWith(packageManager::getComponentEnabledSetting)
        try {
            applyStates(aliases.associateWith { if (it == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
            check(enabledIds() == listOf(id)) { "The launcher couldn't change the icon. Please try again." }
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            val resolved = packageManager.resolveActivity(launch, 0)?.activityInfo
            check(resolved?.name == target.className && resolved.targetActivity == MainActivity::class.java.name) { "The new icon couldn't open Spitify." }
        } catch (error: Exception) {
            runCatching { applyStates(oldStates) }
            if (enabledIds().isEmpty()) {
                // Even if the launcher rejected the rollback, keep a way to reopen the app.
                runCatching { packageManager.setComponentEnabledSetting(component(DEFAULT_ID), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP) }
            }
            throw error
        }
    }

    private fun applyStates(states: Map<ComponentName, Int>) {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.setComponentEnabledSettings(states.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            })
        } else {
            // Older Android needs the new entry enabled before the old entry is removed.
            val enabledFirst = states.entries.sortedBy { (component, state) ->
                val enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
                if (enabled) 0 else 1
            }
            enabledFirst.forEach { (component, state) -> packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP) }
        }
    }

    companion object { const val DEFAULT_ID = "not_for_rent" }
}

package com.localfy.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One page on the back stack. [id] keeps each visit's saved state (scroll position...) separate. */
data class NavEntry(val id: Long, val route: String) {
    /** The pattern this route matches ("album/{id}"), for "which page is showing" checks. */
    val pattern: String get() = Routes.patternOf(route)
}

/**
 * A small back stack for the desktop window (the phone uses navigation-compose). Top-level tabs keep
 * their own stacks, so switching tabs and coming back restores where you were, as on Android.
 */
@Stable
class Navigator(start: String) {
    private var nextId = 1L
    private fun entry(route: String) = NavEntry(nextId++, route)

    var backStack by mutableStateOf(listOf(entry(start)))
        private set

    /** True while the last change was a pop, so the page transition can run backwards. */
    var poppedLast by mutableStateOf(false)
        private set

    /** Entries discarded since the last call: their saved state can be dropped. */
    private val discarded = mutableListOf<NavEntry>()
    private val savedTabs = mutableMapOf<String, List<NavEntry>>()

    val current: NavEntry get() = backStack.last()
    val currentRoute: String get() = current.route

    fun navigate(route: String) {
        poppedLast = false
        backStack = backStack + entry(route)
    }

    fun popBackStack(): Boolean {
        if (backStack.size <= 1) return false
        poppedLast = true
        discarded += backStack.last()
        backStack = backStack.dropLast(1)
        return true
    }

    /**
     * Switches tab: the current tab's stack is saved and the target tab's stack restored (or started).
     * Home is the root of every stack, like popUpTo(HOME) { saveState = true } on Android.
     */
    fun navigateTopLevel(route: String, home: String, tabs: Set<String>) {
        val currentTab = backStack.getOrNull(1)?.route?.takeIf { it in tabs } ?: home
        if (currentTab == route && route != home) return
        poppedLast = false
        if (currentTab != home) savedTabs[currentTab] = backStack
        else discarded += backStack.drop(1)
        backStack = if (route == home) listOf(backStack.first())
        else savedTabs.remove(route) ?: listOf(backStack.first(), entry(route))
    }

    /** State holders of pages that can never come back; the root clears their saved state. */
    fun takeDiscarded(): List<NavEntry> {
        val live = backStack.toSet() + savedTabs.values.flatten().toSet()
        val gone = discarded.filter { it !in live }
        discarded.clear()
        return gone
    }
}

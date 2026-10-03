package com.localfy.app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.localfy.app.MainActivity
import com.localfy.app.R

open class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it, this::class.java) } }
    companion object {
        private val providers = listOf(MusicWidgetProvider::class.java, LibraryWidgetProvider::class.java, FriendsWidgetProvider::class.java)
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            providers.forEach { type -> manager.getAppWidgetIds(ComponentName(context, type)).forEach { update(context, manager, it, type) } }
        }
        private fun action(context: Context, value: String): PendingIntent = PendingIntent.getActivity(context, value.hashCode(), Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("spitify://widget/$value")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun update(context: Context, manager: AppWidgetManager, id: Int, type: Class<*>) {
            val view = RemoteViews(context.packageName, R.layout.music_widget)
            val prefs = context.getSharedPreferences("widget_state", 0)
            val library = type == LibraryWidgetProvider::class.java
            val friends = type == FriendsWidgetProvider::class.java
            val actions = if (library) listOf("library", "recent", "liked", "search") else if (friends) listOf("friends", "code", "rooms", "library") else listOf("toggle", "next", "shuffle", "liked")
            val labels = if (library) listOf("All songs", "Recently added", "Liked songs", "Search") else if (friends) listOf("Friends", "My code", "Listen together", "My library") else listOf(if (prefs.getBoolean("playing", false)) "Pause" else "Play", "Next", "Shuffle", "Liked songs")
            view.setTextViewText(R.id.widget_title, if (library) "Your Library" else if (friends) "Music together" else prefs.getString("title", "Ready when you are"))
            view.setTextViewText(R.id.widget_subtitle, if (library) "Your music, a tap away" else if (friends) "Friends · codes · listening rooms" else prefs.getString("artist", "Open Spitify and start listening"))
            val buttons = listOf(R.id.widget_one, R.id.widget_two, R.id.widget_three, R.id.widget_four)
            buttons.forEachIndexed { index, button -> view.setTextViewText(button, labels[index]); view.setOnClickPendingIntent(button, action(context, actions[index])) }
            view.setOnClickPendingIntent(R.id.widget_title, action(context, if (friends) "friends" else "library"))
            manager.updateAppWidget(id, view)
        }
    }
}
class LibraryWidgetProvider : MusicWidgetProvider()
class FriendsWidgetProvider : MusicWidgetProvider()

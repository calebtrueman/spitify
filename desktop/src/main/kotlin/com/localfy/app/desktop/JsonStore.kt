package com.localfy.app.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Small persistence for Spitify Desktop (the phones use Room / SharedPreferences / JSON files).
 * Each store is one JSON file in [AppPaths.dataDir]; writes are atomic (temp file + rename) and
 * coalesced off the calling thread, so the UI never waits on disk.
 */
class JsonStore(private val file: File) {
    constructor(name: String) : this(AppPaths.data("$name.json"))

    fun readObject(): JSONObject? = runCatching { if (file.isFile) JSONObject(file.readText()) else null }.getOrNull()
    fun readArray(): JSONArray? = runCatching { if (file.isFile) JSONArray(file.readText()) else null }.getOrNull()

    private var pending: ScheduledFuture<*>? = null
    private var latest: (() -> String)? = null

    /** Saves [text] about [delayMs] later; repeated calls in that window collapse into one write. */
    @Synchronized fun save(delayMs: Long = 300, text: () -> String) {
        latest = text
        if (pending?.isDone == false) return
        pending = writer.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Writes any pending change now (call on shutdown). */
    fun flush() {
        val text = synchronized(this) { latest.also { latest = null } } ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "spitify-store").apply { isDaemon = true } }
        private val all = mutableListOf<JsonStore>()
        fun flushAll() = synchronized(all) { all.toList() }.forEach { it.flush() }
    }

    init { synchronized(all) { all += this } }
}

/** Simple key/value settings (the desktop stand-in for SharedPreferences). */
class Prefs private constructor(private val store: JsonStore) {
    constructor(name: String) : this(JsonStore("prefs-$name"))
    /** Settings kept in [file] (tests and a second data folder). */
    constructor(file: File) : this(JsonStore(file))
    private val values: JSONObject = store.readObject() ?: JSONObject()

    @Synchronized fun getBoolean(key: String, default: Boolean) = values.optBoolean(key, default)
    @Synchronized fun getInt(key: String, default: Int) = values.optInt(key, default)
    @Synchronized fun getLong(key: String, default: Long) = values.optLong(key, default)
    @Synchronized fun getFloat(key: String, default: Float) = values.optDouble(key, default.toDouble()).toFloat()
    @Synchronized fun getString(key: String, default: String?): String? = if (values.has(key) && !values.isNull(key)) values.getString(key) else default
    @Synchronized fun getStringSet(key: String, default: Set<String>): Set<String> =
        values.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: default
    @Synchronized fun contains(key: String) = values.has(key)

    @Synchronized fun edit(block: Editor.() -> Unit) { Editor().block(); store.save { synchronized(this) { values.toString() } } }

    inner class Editor {
        fun putBoolean(key: String, v: Boolean) { values.put(key, v) }
        fun putInt(key: String, v: Int) { values.put(key, v) }
        fun putLong(key: String, v: Long) { values.put(key, v) }
        fun putFloat(key: String, v: Float) { values.put(key, v.toDouble()) }
        fun putString(key: String, v: String?) { if (v == null) values.remove(key) else values.put(key, v) }
        fun putStringSet(key: String, v: Set<String>) { values.put(key, JSONArray(v.toList())) }
        fun remove(key: String) { values.remove(key) }
    }
}

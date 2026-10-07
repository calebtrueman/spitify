package com.localfy.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/*
 * Small file helpers shared by the desktop library & data layer (the desktop stand-ins for
 * MediaStore ids, Room tables and SharedPreferences).
 */

/** Stable positive id for a file in the library: the same path always gives the same id. */
fun stableSongId(file: File): Long = stableSongId(file.absoluteFile.normalize().path)

fun stableSongId(path: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (digest[i].toLong() and 0xFF)
    // Keep it positive (negative ids are podcast episodes) and well below Long.MAX_VALUE.
    return (v and 0x3FFF_FFFF_FFFF_FFFFL).coerceAtLeast(1)
}

/** The audio file behind a library song (desktop library songs carry a file: URI), or null for streams/episodes. */
val Song.file: File?
    get() = sourceUri?.takeIf { it.startsWith("file:") }?.let { runCatching { File(URI(it)) }.getOrNull() }

/** Writes [bytes] to [file] atomically (temp file + move), so a crash never leaves half a file. */
fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
    try {
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        tmp.delete()
    }
}

fun writeAtomically(file: File, text: String) = writeAtomically(file, text.toByteArray(Charsets.UTF_8))

/**
 * One JSON document on disk, written atomically and coalesced on a background thread
 * (like [com.localfy.app.desktop.JsonStore], but with an explicit file so tests and profiles can
 * point it anywhere). [flush] writes immediately.
 */
class DataFile(val file: File) {
    private var pending: ScheduledFuture<*>? = null
    private var latest: (() -> String)? = null

    fun readText(): String? = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
    fun readObject(): JSONObject? = readText()?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun readArray(): JSONArray? = readText()?.let { runCatching { JSONArray(it) }.getOrNull() }

    @Synchronized fun save(delayMs: Long = 250, text: () -> String) {
        latest = text
        if (pending?.isDone == false) return
        pending = writer.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun flush() {
        val text = synchronized(this) { latest.also { latest = null } } ?: return
        runCatching { writeAtomically(file, text()) }
    }

    init { synchronized(all) { all += this } }

    companion object {
        private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "spitify-data").apply { isDaemon = true } }
        private val all = mutableListOf<DataFile>()

        /** Writes every pending change now (call on shutdown and in tests). */
        fun flushAll() = synchronized(all) { all.toList() }.forEach { it.flush() }

        init { Runtime.getRuntime().addShutdownHook(Thread { flushAll() }) }
    }
}

/** Key/value settings in one JSON file (the desktop SharedPreferences for repositories that take a data dir). */
class PrefsFile(file: File) {
    private val store = DataFile(file)
    private val values: JSONObject = store.readObject() ?: JSONObject()

    @Synchronized fun getBoolean(key: String, default: Boolean) = values.optBoolean(key, default)
    @Synchronized fun getLong(key: String, default: Long) = values.optLong(key, default)
    @Synchronized fun getString(key: String, default: String?): String? = if (values.has(key) && !values.isNull(key)) values.optString(key) else default
    @Synchronized fun getStringList(key: String): List<String>? =
        values.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, null) } }
    @Synchronized fun getStringSet(key: String, default: Set<String>): Set<String> = getStringList(key)?.toCollection(LinkedHashSet()) ?: default
    @Synchronized fun contains(key: String) = values.has(key)
    @Synchronized fun keys(): Set<String> = values.keySet().toSet()

    @Synchronized fun edit(block: Editor.() -> Unit) {
        Editor().block()
        store.save { synchronized(this) { values.toString() } }
    }

    fun flush() = store.flush()

    inner class Editor {
        fun putBoolean(key: String, v: Boolean) { values.put(key, v) }
        fun putLong(key: String, v: Long) { values.put(key, v) }
        fun putString(key: String, v: String?) { if (v == null) values.remove(key) else values.put(key, v) }
        fun putStringList(key: String, v: List<String>) { values.put(key, JSONArray(v)) }
        fun putStringSet(key: String, v: Set<String>) { values.put(key, JSONArray(v.toList())) }
        fun remove(key: String) { values.remove(key) }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
internal fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

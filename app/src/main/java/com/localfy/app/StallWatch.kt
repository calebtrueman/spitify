package com.localfy.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny on-phone profiler for "it's laggy" reports, since sideloaded installs have no debugger.
 *
 * A daemon thread pings the main thread every 100 ms. Whenever a ping goes unanswered it samples
 * the main thread's stack. Once a minute, if the UI was blocked for a noticeable total, the most
 * common blocking code paths (with how long each held the UI) go to the problem log that
 * Settings › Send problem report shares. Single freezes over 0.7 s are also reported on their own.
 */
object StallWatch {
    private const val TICK_MS = 100L
    private const val FREEZE_MS = 700L
    private const val WINDOW_MS = 60_000L
    private const val REPORT_BLOCKED_MS = 2_000L
    private const val MAX_REPORTS = 15
    private const val DEPTH = 14

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        Thread({
            var reports = 0
            var windowStart = SystemClock.uptimeMillis()
            var blocked = 0L
            val samples = HashMap<List<StackTraceElement>, Long>()
            while (reports < MAX_REPORTS) {
                val answered = AtomicBoolean(false)
                val sent = SystemClock.uptimeMillis()
                main.post { answered.set(true) }
                Thread.sleep(TICK_MS)
                var freezeStack: Array<StackTraceElement>? = null
                while (!answered.get()) {
                    val stack = mainThread.stackTrace
                    val key = stack.take(DEPTH)
                    samples[key] = (samples[key] ?: 0) + TICK_MS
                    blocked += TICK_MS
                    if (freezeStack == null && SystemClock.uptimeMillis() - sent >= FREEZE_MS) freezeStack = stack
                    Thread.sleep(TICK_MS)
                }
                val length = SystemClock.uptimeMillis() - sent
                if (freezeStack != null) {
                    reports++
                    CrashReport.recordNonFatal(app, "Froze for $length ms (playing: ${playing(app)})", Stall(freezeStack))
                }
                val now = SystemClock.uptimeMillis()
                if (now - windowStart >= WINDOW_MS) {
                    if (blocked >= REPORT_BLOCKED_MS) {
                        reports++
                        val top = samples.entries.sortedByDescending { it.value }.take(4)
                        val text = top.joinToString("\n\n") { (stack, ms) -> "~$ms ms in:\n" + stack.joinToString("\n") { "  at $it" } }
                        CrashReport.recordNonFatal(app, "UI blocked ${blocked} ms in the last minute (playing: ${playing(app)})", Summary(text))
                    }
                    windowStart = now; blocked = 0; samples.clear()
                }
            }
        }, "spitify-stall-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun playing(app: Context): String = runCatching {
        val a = app as LocalfyApp
        val song = a.player.state.value.currentId?.let(a::resolve) ?: return "nothing"
        when {
            song.sourceUri?.scheme == "spitify" -> "streamed song"
            song.isPodcast -> "episode"
            else -> "local song"
        }
    }.getOrDefault("unknown")

    private class Stall(trace: Array<StackTraceElement>) : Throwable("Main thread stack while frozen") {
        init { stackTrace = trace }
        override fun fillInStackTrace(): Throwable = this
    }

    private class Summary(text: String) : Throwable("Most common blocking code paths:\n$text") {
        init { stackTrace = emptyArray() }
        override fun fillInStackTrace(): Throwable = this
    }
}

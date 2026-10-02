package com.localfy.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps crash details on the phone so they can be sent to the developer from a sideloaded install
 * (no Play Console). A fatal crash is saved and shown on the next launch, before anything else
 * loads; problems the app recovered from go to a short log that Settings can share.
 */
object CrashReport {
    private const val TAG = "Spitify"

    private fun crashFile(c: Context) = File(c.filesDir, "last_crash.txt")
    private fun logFile(c: Context) = File(c.filesDir, "problems.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashFile(app).writeText(describe(app, "Crash on thread ${thread.name}", error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved crash from the last run, if the app crashed. */
    fun pending(context: Context): String? = crashFile(context).takeIf { it.isFile }?.readText()

    fun dismiss(context: Context) { crashFile(context).delete() }

    fun recordNonFatal(context: Context, what: String, error: Throwable) {
        Log.e(TAG, what, error)
        runCatching {
            val f = logFile(context)
            // Keep the newest few reports only.
            val old = if (f.isFile) f.readText().takeLast(20_000) else ""
            f.writeText(old + describe(context, what, error) + "\n\n")
        }
    }

    fun problems(context: Context): String? = logFile(context).takeIf { it.isFile && it.length() > 0 }?.readText()

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Spitify crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun describe(context: Context, what: String, error: Throwable): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return buildString {
            appendLine("$what — Spitify $version")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine()
            append(Log.getStackTraceString(error).take(12_000))
        }
    }
}

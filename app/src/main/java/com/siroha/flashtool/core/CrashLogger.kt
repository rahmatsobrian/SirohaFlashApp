package com.siroha.flashtool.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches any otherwise-uncaught exception (a genuine crash, not the
 * per-action ones [com.siroha.flashtool.ui.components.launchWithFeedback]
 * and friends already recover from), writes it to a small file, then hands
 * off to Android's normal crash handling so the OS still shows its usual
 * "app has stopped" behavior — this only adds a persisted record of what
 * actually happened first.
 *
 * Zero ongoing cost: [install] just registers a handler once at startup
 * (a single field write) and does nothing else until an actual crash
 * occurs, which is by definition not a hot path. The only I/O this class
 * ever does is: write one small text file at the moment of a crash, and
 * read at most one small text file back at the next app launch — nothing
 * runs on a timer or on every action.
 */
object CrashLogger {
    private const val MAX_KEPT_CRASH_LOGS = 5
    private const val DIR_NAME = "crash_logs"

    private fun crashDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(appContext, thread, throwable) }
            // Always defer to whatever handler Android/the runtime already had
            // installed (or terminate the process ourselves if none), so the
            // normal system crash behavior is completely unaffected.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$timestamp.txt")

        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        file.writeText(
            buildString {
                appendLine("Siroha Flash Tool crash log")
                appendLine("Time: ${Date()}")
                appendLine("Thread: ${thread.name}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine()
                append(stackTrace)
            }
        )

        // Keep only the most recent few crash files so this can never grow
        // unbounded across many runs.
        dir.listFiles()
            ?.filter { it.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEPT_CRASH_LOGS)
            ?.forEach { it.delete() }
    }

    /** The most recent crash log's content, if one exists — call once at startup to surface it, then [clear]. */
    fun latestCrashLog(context: Context): String? {
        val latest = crashDir(context).listFiles()
            ?.filter { it.name.startsWith("crash_") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching { latest.readText() }.getOrNull()
    }

    /** Call after surfacing [latestCrashLog] once, so the same crash isn't re-shown on every future launch. */
    fun clear(context: Context) {
        crashDir(context).listFiles()?.forEach { it.delete() }
    }
}

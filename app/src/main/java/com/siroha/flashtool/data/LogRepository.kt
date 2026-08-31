package com.siroha.flashtool.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR, SUCCESS }

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun format(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        return "[$ts] ${level.name.padEnd(7)} $tag: $message"
    }
}

/**
 * Central log sink for every flashing operation. Kept in memory for the Logs
 * screen and mirrored to a file under filesDir/logs so it can be shared (e.g.
 * pasted back into a chat, or attached to a bug report) via the system share
 * sheet / FileProvider.
 *
 * Two things below exist specifically because a high-volume caller (a
 * multi-GB ADB sideload logging one DEBUG entry per 64KB block — tens of
 * thousands of calls in a single operation — surfaced both the hard way):
 * an unbounded in-memory list and a fresh file-open per call are each, on
 * their own, enough to turn "detailed logging" into the very lag/stutter
 * it's meant to help diagnose.
 */
class LogRepository(context: Context) {
    companion object {
        /** Oldest entries beyond this are dropped from the in-memory/UI list — see class doc. The
         *  full, untruncated history is still always in [sessionFile] regardless of this cap. */
        private const val MAX_IN_MEMORY_ENTRIES = 4000
    }

    private val appContext = context.applicationContext
    private val logDir = File(appContext.filesDir, "logs").apply { mkdirs() }
    private val sessionFile = File(
        logDir,
        "session_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log"
    )

    // Kept open for the whole session instead of the previous
    // File.appendText() per call — that call opens a fresh FileOutputStream
    // (a real open()/close() syscall pair) every single time, which is
    // fine for occasional logging but adds up to real, measurable overhead
    // at the call volume a block-by-block transfer log produces. flush()
    // still runs after every write so a crash never loses a log line
    // that's already been recorded here — only the repeated open/close is
    // what's avoided.
    private val fileWriter: BufferedWriter? = runCatching {
        BufferedWriter(FileWriter(sessionFile, true))
    }.getOrNull()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        // Trim from the front instead of an unbounded append — a plain
        // `list + entry` here re-copies the entire (ever-growing) list on
        // every call, which is what actually made per-block sideload
        // logging measurably contribute to jank rather than just being a
        // lot of text. dropping the oldest entries past the cap keeps each
        // call's cost roughly constant regardless of session length.
        val current = _entries.value
        _entries.value = if (current.size >= MAX_IN_MEMORY_ENTRIES) {
            current.subList(current.size - MAX_IN_MEMORY_ENTRIES + 1, current.size) + entry
        } else {
            current + entry
        }
        runCatching {
            fileWriter?.write(entry.format())
            fileWriter?.newLine()
            fileWriter?.flush()
        }
    }

    fun info(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)
    fun warn(tag: String, msg: String) = log(LogLevel.WARN, tag, msg)
    fun error(tag: String, msg: String) = log(LogLevel.ERROR, tag, msg)
    fun success(tag: String, msg: String) = log(LogLevel.SUCCESS, tag, msg)
    /** Verbose, low-level detail (staged file listings, argv dumps, timing) —
     *  kept out of [LogLevel.INFO] so the Logs screen's normal view isn't
     *  drowned out, but still written to [entries]/[sessionFile] like every
     *  other level so it's there when someone actually needs to dig in. */
    fun debug(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)

    fun clear() {
        _entries.value = emptyList()
    }

    fun currentLogFile(): File = sessionFile
}

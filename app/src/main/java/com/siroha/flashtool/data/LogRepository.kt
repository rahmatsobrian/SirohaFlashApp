package com.siroha.flashtool.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }

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
 */
class LogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val logDir = File(appContext.filesDir, "logs").apply { mkdirs() }
    private val sessionFile = File(
        logDir,
        "session_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log"
    )

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        _entries.value = _entries.value + entry
        runCatching {
            sessionFile.appendText(entry.format() + "\n")
        }
    }

    fun info(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)
    fun warn(tag: String, msg: String) = log(LogLevel.WARN, tag, msg)
    fun error(tag: String, msg: String) = log(LogLevel.ERROR, tag, msg)
    fun success(tag: String, msg: String) = log(LogLevel.SUCCESS, tag, msg)

    fun clear() {
        _entries.value = emptyList()
    }

    fun currentLogFile(): File = sessionFile
}

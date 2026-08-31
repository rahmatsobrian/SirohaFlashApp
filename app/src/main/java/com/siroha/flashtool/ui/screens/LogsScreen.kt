package com.siroha.flashtool.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.siroha.flashtool.data.LogEntry
import com.siroha.flashtool.data.LogLevel
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.theme.HapticIconButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Same "good state" accent used on Home/Settings/USB-Fix, so a SUCCESS log
// line reads as the same green everywhere in the app.
private val ActiveGreen = Color(0xFF84D996)

@Composable
private fun LogLevel.accentColor(): Color = when (this) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.SUCCESS -> ActiveGreen
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    // Dimmer than INFO on purpose - DEBUG lines (staged file listings, argv
    // dumps, timing) are meant to fade into the background until someone's
    // actually scanning for them.
    LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
}

private val timeFormat by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

/** One log line: a colored accent bar (severity, scannable at a glance) plus a dim timestamp/tag header and the message itself - closer to a logcat/console reading pattern than a single flat-colored line. */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    val color = entry.level.accentColor()
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color, RoundedCornerShape(2.dp))
        )
        Column(modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                "${timeFormat.format(Date(entry.timestamp))}  ${entry.tag}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = color.copy(alpha = 0.8f)
            )
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }
    }
}

@Composable
private fun EmptyLogsState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.Article, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
            }
            Text("No logs yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Actions you perform will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LogsScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by logRepository.entries.collectAsState()
    val listState = rememberLazyListState()
    // DEBUG lines (staged file listings, argv dumps, timing) are hidden by
    // default so the normal view stays scannable - the entry count and the
    // exported .log file always include them regardless of this toggle.
    var showDebug by remember { mutableStateOf(false) }
    val visibleEntries = if (showDebug) entries else entries.filter { it.level != LogLevel.DEBUG }

    // A log view reads newest-at-bottom, like a terminal - follow new
    // entries as they arrive instead of leaving the user stuck at the top.
    LaunchedEffect(visibleEntries.size) {
        if (visibleEntries.isNotEmpty()) listState.animateScrollToItem(visibleEntries.size - 1)
    }

    Scaffold(
        topBar = {
            SirohaTopBar(
                "Logs",
                icon = Icons.Filled.Article,
                onBack = onBack,
                actions = {
                    HapticIconButton(onClick = { showDebug = !showDebug }) {
                        Icon(
                            Icons.Filled.BugReport,
                            contentDescription = if (showDebug) "Hide debug logs" else "Show debug logs",
                            tint = if (showDebug) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    HapticIconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            val file = logRepository.currentLogFile()
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share log"))
                        }
                    ) { Icon(Icons.Filled.Share, contentDescription = "Share log") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (entries.isEmpty()) {
                EmptyLogsState()
            } else {
                Text(
                    "${visibleEntries.size} ${if (visibleEntries.size == 1) "entry" else "entries"}" +
                        if (!showDebug && visibleEntries.size != entries.size) " (${entries.size - visibleEntries.size} debug hidden)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(visibleEntries) { entry -> LogEntryRow(entry) }
                }
            }
        }
    }
}

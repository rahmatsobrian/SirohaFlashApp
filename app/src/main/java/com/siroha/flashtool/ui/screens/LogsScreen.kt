package com.siroha.flashtool.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.siroha.flashtool.data.LogLevel
import com.siroha.flashtool.data.LogRepository

@Composable
fun LogsScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by logRepository.entries.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val file = logRepository.currentLogFile()
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share log"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share log") }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            items(entries) { entry ->
                val color = when (entry.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                    LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                }
                Text(entry.format(), color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

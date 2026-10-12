package com.siroha.flashtool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.OperationStatus
import com.siroha.flashtool.data.LogLevel
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.theme.HapticIconButton
import kotlinx.coroutines.delay

/**
 * Floating card that shows what is running (label + progress when a
 * transfer reports it) together with the live log lines produced since the
 * action started, so progress and log are visible without scrolling to the
 * "Recent activity" list at the bottom of the screen.
 *
 * It only appears when [busy] has stayed true for a moment, so instant
 * actions (a single getvar) don't flash it on and off. Place it inside a
 * Box over the screen content, aligned to the bottom. [actions] adds
 * optional buttons under the log (e.g. "Stop after this partition").
 */
@Composable
fun OperationOverlay(
    busy: Boolean,
    logRepository: LogRepository,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null
) {
    val label by OperationStatus.label.collectAsState()
    val progress by OperationStatus.progress.collectAsState()
    val entries by logRepository.entries.collectAsState()
    var show by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(busy) {
        if (busy) {
            startedAt = System.currentTimeMillis()
            delay(350)
            show = true
        } else {
            show = false
        }
    }

    AnimatedVisibility(
        visible = busy && show,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        val lines = remember(entries, startedAt) { entries.filter { it.timestamp >= startedAt }.takeLast(60) }
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (progress == null) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        label ?: "Working...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    progress?.let {
                        Text(
                            "${(it * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    HapticIconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = if (expanded) "Hide log" else "Show log"
                        )
                    }
                }
                val p = progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (expanded) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(lines.size) {
                        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        if (lines.isEmpty()) {
                            Text(
                                "Waiting for output...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(lines) { e ->
                                    val color: Color = when (e.level) {
                                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                        LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        "${e.tag}: ${e.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = color,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                actions?.invoke()
            }
        }
    }
}

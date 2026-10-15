package com.siroha.flashtool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.OperationStatus
import com.siroha.flashtool.data.LogLevel
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.theme.HapticIconButton
import kotlinx.coroutines.delay
import com.siroha.flashtool.ui.theme.LocalMonospaceFontFamily

/** Running average of a transfer's speed, restarted whenever a new transfer begins (total changes or bytes go backwards). */
private class SpeedTracker {
    private var startMs = 0L
    private var startBytes = 0L
    private var lastTotal = -1L
    private var lastDone = 0L
    private var speed = 0.0

    /** Returns bytes per second, or 0.0 until a second of data has been seen. */
    fun update(done: Long, total: Long, nowMs: Long): Double {
        if (total != lastTotal || done < lastDone) {
            startMs = nowMs
            startBytes = done
            lastTotal = total
            speed = 0.0
        }
        lastDone = done
        val seconds = (nowMs - startMs) / 1000.0
        if (seconds >= 1.0) {
            val average = (done - startBytes) / seconds
            speed = if (speed == 0.0) average else speed * 0.6 + average * 0.4
        }
        return speed
    }
}

private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun fmtTime(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Ring with the percentage inside; a plain spinner while the progress can't be measured. */
@Composable
private fun ProgressBadge(fraction: Float?) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        if (fraction != null) {
            val animated by animateFloatAsState(fraction, animationSpec = tween(350), label = "badge")
            CircularProgressIndicator(
                progress = { animated },
                modifier = Modifier.size(58.dp),
                strokeWidth = 5.dp,
                trackColor = track,
                strokeCap = StrokeCap.Round
            )
            Text(
                "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(58.dp),
                strokeWidth = 5.dp,
                trackColor = track,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

/** Rounded bar: fills smoothly when the fraction is known, otherwise a block sweeps across. */
@Composable
private fun OverlayProgressBar(fraction: Float?) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val animated by animateFloatAsState(fraction ?: 0f, animationSpec = tween(350), label = "bar")
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
        val corner = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = corner)
        if (fraction != null) {
            val width = (size.width * animated).coerceAtLeast(size.height)
            drawRoundRect(color = fill, size = Size(width, size.height), cornerRadius = corner)
        } else {
            val block = size.width * 0.35f
            val x = (size.width + block) * sweep - block
            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                drawRoundRect(color = fill, topLeft = Offset(x, 0f), size = Size(block, size.height), cornerRadius = corner)
            }
        }
    }
}

/**
 * Floating card that shows what is running together with the live log lines
 * produced since the action started, so progress and log are visible without
 * scrolling to the "Recent activity" list at the bottom of the screen.
 *
 * Shows a progress ring with the percentage, the action's title and stage line,
 * transferred size / speed / ETA when a transfer reports bytes (otherwise the
 * elapsed time), a rounded progress bar, and the log (DEBUG noise is left out).
 * It only appears when [busy] has stayed true for a moment, so instant actions
 * don't flash it on and off. Place it inside a Box over the screen content,
 * aligned to the bottom. [actions] adds optional buttons under the log.
 */
@Composable
fun OperationOverlay(
    busy: Boolean,
    logRepository: LogRepository,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null
) {
    val label by OperationStatus.label.collectAsState()
    val stage by OperationStatus.stage.collectAsState()
    val progress by OperationStatus.progress.collectAsState()
    val entries by logRepository.entries.collectAsState()
    var show by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(busy) {
        if (busy) {
            startedAt = System.currentTimeMillis()
            delay(350)
            show = true
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
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
        val lines = remember(entries, startedAt) {
            entries.filter { it.timestamp >= startedAt && it.level != LogLevel.DEBUG }.takeLast(40)
        }
        val tracker = remember(label) { SpeedTracker() }
        val p = progress
        val speed = if (p != null && p.bytesTotal > 0) tracker.update(p.bytesDone, p.bytesTotal, System.currentTimeMillis()) else 0.0
        val elapsed = ((now - startedAt) / 1000).coerceAtLeast(0)
        val stats = buildString {
            if (p != null && p.bytesTotal > 0) {
                append("${fmtBytes(p.bytesDone)} / ${fmtBytes(p.bytesTotal)}")
                if (speed > 0) {
                    append("  |  ${fmtBytes(speed.toLong())}/s")
                    val remaining = ((p.bytesTotal - p.bytesDone) / speed).toLong()
                    if (p.fraction < 1f) append("  |  ETA ${fmtTime(remaining)}")
                }
            } else {
                append("Elapsed ${fmtTime(elapsed)}")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp).animateContentSize(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ProgressBadge(p?.fraction)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            label ?: "Working...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        stage?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            stats,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = LocalMonospaceFontFamily.current,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HapticIconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = if (expanded) "Hide log" else "Show log"
                        )
                    }
                }
                Box(modifier = Modifier.padding(top = 14.dp, end = 10.dp)) {
                    OverlayProgressBar(p?.fraction)
                }
                if (expanded) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(lines.size) {
                        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.padding(top = 12.dp, end = 10.dp)
                    ) {
                        if (lines.isEmpty()) {
                            Text(
                                "Waiting for output...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 132.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(lines) { e ->
                                    val dot: Color = when (e.level) {
                                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                        LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier = Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(dot)
                                        )
                                        Text(
                                            buildAnnotatedString {
                                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)) {
                                                    append(e.tag)
                                                    append("  ")
                                                }
                                                append(e.message)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = LocalMonospaceFontFamily.current,
                                            color = if (e.level == LogLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (actions != null) {
                    Box(modifier = Modifier.padding(top = 12.dp, end = 10.dp)) { actions() }
                }
            }
        }
    }
}

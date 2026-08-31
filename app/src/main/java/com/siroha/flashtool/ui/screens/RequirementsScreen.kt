package com.siroha.flashtool.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.siroha.flashtool.ui.theme.HapticIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.BinaryManager
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.ui.components.GroupRowSpacing
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.groupRowShape
import kotlinx.coroutines.launch

private data class Check(val label: String, val ok: Boolean, val hint: String, val optional: Boolean = false)

/** Same accent used for Home's "Active" banner, kept here so both screens agree on what "ready" looks like. */
private val ActiveGreen = Color(0xFF84d996)

/**
 * One requirement as a clustered M3 list row (icon in a tonal circle,
 * headline + supporting hint) - mirrors [com.siroha.flashtool.ui.components.MenuListGroup]
 * so this screen reads like the rest of the app instead of a bare icon+text
 * list.
 */
@Composable
private fun CheckStatusRow(check: Check, shape: Shape) {
    val notGrantedColor = if (check.optional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    val tint = if (check.ok) ActiveGreen else notGrantedColor
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.18f), modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        when {
                            check.ok -> Icons.Filled.CheckCircle
                            check.optional -> Icons.Filled.Info
                            else -> Icons.Filled.Cancel
                        },
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        check.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (check.optional) {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(
                                "Optional",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (!check.ok) {
                    Text(
                        check.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Replaces flash.sh's menu_install - that module was Termux package
 * management (pkg install adb/python3/etc.), which doesn't apply once this
 * is a native APK. What matters natively is: is the right qdl binary
 * bundled, is a USB device visible. This app never needs root or
 * Shizuku - QDL/bypass-UBL flash entirely through the no-root USB bridge
 * ([com.siroha.flashtool.core.UsbBridgeServer]), and Fastboot/ADB are
 * plain USB protocols to begin with.
 *
 * Checks now run automatically on entry (matching Home's live-status
 * behavior) instead of waiting for a tap, with a top-bar refresh action -
 * spinning while a run is in flight - to re-check on demand. A colored
 * summary banner up top gives an at-a-glance verdict before the person
 * reads the individual rows below.
 */
@Composable
fun RequirementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    val refreshRotation = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (running) {
            refreshRotation.snapTo(0f)
            refreshRotation.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(animation = tween(700, easing = LinearEasing))
            )
        } else {
            refreshRotation.stop()
            refreshRotation.snapTo(0f)
        }
    }

    suspend fun runChecks() {
        running = true
        val usbDevices = UsbDeviceHelper.listDevices(context)
        val qdl = BinaryManager.qdlPath(context)
        checks = listOf(
            Check(
                "qdl binary present for this ABI",
                qdl != null,
                "Missing libqdl.so for ABI(s): ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
            ),
            Check(
                "USB device visible",
                usbDevices.isNotEmpty(),
                "No USB device detected - connect the target via OTG (see USB/OTG Fix)."
            ),
        )
        running = false
    }

    LaunchedEffect(Unit) { runChecks() }

    val failedCount = checks.count { !it.ok && !it.optional }

    Scaffold(
        topBar = {
            SirohaTopBar(
                "Requirements & Status",
                icon = Icons.Filled.TaskAlt,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { scope.launch { runChecks() } },
                        enabled = !running
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Re-run checks",
                            modifier = Modifier.graphicsLayer { rotationZ = refreshRotation.value }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when {
                checks.isNotEmpty() -> item {
                    val allOk = failedCount == 0
                    val bannerColor = if (allOk) ActiveGreen else MaterialTheme.colorScheme.error
                    val title = if (allOk) "All checks passed" else if (failedCount == 1) "1 issue found" else "$failedCount issues found"
                    val subtitle = if (allOk) "Ready for flashing." else "Resolve the items below before flashing."
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = bannerColor.copy(alpha = 0.18f * com.siroha.flashtool.ui.theme.LocalCardOpacity.current)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                if (allOk) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = bannerColor
                            )
                            Column {
                                Text(title, style = MaterialTheme.typography.titleMedium, color = bannerColor, fontWeight = FontWeight.Bold)
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bannerColor)
                            }
                        }
                    }
                }
                running -> item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (checks.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        checks.forEachIndexed { index, check ->
                            CheckStatusRow(check = check, shape = groupRowShape(index, checks.size))
                        }
                    }
                }
            }
        }
    }
}

package com.siroha.flashtool.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.siroha.flashtool.core.SafFiles

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

/**
 * Asks once per launch for "All files access" so flashing, booting, sideloading and the terminal
 * can use the picked image where it is instead of copying it (multi-GB) into the app cache.
 * "Not now" is fine: everything keeps working through the cache-copy backup, just slower.
 */
@Composable
fun AllFilesAccessPrompt() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SafFiles.hasAllFilesAccess()) }
    var dismissed by rememberSaveable { mutableStateOf(false) }

    // Re-check when the person comes back from the system settings screen.
    DisposableEffect(context) {
        val owner = context.activityOrNull() as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = SafFiles.hasAllFilesAccess()
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    if (granted || dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Allow All files access?") },
        text = {
            Text(
                "Fastboot flash, boot TWRP, A/B partition, GSI, Mi Flash, QDL, ADB sideload and the terminal " +
                    "can then read your images straight from their folder - no copy into the app cache, " +
                    "no extra free space needed. Without it the app still works by copying each file to " +
                    "its cache first (slower for big images)."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                SafFiles.requestAllFilesAccess(context)
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Not now") } }
    )
}

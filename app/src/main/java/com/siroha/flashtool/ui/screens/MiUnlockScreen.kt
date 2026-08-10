package com.siroha.flashtool.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.MiUnlockApi
import com.siroha.flashtool.core.MiUnlockClearInfo
import com.siroha.flashtool.core.MiUnlockOperations
import com.siroha.flashtool.core.MiUnlockResult
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch

private const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=unlockApi&checkSafeAddress=true"
private const val LOGIN_SUCCESS_PATTERN = "{\"R\":\"\",\"S\":\"OK\"}"

private sealed class UnlockStep {
    object Login : UnlockStep()
    object ResolvingSession : UnlockStep()
    object ConnectDevice : UnlockStep()
    object Checking : UnlockStep()
    data class Confirm(val info: MiUnlockClearInfo) : UnlockStep()
    object Unlocking : UnlockStep()
    data class Done(val result: MiUnlockResult) : UnlockStep()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MiUnlockScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fastboot = remember { FastbootOperations(context, logRepository) }
    val miUnlock = remember { MiUnlockOperations(context, logRepository, fastboot) }
    var step by remember { mutableStateOf<UnlockStep>(UnlockStep.Login) }
    var userId by remember { mutableStateOf("") }

    Scaffold(
        topBar = { SirohaTopBar("Mi Unlock", icon = Icons.Filled.LockOpen, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val s = step) {
                is UnlockStep.Login -> {
                    Text(
                        "Log in with your Xiaomi account — the same one your phone is signed into. " +
                            "This loads Xiaomi's own account.xiaomi.com login page directly; your " +
                            "credentials go to Xiaomi, not through this app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = "(Android) Mobile"
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                            val cleaned = html
                                                .replace("\\u003C", "<").replace("\\u003E", ">")
                                                .replace("\\\"", "\"").replace("\\\\", "\\")
                                            if (cleaned.contains(LOGIN_SUCCESS_PATTERN)) {
                                                val cookieString = CookieManager.getInstance().getCookie("https://account.xiaomi.com")
                                                fun extract(key: String) = cookieString?.split(";")?.map { it.trim() }
                                                    ?.find { it.startsWith("$key=") }?.substringAfter("=")
                                                val passToken = extract("passToken")
                                                val deviceId = extract("deviceId")
                                                val uid = extract("userId")
                                                if (!passToken.isNullOrEmpty() && !deviceId.isNullOrEmpty() && !uid.isNullOrEmpty()) {
                                                    userId = uid
                                                    step = UnlockStep.ResolvingSession
                                                    scope.launch {
                                                        val ok = miUnlock.resolveServerSession(
                                                            MiUnlockApi.AccountSession(passToken, deviceId, uid)
                                                        )
                                                        step = if (ok) UnlockStep.ConnectDevice else UnlockStep.Done(MiUnlockResult.Failed("Could not resolve account session"))
                                                    }
                                                } else {
                                                    view.loadUrl(LOGIN_URL)
                                                }
                                            }
                                        }
                                    }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) {
                                            step = UnlockStep.Done(MiUnlockResult.Failed("Page load error: ${error.description}"))
                                        }
                                    }
                                }
                                loadUrl(LOGIN_URL)
                            }
                        }
                    )
                }

                is UnlockStep.ResolvingSession -> StepProgress("Resolving account session...")

                is UnlockStep.ConnectDevice -> {
                    Text(
                        "Power off the phone, then hold Volume Down + Power to enter Bootloader mode, " +
                            "and connect it via USB OTG.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                step = UnlockStep.Checking
                                val connected = fastboot.connect()
                                if (!connected) {
                                    step = UnlockStep.Done(MiUnlockResult.Failed("Could not connect to the device in fastboot mode"))
                                    return@launch
                                }
                                val infoOk = miUnlock.readDeviceInfo()
                                if (!infoOk) {
                                    step = UnlockStep.Done(MiUnlockResult.Failed("Could not read device info"))
                                    return@launch
                                }
                                val clearInfo = miUnlock.checkClearStatus()
                                step = if (clearInfo != null) UnlockStep.Confirm(clearInfo) else UnlockStep.Done(MiUnlockResult.Failed("Could not check unlock eligibility"))
                            }
                        }
                    ) { Text("Connect and check eligibility") }
                }

                is UnlockStep.Checking -> StepProgress("Connecting and checking eligibility...")

                is UnlockStep.Confirm -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.info.notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                if (s.info.wipesUserData) "This WILL wipe all user data on the device." else "This does not wipe user data.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                step = UnlockStep.Unlocking
                                step = UnlockStep.Done(miUnlock.performUnlock(userId))
                            }
                        }
                    ) { Text("Unlock bootloader") }
                }

                is UnlockStep.Unlocking -> StepProgress("Unlocking — do not disconnect the phone...")

                is UnlockStep.Done -> {
                    val result = s.result
                    Text(
                        when (result) {
                            is MiUnlockResult.Unlocked -> "Bootloader unlocked successfully."
                            is MiUnlockResult.Failed -> "Failed: ${result.reason}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (result is MiUnlockResult.Unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StepProgress(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

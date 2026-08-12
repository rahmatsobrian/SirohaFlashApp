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
                    // weight(1f) — NOT fillMaxSize() — since this is a sibling
                    // after the Text above inside a plain Column. fillMaxSize()
                    // here fights the Column for space instead of taking what's
                    // actually left over.
                    var hasStartedLoad by remember { mutableStateOf(false) }
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // Explicit LayoutParams as a defensive belt-and-
                                // suspenders alongside the Compose modifier above —
                                // Chromium's compositor can read the view's initial
                                // bounds before Compose's own layout pass finishes,
                                // and some pages (ones whose CSS uses height:100%)
                                // render nothing/black if that first measurement is
                                // zero-height. Combined with deferring loadUrl to
                                // `update` below (which only fires once real,
                                // stable bounds exist), this avoids that race.
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                // Compose's AndroidView + WebView is a known
                                // combination that can render solid black until
                                // forced onto its own hardware-accelerated layer —
                                // this doesn't happen in a plain XML Activity
                                // (which is how the reference app runs it), only
                                // through the Compose interop layer.
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                setBackgroundColor(android.graphics.Color.WHITE)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = "(Android) Mobile"
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                // Xiaomi's login page pulls some sub-resources over
                                // plain HTTP; without this, WebView can silently
                                // block them and leave the page half-rendered
                                // (looks identical to a fully black/blank page).
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                                    // Matches offici5l/MiTools' own fix (v1.0.6, commit
                                    // df35cfb, "Fix: Error loading page"): a failed
                                    // sub-resource (ad script, tracking pixel, etc.) used
                                    // to abort the whole login flow even though the actual
                                    // page loaded fine — only a main-frame error is fatal.
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (!request.isForMainFrame) return
                                        step = UnlockStep.Done(
                                            MiUnlockResult.Failed("${error.description} (code ${error.errorCode})")
                                        )
                                    }
                                }
                            }
                        },
                        // loadUrl deferred here instead of in `factory`: `update`
                        // only runs once the AndroidView has real, stable measured
                        // bounds, so the page's first paint happens against its
                        // actual final size instead of a transient zero-height pass.
                        update = { webView ->
                            if (!hasStartedLoad) {
                                hasStartedLoad = true
                                webView.loadUrl(LOGIN_URL)
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

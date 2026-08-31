package com.siroha.flashtool.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs a suspend action and immediately surfaces its outcome as a Snackbar,
 * so the person doesn't have to scroll down to the log list just to find
 * out whether the button they tapped actually worked. [busy] is set true
 * for the duration of [action] so the caller's buttons can disable
 * themselves while it runs.
 *
 * Any exception [action] throws (e.g. a USB I/O error while waiting on a
 * pending "Allow USB debugging?" prompt) is caught here and turned into a
 * failure Snackbar instead of crashing the app — previously an uncaught
 * exception from one of these coroutines (launched from `rememberCoroutineScope`,
 * which has no exception handler by default) would propagate all the way up
 * and force-close the whole app.
 */
fun CoroutineScope.launchWithFeedback(
    snackbarHostState: SnackbarHostState,
    label: String,
    setBusy: (Boolean) -> Unit = {},
    action: suspend () -> Boolean
) {
    launch {
        setBusy(true)
        val ok = try {
            action()
        } catch (e: CancellationException) {
            throw e // preserve normal coroutine cancellation — never swallow this one
        } catch (e: Throwable) {
            false
        } finally {
            setBusy(false)
        }
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = if (ok) "$label — success" else "$label — failed (see log for details)",
            duration = SnackbarDuration.Short
        )
    }
}

/** Same as [launchWithFeedback] but for actions that return a descriptive String rather than Boolean. */
fun CoroutineScope.launchWithTextFeedback(
    snackbarHostState: SnackbarHostState,
    label: String,
    isSuccess: (String) -> Boolean,
    setBusy: (Boolean) -> Unit = {},
    onResult: (String) -> Unit = {},
    action: suspend () -> String
) {
    launch {
        setBusy(true)
        val result = try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "ERROR: ${e.javaClass.simpleName} — ${e.message ?: "unknown error"}"
        } finally {
            setBusy(false)
        }
        onResult(result)
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = if (isSuccess(result)) "$label — success" else "$label — failed (see result below)",
            duration = SnackbarDuration.Short
        )
    }
}

/**
 * For actions whose result already carries a genuine, protocol-verified
 * success flag (e.g. an ADB shell v2 exit code) rather than one guessed by
 * scanning result text for the word "ERROR" — takes any `(text, success)`-
 * shaped result via the [text]/[success] extractors so it works with any
 * such result type without a shared base class. Requires a [fallback]
 * constructor for the rare case [action] throws before producing any [T]
 * at all, so there's still something to hand to [onResult]/display.
 */
fun <T> CoroutineScope.launchWithOutcomeFeedback(
    snackbarHostState: SnackbarHostState,
    label: String,
    text: (T) -> String,
    success: (T) -> Boolean,
    setBusy: (Boolean) -> Unit = {},
    onResult: (T) -> Unit = {},
    fallback: (Throwable) -> T,
    action: suspend () -> T
) {
    launch {
        setBusy(true)
        val result = try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fallback(e)
        } finally {
            setBusy(false)
        }
        onResult(result)
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = if (success(result)) "$label — success" else "$label — failed (see result below)",
            duration = SnackbarDuration.Short
        )
    }
}

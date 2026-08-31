package com.siroha.flashtool.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * Drop-in replacement for `SnackbarHost(hostState)` that also lets the
 * person swipe a Snackbar away instead of having to wait for it to
 * auto-dismiss — Material3's plain SnackbarHost doesn't support that
 * gesture on its own, it needs to be wrapped in a SwipeToDismissBox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DismissibleSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data ->
        // Keyed on the snackbar's own data so each new Snackbar starts from
        // a fresh, un-swiped state rather than inheriting the previous
        // one's dismissed position.
        key(data) {
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        data.dismiss()
                    }
                    true
                }
            )
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {},
                content = { Snackbar(snackbarData = data) }
            )
        }
    }
}

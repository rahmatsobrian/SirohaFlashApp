package com.siroha.flashtool.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app is doing right now, for the floating progress overlay: the
 * label of the running action and, when a transfer can report it, a 0..1
 * progress fraction. Set by the shared action helpers (label) and by the
 * transfer loops (progress); one operation runs at a time, so a single
 * process-wide holder is enough.
 */
object OperationStatus {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    @Volatile
    private var lastPermille = -1

    fun begin(label: String) {
        lastPermille = -1
        _label.value = label
        _progress.value = null
    }

    fun end() {
        lastPermille = -1
        _label.value = null
        _progress.value = null
    }

    /** Called from transfer loops (once per chunk) - only publishes when the value moves by 0.1 %. */
    fun reportProgress(done: Long, total: Long) {
        if (total <= 0) return
        val permille = (done * 1000 / total).toInt().coerceIn(0, 1000)
        if (permille == lastPermille) return
        lastPermille = permille
        _progress.value = permille / 1000f
    }
}

package com.siroha.flashtool.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of how far the running operation is. [fraction] is 0..1 for the
 * whole operation. [bytesDone]/[bytesTotal] describe the transfer that is
 * currently moving (or -1 when progress is step- or percentage-based only),
 * which lets the overlay show sizes, speed and an ETA.
 */
data class OperationProgress(
    val fraction: Float,
    val bytesDone: Long = -1L,
    val bytesTotal: Long = -1L
)

/**
 * What the app is doing right now, for the floating progress overlay: the
 * action's label, an optional stage line ("Copying system.img...", "Flashing
 * boot (3/12)"), and a 0..1 progress value when it can be measured. Set by the
 * shared action helpers (label), transfer loops (bytes) and multi-step flows
 * (steps). One operation runs at a time, so a single process-wide holder is
 * enough.
 *
 * Two ways progress can be fed in, and they combine:
 *  - [reportSteps]: "step 3 of 12" for flows made of several actions.
 *  - [reportProgress]: bytes moved in the current transfer. Inside a step it
 *    fills that step's share of the bar; outside of steps it is the whole bar.
 * Flows with no byte counter (QDL, Mi Unlock) use [reportFraction] directly.
 */
object OperationStatus {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    private val _stage = MutableStateFlow<String?>(null)
    val stage: StateFlow<String?> = _stage.asStateFlow()

    private val _progress = MutableStateFlow<OperationProgress?>(null)
    val progress: StateFlow<OperationProgress?> = _progress.asStateFlow()

    private var stepsDone = 0
    private var stepsTotal = 0
    private var phaseFrom = 0f
    private var phaseTo = 1f
    private var lastPermille = -1
    private var lastPublishNs = 0L

    @Synchronized
    fun begin(label: String) {
        stepsDone = 0
        stepsTotal = 0
        phaseFrom = 0f
        phaseTo = 1f
        lastPermille = -1
        lastPublishNs = 0L
        _label.value = label
        _stage.value = null
        _progress.value = null
    }

    @Synchronized
    fun end() {
        stepsDone = 0
        stepsTotal = 0
        phaseFrom = 0f
        phaseTo = 1f
        lastPermille = -1
        lastPublishNs = 0L
        _label.value = null
        _stage.value = null
        _progress.value = null
    }

    /** Free-text line under the title; null clears it. */
    fun setStage(stage: String?) {
        _stage.value = stage
    }

    /**
     * Restricts the next [reportProgress] values to [from]..[to] of the current
     * step - e.g. copying a file to cache takes the first slice of a flash step
     * and the USB transfer the rest, so the bar doesn't run 0-100% twice.
     */
    @Synchronized
    fun setPhase(from: Float, to: Float) {
        phaseFrom = from.coerceIn(0f, 1f)
        phaseTo = to.coerceIn(phaseFrom, 1f)
    }

    /** Marks [done] of [total] steps finished and starts the next one with a fresh phase. */
    @Synchronized
    fun reportSteps(done: Int, total: Int, stage: String? = null) {
        stepsDone = done.coerceAtLeast(0)
        stepsTotal = total.coerceAtLeast(0)
        phaseFrom = 0f
        phaseTo = 1f
        if (stage != null) _stage.value = stage
        publish(if (stepsTotal > 0) stepsDone.toFloat() / stepsTotal else 0f, -1L, -1L)
    }

    /** Bytes moved in the current transfer. Called once per chunk; only published when the bar moves by 0.1 %. */
    @Synchronized
    fun reportProgress(done: Long, total: Long) {
        if (total <= 0) return
        val raw = (done.toFloat() / total).coerceIn(0f, 1f)
        val inPhase = phaseFrom + (phaseTo - phaseFrom) * raw
        val overall = if (stepsTotal > 0) (stepsDone + inPhase) / stepsTotal else inPhase
        publish(overall, done, total)
    }

    /** Progress that has no byte counter (parsed from tool output, protocol stages, ...). */
    @Synchronized
    fun reportFraction(fraction: Float, stage: String? = null) {
        if (stage != null) _stage.value = stage
        publish(fraction, -1L, -1L)
    }

    /** Publishes when the bar moved by 0.1 % or a quarter second passed (keeps speed/ETA fresh without a recomposition per 16 KB chunk). */
    private fun publish(fraction: Float, done: Long, total: Long) {
        val f = fraction.coerceIn(0f, 1f)
        val permille = (f * 1000).toInt()
        val now = System.nanoTime()
        if (permille == lastPermille && now - lastPublishNs < 250_000_000L) return
        lastPermille = permille
        lastPublishNs = now
        _progress.value = OperationProgress(f, done, total)
    }
}

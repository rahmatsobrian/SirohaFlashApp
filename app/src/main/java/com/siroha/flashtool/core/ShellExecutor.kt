package com.siroha.flashtool.core

import kotlinx.coroutines.flow.Flow

/** Result of running one command to completion. */
data class ShellResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/** Which privilege backend is currently driving command execution. */
enum class ExecutionMode {
    ROOT,       // su via libsu — works on rooted devices (Magisk / KernelSU / APatch)
    SHIZUKU,    // Shizuku (ADB or root-started service) — works without root
    UNAVAILABLE // Neither is ready; flashing operations are disabled
}

/**
 * Common contract for "run this command with elevated privileges" regardless of
 * whether that privilege comes from su or from Shizuku. Every screen in the app
 * talks to this interface only, so QDL/fastboot/bypass-UBL logic never needs to
 * care which backend is actually active.
 */
interface ShellExecutor {
    val mode: ExecutionMode
    suspend fun isReady(): Boolean
    suspend fun requestAccess(): Boolean
    suspend fun exec(command: String): ShellResult

    /** Streams stdout+stderr lines live, for long-running flashes (qdl, dd, etc). */
    fun execStreaming(command: String): Flow<String>
}

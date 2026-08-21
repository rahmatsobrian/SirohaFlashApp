package com.siroha.flashtool.core

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Executes commands as root via libsu. Requires the user to grant Termux/this
 * app superuser access through Magisk, KernelSU, or APatch — same requirement
 * the original flash.sh had ("Root wajib").
 */
class RootShellExecutor : ShellExecutor {

    override val mode = ExecutionMode.ROOT

    // NOTE: Shell.setDefaultBuilder() is intentionally NOT called here
    // anymore. It now lives in SirohaApplication.onCreate(), set once
    // before anything in the app can reach Shell.getShell(). Calling it
    // again here — this class is instantiated lazily, well after Home
    // screen's Live Status may have already created the global shell via
    // passiveStatus() — throws "IllegalStateException: The main shell was
    // already created" and force-closes the app the first time `.root` is
    // accessed. See SirohaApplication for the full explanation.

    // libsu's Shell.getShell() throws (NoShellException and friends) rather
    // than returning a failure value whenever it can't hand back a working
    // shell — e.g. su was revoked after being granted earlier this session,
    // the su binary hiccups, or getShell() races with another caller
    // touching the global shell (Settings' "Use Root" button and
    // Requirements' "Run checks" both call this, and Live Status polls
    // root status in the background at the same time). Left unguarded,
    // that exception propagates out of the coroutine this runs in and
    // force-closes the app — which is why this used to crash for root but
    // never for Shizuku (ShizukuShellExecutor already catches Throwable
    // around every Shizuku call above). Catching here makes root behave
    // the same way: report "not ready" instead of taking the app down.
    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun requestAccess(): Boolean = withContext(Dispatchers.IO) {
        // libsu triggers the su prompt lazily on first getShell() call.
        try {
            Shell.getShell().isRoot
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val code = Shell.cmd(command).to(out, err).exec().code
            ShellResult(code, out, err)
        } catch (t: Throwable) {
            ShellResult(-1, emptyList(), listOf("Root shell error: ${t.message ?: t.javaClass.simpleName}"))
        }
    }

    /**
     * True streaming for long flash operations (qdl prints progress to stdout).
     * We shell out to a raw `su -c` process so we can read output line-by-line
     * as it's produced, rather than waiting for the whole command to finish.
     */
    override fun execStreaming(command: String): Flow<String> = callbackFlow {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            trySend("[error] Failed to start root shell: ${t.message ?: t.javaClass.simpleName}")
            close()
            return@callbackFlow
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                trySend(line)
            }
        } catch (t: Throwable) {
            trySend("[error] Root shell stream error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            reader.close()
            process.waitFor()
        }
        awaitClose { process.destroy() }
    }.flowOn(Dispatchers.IO)
}

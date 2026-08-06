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

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(600) // long timeout: flashing a full ROM can take minutes
        )
    }

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    override suspend fun requestAccess(): Boolean = withContext(Dispatchers.IO) {
        // libsu triggers the su prompt lazily on first getShell() call.
        Shell.getShell().isRoot
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val code = Shell.cmd(command).to(out, err).exec().code
        ShellResult(code, out, err)
    }

    /**
     * True streaming for long flash operations (qdl prints progress to stdout).
     * We shell out to a raw `su -c` process so we can read output line-by-line
     * as it's produced, rather than waiting for the whole command to finish.
     */
    override fun execStreaming(command: String): Flow<String> = callbackFlow {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                trySend(line)
            }
        } finally {
            reader.close()
            process.waitFor()
        }
        awaitClose { process.destroy() }
    }.flowOn(Dispatchers.IO)
}

package com.siroha.flashtool.core

import java.io.BufferedReader
import java.io.InputStreamReader

private const val OUT_MARKER = "\n<<<SIROHA_OUT>>>\n"
private const val ERR_MARKER = "\n<<<SIROHA_ERR>>>\n"

/**
 * Hosted by Shizuku (declared to Shizuku via bindUserService in
 * ShizukuShellExecutor). This class's process is spawned and owned by
 * Shizuku's privileged shell context, so ProcessBuilder here already runs
 * with whatever elevated rights Shizuku was granted — no su call needed.
 */
class ShellUserService : IShellService.Stub() {

    // No-arg constructor is required by Shizuku's UserService binding.
    constructor()

    override fun runCommand(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val out = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val err = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val code = process.waitFor()
            "$code$OUT_MARKER$out$ERR_MARKER$err"
        } catch (t: Throwable) {
            "-1$OUT_MARKER$OUT_MARKER${t.stackTraceToString()}"
        }
    }

    override fun destroy() {
        // Shizuku will unbind/kill the hosting process; nothing to clean up.
    }
}

/** Parses the packed string produced by [ShellUserService.runCommand]. */
fun parseUserServiceResult(raw: String): ShellResult {
    val outIdx = raw.indexOf(OUT_MARKER)
    val errIdx = raw.indexOf(ERR_MARKER)
    if (outIdx < 0 || errIdx < 0) return ShellResult(-1, emptyList(), listOf("malformed response"))
    val code = raw.substring(0, outIdx).toIntOrNull() ?: -1
    val out = raw.substring(outIdx + OUT_MARKER.length, errIdx)
    val err = raw.substring(errIdx + ERR_MARKER.length)
    return ShellResult(
        exitCode = code,
        stdout = out.lines().filter { it.isNotEmpty() },
        stderr = err.lines().filter { it.isNotEmpty() }
    )
}

package com.siroha.flashtool.core

import java.io.BufferedReader

// Control-character delimiters: these can't legitimately appear in normal
// shell stdout/stderr, so — unlike newline-wrapped text markers — they can
// never overlap with each other or with the surrounding output. (An earlier
// version used "\n<<<SIROHA_OUT>>>\n" style markers; when stdout was empty,
// the marker's own trailing/leading newlines could overlap and crash the
// parser with a StringIndexOutOfBoundsException. This format can't do that.)
internal const val OUT_MARKER = "\u0001SIROHA_OUT\u0001"
internal const val ERR_MARKER = "\u0001SIROHA_ERR\u0001"

/**
 * Hosted by Shizuku (declared to Shizuku via bindUserService in
 * ShizukuShellExecutor). This class's process is spawned and owned by
 * Shizuku's privileged shell context, so ProcessBuilder here already runs
 * with whatever elevated rights Shizuku was granted — no su call needed.
 */
class ShellUserService : IShellService.Stub() {

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
            // Report the real failure as stderr instead of losing it —
            // previously this used OUT_MARKER twice, which meant ERR_MARKER
            // was never found and the parser reported a generic "malformed
            // response" instead of the actual exception.
            "-1$OUT_MARKER$ERR_MARKER${t.stackTraceToString()}"
        }
    }

    override fun destroy() {
        // Shizuku will unbind/kill the hosting process; nothing to clean up.
    }
}

/** Parses the packed string produced by [ShellUserService.runCommand]. */
fun parseUserServiceResult(raw: String): ShellResult {
    val outIdx = raw.indexOf(OUT_MARKER)
    val errIdx = raw.indexOf(ERR_MARKER, outIdx + OUT_MARKER.length)
    if (outIdx < 0 || errIdx < 0) return ShellResult(-1, emptyList(), listOf("malformed response: $raw"))
    val code = raw.substring(0, outIdx).toIntOrNull() ?: -1
    val out = raw.substring(outIdx + OUT_MARKER.length, errIdx)
    val err = raw.substring(errIdx + ERR_MARKER.length)
    return ShellResult(
        exitCode = code,
        stdout = out.lines().filter { it.isNotEmpty() },
        stderr = err.lines().filter { it.isNotEmpty() }
    )
}

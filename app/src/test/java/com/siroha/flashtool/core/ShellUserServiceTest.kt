package com.siroha.flashtool.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellUserServiceTest {

    // Built from the real OUT_MARKER/ERR_MARKER constants (now `internal`)
    // instead of hand-typed literal markers — that mismatch is exactly what
    // let the earlier overlap bug slip past this test.
    private fun packed(code: Int, out: String, err: String) =
        "$code$OUT_MARKER$out$ERR_MARKER$err"

    @Test
    fun `parses successful command output`() {
        val raw = packed(0, "hello\nworld\n", "")
        val result = parseUserServiceResult(raw)
        assertEquals(0, result.exitCode)
        assertEquals(listOf("hello", "world"), result.stdout)
        assertEquals(emptyList<String>(), result.stderr)
    }

    @Test
    fun `parses failing command with empty stdout and non-empty stderr`() {
        // Regression test: stdout empty is exactly the case that used to
        // crash with StringIndexOutOfBoundsException (begin 19, end 18).
        val raw = packed(1, "", "permission denied\n")
        val result = parseUserServiceResult(raw)
        assertEquals(1, result.exitCode)
        assertEquals(false, result.isSuccess)
        assertEquals(listOf("permission denied"), result.stderr)
        assertEquals(emptyList<String>(), result.stdout)
    }

    @Test
    fun `parses exception path with empty stdout`() {
        // Mirrors ShellUserService.runCommand's catch block: code -1, empty
        // stdout, stacktrace text as stderr.
        val raw = packed(-1, "", "java.io.IOException: boom")
        val result = parseUserServiceResult(raw)
        assertEquals(-1, result.exitCode)
        assertEquals(listOf("java.io.IOException: boom"), result.stderr)
    }

    @Test
    fun `malformed response is reported as failure, not a crash`() {
        val result = parseUserServiceResult("not a valid response")
        assertEquals(-1, result.exitCode)
    }
}

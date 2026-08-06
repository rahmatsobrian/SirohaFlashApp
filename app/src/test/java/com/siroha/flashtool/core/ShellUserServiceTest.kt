package com.siroha.flashtool.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellUserServiceTest {

    @Test
    fun `parses successful command output`() {
        val raw = "0\n<<<SIROHA_OUT>>>\nhello\nworld\n<<<SIROHA_ERR>>>\n"
        val result = parseUserServiceResult(raw)
        assertEquals(0, result.exitCode)
        assertEquals(listOf("hello", "world"), result.stdout)
        assertEquals(emptyList<String>(), result.stderr)
    }

    @Test
    fun `parses failing command with stderr`() {
        val raw = "1\n<<<SIROHA_OUT>>>\n<<<SIROHA_ERR>>>\npermission denied\n"
        val result = parseUserServiceResult(raw)
        assertEquals(1, result.exitCode)
        assertEquals(false, result.isSuccess)
        assertEquals(listOf("permission denied"), result.stderr)
    }

    @Test
    fun `malformed response is reported as failure, not a crash`() {
        val result = parseUserServiceResult("not a valid response")
        assertEquals(-1, result.exitCode)
    }
}

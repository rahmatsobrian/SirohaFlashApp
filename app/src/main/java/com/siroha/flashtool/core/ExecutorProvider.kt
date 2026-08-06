package com.siroha.flashtool.core

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point the UI talks to. On first use it checks, in order:
 *   1. Root (su) — matches the original flash.sh requirement.
 *   2. Shizuku — lets non-root users run the same flashing operations.
 * Whichever answers first "wins" for the rest of the session; the user can
 * also force a preference from Settings.
 */
class ExecutorProvider(context: Context) {
    private val appContext = context.applicationContext

    val root: ShellExecutor by lazy { RootShellExecutor() }
    val shizuku: ShellExecutor by lazy { ShizukuShellExecutor(appContext) }

    private var active: ShellExecutor? = null

    suspend fun detect(): ShellExecutor {
        active?.let { return it }

        val hasRoot = withContext(Dispatchers.IO) { Shell.isAppGrantedRoot() == true }
        if (hasRoot && root.requestAccess()) {
            active = root
            return root
        }
        if (shizuku.isReady() || shizuku.requestAccess()) {
            active = shizuku
            return shizuku
        }
        return object : ShellExecutor {
            override val mode = ExecutionMode.UNAVAILABLE
            override suspend fun isReady() = false
            override suspend fun requestAccess() = false
            override suspend fun exec(command: String) =
                ShellResult(-1, emptyList(), listOf("No root and no Shizuku access available."))
            override fun execStreaming(command: String) =
                kotlinx.coroutines.flow.flow { emit("[error] No root and no Shizuku access available.") }
        }
    }

    fun setPreferred(executor: ShellExecutor) {
        active = executor
    }

    fun current(): ShellExecutor? = active
}

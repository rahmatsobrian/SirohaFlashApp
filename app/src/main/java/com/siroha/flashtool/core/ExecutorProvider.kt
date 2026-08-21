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
        // Only ever short-circuit on a REAL backend (root/Shizuku) that was
        // already picked. Never on the UNAVAILABLE fallback below — if this
        // used to cache that too (as it did before), the very first call
        // made before the user granted root/Shizuku (e.g. an early EDL/QDL
        // screen visit) would permanently stick every later call to
        // UNAVAILABLE for the rest of the app process, even after Live
        // Status started reporting root/Shizuku as ready. Re-checking here
        // instead lets it self-heal the moment either backend becomes
        // available, without requiring a trip to Settings.
        active?.let { if (it.mode != ExecutionMode.UNAVAILABLE) return it }

        val hasRoot = withContext(Dispatchers.IO) { Shell.isAppGrantedRoot() == true }
        if (hasRoot && root.requestAccess()) {
            active = root
            return root
        }
        if (shizuku.isReady() || shizuku.requestAccess()) {
            active = shizuku
            return shizuku
        }
        val unavailable = object : ShellExecutor {
            override val mode = ExecutionMode.UNAVAILABLE
            override suspend fun isReady() = false
            override suspend fun requestAccess() = false
            override suspend fun exec(command: String) =
                ShellResult(-1, emptyList(), listOf("No root and no Shizuku access available."))
            override fun execStreaming(command: String) =
                kotlinx.coroutines.flow.flow { emit("[error] No root and no Shizuku access available.") }
        }
        active = unavailable
        return unavailable
    }

    fun setPreferred(executor: ShellExecutor) {
        active = executor
    }

    fun current(): ShellExecutor? = active

    /**
     * Non-prompting status snapshot for live display (e.g. a "Working with
     * root/Shizuku" indicator on Home) — unlike [detect]/[requestAccess],
     * this never triggers a NEW su or Shizuku permission dialog, so it's
     * safe to poll on a timer.
     */
    data class PassiveStatus(val rootGranted: Boolean?, val shizukuReady: Boolean)

    private var rootStatusPrimed = false

    suspend fun passiveStatus(): PassiveStatus = withContext(Dispatchers.IO) {
        // libsu's Shell.isAppGrantedRoot() only returns an accurate value
        // once a Shell has been created at least once in this process — if
        // root was already granted at the Magisk/KernelSU/APatch level
        // (the common case for "I already granted this app root"),
        // creating that first Shell here is silent (no new prompt shown,
        // since the grant already exists). It only shows a real prompt on
        // a genuinely fresh install that has never requested root before —
        // done once per app process, not on every poll, so it can't spam.
        if (!rootStatusPrimed) {
            rootStatusPrimed = true
            runCatching { Shell.getShell() }
        }
        val rootGranted = Shell.isAppGrantedRoot()
        val shizukuReady = shizuku.isReady()

        // Live Status polls this every couple seconds and is very often the
        // FIRST place a freshly-granted root/Shizuku permission is actually
        // observed. Previously that observation stayed purely cosmetic —
        // `active` (what detect()/current() hand out, what Settings' "Active:
        // ..." label reflects, and what gates menus like EDL/QDL) was only
        // ever updated by explicitly tapping "Use Root"/"Use Shizuku" in
        // Settings, so the two could show contradictory states until the
        // user did that. Mirror it here instead, so the backend Live Status
        // says is working is the same one everything else actually uses —
        // without ever overriding a backend that's already active (root
        // stays root even if this poll also finds Shizuku ready).
        if (active == null || active?.mode == ExecutionMode.UNAVAILABLE) {
            if (rootGranted == true) active = root
            else if (shizukuReady) active = shizuku
        }

        PassiveStatus(rootGranted = rootGranted, shizukuReady = shizukuReady)
    }
}

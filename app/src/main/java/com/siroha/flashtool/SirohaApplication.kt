package com.siroha.flashtool

import android.app.Application
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.CrashLogger
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.ThemePreferences
import com.siroha.flashtool.data.LogLevel
import com.siroha.flashtool.data.LogRepository
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

class SirohaApplication : Application() {

    lateinit var executorProvider: ExecutorProvider
        private set

    lateinit var logRepository: LogRepository
        private set

    lateinit var themePreferences: ThemePreferences
        private set

    // App-wide singletons rather than one-per-screen instances: a fastboot/
    // ADB connection opened on one screen (e.g. from the Home status card,
    // or FRP) now stays open when navigating to another tool screen,
    // instead of every screen silently owning its own private, independent
    // USB connection that dies the moment you leave that screen.
    lateinit var fastbootOperations: FastbootOperations
        private set

    lateinit var adbOperations: AdbOperations
        private set

    override fun onCreate() {
        super.onCreate()

        // MUST be the very first thing that can touch libsu's Shell class,
        // process-wide, before literally anything else — including
        // CrashLogger/LogRepository init below, in case a future change to
        // either ever ends up touching Shell indirectly. libsu creates its
        // global MainShell lazily on the FIRST Shell.getShell() call using
        // whatever builder is the default *at that moment*, and rejects any
        // later setDefaultBuilder() call with
        // "IllegalStateException: The main shell was already created".
        // Previously this call lived in RootShellExecutor's init block,
        // which only runs lazily the first time `.root` is accessed (e.g.
        // tapping "Run checks"). But ExecutorProvider.passiveStatus() —
        // polled by Live Status from the moment Home screen opens — calls
        // Shell.getShell() directly to prime isAppGrantedRoot(), which
        // silently created the global shell with libsu's un-configured
        // defaults *before* RootShellExecutor ever got a chance to set
        // FLAG_REDIRECT_STDERR / the 600s timeout. The first later call to
        // `.root` then crashed the app trying to set the builder a second
        // time. Setting it once, here, before any other code path in the
        // app can reach Shell.getShell(), removes the race entirely.
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(600) // long timeout: flashing a full ROM can take minutes
        )

        // Installed before anything else runs, so a crash anywhere else in
        // startup is still captured. See CrashLogger's doc comment for why
        // this has no ongoing performance cost.
        CrashLogger.install(this)

        logRepository = LogRepository(this)
        executorProvider = ExecutorProvider(this)
        themePreferences = ThemePreferences(this)
        fastbootOperations = FastbootOperations(this, logRepository)
        adbOperations = AdbOperations(this, logRepository)

        // If the app crashed last run, surface that crash log in the Logs
        // screen right away instead of it sitting invisible in a file —
        // this is the "automatically grab the log that caused the crash"
        // behavior, without needing the person to go find a file manually.
        CrashLogger.latestCrashLog(this)?.let { crashText ->
            logRepository.log(LogLevel.ERROR, "Crash", "App crashed last run:\n$crashText")
            CrashLogger.clear(this)
        }

        // Pre-warm Shizuku's binder listener so ShizukuShellExecutor.isReady()
        // reflects reality as soon as a screen asks, instead of racing it.
        runCatching { Shizuku.pingBinder() }
    }
}

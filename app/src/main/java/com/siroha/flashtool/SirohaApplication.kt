package com.siroha.flashtool

import android.app.Application
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.CrashLogger
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.ThemePreferences
import com.siroha.flashtool.data.LogLevel
import com.siroha.flashtool.data.LogRepository

class SirohaApplication : Application() {

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

        // Installed before anything else runs, so a crash anywhere else in
        // startup is still captured. See CrashLogger's doc comment for why
        // this has no ongoing performance cost.
        CrashLogger.install(this)

        logRepository = LogRepository(this)
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
    }
}

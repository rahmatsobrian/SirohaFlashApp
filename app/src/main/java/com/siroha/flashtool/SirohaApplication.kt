package com.siroha.flashtool

import android.app.Application
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.ThemePreferences
import com.siroha.flashtool.data.LogRepository
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
        logRepository = LogRepository(this)
        executorProvider = ExecutorProvider(this)
        themePreferences = ThemePreferences(this)
        fastbootOperations = FastbootOperations(this, logRepository)
        adbOperations = AdbOperations(this, logRepository)

        // Pre-warm Shizuku's binder listener so ShizukuShellExecutor.isReady()
        // reflects reality as soon as a screen asks, instead of racing it.
        runCatching { Shizuku.pingBinder() }
    }
}

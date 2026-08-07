package com.siroha.flashtool

import android.app.Application
import com.siroha.flashtool.core.ExecutorProvider
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

    override fun onCreate() {
        super.onCreate()
        logRepository = LogRepository(this)
        executorProvider = ExecutorProvider(this)
        themePreferences = ThemePreferences(this)

        // Pre-warm Shizuku's binder listener so ShizukuShellExecutor.isReady()
        // reflects reality as soon as a screen asks, instead of racing it.
        runCatching { Shizuku.pingBinder() }
    }
}

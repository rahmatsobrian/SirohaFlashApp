package com.siroha.flashtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.siroha.flashtool.core.ThemeMode
import com.siroha.flashtool.ui.navigation.SirohaNavGraph
import com.siroha.flashtool.ui.theme.SirohaFlashToolTheme

class MainActivity : ComponentActivity() {

    val app: SirohaApplication by lazy { application as SirohaApplication }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by app.themePreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by app.themePreferences.dynamicColorEnabled.collectAsState(initial = true)

            SirohaFlashToolTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SirohaNavGraph(
                        executorProvider = app.executorProvider,
                        logRepository = app.logRepository,
                        themePreferences = app.themePreferences,
                        fastbootOperations = app.fastbootOperations,
                        adbOperations = app.adbOperations
                    )
                }
            }
        }
    }
}

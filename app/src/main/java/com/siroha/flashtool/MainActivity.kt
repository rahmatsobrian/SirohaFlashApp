package com.siroha.flashtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.siroha.flashtool.ui.navigation.SirohaNavGraph
import com.siroha.flashtool.ui.theme.SirohaFlashToolTheme

class MainActivity : ComponentActivity() {

    val app: SirohaApplication by lazy { application as SirohaApplication }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SirohaFlashToolTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SirohaNavGraph(
                        executorProvider = app.executorProvider,
                        logRepository = app.logRepository
                    )
                }
            }
        }
    }
}

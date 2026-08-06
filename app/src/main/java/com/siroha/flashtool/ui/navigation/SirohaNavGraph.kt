package com.siroha.flashtool.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.screens.BypassUblScreen
import com.siroha.flashtool.ui.screens.HomeScreen
import com.siroha.flashtool.ui.screens.LogsScreen
import com.siroha.flashtool.ui.screens.QdlFlashScreen
import com.siroha.flashtool.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val QDL_FLASH = "qdl_flash"
    const val BYPASS_UBL = "bypass_ubl"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
}

@Composable
fun SirohaNavGraph(
    executorProvider: ExecutorProvider,
    logRepository: LogRepository,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigate = { route -> navController.navigate(route) }
            )
        }
        composable(Routes.QDL_FLASH) {
            QdlFlashScreen(
                executorProvider = executorProvider,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.BYPASS_UBL) {
            BypassUblScreen(
                executorProvider = executorProvider,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                executorProvider = executorProvider,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

package com.siroha.flashtool.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.screens.AboutScreen
import com.siroha.flashtool.ui.screens.AbPartitionScreen
import com.siroha.flashtool.ui.screens.BypassUblScreen
import com.siroha.flashtool.ui.screens.FastbootScreen
import com.siroha.flashtool.ui.screens.FrpToolScreen
import com.siroha.flashtool.ui.screens.GsiToolScreen
import com.siroha.flashtool.ui.screens.GuideScreen
import com.siroha.flashtool.ui.screens.HomeScreen
import com.siroha.flashtool.ui.screens.LogsScreen
import com.siroha.flashtool.ui.screens.QdlFlashScreen
import com.siroha.flashtool.ui.screens.RequirementsScreen
import com.siroha.flashtool.ui.screens.SettingsScreen
import com.siroha.flashtool.ui.screens.UsbFixScreen

object Routes {
    const val HOME = "home"
    const val QDL_FLASH = "qdl_flash"
    const val FASTBOOT = "fastboot"
    const val GSI_TOOL = "gsi_tool"
    const val AB_PARTITION = "ab_partition"
    const val FRP_TOOL = "frp_tool"
    const val USB_FIX = "usb_fix"
    const val REQUIREMENTS = "requirements"
    const val GUIDE = "guide"
    const val BYPASS_UBL = "bypass_ubl"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

@Composable
fun SirohaNavGraph(
    executorProvider: ExecutorProvider,
    logRepository: LogRepository,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onNavigate = { route -> navController.navigate(route) })
        }
        composable(Routes.QDL_FLASH) {
            QdlFlashScreen(
                executorProvider = executorProvider,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.FASTBOOT) {
            FastbootScreen(logRepository = logRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.GSI_TOOL) {
            GsiToolScreen(logRepository = logRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.AB_PARTITION) {
            AbPartitionScreen(logRepository = logRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.FRP_TOOL) {
            FrpToolScreen(logRepository = logRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.USB_FIX) {
            UsbFixScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.REQUIREMENTS) {
            RequirementsScreen(executorProvider = executorProvider, onBack = { navController.popBackStack() })
        }
        composable(Routes.GUIDE) {
            GuideScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.BYPASS_UBL) {
            BypassUblScreen(
                executorProvider = executorProvider,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(logRepository = logRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(executorProvider = executorProvider, onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

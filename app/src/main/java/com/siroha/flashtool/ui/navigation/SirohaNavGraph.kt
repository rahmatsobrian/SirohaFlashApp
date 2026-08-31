package com.siroha.flashtool.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.ThemePreferences
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SirohaBottomBar
// Pastikan semua Screen ter-import di sini
import com.siroha.flashtool.ui.screens.AboutScreen
import com.siroha.flashtool.ui.screens.AbPartitionScreen
import com.siroha.flashtool.ui.screens.AdbScreen
import com.siroha.flashtool.ui.screens.BypassUblScreen
import com.siroha.flashtool.ui.screens.FastbootScreen
import com.siroha.flashtool.ui.screens.FrpToolScreen
import com.siroha.flashtool.ui.screens.GsiToolScreen
import com.siroha.flashtool.ui.screens.GuideEdlScreen
import com.siroha.flashtool.ui.screens.GuideFastbootScreen
import com.siroha.flashtool.ui.screens.GuideMiToolScreen
import com.siroha.flashtool.ui.screens.GuideTabScreen
import com.siroha.flashtool.ui.screens.HomeScreen
import com.siroha.flashtool.ui.screens.LogsScreen
import com.siroha.flashtool.ui.screens.MiToolScreen
import com.siroha.flashtool.ui.screens.MiUnlockScreen
import com.siroha.flashtool.ui.screens.QdlFlashScreen
import com.siroha.flashtool.ui.screens.RequirementsScreen
import com.siroha.flashtool.ui.screens.SettingsScreen
import com.siroha.flashtool.ui.screens.ToolsScreen
import com.siroha.flashtool.ui.screens.UsbFixScreen
import com.siroha.flashtool.ui.screens.UtilitiesScreen

object Routes {
    const val MAIN_TABS = "main_tabs" // Route baru yang membungkus semua halaman bermenu
    const val HOME = "home"
    const val TOOLS = "tools"
    const val GUIDE = "guide"
    const val UTILITIES = "utilities"
    const val QDL_FLASH = "qdl_flash"
    const val FASTBOOT = "fastboot"
    const val GSI_TOOL = "gsi_tool"
    const val AB_PARTITION = "ab_partition"
    const val FRP_TOOL = "frp_tool"
    const val ADB = "adb"
    const val USB_FIX = "usb_fix"
    const val REQUIREMENTS = "requirements"
    const val GUIDE_EDL = "guide_edl"
    const val GUIDE_FASTBOOT = "guide_fastboot"
    const val GUIDE_MITOOL = "guide_mitool"
    const val BYPASS_UBL = "bypass_ubl"
    const val MITOOL = "mitool"
    const val MIUNLOCK = "miunlock"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

private fun NavHostController.navigateSafely(route: String) {
    val isResumed = currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED
    if (isResumed) navigate(route)
}

@Composable
fun SirohaNavGraph(
    logRepository: LogRepository,
    themePreferences: ThemePreferences,
    fastbootOperations: FastbootOperations,
    adbOperations: AdbOperations,
    navController: NavHostController = rememberNavController() // navController utama (fullscreen)
) {
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN_TABS
    ) {
        // =======================================================
        // GRUP 1: HALAMAN YANG NYATU DENGAN NAVBAR (HOME, TOOLS)
        // =======================================================
        composable(Routes.MAIN_TABS) {
            // tabNavController khusus menangani pindah menu antar bottom bar saja
            val tabNavController = rememberNavController()
            val backStackEntry by tabNavController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME

            // Back press from any tab other than Home goes to Home first
            // (standard multi-tab behavior) instead of exiting immediately;
            // back press from Home itself asks for confirmation rather than
            // closing the app on a single accidental press.
            var showExitDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.activity.compose.BackHandler(enabled = true) {
                if (currentRoute != Routes.HOME) {
                    tabNavController.navigate(Routes.HOME) {
                        popUpTo(tabNavController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                } else {
                    showExitDialog = true
                }
            }
            if (showExitDialog) {
                val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { androidx.compose.material3.Text("Exit Siroha Flash Tool?") },
                    text = { androidx.compose.material3.Text("Any in-progress flash will be stopped.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { activity?.finish() }) {
                            androidx.compose.material3.Text("Exit")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showExitDialog = false }) {
                            androidx.compose.material3.Text("Cancel")
                        }
                    }
                )
            }

            Scaffold(
                bottomBar = {
                    SirohaBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            if (route != currentRoute) {
                                tabNavController.navigate(route) {
                                    popUpTo(tabNavController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                NavHost(
                    navController = tabNavController,
                    startDestination = Routes.HOME,
                    modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            fastbootOperations = fastbootOperations,
                            adbOperations = adbOperations
                        )
                    }
                    composable(Routes.TOOLS) {
                        // Gunakan navController utama agar saat dipencet pindah ke layar full
                        ToolsScreen(onNavigate = { route -> navController.navigateSafely(route) })
                    }
                    composable(Routes.GUIDE) {
                        GuideTabScreen(onNavigate = { route -> navController.navigateSafely(route) })
                    }
                    composable(Routes.UTILITIES) {
                        UtilitiesScreen(onNavigate = { route -> navController.navigateSafely(route) })
                    }
                }
            }
        }

        // =======================================================
        // GRUP 2: HALAMAN FULL SCREEN / DETAIL (TANPA NAVBAR)
        // =======================================================
        composable(Routes.QDL_FLASH) {
            QdlFlashScreen(
                fastbootOperations = fastbootOperations,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.FASTBOOT) {
            FastbootScreen(
                fastbootOperations = fastbootOperations,
                logRepository = logRepository,
                onOpenAdb = { navController.navigate(Routes.ADB) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.GSI_TOOL) {
            GsiToolScreen(
                fastbootOperations = fastbootOperations,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.AB_PARTITION) {
            AbPartitionScreen(
                fastbootOperations = fastbootOperations,
                logRepository = logRepository,
                onOpenAdb = { navController.navigate(Routes.ADB) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.FRP_TOOL) {
            FrpToolScreen(
                fastbootOperations = fastbootOperations,
                adbOperations = adbOperations,
                logRepository = logRepository,
                onOpenAdb = { navController.navigate(Routes.ADB) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ADB) {
            AdbScreen(
                adbOperations = adbOperations,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.USB_FIX) {
            UsbFixScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.REQUIREMENTS) {
            RequirementsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.GUIDE_EDL) {
            GuideEdlScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.GUIDE_FASTBOOT) {
            GuideFastbootScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.GUIDE_MITOOL) {
            GuideMiToolScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.BYPASS_UBL) {
            BypassUblScreen(
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.MITOOL) {
            MiToolScreen(
                fastbootOperations = fastbootOperations,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.MIUNLOCK) {
            MiUnlockScreen(
                fastbootOperations = fastbootOperations,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(logRepository = logRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                themePreferences = themePreferences,
                logRepository = logRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

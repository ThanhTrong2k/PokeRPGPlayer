package com.pokerpgplayer.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pokerpgplayer.app.PokeRPGPlayerApp
import com.pokerpgplayer.app.ui.screens.AboutScreen
import com.pokerpgplayer.app.ui.screens.GameDetailScreen
import com.pokerpgplayer.app.ui.screens.HomeScreen
import com.pokerpgplayer.app.ui.screens.LogScreen
import com.pokerpgplayer.app.ui.screens.SettingsScreen
import com.pokerpgplayer.app.viewmodel.GameDetailViewModel
import com.pokerpgplayer.app.viewmodel.HomeViewModel
import com.pokerpgplayer.app.viewmodel.SettingsViewModel

/**
 * The entire app's navigation graph. Single Activity (MainActivity) hosts
 * this once; every screen is a destination here rather than its own
 * Activity — this is what "Single Activity architecture" + "Navigation
 * ready" means.
 *
 * SettingsViewModel is hoisted here (not created inside SettingsScreen) so
 * the selected theme can be read by MainActivity to drive
 * PokeRPGPlayerTheme — Settings isn't an island, the rest of the app needs
 * to react to it.
 *
 * Sprint 2: Home and GameDetail now need real dependencies
 * (repository/detection service/SAF manager from [PokeRPGPlayerApp.container]),
 * so their ViewModels are created here via [viewModelFactory] rather than
 * each screen defaulting to a no-arg `viewModel()` — this is the one place
 * in the app that reads [PokeRPGPlayerApp.container], keeping every screen
 * and ViewModel below it free of any direct Application/Context dependency.
 */
@Composable
fun AppNavHost(
    settingsViewModel: SettingsViewModel,
    navController: NavHostController = rememberNavController()
) {
    val app = LocalContext.current.applicationContext as PokeRPGPlayerApp

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        HomeViewModel(
                            repository = app.container.gameLibraryRepository,
                            detectionService = app.container.gameDetectionService,
                            safAccessManager = app.container.safAccessManager
                        )
                    }
                }
            )
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) },
                onNavigateToLog = { navController.navigate(Screen.Log.route) },
                onGameClick = { gameId -> navController.navigate(Screen.GameDetail.createRoute(gameId)) }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = settingsViewModel
            )
        }
        composable(Screen.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Log.route) {
            LogScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.GameDetail.route,
            arguments = listOf(navArgument(Screen.GameDetail.ARG_GAME_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Screen.GameDetail.ARG_GAME_ID)
            if (gameId != null) {
                val detailViewModel: GameDetailViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            GameDetailViewModel(
                                repository = app.container.gameLibraryRepository,
                                runtimeManager = app.container.runtimeManager,
                                runtimeWorkspaceService = app.container.runtimeWorkspaceService,
                                gameId = gameId
                            )
                        }
                    }
                )
                GameDetailScreen(
                    viewModel = detailViewModel,
                    onBack = { navController.popBackStack() },
                    onRemoved = { navController.popBackStack() }
                )
            }
        }
    }
}

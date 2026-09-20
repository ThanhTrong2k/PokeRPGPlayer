package com.pokerpgplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pokerpgplayer.app.navigation.AppNavHost
import com.pokerpgplayer.app.ui.theme.PokeRPGPlayerTheme
import com.pokerpgplayer.app.viewmodel.SettingsViewModel

/**
 * The one and only Activity in this app (Single Activity architecture).
 * Every screen is a Compose destination inside [AppNavHost] — see that
 * file's doc comment.
 *
 * Splash screen: handled entirely by androidx.core.splashscreen +
 * res/values(-night)/themes.xml (Theme.PokeRPGPlayer.Splash). No separate
 * splash Activity or splash nav-graph destination — that would conflict
 * with "Single Activity architecture," and the AndroidX SplashScreen API
 * is the current correct way to do this regardless.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() and before setContent().
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val themeMode by settingsViewModel.themeMode.collectAsState()

            PokeRPGPlayerTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(settingsViewModel = settingsViewModel)
                }
            }
        }
    }
}

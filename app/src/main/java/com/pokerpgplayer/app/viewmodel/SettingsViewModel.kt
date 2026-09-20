package com.pokerpgplayer.app.viewmodel

import androidx.lifecycle.ViewModel
import com.pokerpgplayer.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Prototype v0.0.1: holds the theme preference for the current app session
 * only — no persistence layer yet (see [ThemeMode] doc comment for why).
 *
 * Demonstrates the MVVM shape the rest of the app should follow: a
 * StateFlow the Composable screen collects, and explicit intent functions
 * (no Composables mutating state directly) — same shape this ViewModel will
 * keep once real persistence is added in Sprint 2, so screens shouldn't
 * need to change when that happens.
 */
class SettingsViewModel : ViewModel() {

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }
}

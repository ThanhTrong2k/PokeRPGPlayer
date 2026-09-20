package com.pokerpgplayer.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pokerpgplayer.app.R
import com.pokerpgplayer.app.ui.components.PokeRPGTopBar
import com.pokerpgplayer.app.ui.theme.ThemeMode
import com.pokerpgplayer.app.viewmodel.SettingsViewModel

/**
 * Prototype v0.0.1 Settings screen. Only a working theme toggle is real;
 * everything else mentioned in [R.string.settings_placeholder_note] is
 * deliberately not built yet — Device Doctor, Recommended Profile, and
 * Developer Module settings all depend on runtime/scanner work that's out
 * of scope for this prototype.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel
) {
    val currentMode by viewModel.themeMode.collectAsState()

    Scaffold(
        topBar = { PokeRPGTopBar(title = stringResource(R.string.settings_title), onBack = onBack) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_appearance_section),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_theme_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            val options = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark)
            )

            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentMode == mode, onClick = null)
                        Text(text = label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_placeholder_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }
}

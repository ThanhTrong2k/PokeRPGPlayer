package com.pokerpgplayer.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pokerpgplayer.app.R
import com.pokerpgplayer.app.ui.components.PokeRPGEmptyState
import com.pokerpgplayer.app.ui.components.PokeRPGTopBar

/**
 * Empty by design (per scope: "Empty Log Screen"). This will surface
 * Error/Diagnostic Layer output once that layer exists — see Runtime
 * Architecture v1.1 §4.12. Nothing to wire up yet.
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { PokeRPGTopBar(title = stringResource(R.string.log_title), onBack = onBack) }
    ) { innerPadding ->
        PokeRPGEmptyState(
            title = stringResource(R.string.log_empty_title),
            description = stringResource(R.string.log_empty_desc),
            icon = Icons.Filled.Description,
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        )
    }
}

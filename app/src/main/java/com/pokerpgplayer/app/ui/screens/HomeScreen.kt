package com.pokerpgplayer.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pokerpgplayer.app.R
import com.pokerpgplayer.app.ui.components.GameCard
import com.pokerpgplayer.app.ui.components.PokeRPGEmptyState
import com.pokerpgplayer.app.viewmodel.HomeMessage
import com.pokerpgplayer.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/**
 * Sprint 2 Home / Game Library screen.
 *
 * Add Game launches the real SAF folder picker, persists the permission
 * grant (see [com.pokerpgplayer.app.data.saf.SafAccessManager] — unlike
 * Prototype v0.0.1, which deliberately did not), runs
 * [com.pokerpgplayer.app.data.detection.GameDetectionService], and adds the
 * result to the library. Everything from "what does the folder look like"
 * onward lives in the ViewModel/services — this screen only renders state
 * and forwards user intents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToLog: () -> Unit,
    onGameClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) viewModel.onFolderPicked(uri) else viewModel.onFolderPickCancelled()
    }

    val cancelledMessage = stringResource(R.string.add_game_picker_cancelled)
    val addedMessage = stringResource(R.string.add_game_added)

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        val text = when (message) {
            HomeMessage.FolderPickCancelled -> cancelledMessage
            HomeMessage.GameAdded -> addedMessage
            is HomeMessage.Error -> message.text
        }
        coroutineScope.launch { snackbarHostState.showSnackbar(text) }
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.home_title))
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_log)) },
                                leadingIcon = { Icon(Icons.Filled.VideogameAsset, contentDescription = null) },
                                onClick = { menuExpanded = false; onNavigateToLog() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_settings)) },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { menuExpanded = false; onNavigateToSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_about)) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = { menuExpanded = false; onNavigateToAbout() }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(if (uiState.isDetecting) stringResource(R.string.action_scanning) else stringResource(R.string.action_add_game)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { if (!uiState.isDetecting) folderPickerLauncher.launch(null) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (uiState.isDetecting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.add_game_scanning_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (uiState.games.isEmpty() && !uiState.isLoading && !uiState.isDetecting) {
                        PokeRPGEmptyState(
                            title = stringResource(R.string.home_empty_library_title),
                            description = stringResource(R.string.home_empty_library_desc),
                            icon = Icons.Filled.VideogameAsset,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (!uiState.isLoading) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(uiState.games, key = { it.id }) { game ->
                                GameCard(
                                    game = game,
                                    onClick = { onGameClick(game.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(game.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.invalidFolderPrompt?.let {
        InvalidFolderDialog(
            onCancel = viewModel::dismissInvalidFolderPrompt,
            onAddAnyway = viewModel::confirmAddInvalidFolder
        )
    }

    uiState.pendingExecutableChoice?.let { pendingGame ->
        ChooseExecutableDialog(
            candidates = pendingGame.detection.executableCandidates,
            onChoose = { name -> viewModel.chooseExecutable(pendingGame.id, name) },
            onDismiss = viewModel::dismissExecutableChoice
        )
    }
}

@Composable
private fun InvalidFolderDialog(onCancel: () -> Unit, onAddAnyway: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.invalid_folder_title)) },
        text = { Text(stringResource(R.string.invalid_folder_message)) },
        confirmButton = {
            TextButton(onClick = onAddAnyway) { Text(stringResource(R.string.invalid_folder_add_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ChooseExecutableDialog(
    candidates: List<String>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_executable_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.choose_executable_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                candidates.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(name) }
                            .padding(vertical = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.choose_executable_later)) }
        }
    )
}

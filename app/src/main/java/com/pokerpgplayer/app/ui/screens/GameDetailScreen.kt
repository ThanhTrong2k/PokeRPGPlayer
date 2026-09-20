package com.pokerpgplayer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pokerpgplayer.app.R
import com.pokerpgplayer.app.ui.components.DetectionStatusBadge
import com.pokerpgplayer.app.ui.components.PokeRPGTopBar
import com.pokerpgplayer.app.viewmodel.GameDetailViewModel
import com.pokerpgplayer.app.viewmodel.LaunchUiState

/**
 * Sprint 2 Game Detail screen. Shows the read-only scan snapshot
 * ([com.pokerpgplayer.app.data.model.GameDetectionResult] — Data) and lets
 * the user edit the Knowledge layered on top: display name, favorite
 * state, and selected executable. Remove-from-library lives here too,
 * with an explicit on-screen reminder that it never touches the original
 * files (per the Sprint 2 product rule).
 */
@Composable
fun GameDetailScreen(
    viewModel: GameDetailViewModel,
    onBack: () -> Unit,
    onRemoved: () -> Unit
) {
    val game by viewModel.game.collectAsState()
    val entry = game ?: return // removed elsewhere (rare) — nothing to show
    val launchState by viewModel.launchState.collectAsState()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { PokeRPGTopBar(title = entry.displayName, onBack = onBack) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DetectionStatusBadge(status = entry.detection.status)
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            imageVector = if (entry.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(
                                if (entry.isFavorite) R.string.action_unfavorite else R.string.action_favorite
                            )
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = entry.displayName, style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_rename))
                    }
                }
            }

            item {
                PlayButtonRow(launchState = launchState, onPlay = viewModel::play)
                val failed = launchState as? LaunchUiState.Failed
                if (failed != null) {
                    Text(
                        text = "${stringResource(R.string.game_detail_launch_failed_prefix)} ${failed.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                DetailSection(title = stringResource(R.string.game_detail_section_executable)) {
                    if (entry.detection.executableCandidates.size > 1) {
                        entry.detection.executableCandidates.forEach { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = entry.selectedExecutable == candidate,
                                    onCheckedChange = { viewModel.chooseExecutable(candidate) }
                                )
                                Text(text = candidate)
                            }
                        }
                    } else {
                        Text(
                            text = entry.selectedExecutable ?: stringResource(R.string.game_card_no_executable),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            item {
                DetailSection(title = stringResource(R.string.game_detail_section_scan)) {
                    DetailRow(stringResource(R.string.game_detail_detected_title), entry.detection.detectedTitle ?: "—")
                    DetailRow(stringResource(R.string.game_detail_library_dll), entry.detection.libraryDll ?: "—")
                    DetailRow(stringResource(R.string.game_detail_scripts_path), entry.detection.scriptsPath ?: "—")
                    DetailCheckRow(stringResource(R.string.game_detail_data_folder), entry.detection.dataFolderExists)
                    DetailCheckRow(stringResource(R.string.game_detail_graphics_folder), entry.detection.graphicsFolderExists)
                    DetailCheckRow(stringResource(R.string.game_detail_audio_folder), entry.detection.audioFolderExists)
                    DetailCheckRow(stringResource(R.string.game_detail_fonts_optional), entry.detection.fontsDetected)
                    DetailCheckRow(stringResource(R.string.game_detail_plugins_optional), entry.detection.pluginsDetected)
                    DetailCheckRow(stringResource(R.string.game_detail_pbs_optional), entry.detection.pbsDetected)
                }
            }

            if (entry.detection.missingItems.isNotEmpty()) {
                item {
                    DetailSection(title = stringResource(R.string.game_detail_section_missing)) {
                        entry.detection.missingItems.forEach {
                            Text(text = "• $it", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (entry.detection.warnings.isNotEmpty()) {
                item {
                    DetailSection(title = stringResource(R.string.game_detail_section_warnings)) {
                        entry.detection.warnings.forEach {
                            Text(text = "• $it", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                TextButton(onClick = { showRemoveConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.action_remove_from_library))
                }
                Text(
                    text = stringResource(R.string.remove_from_library_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = entry.displayName,
            onConfirm = { newName ->
                viewModel.rename(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.remove_confirm_title)) },
            text = { Text(stringResource(R.string.remove_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    viewModel.removeFromLibrary(onRemoved)
                }) { Text(stringResource(R.string.action_remove_from_library)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/**
 * Sprint 53 Play button, extended in Sprint 53.2 to show three distinct
 * states rather than one indefinite "Launching...": [LaunchUiState.Preparing]
 * (no READY workspace yet — a real first-time copy may be running),
 * [LaunchUiState.Launching] (a READY workspace already exists — expected
 * to be fast), and the ordinary idle Play affordance otherwise. Both
 * non-idle states disable the button so a duplicate tap can't start a
 * second launch attempt.
 */
@Composable
private fun PlayButtonRow(launchState: LaunchUiState, onPlay: () -> Unit) {
    val busy = launchState is LaunchUiState.Preparing || launchState is LaunchUiState.Launching
    Button(
        onClick = onPlay,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).padding(end = 8.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        }
        Text(
            text = when (launchState) {
                is LaunchUiState.Preparing -> stringResource(R.string.game_detail_preparing)
                is LaunchUiState.Launching -> stringResource(R.string.game_detail_launching)
                else -> stringResource(R.string.action_play)
            }
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.padding(top = 8.dp)) { content() }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value)
    }
}

@Composable
private fun DetailCheckRow(label: String, present: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = if (present) stringResource(R.string.detail_present) else stringResource(R.string.detail_absent))
    }
}

@Composable
private fun RenameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

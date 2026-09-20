package com.pokerpgplayer.app.viewmodel

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokerpgplayer.app.data.detection.GameDetectionService
import com.pokerpgplayer.app.data.model.DetectionStatus
import com.pokerpgplayer.app.data.model.GameDetectionResult
import com.pokerpgplayer.app.data.model.GameEntry
import com.pokerpgplayer.app.data.repository.GameLibraryRepository
import com.pokerpgplayer.app.data.saf.SafAccessManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A folder the user picked, whose detection came back looking like it
 * doesn't belong to an RPGXP/Pokémon RPGXP project at all (no Game.ini,
 * no Data folder). Held in [HomeUiState] until the user cancels or
 * explicitly chooses "Add Anyway" — the game is never silently added or
 * silently rejected.
 */
data class InvalidFolderPrompt(
    val folderUri: Uri,
    val detection: GameDetectionResult
)

sealed class HomeMessage {
    data object FolderPickCancelled : HomeMessage()
    data object GameAdded : HomeMessage()
    data class Error(val text: String) : HomeMessage()
}

data class HomeUiState(
    val games: List<GameEntry> = emptyList(),
    val isLoading: Boolean = true,
    /** True while a folder picked via Add Game is being scanned. See the App v0.0.2 ANR fix — detection can legitimately take a few seconds on a heavy folder now that it's correctly backgrounded instead of blocking the UI thread; this drives a visible "Scanning…" state instead of the FAB silently doing nothing. */
    val isDetecting: Boolean = false,
    val invalidFolderPrompt: InvalidFolderPrompt? = null,
    /** Non-null right after adding a game whose executable is ambiguous — drives the "Choose Executable" dialog. */
    val pendingExecutableChoice: GameEntry? = null,
    val message: HomeMessage? = null
)

/**
 * Owns the Home/Game-Library screen's add-game orchestration. Detection
 * and persistence logic live in [GameDetectionService] /
 * [GameLibraryRepository] — this class only sequences calls to them and
 * shapes the result into UI state, per the "clean boundaries" requirement
 * for Sprint 2.
 */
class HomeViewModel(
    private val repository: GameLibraryRepository,
    private val detectionService: GameDetectionService,
    private val safAccessManager: SafAccessManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.games.collect { games ->
                _uiState.update { it.copy(games = games, isLoading = false) }
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDetecting = true) }
            try {
                val permissionPersisted = safAccessManager.takePersistableAccess(uri)
                val detection = detectionService.detect(uri)

                // "Doesn't look like a valid RPGXP project folder" — the specific
                // Low Confidence sub-case the spec calls out for its own warning
                // dialog, distinct from "valid project but missing supporting files".
                val looksInvalid = !detection.gameIniExists && !detection.dataFolderExists

                if (looksInvalid) {
                    _uiState.update { it.copy(invalidFolderPrompt = InvalidFolderPrompt(uri, detection)) }
                } else {
                    addGameFromDetection(uri, detection, permissionPersisted)
                }
            } finally {
                _uiState.update { it.copy(isDetecting = false) }
            }
        }
    }

    fun onFolderPickCancelled() {
        _uiState.update { it.copy(message = HomeMessage.FolderPickCancelled) }
    }

    /** User chose "Add Anyway (Needs Review)" on the invalid-folder warning. */
    fun confirmAddInvalidFolder() {
        val prompt = _uiState.value.invalidFolderPrompt ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDetecting = true) }
            try {
                // Re-attempt permission persistence here rather than reusing an
                // earlier result — cheap, and the user has now explicitly
                // confirmed they want this folder added.
                val permissionPersisted = safAccessManager.takePersistableAccess(prompt.folderUri)
                addGameFromDetection(prompt.folderUri, prompt.detection, permissionPersisted)
                _uiState.update { it.copy(invalidFolderPrompt = null) }
            } finally {
                _uiState.update { it.copy(isDetecting = false) }
            }
        }
    }

    fun dismissInvalidFolderPrompt() {
        _uiState.update { it.copy(invalidFolderPrompt = null) }
    }

    fun dismissExecutableChoice() {
        _uiState.update { it.copy(pendingExecutableChoice = null) }
    }

    fun chooseExecutable(gameId: String, executableName: String) {
        viewModelScope.launch {
            val entry = repository.getGame(gameId) ?: return@launch
            val updated = GameDetectionService.applySelectedExecutable(entry, executableName)
            repository.updateGame(updated)
            _uiState.update { it.copy(pendingExecutableChoice = null) }
        }
    }

    fun toggleFavorite(gameId: String) {
        viewModelScope.launch {
            val entry = repository.getGame(gameId) ?: return@launch
            repository.updateGame(entry.copy(isFavorite = !entry.isFavorite))
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun addGameFromDetection(
        uri: Uri,
        detection: GameDetectionResult,
        permissionPersisted: Boolean
    ) {
        val finalDetection = if (!permissionPersisted) {
            detection.copy(
                warnings = detection.warnings +
                    "This folder's access permission may not survive an app restart on this device."
            )
        } else {
            detection
        }

        val entry = GameEntry(
            id = UUID.randomUUID().toString(),
            displayName = finalDetection.detectedTitle?.takeIf { it.isNotBlank() }
                ?: uri.fallbackDisplayName(),
            folderUri = uri.toString(),
            addedDate = nowIso(),
            isFavorite = false,
            selectedExecutable = finalDetection.selectedExecutable,
            detection = finalDetection
        )

        repository.addGame(entry)

        val needsExecutableChoice = finalDetection.status == DetectionStatus.NEEDS_REVIEW &&
            finalDetection.executableCandidates.size > 1 &&
            finalDetection.selectedExecutable == null

        _uiState.update {
            it.copy(
                message = HomeMessage.GameAdded,
                pendingExecutableChoice = if (needsExecutableChoice) entry else null
            )
        }
    }
}

private fun Uri.fallbackDisplayName(): String = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(this)
    docId.substringAfterLast('/').ifBlank { "Unnamed Game" }
}.getOrDefault("Unnamed Game")

private fun nowIso(): String = java.time.Instant.now().toString()

package com.pokerpgplayer.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokerpgplayer.app.data.detection.GameDetectionService
import com.pokerpgplayer.app.data.model.GameEntry
import com.pokerpgplayer.app.data.repository.GameLibraryRepository
import com.pokerpgplayer.app.runtime.LaunchRequestResult
import com.pokerpgplayer.app.runtime.RuntimeError
import com.pokerpgplayer.app.runtime.RuntimeLaunchResult
import com.pokerpgplayer.app.runtime.RuntimeManager
import com.pokerpgplayer.app.runtime.RuntimeWorkspaceService
import com.pokerpgplayer.app.runtime.WorkspaceState
import com.pokerpgplayer.app.runtime.mapToLaunchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sprint 53 (Play button) / Sprint 53.1 (never-stuck launch state) /
 * Sprint 53.2 (Preparing vs Launching distinction) launch UI state.
 *
 * [Preparing] and [Launching] are deliberately distinct — Sprint 53.2's
 * own real-device evidence is a UI that shows only "Launching..." for
 * multiple minutes with no way to tell "a real first-time copy is
 * running" from "something is stuck." [Preparing] is used whenever no
 * READY workspace exists yet (a real copy may be about to happen);
 * [Launching] is used once a READY workspace is already known to exist,
 * where the remaining work (config refresh + starting the Activity) is
 * expected to be fast.
 */
sealed class LaunchUiState {
    data object Idle : LaunchUiState()
    data object Preparing : LaunchUiState()
    data object Launching : LaunchUiState()
    data class Failed(val error: RuntimeError) : LaunchUiState()
}

/**
 * One game's detail screen. [gameId] is supplied by the nav graph
 * (see [com.pokerpgplayer.app.navigation.Screen.GameDetail]) — this
 * ViewModel is created fresh per game, not shared/singleton.
 */
class GameDetailViewModel(
    private val repository: GameLibraryRepository,
    private val runtimeManager: RuntimeManager,
    private val runtimeWorkspaceService: RuntimeWorkspaceService,
    private val gameId: String
) : ViewModel() {

    val game: StateFlow<GameEntry?> = repository.games
        .map { list -> list.firstOrNull { it.id == gameId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.getGame(gameId)
        )

    private val _launchState = MutableStateFlow<LaunchUiState>(LaunchUiState.Idle)
    val launchState: StateFlow<LaunchUiState> = _launchState.asStateFlow()

    fun rename(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val entry = repository.getGame(gameId) ?: return@launch
            repository.updateGame(entry.copy(displayName = trimmed, libraryMetadataUpdatedDate = nowIso()))
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val entry = repository.getGame(gameId) ?: return@launch
            repository.updateGame(entry.copy(isFavorite = !entry.isFavorite, libraryMetadataUpdatedDate = nowIso()))
        }
    }

    fun chooseExecutable(executableName: String) {
        viewModelScope.launch {
            val entry = repository.getGame(gameId) ?: return@launch
            repository.updateGame(GameDetectionService.applySelectedExecutable(entry, executableName))
        }
    }

    /**
     * Removes this entry from the library only. Never touches the
     * original game folder/files — see [GameLibraryRepository.removeGame].
     *
     * Sprint 53.2: also clears this game's managed runtime workspace
     * (best-effort) so removing a game doesn't leave its mirrored copy
     * behind as an orphaned storage leak — audited against the ticket's
     * own "Game Remove Semantics" requirement. Never blocks or fails the
     * removal itself if workspace cleanup has a problem; the library
     * removal (the thing the user actually asked for) always proceeds.
     */
    fun removeFromLibrary(onRemoved: () -> Unit) {
        viewModelScope.launch {
            repository.removeGame(gameId)
            runCatching { runtimeWorkspaceService.clearWorkspace(gameId) }
            onRemoved()
        }
    }

    /**
     * Sprint 53.1 safety net preserved and extended in Sprint 53.2:
     * [runtimeManager.launch] is expected to return a structured
     * [RuntimeLaunchResult] rather than throw, but any unexpected
     * exception is still caught here so [launchState] can never get
     * stuck in [LaunchUiState.Preparing]/[LaunchUiState.Launching]
     * forever. [CancellationException] always propagates — it must
     * never be reported as [LaunchUiState.Failed].
     *
     * Re-entrant taps are ignored while a launch is already in flight —
     * the Sprint 53.2 ticket's own "disable duplicate Play taps while
     * preparing" requirement, enforced here (below UI) rather than only
     * in Compose, since [RuntimeManager] can have other callers too.
     */
    fun play() {
        val currentState = _launchState.value
        if (currentState is LaunchUiState.Preparing || currentState is LaunchUiState.Launching) return

        val entry = repository.getGame(gameId)
        if (entry == null) {
            _launchState.value = LaunchUiState.Failed(RuntimeError.Unknown("Game no longer exists in the library."))
            return
        }

        when (val mapped = mapToLaunchRequest(entry)) {
            is LaunchRequestResult.Rejected -> {
                _launchState.value = LaunchUiState.Failed(mapped.error)
                return
            }
            is LaunchRequestResult.Ready -> {
                viewModelScope.launch {
                    // Sprint 53.2: decide Preparing vs Launching from
                    // whatever is already on record BEFORE calling
                    // launch() — a cheap, local metadata read, never a
                    // guess based on how long launch() has been running.
                    val alreadyReady = runCatching {
                        runtimeWorkspaceService.getWorkspaceMetadata(gameId)?.state == WorkspaceState.READY
                    }.getOrDefault(false)

                    _launchState.value = if (alreadyReady) LaunchUiState.Launching else LaunchUiState.Preparing

                    try {
                        when (val result = runtimeManager.launch(mapped.request)) {
                            is RuntimeLaunchResult.Launched -> {
                                _launchState.value = LaunchUiState.Idle
                            }
                            is RuntimeLaunchResult.Failed -> {
                                _launchState.value = LaunchUiState.Failed(result.error)
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (t: Throwable) {
                        // Final UI safety net, not the primary error path —
                        // RuntimeManager/RuntimeLaunchPreparer should
                        // themselves return a structured Failed result.
                        // Never expose a raw stack trace to the player.
                        _launchState.value = LaunchUiState.Failed(
                            RuntimeError.Unknown("Unexpected error while launching: ${t::class.simpleName}")
                        )
                    }
                }
            }
        }
    }

    fun dismissLaunchError() {
        if (_launchState.value is LaunchUiState.Failed) {
            _launchState.value = LaunchUiState.Idle
        }
    }
}

private fun nowIso(): String = java.time.Instant.now().toString()

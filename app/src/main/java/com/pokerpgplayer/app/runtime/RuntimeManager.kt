package com.pokerpgplayer.app.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * The contract between the rest of the app and the native runtime —
 * defined now, before any native runtime exists, so future callers never
 * have to change shape when [StubRuntimeManager] is eventually replaced by
 * a real mkxp-z-backed implementation. Matches the same pattern
 * [com.pokerpgplayer.app.data.repository.GameLibraryRepository] already
 * established: an interface first, one real implementation now, a second
 * implementation later with zero change to anything that calls it.
 *
 * No `stop()`/`pause()` yet — both are meaningless without a real running
 * game, and adding them now would be speculative API surface with no
 * possible implementation. Add them when there's something real to stop.
 */
interface RuntimeManager {
    /**
     * Observable rather than a one-shot suspend query — a real runtime's
     * status can change over time (e.g. a running game crashing) without a
     * new explicit call, so the UI needs to observe it, not poll it.
     */
    val status: StateFlow<RuntimeStatus>

    suspend fun launch(request: GameLaunchRequest): RuntimeLaunchResult
}

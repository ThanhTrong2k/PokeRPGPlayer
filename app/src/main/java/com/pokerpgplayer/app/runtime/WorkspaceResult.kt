package com.pokerpgplayer.app.runtime

/**
 * Outcome of [RuntimeWorkspaceService.prepareWorkspace]. Sealed class
 * rather than a nullable return, matching this codebase's established
 * preference (see [RuntimeLaunchResult], [com.pokerpgplayer.app.runtime.LaunchRequestResult]).
 */
sealed class WorkspaceResult {
    data class Success(val metadata: WorkspaceMetadata) : WorkspaceResult()

    /**
     * [partialMetadata] is populated only if some prior successful mirror
     * exists for this game (from an earlier call) — this failed attempt's
     * own partial copy is always deleted (see [WorkspaceError] kdoc), so
     * this is never "how far the failed attempt got," only "what was
     * already on record before it started."
     */
    data class Failed(val error: WorkspaceError, val partialMetadata: WorkspaceMetadata? = null) : WorkspaceResult()
}

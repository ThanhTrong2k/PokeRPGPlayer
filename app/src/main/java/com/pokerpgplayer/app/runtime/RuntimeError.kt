package com.pokerpgplayer.app.runtime

/**
 * Why a launch attempt didn't succeed. Defined now, before any real
 * runtime exists, so the UI has a stable, structured vocabulary to
 * pattern-match on later — adding a new case here later is a much smaller
 * change than inventing this vocabulary retroactively once real native
 * failure modes exist.
 */
sealed class RuntimeError {
    /**
     * The only error every single launch attempt in Sprint 3 will ever
     * produce — there is no native runtime yet. Kept as its own case
     * (rather than folded into [Unknown]) so the UI can eventually show an
     * honest, specific message instead of a generic failure string.
     */
    data object RuntimeNotImplemented : RuntimeError()

    /**
     * The [com.pokerpgplayer.app.data.model.GameEntry] passed to
     * [GameLaunchRequestMapper] has no resolved
     * [com.pokerpgplayer.app.data.model.GameEntry.selectedExecutable] yet
     * (still ambiguous / Needs Review from Sprint 2's executable
     * detection). Produced by the mapper — never reaches [RuntimeManager].
     */
    data object ExecutableNotSelected : RuntimeError()

    /**
     * Reserved. Sprint 2's Known Issues already flag that SAF folder
     * access isn't re-validated on app restart — this case exists so that
     * whenever that gets fixed, the error vocabulary doesn't need a
     * breaking change. Nothing in Sprint 3 can detect this condition, so
     * nothing produces this value yet.
     */
    data object GameFolderAccessLost : RuntimeError()

    /**
     * Sprint 53/build-compatibility correction. [RuntimeLaunchPreparer]
     * maps every non-[WorkspaceError.SafAccessLost] [WorkspaceError] here
     * (invalid source folder, a specific file's copy failure, insufficient
     * storage, an unsupported document type) with a player-readable
     * [reason] — [GameFolderAccessLost] stays reserved specifically for
     * "the app's own permission grant to a folder that's otherwise fine
     * was revoked," which every other workspace-preparation failure is
     * not.
     */
    data class WorkspacePreparationFailed(val reason: String) : RuntimeError()

    /** The mirrored workspace has no `mkxp.json` at the path Runtime expected — see [expectedPath]. */
    data class ConfigMissing(val expectedPath: String) : RuntimeError()

    /**
     * The production config overlay could not be generated or committed
     * on top of an otherwise-valid, already-mirrored workspace — either
     * [com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService]
     * itself reported [com.pokerpgplayer.app.data.model.OverlayStatus.ERROR],
     * or [RuntimeLaunchPreparer.promoteGeneratedOverlay] failed to commit
     * the generated result into the workspace. [reason] carries whatever
     * detail is available; it may be null when the underlying failure
     * carried no message.
     */
    data class ConfigGenerationFailed(val reason: String? = null) : RuntimeError()

    /**
     * Sprint 53.1 robustness-preservation correction. Restored — an
     * earlier build-compatibility pass dropped this case along with the
     * structured `try`/`catch` around [AndroidRuntimeManager]'s own
     * `Intent`/`startActivity()` step that produces it. Distinct from
     * every case above: those all describe *preparation* failing before
     * a launch was ever attempted (workspace, config); this one means
     * preparation succeeded — [LaunchPreparationResult.Ready] was
     * returned — but the platform-level step of actually starting
     * [RuntimeActivity] then threw. [kotlinx.coroutines.CancellationException]
     * is never wrapped here — it always propagates unconverted, per this
     * whole codebase's structured-concurrency rule.
     */
    data class LaunchFailed(val reason: String) : RuntimeError()

    /**
     * Escape hatch for anything unanticipated — same defensive pattern
     * already used by [com.pokerpgplayer.app.data.detection.GameDetectionService]'s
     * error handling, kept consistent rather than inventing a new one.
     */
    data class Unknown(val message: String) : RuntimeError()
}

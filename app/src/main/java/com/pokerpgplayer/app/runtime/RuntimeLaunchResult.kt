package com.pokerpgplayer.app.runtime

/**
 * Outcome of a [RuntimeManager.launch] call. A sealed class rather than a
 * nullable `RuntimeError?` (null = success) — matches this codebase's
 * existing preference for explicit sealed results over an implicit
 * null-means-success convention (see
 * [com.pokerpgplayer.app.viewmodel.HomeMessage] for the same pattern).
 */
sealed class RuntimeLaunchResult {
    /**
     * Reserved. No code path in Sprint 3 (Runtime Foundation Preparation)
     * can produce this — there is no native runtime to actually launch
     * anything with yet.
     */
    data object Launched : RuntimeLaunchResult()

    data class Failed(val error: RuntimeError) : RuntimeLaunchResult()
}

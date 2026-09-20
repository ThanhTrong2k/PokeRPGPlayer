package com.pokerpgplayer.app.runtime

/**
 * Build-compatibility correction (post-53.2). The real, pre-existing
 * result type [RuntimeLaunchPreparer.prepareLaunch] returns — a top-level
 * sealed class, not nested inside [RuntimeLaunchPreparer]. An earlier
 * revision of [RuntimeLaunchPreparer] (written against a sandbox-audited
 * source tree that predated this type and never included it) introduced
 * its own nested `RuntimeLaunchPreparer.PreparationResult` with the same
 * two-case shape; that was a genuine gap in the audit materials, not an
 * intentional redesign, and the existing real test suite
 * (`AndroidRuntimeManagerTest`, `AndroidRuntimeManagerProductionLaunchTest`)
 * is the authoritative source of truth for this type's real name and
 * shape. Restored here rather than reinvented.
 */
sealed class LaunchPreparationResult {
    data class Ready(val workspacePath: String) : LaunchPreparationResult()
    data class Failed(val error: RuntimeError) : LaunchPreparationResult()
}

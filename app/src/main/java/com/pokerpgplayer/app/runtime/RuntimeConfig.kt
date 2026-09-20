package com.pokerpgplayer.app.runtime

/**
 * Placeholder. Every field beyond [schemaVersion] is deliberately absent —
 * safe mode, render backend choice, logging verbosity, device tier,
 * performance profile, etc. all depend on systems (Device Doctor,
 * Recommended Profile) that don't exist yet. Guessing at their shape now
 * would couple this type to another module's future design before that
 * design exists.
 *
 * `RuntimeConfig()` (the default constructor) is the config to use
 * anywhere one is needed in Sprint 3 — there is nothing to configure yet.
 * [schemaVersion] exists purely so a future Device Doctor / Recommended
 * Profile integration can extend this type without every existing caller
 * needing to change.
 *
 * Approved per Sprint 3 Runtime Foundation Preparation — Decision 1:
 * use this near-empty placeholder now; do not add guessed fields.
 */
data class RuntimeConfig(
    val schemaVersion: Int = 1
)

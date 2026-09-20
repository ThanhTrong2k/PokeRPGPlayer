package com.pokerpgplayer.app.runtime

import com.pokerpgplayer.app.data.model.RuntimeConfigProfile

/**
 * A fully-resolved request to launch one game. Produced only by
 * [GameLaunchRequestMapper.mapToLaunchRequest] from a
 * [com.pokerpgplayer.app.data.model.GameEntry] — never constructed
 * directly from UI/ViewModel code, so the mapper's rules (see that file)
 * stay the single place a "can this even be attempted" decision is made.
 *
 * Deliberately does NOT carry the full
 * [com.pokerpgplayer.app.data.model.GameDetectionResult] (missing items,
 * warnings, folder-existence booleans, confidence status). Game Library
 * already decided the game was launchable-enough when the user acted on
 * it; Runtime has no use for that diagnostic detail and shouldn't be
 * coupled to however GameDetectionResult happens to be shaped today.
 */
data class GameLaunchRequest(
    /** [com.pokerpgplayer.app.data.model.GameEntry.id] — correlation only. Runtime must never use this to look anything up in Game Library. */
    val gameId: String,
    /** [com.pokerpgplayer.app.data.model.GameEntry.folderUri], already resolved by Game Library. */
    val folderUri: String,
    /** [com.pokerpgplayer.app.data.model.GameEntry.selectedExecutable], already resolved — Runtime must NEVER re-run its own executable detection. */
    val executableName: String,
    /** [com.pokerpgplayer.app.data.model.GameDetectionResult.detectedTitle] — informational only (e.g. a future loading-screen title). */
    val detectedTitle: String?,
    val runtimeConfig: RuntimeConfig = RuntimeConfig(),
    /**
     * Sprint 53 — [com.pokerpgplayer.app.data.model.GameEntry.runtimeConfigProfile],
     * carried through so [RuntimeLaunchPreparer] has the per-game
     * mitigation profile it needs to generate a production
     * [OverlayGenerationTarget.PRODUCTION] overlay after a workspace is
     * READY. Defaults to an empty profile (no mitigations) so every
     * existing call site/test that constructs a [GameLaunchRequest]
     * without this parameter keeps compiling unchanged.
     */
    val configProfile: RuntimeConfigProfile = RuntimeConfigProfile()
)

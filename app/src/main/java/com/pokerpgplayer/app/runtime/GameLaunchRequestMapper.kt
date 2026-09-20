package com.pokerpgplayer.app.runtime

import com.pokerpgplayer.app.data.model.GameEntry

/**
 * Outcome of [mapToLaunchRequest]. A dedicated sealed type (rather than a
 * nullable [GameLaunchRequest]) so the rejection reason is explicit at the
 * call site instead of a bare null a caller has to guess about.
 */
sealed class LaunchRequestResult {
    data class Ready(val request: GameLaunchRequest) : LaunchRequestResult()
    data class Rejected(val error: RuntimeError) : LaunchRequestResult()
}

/**
 * Converts a Game Library [GameEntry] into a [GameLaunchRequest], or
 * explains why it can't be attempted yet.
 *
 * This function is pure — no I/O, no coroutines, no Android framework
 * types — and lives in the `runtime` package (not a new `game` package,
 * and not inside [GameEntry] itself) on purpose: Runtime is the side that
 * imports Game Library's model to build what *it* needs, so the directional
 * dependency stays one-way — Game Library's own package must never become
 * aware that a `runtime` package exists. See "Sprint 3: Runtime Foundation
 * Preparation — Architecture Proposal v1.0" §2 and §6.
 *
 * The only hard precondition enforced here is structural: is there a
 * resolved executable at all. [GameEntry.detection]'s confidence score is
 * deliberately **not** re-checked — Game Library already surfaced that as
 * a hint to the user; it is not a gate Runtime re-enforces. A Low
 * Confidence game with a resolved executable still maps to [Ready].
 */
fun mapToLaunchRequest(entry: GameEntry): LaunchRequestResult {
    val executable = entry.selectedExecutable
        ?: return LaunchRequestResult.Rejected(RuntimeError.ExecutableNotSelected)

    return LaunchRequestResult.Ready(
        GameLaunchRequest(
            gameId = entry.id,
            folderUri = entry.folderUri,
            executableName = executable,
            detectedTitle = entry.detection.detectedTitle,
            runtimeConfig = RuntimeConfig(),
            configProfile = entry.runtimeConfigProfile
        )
    )
}

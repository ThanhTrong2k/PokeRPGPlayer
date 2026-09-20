package com.pokerpgplayer.app.data.detection

/**
 * Pure executable-candidate selection rules — deliberately free of any
 * Android/DocumentFile dependency, unlike the rest of detection which
 * necessarily touches SAF. Kept as its own object so this specific piece
 * of logic (which file becomes the default) can be unit-tested on a plain
 * JVM later without Robolectric, and so it's not duplicated between the
 * initial scan and a later manual re-selection.
 *
 * Rules (per Sprint 2 spec):
 * - "Game.exe" (case-insensitive) always wins if present.
 * - Otherwise, if there's exactly one .exe candidate, it's the default.
 * - Otherwise (zero or multiple candidates with no Game.exe), there is no
 *   safe default — the caller must ask the user.
 */
object ExecutableDetector {

    fun selectDefault(candidates: List<String>): String? = when {
        candidates.any { it.equals("Game.exe", ignoreCase = true) } ->
            candidates.first { it.equals("Game.exe", ignoreCase = true) }
        candidates.size == 1 -> candidates.single()
        else -> null
    }
}

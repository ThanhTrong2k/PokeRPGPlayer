package com.pokerpgplayer.app.data.model

/**
 * A read-only snapshot of what [com.pokerpgplayer.app.data.detection.GameDetectionService]
 * observed in a game folder — Data, in the same Data-vs-Knowledge sense
 * PokeRPG Player OS uses for its Compatibility Database: this is what was
 * measured, not a judgment. [GameEntry.displayName], [GameEntry.isFavorite]
 * and [GameEntry.selectedExecutable] are the Knowledge layered on top by
 * the user, stored separately on [GameEntry] rather than folded into this
 * result — so a future re-scan would never have to guess which fields were
 * "real" scan data versus a user decision.
 *
 * [selectedExecutable] here is the *scan-time suggestion* only (Game.exe if
 * present, or the sole candidate, or null if ambiguous/absent) — it is not
 * updated when the user later picks a different executable from the Game
 * Detail screen. [GameEntry.selectedExecutable] is the live, authoritative
 * value; it starts as a copy of this field but is independently editable.
 *
 * [gameIniExists] and [gameSectionFound] are stored explicitly (rather than
 * inferred from [missingItems] strings) so [DetectionStatus] can be
 * recomputed later — e.g. after the user resolves an ambiguous executable —
 * without re-scanning the folder or parsing free-text messages.
 */
data class GameDetectionResult(
    val status: DetectionStatus,
    val gameIniExists: Boolean,
    val gameSectionFound: Boolean,
    val detectedTitle: String?,
    val libraryDll: String?,
    val scriptsPath: String?,
    val scriptsExists: Boolean,
    val dllExists: Boolean,
    val dataFolderExists: Boolean,
    val graphicsFolderExists: Boolean,
    val audioFolderExists: Boolean,
    val fontsDetected: Boolean,
    val pluginsDetected: Boolean,
    val pbsDetected: Boolean,
    val executableCandidates: List<String>,
    val selectedExecutable: String?,
    val missingItems: List<String>,
    val warnings: List<String>
)

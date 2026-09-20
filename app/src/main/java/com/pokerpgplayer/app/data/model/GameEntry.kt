package com.pokerpgplayer.app.data.model

/**
 * One entry in the user's local game library.
 *
 * [id] is a stable UUID generated when the game is added — never the
 * folder URI or path (Sprint 2 requirement). SAF URIs can be revoked or
 * reassigned by the system, and path strings aren't guaranteed stable
 * across storage providers, so neither is fit to be a primary key.
 * [folderUri] is stored purely as data on the entry, not as its identity.
 *
 * See [GameDetectionResult] for the Data-vs-Knowledge split this model
 * follows: [detection] is the immutable scan snapshot; [displayName],
 * [isFavorite], and [selectedExecutable] are user decisions layered on top.
 *
 * [runtimeConfigProfile] (Sprint 24, Stage 1 of the Runtime Config Safety
 * Layer ADR) follows the same split — it will eventually hold detected
 * compatibility signals and mitigation decisions the same way [detection]
 * holds scan data and [isFavorite]/[selectedExecutable] hold user
 * decisions. It defaults to an empty [RuntimeConfigProfile] and is
 * schema-only in Stage 1: no detection logic populates it, no overlay
 * config is ever generated from it, and no launch behavior reads it yet.
 * See [RuntimeConfigProfile]'s own kdoc for the explicit distinction from
 * the diagnostic `preloadScript` probes built in Sprint 20–23 — those
 * remain test-only and are not the same thing as this field.
 */
data class GameEntry(
    val id: String,
    val displayName: String,
    val folderUri: String,
    val addedDate: String,
    val isFavorite: Boolean = false,
    val selectedExecutable: String?,
    val detection: GameDetectionResult,
    val libraryMetadataUpdatedDate: String? = null,
    val runtimeConfigProfile: RuntimeConfigProfile = RuntimeConfigProfile()
)

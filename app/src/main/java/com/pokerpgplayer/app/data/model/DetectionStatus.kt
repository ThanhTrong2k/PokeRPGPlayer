package com.pokerpgplayer.app.data.model

/**
 * Confidence level assigned by [com.pokerpgplayer.app.data.detection.GameDetectionService]
 * after a read-only scan of a game folder.
 *
 * This is deliberately a Sprint 2 "basic detection" concept — a shallow
 * file/field presence check against Game.ini and root-level folders/exes.
 * It is NOT the deeper, version-aware analysis described in Game Scanner
 * Technical Specification v1.1 (Essentials version era, RGSS layout,
 * save storage strategy classification, PBS multi-file convention). See
 * the "Basic Game Detection is not Deep Compatibility Scanning" technical
 * note logged to PokeRPG Player OS alongside this sprint.
 */
enum class DetectionStatus {
    HIGH_CONFIDENCE,
    MEDIUM_CONFIDENCE,
    NEEDS_REVIEW,
    LOW_CONFIDENCE
}

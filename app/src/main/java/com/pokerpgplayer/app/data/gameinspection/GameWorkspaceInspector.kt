package com.pokerpgplayer.app.data.gameinspection

/**
 * Sprint 52 — top-level, read-only entry point. Combines
 * [PluginDetector] and [EssentialsVersionDetector] into one
 * [GameInspectionResult]. Never mutates anything on [source] — every
 * operation this whole package performs is a read.
 */
object GameWorkspaceInspector {

    fun inspect(source: GameFileSource, gameRoot: String, workspaceIdentifier: String): GameInspectionResult {
        val warnings = mutableListOf<String>()
        val evidence = mutableListOf<EvidenceEntry>()

        val (pluginSystemDetected, plugins) = PluginDetector.detect(source, warnings)

        val versionHint = EssentialsVersionDetector.detect(source, warnings)
        versionHint.evidence?.let { evidence.add(it) }

        // This foundation sprint deliberately does not attempt RGSS-
        // generation discrimination yet — see EssentialsVersionDetector's
        // own header comment and this sprint's own backlog notes for why
        // (would require decoding Data/Scripts.rxdata's own Marshal-
        // encoded rgssVersion constant, out of scope here).
        val detectedEngine = DetectedEngine.UNKNOWN

        return GameInspectionResult(
            gameRoot = gameRoot,
            workspaceIdentifier = workspaceIdentifier,
            detectedEngine = detectedEngine,
            essentialsVersionHint = versionHint.value,
            essentialsVersionConfidence = versionHint.confidence,
            pluginSystemDetected = pluginSystemDetected,
            plugins = plugins,
            metadataWarnings = warnings.toList(),
            sourceEvidence = evidence.toList()
        )
    }
}

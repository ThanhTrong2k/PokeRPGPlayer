package com.pokerpgplayer.app.data.gameinspection

/**
 * Sprint 52 — Plugin Reader Foundation + Game Metadata Detection.
 *
 * This is the domain model for the read-only game workspace
 * inspection layer. Nothing in this file touches the filesystem —
 * see [GameFileSource] for that boundary.
 */

/** How certain a piece of detected information actually is. */
enum class EvidenceConfidence { CONFIRMED, INFERRED, UNKNOWN }

/**
 * RGSS generation, when it can be determined. This foundation sprint
 * deliberately does not attempt to populate this beyond UNKNOWN yet —
 * doing so safely would require decoding Data/Scripts.rxdata's own
 * Marshal-encoded rgssVersion constant, and Marshal deserialization of
 * untrusted game data is a real, separate safety question this
 * foundation layer is not taking on yet. See the backlog section of
 * this sprint's own delivery notes.
 */
enum class DetectedEngine { RGSS1, RGSS2, RGSS3, UNKNOWN }

/**
 * How a piece of evidence was found, paired with the STRONGEST
 * confidence that method is ever allowed to produce. This mapping is
 * enforced by [ConfidencePolicy.clamp] at construction time — it is
 * structurally impossible to end up with, for example, a
 * FOLDER_NAME_ONLY detection reported as CONFIRMED, no matter what a
 * calling code path requests. This directly implements the ticket's
 * own explicit requirement: "KHONG assume folder name alone luon du
 * de xac nhan plugin."
 */
enum class DetectionMethod(val maxConfidence: EvidenceConfidence) {
    /** A dedicated metadata file (plugin.json/metadata.txt/etc.) was found and parsed. */
    EXPLICIT_METADATA_FILE(EvidenceConfidence.CONFIRMED),
    /** A `# Name:`/`# Version:`/`# Author:`-style comment header was found inside a .rb file. */
    COMMENT_HEADER_METADATA(EvidenceConfidence.CONFIRMED),
    /** Circumstantial structure (e.g. a directory existing) without any explicit metadata read. */
    STRUCTURAL_FINGERPRINT(EvidenceConfidence.INFERRED),
    /** Only the file/folder's own name was observed — no content was read or parsed at all. */
    FOLDER_NAME_ONLY(EvidenceConfidence.INFERRED),
    /** No evidence found at all. */
    NONE(EvidenceConfidence.UNKNOWN)
}

/**
 * The single, mandatory gate every confidence value must pass through.
 * [clamp] can only ever REDUCE a requested confidence to the calling
 * [DetectionMethod]'s own ceiling — it can never raise it. Verified by
 * a dedicated unit test asserting this holds even when a caller
 * mistakenly requests CONFIRMED for a FOLDER_NAME_ONLY detection.
 */
object ConfidencePolicy {
    private fun rank(c: EvidenceConfidence): Int = when (c) {
        EvidenceConfidence.UNKNOWN -> 0
        EvidenceConfidence.INFERRED -> 1
        EvidenceConfidence.CONFIRMED -> 2
    }

    fun clamp(method: DetectionMethod, requested: EvidenceConfidence): EvidenceConfidence {
        return if (rank(requested) > rank(method.maxConfidence)) method.maxConfidence else requested
    }
}

/** One piece of evidence backing a detection, kept minimal — a reference, not a content duplicate, per the ticket's own explicit "uu tien evidence/reference thay vi duplicate content." */
data class EvidenceEntry(
    val description: String,
    val sourcePath: String?,
    val method: DetectionMethod
)

data class DetectedPlugin(
    val id: String,
    val displayName: String?,
    val version: String?,
    val author: String?,
    val sourcePath: String,
    val detectionMethod: DetectionMethod,
    val confidence: EvidenceConfidence,
    /** Minimal parsed fields only — never the plugin's own full file content. */
    val rawMetadata: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * The only sanctioned way to construct a [DetectedPlugin] —
         * routes the requested confidence through [ConfidencePolicy.clamp]
         * so no call site can accidentally over-claim certainty.
         */
        fun create(
            id: String,
            displayName: String?,
            version: String?,
            author: String?,
            sourcePath: String,
            detectionMethod: DetectionMethod,
            requestedConfidence: EvidenceConfidence,
            rawMetadata: Map<String, String> = emptyMap()
        ): DetectedPlugin {
            return DetectedPlugin(
                id = id,
                displayName = displayName,
                version = version,
                author = author,
                sourcePath = sourcePath,
                detectionMethod = detectionMethod,
                confidence = ConfidencePolicy.clamp(detectionMethod, requestedConfidence),
                rawMetadata = rawMetadata
            )
        }
    }
}

/** Result of [EssentialsVersionDetector.detect] — kept separate from [GameInspectionResult] itself so the detector stays independently testable. */
data class VersionHint(
    /**
     * The version string itself — deliberately null whenever the
     * confidence is anything less than CONFIRMED. This foundation
     * layer never fabricates a specific version number from
     * circumstantial evidence alone, per the ticket's own explicit
     * "khong duoc tu fabricate version."
     */
    val value: String?,
    val confidence: EvidenceConfidence,
    val evidence: EvidenceEntry?
)

data class GameInspectionResult(
    val gameRoot: String,
    val workspaceIdentifier: String,
    val detectedEngine: DetectedEngine,
    val essentialsVersionHint: String?,
    val essentialsVersionConfidence: EvidenceConfidence,
    val pluginSystemDetected: Boolean,
    val plugins: List<DetectedPlugin>,
    val metadataWarnings: List<String>,
    val sourceEvidence: List<EvidenceEntry>
)

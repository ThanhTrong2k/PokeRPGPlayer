package com.pokerpgplayer.app.data.gameinspection

/**
 * Sprint 52 — Essentials version detection. Multi-tier, evidence-based
 * — never a single heuristic, never a fabricated version number.
 *
 * **Sprint 52 correction (Blocker 2)**: the original pattern was
 * `(?:Essentials|Version)\D{0,10}(\d+...)` — the `Version` alternative
 * meant a completely generic string like a fan game's own "Version
 * 1.4.2" (nothing to do with Essentials at all) would be wrongly
 * reported as the Essentials version, CONFIRMED. [ESSENTIALS_VERSION_PATTERN]
 * now requires the literal word "Essentials" to be present, never
 * matching on a bare "Version" alone. Separately, even when
 * "Essentials" + a number genuinely both appear, the surrounding text
 * might be a compatibility/requirement statement ("Requires Essentials
 * 21.1", "Compatible with Essentials 21.1") rather than a direct
 * statement of the game's own actual base — [looksLikeCompatibilityStatement]
 * checks a small context window around the match for exactly this kind
 * of qualifying language and downgrades to INFERRED (with no number
 * reported, preserving this module's own existing "INFERRED never
 * carries a fabricated value" invariant) when found.
 *
 * Deliberately does NOT decode Data/Scripts.rxdata to read the real
 * `Essentials::VERSION`/`rgssVersion` constants embedded inside it —
 * that would require Marshal-decoding a binary format from untrusted
 * game data, out of scope for this foundation sprint. See this
 * sprint's own backlog notes.
 */
object EssentialsVersionDetector {
    private const val VERSION_READ_LIMIT_BYTES = 4096
    private const val CONTEXT_WINDOW_BEFORE_CHARS = 40
    private const val CONTEXT_WINDOW_AFTER_CHARS = 10

    /** Plain-text files sometimes shipped alongside a game distribution that might state a version explicitly. Not assumed to exist; checked in order, first match wins. */
    private val CANDIDATE_VERSION_FILES = listOf("version.txt", "VERSION.txt", "Version.txt", "CHANGELOG.txt", "README.txt")

    /**
     * Requires the literal word "Essentials" (optionally preceded by
     * "Pokemon"/"Pokémon") directly associated with the number — this
     * is the Blocker 2 fix. A bare "Version 1.4.2" with no mention of
     * Essentials anywhere near it never matches this pattern at all.
     */
    private val ESSENTIALS_VERSION_PATTERN = Regex(
        """(?:Pok[ée]mon\s+)?Essentials\D{0,15}(\d+(?:\.\d+)+)""",
        RegexOption.IGNORE_CASE
    )

    /** Language suggesting the number describes a dependency/compatibility target rather than the game's own actual, confirmed base — downgrades to INFERRED, per this sprint's own explicit conservative-confidence requirement. */
    private val COMPATIBILITY_CONTEXT_KEYWORDS = listOf(
        "compatible", "compatibility", "requires", "requirement", "minimum", "target", "needs", "works with", "supports"
    )

    fun detect(source: GameFileSource, warnings: MutableList<String>): VersionHint {
        for (fileName in CANDIDATE_VERSION_FILES) {
            if (!source.exists(fileName)) continue
            val result = source.readText(fileName, VERSION_READ_LIMIT_BYTES)
            if (result is ReadResult.Success) {
                val match = ESSENTIALS_VERSION_PATTERN.find(result.text)
                if (match != null) {
                    val contextWindow = extractContextWindow(result.text, match.range)
                    val ambiguous = COMPATIBILITY_CONTEXT_KEYWORDS.any { contextWindow.contains(it, ignoreCase = true) }
                    return if (ambiguous) {
                        warnings.add("Found an Essentials version reference in '$fileName' (${match.groupValues[1]}) but nearby text suggests a compatibility/requirement statement, not a confirmed identity — reporting INFERRED, not CONFIRMED, and not populating a specific version number.")
                        VersionHint(
                            value = null,
                            confidence = EvidenceConfidence.INFERRED,
                            evidence = EvidenceEntry("Possible compatibility/requirement reference to Essentials ${match.groupValues[1]} in $fileName — ambiguous, not treated as the game's own confirmed base", fileName, DetectionMethod.STRUCTURAL_FINGERPRINT)
                        )
                    } else {
                        VersionHint(
                            value = match.groupValues[1],
                            confidence = EvidenceConfidence.CONFIRMED,
                            evidence = EvidenceEntry("Explicit Essentials version string found in $fileName", fileName, DetectionMethod.EXPLICIT_METADATA_FILE)
                        )
                    }
                }
            } else if (result is ReadResult.Error) {
                warnings.add("Found candidate version file '$fileName' but could not read it: ${result.reason}")
            }
        }

        val structuralHints = mutableListOf<String>()
        if (source.exists("Plugins")) {
            structuralHints.add("Plugins/ directory present (correlates with Essentials builds supporting a plugin system)")
        }
        if (source.exists("Data/Scripts.rxdata")) {
            structuralHints.add("Data/Scripts.rxdata present (its own embedded version constant is not decoded by this foundation layer)")
        }

        if (structuralHints.isNotEmpty()) {
            warnings.add("No explicit version file found — reporting structural evidence only, not a specific version number.")
            return VersionHint(
                value = null,
                confidence = EvidenceConfidence.INFERRED,
                evidence = EvidenceEntry(structuralHints.joinToString("; "), null, DetectionMethod.STRUCTURAL_FINGERPRINT)
            )
        }

        warnings.add("No version evidence found at all — reporting unknown rather than guessing.")
        return VersionHint(value = null, confidence = EvidenceConfidence.UNKNOWN, evidence = null)
    }

    private fun extractContextWindow(text: String, matchRange: IntRange): String {
        val start = maxOf(0, matchRange.first - CONTEXT_WINDOW_BEFORE_CHARS)
        val end = minOf(text.length, matchRange.last + CONTEXT_WINDOW_AFTER_CHARS)
        return text.substring(start, end)
    }
}

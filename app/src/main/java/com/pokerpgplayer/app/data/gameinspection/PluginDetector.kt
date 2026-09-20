package com.pokerpgplayer.app.data.gameinspection

/**
 * Sprint 52 — plugin detection. Never executes Ruby or any plugin
 * code — every method here only reads bounded text and pattern-
 * matches against it.
 *
 * **Sprint 52 correction (Blocker 1)**: detection now branches on
 * [GameFileSource.type], not on whether `list(entryPath)` happened to
 * be empty. This fixes a real false-positive: an unrelated file
 * sitting directly inside `Plugins/` (e.g. `Plugins/README.txt`,
 * `Plugins/random.dat`) previously got reported as a
 * FOLDER_NAME_ONLY plugin candidate, since an ordinary file and an
 * empty directory both produce an empty child list and were
 * indistinguishable under the old logic. Now: a genuine DIRECTORY
 * (even an empty one) is still a candidate, per this correction's own
 * explicit requirement; an unrelated FILE that isn't a `.rb` script is
 * explicitly excluded and never reported.
 */
object PluginDetector {
    private const val PLUGINS_DIR = "Plugins"
    private const val METADATA_READ_LIMIT_BYTES = 8192

    /**
     * Known candidate filenames for an explicit metadata file inside a
     * plugin's own subdirectory. Explicitly a candidate list, not
     * exhaustive or assumed universal.
     */
    private val KNOWN_METADATA_FILENAMES = listOf("plugin.json", "metadata.txt", "meta.txt", ".metadata")

    /**
     * Simple, bounded `# Key: value` comment-header patterns some
     * plugin .rb files use at the top of their own source to document
     * themselves. A plain regex match over already-bounded text — not
     * Ruby parsing or execution of any kind.
     */
    private val COMMENT_HEADER_PATTERNS = mapOf(
        "name" to Regex("""#\s*Name:\s*(.+)""", RegexOption.IGNORE_CASE),
        "version" to Regex("""#\s*Version:\s*(.+)""", RegexOption.IGNORE_CASE),
        "author" to Regex("""#\s*Author:\s*(.+)""", RegexOption.IGNORE_CASE)
    )

    /** Returns (pluginSystemDetected, plugins). warnings is appended to, never thrown. */
    fun detect(source: GameFileSource, warnings: MutableList<String>): Pair<Boolean, List<DetectedPlugin>> {
        val pluginsDirType = source.type(PLUGINS_DIR)
        if (pluginsDirType != GameEntryType.DIRECTORY) {
            // Missing, a plain file where a directory was expected, or unknown/inaccessible — none of these count as a plugin system being present.
            return false to emptyList()
        }

        val entries = source.list(PLUGINS_DIR)
        if (entries.isEmpty()) {
            return true to emptyList()
        }

        val plugins = entries.mapNotNull { entryName ->
            detectSingle(source, entryName, "$PLUGINS_DIR/$entryName", warnings)
        }
        return true to plugins
    }

    /** Returns null when entryPath should NOT be reported as a plugin candidate at all (Blocker 1's own explicit fix) — never a partial/fabricated entry. */
    private fun detectSingle(source: GameFileSource, entryName: String, entryPath: String, warnings: MutableList<String>): DetectedPlugin? {
        return when (source.type(entryPath)) {
            GameEntryType.DIRECTORY -> {
                val childEntries = source.list(entryPath)
                for (metaName in KNOWN_METADATA_FILENAMES) {
                    if (childEntries.contains(metaName)) {
                        return parseMetadataFile(source, entryName, "$entryPath/$metaName", warnings)
                    }
                }
                // A real directory — even an empty one — is still a legitimate plugin candidate, per this correction's own explicit requirement. FOLDER_NAME_ONLY/INFERRED, never higher.
                folderNameOnly(entryName, entryPath)
            }
            GameEntryType.FILE -> {
                if (entryName.endsWith(".rb", ignoreCase = true)) {
                    parseCommentHeader(source, entryName, entryPath, warnings)
                } else {
                    // An unrelated file (README.txt, random.dat, plugin.json sitting loose with no owning directory, etc.) directly inside Plugins/ is explicitly NOT a plugin candidate — the core Blocker 1 fix.
                    warnings.add("Ignored unrelated file directly inside Plugins/: '$entryName' (not a .rb script or a recognized plugin directory) — not reported as a plugin.")
                    null
                }
            }
            GameEntryType.MISSING, GameEntryType.UNKNOWN -> {
                warnings.add("Could not determine the type of Plugins/ entry '$entryName' — skipped, no claim made.")
                null
            }
        }
    }

    private fun folderNameOnly(entryName: String, entryPath: String): DetectedPlugin {
        return DetectedPlugin.create(
            id = entryName,
            displayName = entryName,
            version = null,
            author = null,
            sourcePath = entryPath,
            detectionMethod = DetectionMethod.FOLDER_NAME_ONLY,
            requestedConfidence = EvidenceConfidence.INFERRED
        )
    }

    private fun parseMetadataFile(source: GameFileSource, entryName: String, metaPath: String, warnings: MutableList<String>): DetectedPlugin {
        return when (val result = source.readText(metaPath, METADATA_READ_LIMIT_BYTES)) {
            is ReadResult.Error -> {
                warnings.add("Failed to read metadata for plugin candidate '$entryName': ${result.reason}")
                folderNameOnly(entryName, metaPath)
            }
            is ReadResult.Success -> {
                if (result.truncated) {
                    warnings.add("Metadata file for plugin candidate '$entryName' exceeds the ${METADATA_READ_LIMIT_BYTES}-byte read limit — parsed truncated content only.")
                }
                val parsed = parseSimpleKeyValueText(result.text)
                if (parsed.isEmpty()) {
                    warnings.add("Metadata file for plugin candidate '$entryName' found but no recognizable key:value fields parsed — treating as malformed.")
                    folderNameOnly(entryName, metaPath)
                } else {
                    DetectedPlugin.create(
                        id = parsed["id"] ?: entryName,
                        displayName = parsed["name"] ?: parsed["displayname"] ?: entryName,
                        version = parsed["version"],
                        author = parsed["author"],
                        sourcePath = metaPath,
                        detectionMethod = DetectionMethod.EXPLICIT_METADATA_FILE,
                        requestedConfidence = EvidenceConfidence.CONFIRMED,
                        rawMetadata = parsed
                    )
                }
            }
        }
    }

    private fun parseCommentHeader(source: GameFileSource, entryName: String, entryPath: String, warnings: MutableList<String>): DetectedPlugin {
        return when (val result = source.readText(entryPath, METADATA_READ_LIMIT_BYTES)) {
            is ReadResult.Error -> {
                warnings.add("Failed to read plugin script '$entryName': ${result.reason}")
                folderNameOnly(entryName, entryPath)
            }
            is ReadResult.Success -> {
                val found = mutableMapOf<String, String>()
                for ((key, pattern) in COMMENT_HEADER_PATTERNS) {
                    pattern.find(result.text)?.let { found[key] = it.groupValues[1].trim() }
                }
                if (found.isEmpty()) {
                    // A loose .rb file with no comment-header metadata is still a legitimate low-confidence candidate — .rb files directly in Plugins/ are a real, common Essentials convention, unlike an arbitrary unrelated file.
                    folderNameOnly(entryName, entryPath)
                } else {
                    DetectedPlugin.create(
                        id = entryName,
                        displayName = found["name"] ?: entryName,
                        version = found["version"],
                        author = found["author"],
                        sourcePath = entryPath,
                        detectionMethod = DetectionMethod.COMMENT_HEADER_METADATA,
                        requestedConfidence = EvidenceConfidence.CONFIRMED,
                        rawMetadata = found
                    )
                }
            }
        }
    }

    /**
     * Deliberately simple, bounded `key: value` / `key = value` line
     * parser — not a real JSON parser, even for plugin.json.
     */
    private fun parseSimpleKeyValueText(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (rawLine in text.lines()) {
            val trimmed = rawLine.trim().trimEnd(',')
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed == "{" || trimmed == "}") continue
            val separatorIndex = trimmed.indexOf(':').let { if (it == -1) trimmed.indexOf('=') else it }
            if (separatorIndex <= 0) continue
            val key = trimmed.substring(0, separatorIndex).trim().trim('"').lowercase()
            val value = trimmed.substring(separatorIndex + 1).trim().trim('"')
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }
}

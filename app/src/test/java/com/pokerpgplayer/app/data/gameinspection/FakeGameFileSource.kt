package com.pokerpgplayer.app.data.gameinspection

/**
 * Sprint 52 — in-memory [GameFileSource] for unit tests. No real
 * filesystem, deterministic, fast.
 *
 * **Sprint 52 correction (Blocker 1)**: now models [GameEntryType]
 * explicitly rather than inferring type from map-key presence alone.
 * [unknownPaths] is new — lets a test deterministically simulate a
 * path whose type genuinely cannot be determined (a permission error,
 * a broken symlink, etc.), distinct from MISSING (definitively not
 * there) — needed for the "inaccessible/unknown entry does not crash"
 * test this correction adds.
 *
 * **A real bug caught by actually running the Python-mirrored test
 * suite, not by code review alone**: [list] originally only scanned
 * [files], never [existingDirectories] — so an empty directory tracked
 * ONLY via [existingDirectories] (e.g. `Plugins/EmptyPluginFolder`
 * with zero files inside it) was invisible when listing its own
 * parent's own children, causing the "empty plugin subdirectory is
 * preserved as a candidate" test to fail. Fixed by having [list]
 * consider both sources.
 */
class FakeGameFileSource(
    private val files: Map<String, String> = emptyMap(),
    private val existingDirectories: Set<String> = emptySet(),
    private val unknownPaths: Set<String> = emptySet(),
    private val simulatedErrors: Set<String> = emptySet()
) : GameFileSource {

    override fun type(relativePath: String): GameEntryType {
        if (unknownPaths.contains(relativePath)) return GameEntryType.UNKNOWN
        if (files.containsKey(relativePath)) return GameEntryType.FILE
        if (existingDirectories.contains(relativePath)) return GameEntryType.DIRECTORY
        if (files.keys.any { it.startsWith("$relativePath/") }) return GameEntryType.DIRECTORY
        return GameEntryType.MISSING
    }

    override fun exists(relativePath: String): Boolean {
        val t = type(relativePath)
        return t == GameEntryType.FILE || t == GameEntryType.DIRECTORY
    }

    override fun list(relativePath: String): List<String> {
        val prefix = if (relativePath.isEmpty()) "" else "$relativePath/"
        val fromFiles = files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore("/") }
        val fromDirs = existingDirectories
            .filter { it.startsWith(prefix) && it != relativePath }
            .map { it.removePrefix(prefix).substringBefore("/") }
        return (fromFiles + fromDirs).distinct().sorted()
    }

    override fun readText(relativePath: String, maxBytes: Int): ReadResult {
        if (simulatedErrors.contains(relativePath)) {
            return ReadResult.Error("Simulated read error for $relativePath")
        }
        val content = files[relativePath] ?: return ReadResult.Error("Not found: $relativePath")
        val bytes = content.toByteArray(Charsets.UTF_8)
        val truncated = bytes.size > maxBytes
        val readBytes = if (truncated) bytes.copyOf(maxBytes) else bytes
        return ReadResult.Success(String(readBytes, Charsets.UTF_8), truncated)
    }
}

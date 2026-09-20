package com.pokerpgplayer.app.data.gameinspection

import java.io.File
import java.io.FileInputStream

/**
 * Sprint 52 — filesystem abstraction for read-only game workspace
 * inspection. No parser/detector in this package touches
 * java.io.File/Android storage APIs directly — everything goes
 * through this interface.
 *
 * **Sprint 52 correction (Blocker 1)**: added [GameEntryType]/[type]
 * — the original interface only had `exists()`/`list()`/`readText()`,
 * and [PluginDetector] inferred file-vs-directory purely from whether
 * `list(entryPath)` returned anything. That conflated an EMPTY
 * DIRECTORY with an ORDINARY NON-DIRECTORY FILE (both produce an
 * empty list), letting an unrelated file sitting directly inside
 * `Plugins/` get reported as a plugin candidate. [type] resolves this
 * cleanly and is now the single source of truth both real detectors
 * use to decide how to treat a given entry.
 */
interface GameFileSource {
    /** True if relativePath exists at all (file or directory) within the game root. */
    fun exists(relativePath: String): Boolean

    /**
     * What relativePath actually is — the fix for Blocker 1. FILE and
     * DIRECTORY are unambiguous when the path is genuinely reachable;
     * MISSING means it definitively doesn't exist; UNKNOWN means the
     * type couldn't be determined (a path-traversal rejection, a
     * permission error, or any other access failure) — callers must
     * treat UNKNOWN the same as "no defensible claim," never as
     * evidence of anything.
     */
    fun type(relativePath: String): GameEntryType

    /**
     * Immediate children only (not recursive) of the directory at
     * relativePath, sorted for deterministic output. Empty list if the
     * path doesn't exist, isn't a directory, or can't be read — never
     * throws.
     */
    fun list(relativePath: String): List<String>

    /**
     * Reads at most maxBytes of relativePath as UTF-8 text. Never
     * reads more than requested regardless of the real file's own
     * size — [ReadResult.Success.truncated] reports whether the real
     * file was larger than what was actually read. Never throws —
     * failures become [ReadResult.Error].
     */
    fun readText(relativePath: String, maxBytes: Int): ReadResult
}

/** Sprint 52 correction (Blocker 1) — the fix for the file/directory conflation bug. */
enum class GameEntryType { FILE, DIRECTORY, MISSING, UNKNOWN }

sealed class ReadResult {
    data class Success(val text: String, val truncated: Boolean) : ReadResult()
    data class Error(val reason: String) : ReadResult()
}

/**
 * Real, filesystem-backed [GameFileSource]. Every operation resolves
 * relativePath against the game root and verifies the resolved,
 * canonical path is still genuinely INSIDE that root before touching
 * anything — rejects any path (via `..` segments, symlinks, or
 * anything else canonicalization would reveal) that would otherwise
 * escape the game workspace. This is the standard, correct pattern
 * for this class of check in Java/Kotlin (canonicalize both sides,
 * then verify a real path-boundary match — not a naive string-prefix
 * check, which has a well-known false-positive bug for sibling paths
 * sharing a prefix, e.g. root "/foo/bar" incorrectly matching
 * "/foo/barbaz" — verified directly against this exact scenario as
 * part of this sprint's own original delivery).
 */
class LocalGameFileSource(gameRootPath: String) : GameFileSource {

    private val rootCanonical: File = File(gameRootPath).canonicalFile

    /** Returns the resolved File if relativePath stays within the game root, or null if it would escape it (rejected, not thrown). */
    private fun resolveSafe(relativePath: String): File? {
        return try {
            val candidate = File(rootCanonical, relativePath).canonicalFile
            val rootPath = rootCanonical.path
            val candidatePath = candidate.path
            val withinRoot = candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
            if (withinRoot) candidate else null
        } catch (t: Throwable) {
            null
        }
    }

    override fun type(relativePath: String): GameEntryType {
        val resolved = resolveSafe(relativePath) ?: return GameEntryType.UNKNOWN
        return try {
            when {
                !resolved.exists() -> GameEntryType.MISSING
                resolved.isDirectory -> GameEntryType.DIRECTORY
                resolved.isFile -> GameEntryType.FILE
                else -> GameEntryType.UNKNOWN
            }
        } catch (t: Throwable) {
            GameEntryType.UNKNOWN
        }
    }

    override fun exists(relativePath: String): Boolean {
        return type(relativePath).let { it == GameEntryType.FILE || it == GameEntryType.DIRECTORY }
    }

    override fun list(relativePath: String): List<String> {
        val dir = resolveSafe(relativePath) ?: return emptyList()
        return try {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    override fun readText(relativePath: String, maxBytes: Int): ReadResult {
        val file = resolveSafe(relativePath) ?: return ReadResult.Error("Path traversal rejected or invalid path: $relativePath")
        return try {
            if (!file.exists() || !file.isFile) return ReadResult.Error("Not found or not a regular file: $relativePath")
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(maxBytes)
                var totalRead = 0
                while (totalRead < maxBytes) {
                    val n = stream.read(buffer, totalRead, maxBytes - totalRead)
                    if (n == -1) break
                    totalRead += n
                }
                val actualBytes = if (totalRead == buffer.size) buffer else buffer.copyOf(totalRead)
                val truncated = file.length() > maxBytes
                ReadResult.Success(String(actualBytes, Charsets.UTF_8), truncated)
            }
        } catch (t: Throwable) {
            ReadResult.Error("Read failed: ${t::class.java.simpleName}: ${t.message}")
        }
    }
}

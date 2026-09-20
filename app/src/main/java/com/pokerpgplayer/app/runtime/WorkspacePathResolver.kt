package com.pokerpgplayer.app.runtime

import java.io.File
import java.util.regex.Pattern

/**
 * Pure path logic for the Runtime Workspace — no Android framework
 * types, no I/O beyond what [File.getCanonicalFile] itself needs (plain
 * POSIX path resolution, works identically on any JVM). Kept as its own
 * object, separate from [MirrorRuntimeWorkspaceService], specifically so
 * this can be unit-tested directly rather than only through the full
 * service.
 *
 * Every path this object hands out is scoped under a single root:
 * `<filesDir>/runtime-workspace/`. Nothing in [MirrorRuntimeWorkspaceService]
 * should ever construct a workspace or registry path any other way —
 * this is the one place that logic lives, per the approved Sprint 6 review's
 * requirement that `clearWorkspace` "must never accept or delete arbitrary
 * caller-provided paths."
 */
object WorkspacePathResolver {

    private const val ROOT_DIR_NAME = "runtime-workspace"
    private const val REGISTRY_FILE_NAME = "registry.json"

    // Sprint 53.2 — staging/promotion path scheme. Both prefixes/suffixes
    // start with "." so they never collide with a gameEntryId (a bare
    // UUID never starts with "."), and both are scoped directly under
    // rootDir(), one level below runtime-workspace/, alongside every
    // final <gameEntryId>/ workspace directory itself.
    private const val STAGING_PREFIX = ".staging-"
    private const val BACKUP_SUFFIX = ".pending-replace-backup"

    // A staging token is generated internally (typically
    // UUID.randomUUID().toString(), but never required to be exactly
    // that shape) — this pattern exists only to validate it before it's
    // ever used to build a path, same defensive posture as
    // isValidGameEntryId: alphanumeric and hyphen only, so it can never
    // smuggle in a path separator or a ".."-style traversal segment.
    private val STAGING_TOKEN_PATTERN: Pattern = Pattern.compile("^[0-9a-zA-Z-]+$")

    // GameEntry.id is always UUID.randomUUID().toString() (see GameEntry's
    // own kdoc) — standard 8-4-4-4-12 hex-and-hyphens form. Validating
    // against that exact shape before ever using it inside a path means a
    // malformed or malicious-looking id (e.g. containing "../") is rejected
    // structurally, not just by convention.
    private val UUID_PATTERN: Pattern = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    fun isValidGameEntryId(gameEntryId: String): Boolean =
        UUID_PATTERN.matcher(gameEntryId).matches()

    /** The root all Runtime Workspace content lives under: `<filesDir>/runtime-workspace/`. */
    fun rootDir(filesDir: File): File = File(filesDir, ROOT_DIR_NAME)

    /** The single JSON registry file: `<filesDir>/runtime-workspace/registry.json`. */
    fun registryFile(filesDir: File): File = File(rootDir(filesDir), REGISTRY_FILE_NAME)

    fun registryTempFile(filesDir: File): File = File(rootDir(filesDir), "$REGISTRY_FILE_NAME.tmp")

    /**
     * The workspace directory for one game: `<filesDir>/runtime-workspace/<gameEntryId>/`.
     * Returns null if [gameEntryId] isn't a valid UUID — callers must
     * treat that as a hard failure, never fall back to some other path.
     */
    fun workspaceDir(filesDir: File, gameEntryId: String): File? {
        if (!isValidGameEntryId(gameEntryId)) return null
        return File(rootDir(filesDir), gameEntryId)
    }

    /**
     * The safety check [MirrorRuntimeWorkspaceService.clearWorkspace] relies
     * on before deleting anything. Confirms, via canonical (symlink- and
     * "..'"-resolved) paths, that [candidate] is *exactly*
     * `<filesDir>/runtime-workspace/<gameEntryId>/` — not a parent of it,
     * not a sibling, not something reached by a ".." trick. This is the
     * one gate standing between "delete this game's workspace" and
     * "delete something else" — deliberately paranoid.
     */
    fun isSafeToDeleteAsWorkspace(candidate: File, filesDir: File, gameEntryId: String): Boolean {
        val expected = workspaceDir(filesDir, gameEntryId) ?: return false
        return try {
            candidate.canonicalFile == expected.canonicalFile
        } catch (e: java.io.IOException) {
            // Canonicalization can fail (e.g. a broken symlink) — treat
            // that as "not safe" rather than guessing.
            false
        }
    }

    /**
     * Sprint 53.2. A private staging directory for one in-progress
     * preparation attempt: `<filesDir>/runtime-workspace/.staging-<gameEntryId>-<token>/`.
     * Every attempt gets its own fresh [token] (the caller generates one,
     * typically `UUID.randomUUID().toString()`) so two concurrent or
     * successive attempts for the *same* gameId never share, and can
     * never collide on, a staging path — single-flight is enforced
     * separately (a per-gameId `Mutex`), this is just what keeps two
     * different points in time from ever aliasing the same directory.
     * Returns null if [gameEntryId] isn't a valid UUID or [token] isn't a
     * safe path segment.
     */
    fun stagingDir(filesDir: File, gameEntryId: String, token: String): File? {
        if (!isValidGameEntryId(gameEntryId)) return null
        if (token.isBlank() || !STAGING_TOKEN_PATTERN.matcher(token).matches()) return null
        return File(rootDir(filesDir), "$STAGING_PREFIX$gameEntryId-$token")
    }

    /**
     * Sprint 53.2. Where an existing final workspace is moved aside,
     * momentarily, while promotion swaps a freshly-staged replacement
     * into its place: `<filesDir>/runtime-workspace/<gameEntryId>.pending-replace-backup/`.
     * Exactly one such path per gameId (not one per attempt) — a
     * previous, unrelated leftover here is always safe to delete before
     * reuse, since nothing this codebase ever exposes as "the workspace"
     * or "a valid registry entry" can be reached through this path.
     */
    fun backupDirForPromotion(filesDir: File, gameEntryId: String): File? {
        if (!isValidGameEntryId(gameEntryId)) return null
        return File(rootDir(filesDir), "$gameEntryId$BACKUP_SUFFIX")
    }

    /** Whether [name] (a single path segment directly under `rootDir()`) is a staging directory name that belongs to [gameEntryId] specifically. */
    fun isStagingDirNameFor(name: String, gameEntryId: String): Boolean =
        name.startsWith("$STAGING_PREFIX$gameEntryId-")

    /**
     * The staging-cleanup counterpart to [isSafeToDeleteAsWorkspace] —
     * deliberately the same paranoid canonical-path check, so a stale
     * staging directory is only ever deleted when it resolves to exactly
     * one directory level under `rootDir()` and its name matches this
     * gameId's own staging-name shape.
     */
    fun isSafeToDeleteAsStaging(candidate: File, filesDir: File, gameEntryId: String): Boolean {
        return try {
            val canonicalCandidate = candidate.canonicalFile
            val canonicalRoot = rootDir(filesDir).canonicalFile
            canonicalCandidate.parentFile == canonicalRoot &&
                isStagingDirNameFor(canonicalCandidate.name, gameEntryId)
        } catch (e: java.io.IOException) {
            false
        }
    }

    /**
     * Pure comparison used by the best-effort storage pre-flight check —
     * kept separate from any real [File.getUsableSpace] call so the
     * decision logic itself (not the real disk query) is what gets
     * unit-tested.
     */
    fun hasEnoughSpace(estimatedBytes: Long, usableBytes: Long): Boolean =
        estimatedBytes <= usableBytes

    /**
     * Pure string check for the common Linux/Android "out of space"
     * signal in an exception message — heuristic, not authoritative (see
     * [MirrorRuntimeWorkspaceService.looksLikeOutOfSpace]'s kdoc for why
     * this can only ever be a best-effort signal, never a guarantee).
     */
    fun messageIndicatesOutOfSpace(message: String?): Boolean {
        val lower = message?.lowercase().orEmpty()
        return lower.contains("enospc") || lower.contains("no space left")
    }

    /**
     * Whether a single path segment (one file/directory name coming out
     * of a SAF [androidx.documentfile.provider.DocumentFile]) is safe to
     * mirror exactly as-is under the workspace tree.
     *
     * Rejects: blank names, names containing a path separator (`/` or
     * `\`) — which would let one "file name" smuggle in extra directory
     * levels or escape the intended destination folder entirely — a
     * literal NUL character, and the two special path segments `.` and
     * `..`, which have their own filesystem meaning and can't be mirrored
     * as an ordinary file/directory name without ambiguity.
     *
     * Never used to *fix* a name (no renaming, no stripping/escaping
     * offending characters) — only to decide whether mirroring must abort
     * for this document, per the approved rule that no document is ever
     * silently skipped or silently renamed.
     */
    fun isSafeDocumentName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.contains('\u0000')) return false
        return true
    }
}

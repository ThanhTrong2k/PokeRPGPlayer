package com.pokerpgplayer.app.runtime

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge Option B (DEC-017): mirrors a SAF-selected game folder into an
 * app-private workspace at `<filesDir>/runtime-workspace/<gameEntryId>/`,
 * preserving directory structure and filenames exactly.
 *
 * **Sprint 53.2 rewrite — persistent workspace, staging + promotion,
 * single-flight.** The original Sprint 6 policy ("every call fully
 * deletes and re-mirrors") is gone. Real-device evidence (Sprint 53.2
 * ticket) showed it producing multi-minute "Launching..." on every single
 * Play, plus partial/orphaned workspace directories after an interrupted
 * copy was retried. The replacement lifecycle:
 *
 * 1. If a [WorkspaceState.READY] workspace already exists for this game
 *    (recorded in the registry *and* its directory still exists), reuse
 *    it immediately — no source access, no copying.
 * 2. Otherwise, copy the source into a private staging directory
 *    (`.staging-<gameEntryId>-<token>/`, [WorkspacePathResolver.stagingDir]),
 *    never touching the final workspace path directly during the copy.
 * 3. Only once the staging copy completes without error is it *promoted*
 *    into the final workspace path via [promoteStagingToFinal] — an
 *    almost-always-atomic [File.renameTo], with a safe move-aside/restore
 *    swap for the one case where a final directory already occupies that
 *    path (a forced refresh, or a pre-53.2 leftover with no registry
 *    entry — see "migration" in this class's own promotion logic).
 * 4. The registry is only ever moved to [WorkspaceState.PREPARING] when
 *    there is *no* valid READY workspace to protect. A failed attempt
 *    never overwrites a still-valid READY record — see
 *    [failPreservingReady].
 * 5. Per-gameId single-flight is enforced with a [Mutex] — see
 *    [lockFor]. Two overlapping [prepareWorkspace] calls for the same
 *    game queue on the same lock; the second one, after acquiring it,
 *    re-checks freshly-written state and takes the fast READY-reuse path
 *    if the first call already succeeded, so only one physical copy ever
 *    runs.
 *
 * **Testability seam (Sprint 53.2):** this class's primary constructor
 * takes [filesDir] and a [SourceMirror] directly — zero Android
 * dependency — specifically because this project's `build.gradle.kts`
 * has no Robolectric or Mockito (`testImplementation("junit:junit:4.13.2")`
 * only), so there is no way to construct a working fake
 * [android.content.Context] for a plain JVM test. The secondary
 * `constructor(appContext: Context)` is the only production entry point
 * and wires in the real [RealSafSourceMirror]. This is the same
 * pure-core/thin-production-wrapper pattern already established by
 * [RuntimeLaunchPreparer]/`AndroidRuntimeManager` and
 * [com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService].
 *
 * The original SAF folder is never written to — every read against it
 * happens inside [SourceMirror]/[RealSafSourceMirror], never here.
 *
 * **Post-53.2 correction (ChatGPT audit).** Three fixes on top of the
 * design above, none of which change the architecture itself:
 *
 * 1. **Registry I/O is now globally serialized** via [registryIoLock].
 *    The per-gameId [Mutex] in [lockFor] only ever protects one game's
 *    *workspace* lifecycle; it says nothing about two *different* games
 *    racing on the one shared `registry.json` file both of them read,
 *    modify, and rewrite. Two concurrent [prepareWorkspace] calls for
 *    different gameIds could previously interleave their
 *    read-modify-write-temp-rename sequences and lose one game's update.
 *    Physical copying for different games still runs fully concurrently —
 *    only the brief JSON read/modify/write critical section is global.
 * 2. **A registry entry's `state` is READY if and only if it was written
 *    as exactly `"READY"`.** The original [readRegistryEntry] defaulted a
 *    missing `state` field to [WorkspaceState.READY] (for backward
 *    compatibility with pre-53.2 registry rows), and defaulted an
 *    unparseable value the same way. Both silently violated this whole
 *    sprint's central invariant — READY must mean "this lifecycle
 *    explicitly completed staging and promotion" — and real-device
 *    evidence already contains exactly the legacy/partial rows this would
 *    have mishandled. A missing `state` now reads as
 *    [WorkspaceState.MISSING] (never READY); an unparseable one reads as
 *    [WorkspaceState.FAILED] (never READY). Either way the next
 *    [prepareWorkspace] call for that game takes a real staging+promotion
 *    path rather than the fast-reuse path, and — because promotion never
 *    deletes the old final directory before the replacement succeeds —
 *    an existing (possibly partial, possibly perfectly fine) directory at
 *    that path is safely swapped, never blindly trusted or blindly wiped.
 * 3. **A READY record is only reusable if its recorded [WorkspaceMetadata.sourceUri]
 *    still matches the [folderUri] being requested.** If a game's SAF
 *    source ever changes, a stale workspace mirrored from the *old*
 *    source must never be reused just because its `state` says READY —
 *    the same staging+promotion safe-swap path handles the mismatch,
 *    preserving the old workspace until the new one is fully ready.
 */
class MirrorRuntimeWorkspaceService internal constructor(
    private val filesDir: File,
    private val sourceMirror: SourceMirror
) : RuntimeWorkspaceService {

    // Public constructor — the only one usable outside this Gradle
    // module. The (filesDir, SourceMirror) constructor above is
    // `internal` on purpose: SourceMirror itself is an internal type, and
    // Kotlin does not allow a public constructor to expose an internal
    // parameter type. `internal` visibility is exactly right here anyway
    // — it's a test-only seam, visible to this module's own test source
    // set (Gradle treats `test` as a friend of `main`), never meant to be
    // part of this class's real public API.
    constructor(appContext: Context) : this(appContext.filesDir, RealSafSourceMirror(appContext))

    /** Sprint 53.2 single-flight: one [Mutex] per gameId, created on first use and kept for the life of this service instance. */
    private val locksByGameId = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(gameEntryId: String): Mutex =
        locksByGameId.computeIfAbsent(gameEntryId) { Mutex() }

    /**
     * Post-53.2 fix. Guards the entire read-modify-write-temp-rename
     * sequence in [readRegistryEntry], [writeRegistryEntry] and
     * [removeRegistryEntry] against **cross-game** interleaving. The
     * per-gameId [Mutex] above only serializes one game's *workspace*
     * lifecycle (copying, staging, promotion) — it does nothing to stop
     * game A's and game B's registry read/modify/write sequences from
     * interleaving on the one shared `registry.json`/`registry.json.tmp`
     * pair, which could silently lose one game's update. This lock's
     * critical section is deliberately tiny (a small JSON file read,
     * one field mutated, a rewrite, a rename) — physical source copying
     * for different games is untouched by this lock and continues to run
     * fully concurrently.
     */
    private val registryIoLock = Any()

    private class CopyProgress {
        var fileCount: Int = 0
        var totalSizeBytes: Long = 0L
    }

    override suspend fun prepareWorkspace(gameEntryId: String, folderUri: String): WorkspaceResult =
        prepareWorkspaceInternal(gameEntryId, folderUri, forceRefresh = false)

    /**
     * Sprint 53.2 — not part of [RuntimeWorkspaceService] and not wired
     * into any UI/ViewModel yet. Forces a fresh copy-and-safe-swap even
     * when a READY workspace already exists, exercising exactly the
     * "existing READY workspace + failed attempted refresh must preserve
     * the previous READY workspace" invariant (Sprint 53.2 ticket, test
     * scenario 7) and the safe-swap-not-delete-first promotion path.
     * Exposed now as the seam a future "Refresh/Reimport Game" feature
     * (explicitly out of scope for this bug-fix sprint) will call — see
     * this sprint's own "known debt" notes.
     */
    suspend fun forceRefreshWorkspace(gameEntryId: String, folderUri: String): WorkspaceResult =
        prepareWorkspaceInternal(gameEntryId, folderUri, forceRefresh = true)

    private suspend fun prepareWorkspaceInternal(gameEntryId: String, folderUri: String, forceRefresh: Boolean): WorkspaceResult =
        withContext(Dispatchers.IO) {
            if (!WorkspacePathResolver.isValidGameEntryId(gameEntryId)) {
                return@withContext WorkspaceResult.Failed(
                    WorkspaceError.Unknown("gameEntryId is not a valid UUID: refusing to construct a path from it.")
                )
            }
            val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameEntryId)
                ?: return@withContext WorkspaceResult.Failed(WorkspaceError.Unknown("Could not resolve a workspace path."))

            // Single-flight: whichever caller for this exact gameId gets
            // here first does the real work; anyone else queues here and,
            // once unblocked, re-evaluates fresh state below rather than
            // repeating the copy.
            lockFor(gameEntryId).withLock {
                prepareWorkspaceLocked(gameEntryId, folderUri, finalDir, forceRefresh)
            }
        }

    /** Runs entirely under [lockFor]'s mutex for [gameEntryId] — never called directly. */
    private fun prepareWorkspaceLocked(gameEntryId: String, folderUri: String, finalDir: File, forceRefresh: Boolean): WorkspaceResult {
        val existingBefore = readRegistryEntry(gameEntryId)
        // Post-53.2 fix: a READY record is only trustworthy for reuse
        // when it was recorded against this exact folderUri. If the
        // game's SAF source ever changes, a workspace mirrored from the
        // *old* source must not be handed back just because `state` says
        // READY — it falls through to a real staging+promotion pass
        // below, which safely swaps it out rather than reusing stale
        // content or deleting it up front.
        val wasReady = existingBefore?.state == WorkspaceState.READY &&
            existingBefore.sourceUri == folderUri &&
            finalDir.exists() && finalDir.isDirectory

        if (wasReady && !forceRefresh) {
            // The fast path this whole sprint exists to enable: no source
            // access, no copying, no config work — just hand back what's
            // already there.
            return WorkspaceResult.Success(existingBefore!!)
        }

        if (!sourceMirror.validateSource(folderUri)) {
            return failPreservingReady(gameEntryId, wasReady, existingBefore, WorkspaceError.InvalidSourceFolder)
        }

        // Clean any stale staging directories left over from a previous
        // interrupted attempt for this exact game BEFORE starting a new
        // one, so repeated interrupted retries don't accumulate staging
        // directories on disk forever.
        cleanStaleStagingFor(gameEntryId)

        // Only move the registry to PREPARING when there is no valid READY
        // workspace to protect. Unconditionally overwriting a READY entry
        // here — an earlier draft's real bug, caught in this sprint's own
        // verification — would erase the fact this workspace was READY
        // before a later failure could consult it in failPreservingReady.
        if (!wasReady) {
            val now = nowIso()
            runCatching {
                writeRegistryEntry(
                    WorkspaceMetadata(
                        gameEntryId = gameEntryId,
                        sourceUri = folderUri,
                        workspacePath = finalDir.absolutePath,
                        createdAt = existingBefore?.createdAt ?: now,
                        updatedAt = now,
                        fileCount = existingBefore?.fileCount ?: 0,
                        totalSizeBytes = existingBefore?.totalSizeBytes ?: 0,
                        state = WorkspaceState.PREPARING
                    )
                )
            }
            // A failure to record PREPARING is not itself fatal — it's a
            // bookkeeping write, not the copy itself — so preparation
            // still proceeds; a later failure/success write attempts the
            // registry again regardless.
        }

        val token = UUID.randomUUID().toString()
        val stagingDir = WorkspacePathResolver.stagingDir(filesDir, gameEntryId, token)
            ?: return failPreservingReady(gameEntryId, wasReady, existingBefore, WorkspaceError.Unknown("Could not resolve a staging path."))

        if (!ensureDirectoryExists(stagingDir)) {
            return failPreservingReady(gameEntryId, wasReady, existingBefore, WorkspaceError.Unknown("Could not create staging directory: ${stagingDir.absolutePath}"))
        }

        var stagingConsumed = false
        try {
            val progress = CopyProgress()
            try {
                sourceMirror.mirrorInto(folderUri, stagingDir) { fileCount, totalBytes ->
                    progress.fileCount = fileCount
                    progress.totalSizeBytes = totalBytes
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WorkspaceMirrorException) {
                return failPreservingReady(gameEntryId, wasReady, existingBefore, e.error)
            } catch (e: Exception) {
                return failPreservingReady(gameEntryId, wasReady, existingBefore, WorkspaceError.Unknown(e.message ?: e.toString()))
            }

            val promotionError = promoteStagingToFinal(stagingDir, finalDir, gameEntryId)
            if (promotionError != null) {
                return failPreservingReady(gameEntryId, wasReady, existingBefore, promotionError)
            }
            // Staging directory no longer exists at its own path — it was
            // renamed (directly, or via the backup-swap sequence) into
            // finalDir. Nothing left for the finally block to clean up.
            stagingConsumed = true

            val now = nowIso()
            val metadata = WorkspaceMetadata(
                gameEntryId = gameEntryId,
                sourceUri = folderUri,
                workspacePath = finalDir.absolutePath,
                createdAt = existingBefore?.createdAt ?: now,
                updatedAt = now,
                fileCount = progress.fileCount,
                totalSizeBytes = progress.totalSizeBytes,
                state = WorkspaceState.READY
            )
            try {
                writeRegistryEntry(metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The workspace itself is already fully promoted and
                // correct on disk at this point — only the registry write
                // failed. Reporting Failed here (rather than silently
                // succeeding) keeps the registry's own state honest: a
                // later getWorkspaceMetadata() must not claim READY when
                // the READY record was never actually committed. The
                // promoted directory is deliberately NOT deleted — that
                // would turn a metadata bookkeeping failure into a real
                // data-loss bug for no benefit.
                return failPreservingReady(gameEntryId, wasReady, existingBefore, WorkspaceError.Unknown("Workspace promoted but registry could not be updated: ${e.message ?: e}"))
            }

            return WorkspaceResult.Success(metadata)
        } catch (e: CancellationException) {
            // Never converted into a WorkspaceResult — propagate as-is.
            // stagingConsumed stays false unless promotion already
            // completed, so the finally block below still cleans up a
            // truly partial staging copy.
            throw e
        } finally {
            if (!stagingConsumed && stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }
    }

    /**
     * Promotes a completed staging copy into [finalDir]. Returns `null`
     * on success, or a structured [WorkspaceError] on failure — never
     * throws for an ordinary filesystem failure.
     *
     * Two cases:
     * - [finalDir] does not exist yet (first-ever preparation, or a prior
     *   preparation that failed before ever reaching promotion): a single
     *   rename is enough, and is as close to atomic as this filesystem
     *   offers.
     * - [finalDir] already exists — a forced refresh
     *   ([forceRefreshWorkspace]), **or** a pre-53.2 orphaned/partial
     *   directory with no (or a non-READY) registry entry, which is
     *   exactly this sprint's own "migration handling for existing
     *   partial runtime-workspace dirs" requirement: this same safe-swap
     *   path handles it by construction, with no separate migration step
     *   needed. The existing directory is moved aside to a backup path
     *   first; only once that succeeds is staging renamed into place; if
     *   the second rename fails, the backup is restored so a previously-
     *   working (or simply pre-existing) directory is never left missing.
     */
    internal fun promoteStagingToFinal(stagingDir: File, finalDir: File, gameEntryId: String): WorkspaceError? {
        if (!finalDir.exists()) {
            return if (stagingDir.renameTo(finalDir)) {
                null
            } else {
                WorkspaceError.Unknown("Could not promote staging workspace to final location: ${finalDir.absolutePath}")
            }
        }

        val backupDir = WorkspacePathResolver.backupDirForPromotion(filesDir, gameEntryId)
            ?: return WorkspaceError.Unknown("Could not resolve a backup path for promotion.")

        // A leftover backup from a previous, already-concluded promotion
        // attempt is always safe to discard before reuse — nothing in
        // this codebase ever treats a backup path as a valid workspace or
        // registry-referenced location.
        if (backupDir.exists()) backupDir.deleteRecursively()

        if (!finalDir.renameTo(backupDir)) {
            return WorkspaceError.Unknown("Could not move existing workspace aside for promotion: ${finalDir.absolutePath}")
        }

        if (!stagingDir.renameTo(finalDir)) {
            // Restore the previous workspace exactly as it was — a failed
            // promotion must never leave the game with *no* workspace at
            // all when it had a working (or at least pre-existing) one a
            // moment ago.
            val restored = backupDir.renameTo(finalDir)
            return WorkspaceError.Unknown(
                if (restored) {
                    "Could not promote staging workspace to final location; previous workspace was restored."
                } else {
                    "Could not promote staging workspace to final location, AND could not restore the previous workspace from backup at ${backupDir.absolutePath}."
                }
            )
        }

        backupDir.deleteRecursively()
        return null
    }

    /**
     * `mkdirs()==false` is not, by itself, evidence of failure — it also
     * returns false when the directory already exists (Sprint 53.2 root-
     * cause fix; the original code treated any `false` as
     * "Could not create workspace directory", which is exactly the false
     * failure reported in this sprint's own real-device evidence). The
     * only thing that actually matters is the state after the call.
     */
    private fun ensureDirectoryExists(dir: File): Boolean {
        if (!dir.exists()) dir.mkdirs()
        return dir.exists() && dir.isDirectory
    }

    /**
     * The core "never let a failed attempt erase a valid READY record"
     * fix. If [wasReady] is true, the registry is left **completely
     * untouched** — [existingBefore] is still there, still honestly
     * reporting the previously-valid workspace, exactly as invariant #6/
     * test scenario 7 require. Only when there was *no* valid READY
     * workspace to protect does this record the failure, so a later
     * [getWorkspaceMetadata] call reports [WorkspaceState.FAILED] instead
     * of a stale [WorkspaceState.PREPARING] or [WorkspaceState.MISSING].
     */
    private fun failPreservingReady(
        gameEntryId: String,
        wasReady: Boolean,
        existingBefore: WorkspaceMetadata?,
        error: WorkspaceError
    ): WorkspaceResult.Failed {
        if (!wasReady) {
            val now = nowIso()
            runCatching {
                writeRegistryEntry(
                    WorkspaceMetadata(
                        gameEntryId = gameEntryId,
                        sourceUri = existingBefore?.sourceUri ?: "",
                        workspacePath = existingBefore?.workspacePath
                            ?: (WorkspacePathResolver.workspaceDir(filesDir, gameEntryId)?.absolutePath ?: ""),
                        createdAt = existingBefore?.createdAt ?: now,
                        updatedAt = now,
                        fileCount = existingBefore?.fileCount ?: 0,
                        totalSizeBytes = existingBefore?.totalSizeBytes ?: 0,
                        state = WorkspaceState.FAILED
                    )
                )
            }
        }
        return WorkspaceResult.Failed(error, existingBefore)
    }

    /** Deletes any staging directory left over for [gameEntryId] specifically — never anything else. */
    private fun cleanStaleStagingFor(gameEntryId: String) {
        val root = WorkspacePathResolver.rootDir(filesDir)
        val children = root.listFiles() ?: return
        for (child in children) {
            if (WorkspacePathResolver.isStagingDirNameFor(child.name, gameEntryId) &&
                WorkspacePathResolver.isSafeToDeleteAsStaging(child, filesDir, gameEntryId)
            ) {
                child.deleteRecursively()
            }
        }
    }

    override suspend fun getWorkspaceMetadata(gameEntryId: String): WorkspaceMetadata? =
        withContext(Dispatchers.IO) {
            if (!WorkspacePathResolver.isValidGameEntryId(gameEntryId)) return@withContext null
            readRegistryEntry(gameEntryId)
        }

    override suspend fun clearWorkspace(gameEntryId: String): Boolean =
        withContext(Dispatchers.IO) {
            val workspaceDir = WorkspacePathResolver.workspaceDir(filesDir, gameEntryId) ?: return@withContext false

            lockFor(gameEntryId).withLock {
                // Same paranoid re-derive-and-confirm check as the
                // original Sprint 6 design — this function accepts no
                // path from any caller, only a gameEntryId.
                if (!WorkspacePathResolver.isSafeToDeleteAsWorkspace(workspaceDir, filesDir, gameEntryId)) {
                    return@withLock false
                }

                if (workspaceDir.exists()) {
                    workspaceDir.deleteRecursively()
                }
                cleanStaleStagingFor(gameEntryId)
                removeRegistryEntry(gameEntryId)
                !workspaceDir.exists()
            }
        }

    // ---- Registry persistence ----
    //
    // Build-compatibility correction: uses this file's own [RegistryJson]
    // codec, not `org.json.JSONObject` — see that file's kdoc for exactly
    // why `org.json.JSONObject` is unsafe inside a path this class needs
    // to stay plain-JVM-testable (it resolves to the Android platform's
    // stub jar, not the real implementation, under `testDebugUnitTest`).
    // Same atomic-write pattern as before: read-modify-write the whole
    // file, write to a temp file, rename over the real one.

    private fun readRegistryEntry(gameEntryId: String): WorkspaceMetadata? = synchronized(registryIoLock) {
        val file = WorkspacePathResolver.registryFile(filesDir)
        if (!file.exists()) return@synchronized null
        try {
            val root = RegistryJson.parseObject(file.readText())
            @Suppress("UNCHECKED_CAST")
            val entry = root[gameEntryId] as? Map<String, Any?> ?: return@synchronized null
            WorkspaceMetadata(
                gameEntryId = gameEntryId,
                sourceUri = entry["sourceUri"] as? String ?: return@synchronized null,
                workspacePath = entry["workspacePath"] as? String ?: return@synchronized null,
                createdAt = entry["createdAt"] as? String ?: return@synchronized null,
                updatedAt = entry["updatedAt"] as? String ?: return@synchronized null,
                fileCount = (entry["fileCount"] as? Number)?.toInt() ?: 0,
                totalSizeBytes = (entry["totalSizeBytes"] as? Number)?.toLong() ?: 0L,
                state = deserializeState(entry)
            )
        } catch (e: Exception) {
            // Corrupted registry — degrade to "no metadata on record"
            // rather than crash. The file itself is left untouched.
            null
        }
    }

    /**
     * Post-53.2 fix. The ONLY string that deserializes to
     * [WorkspaceState.READY] is the literal `"READY"` written by this
     * class's own [writeRegistryEntry]. A pre-53.2 registry row has no
     * `state` field at all — that must never be read back as READY, since
     * this whole sprint's invariant is that READY means "staging and
     * promotion explicitly completed," which a legacy row never did. A
     * corrupted or otherwise unparseable value is equally never READY.
     * Either case reads as a real, valid [WorkspaceState] so the rest of
     * this class's null-handling is unaffected — just never the one value
     * that would wrongly unlock the fast reuse path.
     */
    private fun deserializeState(entry: Map<String, Any?>): WorkspaceState {
        val stateRaw = entry["state"] as? String ?: ""
        if (stateRaw == WorkspaceState.READY.name) return WorkspaceState.READY
        if (stateRaw.isBlank()) return WorkspaceState.MISSING
        return runCatching { WorkspaceState.valueOf(stateRaw) }.getOrDefault(WorkspaceState.FAILED)
    }

    private fun writeRegistryEntry(metadata: WorkspaceMetadata): Unit = synchronized(registryIoLock) {
        val rootDir = WorkspacePathResolver.rootDir(filesDir)
        if (!rootDir.exists()) rootDir.mkdirs()
        val file = WorkspacePathResolver.registryFile(filesDir)
        val tempFile = WorkspacePathResolver.registryTempFile(filesDir)

        // Sprint53.2 RegistryJVM FINAL integrity correction (bounded
        // check, invariant 2): a stale registry.json.tmp left over from a
        // previous interrupted write/remove must never influence this
        // commit. `tempFile.writeText()` below would overwrite a stale
        // plain file anyway, but clearing it defensively first also
        // covers the pathological case of a stale *directory* sitting at
        // the temp path (writeText() throws against a directory) — either
        // way, nothing left over from before this call can corrupt or
        // block it.
        if (tempFile.exists()) tempFile.deleteRecursively()

        val root: MutableMap<String, Any?> = if (file.exists()) {
            runCatching { RegistryJson.parseObject(file.readText()).toMutableMap() }.getOrDefault(LinkedHashMap())
        } else {
            LinkedHashMap()
        }

        root[metadata.gameEntryId] = linkedMapOf<String, Any?>(
            "sourceUri" to metadata.sourceUri,
            "workspacePath" to metadata.workspacePath,
            "createdAt" to metadata.createdAt,
            "updatedAt" to metadata.updatedAt,
            "fileCount" to metadata.fileCount,
            "totalSizeBytes" to metadata.totalSizeBytes,
            "state" to metadata.state.name
        )

        tempFile.writeText(RegistryJson.serializeObject(root))
        if (!tempFile.renameTo(file)) {
            // Invariant 1 (no data loss on failed commit): `file` is never
            // touched until this rename succeeds, so the previous valid
            // registry — if any — is still intact on disk right now.
            // Invariant 2 (a stale temp must not block the NEXT commit):
            // this failed attempt's own tempFile is cleaned up immediately
            // rather than left for a future call to deal with — belt and
            // braces on top of the defensive cleanup at the top of this
            // function, so a failed commit never leaves anything behind,
            // not even until the next write.
            tempFile.delete()
            throw IOException("Could not commit workspace registry (rename from ${tempFile.name} to ${file.name} failed)")
        }
    }

    private fun removeRegistryEntry(gameEntryId: String): Unit = synchronized(registryIoLock) {
        val file = WorkspacePathResolver.registryFile(filesDir)
        if (!file.exists()) return@synchronized
        val root = runCatching { RegistryJson.parseObject(file.readText()).toMutableMap() }.getOrDefault(LinkedHashMap())
        if (!root.containsKey(gameEntryId)) return@synchronized
        root.remove(gameEntryId)
        val tempFile = WorkspacePathResolver.registryTempFile(filesDir)
        // Same stale-temp defensiveness as writeRegistryEntry above.
        if (tempFile.exists()) tempFile.deleteRecursively()
        tempFile.writeText(RegistryJson.serializeObject(root))
        // Best-effort only, same reasoning as the original Sprint 6
        // design — clearWorkspace's own result is based on whether the
        // workspace directory itself is gone, which already succeeded by
        // the time this runs. Invariant 4: even though a failed rename
        // here is not itself surfaced as an error, the tempFile it would
        // otherwise leave behind is still cleaned up so it can never
        // interfere with (or be mistaken for) a later write's own temp
        // file.
        if (!tempFile.renameTo(file)) {
            tempFile.delete()
        }
    }
}

private fun nowIso(): String = java.time.Instant.now().toString()

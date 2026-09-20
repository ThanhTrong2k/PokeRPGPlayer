package com.pokerpgplayer.app.runtime

import com.pokerpgplayer.app.data.config.DiagnosticProfiles
import com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService
import com.pokerpgplayer.app.data.model.OverlayGenerationTarget
import com.pokerpgplayer.app.data.model.OverlayStatus
import com.pokerpgplayer.app.data.model.RuntimeConfigProfile
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Sprint 53 (wiring) / Sprint 53.1 (failure-safe promotion) / Sprint 53.2
 * (persistent workspace) — the pure preparation core behind a real Play
 * attempt: resolve a ready workspace, refresh its production runtime
 * config, and hand back a real filesystem path [RuntimeActivity] can
 * launch from. Takes no [android.content.Context] and performs no
 * `Intent`/`startActivity` work — that belongs to `AndroidRuntimeManager`,
 * the thin wrapper around this class — so this entire pipeline, including
 * its two Sprint 53.1 failure-safety fixes, is unit-testable in a plain
 * JVM test.
 */
class RuntimeLaunchPreparer(
    private val workspaceService: RuntimeWorkspaceService,
    private val overlayService: RuntimeConfigOverlayService = RuntimeConfigOverlayService()
) {

    /**
     * Build-compatibility correction. Every [WorkspaceError] except
     * [WorkspaceError.SafAccessLost] maps to a player-readable
     * [RuntimeError.WorkspacePreparationFailed] — [WorkspaceError.SafAccessLost]
     * alone maps to [RuntimeError.GameFolderAccessLost], since that one
     * case means the folder itself is fine but this app's own permission
     * grant to it was revoked, a materially different situation from "the
     * folder is invalid" or "one file failed to copy."
     */
    private fun mapWorkspaceError(error: WorkspaceError): RuntimeError = when (error) {
        is WorkspaceError.SafAccessLost ->
            RuntimeError.GameFolderAccessLost
        is WorkspaceError.InvalidSourceFolder ->
            RuntimeError.WorkspacePreparationFailed("The selected game folder could not be opened.")
        is WorkspaceError.CopyFailure ->
            RuntimeError.WorkspacePreparationFailed("Failed to copy ${error.relativePath}: ${error.message}")
        is WorkspaceError.InsufficientStorage ->
            RuntimeError.WorkspacePreparationFailed("Not enough storage space to prepare this game.")
        is WorkspaceError.UnsupportedDocumentType ->
            RuntimeError.WorkspacePreparationFailed("Unsupported file type in the game folder: ${error.relativePath}")
        is WorkspaceError.Unknown ->
            RuntimeError.WorkspacePreparationFailed(error.message)
    }

    /**
     * Resolves (reusing a READY workspace when one exists — Sprint 53.2 —
     * or preparing one when it doesn't) and refreshes the production
     * config on top of it. Never recopies the source merely to refresh
     * config: [RuntimeWorkspaceService.prepareWorkspace] is the only step
     * that can touch the SAF source, and this function calls it exactly
     * once per attempt.
     */
    suspend fun prepareLaunch(request: GameLaunchRequest): LaunchPreparationResult {
        val workspaceResult = workspaceService.prepareWorkspace(request.gameId, request.folderUri)

        val metadata = when (workspaceResult) {
            is WorkspaceResult.Success -> workspaceResult.metadata
            is WorkspaceResult.Failed -> return LaunchPreparationResult.Failed(mapWorkspaceError(workspaceResult.error))
        }

        val workspaceDir = File(metadata.workspacePath)
        val mkxpJsonFile = File(workspaceDir, MKXP_JSON_FILE_NAME)
        if (!mkxpJsonFile.exists()) {
            return LaunchPreparationResult.Failed(RuntimeError.ConfigMissing(mkxpJsonFile.absolutePath))
        }

        return refreshProductionConfig(request, workspaceDir, mkxpJsonFile)
    }

    /**
     * Sprint 53.1 hardening applied here: generates into a private temp
     * directory first, validates every promised file exists, and only
     * then promotes — never partially, never leaving `mkxp.json`
     * referencing preload scripts that don't exist in the workspace.
     */
    private fun refreshProductionConfig(request: GameLaunchRequest, workspaceDir: File, mkxpJsonFile: File): LaunchPreparationResult {
        val profile = productionProfileFor(request)
        val tempOverlayDir = File(workspaceDir.parentFile, "$TEMP_OVERLAY_DIR_PREFIX${request.gameId}")

        // A stale tempOverlayDir from a previous interrupted attempt must
        // never influence this one (Sprint 53.1 requirement) — clean it
        // before generating into it, not after.
        if (tempOverlayDir.exists()) tempOverlayDir.deleteRecursively()

        // Build-compatibility correction: also clean the LEGACY Sprint
        // 53.1 temp overlay path, not just the current Sprint 53.2 one.
        // Sprint 53.1 originally named this directory
        // `<workspaceDir.name>-overlay-tmp`; Sprint 53.2 renamed it to
        // `.overlay-tmp-<gameId>` (this class's own [TEMP_OVERLAY_DIR_PREFIX]
        // above) without also cleaning up the old name, so a directory
        // left over from a build predating Sprint 53.2 would sit there
        // forever, untouched by the current cleanup line above. Both
        // paths are derived purely from [workspaceDir] (never from
        // caller-supplied input), and the legacy name always differs
        // from workspaceDir's own name (it has a suffix appended), so
        // this can never target workspaceDir itself.
        val legacyTempOverlayDir = File(workspaceDir.parentFile, "${workspaceDir.name}$LEGACY_TEMP_OVERLAY_DIR_SUFFIX")
        if (legacyTempOverlayDir.exists()) legacyTempOverlayDir.deleteRecursively()

        try {
            val genResult = overlayService.generateOverlayToDirectory(
                originalConfigFile = mkxpJsonFile,
                profile = profile,
                outputDirectory = tempOverlayDir,
                target = OverlayGenerationTarget.PRODUCTION
            )

            return when (genResult.overlayStatus) {
                OverlayStatus.NOT_GENERATED -> {
                    // No mitigations were requested/applicable — the
                    // mirrored mkxp.json is used exactly as promoted by
                    // the workspace, unmodified.
                    LaunchPreparationResult.Ready(workspaceDir.absolutePath)
                }
                OverlayStatus.ERROR -> {
                    LaunchPreparationResult.Failed(RuntimeError.ConfigGenerationFailed(genResult.errorMessage))
                }
                OverlayStatus.GENERATED_PRODUCTION -> {
                    promoteGeneratedOverlay(tempOverlayDir, workspaceDir, mkxpJsonFile, genResult.generatedAuxiliaryFiles.keys)
                        .fold(
                            onSuccess = { LaunchPreparationResult.Ready(workspaceDir.absolutePath) },
                            onFailure = { t ->
                                if (t is CancellationException) throw t
                                LaunchPreparationResult.Failed(RuntimeError.ConfigGenerationFailed(t.message))
                            }
                        )
                }
                OverlayStatus.GENERATED_TEST_ONLY, OverlayStatus.DISABLED -> {
                    // Never produced by this call (target is always
                    // PRODUCTION here) — defensive fallback only.
                    LaunchPreparationResult.Ready(workspaceDir.absolutePath)
                }
            }
        } finally {
            tempOverlayDir.deleteRecursively()
        }
    }

    /**
     * Sprint 53.1's own required fix, preserved unchanged in spirit:
     * stage every auxiliary file first, commit `mkxp.json` **last**, and
     * restore the previous `mkxp.json` content if the final commit fails.
     * Returns [Result.failure] instead of throwing for any ordinary
     * filesystem failure; [CancellationException] is rethrown, never
     * wrapped.
     */
    internal fun promoteGeneratedOverlay(
        tempOverlayDir: File,
        workspaceDir: File,
        mkxpJsonFile: File,
        promisedAuxiliaryFileNames: Set<String>
    ): Result<Unit> {
        return try {
            // Deliberately `isFile`, not just `exists()`: `File.copyTo`
            // does NOT throw when its source is a directory — it silently
            // deletes the destination and creates an empty directory in
            // its place (verified directly: this is real
            // `kotlin.io.FilesKt.copyTo` behavior, not a hypothetical).
            // Relying on `exists()` alone here would let a corrupted or
            // unexpected generation result silently destroy the
            // workspace's own `mkxp.json`/auxiliary files with no
            // exception ever thrown to catch.
            val generatedMkxpJson = File(tempOverlayDir, RuntimeConfigOverlayService.OVERLAY_CONFIG_FILE_NAME)
            if (!generatedMkxpJson.isFile) {
                return Result.failure(IllegalStateException("Generated overlay config missing or not a regular file at ${generatedMkxpJson.absolutePath}"))
            }
            for (auxName in promisedAuxiliaryFileNames) {
                if (!File(tempOverlayDir, auxName).isFile) {
                    return Result.failure(IllegalStateException("Promised auxiliary file missing or not a regular file: $auxName"))
                }
            }

            // 1. Stage every auxiliary file into the workspace first.
            for (auxName in promisedAuxiliaryFileNames) {
                File(tempOverlayDir, auxName).copyTo(File(workspaceDir, auxName), overwrite = true)
            }

            // 2. Back up the current mkxp.json content in memory so it can
            //    be restored if the final commit below fails.
            val previousMkxpJsonText = runCatching { mkxpJsonFile.readText() }.getOrNull()

            // 3. Commit the new mkxp.json LAST — only once every auxiliary
            //    file it may reference already exists in the workspace.
            try {
                generatedMkxpJson.copyTo(mkxpJsonFile, overwrite = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (previousMkxpJsonText != null) {
                    runCatching { mkxpJsonFile.writeText(previousMkxpJsonText) }
                }
                return Result.failure(e)
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val MKXP_JSON_FILE_NAME = "mkxp.json"
        private const val TEMP_OVERLAY_DIR_PREFIX = ".overlay-tmp-"

        /** The pre-53.2 Sprint 53.1 temp overlay directory name suffix — `<workspaceDir.name>-overlay-tmp`. Still cleaned defensively before every generation; see the build-compatibility correction note at its one call site. */
        private const val LEGACY_TEMP_OVERLAY_DIR_SUFFIX = "-overlay-tmp"

        /**
         * The mitigation set every real Play applies, on every launch,
         * to the managed workspace's own config — matches
         * [DiagnosticProfiles.normalDiagnosticMitigations] (zlib preload,
         * ASCII-safe window title, aspect-fit-center rendering, the
         * confirmed FPS-60 cap, and the Sprint 48/50 timebase/speed
         * shims), deliberately excluding the Sprint 41–44 diagnostic
         * tracing scripts, which stay `androidTest`-only. A per-game
         * override via [GameLaunchRequest.configProfile] (sourced from
         * [com.pokerpgplayer.app.data.model.GameEntry.runtimeConfigProfile])
         * is layered on top by unioning its own `enabledMitigations` —
         * the empty default profile every [GameEntry] has today changes
         * nothing.
         */
        internal fun productionProfileFor(request: GameLaunchRequest): RuntimeConfigProfile {
            val base = DiagnosticProfiles.normalDiagnosticMitigations()
            val fromEntry = request.configProfile.enabledMitigations
            val disabled = request.configProfile.disabledMitigations
            return RuntimeConfigProfile(
                enabledMitigations = (base + fromEntry).distinct(),
                disabledMitigations = disabled
            )
        }
    }
}

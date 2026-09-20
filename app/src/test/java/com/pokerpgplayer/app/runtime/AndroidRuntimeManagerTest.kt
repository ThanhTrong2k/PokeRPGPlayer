package com.pokerpgplayer.app.runtime

import com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sprint 53 — JVM unit tests for [RuntimeLaunchPreparer.prepareLaunch].
 * No Android dependency at all: [FakeRuntimeWorkspaceService] stands in
 * for [RuntimeWorkspaceService] (a real Android Context/SAF dependency),
 * and the real [RuntimeConfigOverlayService] is used directly — it's
 * already a pure, no-I/O-beyond-plain-File-access class.
 *
 * Tests [RuntimeLaunchPreparer] directly, not [AndroidRuntimeManager] —
 * [AndroidRuntimeManager] itself needs a real Context (used only by its
 * own `launch()`, for the Intent/startActivity step), which this file
 * never constructs. A narrow instrumentation test
 * (`AndroidRuntimeManagerProductionLaunchTest`) separately covers that
 * remaining step end-to-end on a real device.
 */
class AndroidRuntimeManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val overlayService = RuntimeConfigOverlayService()

    private class FakeRuntimeWorkspaceService(private val result: WorkspaceResult) : RuntimeWorkspaceService {
        override suspend fun prepareWorkspace(gameEntryId: String, folderUri: String): WorkspaceResult = result
        override suspend fun getWorkspaceMetadata(gameEntryId: String): WorkspaceMetadata? = null
        override suspend fun clearWorkspace(gameEntryId: String): Boolean = true
    }

    private fun sampleRequest(gameId: String = "game-1", folderUri: String = "content://fake/tree/Game") =
        GameLaunchRequest(
            gameId = gameId,
            folderUri = folderUri,
            executableName = "Game.exe",
            detectedTitle = "Sample Game"
        )

    private val realisticMkxpJson = """{
    "windowTitle": "Pokemon Essentials",
    "vsync": true
}"""

    // ---- Workspace error mapping ----

    @Test
    fun `SafAccessLost maps to GameFolderAccessLost`() = runBlocking {
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Failed(WorkspaceError.SafAccessLost)),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        assertTrue(result is LaunchPreparationResult.Failed)
        assertEquals(RuntimeError.GameFolderAccessLost, (result as LaunchPreparationResult.Failed).error)
    }

    @Test
    fun `InvalidSourceFolder maps to WorkspacePreparationFailed, not GameFolderAccessLost`() = runBlocking {
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Failed(WorkspaceError.InvalidSourceFolder)),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        assertTrue(result is LaunchPreparationResult.Failed)
        val error = (result as LaunchPreparationResult.Failed).error
        assertTrue("InvalidSourceFolder must map to WorkspacePreparationFailed, not be conflated with SafAccessLost's own GameFolderAccessLost", error is RuntimeError.WorkspacePreparationFailed)
    }

    @Test
    fun `CopyFailure maps to WorkspacePreparationFailed carrying the relative path and message`() = runBlocking {
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Failed(WorkspaceError.CopyFailure("Data/Scripts.rxdata", "disk read error"))),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        val error = (result as LaunchPreparationResult.Failed).error as RuntimeError.WorkspacePreparationFailed
        assertTrue(error.reason.contains("Data/Scripts.rxdata"))
        assertTrue(error.reason.contains("disk read error"))
    }

    @Test
    fun `InsufficientStorage maps to a player-readable WorkspacePreparationFailed`() = runBlocking {
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Failed(WorkspaceError.InsufficientStorage)),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        val error = (result as LaunchPreparationResult.Failed).error as RuntimeError.WorkspacePreparationFailed
        assertTrue(error.reason.isNotBlank())
    }

    @Test
    fun `UnsupportedDocumentType maps to WorkspacePreparationFailed`() = runBlocking {
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Failed(WorkspaceError.UnsupportedDocumentType("Data/weird.doc"))),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        assertTrue((result as LaunchPreparationResult.Failed).error is RuntimeError.WorkspacePreparationFailed)
    }

    // ---- Config missing ----

    @Test
    fun `missing mkxp-json in the mirrored workspace maps to ConfigMissing`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-no-config")
        val metadata = WorkspaceMetadata("game-1", "content://fake", workspaceDir.absolutePath, "now", "now", 0, 0)
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(metadata)),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        assertTrue(result is LaunchPreparationResult.Failed)
        val error = (result as LaunchPreparationResult.Failed).error
        assertTrue(error is RuntimeError.ConfigMissing)
        assertTrue((error as RuntimeError.ConfigMissing).expectedPath.contains("mkxp.json"))
    }

    // ---- Config generation failure ----

    @Test
    fun `malformed mkxp-json that cannot be transformed maps to ConfigGenerationFailed`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-malformed-config")
        File(workspaceDir, "mkxp.json").writeText("{ this is not valid and never closes")
        val metadata = WorkspaceMetadata("game-1", "content://fake", workspaceDir.absolutePath, "now", "now", 1, 10)
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(metadata)),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        assertTrue(result is LaunchPreparationResult.Failed)
        assertTrue((result as LaunchPreparationResult.Failed).error is RuntimeError.ConfigGenerationFailed)
    }

    // ---- Successful preparation, real production overlay ----

    @Test
    fun `successful workspace and production config preparation returns Ready with the workspace path`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-happy-path")
        File(workspaceDir, "mkxp.json").writeText(realisticMkxpJson)
        val metadata = WorkspaceMetadata("game-1", "content://fake", workspaceDir.absolutePath, "now", "now", 1, 10)
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(metadata)),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())
        assertTrue(result is LaunchPreparationResult.Ready)
        assertEquals(workspaceDir.absolutePath, (result as LaunchPreparationResult.Ready).workspacePath)
    }

    @Test
    fun `successful preparation replaces the workspace mkxp-json with the generated production overlay`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-overlay-replace")
        File(workspaceDir, "mkxp.json").writeText(realisticMkxpJson)
        val metadata = WorkspaceMetadata("game-1", "content://fake", workspaceDir.absolutePath, "now", "now", 1, 10)
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(metadata)),
            overlayService
        )
        preparer.prepareLaunch(sampleRequest())

        val finalConfig = File(workspaceDir, "mkxp.json").readText()
        assertTrue("workspace mkxp.json must contain preloadScript after production preparation", finalConfig.contains("preloadScript"))
    }

    @Test
    fun `preload ordering in the final workspace mkxp-json matches the required normal production order`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-preload-order")
        File(workspaceDir, "mkxp.json").writeText(realisticMkxpJson)
        val metadata = WorkspaceMetadata("game-1", "content://fake", workspaceDir.absolutePath, "now", "now", 1, 10)
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(metadata)),
            overlayService
        )
        preparer.prepareLaunch(sampleRequest())

        val finalConfig = File(workspaceDir, "mkxp.json").readText()
        val sprint48Index = finalConfig.indexOf("sprint48-system-uptime-seconds-shim.rb")
        val sprint50Index = finalConfig.indexOf("sprint50-speed-control-shim.rb")
        val zlibIndex = finalConfig.indexOf("pokerpg_preload_zlib.rb")

        assertTrue("sprint48 shim must be present", sprint48Index >= 0)
        assertTrue("sprint50 shim must be present", sprint50Index >= 0)
        assertTrue("zlib preload must be present", zlibIndex >= 0)
        assertTrue("required order: sprint48 -> sprint50 -> zlib", sprint48Index < sprint50Index && sprint50Index < zlibIndex)

        assertFalse(finalConfig.contains("sprint41-input-diagnostic"))
        assertFalse(finalConfig.contains("sprint42-movement-path-diagnostic"))
        assertFalse(finalConfig.contains("sprint43-command-pipeline-diagnostic"))
        assertFalse(finalConfig.contains("sprint44-timebase-diagnostic"))

        assertTrue(finalConfig.contains("\"syncToRefreshrate\": false"))
        assertTrue(finalConfig.contains("\"fixedFramerate\": 60"))
    }

    @Test
    fun `production auxiliary shim files are written into the workspace directory itself`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-aux-files")
        File(workspaceDir, "mkxp.json").writeText(realisticMkxpJson)
        val metadata = WorkspaceMetadata("game-1", "content://fake", workspaceDir.absolutePath, "now", "now", 1, 10)
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(metadata)),
            overlayService
        )
        preparer.prepareLaunch(sampleRequest())

        assertTrue(File(workspaceDir, "sprint48-system-uptime-seconds-shim.rb").exists())
        assertTrue(File(workspaceDir, "sprint50-speed-control-shim.rb").exists())
        assertTrue(File(workspaceDir, "pokerpg_preload_zlib.rb").exists())
    }

    @Test
    fun `no leftover temporary overlay directory remains after a successful preparation`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-cleanup-check")
        File(workspaceDir, "mkxp.json").writeText(realisticMkxpJson)
        val metadata = WorkspaceMetadata("game-1", "content://fake", workspaceDir.absolutePath, "now", "now", 1, 10)
        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(metadata)),
            overlayService
        )
        preparer.prepareLaunch(sampleRequest())

        val tempOverlayDir = File(workspaceDir.parentFile, "${workspaceDir.name}-overlay-tmp")
        assertFalse("temporary overlay directory must be cleaned up after a successful preparation", tempOverlayDir.exists())
    }

    // ==================== Sprint 53.1 — ChatGPT audit correction: failure-safe promotion ====================

    /**
     * Real, OS-universal I/O failure technique: place a NON-EMPTY
     * directory at the exact destination path `File.copyTo(overwrite =
     * true)` will target. Kotlin's `copyTo` deletes the existing target
     * first when overwriting — `File.delete()` never removes a non-empty
     * directory on any OS, and even if it somehow did, writing a plain
     * file over a path that's still a directory also universally fails
     * ("Is a directory") — this is standard, portable filesystem
     * behavior, not an OS-specific permission-bit quirk that might not
     * reproduce identically everywhere.
     */
    private fun blockPathWithNonEmptyDirectory(path: File) {
        path.mkdirs()
        File(path, "blocking-child-file.txt").writeText("prevents File.delete() from succeeding on this directory")
    }

    @Test
    fun `Sprint53_1 - auxiliary promotion failure returns Failed, not Ready, and never leaves a partial commit path silently succeeding`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-aux-promotion-fail")
        val tempOverlayDir = tempFolder.newFolder("temp-overlay-aux-fail")
        File(tempOverlayDir, "mkxp.json").writeText(realisticMkxpJson)
        File(tempOverlayDir, "shim.rb").writeText("shim content")

        // The destination for "shim.rb" inside workspaceDir is blocked —
        // forces a real File.copyTo failure during auxiliary staging.
        blockPathWithNonEmptyDirectory(File(workspaceDir, "shim.rb"))

        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(WorkspaceMetadata("g", "c", workspaceDir.absolutePath, "now", "now", 0, 0))),
            overlayService
        )
        val result = preparer.promoteGeneratedOverlay(tempOverlayDir, workspaceDir, File(workspaceDir, "mkxp.json"), setOf("shim.rb"))

        assertTrue("auxiliary copy failure must return Result.failure, never throw uncaught", result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    fun `Sprint53_1 - auxiliary promotion failure leaves the workspace mkxp-json completely untouched`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-aux-fail-config-untouched")
        val originalMkxpContent = """{"original": "untouched"}"""
        val workspaceMkxpJson = File(workspaceDir, "mkxp.json")
        workspaceMkxpJson.writeText(originalMkxpContent)

        val tempOverlayDir = tempFolder.newFolder("temp-overlay-aux-fail-2")
        File(tempOverlayDir, "mkxp.json").writeText(realisticMkxpJson)
        File(tempOverlayDir, "shim.rb").writeText("new shim content")
        blockPathWithNonEmptyDirectory(File(workspaceDir, "shim.rb"))

        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(WorkspaceMetadata("g", "c", workspaceDir.absolutePath, "now", "now", 0, 0))),
            overlayService
        )
        preparer.promoteGeneratedOverlay(tempOverlayDir, workspaceDir, workspaceMkxpJson, setOf("shim.rb"))

        assertEquals(
            "auxiliary staging happens BEFORE config commit — a failure there must leave the prior, coherent mkxp.json completely unchanged",
            originalMkxpContent,
            workspaceMkxpJson.readText()
        )
    }

    @Test
    fun `Sprint53_1 - config commit failure returns Failed, not Ready, and does not silently proceed`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-config-promotion-fail")
        // mkxp.json's own destination path is blocked by a non-empty
        // directory — readText()/copyTo() against it will fail for real.
        val workspaceMkxpJsonPath = File(workspaceDir, "mkxp.json")
        blockPathWithNonEmptyDirectory(workspaceMkxpJsonPath)

        val tempOverlayDir = tempFolder.newFolder("temp-overlay-config-fail")
        File(tempOverlayDir, "mkxp.json").writeText(realisticMkxpJson)
        // No auxiliary files this time — isolates the failure to the config-commit step itself.

        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(WorkspaceMetadata("g", "c", workspaceDir.absolutePath, "now", "now", 0, 0))),
            overlayService
        )
        val result = preparer.promoteGeneratedOverlay(tempOverlayDir, workspaceDir, workspaceMkxpJsonPath, emptySet())

        assertTrue("config commit failure must return Result.failure, never throw uncaught, never silently succeed", result.isFailure)
    }

    @Test
    fun `Sprint53_1 - missing promised auxiliary file is caught before any real copy is attempted`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-missing-promised-aux")
        val tempOverlayDir = tempFolder.newFolder("temp-overlay-missing-aux")
        File(tempOverlayDir, "mkxp.json").writeText(realisticMkxpJson)
        // Deliberately do NOT create "promised-but-missing.rb" in tempOverlayDir.

        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(WorkspaceMetadata("g", "c", workspaceDir.absolutePath, "now", "now", 0, 0))),
            overlayService
        )
        val result = preparer.promoteGeneratedOverlay(tempOverlayDir, workspaceDir, File(workspaceDir, "mkxp.json"), setOf("promised-but-missing.rb"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("missing") == true)
        // Nothing should have been written into workspaceDir at all — validated before any copy was attempted.
        assertFalse(File(workspaceDir, "promised-but-missing.rb").exists())
    }

    @Test
    fun `Sprint53_1 - a stale temp overlay directory from a previous interrupted run is cleaned before generating into it`() = runBlocking {
        val workspaceDir = tempFolder.newFolder("workspace-stale-temp-dir")
        File(workspaceDir, "mkxp.json").writeText(realisticMkxpJson)

        // Simulate leftovers from a previous interrupted run — a stale
        // file that has nothing to do with the current normal profile's
        // own real mitigation set.
        val tempOverlayDir = File(workspaceDir.parentFile, "${workspaceDir.name}-overlay-tmp")
        tempOverlayDir.mkdirs()
        File(tempOverlayDir, "stale-leftover-from-interrupted-run.rb").writeText("stale junk")

        val preparer = RuntimeLaunchPreparer(
            FakeRuntimeWorkspaceService(WorkspaceResult.Success(WorkspaceMetadata("g", "c", workspaceDir.absolutePath, "now", "now", 0, 0))),
            overlayService
        )
        val result = preparer.prepareLaunch(sampleRequest())

        assertTrue("a stale temp dir must never prevent a fresh attempt from succeeding normally", result is LaunchPreparationResult.Ready)
        // The stale file must be gone — either cleaned before generation (proving the deliberate pre-clean) or as part of the normal post-success cleanup either way; what matters is it's never left influencing anything.
        assertFalse(File(tempOverlayDir, "stale-leftover-from-interrupted-run.rb").exists())
    }
}

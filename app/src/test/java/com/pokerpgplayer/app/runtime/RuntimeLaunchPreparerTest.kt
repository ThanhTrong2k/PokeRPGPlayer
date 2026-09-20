package com.pokerpgplayer.app.runtime

import com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService
import com.pokerpgplayer.app.data.model.RuntimeConfigProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Sprint 53 (wiring) / Sprint 53.1 (failure-safe promotion) coverage for
 * [RuntimeLaunchPreparer] — pure JVM, no Android dependency, using a fake
 * [RuntimeWorkspaceService] rather than the real SAF-backed mirror.
 */
class RuntimeLaunchPreparerTest {

    private class FakeWorkspaceService(private val workspaceDir: File) : RuntimeWorkspaceService {
        var result: WorkspaceResult = WorkspaceResult.Success(
            WorkspaceMetadata(GAME_ID, "content://fake", workspaceDir.absolutePath, "t0", "t0", 1, 1, WorkspaceState.READY)
        )
        var throwOnPrepare: Throwable? = null

        override suspend fun prepareWorkspace(gameEntryId: String, folderUri: String): WorkspaceResult {
            throwOnPrepare?.let { throw it }
            return result
        }

        override suspend fun getWorkspaceMetadata(gameEntryId: String): WorkspaceMetadata? = null
        override suspend fun clearWorkspace(gameEntryId: String): Boolean = true

        companion object {
            const val GAME_ID = "550e8400-e29b-41d4-a716-446655440000"
        }
    }

    private fun sampleRequest(profile: RuntimeConfigProfile = RuntimeConfigProfile()) = GameLaunchRequest(
        gameId = FakeWorkspaceService.GAME_ID,
        folderUri = "content://fake",
        executableName = "Game.exe",
        detectedTitle = "Sample",
        configProfile = profile
    )

    private fun workspaceWithMkxpJson(text: String = "{}"): File {
        val dir = Files.createTempDirectory("prep-test").toFile()
        File(dir, "mkxp.json").writeText(text)
        return dir
    }

    @Test
    fun `workspace failure is surfaced as Failed without attempting config generation`() = runBlocking {
        val fake = FakeWorkspaceService(workspaceWithMkxpJson())
        fake.result = WorkspaceResult.Failed(WorkspaceError.InsufficientStorage)
        val preparer = RuntimeLaunchPreparer(fake)

        val result = preparer.prepareLaunch(sampleRequest())

        assertTrue(result is LaunchPreparationResult.Failed)
    }

    @Test
    fun `missing mkxp json in the resolved workspace is a structured failure`() = runBlocking {
        val emptyDir = Files.createTempDirectory("prep-test-empty").toFile()
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(emptyDir))

        val result = preparer.prepareLaunch(sampleRequest())

        assertTrue(result is LaunchPreparationResult.Failed)
    }

    @Test
    fun `production mitigations are always applied to a resolved workspace, even with an empty per-game profile`() = runBlocking {
        val workspaceDir = workspaceWithMkxpJson("{\"preloadScript\": []}")
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(workspaceDir))

        val result = preparer.prepareLaunch(sampleRequest(RuntimeConfigProfile()))

        assertTrue(result is LaunchPreparationResult.Ready)
        val finalText = File(workspaceDir, "mkxp.json").readText()
        assertTrue("expected zlib preload to be applied to every real Play", finalText.contains("pokerpg_preload_zlib.rb"))
        assertTrue(File(workspaceDir, "pokerpg_preload_zlib.rb").exists())
        assertTrue(File(workspaceDir, "sprint48-system-uptime-seconds-shim.rb").exists())
        assertTrue(File(workspaceDir, "sprint50-speed-control-shim.rb").exists())
    }

    @Test
    fun `a stale temp overlay directory from a previous interrupted attempt does not survive a new attempt`() = runBlocking {
        val workspaceDir = workspaceWithMkxpJson("{\"preloadScript\": []}")
        val staleTemp = File(workspaceDir.parentFile, ".overlay-tmp-${FakeWorkspaceService.GAME_ID}")
        staleTemp.mkdirs()
        File(staleTemp, "leftover-from-a-crash.rb").writeText("stale")
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(workspaceDir))

        val result = preparer.prepareLaunch(sampleRequest())

        assertTrue(result is LaunchPreparationResult.Ready)
        assertFalse("temp overlay dir must be cleaned up (both before generating and again in the finally block)", staleTemp.exists())
    }

    /** A workspace directory named exactly as the real gameId, matching production's real `<runtime-workspace>/<gameId>/` layout — needed so the LEGACY temp overlay path (`<workspaceDir.name>-overlay-tmp`) below is derived exactly the way a real device would compute it. */
    private fun workspaceNamedAsGameId(text: String = "{\"preloadScript\": []}"): File {
        val parent = Files.createTempDirectory("runtime-workspace-root").toFile()
        val dir = File(parent, FakeWorkspaceService.GAME_ID).apply { mkdirs() }
        File(dir, "mkxp.json").writeText(text)
        return dir
    }

    @Test
    fun `a legacy Sprint53_1 temp overlay directory from before the Sprint53_2 rename is also cleaned before generating`() = runBlocking {
        val workspaceDir = workspaceNamedAsGameId()
        val legacyStaleTemp = File(workspaceDir.parentFile, "${workspaceDir.name}-overlay-tmp")
        legacyStaleTemp.mkdirs()
        File(legacyStaleTemp, "leftover-from-before-53-2.rb").writeText("stale legacy")
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(workspaceDir))

        val result = preparer.prepareLaunch(sampleRequest())

        assertTrue(result is LaunchPreparationResult.Ready)
        assertFalse(
            "the legacy Sprint 53.1 temp overlay directory must be cleaned even though Sprint 53.2 renamed the current one",
            legacyStaleTemp.exists()
        )
    }

    @Test
    fun `cleaning the legacy temp overlay directory never touches an unrelated sibling directory`() = runBlocking {
        val workspaceDir = workspaceNamedAsGameId()
        val unrelatedSibling = File(workspaceDir.parentFile, "some-other-directory-overlay-tmp-but-not-ours")
        unrelatedSibling.mkdirs()
        File(unrelatedSibling, "must-survive.txt").writeText("unrelated")
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(workspaceDir))

        val result = preparer.prepareLaunch(sampleRequest())

        assertTrue(result is LaunchPreparationResult.Ready)
        assertTrue("an unrelated sibling directory must never be deleted by legacy/current temp overlay cleanup", unrelatedSibling.exists())
        assertTrue(File(unrelatedSibling, "must-survive.txt").exists())
        // The real workspace directory itself must obviously survive too.
        assertTrue(workspaceDir.exists())
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException from workspace preparation propagates rather than becoming a Failed result`(): Unit = runBlocking {
        val fake = FakeWorkspaceService(Files.createTempDirectory("prep-cancel").toFile())
        fake.throwOnPrepare = CancellationException("cancelled")
        val preparer = RuntimeLaunchPreparer(fake)

        preparer.prepareLaunch(sampleRequest())
    }

    // ---- Sprint 53.1 blockers, preserved unchanged ----

    @Test
    fun `auxiliary promotion failure does not touch mkxp json and is reported as a structured failure`() {
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(Files.createTempDirectory("unused").toFile()))
        val tempDir = Files.createTempDirectory("temp-overlay").toFile()
        val workspaceDir = Files.createTempDirectory("workspace").toFile()
        File(tempDir, RuntimeConfigOverlayService.OVERLAY_CONFIG_FILE_NAME).writeText("{\"generated\":true}")
        // The promised auxiliary file is deliberately NOT present in
        // tempDir — simulates a generation step that reported an
        // auxiliary file in its result but never actually wrote it.
        val originalMkxpJson = File(workspaceDir, "mkxp.json").apply { writeText("{\"original\":true}") }

        val result = preparer.promoteGeneratedOverlay(tempDir, workspaceDir, originalMkxpJson, setOf("missing-aux.rb"))

        assertTrue(result.isFailure)
        assertEquals("{\"original\":true}", originalMkxpJson.readText())
    }

    @Test
    fun `config commit failure restores the previous mkxp json content`() {
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(Files.createTempDirectory("unused").toFile()))
        val tempDir = Files.createTempDirectory("temp-overlay").toFile()
        val workspaceDir = Files.createTempDirectory("workspace").toFile()
        // A directory where the generated mkxp.json should be — forces
        // the final File.copyTo commit to fail for real, no mocking.
        File(tempDir, RuntimeConfigOverlayService.OVERLAY_CONFIG_FILE_NAME).mkdirs()
        val originalMkxpJson = File(workspaceDir, "mkxp.json").apply { writeText("{\"original\":true}") }

        val result = preparer.promoteGeneratedOverlay(tempDir, workspaceDir, originalMkxpJson, emptySet())

        assertTrue(result.isFailure)
        assertEquals("{\"original\":true}", originalMkxpJson.readText())
    }

    @Test
    fun `a successful promotion stages auxiliary files before committing mkxp json`() {
        val preparer = RuntimeLaunchPreparer(FakeWorkspaceService(Files.createTempDirectory("unused").toFile()))
        val tempDir = Files.createTempDirectory("temp-overlay").toFile()
        val workspaceDir = Files.createTempDirectory("workspace").toFile()
        File(tempDir, RuntimeConfigOverlayService.OVERLAY_CONFIG_FILE_NAME).writeText("{\"generated\":true}")
        File(tempDir, "aux.rb").writeText("require 'zlib'")
        File(workspaceDir, "mkxp.json").writeText("{\"original\":true}")

        val result = preparer.promoteGeneratedOverlay(tempDir, workspaceDir, mkxpJsonFile = File(workspaceDir, "mkxp.json"), promisedAuxiliaryFileNames = setOf("aux.rb"))

        assertTrue(result.isSuccess)
        assertEquals("{\"generated\":true}", File(workspaceDir, "mkxp.json").readText())
        assertEquals("require 'zlib'", File(workspaceDir, "aux.rb").readText())
    }
}

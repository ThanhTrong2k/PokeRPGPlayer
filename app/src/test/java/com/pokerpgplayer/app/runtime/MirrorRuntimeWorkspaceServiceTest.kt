package com.pokerpgplayer.app.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sprint 53.2 — the persistent-workspace/staging/promotion/single-flight
 * lifecycle in [MirrorRuntimeWorkspaceService], exercised with a
 * [FakeSourceMirror] rather than real SAF/DocumentFile access. This is
 * exactly why [MirrorRuntimeWorkspaceService]'s primary constructor takes
 * a plain [File] + [SourceMirror] — this project's `build.gradle.kts` has
 * no Robolectric/Mockito, so there is no other way to unit test this
 * class's Android-adjacent logic in a plain JVM test.
 */
class MirrorRuntimeWorkspaceServiceTest {

    /** In-memory fake standing in for [RealSafSourceMirror]. */
    private class FakeSourceMirror(
        private val files: Map<String, String> = mapOf(
            "mkxp.json" to "{}",
            "Data/Scripts.rxdata" to "scripts-content"
        )
    ) : SourceMirror {
        var validateResult: Boolean = true
        var failWith: WorkspaceError? = null
        var throwCancellationAfterFiles: Int? = null
        var artificialDelayMillisPerFile: Long = 0L

        val validateCallCount = AtomicInteger(0)
        val mirrorCallCount = AtomicInteger(0)
        val physicalCopyCompletedCount = AtomicInteger(0)

        override fun validateSource(folderUri: String): Boolean {
            validateCallCount.incrementAndGet()
            return validateResult
        }

        override fun mirrorInto(folderUri: String, destDir: File, onFileCopied: (fileCount: Int, totalBytes: Long) -> Unit) {
            mirrorCallCount.incrementAndGet()
            failWith?.let { throw WorkspaceMirrorException(it) }

            var count = 0
            var bytes = 0L
            for ((relativePath, content) in files) {
                if (artificialDelayMillisPerFile > 0) Thread.sleep(artificialDelayMillisPerFile)
                count++
                if (throwCancellationAfterFiles != null && count > throwCancellationAfterFiles!!) {
                    throw CancellationException("simulated cancellation mid-copy")
                }
                val dest = File(destDir, relativePath)
                dest.parentFile?.mkdirs()
                dest.writeText(content)
                bytes += content.length
                onFileCopied(count, bytes)
            }
            physicalCopyCompletedCount.incrementAndGet()
        }
    }

    private fun newService(mirror: SourceMirror = FakeSourceMirror()): Pair<MirrorRuntimeWorkspaceService, File> {
        val filesDir = Files.createTempDirectory("mirror-test").toFile()
        return MirrorRuntimeWorkspaceService(filesDir, mirror) to filesDir
    }

    private val gameId = "550e8400-e29b-41d4-a716-446655440000"
    private val folderUri = "content://fake/tree/document/fake%3Agame"

    // ---- 1. first Play with no workspace ----

    @Test
    fun `first prepare with no existing workspace stages then promotes to READY`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue(result is WorkspaceResult.Success)
        val metadata = (result as WorkspaceResult.Success).metadata
        assertEquals(WorkspaceState.READY, metadata.state)
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        assertTrue(finalDir.exists())
        assertTrue(File(finalDir, "mkxp.json").exists())
        assertTrue(File(finalDir, "Data/Scripts.rxdata").exists())
        // No staging directory left behind after a successful promotion.
        assertTrue(WorkspacePathResolver.rootDir(filesDir).listFiles()!!.none { it.name.startsWith(".staging-") })
    }

    // ---- 2 & 13. second Play with READY workspace: no full source copy, same workspace reused ----

    @Test
    fun `second prepare with a READY workspace reuses it without copying again`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, _) = newService(mirror)

        val first = service.prepareWorkspace(gameId, folderUri) as WorkspaceResult.Success
        val second = service.prepareWorkspace(gameId, folderUri) as WorkspaceResult.Success

        assertEquals(1, mirror.mirrorCallCount.get())
        assertEquals(first.metadata.workspacePath, second.metadata.workspacePath)
        assertEquals(WorkspaceState.READY, second.metadata.state)
    }

    @Test
    fun `many repeated prepare calls after READY never trigger another physical copy`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, _) = newService(mirror)

        service.prepareWorkspace(gameId, folderUri)
        repeat(5) { service.prepareWorkspace(gameId, folderUri) }

        assertEquals(1, mirror.mirrorCallCount.get())
    }

    // ---- 3 & 11. cancellation during copy propagates; workspace not marked READY ----

    @Test(expected = CancellationException::class)
    fun `cancellation during copy propagates rather than being converted to Failed`(): Unit = runBlocking {
        val mirror = FakeSourceMirror().apply { throwCancellationAfterFiles = 0 }
        val (service, _) = newService(mirror)

        service.prepareWorkspace(gameId, folderUri)
    }

    @Test
    fun `a cancelled preparation leaves no READY workspace and cleans its own staging directory`() = runBlocking {
        val mirror = FakeSourceMirror().apply { throwCancellationAfterFiles = 0 }
        val (service, filesDir) = newService(mirror)

        try {
            service.prepareWorkspace(gameId, folderUri)
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }

        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        assertFalse(finalDir.exists())
        assertTrue(
            "no staging directory should remain after cancellation cleanup",
            WorkspacePathResolver.rootDir(filesDir).listFiles()?.none { it.name.startsWith(".staging-") } ?: true
        )

        // The metadata lookup itself must never throw and must not report READY.
        val metadataAfter = service.getWorkspaceMetadata(gameId)
        assertTrue(metadataAfter == null || metadataAfter.state != WorkspaceState.READY)
    }

    // ---- 4. retry after interrupted preparation succeeds ----

    @Test
    fun `retry after a copy failure succeeds and produces a READY workspace`() = runBlocking {
        val mirror = FakeSourceMirror().apply { failWith = WorkspaceError.CopyFailure("Data/Scripts.rxdata", "boom") }
        val (service, filesDir) = newService(mirror)

        val failedResult = service.prepareWorkspace(gameId, folderUri)
        assertTrue(failedResult is WorkspaceResult.Failed)

        mirror.failWith = null
        val retryResult = service.prepareWorkspace(gameId, folderUri)

        assertTrue(retryResult is WorkspaceResult.Success)
        assertEquals(WorkspaceState.READY, (retryResult as WorkspaceResult.Success).metadata.state)
        assertTrue(WorkspacePathResolver.workspaceDir(filesDir, gameId)!!.exists())
    }

    // ---- 5. two concurrent prepare requests for the same gameId: only one physical copy ----

    @Test
    fun `two concurrent prepare calls for the same gameId perform only one physical copy`() = runBlocking {
        val mirror = FakeSourceMirror().apply { artificialDelayMillisPerFile = 40 }
        val (service, _) = newService(mirror)

        val first = async(Dispatchers.Default) { service.prepareWorkspace(gameId, folderUri) }
        val second = async(Dispatchers.Default) { service.prepareWorkspace(gameId, folderUri) }

        val results = listOf(first.await(), second.await())

        assertEquals(1, mirror.mirrorCallCount.get())
        assertTrue(results.all { it is WorkspaceResult.Success })
        val paths = results.map { (it as WorkspaceResult.Success).metadata.workspacePath }.distinct()
        assertEquals(1, paths.size)
    }

    // ---- 6. stale staging directory cleaned deterministically ----

    @Test
    fun `a stale staging directory for this gameId is cleaned before a new preparation attempt`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        val staleStaging = WorkspacePathResolver.stagingDir(filesDir, gameId, "stale-token")!!
        staleStaging.mkdirs()
        File(staleStaging, "leftover.txt").writeText("leftover")
        assertTrue(staleStaging.exists())

        service.prepareWorkspace(gameId, folderUri)

        assertFalse(staleStaging.exists())
    }

    @Test
    fun `a stale staging directory belonging to a different gameId is left untouched`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)
        val otherGameId = "11111111-1111-1111-1111-111111111111"

        val otherStaging = WorkspacePathResolver.stagingDir(filesDir, otherGameId, "token")!!
        otherStaging.mkdirs()

        service.prepareWorkspace(gameId, folderUri)

        assertTrue("a different game's staging directory must never be touched by this game's preparation", otherStaging.exists())
    }

    // ---- 7. existing READY workspace + failed attempted refresh preserves the previous READY workspace ----

    @Test
    fun `a failed forced refresh preserves the previous READY workspace and its registry record`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        val originalReady = service.prepareWorkspace(gameId, folderUri) as WorkspaceResult.Success
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        val markerBeforeRefresh = File(finalDir, "Data/Scripts.rxdata").readText()

        mirror.failWith = WorkspaceError.CopyFailure("Data/Scripts.rxdata", "refresh boom")
        val refreshResult = service.forceRefreshWorkspace(gameId, folderUri)

        assertTrue(refreshResult is WorkspaceResult.Failed)
        assertEquals(originalReady.metadata, (refreshResult as WorkspaceResult.Failed).partialMetadata)

        // The registry must still report READY — never overwritten by the failed refresh attempt.
        val metadataAfter = service.getWorkspaceMetadata(gameId)
        assertNotNull(metadataAfter)
        assertEquals(WorkspaceState.READY, metadataAfter!!.state)
        assertEquals(originalReady.metadata, metadataAfter)

        // The previous workspace's own content on disk must be untouched.
        assertTrue(finalDir.exists())
        assertEquals(markerBeforeRefresh, File(finalDir, "Data/Scripts.rxdata").readText())
    }

    // ---- 8. directory already existing must not be falsely reported as a creation failure ----

    @Test
    fun `an already-existing empty final workspace directory with no registry entry is not treated as a failure`() = runBlocking {
        // Reproduces the real-device evidence directly: an orphaned
        // partial workspace directory exists on disk with no (or a
        // stale) registry entry — this must be safely swapped out during
        // promotion, never reported as "Could not create workspace
        // directory" the way the pre-53.2 mkdirs()==false bug did.
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        finalDir.mkdirs()
        File(finalDir, "orphaned-partial-file.tmp").writeText("6MB of nothing, pretend")

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue(result is WorkspaceResult.Success)
        assertTrue(finalDir.exists())
        assertTrue(File(finalDir, "mkxp.json").exists())
        // The orphaned leftover file must be gone — it was safely swapped
        // out and discarded as part of promotion, not merged with the
        // fresh copy.
        assertFalse(File(finalDir, "orphaned-partial-file.tmp").exists())
    }

    // ---- 9. copy failure yields a structured WorkspaceError ----

    @Test
    fun `a copy failure yields a structured WorkspaceError, not a generic exception`() = runBlocking {
        val mirror = FakeSourceMirror().apply { failWith = WorkspaceError.InsufficientStorage }
        val (service, filesDir) = newService(mirror)

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue(result is WorkspaceResult.Failed)
        assertEquals(WorkspaceError.InsufficientStorage, (result as WorkspaceResult.Failed).error)
        assertFalse(WorkspacePathResolver.workspaceDir(filesDir, gameId)!!.exists())
    }

    @Test
    fun `an invalid source folder yields a structured WorkspaceError without ever touching staging`() = runBlocking {
        val mirror = FakeSourceMirror().apply { validateResult = false }
        val (service, filesDir) = newService(mirror)

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue(result is WorkspaceResult.Failed)
        assertEquals(WorkspaceError.InvalidSourceFolder, (result as WorkspaceResult.Failed).error)
        assertEquals(0, mirror.mirrorCallCount.get())
    }

    // ---- 10. promotion failure yields a structured WorkspaceError, no false READY ----

    // Promotion failure is tested directly against promoteStagingToFinal
    // (made `internal` for exactly this reason) rather than via OS
    // permission bits: a permission-based test is unreliable in any
    // sandbox/CI environment running as root, since root bypasses POSIX
    // permission checks entirely. Deleting the staging directory out from
    // under a promotion attempt is deterministic on every OS and user.

    @Test
    fun `promoteStagingToFinal reports a structured error when the staging directory is missing, without creating a final directory`() {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        val missingStaging = WorkspacePathResolver.stagingDir(filesDir, gameId, "vanished")!!
        // Deliberately never created.

        val error = service.promoteStagingToFinal(missingStaging, finalDir, gameId)

        assertTrue(error is WorkspaceError.Unknown)
        assertFalse(finalDir.exists())
    }

    @Test
    fun `promoteStagingToFinal restores the previous workspace when the second swap rename fails`() {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        finalDir.mkdirs()
        File(finalDir, "previous-content.txt").writeText("the previous, working workspace")

        val staging = WorkspacePathResolver.stagingDir(filesDir, gameId, "will-vanish")!!
        staging.mkdirs()
        File(staging, "new-content.txt").writeText("a fresh copy that never gets to replace the old one")
        // Simulate the staging directory disappearing between validation
        // and the actual promotion rename (e.g. external interference) —
        // this deterministically fails the second rename in the
        // move-aside/swap sequence, regardless of OS or user.
        staging.deleteRecursively()

        val error = service.promoteStagingToFinal(staging, finalDir, gameId)

        assertTrue(error is WorkspaceError.Unknown)
        // The previous, working workspace must be restored exactly —
        // never left missing because a replacement attempt failed.
        assertTrue(finalDir.exists())
        assertEquals("the previous, working workspace", File(finalDir, "previous-content.txt").readText())
    }

    // ---- 14. different game IDs prepare independently ----

    @Test
    fun `different gameIds prepare independent workspaces without interfering with each other`() = runBlocking {
        val mirrorA = FakeSourceMirror(files = mapOf("mkxp.json" to "{\"game\":\"A\"}"))
        val mirrorB = FakeSourceMirror(files = mapOf("mkxp.json" to "{\"game\":\"B\"}"))
        val filesDir = Files.createTempDirectory("mirror-test").toFile()
        val serviceA = MirrorRuntimeWorkspaceService(filesDir, mirrorA)
        val serviceB = MirrorRuntimeWorkspaceService(filesDir, mirrorB)
        val gameIdB = "11111111-1111-1111-1111-111111111111"

        val resultA = serviceA.prepareWorkspace(gameId, folderUri) as WorkspaceResult.Success
        val resultB = serviceB.prepareWorkspace(gameIdB, folderUri) as WorkspaceResult.Success

        assertTrue(resultA.metadata.workspacePath != resultB.metadata.workspacePath)
        assertEquals("{\"game\":\"A\"}", File(resultA.metadata.workspacePath, "mkxp.json").readText())
        assertEquals("{\"game\":\"B\"}", File(resultB.metadata.workspacePath, "mkxp.json").readText())
    }

    // ---- Post-53.2 fix 1: registry I/O is globally serialized across DIFFERENT gameIds ----

    @Test
    fun `two concurrent prepare calls for DIFFERENT gameIds on one service instance both complete and both land READY in the registry`() = runBlocking {
        // Deliberately ONE shared MirrorRuntimeWorkspaceService instance —
        // and deliberately NOT two separate instances constructed over the
        // same filesDir. An earlier draft of this test used two separate
        // instances (one per game); that draft was itself invalid as a
        // regression test for the registryIoLock fix, because each
        // instance owns its OWN `registryIoLock = Any()`, so two separate
        // instances are never actually synchronized against each other no
        // matter what the fix does — it produced a real, reproducible
        // flake (~1-in-3) purely from that test-construction bug, not from
        // production code. The real app (AppContainer) only ever
        // constructs one MirrorRuntimeWorkspaceService for the whole app,
        // so a single shared instance is also the only realistic scenario
        // to test. Both prepares below run as real concurrent coroutines
        // on Dispatchers.Default against the SAME shared instance's
        // registryIoLock and the same filesDir/registry.json — exactly
        // the race ChatGPT's audit identified.
        val mirror = FakeSourceMirror().apply { artificialDelayMillisPerFile = 30 }
        val (service, filesDir) = newService(mirror)
        val gameIdB = "11111111-1111-1111-1111-111111111111"

        val jobA = async(Dispatchers.Default) { service.prepareWorkspace(gameId, folderUri) }
        val jobB = async(Dispatchers.Default) { service.prepareWorkspace(gameIdB, folderUri) }

        val resultA = jobA.await()
        val resultB = jobB.await()

        assertTrue(resultA is WorkspaceResult.Success)
        assertTrue(resultB is WorkspaceResult.Success)

        // Both physical copies genuinely ran independently — neither
        // gameId was skipped, and different gameIds are never serialized
        // against each other by the per-game Mutex (only the registry I/O
        // critical section is global).
        assertEquals(2, mirror.mirrorCallCount.get())
        assertEquals(2, mirror.physicalCopyCompletedCount.get())

        // The critical assertion: the shared registry.json contains BOTH
        // entries, both READY. A lost update from an unsynchronized
        // read-modify-write would show one of these as missing or stuck
        // at PREPARING (the state written before the copy, overwritten by
        // the other game's read-modify-write racing in between).
        val metaA = service.getWorkspaceMetadata(gameId)
        val metaB = service.getWorkspaceMetadata(gameIdB)
        assertNotNull("game A's registry entry must survive a concurrent write from game B", metaA)
        assertNotNull("game B's registry entry must survive a concurrent write from game A", metaB)
        assertEquals(WorkspaceState.READY, metaA!!.state)
        assertEquals(WorkspaceState.READY, metaB!!.state)

        // No leftover temp file from a collided rename.
        assertFalse(WorkspacePathResolver.registryTempFile(filesDir).exists())
    }

    // ---- Post-53.2 fix 2: legacy/invalid registry state never fails open to READY ----

    @Test
    fun `a legacy registry entry with no state field does not take the fast reuse path and is safely re-prepared to explicit READY`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        // Simulate a pre-53.2 registry row: no "state" key at all, but a
        // real, non-empty final directory already on disk (exactly the
        // real-device evidence this fix targets).
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        finalDir.mkdirs()
        File(finalDir, "legacy-content.txt").writeText("pre-53.2 leftover")
        val rootDir = WorkspacePathResolver.rootDir(filesDir)
        rootDir.mkdirs()
        WorkspacePathResolver.registryFile(filesDir).writeText(
            """{"$gameId":{"sourceUri":"$folderUri","workspacePath":"${finalDir.absolutePath}","createdAt":"t0","updatedAt":"t0","fileCount":1,"totalSizeBytes":1}}"""
        )

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue(result is WorkspaceResult.Success)
        // A real mirror ran — the fast READY-reuse path must NOT have
        // been taken for a legacy row with no state field.
        assertEquals(1, mirror.mirrorCallCount.get())
        assertTrue(File(finalDir, "mkxp.json").exists())
        // The legacy leftover was safely swapped out, not merged with it.
        assertFalse(File(finalDir, "legacy-content.txt").exists())
        val metadataAfter = service.getWorkspaceMetadata(gameId)
        assertEquals(WorkspaceState.READY, metadataAfter!!.state)
    }

    @Test
    fun `a registry entry with an unknown state value is never interpreted as READY`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        finalDir.mkdirs()
        val rootDir = WorkspacePathResolver.rootDir(filesDir)
        rootDir.mkdirs()
        WorkspacePathResolver.registryFile(filesDir).writeText(
            """{"$gameId":{"sourceUri":"$folderUri","workspacePath":"${finalDir.absolutePath}","createdAt":"t0","updatedAt":"t0","fileCount":0,"totalSizeBytes":0,"state":"TOTALLY_BOGUS_VALUE"}}"""
        )

        val metadataBefore = service.getWorkspaceMetadata(gameId)
        assertNotNull(metadataBefore)
        assertTrue("an unparseable state value must never deserialize as READY", metadataBefore!!.state != WorkspaceState.READY)

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue(result is WorkspaceResult.Success)
        assertEquals(1, mirror.mirrorCallCount.get())
        assertEquals(WorkspaceState.READY, service.getWorkspaceMetadata(gameId)!!.state)
    }

    @Test
    fun `an explicit READY registry entry with a matching sourceUri still takes the fast reuse path`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, _) = newService(mirror)

        service.prepareWorkspace(gameId, folderUri)
        service.prepareWorkspace(gameId, folderUri)

        assertEquals(1, mirror.mirrorCallCount.get())
    }

    // ---- Post-53.2 fix 3: sourceUri mismatch invalidates an otherwise-READY workspace ----

    @Test
    fun `a READY workspace recorded against a different sourceUri is not reused and is safely replaced`() = runBlocking {
        val mirror = FakeSourceMirror(files = mapOf("mkxp.json" to "{\"from\":\"old-source\"}"))
        val (service, filesDir) = newService(mirror)

        val originalUri = folderUri
        val newUri = "content://fake/tree/document/fake%3Agame-moved"

        val first = service.prepareWorkspace(gameId, originalUri) as WorkspaceResult.Success
        assertEquals(WorkspaceState.READY, first.metadata.state)
        assertEquals(1, mirror.mirrorCallCount.get())

        // A second SourceMirror standing in for the new location.
        val mirrorAtNewUri = FakeSourceMirror(files = mapOf("mkxp.json" to "{\"from\":\"new-source\"}"))
        val serviceSameFilesDir = MirrorRuntimeWorkspaceService(filesDir, mirrorAtNewUri)

        val second = serviceSameFilesDir.prepareWorkspace(gameId, newUri)

        assertTrue(second is WorkspaceResult.Success)
        // A real mirror ran against the NEW source — the old READY record
        // (from a different sourceUri) was not trusted for reuse.
        assertEquals(1, mirrorAtNewUri.mirrorCallCount.get())
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, gameId)!!
        assertEquals("{\"from\":\"new-source\"}", File(finalDir, "mkxp.json").readText())

        val metadataAfter = serviceSameFilesDir.getWorkspaceMetadata(gameId)
        assertEquals(WorkspaceState.READY, metadataAfter!!.state)
        assertEquals(newUri, metadataAfter.sourceUri)
    }

    // ---- clearWorkspace also removes stale staging for that game ----

    @Test
    fun `clearWorkspace removes the final workspace, its registry entry, and any stale staging for that game`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        service.prepareWorkspace(gameId, folderUri)
        val stray = WorkspacePathResolver.stagingDir(filesDir, gameId, "stray")!!
        stray.mkdirs()

        val cleared = service.clearWorkspace(gameId)

        assertTrue(cleared)
        assertNull(service.getWorkspaceMetadata(gameId))
        assertFalse(WorkspacePathResolver.workspaceDir(filesDir, gameId)!!.exists())
        assertFalse(stray.exists())
    }

    // ---- Sprint53.2 RegistryJVM FINAL integrity correction: registry.json.tmp bounded check ----

    @Test
    fun `a stale registry json tmp left over from a previous interrupted write does not corrupt or block the next commit`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        // Simulate a crash between a previous writeText() and its
        // renameTo(): a leftover registry.json.tmp with unrelated/garbage
        // content, and no real registry.json yet at all.
        val rootDir = WorkspacePathResolver.rootDir(filesDir)
        rootDir.mkdirs()
        WorkspacePathResolver.registryTempFile(filesDir).writeText("garbage-from-a-crashed-previous-write")

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue("a stale temp file must never corrupt or block a fresh commit", result is WorkspaceResult.Success)
        val metadata = service.getWorkspaceMetadata(gameId)
        assertNotNull(metadata)
        assertEquals(WorkspaceState.READY, metadata!!.state)
        // The stale temp was fully consumed/overwritten by this commit —
        // nothing of the old garbage content survives it.
        assertFalse(WorkspacePathResolver.registryTempFile(filesDir).exists())
    }

    @Test
    fun `a stale registry json tmp left as a directory from a previous crash is cleaned rather than crashing the next commit`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        // A more pathological leftover: a directory sitting at the temp
        // path instead of a plain file — writeText() would throw against
        // this if it were not cleaned up defensively first.
        val tempFile = WorkspacePathResolver.registryTempFile(filesDir)
        tempFile.mkdirs()
        File(tempFile, "leftover.txt").writeText("stale")

        val result = service.prepareWorkspace(gameId, folderUri)

        assertTrue(result is WorkspaceResult.Success)
        assertEquals(WorkspaceState.READY, service.getWorkspaceMetadata(gameId)!!.state)
        assertFalse(WorkspacePathResolver.registryTempFile(filesDir).exists())
    }

    @Test
    fun `an existing valid registry is not lost when the temp commit rename fails`() = runBlocking {
        val mirror = FakeSourceMirror()
        val (service, filesDir) = newService(mirror)

        // Establish a real, valid READY entry for one game first.
        service.prepareWorkspace(gameId, folderUri)
        assertEquals(WorkspaceState.READY, service.getWorkspaceMetadata(gameId)!!.state)

        // Now force the NEXT commit's rename to fail by occupying
        // registry.json's own path with a non-empty directory —
        // File.renameTo(file) cannot replace a non-empty directory.
        val registryFile = WorkspacePathResolver.registryFile(filesDir)
        val previousRegistryText = registryFile.readText()
        registryFile.delete()
        registryFile.mkdirs()
        File(registryFile, "blocking-entry").writeText("x")

        val gameIdB = "660e8400-e29b-41d4-a716-446655440001"
        val resultB = service.prepareWorkspace(gameIdB, folderUri)

        // The commit for game B must surface as a failure...
        assertTrue(resultB is WorkspaceResult.Failed)
        // ...and must not leave its own tempFile behind afterward.
        assertFalse(WorkspacePathResolver.registryTempFile(filesDir).exists())

        // Restore the real registry.json exactly as it was before this
        // test forced the failure, and confirm game A's original entry
        // was never touched by the failed attempt to commit game B.
        registryFile.deleteRecursively()
        registryFile.writeText(previousRegistryText)
        assertEquals(WorkspaceState.READY, service.getWorkspaceMetadata(gameId)!!.state)
    }
}

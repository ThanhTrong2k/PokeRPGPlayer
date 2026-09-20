package com.pokerpgplayer.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Plain JUnit — no Android framework dependency anywhere in
 * [WorkspacePathResolver], so this runs for real (same discipline as
 * [GameLaunchRequestMapperTest]), not just static review.
 */
class WorkspacePathResolverTest {

    private val validUuid = "550e8400-e29b-41d4-a716-446655440000"

    // ---- UUID validation ----

    @Test
    fun `valid UUID is accepted`() {
        assertTrue(WorkspacePathResolver.isValidGameEntryId(validUuid))
    }

    @Test
    fun `path traversal attempt is rejected as an invalid gameEntryId`() {
        assertFalse(WorkspacePathResolver.isValidGameEntryId("../../../etc/passwd"))
    }

    @Test
    fun `empty string is rejected as an invalid gameEntryId`() {
        assertFalse(WorkspacePathResolver.isValidGameEntryId(""))
    }

    @Test
    fun `uuid-like string with extra trailing content is rejected`() {
        assertFalse(WorkspacePathResolver.isValidGameEntryId("$validUuid/../evil"))
    }

    // ---- Path derivation ----

    @Test
    fun `workspaceDir is nested under runtime-workspace and named by gameEntryId`() {
        val filesDir = File("/fake/files")
        val dir = WorkspacePathResolver.workspaceDir(filesDir, validUuid)
        assertEquals(File("/fake/files/runtime-workspace/$validUuid"), dir)
    }

    @Test
    fun `workspaceDir returns null for an invalid gameEntryId rather than constructing a wrong path`() {
        val filesDir = File("/fake/files")
        assertNull(WorkspacePathResolver.workspaceDir(filesDir, "not-a-uuid"))
    }

    @Test
    fun `registryFile sits directly under the runtime-workspace root, not inside any game folder`() {
        val filesDir = File("/fake/files")
        val registry = WorkspacePathResolver.registryFile(filesDir)
        assertEquals(File("/fake/files/runtime-workspace/registry.json"), registry)
    }

    // ---- Path safety (isSafeToDeleteAsWorkspace) — the critical clearWorkspace guard ----

    @Test
    fun `the real workspace directory is considered safe to delete`() {
        val tempRoot = Files.createTempDirectory("pokerpg-test-filesdir").toFile()
        try {
            val filesDir = tempRoot
            val workspaceDir = WorkspacePathResolver.workspaceDir(filesDir, validUuid)!!
            workspaceDir.mkdirs()
            assertTrue(WorkspacePathResolver.isSafeToDeleteAsWorkspace(workspaceDir, filesDir, validUuid))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `a path traversal attempt to escape the workspace root is rejected`() {
        val tempRoot = Files.createTempDirectory("pokerpg-test-filesdir").toFile()
        try {
            val filesDir = tempRoot
            // Simulates a caller trying to sneak "../../something-important"
            // in as if it were the workspace for validUuid.
            val maliciousPath = File(WorkspacePathResolver.rootDir(filesDir), "$validUuid/../../something-important")
            assertFalse(WorkspacePathResolver.isSafeToDeleteAsWorkspace(maliciousPath, filesDir, validUuid))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `a different game's workspace path is never considered safe for this gameEntryId`() {
        val tempRoot = Files.createTempDirectory("pokerpg-test-filesdir").toFile()
        try {
            val filesDir = tempRoot
            val otherUuid = "00000000-0000-0000-0000-000000000000"
            val otherWorkspace = WorkspacePathResolver.workspaceDir(filesDir, otherUuid)!!
            otherWorkspace.mkdirs()
            assertFalse(WorkspacePathResolver.isSafeToDeleteAsWorkspace(otherWorkspace, filesDir, validUuid))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `the runtime-workspace root itself is never safe to delete as if it were one game's workspace`() {
        val tempRoot = Files.createTempDirectory("pokerpg-test-filesdir").toFile()
        try {
            val filesDir = tempRoot
            val root = WorkspacePathResolver.rootDir(filesDir)
            root.mkdirs()
            assertFalse(WorkspacePathResolver.isSafeToDeleteAsWorkspace(root, filesDir, validUuid))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // ---- Storage sufficiency comparison ----

    @Test
    fun `hasEnoughSpace is true when estimate is under the usable amount`() {
        assertTrue(WorkspacePathResolver.hasEnoughSpace(estimatedBytes = 100L, usableBytes = 200L))
    }

    @Test
    fun `hasEnoughSpace is true at the exact boundary`() {
        assertTrue(WorkspacePathResolver.hasEnoughSpace(estimatedBytes = 100L, usableBytes = 100L))
    }

    @Test
    fun `hasEnoughSpace is false when estimate exceeds usable amount`() {
        assertFalse(WorkspacePathResolver.hasEnoughSpace(estimatedBytes = 201L, usableBytes = 200L))
    }

    // ---- Out-of-space message heuristic ----

    @Test
    fun `message mentioning ENOSPC is detected as out of space`() {
        assertTrue(WorkspacePathResolver.messageIndicatesOutOfSpace("write failed: ENOSPC (No space left on device)"))
    }

    @Test
    fun `message mentioning no space left is detected case-insensitively`() {
        assertTrue(WorkspacePathResolver.messageIndicatesOutOfSpace("No Space Left on device"))
    }

    @Test
    fun `unrelated IO error message is not misidentified as out of space`() {
        assertFalse(WorkspacePathResolver.messageIndicatesOutOfSpace("permission denied"))
    }

    @Test
    fun `null message is not misidentified as out of space`() {
        assertFalse(WorkspacePathResolver.messageIndicatesOutOfSpace(null))
    }

    // ---- Document name safety (Sprint 6 hotfix — Fix 1) ----

    @Test
    fun `an ordinary file name is safe`() {
        assertTrue(WorkspacePathResolver.isSafeDocumentName("Game.ini"))
    }

    @Test
    fun `an ordinary name with spaces and unicode is safe`() {
        assertTrue(WorkspacePathResolver.isSafeDocumentName("Pokémon Opalo.exe"))
    }

    @Test
    fun `null name is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName(null))
    }

    @Test
    fun `blank name is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName(""))
        assertFalse(WorkspacePathResolver.isSafeDocumentName("   "))
    }

    @Test
    fun `name equal to single dot is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName("."))
    }

    @Test
    fun `name equal to double dot is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName(".."))
    }

    @Test
    fun `name containing a forward slash is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName("evil/name.txt"))
    }

    @Test
    fun `name containing a backslash is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName("evil\\name.txt"))
    }

    @Test
    fun `name containing a path traversal attempt embedded in an otherwise normal-looking name is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName("../../etc/passwd"))
    }

    @Test
    fun `name containing a null character is unsafe`() {
        assertFalse(WorkspacePathResolver.isSafeDocumentName("evil\u0000name.txt"))
    }

    @Test
    fun `a name that merely contains dots as part of a normal filename remains safe`() {
        // Sanity check that the "." / ".." rejection is an exact-match
        // rule, not a substring rule — "Save1.rxdata" must not be
        // penalized just because it contains a period.
        assertTrue(WorkspacePathResolver.isSafeDocumentName("Save1.rxdata"))
        assertTrue(WorkspacePathResolver.isSafeDocumentName("archive.tar.gz"))
    }

    // ---- Sprint 53.2: staging / promotion-backup path helpers ----

    @Test
    fun `stagingDir is scoped directly under the runtime-workspace root and includes both the gameId and the token`() {
        val filesDir = Files.createTempDirectory("wpr-test").toFile()
        val dir = WorkspacePathResolver.stagingDir(filesDir, validUuid, "token-1")
        assertEquals(WorkspacePathResolver.rootDir(filesDir), dir?.parentFile)
        assertTrue(dir!!.name.contains(validUuid))
        assertTrue(dir.name.contains("token-1"))
    }

    @Test
    fun `stagingDir returns null for an invalid gameEntryId`() {
        val filesDir = Files.createTempDirectory("wpr-test").toFile()
        assertNull(WorkspacePathResolver.stagingDir(filesDir, "not-a-uuid", "token-1"))
    }

    @Test
    fun `stagingDir returns null for a blank or unsafe token`() {
        val filesDir = Files.createTempDirectory("wpr-test").toFile()
        assertNull(WorkspacePathResolver.stagingDir(filesDir, validUuid, ""))
        assertNull(WorkspacePathResolver.stagingDir(filesDir, validUuid, "../evil"))
    }

    @Test
    fun `two different tokens for the same gameId produce two different staging directories`() {
        val filesDir = Files.createTempDirectory("wpr-test").toFile()
        val dirA = WorkspacePathResolver.stagingDir(filesDir, validUuid, "token-a")
        val dirB = WorkspacePathResolver.stagingDir(filesDir, validUuid, "token-b")
        assertTrue(dirA != dirB)
    }

    @Test
    fun `isStagingDirNameFor matches only this gameId's own staging directory names`() {
        val stagingName = ".staging-$validUuid-token-1"
        assertTrue(WorkspacePathResolver.isStagingDirNameFor(stagingName, validUuid))
        assertFalse(WorkspacePathResolver.isStagingDirNameFor(stagingName, "11111111-1111-1111-1111-111111111111"))
        assertFalse(WorkspacePathResolver.isStagingDirNameFor("registry.json", validUuid))
    }

    @Test
    fun `isSafeToDeleteAsStaging accepts only a real staging directory directly under the workspace root`() {
        val filesDir = Files.createTempDirectory("wpr-test").toFile()
        val staging = WorkspacePathResolver.stagingDir(filesDir, validUuid, "token-1")!!
        staging.mkdirs()
        assertTrue(WorkspacePathResolver.isSafeToDeleteAsStaging(staging, filesDir, validUuid))
    }

    @Test
    fun `isSafeToDeleteAsStaging rejects the final workspace directory itself`() {
        val filesDir = Files.createTempDirectory("wpr-test").toFile()
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, validUuid)!!
        finalDir.mkdirs()
        assertFalse(WorkspacePathResolver.isSafeToDeleteAsStaging(finalDir, filesDir, validUuid))
    }

    @Test
    fun `backupDirForPromotion is scoped directly under the runtime-workspace root and is distinct from the final workspace dir`() {
        val filesDir = Files.createTempDirectory("wpr-test").toFile()
        val backup = WorkspacePathResolver.backupDirForPromotion(filesDir, validUuid)
        val finalDir = WorkspacePathResolver.workspaceDir(filesDir, validUuid)
        assertEquals(WorkspacePathResolver.rootDir(filesDir), backup?.parentFile)
        assertTrue(backup != finalDir)
    }
}

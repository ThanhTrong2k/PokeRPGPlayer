package com.pokerpgplayer.app.data.gameinspection

import org.junit.Assert.*
import org.junit.Test

class GameWorkspaceInspectorTest {

    // ==================== Original coverage, re-verified against the corrected type()-based logic ====================

    @Test
    fun `no Plugins directory results in pluginSystemDetected false and empty plugin list`() {
        val source = FakeGameFileSource(files = mapOf("Data/Scripts.rxdata" to "binary-placeholder"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertFalse(result.pluginSystemDetected)
        assertTrue(result.plugins.isEmpty())
    }

    @Test
    fun `empty top-level Plugins directory results in pluginSystemDetected true and empty plugin list`() {
        val source = FakeGameFileSource(existingDirectories = setOf("Plugins"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertTrue(result.pluginSystemDetected)
        assertTrue(result.plugins.isEmpty())
    }

    @Test
    fun `one plugin with explicit metadata file is CONFIRMED with parsed fields`() {
        val source = FakeGameFileSource(files = mapOf(
            "Plugins/CoolPlugin/metadata.txt" to "name: Cool Plugin\nversion: 1.2.0\nauthor: SomeDev"
        ))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        val plugin = result.plugins[0]
        assertEquals("Cool Plugin", plugin.displayName)
        assertEquals("1.2.0", plugin.version)
        assertEquals("SomeDev", plugin.author)
        assertEquals(EvidenceConfidence.CONFIRMED, plugin.confidence)
        assertEquals(DetectionMethod.EXPLICIT_METADATA_FILE, plugin.detectionMethod)
    }

    @Test
    fun `multiple plugins are all detected independently`() {
        val source = FakeGameFileSource(files = mapOf(
            "Plugins/PluginA/metadata.txt" to "name: A\nversion: 1.0",
            "Plugins/PluginB/metadata.txt" to "name: B\nversion: 2.0",
            "Plugins/LoosePlugin.rb" to "# Name: Loose\n# Version: 0.9\nputs 'hi'"
        ))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(3, result.plugins.size)
        val names = result.plugins.map { it.displayName }.toSet()
        assertEquals(setOf("A", "B", "Loose"), names)
    }

    @Test
    fun `malformed metadata file with no parseable fields degrades to folder-name-only, not a crash`() {
        val source = FakeGameFileSource(files = mapOf(
            "Plugins/BrokenPlugin/metadata.txt" to "this is not key value data at all just prose"
        ))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        val plugin = result.plugins[0]
        assertEquals(DetectionMethod.FOLDER_NAME_ONLY, plugin.detectionMethod)
        assertEquals(EvidenceConfidence.INFERRED, plugin.confidence)
        assertTrue(result.metadataWarnings.any { it.contains("malformed") })
    }

    @Test
    fun `an unrecognized directory with a custom-format file inside is still reported, lowest confidence`() {
        // Distinct from the "unrelated FILE directly in Plugins" case below — this is a real
        // DIRECTORY (a genuine plugin folder) whose own contents just aren't in a known format.
        val source = FakeGameFileSource(files = mapOf(
            "Plugins/WeirdCustomThing/some_custom_file.dat" to "unrecognized"
        ))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        val plugin = result.plugins[0]
        assertEquals("WeirdCustomThing", plugin.displayName)
        assertEquals(DetectionMethod.FOLDER_NAME_ONLY, plugin.detectionMethod)
        assertEquals(EvidenceConfidence.INFERRED, plugin.confidence)
    }

    @Test
    fun `oversized metadata file is truncated, not fully read, and flagged in warnings`() {
        val oversized = "name: BigPlugin\n" + "x".repeat(20_000)
        val source = FakeGameFileSource(files = mapOf("Plugins/BigPlugin/metadata.txt" to oversized))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        assertEquals("BigPlugin", result.plugins[0].displayName)
        assertTrue(result.metadataWarnings.any { it.contains("exceeds") })
    }

    @Test
    fun `a simulated read error on a metadata file degrades gracefully with a warning, no crash`() {
        val source = FakeGameFileSource(
            files = mapOf("Plugins/UnreadablePlugin/metadata.txt" to "name: X"),
            simulatedErrors = setOf("Plugins/UnreadablePlugin/metadata.txt")
        )
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        assertEquals(DetectionMethod.FOLDER_NAME_ONLY, result.plugins[0].detectionMethod)
        assertTrue(result.metadataWarnings.any { it.contains("Failed to read metadata") })
    }

    @Test
    fun `deterministic ordering across repeated calls`() {
        val source = FakeGameFileSource(files = mapOf(
            "Plugins/Zeta/metadata.txt" to "name: Zeta\nversion: 1.0",
            "Plugins/Alpha/metadata.txt" to "name: Alpha\nversion: 1.0",
            "Plugins/Mid/metadata.txt" to "name: Mid\nversion: 1.0"
        ))
        val order1 = GameWorkspaceInspector.inspect(source, "/root", "test-ws").plugins.map { it.id }
        val order2 = GameWorkspaceInspector.inspect(source, "/root", "test-ws").plugins.map { it.id }
        assertEquals(order1, order2)
        assertEquals(listOf("Alpha", "Mid", "Zeta"), order1)
    }

    @Test
    fun `ConfidencePolicy clamp never allows FOLDER_NAME_ONLY to be reported as CONFIRMED`() {
        assertEquals(EvidenceConfidence.INFERRED, ConfidencePolicy.clamp(DetectionMethod.FOLDER_NAME_ONLY, EvidenceConfidence.CONFIRMED))
    }

    @Test
    fun `ConfidencePolicy clamp never raises a lower requested confidence`() {
        assertEquals(EvidenceConfidence.UNKNOWN, ConfidencePolicy.clamp(DetectionMethod.EXPLICIT_METADATA_FILE, EvidenceConfidence.UNKNOWN))
    }

    @Test
    fun `explicit version file yields CONFIRMED with the real version string`() {
        val source = FakeGameFileSource(files = mapOf("version.txt" to "This game uses Pokemon Essentials v21.1"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals("21.1", result.essentialsVersionHint)
        assertEquals(EvidenceConfidence.CONFIRMED, result.essentialsVersionConfidence)
    }

    @Test
    fun `structural evidence alone yields INFERRED with no fabricated version number`() {
        val source = FakeGameFileSource(
            files = mapOf("Data/Scripts.rxdata" to "binary-placeholder"),
            existingDirectories = setOf("Plugins")
        )
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(EvidenceConfidence.INFERRED, result.essentialsVersionConfidence)
        assertNull("must never fabricate a specific version number from structural evidence alone", result.essentialsVersionHint)
    }

    @Test
    fun `no evidence at all yields UNKNOWN`() {
        val source = FakeGameFileSource(files = emptyMap())
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(EvidenceConfidence.UNKNOWN, result.essentialsVersionConfidence)
        assertNull(result.essentialsVersionHint)
    }

    // ==================== Blocker 1 — new required tests ====================

    @Test
    fun `Blocker1 - empty plugin SUBdirectory (not the top-level Plugins dir) is preserved as an inferred candidate`() {
        val source = FakeGameFileSource(
            existingDirectories = setOf("Plugins", "Plugins/EmptyPluginFolder")
        )
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        val plugin = result.plugins[0]
        assertEquals("EmptyPluginFolder", plugin.displayName)
        assertEquals(DetectionMethod.FOLDER_NAME_ONLY, plugin.detectionMethod)
        assertEquals(EvidenceConfidence.INFERRED, plugin.confidence)
    }

    @Test
    fun `Blocker1 - unrelated Plugins-README-txt is NOT reported as a plugin`() {
        val source = FakeGameFileSource(files = mapOf("Plugins/README.txt" to "This folder contains plugins."))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertTrue(result.pluginSystemDetected)
        assertTrue("an unrelated README.txt must not be reported as a plugin", result.plugins.isEmpty())
        assertTrue(result.metadataWarnings.any { it.contains("Ignored unrelated file") && it.contains("README.txt") })
    }

    @Test
    fun `Blocker1 - unrelated Plugins-random-dat is NOT reported as a plugin`() {
        val source = FakeGameFileSource(files = mapOf("Plugins/random.dat" to "binary junk"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertTrue(result.plugins.isEmpty())
        assertTrue(result.metadataWarnings.any { it.contains("Ignored unrelated file") && it.contains("random.dat") })
    }

    @Test
    fun `Blocker1 - a directory and an unrelated file coexist in Plugins, only the directory is reported`() {
        val source = FakeGameFileSource(files = mapOf(
            "Plugins/RealPlugin/metadata.txt" to "name: Real\nversion: 1.0",
            "Plugins/README.txt" to "docs"
        ))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        assertEquals("Real", result.plugins[0].displayName)
    }

    @Test
    fun `Blocker1 - loose rb file with comment header is deterministically CONFIRMED`() {
        val source = FakeGameFileSource(files = mapOf(
            "Plugins/Standalone.rb" to "# Name: Standalone\n# Version: 3.0\n# Author: Dev\nclass Foo; end"
        ))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        val plugin = result.plugins[0]
        assertEquals("Standalone", plugin.displayName)
        assertEquals("3.0", plugin.version)
        assertEquals(DetectionMethod.COMMENT_HEADER_METADATA, plugin.detectionMethod)
        assertEquals(EvidenceConfidence.CONFIRMED, plugin.confidence)
    }

    @Test
    fun `Blocker1 - loose rb file with NO comment header is still a low-confidence candidate, not dropped`() {
        val source = FakeGameFileSource(files = mapOf("Plugins/NoHeader.rb" to "class Foo; end"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        assertEquals(DetectionMethod.FOLDER_NAME_ONLY, result.plugins[0].detectionMethod)
        assertEquals(EvidenceConfidence.INFERRED, result.plugins[0].confidence)
    }

    @Test
    fun `Blocker1 - inaccessible-unknown entry does not crash and is skipped with a warning`() {
        val source = FakeGameFileSource(
            existingDirectories = setOf("Plugins"),
            files = mapOf("Plugins/NormalPlugin/metadata.txt" to "name: Normal\nversion: 1.0"),
            unknownPaths = setOf("Plugins/WeirdSymlink")
        )
        // FakeGameFileSource.list() only enumerates from `files`/directories it knows about via
        // the files map; to exercise the UNKNOWN branch directly for a `Plugins/` entry, model it
        // by also making the entry appear in list() — achieved by giving it an empty-string file
        // so it is enumerable, while its own type() resolves to UNKNOWN via unknownPaths.
        val sourceWithEnumerableUnknown = FakeGameFileSource(
            files = mapOf(
                "Plugins/NormalPlugin/metadata.txt" to "name: Normal\nversion: 1.0",
                "Plugins/WeirdSymlink" to ""
            ),
            unknownPaths = setOf("Plugins/WeirdSymlink")
        )
        val result = GameWorkspaceInspector.inspect(sourceWithEnumerableUnknown, "/root", "test-ws")
        assertEquals(1, result.plugins.size)
        assertEquals("Normal", result.plugins[0].displayName)
        assertTrue(result.metadataWarnings.any { it.contains("Could not determine the type") && it.contains("WeirdSymlink") })
    }

    // ==================== Blocker 2 — new required tests ====================

    @Test
    fun `Blocker2 - generic Version string alone yields no Essentials version`() {
        val source = FakeGameFileSource(files = mapOf("version.txt" to "Version 1.4.2"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertNull("a bare 'Version X.Y.Z' with no mention of Essentials must never populate the Essentials version", result.essentialsVersionHint)
        assertNotEquals(EvidenceConfidence.CONFIRMED, result.essentialsVersionConfidence)
    }

    @Test
    fun `Blocker2 - Game Version string alone yields no Essentials version`() {
        val source = FakeGameFileSource(files = mapOf("README.txt" to "Game Version 2.0\nA fan-made adventure."))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertNull(result.essentialsVersionHint)
        assertNotEquals(EvidenceConfidence.CONFIRMED, result.essentialsVersionConfidence)
    }

    @Test
    fun `Blocker2 - Pokemon Essentials v21-1 phrasing is CONFIRMED as 21-1`() {
        val source = FakeGameFileSource(files = mapOf("version.txt" to "Pokémon Essentials v21.1"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals("21.1", result.essentialsVersionHint)
        assertEquals(EvidenceConfidence.CONFIRMED, result.essentialsVersionConfidence)
    }

    @Test
    fun `Blocker2 - Essentials version 20-1 phrasing is CONFIRMED as 20-1`() {
        val source = FakeGameFileSource(files = mapOf("version.txt" to "Essentials version 20.1"))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals("20.1", result.essentialsVersionHint)
        assertEquals(EvidenceConfidence.CONFIRMED, result.essentialsVersionConfidence)
    }

    @Test
    fun `Blocker2 - ambiguous compatibility prose (Requires Essentials) does not become falsely CONFIRMED`() {
        val source = FakeGameFileSource(files = mapOf("README.txt" to "This game requires Essentials 21.1 or higher to run."))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertNotEquals(EvidenceConfidence.CONFIRMED, result.essentialsVersionConfidence)
        assertNull("an ambiguous compatibility statement must not populate a specific version number", result.essentialsVersionHint)
        assertEquals(EvidenceConfidence.INFERRED, result.essentialsVersionConfidence)
    }

    @Test
    fun `Blocker2 - ambiguous compatibility prose (Compatible with Essentials) does not become falsely CONFIRMED`() {
        val source = FakeGameFileSource(files = mapOf("README.txt" to "Compatible with Essentials 21.1."))
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertNotEquals(EvidenceConfidence.CONFIRMED, result.essentialsVersionConfidence)
        assertNull(result.essentialsVersionHint)
    }

    @Test
    fun `Blocker2 - structural evidence still never fabricates a version number`() {
        val source = FakeGameFileSource(
            files = mapOf("Data/Scripts.rxdata" to "binary-placeholder"),
            existingDirectories = setOf("Plugins")
        )
        val result = GameWorkspaceInspector.inspect(source, "/root", "test-ws")
        assertEquals(EvidenceConfidence.INFERRED, result.essentialsVersionConfidence)
        assertNull(result.essentialsVersionHint)
    }
}

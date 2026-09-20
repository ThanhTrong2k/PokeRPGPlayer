package com.pokerpgplayer.app.data.config

import com.pokerpgplayer.app.data.model.KnownMitigationIds
import com.pokerpgplayer.app.data.model.OverlayStatus
import com.pokerpgplayer.app.data.model.RuntimeConfigProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 25 — plain JVM unit tests for [RuntimeConfigOverlayService].
 * No Android dependency of any kind is exercised by [RuntimeConfigOverlayService.generateOverlay]
 * itself, so these run as ordinary `testDebugUnitTest` tests, not
 * `androidTest` — matching this project's own preference (stated in the
 * approved Sprint 25 scope) for unit tests where the logic under test
 * doesn't actually require a real Android runtime.
 *
 * Every scenario below was first verified via an independent Python
 * simulation of the exact same algorithm before this file was written,
 * matching this project's own established practice of catching logic
 * errors before committing to the real implementation language.
 */
class RuntimeConfigOverlayServiceTest {

    private val service = RuntimeConfigOverlayService()

    /** A realistic mkxp.json shape with a `//` comment and a non-ASCII windowTitle — matching real, Ti-confirmed config shapes from Sprint 16–21. */
    private val configWithCommentsAndNonAsciiTitle = """{
    // this is a comment
    "windowTitle": "Pokémon Essentials v21.1",
    "vsync": true
}"""

    private fun profileWith(enabled: List<String>, disabled: List<String> = emptyList()): RuntimeConfigProfile =
        RuntimeConfigProfile(enabledMitigations = enabled, disabledMitigations = disabled)

    // --- Comments must not break generation ---

    @Test
    fun `zlib-preload insertion preserves existing comments`() {
        val result = service.generateOverlay(
            configWithCommentsAndNonAsciiTitle,
            profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD))
        )

        assertNotNull(result.overlayConfigText)
        assertTrue("Comment must be preserved", result.overlayConfigText!!.contains("// this is a comment"))
        assertTrue("preloadScript must be present", result.overlayConfigText.contains("\"preloadScript\": [\"${RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME}\"]"))
        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertEquals(listOf(KnownMitigationIds.ZLIB_PRELOAD), result.audit.lastAppliedMitigations)
    }

    @Test
    fun `zlib-preload generates the expected auxiliary script file with exact required content`() {
        val result = service.generateOverlay(
            configWithCommentsAndNonAsciiTitle,
            profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD))
        )

        assertTrue(result.generatedAuxiliaryFiles.containsKey(RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME))
        assertEquals("require 'zlib'\n", result.generatedAuxiliaryFiles[RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME])
    }

    // --- ascii-safe-window-title ---

    @Test
    fun `ascii-safe-window-title replaces non-ASCII title and preserves everything else`() {
        val result = service.generateOverlay(
            configWithCommentsAndNonAsciiTitle,
            profileWith(listOf(KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE))
        )

        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("\"windowTitle\": \"Pokemon Essentials v21.1\""))
        assertFalse("Non-ASCII character must be gone", result.overlayConfigText.contains("é"))
        assertTrue("Comment must still be preserved", result.overlayConfigText.contains("// this is a comment"))
        assertEquals(listOf(KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE), result.audit.lastAppliedMitigations)
    }

    @Test
    fun `ascii-safe-window-title skips cleanly when the title is already ASCII`() {
        val alreadyAsciiConfig = """{"windowTitle": "Pokemon Essentials v21.1"}"""
        val result = service.generateOverlay(alreadyAsciiConfig, profileWith(listOf(KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE)))

        assertEquals(OverlayStatus.NOT_GENERATED, result.overlayStatus)
        assertNull(result.overlayConfigText)
        assertTrue(KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE in result.skippedMitigations)
    }

    @Test
    fun `ascii-safe-window-title skips cleanly when windowTitle is entirely absent`() {
        val noTitleConfig = """{"vsync": true}"""
        val result = service.generateOverlay(noTitleConfig, profileWith(listOf(KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE)))

        assertEquals(OverlayStatus.NOT_GENERATED, result.overlayStatus)
        assertTrue(KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE in result.skippedMitigations)
    }

    // --- Sprint 30: aspect-fit-render ---

    @Test
    fun `aspect-fit-render inserts fixedAspectRatio true when the key is absent`() {
        val result = service.generateOverlay(configWithCommentsAndNonAsciiTitle, profileWith(listOf(KnownMitigationIds.ASPECT_FIT_RENDER)))

        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("\"fixedAspectRatio\": true"))
        assertTrue(KnownMitigationIds.ASPECT_FIT_RENDER in result.audit.lastAppliedMitigations)
    }

    @Test
    fun `aspect-fit-render replaces an existing fixedAspectRatio false with true`() {
        val configWithFalse = """{"fixedAspectRatio": false, "vsync": true}"""
        val result = service.generateOverlay(configWithFalse, profileWith(listOf(KnownMitigationIds.ASPECT_FIT_RENDER)))

        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("\"fixedAspectRatio\": true"))
        assertFalse(result.overlayConfigText.contains("false"))
    }

    @Test
    fun `aspect-fit-render records applied and GENERATED_TEST_ONLY when fixedAspectRatio is already true`() {
        // Sprint 30.2 fix: this was previously asserting the opposite
        // (NOT_GENERATED, skippedMitigations) — a real audit/contract
        // bug found via Ti's own real-device evidence, where the
        // Ti-provided mkxp.json already had fixedAspectRatio:true, and
        // the overlay correctly contained it, but appliedMitigations
        // incorrectly did not include aspect-fit-render, causing
        // RuntimeActivity to never launch at all.
        val configAlreadyTrue = """{"fixedAspectRatio": true, "vsync": true}"""
        val result = service.generateOverlay(configAlreadyTrue, profileWith(listOf(KnownMitigationIds.ASPECT_FIT_RENDER)))

        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertTrue(KnownMitigationIds.ASPECT_FIT_RENDER in result.audit.lastAppliedMitigations)
        assertTrue(KnownMitigationIds.ASPECT_FIT_RENDER !in result.skippedMitigations)
        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("\"fixedAspectRatio\": true"))
    }

    @Test
    fun `aspect-fit-render is idempotent when run twice against a config that already has fixedAspectRatio true`() {
        val configAlreadyTrue = """{"fixedAspectRatio": true, "vsync": true}"""
        val profile = profileWith(listOf(KnownMitigationIds.ASPECT_FIT_RENDER))

        val first = service.generateOverlay(configAlreadyTrue, profile)
        val second = service.generateOverlay(configAlreadyTrue, profile)

        assertEquals(first.overlayStatus, second.overlayStatus)
        assertEquals(first.overlayConfigText, second.overlayConfigText)
        assertTrue(KnownMitigationIds.ASPECT_FIT_RENDER in first.audit.lastAppliedMitigations)
        assertTrue(KnownMitigationIds.ASPECT_FIT_RENDER in second.audit.lastAppliedMitigations)
        val occurrences = first.overlayConfigText!!.split("\"fixedAspectRatio\"").size - 1
        assertEquals("Must not duplicate the key across repeated runs", 1, occurrences)
    }

    @Test
    fun `aspect-fit-render does not set fullscreen or any other unrelated key`() {
        val result = service.generateOverlay(configWithCommentsAndNonAsciiTitle, profileWith(listOf(KnownMitigationIds.ASPECT_FIT_RENDER)))

        assertNotNull(result.overlayConfigText)
        assertFalse("aspect-fit-render must not set fullscreen — that remains a separate, unconfirmed concern", result.overlayConfigText!!.contains("\"fullscreen\""))
    }

    // --- Both mitigations together ---

    @Test
    fun `both mitigations applied together produce a single, coherent overlay`() {
        val result = service.generateOverlay(
            configWithCommentsAndNonAsciiTitle,
            profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD, KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE))
        )

        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("preloadScript"))
        assertTrue(result.overlayConfigText.contains("\"windowTitle\": \"Pokemon Essentials v21.1\""))
        assertEquals(2, result.audit.lastAppliedMitigations.size)
        assertTrue(KnownMitigationIds.ZLIB_PRELOAD in result.audit.lastAppliedMitigations)
        assertTrue(KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE in result.audit.lastAppliedMitigations)
    }

    // --- Idempotence ---

    @Test
    fun `generating the overlay twice from the same original text does not duplicate preloadScript`() {
        val profile = profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD))
        val first = service.generateOverlay(configWithCommentsAndNonAsciiTitle, profile)
        val second = service.generateOverlay(configWithCommentsAndNonAsciiTitle, profile)

        assertEquals(first.overlayConfigText, second.overlayConfigText)
        val occurrences = first.overlayConfigText!!.split("\"preloadScript\"").size - 1
        assertEquals(1, occurrences)
    }

    @Test
    fun `zlib-preload appends to an existing preloadScript array rather than skipping it`() {
        val configWithExistingArrayPreload = """{
    "preloadScript": ["something_else.rb"],
    "vsync": true
}"""
        val result = service.generateOverlay(configWithExistingArrayPreload, profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertTrue(KnownMitigationIds.ZLIB_PRELOAD in result.audit.lastAppliedMitigations)
        assertNotNull(result.overlayConfigText)
        assertTrue("Existing entry must be preserved", result.overlayConfigText!!.contains("\"something_else.rb\""))
        assertTrue("Our own script must be appended", result.overlayConfigText.contains("\"${RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME}\""))
        val occurrences = result.overlayConfigText.split("\"preloadScript\"").size - 1
        assertEquals("Must not create a second preloadScript key", 1, occurrences)
    }

    @Test
    fun `zlib-preload converts an existing bare-string preloadScript into an array containing both`() {
        val configWithStringPreload = """{
    "preloadScript": "something_else.rb",
    "vsync": true
}"""
        val result = service.generateOverlay(configWithStringPreload, profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("\"something_else.rb\""))
        assertTrue(result.overlayConfigText.contains("\"${RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME}\""))
        assertTrue("Must have been converted to array form", result.overlayConfigText.contains("\"preloadScript\": ["))
    }

    @Test
    fun `zlib-preload is a no-op when our own script is already present in an existing array`() {
        val configAlreadyHasOurScript = """{
    "preloadScript": ["${RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME}"],
    "vsync": true
}"""
        val result = service.generateOverlay(configAlreadyHasOurScript, profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        assertTrue(KnownMitigationIds.ZLIB_PRELOAD in result.skippedMitigations)
        assertEquals(OverlayStatus.NOT_GENERATED, result.overlayStatus)
        val occurrences = configAlreadyHasOurScript.split(RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME).size - 1
        assertEquals(1, occurrences)
    }

    @Test
    fun `zlib-preload appending twice from the same original array-based config is idempotent`() {
        val configWithExistingArrayPreload = """{"preloadScript": ["something_else.rb"], "vsync": true}"""
        val profile = profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD))

        val first = service.generateOverlay(configWithExistingArrayPreload, profile)
        val second = service.generateOverlay(configWithExistingArrayPreload, profile)

        assertEquals(first.overlayConfigText, second.overlayConfigText)
        val occurrences = first.overlayConfigText!!.split(RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME).size - 1
        assertEquals(1, occurrences)
    }

    // --- Sprint 26 hardening: malformed config must fail cleanly, never crash, never claim success ---

    @Test
    fun `a config with no closing brace at all returns overlayStatus ERROR with a reason, not a crash`() {
        val malformedConfig = "{ \"windowTitle\": \"test\", \"vsync\": true "  // deliberately missing closing brace
        val result = service.generateOverlay(malformedConfig, profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        assertEquals(OverlayStatus.ERROR, result.overlayStatus)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.isNotBlank())
        assertNull(result.overlayConfigText)
    }

    @Test
    fun `an empty config text returns overlayStatus ERROR rather than throwing`() {
        val result = service.generateOverlay("", profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        assertEquals(OverlayStatus.ERROR, result.overlayStatus)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `original config text is never mutated even when generation fails`() {
        val malformedConfig = "{ \"windowTitle\": \"test\" "
        val beforeCall = malformedConfig
        service.generateOverlay(malformedConfig, profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        assertEquals(beforeCall, malformedConfig)
    }

    // --- No-mitigation profile ---

    @Test
    fun `no enabled mitigations produces overlayStatus NOT_GENERATED and no overlay text`() {
        val result = service.generateOverlay(configWithCommentsAndNonAsciiTitle, RuntimeConfigProfile())

        assertEquals(OverlayStatus.NOT_GENERATED, result.overlayStatus)
        assertNull(result.overlayConfigText)
        assertTrue(result.generatedAuxiliaryFiles.isEmpty())
    }

    @Test
    fun `a disabled mitigation is not applied even if it would otherwise be enabled`() {
        val profile = profileWith(
            enabled = listOf(KnownMitigationIds.ZLIB_PRELOAD),
            disabled = listOf(KnownMitigationIds.ZLIB_PRELOAD)
        )
        val result = service.generateOverlay(configWithCommentsAndNonAsciiTitle, profile)

        assertEquals(OverlayStatus.NOT_GENERATED, result.overlayStatus)
        assertNull(result.overlayConfigText)
    }

    // --- Original content is never mutated ---

    @Test
    fun `the original config text parameter itself is never mutated`() {
        val originalBeforeCall = configWithCommentsAndNonAsciiTitle
        service.generateOverlay(configWithCommentsAndNonAsciiTitle, profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        // Kotlin Strings are immutable, so this is really confirming the
        // *variable this test holds* is unaffected — the meaningful
        // guarantee (no File is ever opened for writing to the original
        // path) is exercised by the generateOverlayToDirectory tests
        // below instead, since generateOverlay itself has no file access
        // at all by construction.
        assertEquals(originalBeforeCall, configWithCommentsAndNonAsciiTitle)
    }

    // --- Audit hashes ---

    @Test
    fun `audit hashes are populated and differ between original and overlay`() {
        val result = service.generateOverlay(configWithCommentsAndNonAsciiTitle, profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)))

        assertNotNull(result.audit.originalConfigHash)
        assertNotNull(result.audit.overlayConfigHash)
        assertTrue(result.audit.originalConfigHash!!.isNotBlank())
        assertTrue(result.audit.overlayConfigHash!!.isNotBlank())
        assertFalse(result.audit.originalConfigHash == result.audit.overlayConfigHash)
    }

    @Test
    fun `original config hash is still populated even when no mitigation is applied`() {
        val result = service.generateOverlay(configWithCommentsAndNonAsciiTitle, RuntimeConfigProfile())

        assertNotNull(result.audit.originalConfigHash)
        assertNull(result.audit.overlayConfigHash)
    }

    // --- File-based wrapper: never writes back to the original path ---

    @Test
    fun `generateOverlayToDirectory reads the original file but never writes to its own path`() {
        val tempDir = java.nio.file.Files.createTempDirectory("sprint25-original-").toFile()
        val outputDir = java.nio.file.Files.createTempDirectory("sprint25-output-").toFile()
        try {
            val originalFile = java.io.File(tempDir, "mkxp.json")
            originalFile.writeText(configWithCommentsAndNonAsciiTitle)
            val originalHashBefore = originalFile.readText()

            val result = service.generateOverlayToDirectory(
                originalFile,
                profileWith(listOf(KnownMitigationIds.ZLIB_PRELOAD)),
                outputDir
            )

            assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
            assertEquals("Original file on disk must be byte-for-byte unchanged", originalHashBefore, originalFile.readText())

            val generatedOverlayFile = java.io.File(outputDir, RuntimeConfigOverlayService.OVERLAY_CONFIG_FILE_NAME)
            assertTrue("Overlay file must exist in the output directory", generatedOverlayFile.exists())
            assertTrue(generatedOverlayFile.readText().contains("preloadScript"))

            val generatedScriptFile = java.io.File(outputDir, RuntimeConfigOverlayService.ZLIB_PRELOAD_SCRIPT_FILE_NAME)
            assertTrue("Preload script file must exist in the output directory", generatedScriptFile.exists())
            assertEquals("require 'zlib'\n", generatedScriptFile.readText())
        } finally {
            tempDir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun `generateOverlayToDirectory writes nothing to the output directory when no mitigation applies`() {
        val tempDir = java.nio.file.Files.createTempDirectory("sprint25-original-").toFile()
        val outputDir = java.nio.file.Files.createTempDirectory("sprint25-output-").toFile()
        try {
            val originalFile = java.io.File(tempDir, "mkxp.json")
            originalFile.writeText(configWithCommentsAndNonAsciiTitle)

            val result = service.generateOverlayToDirectory(originalFile, RuntimeConfigProfile(), outputDir)

            assertEquals(OverlayStatus.NOT_GENERATED, result.overlayStatus)
            assertFalse(java.io.File(outputDir, RuntimeConfigOverlayService.OVERLAY_CONFIG_FILE_NAME).exists())
        } finally {
            tempDir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }
    @Test
    fun `sprint45-fps60-cap-diagnostic inserts both keys when absent`() {
        val config = """{"vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC))
        )

        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("\"syncToRefreshrate\": false"))
        assertTrue(result.overlayConfigText!!.contains("\"fixedFramerate\": 60"))
        assertTrue(result.overlayConfigText!!.contains("\"vsync\": true"))
    }

    @Test
    fun `sprint45-fps60-cap-diagnostic replaces existing wrong fixedFramerate and syncToRefreshrate`() {
        val config = """{"syncToRefreshrate": true, "fixedFramerate": 120, "vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC))
        )

        assertNotNull(result.overlayConfigText)
        assertTrue(result.overlayConfigText!!.contains("\"syncToRefreshrate\": false"))
        assertTrue(result.overlayConfigText!!.contains("\"fixedFramerate\": 60"))
        assertFalse(result.overlayConfigText!!.contains("120"))
    }

    @Test
    fun `sprint45-fps60-cap-diagnostic reports applied when already correct`() {
        val config = """{"syncToRefreshrate": false, "fixedFramerate": 60, "vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC))
        )

        assertEquals(OverlayStatus.GENERATED_TEST_ONLY, result.overlayStatus)
        assertTrue(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC in result.audit.lastAppliedMitigations)
    }

    @Test
    fun `sprint45-fps60-cap-diagnostic preserves existing preloadScript entries`() {
        val config = """{"preloadScript": ["pokerpg_preload_zlib.rb", "sprint41-input-diagnostic.rb", "sprint42-movement-path-diagnostic.rb", "sprint43-command-pipeline-diagnostic.rb", "sprint44-timebase-diagnostic.rb"], "vsync": true, "fixedFramerate": 120}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC))
        )

        assertNotNull(result.overlayConfigText)
        listOf(
            "pokerpg_preload_zlib.rb",
            "sprint41-input-diagnostic.rb",
            "sprint42-movement-path-diagnostic.rb",
            "sprint43-command-pipeline-diagnostic.rb",
            "sprint44-timebase-diagnostic.rb"
        ).forEach { scriptName ->
            assertTrue("Must preserve $scriptName", result.overlayConfigText!!.contains(scriptName))
        }
        assertTrue(result.overlayConfigText!!.contains("\"fixedFramerate\": 60"))
        assertFalse(result.overlayConfigText!!.contains("120"))
    }


    @Test
    fun `sprint45-fps60-cap-diagnostic uncomments a commented-out fixedFramerate line instead of leaving it inert`() {
        val config = """
            {"syncToRefreshrate": true,
            // "fixedFramerate": 60,
            "vsync": true}
        """.trimIndent()
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC))
        )

        assertNotNull(result.overlayConfigText)
        val overlay = result.overlayConfigText!!
        assertTrue("Must set syncToRefreshrate false", overlay.contains("\"syncToRefreshrate\": false"))
        assertTrue("Must have an ACTIVE fixedFramerate:60 line", overlay.contains("\"fixedFramerate\": 60,"))
        assertFalse("Must not leave the fixedFramerate line commented out", overlay.contains("// \"fixedFramerate\""))
    }

    @Test
    fun `sprint45-fps60-cap-diagnostic replaces an existing wrong fixedFramerate on a compact single line without duplicating key`() {
        val config = """{"syncToRefreshrate": true, "fixedFramerate": 120, "vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC))
        )

        assertNotNull(result.overlayConfigText)
        val overlay = result.overlayConfigText!!
        assertTrue(overlay.contains("\"fixedFramerate\": 60"))
        assertFalse(overlay.contains("120"))
        val occurrences = overlay.split("\"fixedFramerate\"").size - 1
        assertEquals(1, occurrences)
    }


    @Test
    fun `sprint48-system-uptime-seconds-shim prepends shim before existing preload scripts`() {
        val config = """{"preloadScript": ["pokerpg_preload_zlib.rb", "sprint44-timebase-diagnostic.rb"], "vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM))
        )

        assertNotNull(result.overlayConfigText)
        val overlay = result.overlayConfigText!!
        val shimIndex = overlay.indexOf("sprint48-system-uptime-seconds-shim.rb")
        val zlibIndex = overlay.indexOf("pokerpg_preload_zlib.rb")
        val sprint44Index = overlay.indexOf("sprint44-timebase-diagnostic.rb")

        assertTrue(shimIndex >= 0)
        assertTrue(zlibIndex > shimIndex)
        assertTrue(sprint44Index > shimIndex)
        assertTrue(overlay.contains("sprint48-system-uptime-seconds-shim.rb"))
    }

    @Test
    fun `sprint48-system-uptime-seconds-shim converts single preloadScript string to array with shim first`() {
        val config = """{"preloadScript": "pokerpg_preload_zlib.rb", "vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM))
        )

        assertNotNull(result.overlayConfigText)
        val overlay = result.overlayConfigText!!
        assertTrue(overlay.contains("\"preloadScript\": [\"sprint48-system-uptime-seconds-shim.rb\", \"pokerpg_preload_zlib.rb\"]"))
    }

    @Test
    fun `sprint48-system-uptime-seconds-shim is idempotent when already first`() {
        val config = """{"preloadScript": ["sprint48-system-uptime-seconds-shim.rb", "pokerpg_preload_zlib.rb"], "vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM))
        )

        assertNotNull(result.overlayConfigText)
        val overlay = result.overlayConfigText!!
        val occurrences = overlay.split("sprint48-system-uptime-seconds-shim.rb").size - 1
        assertEquals(1, occurrences)
    }


    @Test
    fun `normal diagnostic profile excludes Sprint41-44 diagnostics but includes Sprint48 shim`() {
        val mitigations = DiagnosticProfiles.normalDiagnosticMitigations()

        assertTrue(KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM in mitigations)
        assertTrue(KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC in mitigations)
        assertFalse(KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC in mitigations)
        assertFalse(KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC in mitigations)
        assertFalse(KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC in mitigations)
        assertFalse(KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC in mitigations)
    }

    @Test
    fun `verbose diagnostic profile is a strict superset of normal restoring Sprint41-44`() {
        val normal = DiagnosticProfiles.normalDiagnosticMitigations()
        val verbose = DiagnosticProfiles.verboseDiagnosticMitigations()

        assertTrue("verbose must contain everything normal does", normal.all { it in verbose })
        assertTrue(KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC in verbose)
        assertTrue(KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC in verbose)
        assertTrue(KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC in verbose)
        assertTrue(KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC in verbose)
        assertFalse("Sprint47 is deliberately excluded from this first Sprint49 merge", verbose.any { it.contains("sprint47") })
    }

    @Test
    fun `sprint48-system-uptime-seconds-shim prepends before Sprint41-44 preload entries preserving all of them`() {
        val config = """{"preloadScript": ["pokerpg_preload_zlib.rb", "sprint41-input-diagnostic.rb", "sprint42-movement-path-diagnostic.rb", "sprint43-command-pipeline-diagnostic.rb", "sprint44-timebase-diagnostic.rb"], "vsync": true}"""
        val result = service.generateOverlay(
            config,
            profileWith(listOf(KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM))
        )

        assertNotNull(result.overlayConfigText)
        val overlay = result.overlayConfigText!!
        val shimIndex = overlay.indexOf("sprint48-system-uptime-seconds-shim.rb")
        val zlibIndex = overlay.indexOf("pokerpg_preload_zlib.rb")

        assertTrue("shim must exist", shimIndex >= 0)
        assertTrue("zlib preload must exist", zlibIndex >= 0)
        assertTrue("shim must come first", shimIndex < zlibIndex)

        listOf(
            "pokerpg_preload_zlib.rb",
            "sprint41-input-diagnostic.rb",
            "sprint42-movement-path-diagnostic.rb",
            "sprint43-command-pipeline-diagnostic.rb",
            "sprint44-timebase-diagnostic.rb"
        ).forEach { scriptName ->
            assertTrue("Must preserve $scriptName", overlay.contains(scriptName))
        }
    }



    @Test
    fun `sprint50-speed-control inserts immediately after sprint48's own entry`() {
        val config = """{"preloadScript": ["sprint48-system-uptime-seconds-shim.rb", "pokerpg_preload_zlib.rb"]}"""
        val result = service.generateOverlay(config, profileWith(listOf(KnownMitigationIds.SPRINT50_SPEED_CONTROL)))

        assertNotNull(result.overlayConfigText)
        val overlay = result.overlayConfigText!!
        val i48 = overlay.indexOf("sprint48-system-uptime-seconds-shim.rb")
        val i50 = overlay.indexOf("sprint50-speed-control-shim.rb")
        val izlib = overlay.indexOf("pokerpg_preload_zlib.rb")

        assertTrue("Sprint48 shim must exist", i48 >= 0)
        assertTrue("Sprint50 shim must exist", i50 >= 0)
        assertTrue("zlib preload must exist", izlib >= 0)
        assertTrue("order must be 48, 50, zlib", i48 < i50 && i50 < izlib)
    }

    @Test
    fun `normal diagnostic profile includes sprint50 speed control by default`() {
        assertTrue(KnownMitigationIds.SPRINT50_SPEED_CONTROL in DiagnosticProfiles.normalDiagnosticMitigations())
    }

    @Test
    fun `generated speed shim script content contains anchored x1 x2 x3 speed values`() {
        val config = """{"vsync": true}"""
        val result = service.generateOverlay(config, profileWith(listOf(KnownMitigationIds.SPRINT50_SPEED_CONTROL)))

        val scriptContent = result.generatedAuxiliaryFiles["sprint50-speed-control-shim.rb"]
        assertNotNull("shim script content must be generated", scriptContent)
        assertTrue(scriptContent!!.contains("VALID_SPEEDS = [1, 2, 3]"))
        assertTrue(scriptContent.contains("DEFAULT_SPEED = 1"))
        assertTrue(scriptContent.contains("base_scaled_seconds"))
        assertTrue(scriptContent.contains("base_raw_seconds"))
        assertTrue(scriptContent.contains("scaled_uptime"))
        assertTrue(scriptContent.contains("change_speed"))
    }


}

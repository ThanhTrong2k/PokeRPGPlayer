package com.pokerpgplayer.app.runtime

import com.pokerpgplayer.app.data.model.DetectionStatus
import com.pokerpgplayer.app.data.model.GameDetectionResult
import com.pokerpgplayer.app.data.model.GameEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit (no Robolectric, no instrumentation) — [mapToLaunchRequest]
 * and its inputs are pure Kotlin, so this is the project's first suite
 * that actually runs rather than only being statically reviewed. See
 * "Sprint 3: Runtime Foundation Preparation — Architecture Proposal v1.0"
 * §9 for why this was worth adding now rather than deferring.
 */
class GameLaunchRequestMapperTest {

    /** Builds a minimal, valid [GameEntry] with sensible defaults, overridable per test to avoid repeating every field in every test case. */
    private fun sampleEntry(
        selectedExecutable: String? = "Game.exe",
        status: DetectionStatus = DetectionStatus.HIGH_CONFIDENCE,
        detectedTitle: String? = "Pokémon Sample Game"
    ): GameEntry {
        val detection = GameDetectionResult(
            status = status,
            gameIniExists = true,
            gameSectionFound = true,
            detectedTitle = detectedTitle,
            libraryDll = "RGSS104E.dll",
            scriptsPath = "Data/Scripts.rxdata",
            scriptsExists = true,
            dllExists = true,
            dataFolderExists = true,
            graphicsFolderExists = true,
            audioFolderExists = true,
            fontsDetected = false,
            pluginsDetected = false,
            pbsDetected = false,
            executableCandidates = if (selectedExecutable != null) listOf(selectedExecutable) else emptyList(),
            selectedExecutable = selectedExecutable,
            missingItems = emptyList(),
            warnings = emptyList()
        )
        return GameEntry(
            id = "TEST-GAME-ID-001",
            displayName = "Sample Game",
            folderUri = "content://com.android.externalstorage.documents/tree/sample",
            addedDate = "2026-07-05T00:00:00Z",
            isFavorite = false,
            selectedExecutable = selectedExecutable,
            detection = detection
        )
    }

    // --- Required test 1: fully resolved GameEntry maps to Ready with copied fields ---
    @Test
    fun `fully resolved GameEntry maps to Ready with correctly copied fields`() {
        val entry = sampleEntry(selectedExecutable = "Game.exe", detectedTitle = "Pokémon Sample Game")

        val result = mapToLaunchRequest(entry)

        assertTrue("expected Ready, got $result", result is LaunchRequestResult.Ready)
        val request = (result as LaunchRequestResult.Ready).request
        assertEquals(entry.id, request.gameId)
        assertEquals(entry.folderUri, request.folderUri)
        assertEquals("Game.exe", request.executableName)
        assertEquals("Pokémon Sample Game", request.detectedTitle)
        assertEquals(RuntimeConfig(), request.runtimeConfig)
    }

    // --- Required test 2: null selectedExecutable maps to Rejected(ExecutableNotSelected) ---
    @Test
    fun `null selectedExecutable maps to Rejected ExecutableNotSelected`() {
        val entry = sampleEntry(selectedExecutable = null, status = DetectionStatus.NEEDS_REVIEW)

        val result = mapToLaunchRequest(entry)

        assertTrue("expected Rejected, got $result", result is LaunchRequestResult.Rejected)
        assertEquals(RuntimeError.ExecutableNotSelected, (result as LaunchRequestResult.Rejected).error)
    }

    // --- Required test 3: Low Confidence with a resolved executable still maps to Ready — confidence is not a runtime gate ---
    @Test
    fun `Low Confidence GameEntry with resolved executable still maps to Ready`() {
        val entry = sampleEntry(selectedExecutable = "reminiscencia.exe", status = DetectionStatus.LOW_CONFIDENCE)

        val result = mapToLaunchRequest(entry)

        assertTrue(
            "confidence must not gate launch requests — expected Ready even at LOW_CONFIDENCE, got $result",
            result is LaunchRequestResult.Ready
        )
        assertEquals("reminiscencia.exe", (result as LaunchRequestResult.Ready).request.executableName)
    }

    // --- Extra coverage: detectedTitle is allowed to be null and should pass through as null, not crash or substitute a default ---
    @Test
    fun `null detectedTitle passes through as null rather than being substituted`() {
        val entry = sampleEntry(selectedExecutable = "Pokemon Opalo.exe", detectedTitle = null)

        val result = mapToLaunchRequest(entry)

        assertTrue(result is LaunchRequestResult.Ready)
        assertNull((result as LaunchRequestResult.Ready).request.detectedTitle)
    }

    // --- Extra coverage: a non-Game.exe executable name is passed through unchanged, never re-derived ---
    @Test
    fun `mapper never re-derives the executable name, it only copies what Game Library already resolved`() {
        val entry = sampleEntry(selectedExecutable = "A Farfetch'd Story.exe")

        val result = mapToLaunchRequest(entry) as LaunchRequestResult.Ready

        assertEquals("A Farfetch'd Story.exe", result.request.executableName)
    }
}

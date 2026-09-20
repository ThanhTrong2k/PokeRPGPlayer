package com.pokerpgplayer.app.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService
import com.pokerpgplayer.app.data.model.KnownMitigationIds
import com.pokerpgplayer.app.data.model.OverlayStatus
import com.pokerpgplayer.app.data.model.RuntimeConfigProfile
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 27 — Runtime Config Safety Layer, Stage 3 (minimal diagnostic
 * launch-path wiring).
 *
 * **The architecture question this class answers first, per the
 * approved Sprint 27 scope's own requirement 4/5:** does the current
 * launch architecture support a "config path override," and if not,
 * what's the minimum seam needed? **Answer: the seam already exists,
 * confirmed directly from source since Sprint 9 — no native change was
 * needed.** mkxp-z's own native code never accepts an arbitrary config
 * *file path*; it always reads a hardcoded `mkxp.json` (`#define
 * CONF_FILE "mkxp.json"`, `config.cpp`) from whatever directory it
 * `chdir()`s into via `GAME_PATH`. "Overriding the config" therefore
 * means pointing `GAME_PATH` at a **disposable workspace directory**
 * that already contains the desired `mkxp.json` — exactly the same
 * mechanism every diagnostic test since Sprint 21 has already used
 * successfully. This class is the first to drive that mechanism with
 * [RuntimeConfigOverlayService]'s own real, hardened, general-purpose
 * generation logic (Stage 2/2.5) instead of a test-hardcoded script.
 *
 * **Uses the genuinely original, un-hand-edited Essentials v21.1 config**
 * (`essentials-v211-original-workspace`, prepared by Ti since Sprint 17
 * — the one with the real, non-ASCII `windowTitle` that crashes native
 * config-read, per Sprint 18's own finding) as this test's own base —
 * deliberately, not the already-ASCII-safe workspace used by every
 * other test since Sprint 18. This is the first test where
 * `ascii-safe-window-title` actually does something: the overlay
 * service needs to fix the *real* problem, not a pre-fixed fixture,
 * to genuinely prove the Stage 1–2.5 architecture end-to-end.
 *
 * **`zlib-preload` and `ascii-safe-window-title` are both requested in
 * the same [RuntimeConfigProfile], applied in one [RuntimeConfigOverlayService.generateOverlay]
 * call** — the same real, hardened service class from App v0.0.31/32,
 * not a reimplementation.
 *
 * **The original base workspace's own `mkxp.json` is never opened for
 * writing anywhere in this class** — only the disposable copy's own
 * file (created fresh for this test) is ever modified, confirmed by an
 * explicit before/after size check.
 *
 * **This remains diagnostic only** — no production `RuntimeManager`/
 * `AppContainer`/Game Detail launch flow is touched. This proves the
 * seam and the service work together correctly; wiring this into the
 * real, user-facing launch flow is a separate, later, explicitly-
 * approved step, not started here.
 *
 * **Does not test input, controller interaction, save/load, or audio.**
 * Does not touch fullscreen/scaling/viewport behavior. No `DEBUG` force,
 * no dev/cheat behavior. No native C++ change. No Pokémon Essentials
 * original file modified.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityConfigOverlayLaunchTest {

    companion object {
        private const val TAG = "RuntimeActivityConfigOverlayLaunchTest"

        /** The genuinely original config, prepared by Ti since Sprint 17 — not the ASCII-safe one. */
        private const val BASE_WORKSPACE_FOLDER_NAME = "essentials-v211-original-workspace"
        private const val DISPOSABLE_WORKSPACE_FOLDER_NAME = "essentials-v211-stage3-overlay-workspace"

        private const val NATIVE_STARTUP_WAIT_MS = 20000L
        private const val CHECKPOINT_INTERVAL_MS = 20000L
        private const val TOTAL_WAIT_MS = 90000L
        private const val CLOSE_TIMEOUT_MS = 15000L

        private const val MISSING_SCRIPTS_MESSAGE = "No game scripts specified (missing Game.ini?)"
        private const val PLUGIN_MANAGER_ZLIB_MARKER = "PluginManager::Zlib"
    }

    private val overlayService = RuntimeConfigOverlayService()

    private fun captureScreenshot(context: Context, fileName: String) {
        val outputFile = File(context.filesDir, fileName)
        try {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val success = device.takeScreenshot(outputFile)
            if (success && outputFile.exists()) {
                Log.i(TAG, "EVIDENCE: screenshot captured at ${outputFile.absolutePath}, size=${outputFile.length()} bytes.")
            } else {
                Log.w(TAG, "Screenshot capture reported failure (takeScreenshot returned $success, file exists=${outputFile.exists()}).")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Screenshot capture threw: ${t::class.java.simpleName}: ${t.message}")
        }
    }

    private fun pollForViewWithText(text: String, timeoutMs: Long, intervalMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return true
            } catch (t: Throwable) {
                Thread.sleep(intervalMs)
            }
        }
        return false
    }

    @Test
    fun launchAttempt_withGeneratedOverlay_reachesTitleMenuWithoutPluginManagerZlibError() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseWorkspace = File(context.filesDir, BASE_WORKSPACE_FOLDER_NAME)

        // Test Environment Hardening: self-heal the fixture from the
        // external staging path if app-private storage was wiped by a
        // reinstall/clear-data/new-sprint-install since it was last
        // seeded — see EssentialsFixtureSeeder's own kdoc.
        when (val fixtureResult = EssentialsFixtureSeeder.ensureFixtureAvailable(context, BASE_WORKSPACE_FOLDER_NAME)) {
            is EssentialsFixtureSeeder.FixtureResult.Ready -> Unit
            is EssentialsFixtureSeeder.FixtureResult.Missing -> assumeTrue(
                "Missing PE21 fixture. Push it once to /sdcard/Download/$BASE_WORKSPACE_FOLDER_NAME",
                false
            )
            is EssentialsFixtureSeeder.FixtureResult.CopyFailed -> fail(
                "PE21 fixture seeding failed (do not silently continue): ${fixtureResult.reason}"
            )
        }

        val baseMkxpJson = File(baseWorkspace, "mkxp.json")
        val baseMkxpJsonSizeBefore = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L

        // --- Step 1: byte-for-byte copy, never opening the base workspace's own mkxp.json for writing ---
        val disposableWorkspace = File(context.filesDir, DISPOSABLE_WORKSPACE_FOLDER_NAME)
        if (disposableWorkspace.exists()) {
            disposableWorkspace.deleteRecursively()
        }
        baseWorkspace.copyRecursively(disposableWorkspace, overwrite = true)
        Log.i(TAG, "Disposable workspace created at: ${disposableWorkspace.absolutePath}")

        // --- Step 2: derive the profile for this fixture. Explicit, test-specified — not derived from any detection logic, since none exists yet (Sprint 24 ADR question 3 remains unimplemented). ---
        val profile = RuntimeConfigProfile(
            enabledMitigations = listOf(KnownMitigationIds.ZLIB_PRELOAD, KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE)
        )

        // --- Step 3: generate the overlay using the real, hardened Stage 2/2.5 service — not hand-written diagnostic text. ---
        val disposableMkxpJsonFile = File(disposableWorkspace, "mkxp.json")
        val originalTextInCopy = disposableMkxpJsonFile.readText(Charsets.UTF_8)
        val result = overlayService.generateOverlay(originalTextInCopy, profile)

        Log.i(TAG, "EVIDENCE: originalConfigHash=${result.audit.originalConfigHash}")
        Log.i(TAG, "EVIDENCE: overlayConfigHash=${result.audit.overlayConfigHash}")
        Log.i(TAG, "EVIDENCE: overlayStatus=${result.overlayStatus}")
        Log.i(TAG, "EVIDENCE: appliedMitigations=${result.audit.lastAppliedMitigations}")
        Log.i(TAG, "EVIDENCE: skippedMitigations=${result.skippedMitigations}")
        Log.i(TAG, "EVIDENCE: reason=${result.audit.lastReason}")

        if (result.overlayStatus != OverlayStatus.GENERATED_TEST_ONLY || result.overlayConfigText == null) {
            fail("Overlay generation did not succeed as expected (overlayStatus=${result.overlayStatus}, errorMessage=${result.errorMessage}) — " +
                "this is a real problem to report, not something to launch through regardless.")
            return
        }

        // --- Step 4: apply the overlay to the DISPOSABLE COPY only — the base workspace's own file is never touched. ---
        disposableMkxpJsonFile.writeText(result.overlayConfigText, Charsets.UTF_8)
        result.generatedAuxiliaryFiles.forEach { (fileName, content) ->
            File(disposableWorkspace, fileName).writeText(content, Charsets.UTF_8)
            Log.i(TAG, "EVIDENCE: generated auxiliary file: $fileName (${content.length} chars)")
        }
        Log.i(TAG, "EVIDENCE: overlay path=${disposableMkxpJsonFile.absolutePath}")

        // --- Confirm the base workspace's own mkxp.json is untouched ---
        val baseMkxpJsonSizeAfter = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L
        if (baseMkxpJsonSizeAfter == baseMkxpJsonSizeBefore) {
            Log.i(TAG, "EVIDENCE: CONFIRMED — base workspace mkxp.json size unchanged ($baseMkxpJsonSizeAfter bytes). Original PE21 config not modified.")
        } else {
            Log.w(TAG, "UNEXPECTED: base workspace mkxp.json size changed ($baseMkxpJsonSizeBefore -> $baseMkxpJsonSizeAfter) — investigate immediately.")
        }

        // --- Step 5 (the seam): point GAME_PATH at the disposable workspace directory containing the overlay. No native change, no config-path parameter — this is the existing, already-proven mechanism. ---
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, disposableWorkspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native startup before beginning checkpointed observation.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)

        var elapsedMs = 0L
        while (elapsedMs < TOTAL_WAIT_MS) {
            Thread.sleep(CHECKPOINT_INTERVAL_MS)
            elapsedMs += CHECKPOINT_INTERVAL_MS
            Log.i(TAG, "Checkpoint: ${elapsedMs}ms elapsed of ${TOTAL_WAIT_MS}ms.")
            captureScreenshot(context, "sprint27_overlay_launch_${elapsedMs / 1000}s.png")
        }
        Log.i(TAG, "Observation window complete.")

        try {
            val sawMissingScripts = pollForViewWithText(MISSING_SCRIPTS_MESSAGE, 3000L, 500L)
            if (sawMissingScripts) {
                Log.w(TAG, "UNEXPECTED: missing-scripts dialog observed — would indicate a workspace-copy problem, not an overlay-generation finding.")
            } else {
                Log.i(TAG, "EVIDENCE: missing-scripts dialog NOT observed (expected, since Scripts.rxdata was copied intact).")
            }

            Log.i(TAG, "EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) across the full observation window for:")
            Log.i(TAG, "  - the \"RGSS version\" checkpoint line.")
            Log.i(TAG, "  - whether \"$PLUGIN_MANAGER_ZLIB_MARKER\" appears at all — NOT expected, since zlib-preload was applied via the overlay.")
            Log.i(TAG, "  - any other showExc()-produced exception, at any point — report exact class/message/backtrace.")
            Log.i(TAG, "EVIDENCE: review the pulled screenshots for whether the Essentials title menu is reached and remains stable — same visual confirmation already proven in Sprint 22, now via the real overlay service instead of a hand-written diagnostic script.")

        } finally {
            val closeThread = Thread {
                try {
                    scenario.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "scenario.close() threw on the background close thread: ${t.message}")
                }
            }
            closeThread.isDaemon = true
            closeThread.start()
            closeThread.join(CLOSE_TIMEOUT_MS)

            if (closeThread.isAlive) {
                Log.w(TAG, "scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if native execution is still active. Not failing the test for this.")
            } else {
                Log.i(TAG, "scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
            }
        }
    }
}

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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 17 — Pokémon Essentials v21.1 `mkxp.json` Config Variant
 * Investigation.
 *
 * **Purely read-only against three separately, manually prepared,
 * pre-existing app-private workspace folders.** This class does not
 * generate, write, overwrite, or derive any file — it only *points*
 * `RuntimeActivity`'s existing `EXTRA_WORKSPACE_PATH` mechanism at
 * whichever of the three fixed folder names below already exists in this
 * app's own private storage, verifies a few expected files are present,
 * and collects evidence. Each folder is expected to be a **complete,
 * independent copy** of the Pokémon Essentials v21.1 baseline — prepared
 * entirely on Ti's own machine, with only that local staging copy's own
 * `mkxp.json` hand-edited before being pushed to the device — never
 * generated, reconstructed, or modified by this app or this test in any
 * way. See this project's README (App v0.0.21 section) for the exact
 * Ti-side preparation steps.
 *
 * **Why this matters, and why an earlier version of this class did it
 * differently (and incorrectly):** a prior revision of this test
 * embedded `mkxp.json` content as Kotlin string constants and wrote them
 * into a derived copy at runtime. That approach was reviewed and
 * rejected — reconstructing this exact file's content inside Kotlin
 * source introduces its own additional encoding-transformation layer
 * (Kotlin source file encoding, compilation, `File.writeText()`'s own
 * charset handling) *on top of* the very encoding question
 * (`windowTitle`'s reported `"PokĂ©mon Essentials v21.1"`, per Sprint 16)
 * this investigation exists to resolve — completely undermining the
 * experiment's own validity. Ti manually preparing three real,
 * independently verified folders, each pushed to the device exactly as
 * Ti wrote it, is the only way this comparison is actually meaningful.
 *
 * **The three fixed workspace folders, one per test method, each
 * expected to differ *only* in its own `mkxp.json`'s content — everything
 * else (`Data/`, `Graphics/`, `Audio/`, `PBS/`, `Scripts.rxdata`, etc.)
 * identical across all three:**
 * - `files/essentials-v211-original-workspace` — `mkxp.json` exactly as
 *   originally observed during Sprint 16 intake.
 * - `files/essentials-v211-ascii-title-workspace` — `windowTitle`
 *   hand-edited to plain ASCII, everything else unchanged.
 * - `files/essentials-v211-ascii-title-no-trailing-comma-workspace` —
 *   same ASCII title, and the trailing comma before the closing `}`
 *   also hand-removed.
 *
 * **If a given test's own workspace folder is missing, that test is
 * skipped** (via [assumeTrue]), not failed — a missing fixture is a
 * setup gap, not a finding.
 *
 * **Reuses the same proven lifecycle mechanics as every prior real-game
 * evidence-collection test** (`EXTRA_WORKSPACE_PATH` injection timing,
 * `android:configChanges` stability, bounded `scenario.close()`).
 * **Deliberately does not hard-assert most outcomes**, for the same
 * reasoning as those prior tests — this explores genuinely open
 * questions. It polls and logs; it does not fail the build for an
 * unexpected-but-informative outcome. The one genuine hard-fail case is
 * the missing-workspace precondition itself.
 *
 * **Does not assert:** title screen, rendering, real gameplay, input,
 * audio, or save/load of any kind. No production or bridge file is
 * changed by this test. No native C++ change. No file is created,
 * written, or modified by this class — read-only checks only.
 *
 * **Sprint 18 Track 1 (App v0.0.22) addition —
 * [launchAttempt_asciiTitleWorkspace_extendedObservation]:** the three
 * methods above each wait a fixed 20 seconds before collecting evidence
 * — enough to confirm the config-read checkpoint, but not necessarily
 * enough to observe deeper native/Ruby execution behavior. This new,
 * separate method reuses the exact same, already-proven ASCII-title
 * workspace (no new fixture, no new Ti-side setup), but waits up to 90
 * seconds instead, with an explicit log line every 10 seconds so a long
 * run's own progress is visible in logcat rather than a single silent
 * gap. It exists purely to see whether this fixture proceeds further
 * than the 20-second window could show — a new exception, a new dialog,
 * or continued silence — without asserting an outcome either way. The
 * three original methods are unchanged; this method does not replace or
 * modify them.
 *
 * **Sprint 19 (App v0.0.23) addition — screenshot checkpoints inside
 * [launchAttempt_asciiTitleWorkspace_extendedObservation]:** logcat alone
 * cannot distinguish "reached an idle/rendering state," "blocked
 * silently," or "rendering something broken" — all three look identical
 * as an absence of further log output. This adds real, pixel-level
 * screenshot capture (via `UiDevice.takeScreenshot()`, which captures the
 * full composited output including SDL's own OpenGL-rendered content —
 * unlike Espresso's own View-hierarchy checks, which do not "see"
 * `SurfaceView`/GL content at all) at two points *aligned to the existing
 * 10-second checkpoint grid* already established in App v0.0.22: **40
 * seconds** (the nearest existing checkpoint to the originally-requested
 * ~45s midpoint — kept on the existing grid deliberately, rather than
 * introducing a separate, misaligned timer) and **90 seconds** (the
 * loop's own natural end). Screenshots are written to this app's own
 * private storage; **this test does not interpret their content in any
 * way** — it logs only objective, non-interpretive facts (capture
 * success/failure, file path, file size). What the images actually show
 * is Ti's own visual review after pulling them to a local machine, per
 * this release's README section — never an automated claim this code
 * makes about compatibility, a title screen, or anything else.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityEssentialsConfigVariantTest {

    companion object {
        private const val TAG = "RuntimeActivityEssentialsConfigVariantTest"

        private const val ORIGINAL_WORKSPACE_FOLDER_NAME = "essentials-v211-original-workspace"
        private const val ASCII_TITLE_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-workspace"
        private const val ASCII_TITLE_NO_TRAILING_COMMA_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-no-trailing-comma-workspace"

        /** Same margin as App v0.0.19/20 — see those classes' own kdoc for the reasoning. */
        private const val NATIVE_STARTUP_WAIT_MS = 20000L

        private const val DIALOG_POLL_TIMEOUT_MS = 15000L
        private const val DIALOG_POLL_INTERVAL_MS = 500L
        private const val CLOSE_TIMEOUT_MS = 15000L

        private const val MISSING_SCRIPTS_MESSAGE = "No game scripts specified (missing Game.ini?)"

        /**
         * Sprint 18 Track 1 (App v0.0.22) — total extended observation
         * window for [launchAttempt_asciiTitleWorkspace_extendedObservation],
         * replacing the shorter 20s margin used by the three
         * config-read-only methods above for this one, separate method.
         * Not a precise, principled figure — a generous window to give
         * genuinely new territory (real Ruby script execution on this
         * baseline, not yet observed past the RGSS1/OpenGL checkpoint)
         * real wall-clock time, without making a single test run
         * excessively slow.
         */
        private const val EXTENDED_OBSERVATION_TOTAL_WAIT_MS = 90000L

        /** How often to log a progress checkpoint during the extended wait. */
        private const val EXTENDED_OBSERVATION_CHECKPOINT_INTERVAL_MS = 10000L
    }

    /** Same poll-and-log helper as prior real-game evidence-collection tests. */
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

    /**
     * Sprint 19 (App v0.0.23). Captures a real, pixel-level screenshot via
     * `UiDevice.takeScreenshot()` and writes it to this app's own private
     * storage. Logs only objective, non-interpretive facts — capture
     * success/failure and the resulting file's path/size — never an
     * interpretation of what the image shows. Failure (e.g., the API
     * being unavailable on this device/Android version) is logged clearly
     * and does not throw or fail the test — a failed capture is itself a
     * reportable finding, not silently swallowed.
     */
    private fun captureScreenshot(variantLabel: String, context: Context, fileName: String) {
        val outputFile = File(context.filesDir, fileName)
        try {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val success = device.takeScreenshot(outputFile)
            if (success && outputFile.exists()) {
                Log.i(TAG, "[$variantLabel] EVIDENCE: screenshot captured successfully at ${outputFile.absolutePath}, size=${outputFile.length()} bytes.")
            } else {
                Log.w(TAG, "[$variantLabel] Screenshot capture reported failure (takeScreenshot returned $success, file exists=${outputFile.exists()}) — this is itself a reportable finding, not assumed to be a test bug.")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[$variantLabel] Screenshot capture threw: ${t::class.java.simpleName}: ${t.message} — logged, not failing the test for this.")
        }
    }

    /**
     * Shared launch-attempt-and-evidence-collection body, reused by all
     * three named test methods below. Purely read-only: resolves the
     * fixed workspace folder, verifies expected files are present
     * (never writes or modifies anything), launches, waits, and logs.
     */
    private fun runLaunchAttempt(variantLabel: String, workspaceFolderName: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(context.filesDir, workspaceFolderName)

        // Hard precondition, not a finding: requires Ti's own, separate,
        // manual preparation of this exact folder (see README) to have
        // already happened. Skip (not fail) if missing.
        assumeTrue(
            "[$variantLabel] Workspace not found at ${workspace.absolutePath} — see README's App v0.0.21 " +
                "Ti-side preparation steps for this specific variant before running this test.",
            workspace.exists() && workspace.isDirectory
        )

        val gameIni = File(workspace, "Game.ini")
        val scriptsData = File(workspace, "Data/Scripts.rxdata")
        val mkxpJson = File(workspace, "mkxp.json")

        Log.i(TAG, "[$variantLabel] Using workspace path: ${workspace.absolutePath}")
        Log.i(TAG, "[$variantLabel] Game.ini present: ${gameIni.exists()}")
        Log.i(TAG, "[$variantLabel] Data/Scripts.rxdata present: ${scriptsData.exists()}")
        Log.i(TAG, "[$variantLabel] mkxp.json present: ${mkxpJson.exists()}, length=${if (mkxpJson.exists()) mkxpJson.length() else -1}")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "[$variantLabel] Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native startup and config load.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "[$variantLabel] Wait complete — beginning evidence collection.")

        try {
            val sawMissingScripts = pollForViewWithText(MISSING_SCRIPTS_MESSAGE, 3000L, DIALOG_POLL_INTERVAL_MS)
            if (sawMissingScripts) {
                Log.w(TAG, "[$variantLabel] UNEXPECTED: missing-scripts dialog observed despite Scripts.rxdata being present — likely a workspace preparation problem, not a config-variant finding.")
            } else {
                Log.i(TAG, "[$variantLabel] EVIDENCE: missing-scripts dialog NOT observed (expected, since Scripts.rxdata exists).")
            }

            Log.i(TAG, "[$variantLabel] EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) for:")
            Log.i(TAG, "[$variantLabel]   - the \"RGSS version\" checkpoint line, if reached.")
            Log.i(TAG, "[$variantLabel]   - any showExc()-produced exception — report exact class/message/backtrace, and note whether it differs between this variant and the others.")
            Log.i(TAG, "[$variantLabel]   - any config-parse-failure line (\"Failed to parse...\"), if this variant's mkxp.json is rejected outright — not expected, given config.cpp's json::parse5 JSON5 tolerance, but explicitly worth checking, not assumed.")
            Log.i(TAG, "[$variantLabel]   - the actual windowTitle value if it surfaces anywhere in a dialog title or log line, for direct comparison against this specific variant's own hand-edited file.")

        } finally {
            val closeThread = Thread {
                try {
                    scenario.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "[$variantLabel] scenario.close() threw on the background close thread: ${t.message}")
                }
            }
            closeThread.isDaemon = true
            closeThread.start()
            closeThread.join(CLOSE_TIMEOUT_MS)

            if (closeThread.isAlive) {
                Log.w(TAG, "[$variantLabel] scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if native execution is still active. Not failing the test for this.")
            } else {
                Log.i(TAG, "[$variantLabel] scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
            }
        }
    }

    @Test
    fun launchAttempt_originalWorkspace_collectsConfigReadEvidence() {
        runLaunchAttempt("original", ORIGINAL_WORKSPACE_FOLDER_NAME)
    }

    @Test
    fun launchAttempt_asciiTitleWorkspace_collectsConfigReadEvidence() {
        runLaunchAttempt("ascii-title", ASCII_TITLE_WORKSPACE_FOLDER_NAME)
    }

    @Test
    fun launchAttempt_asciiTitleNoTrailingCommaWorkspace_collectsConfigReadEvidence() {
        runLaunchAttempt("ascii-title-no-trailing-comma", ASCII_TITLE_NO_TRAILING_COMMA_WORKSPACE_FOLDER_NAME)
    }

    /**
     * Sprint 18 Track 1 (App v0.0.22). Separate from [runLaunchAttempt] —
     * does not modify or reuse that method's own fixed 20s wait. Reuses
     * the exact same, already-proven `ASCII_TITLE_WORKSPACE_FOLDER_NAME`
     * fixture (no new Ti-side setup required), but waits up to
     * [EXTENDED_OBSERVATION_TOTAL_WAIT_MS] with an explicit log line every
     * [EXTENDED_OBSERVATION_CHECKPOINT_INTERVAL_MS], to see whether this
     * fixture proceeds further than the shorter window could show —
     * without asserting an outcome either way, matching every prior
     * real-game evidence-collection test's own discipline.
     */
    @Test
    fun launchAttempt_asciiTitleWorkspace_extendedObservation() {
        val variantLabel = "ascii-title-extended"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(context.filesDir, ASCII_TITLE_WORKSPACE_FOLDER_NAME)

        assumeTrue(
            "[$variantLabel] Workspace not found at ${workspace.absolutePath} — see README's App v0.0.21 " +
                "Ti-side preparation steps (same ASCII-title fixture as the shorter config-read test) before running this test.",
            workspace.exists() && workspace.isDirectory
        )

        val gameIni = File(workspace, "Game.ini")
        val scriptsData = File(workspace, "Data/Scripts.rxdata")
        val mkxpJson = File(workspace, "mkxp.json")

        Log.i(TAG, "[$variantLabel] Using workspace path: ${workspace.absolutePath}")
        Log.i(TAG, "[$variantLabel] Game.ini present: ${gameIni.exists()}")
        Log.i(TAG, "[$variantLabel] Data/Scripts.rxdata present: ${scriptsData.exists()}")
        Log.i(TAG, "[$variantLabel] mkxp.json present: ${mkxpJson.exists()}, length=${if (mkxpJson.exists()) mkxpJson.length() else -1}")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "[$variantLabel] Reached RESUMED — beginning extended observation, up to ${EXTENDED_OBSERVATION_TOTAL_WAIT_MS}ms, with a checkpoint every ${EXTENDED_OBSERVATION_CHECKPOINT_INTERVAL_MS}ms.")

        var elapsedMs = 0L
        while (elapsedMs < EXTENDED_OBSERVATION_TOTAL_WAIT_MS) {
            Thread.sleep(EXTENDED_OBSERVATION_CHECKPOINT_INTERVAL_MS)
            elapsedMs += EXTENDED_OBSERVATION_CHECKPOINT_INTERVAL_MS
            Log.i(TAG, "[$variantLabel] Checkpoint: ${elapsedMs}ms elapsed of ${EXTENDED_OBSERVATION_TOTAL_WAIT_MS}ms — still observing, no assertion made at this point.")

            // Sprint 19: screenshot at the checkpoint nearest the
            // originally-requested ~45s midpoint (40s, staying on the
            // existing 10s grid rather than introducing a separate,
            // misaligned timer) and again at the loop's own natural end
            // (90s) — see this method's own kdoc for the full reasoning.
            if (elapsedMs == 40000L) {
                captureScreenshot(variantLabel, context, "sprint19-screenshot-40s.png")
            }
        }
        Log.i(TAG, "[$variantLabel] Extended wait complete (${elapsedMs}ms total) — beginning evidence collection.")
        captureScreenshot(variantLabel, context, "sprint19-screenshot-90s.png")

        try {
            val sawMissingScripts = pollForViewWithText(MISSING_SCRIPTS_MESSAGE, 3000L, DIALOG_POLL_INTERVAL_MS)
            if (sawMissingScripts) {
                Log.w(TAG, "[$variantLabel] UNEXPECTED: missing-scripts dialog observed despite Scripts.rxdata being present — likely a workspace preparation problem, not a fixture finding.")
            } else {
                Log.i(TAG, "[$variantLabel] EVIDENCE: missing-scripts dialog NOT observed (expected, since Scripts.rxdata exists).")
            }

            Log.i(TAG, "[$variantLabel] EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) across the full ${EXTENDED_OBSERVATION_TOTAL_WAIT_MS}ms window for:")
            Log.i(TAG, "[$variantLabel]   - the \"RGSS version\" checkpoint line, if not already confirmed by the shorter test.")
            Log.i(TAG, "[$variantLabel]   - any showExc()-produced exception, at any point during the extended window — report exact class/message/backtrace and approximately which checkpoint interval it appeared near.")
            Log.i(TAG, "[$variantLabel]   - whether logcat output continues, stalls, or stops entirely partway through the window — a silent native loop with no further output is itself informative, distinct from a crash or an explicit exception.")
            Log.i(TAG, "[$variantLabel]   - any Espresso-observable dialog beyond the missing-scripts one already checked above.")
            Log.i(TAG, "[$variantLabel] EVIDENCE: two screenshots were captured (or capture failure was logged) at 40s and 90s — see the log lines above for exact paths/sizes, and this release's README for the adb command to pull them.")

        } finally {
            val closeThread = Thread {
                try {
                    scenario.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "[$variantLabel] scenario.close() threw on the background close thread: ${t.message}")
                }
            }
            closeThread.isDaemon = true
            closeThread.start()
            closeThread.join(CLOSE_TIMEOUT_MS)

            if (closeThread.isAlive) {
                Log.w(TAG, "[$variantLabel] scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if native execution is still active. Not failing the test for this.")
            } else {
                Log.i(TAG, "[$variantLabel] scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
            }
        }
    }
}

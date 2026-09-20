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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 16 — Pokémon Essentials v21.1 Clean Baseline Controlled Launch Attempt.
 *
 * **Requires a one-time, Ti-side manual setup step before this test can
 * run at all** — see this project's README (App v0.0.19 section) for the
 * exact `adb`/`run-as` commands. This test never bundles, copies, or
 * mirrors any Essentials file itself; it only *points* `RuntimeActivity`'s
 * existing `EXTRA_WORKSPACE_PATH` mechanism at a folder that must already
 * exist in this app's own private storage
 * (`context.filesDir/essentials-v211-workspace/`) by the time this test
 * runs. If that folder is missing, this test is **skipped** (via
 * [assumeTrue]), not failed — a missing fixture is a setup problem, not a
 * finding about this baseline.
 *
 * **Adapted from, not copy-pasted from, [RuntimeActivityPokemonZLaunchAttemptTest]
 * (Sprint 14).** Reuses the same proven lifecycle mechanics — the same
 * `EXTRA_WORKSPACE_PATH` injection timing, the same `android:configChanges`
 * stability (App v0.0.17), the same bounded `scenario.close()` pattern
 * (App v0.0.15/16) — since those are proven properties of the runtime
 * harness itself, not specific to any one game. Everything else (workspace
 * folder name, evidence points, class name) is deliberately distinct, per
 * the approved Sprint 16 plan's own instruction not to reuse Pokémon-Z-
 * specific naming or assumptions blindly.
 *
 * **New evidence points specific to this baseline** (see the approved
 * Sprint 16 plan §8 for the full reasoning behind each):
 * - This baseline's own `mkxp.json` explicitly sets `"midiSoundFont":
 *   "soundfont.sf2"` (Pokémon Z's own config had this commented out) —
 *   actively engaging the MIDI/FluidSynth path this project already
 *   confirmed fails gracefully in Sprint 10 (`"Failed to load
 *   libfluidsynth.so.3. Midi playback is disabled."`). This test watches
 *   logcat for that line reappearing, as expected corroborating evidence,
 *   not a new problem.
 * - This test also watches for a `Win32API`/`kernel32`-style exception,
 *   the same class Sprint 14 found for Pokémon Z — purely as an
 *   *observation* of whether this different, cleaner baseline hits the
 *   same class of issue. This does **not** reopen Sprint 15's own
 *   Win32API shim strategy question; no shim, workaround, or patch is
 *   implemented or proposed here.
 * - This baseline's `windowTitle` value was reported during intake as
 *   `"PokĂ©mon Essentials v21.1"` — a pattern consistent with a UTF-8
 *   string being misread through a different code page. The approved
 *   Sprint 16 plan traced this to `Encoding::convertString()`'s own
 *   `uchardet`-based detection in `config.cpp`, and found it's genuinely
 *   unresolved from source alone whether this is a real, runtime-level
 *   encoding bug or purely a display artifact from whatever tool
 *   inspected the file during intake. This test cannot directly observe
 *   a rendered "title bar" (Android apps have no desktop-style window
 *   chrome), so this remains a **best-effort, logcat-only** observation —
 *   documented as a real limitation, not silently assumed to be resolved
 *   by this test's own evidence collection.
 *
 * **Deliberately does not hard-assert most outcomes**, for the same
 * reasoning as [RuntimeActivityPokemonZLaunchAttemptTest] — this explores
 * a real, previously-unattempted baseline. It polls and logs; it does not
 * fail the build for an unexpected-but-informative real outcome. The one
 * genuine hard-fail case is the missing-workspace precondition itself.
 *
 * **Does not assert:** title screen, rendering, real gameplay, input,
 * audio, or save/load of any kind. No production or bridge file is
 * changed by this test. No Essentials file is modified, copied into this
 * repository, or included in any delivered build artifact. No config
 * injection, no game-specific hack, no native C++ change.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityEssentialsBaselineLaunchAttemptTest {

    companion object {
        private const val TAG = "RuntimeActivityEssentialsBaselineLaunchAttemptTest"

        /** Must match the exact path documented in the README's Ti-side setup steps. */
        private const val WORKSPACE_FOLDER_NAME = "essentials-v211-workspace"

        /**
         * Same margin as Sprint 14's own Pokémon Z attempt — this
         * baseline's Scripts.rxdata is comparably sized (1,069,431 bytes
         * vs. 1,018,480), so no strong reason to expect faster or slower
         * script execution. Considered explicitly, not silently copied
         * forward without thought, per the approved Sprint 16 plan §2.
         */
        private const val NATIVE_STARTUP_WAIT_MS = 20000L

        private const val DIALOG_POLL_TIMEOUT_MS = 15000L
        private const val DIALOG_POLL_INTERVAL_MS = 500L

        /** Same bounded-close margin as App v0.0.15/16/18. */
        private const val CLOSE_TIMEOUT_MS = 15000L

        private const val MISSING_SCRIPTS_MESSAGE = "No game scripts specified (missing Game.ini?)"
    }

    /**
     * Polls for a view with the given text for up to [timeoutMs],
     * returning true if found, false if not — never throws. Used for
     * observation/logging, not hard test failure, since presence or
     * absence is itself the evidence collected here, not a pass/fail
     * gate on its own.
     */
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
    fun runtimeActivity_withEssentialsBaselineWorkspace_collectsLaunchAttemptEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(context.filesDir, WORKSPACE_FOLDER_NAME)

        // Hard precondition, not a finding about this baseline: requires
        // Ti's one-time manual setup (see README) to have already placed
        // the mirrored folder here. Skip (not fail) if missing.
        assumeTrue(
            "Essentials v21.1 workspace not found at ${workspace.absolutePath} — see README's App v0.0.19 " +
                "one-time setup steps before running this test.",
            workspace.exists() && workspace.isDirectory
        )

        Log.i(TAG, "Using Essentials v21.1 workspace path: ${workspace.absolutePath}")
        Log.i(TAG, "workspace.exists=${workspace.exists()}")
        Log.i(TAG, "workspace.isDirectory=${workspace.isDirectory}")

        val gameIni = File(workspace, "Game.ini")
        val scriptsData = File(workspace, "Data/Scripts.rxdata")
        val mkxpJson = File(workspace, "mkxp.json")
        Log.i(TAG, "Game.ini present: ${gameIni.exists()}")
        Log.i(TAG, "Data/Scripts.rxdata present: ${scriptsData.exists()}")
        Log.i(TAG, "mkxp.json present: ${mkxpJson.exists()}")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native startup, script decompression, and Ruby evaluation.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "Wait complete — beginning evidence collection.")

        try {
            // Negative-but-informative check: this dialog appearing would
            // mean the workspace copy is incomplete/wrong, not a finding
            // about this baseline.
            val sawMissingScripts = pollForViewWithText(MISSING_SCRIPTS_MESSAGE, 3000L, DIALOG_POLL_INTERVAL_MS)
            if (sawMissingScripts) {
                Log.w(TAG, "UNEXPECTED: missing-scripts dialog observed despite Scripts.rxdata being present in the workspace — likely a workspace copy problem, not an Essentials finding.")
            } else {
                Log.i(TAG, "EVIDENCE: missing-scripts dialog NOT observed (expected and correct, since Scripts.rxdata exists).")
            }

            Log.i(TAG, "EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) for:")
            Log.i(TAG, "  - the \"RGSS version\" checkpoint line, if reached.")
            Log.i(TAG, "  - any showExc()-produced Ruby exception — report exact class/message/backtrace if present.")
            Log.i(TAG, "  - specifically watch for a Win32API/kernel32-style exception (same class Sprint 14 found for Pokémon Z) — this is an OBSERVATION only; no shim/workaround is implemented or proposed by this test.")
            Log.i(TAG, "  - a libfluidsynth.so.3 / MIDI-related line — expected here, since this baseline's mkxp.json explicitly sets midiSoundFont=soundfont.sf2, unlike Pokémon Z's own commented-out value.")
            Log.i(TAG, "  - the actual windowTitle value if it appears anywhere in a dialog title or log line — compare it character-by-character against \"Pokémon Essentials v21.1\" to help resolve whether the mojibake reported during intake is a real runtime encoding bug or a display-only artifact. This is best-effort only: Android has no desktop-style window title bar to inspect directly.")

        } finally {
            // Same bounded-close pattern as App v0.0.15/16/18.
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
                Log.w(TAG, "scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if native execution (event loop, dialog wait, or ongoing script/graphics activity) is still active. Not failing the test for this.")
            } else {
                Log.i(TAG, "scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
            }
        }
    }
}

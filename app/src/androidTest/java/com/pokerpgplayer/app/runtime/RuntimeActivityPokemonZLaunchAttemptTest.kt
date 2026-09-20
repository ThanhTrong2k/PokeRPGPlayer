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
 * Sprint 14 — Pokémon Z Controlled Launch Attempt.
 *
 * **Requires a one-time, Ti-side manual setup step before this test can
 * run at all** — see this project's README (App v0.0.18 section) for the
 * exact `adb`/`run-as` commands. This test never bundles, copies, or
 * mirrors any Pokémon Z file itself; it only *points* `RuntimeActivity`'s
 * existing `EXTRA_WORKSPACE_PATH` mechanism at a folder that must already
 * exist in this app's own private storage
 * (`context.filesDir/pokemon-z-workspace/`) by the time this test runs.
 * If that folder is missing, this test is **skipped** (via
 * [assumeTrue]), not failed — a missing fixture is a setup problem, not a
 * finding about Pokémon Z itself.
 *
 * **Reuses every lifecycle protection already proven since Sprint 8–12:**
 * the same non-exported `RuntimeActivity`, the same `EXTRA_WORKSPACE_PATH`
 * injection (set before `SDLActivity.onCreate()`), the same
 * `android:configChanges` stability fix from App v0.0.17 (no
 * destroy/recreate on SDL's orientation request), and the same bounded
 * `scenario.close()` pattern from App v0.0.15/16 (a background daemon
 * thread with a timeout, since a real, long-running native loop — now
 * plus real Ruby script execution — can still leave the native side
 * active well past this test's own patience).
 *
 * **Deliberately does not hard-assert most outcomes.** Unlike
 * [RuntimeActivityMessageBoxCheckpointTest] (Sprint 12), which tested a
 * fully-understood, artificial fixture with one deterministic expected
 * outcome, this test explores a *real*, previously-never-attempted game.
 * Per the approved Sprint 14 plan: the missing-scripts dialog appearing
 * would itself be a real, valid finding (not necessarily a test bug) if
 * the workspace copy is somehow incomplete, and a `showExc()`-logged Ruby
 * exception is expected, informative, *acceptable* evidence, not a
 * failure. This test observes and logs; it does not fail the build for
 * an unexpected-but-informative real-game outcome. The one genuine
 * hard-fail case is the missing-workspace precondition itself
 * (via [assumeTrue], which skips rather than fails, since Ti forgetting
 * the one-time setup step is not evidence about Pokémon Z at all).
 *
 * **Does not assert:** title screen, rendering, real gameplay, input,
 * audio, or save/load of any kind. No production or bridge file is
 * changed by this test. No Pokémon Z file is modified, copied into this
 * repository, or included in any delivered build artifact.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityPokemonZLaunchAttemptTest {

    companion object {
        private const val TAG = "RuntimeActivityPokemonZLaunchAttemptTest"

        /** Must match the exact path documented in the README's Ti-side setup steps. */
        private const val WORKSPACE_FOLDER_NAME = "pokemon-z-workspace"

        /**
         * Longer than Sprint 9/10/12's own 10s native-startup margin —
         * a real ~1MB Scripts.rxdata archive needs per-script zlib
         * decompression and Ruby evaluation for potentially many script
         * segments before either succeeding silently or hitting a
         * showExc()-logged exception (see the approved Sprint 14 plan
         * §1–§2 for the full source trace). Not a precise, principled
         * figure — a generous margin given this is genuinely new,
         * previously-unexercised territory.
         */
        private const val NATIVE_STARTUP_WAIT_MS = 20000L

        /** How long to poll for any dialog-related observation before concluding "not observed." */
        private const val DIALOG_POLL_TIMEOUT_MS = 15000L
        private const val DIALOG_POLL_INTERVAL_MS = 500L

        /** Same bounded-close margin as App v0.0.15/16. */
        private const val CLOSE_TIMEOUT_MS = 15000L

        private const val MISSING_SCRIPTS_MESSAGE = "No game scripts specified (missing Game.ini?)"
        private const val UNABLE_TO_OPEN_PREFIX = "Unable to open '"
    }

    /**
     * Polls for a view with the given text for up to [timeoutMs],
     * returning true if found, false if not — never throws. Used for
     * observation/logging here, not for hard test failure, since the
     * presence or absence of these views is itself the evidence this
     * test collects, not a pass/fail gate on its own.
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
    fun runtimeActivity_withPokemonZWorkspace_collectsLaunchAttemptEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(context.filesDir, WORKSPACE_FOLDER_NAME)

        // Hard precondition, not a finding about Pokémon Z: this test
        // requires Ti's one-time manual setup (see README) to have
        // already placed the mirrored folder here. If it's missing,
        // skip (not fail) — this is a setup gap, not evidence.
        assumeTrue(
            "Pokémon Z workspace not found at ${workspace.absolutePath} — see README's App v0.0.18 " +
                "one-time setup steps before running this test.",
            workspace.exists() && workspace.isDirectory
        )

        Log.i(TAG, "Using Pokémon Z workspace path: ${workspace.absolutePath}")
        Log.i(TAG, "workspace.exists=${workspace.exists()}")
        Log.i(TAG, "workspace.isDirectory=${workspace.isDirectory}")

        val gameIni = File(workspace, "Game.ini")
        val scriptsData = File(workspace, "Data/Scripts.rxdata")
        Log.i(TAG, "Game.ini present: ${gameIni.exists()}")
        Log.i(TAG, "Data/Scripts.rxdata present: ${scriptsData.exists()}")

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
            // Negative-but-informative check: this dialog appearing
            // would mean the workspace copy is incomplete/wrong, not a
            // Pokémon Z finding — log clearly either way, don't fail.
            val sawMissingScripts = pollForViewWithText(MISSING_SCRIPTS_MESSAGE, 3000L, DIALOG_POLL_INTERVAL_MS)
            if (sawMissingScripts) {
                Log.w(TAG, "UNEXPECTED: missing-scripts dialog observed despite Scripts.rxdata being present in the workspace — likely a workspace copy problem, not a Pokémon Z finding.")
            } else {
                Log.i(TAG, "EVIDENCE: missing-scripts dialog NOT observed (expected and correct, since Scripts.rxdata exists).")
            }

            // Best-effort check for an "Unable to open '...'" dialog —
            // Espresso's withText() needs an exact match, and the exact
            // filename inside the message isn't known ahead of time, so
            // this specific check is necessarily approximate; a full,
            // reliable catch-all for *any* unexpected dialog is out of
            // scope for this test's own evidence collection and is noted
            // as a real limitation, not silently assumed to be covered.
            Log.i(TAG, "Note: this test does not attempt to match an \"$UNABLE_TO_OPEN_PREFIX...\" dialog with an unknown filename — see README for this limitation.")

            Log.i(TAG, "EVIDENCE: check logcat for tag \"mkxp\" — a Debug()-logged Ruby exception (via showExc()) would appear there if script execution hit a runtime error. No Espresso check exists for this; it is logcat-only evidence per the approved Sprint 14 plan.")

        } finally {
            // Same bounded-close pattern as App v0.0.15/16.
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

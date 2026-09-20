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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 12 — UI Automator/Espresso Dialog Checkpoint Route.
 *
 * **Reuses the exact same empty-workspace fixture already proven in
 * Sprint 10** ([RuntimeActivityValidWorkspaceInitTest]) — a real,
 * existing, app-private directory with no `Game.ini`. Sprint 10 proved
 * native `main()` reaches `printRgssVersion()` for this fixture. Sprint
 * 11's own source-only research (see the approved Sprint 11 closure and
 * Sprint 12 plan) found the *next* checkpoint after that — reached inside
 * `binding-mri.cpp`'s `runRMXPScripts()` — is silent in logcat on both
 * the native and Java sides, but surfaces as a real, standard Android
 * `AlertDialog`:
 *
 * ```cpp
 * // binding-mri.cpp, runRMXPScripts()
 * if (scriptPack.empty()) {
 *     showMsg("No game scripts specified (missing Game.ini?)");
 *     return;
 * }
 * ```
 *
 * Traced through `EventThread::showMessageBox()` →
 * `SDL_ShowSimpleMessageBox()` (SDL2's own single-button convenience API)
 * → `SDLActivity.messageboxCreateAndShow()`, which builds a genuine,
 * in-process `AlertDialog` with the message in a plain `TextView` and the
 * dialog's title set to `rtData.config.windowTitle` — which defaults to
 * `"mkxp-z"` for this exact fixture (no `Game.ini` to override it).
 * Because this dialog lives entirely inside this app's own process and
 * view hierarchy, Espresso can inspect it directly — no `UiAutomator`,
 * no cross-process reach needed.
 *
 * **Deliberately does not tap the dialog's "OK" button.** Traced
 * directly: dismissing it would unblock the RGSS thread's
 * `messageboxSelection.wait()`, letting it finish and acknowledge
 * termination — but per Sprint 11's own finding, this specific path never
 * calls `ethread->requestTerminate()`, so the event thread (the real
 * SDL/graphics loop) would keep running regardless, same as Sprint 10's
 * own fixture. Tapping OK is not unsafe, but it adds real
 * interaction-timing risk for zero additional evidence — this dialog's
 * own text *is* the complete checkpoint this test exists to observe.
 *
 * **Bounded polling, not a fixed sleep, for the dialog check itself** —
 * unlike the fixed [Thread.sleep] used for the initial native-startup
 * margin (same proven value as Sprint 9/10), the dialog's own appearance
 * is polled with Espresso assertions in a retry loop, since it depends on
 * `runOnUiThread` being triggered indirectly through a native
 * `SDL_PushEvent` → event-thread callback chain, which Espresso's default
 * idling synchronization is not guaranteed to track automatically.
 *
 * **This is a genuine PASS/FAIL test, unlike every prior Sprint 8–10
 * test in this package** — per the approved Sprint 12 plan, this
 * checkpoint is a single, well-defined yes/no (did the expected dialog
 * appear with the expected text, or not), not a "log only, never assert"
 * design. The bounded-close teardown ([CLOSE_TIMEOUT_MS], same pattern as
 * App v0.0.15) still runs in a `finally` block regardless of whether the
 * assertion passes or fails, so a failed checkpoint doesn't also leave
 * the instrumentation run hanging.
 *
 * **Does not assert:** title screen, rendering, real gameplay, input,
 * audio, or save/load of any kind — only that this specific, named
 * dialog (title `"mkxp-z"`, message `"No game scripts specified (missing
 * Game.ini?)"`) becomes visible. No production or bridge file is changed
 * by this test.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityMessageBoxCheckpointTest {

    companion object {
        private const val TAG = "RuntimeActivityMessageBoxCheckpointTest"

        /**
         * Same proven margin as Sprint 9/10's own native-startup wait —
         * time for RESUMED → surface-ready → SDLMain thread start →
         * native main() → config load, before this test even starts
         * looking for the dialog.
         */
        private const val NATIVE_STARTUP_WAIT_MS = 10000L

        /** How long to keep polling for the expected dialog before treating it as not found. */
        private const val DIALOG_POLL_TIMEOUT_MS = 15000L

        /** How long to wait between each Espresso check while polling. */
        private const val DIALOG_POLL_INTERVAL_MS = 500L

        /**
         * Same bounded-close margin and reasoning as App v0.0.15 — see
         * [RuntimeActivityValidWorkspaceInitTest]'s own kdoc for the full
         * root-cause explanation of why `scenario.close()` is never
         * called directly.
         */
        private const val CLOSE_TIMEOUT_MS = 15000L

        private const val EXPECTED_TITLE = "mkxp-z"
        private const val EXPECTED_MESSAGE = "No game scripts specified (missing Game.ini?)"
    }

    /**
     * Repeatedly checks for a displayed view with the given exact text
     * until it's found or [timeoutMs] elapses. Throws the last observed
     * Espresso failure (wrapped) if it never appears — this is what makes
     * this test a genuine PASS/FAIL, unlike the log-only design of every
     * prior Sprint 8–10 test in this package.
     */
    private fun waitForViewWithText(text: String, timeoutMs: Long, intervalMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(intervalMs)
            }
        }
        throw AssertionError(
            "Expected view with text \"$text\" was not displayed within ${timeoutMs}ms.",
            lastError
        )
    }

    @Test
    fun runtimeActivity_withEmptyWorkspace_showsMissingScriptsDialog() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Same fixture shape as RuntimeActivityValidWorkspaceInitTest —
        // a real, existing, empty app-private directory. Cleared first if
        // a previous, interrupted run left it behind, same defensive
        // pattern used since Sprint 9.
        val workspace = File(context.filesDir, "sprint12-valid-empty-workspace")
        if (workspace.exists()) {
            workspace.deleteRecursively()
        }
        workspace.mkdirs()

        Log.i(TAG, "Created valid workspace path: ${workspace.absolutePath}")
        Log.i(TAG, "workspace.exists=${workspace.exists()}")
        Log.i(TAG, "workspace.isDirectory=${workspace.isDirectory}")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native SDLMain startup before polling for the dialog.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "Wait complete — polling for the expected dialog now (up to ${DIALOG_POLL_TIMEOUT_MS}ms).")

        try {
            waitForViewWithText(EXPECTED_MESSAGE, DIALOG_POLL_TIMEOUT_MS, DIALOG_POLL_INTERVAL_MS)
            Log.i(TAG, "CHECKPOINT PASS: dialog message observed: \"$EXPECTED_MESSAGE\"")

            // Title check gets a short, separate window — if the message
            // is already showing, the title should already be there too;
            // this isn't re-polling for the same long budget again.
            waitForViewWithText(EXPECTED_TITLE, 2000L, 250L)
            Log.i(TAG, "CHECKPOINT PASS: dialog title observed: \"$EXPECTED_TITLE\"")

            // Deliberately no tap on the dialog's "OK" button — see this
            // class's own kdoc for why. The dialog's own text is the
            // complete checkpoint; nothing further is claimed.
        } finally {
            // Same bounded-close pattern as App v0.0.15, run in a finally
            // block so teardown is always attempted regardless of whether
            // the assertions above passed or failed.
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
                Log.w(TAG, "scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if the native SDL/mkxp main loop or dialog wait is still active. Not failing the test for this.")
            } else {
                Log.i(TAG, "scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
            }
        }
    }
}

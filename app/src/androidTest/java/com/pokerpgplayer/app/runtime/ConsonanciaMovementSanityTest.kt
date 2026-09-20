package com.pokerpgplayer.app.runtime

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import android.util.Log
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 41.2b — Cross-Game Movement Sanity Check, via instrumentation
 * launch instead of `adb shell am start`.
 *
 * **Why instrumentation, not adb:** Sprint 41.2's own direct
 * `adb shell am start` attempt failed with `SecurityException:
 * RuntimeActivity not exported from uid ...` — `RuntimeActivity` is
 * deliberately *not* exported (a correct, intentional security
 * posture this sprint does **not** change), so the `shell` UID cannot
 * start it directly. Instrumentation tests run under the *app's own*
 * UID, not `shell`'s — `ActivityScenario.launch()` (the exact same
 * mechanism every other diagnostic test in this project already uses)
 * starts `RuntimeActivity` from inside that UID, so this restriction
 * simply doesn't apply here. No manifest change, no `exported=true`,
 * no change to `RuntimeActivity` itself.
 *
 * **Deliberately minimal, per this sprint's own explicit scope:**
 * launches the already-seeded `consonancia-original-workspace`
 * *directly* — no disposable copy, no Runtime Config Overlay
 * mitigation (`zlib-preload`/`aspect-fit-render`/etc.) — since this is
 * a one-off sanity check, not a repeated diagnostic run, and the
 * ticket's own point 6 only calls for adding a mitigation if launch
 * specifically fails due to a missing preload/config dependency (not
 * preemptively). [VirtualControlsOverlay] attaches automatically
 * (unconditionally, in `RuntimeActivity.onCreate()`, unchanged since
 * Sprint 36) — no code here needs to do anything to get the same
 * D-Pad/X/C/Z/Q overlay Ti already knows.
 *
 * **This is Ti-driven, manual testing** — the Activity is kept alive
 * for a fixed observation window so Ti can interact with it live
 * (press D-Pad, test menu/interact/back); this test does not attempt
 * to programmatically verify movement itself.
 */
@RunWith(AndroidJUnit4::class)
class ConsonanciaMovementSanityTest {

    companion object {
        private const val TAG = "ConsonanciaMovementSanityTest"

        /** The already-seeded folder name, per this sprint's own context — confirmed present with Game.exe/Game.ini/mkxp.json/Data/Graphics/Audio/Plugins/PBS/Ruby Library 3.3.0. */
        private const val WORKSPACE_FOLDER_NAME = "consonancia-original-workspace"

        /** How long to keep the Activity alive for Ti's own manual, live interaction. */
        private const val OBSERVATION_WINDOW_MS = 90_000L
    }

    @Test
    fun launchConsonanciaWorkspace_forManualMovementSanityCheck() {
        Log.i(TAG, "===== TEST_STARTED: launchConsonanciaWorkspace_forManualMovementSanityCheck =====")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(context.filesDir, WORKSPACE_FOLDER_NAME)

        // Hard verification, not assumeTrue — matches this project's
        // own established practice (Sprint 40) for a fixture
        // precondition: a missing workspace is a genuine, visible
        // failure with a clear message, not a silent skip. The ticket
        // itself states this workspace was already seeded and
        // confirmed present, so this should always pass — this check
        // exists purely as a safety net against a stale/incomplete
        // seed on a given run.
        val workspaceExists = workspace.exists() && workspace.isDirectory
        Log.i(TAG, "workspace path=${workspace.absolutePath} exists=$workspaceExists")
        assertTrue(
            "Consonancia workspace not found at ${workspace.absolutePath}. Re-seed it with: " +
                "adb shell \"cd /sdcard/Download && tar -cf - $WORKSPACE_FOLDER_NAME | run-as com.pokerpgplayer.app.debug sh -c 'cd files && tar -xf -'\"",
            workspaceExists
        )

        Log.i(TAG, "SPRINT41_2_DIAG: launching Consonancia workspace")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "RuntimeActivity reached RESUMED — observation window is ${OBSERVATION_WINDOW_MS}ms; Ti should now manually test D-Pad Up/Down/Left/Right, X/C/Z, and menu/back.")

        Thread.sleep(OBSERVATION_WINDOW_MS)

        // One screenshot at the end of the window as baseline evidence
        // — Ti's own manual screen capture (per the Sprint 35 QA
        // addendum) during live interaction remains the primary
        // evidence mechanism here, since an automated mid-window
        // screenshot can't know when Ti is actually mid-test.
        try {
            val outputFile = File(context.filesDir, "sprint41_2_consonancia_end_of_window.png")
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val success = device.takeScreenshot(outputFile)
            val exists = outputFile.exists()
            val size = if (exists) outputFile.length() else -1L
            Log.i(TAG, "end-of-window screenshot: path=${outputFile.absolutePath} takeScreenshotReturned=$success exists=$exists sizeBytes=$size")
        } catch (t: Throwable) {
            Log.w(TAG, "end-of-window screenshot threw: ${t::class.java.simpleName}: ${t.message}")
        }

        Log.i(TAG, "observation window complete — closing.")
        scenario.close()

        Log.i(TAG, "===== TEST_FINISHED: launchConsonanciaWorkspace_forManualMovementSanityCheck =====")
    }
}

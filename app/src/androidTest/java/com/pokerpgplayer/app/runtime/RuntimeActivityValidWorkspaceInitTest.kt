package com.pokerpgplayer.app.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 10 — Valid Workspace Native chdir / Minimal mkxp-z Init Attempt.
 *
 * **Injects a real, existing, empty app-private directory** — the
 * opposite fixture from [RuntimeActivityInvalidGamePathTest]'s
 * deliberately-missing path. Sprint 9 proved native `main()` reads
 * `RuntimeActivity.GAME_PATH` via JNI and correctly reports a *failure*
 * when the path doesn't exist. This test answers the next question: when
 * the path *does* exist, does native `main()` actually pass
 * `directoryExists()`/`setCurrentDirectory()` and proceed into config
 * load — and can that be proven *positively*, not merely inferred from
 * the absence of the failure line?
 *
 * **Why an empty directory is enough, and why success isn't silent here**
 * (see the approved Sprint 10 plan §1 for the full, source-grounded
 * explanation): reading `main.cpp` directly, immediately after
 * `setCurrentDirectory()` and config load, `printRgssVersion()` calls
 * `Debug() << "RGSS version " + ver + " (RPG Maker " + maker + ")"` —
 * same `Debug()`/logcat-tag-`"mkxp"`/level-`DEBUG` mechanism Sprint 9
 * already proved works. This line can only be reached by passing
 * `directoryExists()`, calling `setCurrentDirectory()`, and completing
 * config load — a real, positive checkpoint, not an absence-of-failure
 * inference. Also confirmed directly: `config.cpp`'s own post-processing
 * unconditionally resolves `rgssVersion` from its default `0` to `1`
 * before `main.cpp`'s `assert(conf.rgssVersion >= 1 && conf.rgssVersion <= 3)`
 * runs, regardless of whether a `Game.ini` exists to auto-detect from —
 * so this assert cannot fire for an empty workspace, and the expected
 * logcat line is exactly `"RGSS version 1 (RPG Maker XP)"`.
 *
 * **This test does not add, modify, or rebuild any native code** — it
 * exercises a success path that already exists in the native source Ti
 * already built (Sprint 4/5) and already packaged (Sprint 7), using the
 * exact same `RuntimeActivity`/`ActivityScenario` mechanism proven across
 * Sprint 8/9. No new production code, no new Activity, no SAF picker, no
 * `MANAGE_EXTERNAL_STORAGE`.
 *
 * **Same evidence caveat as every prior Sprint 8/9 test:** `RuntimeActivity`'s
 * superclass chain can still reach the selected fork's documented
 * `System.exit(0)` behavior once native execution proceeds far enough —
 * if that happens, the whole instrumentation process terminates
 * immediately. A JUnit FAIL, or the process dying outright, is still an
 * **acceptable** result here, provided logcat already shows the positive
 * `"RGSS version 1 (RPG Maker XP)"` line first. See this sprint's README
 * section for the exact `adb logcat` filter and PASS/PARTIAL
 * PASS/INCONCLUSIVE/FAIL definitions.
 *
 * **Never reachable from any app screen** — same as every other test in
 * this package, the only way to run this is Android Studio's own
 * instrumented test runner or `./gradlew connectedAndroidTest`.
 *
 * **Does not assert:** title screen, rendering, audio, input, game boot,
 * or gameplay of any kind — none of that is in scope for Sprint 10. Only
 * the positive `"RGSS version"` checkpoint (and, as a bonus if it
 * appears, further SDL subsystem/window-creation logs) is this sprint's
 * target — never claimed as more than that.
 *
 * **v0.0.15 harness fix — why `scenario.close()` is bounded here, not
 * called directly:** Ti's first real-device run captured the positive
 * `"RGSS version 1 (RPG Maker XP)"` evidence successfully, but the
 * `androidTest` run itself was cancelled by the test framework
 * (`io.grpc.StatusRuntimeException: CANCELLED`, 0 tests reported)
 * because the app never finished tearing down. Root cause, found by
 * reading `SDLActivity.onDestroy()` directly: when the native thread is
 * still running (which it is here — a valid workspace lets `main()`
 * proceed into mkxp-z's real, long-running engine loop, confirmed by the
 * logged OpenGL backend/device info), `onDestroy()` calls
 * `SDLActivity.mSDLThread.join()` with **no timeout**, blocking the
 * Android main thread until the native loop notices the quit signal
 * (`nativeSendQuit()`) and actually returns from `main()` — which is not
 * guaranteed to happen quickly, or at all, for a freshly-initialized
 * engine. [RuntimeActivityInvalidGamePathTest] never hit this, because
 * its native `main()` had already returned (via the `directoryExists()`
 * failure branch) *before* `scenario.close()` ever ran, so `join()`
 * there returns instantly on an already-dead thread. This is genuine
 * engine behavior, not a defect in this test or in `RuntimeActivity`/
 * `SDLActivity` — no production or bridge file is changed here. Instead,
 * `scenario.close()` (which triggers that same blocking `onDestroy()`
 * internally) is invoked on a separate daemon thread with a bounded
 * [CLOSE_TIMEOUT_MS] wait: if it completes in time, great; if not, this
 * test simply stops waiting and returns rather than hanging the whole
 * instrumentation run — the positive native evidence was already
 * captured in logcat well before this point, so a slow/incomplete
 * teardown here doesn't invalidate it.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityValidWorkspaceInitTest {

    companion object {
        private const val TAG = "RuntimeActivityValidWorkspaceInitTest"

        /**
         * Same proven margin as Sprint 9's own diagnostic step 2 fix
         * (raised from an initial 4000ms once that turned out to leave
         * no confirmed scheduling margin for the native thread itself).
         * Reused here directly rather than starting back at a shorter
         * value, since there is no reason to expect this fixture needs
         * less margin than the invalid-path one did.
         */
        private const val NATIVE_STARTUP_WAIT_MS = 10000L

        /**
         * How long this test waits for `scenario.close()` (and the
         * blocking `SDLActivity.onDestroy()` → `mSDLThread.join()` it
         * triggers internally) before giving up and moving on. Not a
         * claim that the native engine actually finishes tearing down by
         * this point — only that this test stops waiting for it to. See
         * this class's own kdoc for the full root-cause explanation of
         * why this bound exists at all.
         */
        private const val CLOSE_TIMEOUT_MS = 15000L
    }

    @Test
    fun runtimeActivity_injectedWithValidEmptyWorkspace_reachesConfigLoadCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // A real, existing, empty directory — the opposite fixture from
        // RuntimeActivityInvalidGamePathTest's deliberately-missing path.
        // If a previous, interrupted test run left stale contents behind,
        // clear them first so this is genuinely a fresh, empty directory
        // each time, not an assumption.
        val workspace = File(context.filesDir, "sprint10-valid-empty-workspace")
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

        // Same RESUMED-transition reasoning as every prior Sprint 8/9
        // test — the native SDLMain thread only starts once the surface
        // is ready and the Activity is resumed, so reaching RESUMED
        // matters, not just launching alone.
        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native SDLMain startup before closing the scenario.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "Wait complete — closing the scenario now.")

        // Deliberately no assertions here — see this class's own kdoc for
        // why. By this point, native main.cpp should have already passed
        // directoryExists()/setCurrentDirectory() against this real,
        // existing workspace, completed config load, and logged
        // "RGSS version 1 (RPG Maker XP)" via Debug() (tag "mkxp", level
        // DEBUG) — a positive checkpoint, not an absence-of-failure
        // inference. Whether the instrumentation process is still alive
        // to reach this exact line is not something this test can
        // control or needs to assert on — logcat is what Ti reads to
        // confirm the result, per the approved Sprint 10 PASS/PARTIAL
        // PASS/INCONCLUSIVE/FAIL definitions.
        //
        // v0.0.15: scenario.close() is not called directly here. It
        // triggers SDLActivity.onDestroy(), which — when the native
        // thread is still running its own real engine loop, as it is for
        // a valid workspace — calls mSDLThread.join() with no timeout,
        // blocking the main thread until that loop exits on its own. Ti's
        // first real-device run of this exact test was cancelled by the
        // test framework for exactly this reason (see this class's own
        // kdoc). Running the close attempt on a separate daemon thread
        // with a bounded wait means this test stops waiting rather than
        // hanging the whole instrumentation run — the native evidence
        // this test exists to capture was already written to logcat
        // well before this point, so a slow or incomplete teardown here
        // does not affect it.
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
            Log.w(TAG, "scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if the native SDL/mkxp main loop is still running (see this class's own kdoc). Not failing the test for this; the positive native evidence was already captured in logcat before this point.")
        } else {
            Log.i(TAG, "scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
        }
    }
}

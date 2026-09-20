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
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Sprint 9 — Native mkxp-z main GAME_PATH Read / Workspace chdir Attempt.
 *
 * **Deliberately injects a path that does not exist**, to force the
 * native side's own, already-existing failure path — this is the whole
 * point, not an error in this test. Sprint 8 proved `RuntimeActivity`
 * receives a workspace path and sets its own `GAME_PATH` field correctly,
 * but never got native-side confirmation that `main()` actually reads
 * that value, because the success path in `main.cpp` is completely
 * silent by design (see the approved Sprint 9 plan §3 for the full,
 * source-grounded explanation — there is no `"Game path:"` log line
 * anywhere in the selected fork; that was an incorrect assumption in
 * Sprint 8's own plan, corrected here). The **only** way to get positive,
 * native-originated log evidence using the exact code already built into
 * the currently-packaged `.so` files is to deliberately trigger the one
 * branch that *does* log: `main.cpp`'s `directoryExists(dataDir)` check
 * failing, which calls `showInitError()` → `Debug()` →
 * `__android_log_write(ANDROID_LOG_DEBUG, "mkxp", ...)`, echoing the
 * exact invalid path back in the message
 * `"Failed to set current directory to <path>"`.
 *
 * **This test does not add, modify, or rebuild any native code.** It
 * exercises a failure branch that already exists in the native source
 * Ti already built (Sprint 4/5) and already packaged (Sprint 7) —
 * nothing here requires a new native build.
 *
 * **Same evidence caveat as [RuntimeActivityLaunchTest]:** this is not a
 * pass/fail correctness test in the usual sense. `RuntimeActivity`'s
 * superclass chain can still reach the selected fork's documented
 * `System.exit(0)` behavior once native execution proceeds far enough
 * past the failure this test is designed to trigger — if that happens,
 * the whole instrumentation process terminates immediately. **A JUnit
 * FAIL, or the process dying outright, is still an acceptable outcome
 * here, provided logcat already shows the native `"mkxp"`-tagged failure
 * line with the exact injected path echoed back before that happened.**
 * See this sprint's README section for the exact `adb logcat` filter
 * terms and run steps.
 *
 * **Never reachable from any app screen** — same as every other test in
 * this package, the only way to run this is Android Studio's own
 * instrumented test runner or `./gradlew connectedAndroidTest`.
 *
 * **Does not assert:** title screen, rendering, audio, input, game boot,
 * gameplay, or chdir *success* of any kind — none of that is in scope
 * for Sprint 9. A **valid**-path confirmation test (demonstrating the
 * *absence* of this same failure line) is explicitly optional/deferred
 * per the approved Sprint 9 scope correction, since a silent success path
 * is inherently weaker evidence than this test's own positive failure
 * proof — [RuntimeActivityLaunchTest] already covers that fixture if it's
 * wanted later.
 *
 * **Sprint 9 hotfix — root cause of the missing native evidence:** Ti's
 * first real-device run of this test reached `RESUMED`, then went
 * straight through `PAUSED` → `STOPPED` → `DESTROYED` with no `"mkxp"`
 * logcat line at all. The original version of this test called
 * `scenario.close()` on the very next line after `moveToState(RESUMED)`,
 * with no wait in between. `moveToState(RESUMED)` only guarantees that
 * `Activity.onResume()` has returned — it does not wait for the
 * asynchronous native `SDLMain` thread (started separately, once the
 * render surface is also ready) to actually run far enough to reach C's
 * `main()`. Closing the scenario immediately very likely tore the
 * Activity down before that thread was ever scheduled. The fix is a
 * bounded [Thread.sleep] between reaching `RESUMED` and calling
 * `scenario.close()`, giving the native thread real wall-clock time to
 * start, read `GAME_PATH` via JNI, and hit the `directoryExists()`
 * failure this test is designed to trigger.
 *
 * **Sprint 9 diagnostic step 2 — the 4-second wait still wasn't enough,
 * and why the original `mBrokenLibraries` suspicion was wrong:** with the
 * bounded wait in place, Ti's next run showed the *entire* SDL lifecycle
 * completing correctly — `onCreate()`, `nativeSetupJNI()`, `onResume()`,
 * `surfaceCreated()`, `surfaceChanged()`, and finally
 * `onWindowFocusChanged(): true` — all three conditions
 * `handleNativeState()` gates on (`mSurface.mIsSurfaceReady`, `mHasFocus`,
 * `mIsResumedCalled`) genuinely became `true`. The appearance of the
 * `nativeSetupJNI()` lines specifically rules out `SDLActivity`'s own
 * `mBrokenLibraries` flag as the blocker — reading `onCreate()` directly,
 * `SDL.setupJNI()` (which logs `nativeSetupJNI()`) is only reached *after*
 * an early-return guarded by `if (mBrokenLibraries) { ...; return; }`, so
 * library loading and the SDL C/Java version check must have both already
 * succeeded. Still, `"Running main function ... from library ..."` (the
 * line logged immediately before the actual native call) never appeared.
 * The most likely remaining explanation: `mSDLThread.start()` is
 * non-blocking — it schedules a new thread but does not wait for it to
 * run. If the full resume→surface→focus sequence itself consumes a
 * meaningful fraction of the wait window on a real device under
 * instrumentation (plausibly with additional OEM-specific overhead), the
 * newly-started thread may simply not have been scheduled long enough to
 * log anything before `scenario.close()` ran. This revision (a) extends
 * the wait to [NATIVE_STARTUP_WAIT_MS] `10000`ms for more margin, and (b)
 * logs `SDLActivity`'s own `mBrokenLibraries`/`mHasFocus`/
 * `mIsResumedCalled`/`mCurrentNativeState`/`mNextNativeState` fields
 * directly — all already `public static` on the copied `SDLActivity`, so
 * this needs no reflection and no change to that file at all — as a
 * direct, positive confirmation of exactly which gate condition (if any)
 * is still unmet, rather than continuing to infer it solely from which
 * downstream logs are absent.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityInvalidGamePathTest {

    companion object {
        private const val TAG = "RuntimeActivityInvalidGamePathTest"

        /**
         * How long to wait after [Lifecycle.State.RESUMED] before closing
         * the scenario. Not a precise, principled figure — a generous
         * margin to give the native `SDLMain` thread real wall-clock time
         * to start and reach `main()`'s `GAME_PATH` read, on a real
         * device, without waiting so long the test suite becomes
         * noticeably slow to run. Raised from `4000` to `10000` in Sprint
         * 9 diagnostic step 2 — the shorter wait was enough for the full
         * SDL lifecycle (resume/surface/focus) to complete, but left no
         * confirmed margin for the newly-started native thread itself to
         * actually get scheduled and log anything before the scenario
         * closed. If native evidence still doesn't appear with this much
         * margin, that points at something other than simple timing —
         * the new diagnostic field reads below are what should answer
         * that directly.
         */
        private const val NATIVE_STARTUP_WAIT_MS = 10000L
    }

    @Test
    fun runtimeActivity_injectedWithNonExistentPath_triggersNativeDirectoryExistsFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Deliberately never created. If a previous, interrupted test run
        // somehow left this directory behind, delete it first — the whole
        // point of this fixture is that the path does NOT exist when
        // RuntimeActivity launches, so this precondition is worth
        // guaranteeing explicitly rather than assuming.
        val nonExistentPath = File(context.filesDir, "sprint9-invalid-game-path-does-not-exist")
        if (nonExistentPath.exists()) {
            nonExistentPath.deleteRecursively()
        }

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, nonExistentPath.absolutePath)
        }

        // Same RESUMED-transition reasoning as RuntimeActivityLaunchTest —
        // the native SDLMain thread only starts once the surface is ready
        // and the Activity is resumed, so reaching RESUMED matters, not
        // just launching alone.
        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        // Sprint 9 hotfix: the native SDLMain thread starts asynchronously
        // once the surface is also ready — moveToState(RESUMED) returning
        // does not mean that thread has run yet, only that onResume()
        // itself has returned. Ti's first real-device run closed the
        // scenario immediately here and saw no native "mkxp" evidence at
        // all before the Activity was torn down (RESUMED straight through
        // to DESTROYED). This bounded wait gives the native thread real
        // wall-clock time to start, read GAME_PATH via JNI, and hit the
        // directoryExists() failure this test exists to trigger — see
        // this class's own kdoc for the full explanation.
        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native SDLMain startup before closing the scenario.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "Wait complete.")

        // Sprint 9 diagnostic step 2: direct, positive confirmation of
        // SDLActivity's own gate state, rather than continuing to infer
        // it solely from which downstream logs are absent. All fields
        // read here are already `public static` on the copied
        // SDLActivity.java — no reflection needed, no change to that
        // file. If mBrokenLibraries is unexpectedly true, or any of
        // mHasFocus/mIsResumedCalled is unexpectedly false, or
        // mCurrentNativeState never reached RESUMED despite
        // mNextNativeState being RESUMED, that directly names which gate
        // condition is still unmet — see this class's own kdoc.
        Log.i(TAG, "SDLActivity.mBrokenLibraries=${SDLActivity.mBrokenLibraries}")
        Log.i(TAG, "SDLActivity.mHasFocus=${SDLActivity.mHasFocus}")
        Log.i(TAG, "SDLActivity.mIsResumedCalled=${SDLActivity.mIsResumedCalled}")
        Log.i(TAG, "SDLActivity.mCurrentNativeState=${SDLActivity.mCurrentNativeState}")
        Log.i(TAG, "SDLActivity.mNextNativeState=${SDLActivity.mNextNativeState}")
        Log.i(TAG, "Closing the scenario now.")

        // Deliberately no assertions here — see this class's own kdoc for
        // why. By this point, native main.cpp should have already
        // attempted directoryExists() against nonExistentPath, found it
        // missing, and logged the failure via Debug() (tag "mkxp",
        // level DEBUG) before calling SDL_Quit(). Whether the
        // instrumentation process is still alive to reach this exact
        // line is not something this test can control or needs to
        // assert on — logcat is what Ti reads to confirm the result.
        scenario.close()
    }
}

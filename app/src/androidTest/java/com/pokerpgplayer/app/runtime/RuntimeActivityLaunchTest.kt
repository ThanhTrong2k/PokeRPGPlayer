package com.pokerpgplayer.app.runtime

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 8 — First Runtime Launch Boundary / GAME_PATH Injection.
 *
 * **This is not a pass/fail correctness test in the usual sense — it is
 * an internal launch *attempt*, whose real evidence is `adb logcat`, not
 * this test's own JUnit result.** [RuntimeActivity]'s superclass chain
 * ultimately reaches the selected mkxp-z fork's documented `System.exit(0)`
 * behavior once real native execution gets far enough — if that happens,
 * the entire instrumentation process terminates immediately, with no
 * opportunity for this test method to observe anything afterward or
 * report a clean result. **A process death here is expected, acceptable
 * forward progress** (per the approved Sprint 8 scope) provided logcat
 * shows the injected `GAME_PATH` was received and set correctly before
 * that happened — this test does not and cannot assert that from inside
 * itself; a human (Ti) must read the logcat capture separately. See this
 * sprint's README section for the exact `adb logcat` command and the
 * specific log lines to look for.
 *
 * **Deliberately does not use the SAF picker or [RuntimeWorkspaceService]'s
 * own mirroring** — per the approved scope correction, this sprint tests
 * the `GAME_PATH` injection / Activity launch boundary specifically, not
 * SAF mirroring again (Sprint 6 already covers that). This test creates
 * its own tiny, disposable, app-private directory directly and injects
 * that raw path.
 *
 * **Never reachable from any app screen** — the only way to run this is
 * Android Studio's own instrumented test runner or `./gradlew
 * connectedAndroidTest`, exactly like [NativeLibraryLoadSmokeTest].
 *
 * **Does not assert:** title screen, rendering, audio, input, game boot,
 * or gameplay of any kind — none of that is in scope for Sprint 8.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityLaunchTest {

    private lateinit var testWorkspaceDir: File

    @Before
    fun createMinimalTestWorkspace() {
        // A tiny, disposable, app-private directory — not a real game
        // folder, not produced by RuntimeWorkspaceService's own mirroring.
        // Its only job is to be a real, existing path for GAME_PATH to
        // point at, so the native side's own directoryExists() check
        // (confirmed present in main.cpp) passes and execution proceeds
        // past that specific gate. Nothing resembling real game assets is
        // placed inside it — this sprint is not testing asset loading.
        val context = ApplicationProvider.getApplicationContext<Context>()
        testWorkspaceDir = File(context.filesDir, "sprint8-runtime-activity-test-workspace")
        testWorkspaceDir.mkdirs()
    }

    @After
    fun cleanUpTestWorkspace() {
        // Best-effort only — this is disposable, app-private test scratch
        // space, not anything a real user's data ever touches.
        testWorkspaceDir.deleteRecursively()
    }

    @Test
    fun runtimeActivity_receivesInjectedGamePath_beforeNativeStartup() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, testWorkspaceDir.absolutePath)
        }

        // ActivityScenario.launch() takes the Activity to CREATED, then
        // STARTED. The native SDLMain thread only begins once the render
        // surface is ready and the Activity reaches RESUMED (confirmed by
        // reading SDLActivity.java directly during Sprint 8 planning) —
        // so this explicit transition matters, not just launching alone.
        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        // Deliberately no assertions here. By the time this line would
        // run, one of three things has already happened: (a) the native
        // side logged the injected GAME_PATH and proceeded, (b) it logged
        // the path and then failed cleanly via showInitError/SDL_Quit
        // (the test workspace is not a real game — this is an expected,
        // acceptable outcome), or (c) the process has already been
        // terminated by System.exit(0) and this code never executes at
        // all. All three are informative; none of them is something this
        // test can distinguish from inside itself. logcat is what Ti reads
        // to tell them apart — see this sprint's README section.
        scenario.close()
    }
}

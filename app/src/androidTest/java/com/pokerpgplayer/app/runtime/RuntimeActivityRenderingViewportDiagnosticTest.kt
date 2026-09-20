package com.pokerpgplayer.app.runtime

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
 * Sprint 28 — Rendering Viewport Diagnostic.
 *
 * **Context:** App v0.0.33's own `RuntimeActivityConfigOverlayLaunchTest`
 * (Sprint 27) reached the Essentials v21.1 title menu successfully, but
 * Ti reported the rendered output appears anchored in the lower-left
 * corner instead of scaled/centered/fullscreen — genuinely new territory
 * this project has not yet investigated, since every prior sprint's own
 * evidence was logcat/exception-focused, never actual rendered pixel
 * *layout* within the surface.
 *
 * **Root-cause hypothesis this test exists to gather confirming evidence
 * for** (full source trace in the Sprint 28 planning/closure documents):
 * mkxp-z's own `main.cpp` calls `SDL_CreateWindow(...)` using the game's
 * own logical resolution (`defScreenW`/`defScreenH` — `512x384` for this
 * Essentials v21.1 config, confirmed directly in its own `mkxp.json`
 * since Sprint 16) as the window's own requested size, then immediately
 * calls `SDL_GetWindowSize()` and posts that value as the *only*
 * `windowSizeMsg` the render thread's own `checkResize()` will see
 * unless a **real** `SDL_WINDOWEVENT_SIZE_CHANGED` event later corrects
 * it (`eventthread.cpp`). If that correction never arrives — plausible
 * if the underlying Android `SurfaceView` genuinely gets laid out at
 * that same small, literal `512x384` size rather than stretched to fill
 * the real screen — `graphics.cpp`'s own `recalculateScreenSize()`
 * (specifically its early-return path, active whenever `integerLastMileScaling`
 * is at its own documented default of `true` and `fixedAspectRatio` is
 * unset) never applies its own centering/letterboxing math, and OpenGL's
 * default bottom-left coordinate origin would place a small, correctly-
 * rendered `512x384` image in the lower-left of whatever larger surface
 * actually exists — matching the reported symptom precisely.
 *
 * **This test does not attempt a fix.** It reuses the exact, already-
 * proven overlay-launch pattern from `RuntimeActivityConfigOverlayLaunchTest`
 * (Sprint 27) — same base workspace, same two mitigations, same real
 * `RuntimeConfigOverlayService` — and adds new, additional diagnostic
 * logging: real Android display metrics (via `WindowManager`, the same
 * API `SDLActivity.java` itself already uses internally for its own
 * fullscreen-layout check) and the SDL surface's own actual, real,
 * laid-out pixel dimensions (via [RuntimeActivity.currentSurfaceDimensionsForDiagnostics],
 * a small, new, diagnostic-only accessor added to `RuntimeActivity`
 * itself this sprint — reads existing state only, changes no behavior),
 * captured at multiple checkpoints across the same observation window
 * already proven to reach the title menu.
 *
 * **Does not implement input mapping, does not hide the issue behind a
 * UI overlay, does not stretch anything, does not modify any Pokémon
 * Essentials original file, does not change the Runtime Config Overlay
 * architecture.** No native C++ change — the hypothesis above is traced
 * entirely from already-published fork source, not modified.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityRenderingViewportDiagnosticTest {

    companion object {
        private const val TAG = "RuntimeActivityRenderingViewportDiagnosticTest"

        private const val BASE_WORKSPACE_FOLDER_NAME = "essentials-v211-original-workspace"
        private const val DISPOSABLE_WORKSPACE_FOLDER_NAME = "essentials-v211-stage3-viewport-diagnostic-workspace"

        private const val NATIVE_STARTUP_WAIT_MS = 20000L
        private const val CHECKPOINT_INTERVAL_MS = 20000L
        private const val TOTAL_WAIT_MS = 90000L
        private const val CLOSE_TIMEOUT_MS = 15000L

        /** The game's own logical resolution, confirmed directly in this fixture's own mkxp.json since Sprint 16. Not asserted against — logged for direct comparison only. */
        private const val KNOWN_LOGICAL_GAME_WIDTH = 512
        private const val KNOWN_LOGICAL_GAME_HEIGHT = 384
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

    /**
     * Logs real Android display metrics — the same `WindowManager`/
     * `DisplayMetrics` approach `SDLActivity.java` itself already uses
     * internally (its own `bFullscreenLayout` check) — plus the SDL
     * surface's own current, real, laid-out size via [RuntimeActivity.currentSurfaceDimensionsForDiagnostics].
     * Read-only observation only; asserts nothing.
     */
    private fun logDisplayAndSurfaceMetrics(context: Context, label: String, activity: RuntimeActivity?) {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val realMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(realMetrics)
            Log.i(TAG, "[$label] EVIDENCE: real display metrics = ${realMetrics.widthPixels}x${realMetrics.heightPixels} (density=${realMetrics.density})")
        } catch (t: Throwable) {
            Log.w(TAG, "[$label] Failed to read real display metrics: ${t.message}")
        }

        if (activity == null) {
            Log.w(TAG, "[$label] No RuntimeActivity instance available to query surface dimensions from.")
            return
        }
        val surfaceDimensions = activity.currentSurfaceDimensionsForDiagnostics()
        if (surfaceDimensions != null) {
            val (w, h) = surfaceDimensions
            Log.i(TAG, "[$label] EVIDENCE: SDL surface real dimensions = ${w}x${h}")
            Log.i(TAG, "[$label] EVIDENCE: known logical game resolution = ${KNOWN_LOGICAL_GAME_WIDTH}x${KNOWN_LOGICAL_GAME_HEIGHT}")
            if (w == KNOWN_LOGICAL_GAME_WIDTH && h == KNOWN_LOGICAL_GAME_HEIGHT) {
                Log.w(TAG, "[$label] NOTABLE: SDL surface dimensions exactly match the game's own logical resolution — consistent with the Sprint 28 root-cause hypothesis (winSize never corrected to the real, larger surface size).")
            }
        } else {
            Log.w(TAG, "[$label] currentSurfaceDimensionsForDiagnostics() returned null — surface not yet created at this checkpoint.")
        }
    }

    @Test
    fun launchAttempt_withGeneratedOverlay_capturesRenderingViewportDiagnostics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseWorkspace = File(context.filesDir, BASE_WORKSPACE_FOLDER_NAME)

        // Test Environment Hardening: self-heal the fixture from the
        // external staging path if app-private storage was wiped since
        // it was last seeded — see EssentialsFixtureSeeder's own kdoc.
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

        val disposableWorkspace = File(context.filesDir, DISPOSABLE_WORKSPACE_FOLDER_NAME)
        if (disposableWorkspace.exists()) {
            disposableWorkspace.deleteRecursively()
        }
        baseWorkspace.copyRecursively(disposableWorkspace, overwrite = true)
        Log.i(TAG, "Disposable workspace created at: ${disposableWorkspace.absolutePath}")

        val profile = RuntimeConfigProfile(
            enabledMitigations = listOf(KnownMitigationIds.ZLIB_PRELOAD, KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE)
        )

        val disposableMkxpJsonFile = File(disposableWorkspace, "mkxp.json")
        val originalTextInCopy = disposableMkxpJsonFile.readText(Charsets.UTF_8)
        val result = overlayService.generateOverlay(originalTextInCopy, profile)

        if (result.overlayStatus != OverlayStatus.GENERATED_TEST_ONLY || result.overlayConfigText == null) {
            fail("Overlay generation did not succeed (overlayStatus=${result.overlayStatus}, errorMessage=${result.errorMessage}) — same real service already proven in App v0.0.33; not expected to fail here.")
            return
        }

        disposableMkxpJsonFile.writeText(result.overlayConfigText, Charsets.UTF_8)
        result.generatedAuxiliaryFiles.forEach { (fileName, content) ->
            File(disposableWorkspace, fileName).writeText(content, Charsets.UTF_8)
        }
        Log.i(TAG, "Overlay applied to disposable workspace (appliedMitigations=${result.audit.lastAppliedMitigations}).")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, disposableWorkspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms before the first diagnostic checkpoint.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)

        scenario.onActivity { activity ->
            logDisplayAndSurfaceMetrics(context, "checkpoint-0-after-native-startup", activity)
        }
        captureScreenshot(context, "sprint28_viewport_diagnostic_checkpoint0.png")

        var elapsedMs = 0L
        while (elapsedMs < TOTAL_WAIT_MS) {
            Thread.sleep(CHECKPOINT_INTERVAL_MS)
            elapsedMs += CHECKPOINT_INTERVAL_MS
            val label = "checkpoint-${elapsedMs / 1000}s"
            Log.i(TAG, "Checkpoint: ${elapsedMs}ms elapsed of ${TOTAL_WAIT_MS}ms.")
            scenario.onActivity { activity ->
                logDisplayAndSurfaceMetrics(context, label, activity)
            }
            captureScreenshot(context, "sprint28_viewport_diagnostic_$label.png")
        }
        Log.i(TAG, "Observation window complete.")

        Log.i(TAG, "EVIDENCE SUMMARY: compare the SDL surface dimensions logged at each checkpoint above against the known logical game resolution (${KNOWN_LOGICAL_GAME_WIDTH}x${KNOWN_LOGICAL_GAME_HEIGHT}) and the real display metrics. If the surface dimensions match the logical resolution rather than the real display size at every checkpoint, this directly confirms the Sprint 28 root-cause hypothesis. Cross-reference against the pulled screenshots for the exact rendered position/size.")

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

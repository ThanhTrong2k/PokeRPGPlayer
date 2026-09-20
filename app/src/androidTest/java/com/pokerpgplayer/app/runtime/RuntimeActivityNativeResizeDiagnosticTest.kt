package com.pokerpgplayer.app.runtime

import com.pokerpgplayer.app.data.config.DiagnosticProfiles
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 29 — Native/SDL/mkxp-z Viewport and Resize Pipeline Diagnostic,
 * Test Evidence Stabilization revision.
 *
 * **Why this revision exists:** Ti's own review of the prior evidence
 * pull found app-internal storage contained *only* `profileInstalled`
 * (Android's own automatic baseline-profile marker, unrelated to this
 * project's own code) — no disposable workspace, no screenshots, no
 * diagnostic log lines of any kind, despite Android itself confirming a
 * real portrait→landscape surface transition in system logs. This means
 * the test's own body never actually ran to completion (or never ran at
 * all) in that specific attempt — not that this class's own logging was
 * silently broken. This revision makes that distinction directly,
 * immediately observable, and gives every log line short, stable,
 * typo-resistant tags.
 *
 * **Tags, deliberately short and stable, replacing this class's own
 * earlier, longer tag name:**
 * - `PokeRPGSprint29` — general test flow: fixture check, overlay
 *   generation, launch, checkpoints, close.
 * - `PokeRPGEvidence` — specifically screenshot/artifact evidence:
 *   filename, path, exists, size, pass/fail per artifact.
 * - `PokeRPGSeeder` — see [EssentialsFixtureSeeder], used for fixture
 *   seeding specifically.
 *
 * **Root-cause hypothesis for the underlying rendering issue is
 * unchanged from the prior revision** (see the separate Sprint 29
 * planning/closure documents and the still-unapplied, proposed native
 * diagnostic patch) — **this revision does not implement or attempt any
 * viewport/rendering fix.** It exists solely to stabilize the seeder and
 * evidence-gathering pipeline before that investigation continues.
 *
 * **Evidence artifacts (screenshots, the disposable workspace) are
 * never automatically cleaned up by this test** — they remain in app-
 * private storage after the test finishes specifically so Ti can pull
 * them afterward; only a *fresh run* clears and recreates the disposable
 * workspace at its own start (the same behavior every diagnostic test in
 * this project has always had).
 *
 * **App v0.0.39 hotfix:** App v0.0.38 added `aspect-fit-render` to
 * [RuntimeConfigOverlayService]'s own known mitigation catalog, but this
 * class's own [RuntimeConfigProfile] never actually requested it — a
 * real regression, since the whole point of the mitigation is to be
 * exercised by this exact diagnostic launch. Fixed here: the profile
 * now requests all three mitigations, and an explicit `assertTrue` (not
 * just a log line) fails the test loudly if `aspect-fit-render` isn't
 * genuinely applied and `"fixedAspectRatio": true` isn't genuinely
 * present in the generated overlay text, so this specific regression
 * cannot silently recur. **This still does not, by itself, confirm the
 * rendering issue is fixed** — only that the config-level mitigation
 * this diagnostic depends on is actually being exercised this time. The
 * native pointer-to-`bool` patch proposed in Sprint 30 remains
 * unapplied; Ti's own real-device visual result is still required
 * before claiming anything about the actual rendered output.
 *
 * **App v0.0.40 (Sprint 30.1) — auto-seeding removed from this test's
 * own critical path.** App v0.0.39's own [EssentialsFixtureSeeder]-based
 * flow reported `SKIPPED`, not a real failure — after that run, app-
 * internal storage contained only `profileInstalled`, meaning the
 * shell-pipe auto-seed did not actually populate the fixture this time,
 * even though Ti separately confirmed the *identical* shell-pipe
 * command works correctly when run manually via `adb shell`. Rather
 * than keep debugging why the *automated*, in-test invocation of that
 * same command behaves differently, this revision stops depending on it
 * here entirely: Ti seeds the fixture manually, once, before running
 * this test, and this test now only *verifies* — hard-failing loudly,
 * never skipping — that the fixture genuinely exists and is complete.
 * [EssentialsFixtureSeeder] itself is untouched and remains available
 * for future use; it is simply not called by this class anymore.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityNativeResizeDiagnosticTest {

    companion object {

        /**
         * Sprint 49 — false for ordinary diagnostic runs with minimal preloadScript.
         * Flip to true to restore Sprint41-44 verbose diagnostics.
         */
        private const val USE_VERBOSE_DIAGNOSTIC_PROFILE = false

        private const val TAG_SPRINT29 = "PokeRPGSprint29"
        private const val TAG_SPRINT30 = "PokeRPGSprint30"
        private const val TAG_EVIDENCE = "PokeRPGEvidence"

        private const val BASE_WORKSPACE_FOLDER_NAME = "essentials-v211-original-workspace"
        private const val DISPOSABLE_WORKSPACE_FOLDER_NAME = "essentials-v211-stage3-resize-diagnostic-workspace"

        private const val FINE_CHECKPOINT_INTERVAL_MS = 5000L
        private const val FINE_CHECKPOINT_TOTAL_MS = 30000L
        private const val COARSE_CHECKPOINT_INTERVAL_MS = 20000L
        private const val COARSE_CHECKPOINT_TOTAL_MS = 60000L
        private const val CLOSE_TIMEOUT_MS = 15000L

        private const val KNOWN_LOGICAL_GAME_WIDTH = 512
        private const val KNOWN_LOGICAL_GAME_HEIGHT = 384

        /** Per this sprint's own explicit requirement: below this, log WARNING and fail the diagnostic evidence check. */
        private const val MIN_VALID_SCREENSHOT_BYTES = 10 * 1024L

        /** Exact text expected inside the overlay if aspect-fit-render was genuinely applied — kept as a plain constant (not built inline in a string template) specifically to avoid ambiguous nested-quote string templates. */
        private const val FIXED_ASPECT_RATIO_TRUE_MARKER = "\"fixedAspectRatio\": true"

        /** Sprint 30.2 — extracts the actual VALUE of an existing "fullscreen" key (if present), for a before/after comparison confirming aspect-fit-render never touches it — replaces the old, misleading bare-"contains" check. */
        private val FULLSCREEN_VALUE_PATTERN = Regex("\"fullscreen\"\\s*:\\s*(true|false)")

        /** App v0.0.40 (Sprint 30.1) — the same minimal set of paths EssentialsFixtureSeeder already checks, verified here directly instead, since this class no longer calls that seeder. */
        private val KEY_FIXTURE_ENTRIES = listOf("Game.exe", "Game.ini", "mkxp.json", "Data", "Graphics", "Audio", "Plugins")

        /** The exact, Ti-confirmed-working manual seed command — included verbatim in every failure message this class can produce for a missing/incomplete fixture, so there's never any ambiguity about what to run next. Plain `val`, not `const val`, since it's a string template referencing another constant. */
        private val MANUAL_SEED_COMMAND =
            "adb shell \"cd /sdcard/Download && tar -cf - $BASE_WORKSPACE_FOLDER_NAME | run-as com.pokerpgplayer.app.debug sh -c 'cd files && tar -xf -'\""
    }

    private val overlayService = RuntimeConfigOverlayService()

    /**
     * Captures one screenshot and logs five separate, explicit facts
     * about it under [TAG_EVIDENCE]: filename, full path,
     * `takeScreenshot()`'s own return value, whether the file exists
     * afterward, and its size in bytes. Returns `true` if this artifact
     * passes the evidence check (file exists and is at least
     * [MIN_VALID_SCREENSHOT_BYTES]), `false` otherwise — the caller
     * collects these results and fails the whole test at the end if any
     * artifact failed, without aborting the observation window early.
     */
    private fun captureScreenshot(context: Context, fileName: String): Boolean {
        val outputFile = File(context.filesDir, fileName)
        return try {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val takeScreenshotReturned = device.takeScreenshot(outputFile)
            val exists = outputFile.exists()
            val size = if (exists) outputFile.length() else -1L

            Log.i(TAG_EVIDENCE, "screenshot filename=$fileName")
            Log.i(TAG_EVIDENCE, "screenshot fullPath=${outputFile.absolutePath}")
            Log.i(TAG_EVIDENCE, "screenshot takeScreenshotReturned=$takeScreenshotReturned")
            Log.i(TAG_EVIDENCE, "screenshot fileExists=$exists")
            Log.i(TAG_EVIDENCE, "screenshot fileSizeBytes=$size")

            val passed = exists && size >= MIN_VALID_SCREENSHOT_BYTES
            if (!passed) {
                Log.w(TAG_EVIDENCE, "WARNING screenshot evidence check FAILED for $fileName — exists=$exists, size=$size bytes, minimum required=$MIN_VALID_SCREENSHOT_BYTES bytes. This artifact is not trustworthy as visual evidence.")
            } else {
                Log.i(TAG_EVIDENCE, "screenshot evidence check passed for $fileName")
            }
            passed
        } catch (t: Throwable) {
            Log.e(TAG_EVIDENCE, "screenshot capture threw for $fileName: ${t::class.java.simpleName}: ${t.message}")
            false
        }
    }

    private fun logDisplayAndSurfaceMetrics(context: Context, label: String, activity: RuntimeActivity?) {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val realMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(realMetrics)
            Log.i(TAG_SPRINT29, "[$label] real display metrics = ${realMetrics.widthPixels}x${realMetrics.heightPixels} (density=${realMetrics.density})")

            val orientationName = when (context.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
                Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
                else -> "UNDEFINED"
            }
            Log.i(TAG_SPRINT29, "[$label] Configuration.orientation = $orientationName")
        } catch (t: Throwable) {
            Log.w(TAG_SPRINT29, "[$label] Failed to read real display metrics: ${t.message}")
        }

        if (activity == null) {
            Log.w(TAG_SPRINT29, "[$label] No RuntimeActivity instance available to query surface dimensions from.")
            return
        }
        val surfaceDimensions = activity.currentSurfaceDimensionsForDiagnostics()
        if (surfaceDimensions != null) {
            val (w, h) = surfaceDimensions
            Log.i(TAG_SPRINT29, "[$label] SDL surface real dimensions = ${w}x${h} (known logical game resolution = ${KNOWN_LOGICAL_GAME_WIDTH}x${KNOWN_LOGICAL_GAME_HEIGHT})")
        } else {
            Log.w(TAG_SPRINT29, "[$label] currentSurfaceDimensionsForDiagnostics() returned null — surface not yet created at this checkpoint.")
        }
    }

    private fun currentDiagnosticMitigations(): List<String> =
        if (USE_VERBOSE_DIAGNOSTIC_PROFILE) {
            DiagnosticProfiles.verboseDiagnosticMitigations()
        } else {
            DiagnosticProfiles.normalDiagnosticMitigations()
        }

    @Test
    fun launchAttempt_withGeneratedOverlay_capturesFineGrainedResizeSequenceDiagnostics() {
        // Unconditional, first line of the test body — before any file
        // I/O, before touching context.filesDir at all — so this test's
        // own invocation is directly observable in logcat even if
        // everything after this line were to fail immediately. If this
        // line does not appear in a real evidence pull, the test never
        // ran (wrong instrumentation target, stale APK, or the test
        // crashed at JUnit setup before its own body started) — that is
        // a different problem than anything this class's own logic
        // could cause.
        Log.i(TAG_SPRINT29, "===== TEST_STARTED: launchAttempt_withGeneratedOverlay_capturesFineGrainedResizeSequenceDiagnostics =====")

        val context = ApplicationProvider.getApplicationContext<Context>()
        Log.i(TAG_SPRINT29, "app internal files absolute path=${context.filesDir.absolutePath}")

        val baseWorkspace = File(context.filesDir, BASE_WORKSPACE_FOLDER_NAME)

        // App v0.0.40 (Sprint 30.1) — hard verification only, no
        // auto-seeding attempt. Ti seeds the fixture manually, once,
        // via MANUAL_SEED_COMMAND, before running this test. This is a
        // deliberate, temporary pivot away from EssentialsFixtureSeeder
        // for THIS SPECIFIC test — see this class's own kdoc for why —
        // and a deliberate use of assertTrue/fail (never assumeTrue)
        // for this fixture precondition, so a missing/incomplete
        // fixture is a loud, visible FAILURE, never a silent SKIP.
        Log.i(TAG_SPRINT30, "internal workspace path=${baseWorkspace.absolutePath}")

        val workspaceExists = baseWorkspace.exists() && baseWorkspace.isDirectory
        Log.i(TAG_SPRINT30, "internal workspace exists=$workspaceExists")
        assertTrue(
            "Internal PE21 workspace not found at ${baseWorkspace.absolutePath}. " +
                "Run this manual seed command once, then re-run this test: $MANUAL_SEED_COMMAND",
            workspaceExists
        )

        val missingKeyEntries = mutableListOf<String>()
        for (entryName in KEY_FIXTURE_ENTRIES) {
            val entryFile = File(baseWorkspace, entryName)
            val present = entryFile.exists()
            Log.i(TAG_SPRINT30, "key-file-check $entryName present=$present at ${entryFile.absolutePath}")
            if (!present) missingKeyEntries += entryName
        }
        assertTrue(
            "Internal PE21 workspace at ${baseWorkspace.absolutePath} is missing key path(s): $missingKeyEntries. " +
                "Run this manual seed command once, then re-run this test: $MANUAL_SEED_COMMAND",
            missingKeyEntries.isEmpty()
        )
        Log.i(TAG_SPRINT30, "all key fixture entries verified present — proceeding.")

        val disposableWorkspace = File(context.filesDir, DISPOSABLE_WORKSPACE_FOLDER_NAME)
        if (disposableWorkspace.exists()) {
            disposableWorkspace.deleteRecursively()
        }
        baseWorkspace.copyRecursively(disposableWorkspace, overwrite = true)
        Log.i(TAG_SPRINT29, "disposable workspace created at: ${disposableWorkspace.absolutePath}")

        val profile = RuntimeConfigProfile(
            enabledMitigations = currentDiagnosticMitigations()
        )

        val disposableMkxpJsonFile = File(disposableWorkspace, "mkxp.json")
        val originalTextInCopy = disposableMkxpJsonFile.readText(Charsets.UTF_8)
        val result = overlayService.generateOverlay(originalTextInCopy, profile)

        if (result.overlayStatus != OverlayStatus.GENERATED_TEST_ONLY || result.overlayConfigText == null) {
            fail("Overlay generation did not succeed (overlayStatus=${result.overlayStatus}, errorMessage=${result.errorMessage}) — same real service already proven; not expected to fail here.")
            return
        }

        // v0.0.39 hotfix — v0.0.38 added the aspect-fit-render
        // mitigation to RuntimeConfigOverlayService but never actually
        // requested it in this test's own profile above, so the v0.0.38
        // diagnostic launch never tested it at all. Fixed here, plus an
        // explicit, hard assertion (not just a log line) that the
        // generated overlay text genuinely contains the expected key —
        // this must fail loudly if it's ever missing again, not just be
        // silently absent from a log Ti has to notice on their own.
        val appliedMitigations = result.audit.lastAppliedMitigations
        val zlibPreloadApplied = KnownMitigationIds.ZLIB_PRELOAD in appliedMitigations
        val asciiSafeTitleApplied = KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE in appliedMitigations
        val aspectFitRenderApplied = KnownMitigationIds.ASPECT_FIT_RENDER in appliedMitigations
        val overlayHasFixedAspectRatioTrue = result.overlayConfigText.contains(FIXED_ASPECT_RATIO_TRUE_MARKER)

        // Sprint 30.2 fix: the prior "overlay contains fullscreen key"
        // log was misleading — mkxp.json may legitimately already
        // contain "fullscreen": false (or true) on its own, unrelated
        // to aspect-fit-render, and a bare "contains" check can't tell
        // the difference between "already present, untouched" and
        // "inserted/altered by this mitigation." Compare the actual
        // VALUE before and after instead — aspect-fit-render must never
        // change it, so these two must always be identical.
        val originalFullscreenValue = FULLSCREEN_VALUE_PATTERN.find(originalTextInCopy)?.groupValues?.get(1)
        val overlayFullscreenValue = FULLSCREEN_VALUE_PATTERN.find(result.overlayConfigText)?.groupValues?.get(1)
        val fullscreenChangedByAspectFitRender = originalFullscreenValue != overlayFullscreenValue

        Log.i(TAG_SPRINT30, "appliedMitigations=$appliedMitigations")
        Log.i(TAG_SPRINT30, "zlib-preload applied=$zlibPreloadApplied")
        Log.i(TAG_SPRINT30, "ascii-safe-window-title applied=$asciiSafeTitleApplied")
        Log.i(TAG_SPRINT30, "aspect-fit-render applied=$aspectFitRenderApplied")
        Log.i(TAG_SPRINT30, "overlay contains fixedAspectRatio:true=$overlayHasFixedAspectRatioTrue")
        Log.i(TAG_SPRINT30, "original fullscreen value (absent if not present)=$originalFullscreenValue")
        Log.i(TAG_SPRINT30, "overlay fullscreen value (absent if not present)=$overlayFullscreenValue")
        Log.i(TAG_SPRINT30, "fullscreen changed by aspect-fit-render=$fullscreenChangedByAspectFitRender (must always be false)")

        assertTrue(
            "aspect-fit-render must actually be applied and overlayConfigText must contain fixedAspectRatio:true before this test launches — v0.0.38's own regression (mitigation added to the service but never requested in this profile) must not silently recur.",
            aspectFitRenderApplied && overlayHasFixedAspectRatioTrue
        )
        assertTrue(
            "aspect-fit-render must not insert or alter fullscreen — original value was $originalFullscreenValue, overlay value is $overlayFullscreenValue.",
            !fullscreenChangedByAspectFitRender
        )

        disposableMkxpJsonFile.writeText(result.overlayConfigText, Charsets.UTF_8)
        result.generatedAuxiliaryFiles.forEach { (fileName, content) ->
            File(disposableWorkspace, fileName).writeText(content, Charsets.UTF_8)
        }
        Log.i(TAG_SPRINT29, "overlay applied (appliedMitigations=${result.audit.lastAppliedMitigations}), including aspect-fit-render this time — see PokeRPGSprint30-tagged lines above for the explicit confirmation.")
        Log.i(TAG_SPRINT29, "native/SDL/mkxp SPRINT29_DIAG lines are NOT expected in this run unless Ti has separately applied and rebuilt the proposed native diagnostic patch. Their absence here is expected, not a bug, unless that patch was applied.")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, disposableWorkspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG_SPRINT29, "reached RESUMED — beginning fine-grained checkpoints every ${FINE_CHECKPOINT_INTERVAL_MS}ms for the first ${FINE_CHECKPOINT_TOTAL_MS}ms.")

        val failedEvidenceArtifacts = mutableListOf<String>()

        var elapsedMs = 0L
        while (elapsedMs < FINE_CHECKPOINT_TOTAL_MS) {
            Thread.sleep(FINE_CHECKPOINT_INTERVAL_MS)
            elapsedMs += FINE_CHECKPOINT_INTERVAL_MS
            val label = "fine-${elapsedMs / 1000}s"
            Log.i(TAG_SPRINT29, "checkpoint: ${elapsedMs}ms elapsed (fine-grained phase).")
            scenario.onActivity { activity -> logDisplayAndSurfaceMetrics(context, label, activity) }
            val fileName = "sprint29_resize_diagnostic_$label.png"
            if (!captureScreenshot(context, fileName)) failedEvidenceArtifacts += fileName
        }

        Log.i(TAG_SPRINT29, "fine-grained phase complete — continuing with coarse checkpoints every ${COARSE_CHECKPOINT_INTERVAL_MS}ms for another ${COARSE_CHECKPOINT_TOTAL_MS}ms.")
        var coarseElapsedMs = 0L
        while (coarseElapsedMs < COARSE_CHECKPOINT_TOTAL_MS) {
            Thread.sleep(COARSE_CHECKPOINT_INTERVAL_MS)
            coarseElapsedMs += COARSE_CHECKPOINT_INTERVAL_MS
            val label = "coarse-${(elapsedMs + coarseElapsedMs) / 1000}s"
            Log.i(TAG_SPRINT29, "checkpoint: ${elapsedMs + coarseElapsedMs}ms total elapsed (coarse phase).")
            scenario.onActivity { activity -> logDisplayAndSurfaceMetrics(context, label, activity) }
            val fileName = "sprint29_resize_diagnostic_$label.png"
            if (!captureScreenshot(context, fileName)) failedEvidenceArtifacts += fileName
        }
        Log.i(TAG_SPRINT29, "observation window complete.")
        Log.i(TAG_SPRINT29, "evidence artifacts (screenshots, disposable workspace) are left in place under ${context.filesDir.absolutePath} — not cleaned up by this test, so Ti can pull them afterward.")

        Log.i(TAG_SPRINT29, "EVIDENCE SUMMARY: compare the fine-grained (5s) checkpoints for exactly when SDL surface dimensions and Configuration.orientation transition, relative to when native mkxp logcat lines (RGSS version, renderer init) appear. If a native diagnostic patch has been separately applied and rebuilt by Ti, cross-reference against its own logged conf.fullscreen / winW,winH / winSize / scSize / glViewport values for the exact mismatch point.")

        val closeThread = Thread {
            try {
                scenario.close()
            } catch (t: Throwable) {
                Log.w(TAG_SPRINT29, "scenario.close() threw on the background close thread: ${t.message}")
            }
        }
        closeThread.isDaemon = true
        closeThread.start()
        closeThread.join(CLOSE_TIMEOUT_MS)

        if (closeThread.isAlive) {
            Log.w(TAG_SPRINT29, "scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if native execution is still active. Not failing the test for this.")
        } else {
            Log.i(TAG_SPRINT29, "scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
        }

        Log.i(TAG_SPRINT29, "===== TEST_FINISHED: launchAttempt_withGeneratedOverlay_capturesFineGrainedResizeSequenceDiagnostics =====")

        if (failedEvidenceArtifacts.isNotEmpty()) {
            fail("Diagnostic evidence check failed for ${failedEvidenceArtifacts.size} screenshot(s): $failedEvidenceArtifacts — each is missing or under $MIN_VALID_SCREENSHOT_BYTES bytes. See PokeRPGEvidence-tagged log lines above for exact details per artifact.")
        }
    }
}

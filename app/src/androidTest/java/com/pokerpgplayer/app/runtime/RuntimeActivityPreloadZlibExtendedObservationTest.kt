package com.pokerpgplayer.app.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 22 — Extended Observation of the `preloadScript`-Fixed Essentials Fixture.
 *
 * **Builds directly on App v0.0.26's own confirmed result:** with
 * `Zlib` pre-required via `mkxp.json`'s `preloadScript` mechanism, the
 * previously-observed `NameError: uninitialized constant
 * PluginManager::Zlib` dialog no longer appears. The runtime instead
 * reaches a state showing only the window title bar
 * ("Pokemon Essentials v21.1") over a black render area, at the same
 * 20-second checkpoint App v0.0.26 used. This is genuinely ambiguous —
 * matching the exact same "logcat/single-screenshot ceiling" this
 * project already resolved once before with extended observation
 * (Sprint 18 Track 1) and multi-checkpoint screenshots (Sprint 19) for
 * the *original*, un-preloaded fixture. This class applies the same,
 * already-proven technique to the *new*, preload-fixed state.
 *
 * **Reuses the exact disposable-workspace approach from App v0.0.26** —
 * byte-for-byte copy of the existing, Ti-prepared ASCII-safe workspace,
 * a test-authored preload script (`require 'zlib'`, no raise, no
 * `PluginManager` reference), and the same text-based `mkxp.json`
 * insertion (never a JSON parse — see `RuntimeActivityPreloadZlibProbeTest`'s
 * own kdoc for why `org.json.JSONObject` was rejected in App v0.0.25).
 * The original workspace is never opened for writing anywhere in this
 * class either.
 *
 * **New in this class, beyond App v0.0.26:**
 * - Extended wait: 120 seconds total (vs. 20s), with an explicit log
 *   checkpoint every 20 seconds, matching the same "boring, explicit
 *   progress markers" pattern already proven in Sprint 18 Track 1.
 * - Screenshots at three points — 20s, 60s, and 120s — aligned to the
 *   same 20-second checkpoint grid (the task's own suggested "90s" isn't
 *   a multiple of 20; 120s is used instead, on the existing grid, the
 *   same kind of small, explicitly-documented adjustment already made in
 *   Sprint 19 for its own 45s-vs-40s case).
 * - A before/after recursive file-listing diff of the disposable
 *   workspace, logging any file that's genuinely new after the wait —
 *   a cheap, read-only way to check whether `PluginManager`'s own
 *   plugin-cache-generation behavior (Sprint 21 planning's own
 *   hypothesis 2) produced anything observable, without needing to
 *   inspect file *contents*.
 *
 * **This is diagnostic only** — same explicit distinction as App
 * v0.0.25/26: no production preload strategy, no config-injection
 * mechanism, no Runtime Config Safety Layer implementation.
 *
 * **Does not assert:** title screen, rendering, real gameplay, input,
 * audio, or save/load of any kind. No production or bridge file is
 * changed. No native C++ change. No Pokémon Essentials original file
 * modified.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityPreloadZlibExtendedObservationTest {

    companion object {
        private const val TAG = "RuntimeActivityPreloadZlibExtendedObservationTest"

        private const val BASE_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-workspace"
        private const val DISPOSABLE_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-workspace-preload-zlib-extended"
        private const val PRELOAD_SCRIPT_FILE_NAME = "sprint21_preload_zlib.rb"

        private const val PRELOAD_SCRIPT_CONTENT = """require 'zlib'
${'$'}pokerpg_zlib_preloaded = defined?(Zlib)
"""

        /** 120s total, on a clean 20s grid — see this class's own kdoc for why 120s rather than the task's suggested 90s. */
        private const val TOTAL_WAIT_MS = 120000L
        private const val CHECKPOINT_INTERVAL_MS = 20000L
        private const val CLOSE_TIMEOUT_MS = 15000L
    }

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

    /** Same text-based insertion as App v0.0.26 — never a JSON parse. See RuntimeActivityPreloadZlibProbeTest's own kdoc for why. */
    private fun insertPreloadScriptKey(originalText: String, scriptFileName: String): String {
        val lastBraceIndex = originalText.lastIndexOf('}')
        require(lastBraceIndex >= 0) { "No closing brace found in mkxp.json content — cannot insert preloadScript key." }

        val before = originalText.substring(0, lastBraceIndex)
        val after = originalText.substring(lastBraceIndex)

        val trimmedBefore = before.trimEnd()
        val lastChar = trimmedBefore.lastOrNull()
        val needsComma = lastChar != null && lastChar != ',' && lastChar != '{'

        val newPropertyLine = "    \"preloadScript\": [\"$scriptFileName\"],"

        return if (needsComma) {
            "$trimmedBefore,\n$newPropertyLine\n$after"
        } else {
            "$trimmedBefore\n$newPropertyLine\n$after"
        }
    }

    /** Relative paths (from workspaceRoot) of every regular file in the tree — used for the before/after diff. */
    private fun listRelativeFilePaths(workspaceRoot: File): Set<String> {
        if (!workspaceRoot.exists()) return emptySet()
        return workspaceRoot.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(workspaceRoot).path }
            .toSet()
    }

    @Test
    fun launchAttempt_preloadZlibExtendedObservation_reportsVisualProgression() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseWorkspace = File(context.filesDir, BASE_WORKSPACE_FOLDER_NAME)

        assumeTrue(
            "Base workspace not found at ${baseWorkspace.absolutePath} — see README's App v0.0.21 " +
                "Ti-side preparation steps before running this test.",
            baseWorkspace.exists() && baseWorkspace.isDirectory
        )

        val baseMkxpJson = File(baseWorkspace, "mkxp.json")
        val baseMkxpJsonSizeBefore = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L

        val disposableWorkspace = File(context.filesDir, DISPOSABLE_WORKSPACE_FOLDER_NAME)
        if (disposableWorkspace.exists()) {
            disposableWorkspace.deleteRecursively()
        }
        baseWorkspace.copyRecursively(disposableWorkspace, overwrite = true)
        Log.i(TAG, "Disposable workspace created at: ${disposableWorkspace.absolutePath}")

        File(disposableWorkspace, PRELOAD_SCRIPT_FILE_NAME).writeText(PRELOAD_SCRIPT_CONTENT, Charsets.UTF_8)

        val disposableMkxpJsonFile = File(disposableWorkspace, "mkxp.json")
        val originalJsonText = disposableMkxpJsonFile.readText(Charsets.UTF_8)
        val updatedJsonText = insertPreloadScriptKey(originalJsonText, PRELOAD_SCRIPT_FILE_NAME)
        disposableMkxpJsonFile.writeText(updatedJsonText, Charsets.UTF_8)
        Log.i(TAG, "Disposable workspace mkxp.json updated with preloadScript, same text-based insertion as App v0.0.26.")

        val baseMkxpJsonSizeAfter = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L
        if (baseMkxpJsonSizeAfter == baseMkxpJsonSizeBefore) {
            Log.i(TAG, "CONFIRMED: base workspace mkxp.json size unchanged ($baseMkxpJsonSizeAfter bytes) — original PE21 workspace not modified.")
        } else {
            Log.w(TAG, "UNEXPECTED: base workspace mkxp.json size changed ($baseMkxpJsonSizeBefore -> $baseMkxpJsonSizeAfter bytes) — investigate immediately.")
        }

        // Cheap, read-only snapshot before launch, for the after-wait diff.
        val filesBeforeLaunch = listRelativeFilePaths(disposableWorkspace)
        Log.i(TAG, "Disposable workspace file count before launch: ${filesBeforeLaunch.size}")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, disposableWorkspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — beginning extended observation, up to ${TOTAL_WAIT_MS}ms, checkpoint every ${CHECKPOINT_INTERVAL_MS}ms.")

        var elapsedMs = 0L
        while (elapsedMs < TOTAL_WAIT_MS) {
            Thread.sleep(CHECKPOINT_INTERVAL_MS)
            elapsedMs += CHECKPOINT_INTERVAL_MS
            Log.i(TAG, "Checkpoint: ${elapsedMs}ms elapsed of ${TOTAL_WAIT_MS}ms — still observing, no assertion made at this point.")

            when (elapsedMs) {
                20000L -> captureScreenshot(context, "sprint22_preload_zlib_extended_screenshot_20s.png")
                60000L -> captureScreenshot(context, "sprint22_preload_zlib_extended_screenshot_60s.png")
                120000L -> captureScreenshot(context, "sprint22_preload_zlib_extended_screenshot_120s.png")
            }
        }
        Log.i(TAG, "Extended wait complete (${elapsedMs}ms total) — beginning final evidence collection.")

        try {
            val filesAfterWait = listRelativeFilePaths(disposableWorkspace)
            val newFiles = filesAfterWait - filesBeforeLaunch
            if (newFiles.isEmpty()) {
                Log.i(TAG, "EVIDENCE: no new files appeared in the disposable workspace during the observation window (file count before=${filesBeforeLaunch.size}, after=${filesAfterWait.size}).")
            } else {
                Log.i(TAG, "EVIDENCE: ${newFiles.size} new file(s) appeared in the disposable workspace during the observation window:")
                newFiles.sorted().forEach { relativePath ->
                    val size = File(disposableWorkspace, relativePath).length()
                    Log.i(TAG, "  NEW FILE: $relativePath ($size bytes)")
                }
            }

            Log.i(TAG, "EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) across the full ${TOTAL_WAIT_MS}ms window for:")
            Log.i(TAG, "  - whether \"uninitialized constant PluginManager::Zlib\" reappears at any point (not expected, per App v0.0.26's own result, but explicitly checked, not assumed).")
            Log.i(TAG, "  - any other showExc()-produced exception, at any point — report exact class/message/backtrace and which checkpoint it appeared near.")
            Log.i(TAG, "  - whether logcat output continues, stalls, or stops entirely partway through the window.")
            Log.i(TAG, "EVIDENCE: compare the three pulled screenshots (20s/60s/120s) for whether the same black-screen-with-title-bar state persists unchanged, a dialog appears, or any visual progression occurs between them.")

        } finally {
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
}

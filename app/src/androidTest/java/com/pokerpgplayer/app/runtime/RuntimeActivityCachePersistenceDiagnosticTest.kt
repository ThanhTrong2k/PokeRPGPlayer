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
 * Sprint 23 hotfix (App v0.0.29) — Cache Persistence / Second-Run
 * Diagnostic, Split Into Two Independent Test Methods.
 *
 * **Why this is split, and why the App v0.0.28 single-method design was
 * abandoned rather than debugged further:** Ti's App v0.0.28 run showed
 * the first launch (with `preloadScript`) completing correctly —
 * title menu visible at all three checkpoints, `Data/PluginScripts.rxdata`
 * confirmed generated — but the **second** `ActivityScenario` launch,
 * invoked later in the *same* test method, produced no observation
 * output at all: no checkpoint lines, no screenshots, no completion log.
 * This is consistent with an Android instrumentation/`ActivityScenario`
 * sequencing limitation when running two full native-runtime launches
 * back-to-back within a single test method, not a finding about
 * `PluginManager`, `Zlib`, or the cache itself — Sprint 23's own
 * "do not conclude cache works or fails" instruction is followed here
 * by not attempting to diagnose the exact sequencing cause and instead
 * removing the shared-method-scope entirely: **each launch now gets its
 * own, independent test method and instrumentation invocation.**
 *
 * **How state passes between the two methods:** [primeCache_withPreload_reachesTitleMenuAndGeneratesCache]
 * creates the disposable workspace, primes it, and — unlike every other
 * test in this project — **deliberately does not delete it when the
 * method finishes.** It also writes a small marker file
 * (`sprint23_cache_primed.marker`) as an explicit, easy-to-check signal
 * that priming completed. [secondRun_withoutPreload_usesPrimedCache]
 * never creates or clears the workspace itself — it only *locates* the
 * one the first method already left behind, skipping (not failing) via
 * `assumeTrue` if it's missing, matching this project's own established
 * precondition-handling pattern.
 *
 * **Both methods reuse the exact same disposable-workspace and
 * text-based `mkxp.json` insertion/removal approach already proven in
 * App v0.0.26–28** — byte-for-byte copy, never a JSON parse (see
 * `RuntimeActivityPreloadZlibProbeTest`'s own kdoc for why
 * `org.json.JSONObject` was rejected in App v0.0.25).
 *
 * **This remains diagnostic only.** No production preload/config-
 * injection mechanism, no Runtime Config Safety Layer implementation.
 * No input, controller, save, or audio testing of any kind. No
 * Pokémon Essentials original file modified — confirmed by an explicit
 * before/after size check on the base workspace's own `mkxp.json` in
 * both methods.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityCachePersistenceDiagnosticTest {

    companion object {
        private const val TAG = "RuntimeActivityCachePersistenceDiagnosticTest"

        private const val BASE_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-workspace"
        private const val DISPOSABLE_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-workspace-cache-persistence"
        private const val PRELOAD_SCRIPT_FILE_NAME = "sprint21_preload_zlib.rb"
        private const val CACHE_PRIMED_MARKER_FILE_NAME = "sprint23_cache_primed.marker"

        private const val PRELOAD_SCRIPT_CONTENT = """require 'zlib'
${'$'}pokerpg_zlib_preloaded = defined?(Zlib)
"""

        /** Exact format inserted/removed — used for both operations. */
        private const val PRELOAD_SCRIPT_PROPERTY_LINE = "    \"preloadScript\": [\"$PRELOAD_SCRIPT_FILE_NAME\"],"

        private const val CHECKPOINT_INTERVAL_MS = 30000L
        private const val RUN_TOTAL_WAIT_MS = 90000L
        private const val CLOSE_TIMEOUT_MS = 15000L

        private const val PLUGIN_SCRIPTS_CACHE_RELATIVE_PATH = "Data/PluginScripts.rxdata"
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

    /** Same text-based insertion as App v0.0.26–28 — never a JSON parse. */
    private fun insertPreloadScriptKey(originalText: String): String {
        val lastBraceIndex = originalText.lastIndexOf('}')
        require(lastBraceIndex >= 0) { "No closing brace found in mkxp.json content — cannot insert preloadScript key." }

        val before = originalText.substring(0, lastBraceIndex)
        val after = originalText.substring(lastBraceIndex)

        val trimmedBefore = before.trimEnd()
        val lastChar = trimmedBefore.lastOrNull()
        val needsComma = lastChar != null && lastChar != ',' && lastChar != '{'

        return if (needsComma) {
            "$trimmedBefore,\n$PRELOAD_SCRIPT_PROPERTY_LINE\n$after"
        } else {
            "$trimmedBefore\n$PRELOAD_SCRIPT_PROPERTY_LINE\n$after"
        }
    }

    /** Precise removal of exactly the known inserted line — safe, since the format is fully controlled by this same class. */
    private fun removePreloadScriptKey(text: String): String {
        if (!text.contains(PRELOAD_SCRIPT_PROPERTY_LINE)) {
            return text
        }
        return text.replace(PRELOAD_SCRIPT_PROPERTY_LINE, "")
    }

    private fun listRelativeFilePaths(workspaceRoot: File): Set<String> {
        if (!workspaceRoot.exists()) return emptySet()
        return workspaceRoot.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(workspaceRoot).path }
            .toSet()
    }

    /** Shared launch-wait-screenshot-close body, reused by both methods with a distinct label/checkpoint file prefix. */
    private fun runLaunchAndObserve(context: Context, runLabel: String, workspace: File) {
        Log.i(TAG, "[$runLabel] Launching RuntimeActivity against: ${workspace.absolutePath}")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "[$runLabel] Reached RESUMED — observing up to ${RUN_TOTAL_WAIT_MS}ms, checkpoint every ${CHECKPOINT_INTERVAL_MS}ms.")

        var elapsedMs = 0L
        while (elapsedMs < RUN_TOTAL_WAIT_MS) {
            Thread.sleep(CHECKPOINT_INTERVAL_MS)
            elapsedMs += CHECKPOINT_INTERVAL_MS
            Log.i(TAG, "[$runLabel] Checkpoint: ${elapsedMs}ms elapsed of ${RUN_TOTAL_WAIT_MS}ms.")
            captureScreenshot(context, "sprint23_cache_persistence_${runLabel}_${elapsedMs / 1000}s.png")
        }
        Log.i(TAG, "[$runLabel] Wait complete (${elapsedMs}ms total).")

        Log.i(TAG, "[$runLabel] EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) for whether \"uninitialized constant PluginManager::Zlib\" appears, and for any other showExc()-produced exception, with exact class/message/backtrace.")

        val closeThread = Thread {
            try {
                scenario.close()
            } catch (t: Throwable) {
                Log.w(TAG, "[$runLabel] scenario.close() threw on the background close thread: ${t.message}")
            }
        }
        closeThread.isDaemon = true
        closeThread.start()
        closeThread.join(CLOSE_TIMEOUT_MS)

        if (closeThread.isAlive) {
            Log.w(TAG, "[$runLabel] scenario.close() did not complete within ${CLOSE_TIMEOUT_MS}ms — expected if native execution is still active. Not failing the test for this.")
        } else {
            Log.i(TAG, "[$runLabel] scenario.close() completed within ${CLOSE_TIMEOUT_MS}ms.")
        }
        Log.i(TAG, "[$runLabel] RUN FINISHED.")
    }

    /**
     * Method 1 — primes the cache. Creates the disposable workspace with
     * `preloadScript` present, launches, observes to title menu,
     * confirms `Data/PluginScripts.rxdata` exists, and — deliberately,
     * unlike every prior test in this project — does **not** delete the
     * workspace when finished, so [secondRun_withoutPreload_usesPrimedCache]
     * can reuse it in a later, separate instrumentation invocation.
     */
    @Test
    fun primeCache_withPreload_reachesTitleMenuAndGeneratesCache() {
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
        Log.i(TAG, "[prime] Disposable workspace created at: ${disposableWorkspace.absolutePath}")

        File(disposableWorkspace, PRELOAD_SCRIPT_FILE_NAME).writeText(PRELOAD_SCRIPT_CONTENT, Charsets.UTF_8)

        val mkxpJsonFile = File(disposableWorkspace, "mkxp.json")
        val originalJsonText = mkxpJsonFile.readText(Charsets.UTF_8)
        mkxpJsonFile.writeText(insertPreloadScriptKey(originalJsonText), Charsets.UTF_8)
        Log.i(TAG, "[prime] preloadScript inserted into disposable workspace's own mkxp.json only.")

        val baseMkxpJsonSizeAfter = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L
        if (baseMkxpJsonSizeAfter == baseMkxpJsonSizeBefore) {
            Log.i(TAG, "[prime] CONFIRMED: base workspace mkxp.json size unchanged ($baseMkxpJsonSizeAfter bytes) — original PE21 workspace not modified.")
        } else {
            Log.w(TAG, "[prime] UNEXPECTED: base workspace mkxp.json size changed — investigate immediately.")
        }

        val filesBefore = listRelativeFilePaths(disposableWorkspace)
        Log.i(TAG, "[prime] File count before launch: ${filesBefore.size}")

        runLaunchAndObserve(context, "prime-with-preload", disposableWorkspace)

        val filesAfter = listRelativeFilePaths(disposableWorkspace)
        val newFiles = filesAfter - filesBefore
        Log.i(TAG, "[prime] File count after launch: ${filesAfter.size} (${newFiles.size} new).")
        newFiles.sorted().forEach { path ->
            Log.i(TAG, "[prime]   NEW FILE: $path (${File(disposableWorkspace, path).length()} bytes)")
        }

        val pluginScriptsCacheFile = File(disposableWorkspace, PLUGIN_SCRIPTS_CACHE_RELATIVE_PATH)
        if (pluginScriptsCacheFile.exists()) {
            Log.i(TAG, "[prime] CONFIRMED: $PLUGIN_SCRIPTS_CACHE_RELATIVE_PATH exists, size=${pluginScriptsCacheFile.length()} bytes.")
        } else {
            Log.w(TAG, "[prime] $PLUGIN_SCRIPTS_CACHE_RELATIVE_PATH does NOT exist — cache priming may not have completed. secondRun_withoutPreload_usesPrimedCache will skip if this file is missing.")
        }

        // Deliberately NOT deleted — the whole point is for method 2 to reuse this exact workspace.
        File(disposableWorkspace, CACHE_PRIMED_MARKER_FILE_NAME).writeText(
            "Primed by primeCache_withPreload_reachesTitleMenuAndGeneratesCache at ${System.currentTimeMillis()}",
            Charsets.UTF_8
        )
        Log.i(TAG, "[prime] Marker file written: $CACHE_PRIMED_MARKER_FILE_NAME. Workspace deliberately left in place for the second-run method.")
    }

    /**
     * Method 2 — reuses the workspace [primeCache_withPreload_reachesTitleMenuAndGeneratesCache]
     * already primed. Never creates or clears the workspace itself.
     * Removes `preloadScript` (if present), confirms its absence, then
     * launches fresh in this method's own, separate `ActivityScenario` —
     * a genuinely independent instrumentation invocation, avoiding the
     * sequencing issue found in App v0.0.28's own single-method design.
     */
    @Test
    fun secondRun_withoutPreload_usesPrimedCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseWorkspace = File(context.filesDir, BASE_WORKSPACE_FOLDER_NAME)
        val disposableWorkspace = File(context.filesDir, DISPOSABLE_WORKSPACE_FOLDER_NAME)
        val markerFile = File(disposableWorkspace, CACHE_PRIMED_MARKER_FILE_NAME)

        assumeTrue(
            "Primed disposable workspace not found at ${disposableWorkspace.absolutePath} (or missing its marker file) — " +
                "run primeCache_withPreload_reachesTitleMenuAndGeneratesCache first, in its own separate instrumentation invocation.",
            disposableWorkspace.exists() && disposableWorkspace.isDirectory && markerFile.exists()
        )
        Log.i(TAG, "[second-run] Found primed workspace and marker file: ${markerFile.readText(Charsets.UTF_8)}")

        val baseMkxpJson = File(baseWorkspace, "mkxp.json")
        val baseMkxpJsonSizeBefore = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L

        val pluginScriptsCacheFile = File(disposableWorkspace, PLUGIN_SCRIPTS_CACHE_RELATIVE_PATH)
        if (pluginScriptsCacheFile.exists()) {
            Log.i(TAG, "[second-run] CONFIRMED before launch: $PLUGIN_SCRIPTS_CACHE_RELATIVE_PATH exists, size=${pluginScriptsCacheFile.length()} bytes.")
        } else {
            Log.w(TAG, "[second-run] $PLUGIN_SCRIPTS_CACHE_RELATIVE_PATH does NOT exist before launch — the cache-persistence question this test exists to answer may be moot; proceeding anyway to observe what happens.")
        }

        val mkxpJsonFile = File(disposableWorkspace, "mkxp.json")
        val jsonTextBeforeRemoval = mkxpJsonFile.readText(Charsets.UTF_8)
        val jsonTextAfterRemoval = removePreloadScriptKey(jsonTextBeforeRemoval)
        mkxpJsonFile.writeText(jsonTextAfterRemoval, Charsets.UTF_8)

        if (jsonTextAfterRemoval.contains(PRELOAD_SCRIPT_PROPERTY_LINE)) {
            Log.w(TAG, "[second-run] UNEXPECTED: preloadScript line still present after removal attempt — investigate immediately.")
        } else {
            Log.i(TAG, "[second-run] CONFIRMED: preloadScript is absent from the disposable workspace's mkxp.json before this launch.")
        }

        val baseMkxpJsonSizeAfter = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L
        if (baseMkxpJsonSizeAfter == baseMkxpJsonSizeBefore) {
            Log.i(TAG, "[second-run] CONFIRMED: base workspace mkxp.json size unchanged ($baseMkxpJsonSizeAfter bytes) — original PE21 workspace not modified.")
        } else {
            Log.w(TAG, "[second-run] UNEXPECTED: base workspace mkxp.json size changed — investigate immediately.")
        }

        val filesBefore = listRelativeFilePaths(disposableWorkspace)

        runLaunchAndObserve(context, "second-run-without-preload", disposableWorkspace)

        val filesAfter = listRelativeFilePaths(disposableWorkspace)
        val newFiles = filesAfter - filesBefore
        Log.i(TAG, "[second-run] File count before launch: ${filesBefore.size}, after: ${filesAfter.size} (${newFiles.size} new).")
        newFiles.sorted().forEach { path ->
            Log.i(TAG, "[second-run]   NEW FILE: $path (${File(disposableWorkspace, path).length()} bytes)")
        }

        Log.i(TAG, "[second-run] SUMMARY: report whether \"PluginManager::Zlib\" reappeared (cache alone NOT sufficient), the title menu still appeared without it (cache alone WAS sufficient), or a different, new blocker appeared.")
    }
}

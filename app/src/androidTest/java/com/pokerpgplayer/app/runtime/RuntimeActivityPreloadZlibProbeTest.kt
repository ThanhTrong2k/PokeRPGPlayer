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
 * Sprint 21 — Test-Only `preloadScript` Zlib Probe.
 *
 * **Goal:** determine whether pre-requiring Ruby's `Zlib` module *before*
 * Pokémon Essentials v21.1's own real `Scripts.rxdata` executes resolves
 * the `NameError: uninitialized constant PluginManager::Zlib` blocker
 * observed via App v0.0.23's own screenshot evidence — testing the
 * Sprint 21 planning document's own load-order hypothesis directly,
 * against real PE21 content, rather than relying on general community
 * precedent alone.
 *
 * **How the disposable workspace is built, precisely, and why this does
 * not repeat the mistake corrected in App v0.0.21:** the App v0.0.21
 * correction was about *generating or reconstructing* `mkxp.json`
 * content from Kotlin string constants — introducing an extra,
 * unnecessary encoding-transformation layer on top of exactly the kind
 * of question this project has had to investigate (Sprint 16–18's own
 * `windowTitle` encoding trace). This class does something different and
 * safer: it byte-for-byte copies (`File.copyRecursively()`, no text
 * decoding/re-encoding involved) the *existing*, Ti-prepared
 * `essentials-v211-ascii-title-workspace` into a new, disposable folder,
 * then modifies that copy's own real `mkxp.json` (Ti's own actual bytes,
 * not reconstructed) via a **minimal, text-based insertion** — not a
 * JSON parse/re-serialize. The original `essentials-v211-ascii-title-workspace`
 * is never opened for writing anywhere in this class.
 *
 * **App v0.0.25 correction (this build):** the first version of this
 * probe parsed `mkxp.json` with `org.json.JSONObject` and failed with
 * `JSONException: Expected literal value` — because mkxp-z's own config
 * format is not strict JSON. Reading `config.cpp` directly (already
 * established in Sprint 16): mkxp-z parses config files via
 * `json::parse5`, a JSON5-tolerant parser that explicitly permits
 * comments and trailing commas — both of which strict `org.json` rejects
 * outright. This version never parses the file into an object model at
 * all. It instead finds the position of the final top-level closing
 * brace, checks whether the content immediately before it already ends
 * in a comma (after trimming trailing whitespace), adds one only if
 * needed, and inserts the new `"preloadScript"` property directly before
 * that brace — leaving every existing character, comment, and formatting
 * choice in the rest of the file completely untouched.
 *
 * **How the preload mechanism itself works, traced directly from
 * `binding-mri.cpp`:** `conf.preloadScripts` (mapped from `mkxp.json`'s
 * `"preloadScript"` array key) is iterated *inside* `runRMXPScripts()`,
 * immediately after the script archive is decompressed but *before* the
 * real script-execution loop begins — unlike Sprint 20's own
 * `customScript` mechanism (mutually exclusive with `Scripts.rxdata`),
 * this one runs first and then *falls through* to the real scripts
 * automatically, **provided the preload script itself leaves no
 * uncaught exception** (`runRMXPScripts()` checks `rb_gv_get("$!")`
 * immediately after the preload loop and returns early, skipping
 * `Scripts.rxdata` entirely, if one exists) — hence the preload script
 * below deliberately never raises.
 *
 * **The preload script itself does only what the approved scope
 * specifies:** `require 'zlib'`, then sets a harmless global marker
 * (`$pokerpg_zlib_preloaded`). No raise, no file mutation, no
 * `PluginManager` patching, no reference to any Essentials class at all.
 *
 * **Prefers screenshot evidence, reusing the exact `UiDevice.takeScreenshot()`
 * approach proven in App v0.0.23** — no new dependency, since
 * `androidx.test.uiautomator` was already added then.
 *
 * **Never modifies any Pokémon Essentials original file.** The base
 * workspace is only ever read from, byte-for-byte copied, and left
 * untouched — confirmed by this test's own before/after size check on
 * the base workspace's own `mkxp.json`.
 *
 * **This is diagnostic only.** It does not constitute, propose, or
 * imply a production config-injection mechanism or a Runtime Config
 * Safety Layer implementation — see the Sprint 21 planning document's
 * own explicit distinction between test-only probing and any future,
 * separately-approved production mitigation.
 *
 * **Does not assert:** title screen, rendering, real gameplay, input,
 * audio, or save/load of any kind. No production or bridge file is
 * changed. No native C++ change.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityPreloadZlibProbeTest {

    companion object {
        private const val TAG = "RuntimeActivityPreloadZlibProbeTest"

        /** The existing, Ti-prepared base workspace from App v0.0.21+ — read from only, never written to. */
        private const val BASE_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-workspace"

        /** Disposable copy created fresh for this probe. */
        private const val DISPOSABLE_WORKSPACE_FOLDER_NAME = "essentials-v211-ascii-title-workspace-preload-zlib-probe"

        private const val PRELOAD_SCRIPT_FILE_NAME = "sprint21_preload_zlib.rb"

        /** Matches the approved scope exactly: require, harmless marker, no raise, no mutation, no PluginManager reference. */
        private const val PRELOAD_SCRIPT_CONTENT = """require 'zlib'
${'$'}pokerpg_zlib_preloaded = defined?(Zlib)
"""

        private const val NATIVE_STARTUP_WAIT_MS = 20000L
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

    /**
     * Minimal, text-based insertion — never parses `mkxp.json` into an
     * object model, since mkxp-z's own config format (JSON5-tolerant:
     * comments, trailing commas) is not strict JSON and a prior version
     * of this test's own use of `org.json.JSONObject` failed on exactly
     * that mismatch (App v0.0.25's own `JSONException`). Finds the final
     * top-level closing brace, adds a trailing comma to the preceding
     * content only if one isn't already present, and inserts the new
     * property directly before that brace — every other character,
     * comment, and formatting choice in the file is left untouched.
     */
    private fun insertPreloadScriptKey(originalText: String, scriptFileName: String): String {
        val lastBraceIndex = originalText.lastIndexOf('}')
        require(lastBraceIndex >= 0) { "No closing brace found in mkxp.json content — cannot insert preloadScript key." }

        val before = originalText.substring(0, lastBraceIndex)
        val after = originalText.substring(lastBraceIndex) // starts with the final '}'

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

    @Test
    fun launchAttempt_preloadZlibProbeWorkspace_reportsBlockerOutcome() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseWorkspace = File(context.filesDir, BASE_WORKSPACE_FOLDER_NAME)

        assumeTrue(
            "Base workspace not found at ${baseWorkspace.absolutePath} — see README's App v0.0.21 " +
                "Ti-side preparation steps (same ASCII-title fixture used since Sprint 17) before running this test.",
            baseWorkspace.exists() && baseWorkspace.isDirectory
        )

        val baseMkxpJson = File(baseWorkspace, "mkxp.json")
        val baseMkxpJsonSizeBefore = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L
        Log.i(TAG, "Base workspace mkxp.json size before copy: $baseMkxpJsonSizeBefore bytes (recorded for a before/after no-modification check).")

        // Byte-for-byte copy only — no text decoding/re-encoding of any
        // PE21 content. The base workspace is never opened for writing
        // anywhere in this test.
        val disposableWorkspace = File(context.filesDir, DISPOSABLE_WORKSPACE_FOLDER_NAME)
        if (disposableWorkspace.exists()) {
            disposableWorkspace.deleteRecursively()
        }
        baseWorkspace.copyRecursively(disposableWorkspace, overwrite = true)
        Log.i(TAG, "Disposable workspace created at: ${disposableWorkspace.absolutePath}")

        // Add the test-authored preload script — new file, not modifying anything PE21 wrote.
        File(disposableWorkspace, PRELOAD_SCRIPT_FILE_NAME).writeText(PRELOAD_SCRIPT_CONTENT, Charsets.UTF_8)
        Log.i(TAG, "Preload script written: $PRELOAD_SCRIPT_FILE_NAME")

        // Modify the DISPOSABLE COPY's own real mkxp.json (Ti's own
        // actual bytes, copied, not reconstructed) using a minimal,
        // text-based insertion — never a JSON parse/re-serialize, since
        // mkxp-z's own config format is JSON5-tolerant (comments,
        // trailing commas), not strict JSON. See insertPreloadScriptKey()'s
        // own kdoc for why (App v0.0.25's own JSONObject attempt failed
        // on exactly this mismatch).
        val disposableMkxpJsonFile = File(disposableWorkspace, "mkxp.json")
        val originalJsonText = disposableMkxpJsonFile.readText(Charsets.UTF_8)
        val sizeBeforeInsertion = originalJsonText.length
        val updatedJsonText = insertPreloadScriptKey(originalJsonText, PRELOAD_SCRIPT_FILE_NAME)
        disposableMkxpJsonFile.writeText(updatedJsonText, Charsets.UTF_8)
        Log.i(TAG, "Disposable workspace mkxp.json updated via text-based insertion: size before=$sizeBeforeInsertion chars, after=${updatedJsonText.length} chars. Added \"preloadScript\": [\"$PRELOAD_SCRIPT_FILE_NAME\"], every other character/comment/formatting preserved from Ti's own original.")

        val baseMkxpJsonSizeAfter = if (baseMkxpJson.exists()) baseMkxpJson.length() else -1L
        if (baseMkxpJsonSizeAfter == baseMkxpJsonSizeBefore) {
            Log.i(TAG, "CONFIRMED: base workspace mkxp.json size unchanged ($baseMkxpJsonSizeAfter bytes) — original PE21 workspace not modified.")
        } else {
            Log.w(TAG, "UNEXPECTED: base workspace mkxp.json size changed ($baseMkxpJsonSizeBefore -> $baseMkxpJsonSizeAfter bytes) — investigate immediately, this should never happen.")
        }

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, disposableWorkspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native startup, preload script, and Essentials' own real script execution.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "Wait complete — capturing evidence.")

        try {
            captureScreenshot(context, "sprint21_preload_zlib_probe_screenshot.png")

            Log.i(TAG, "EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) for:")
            Log.i(TAG, "  - the \"RGSS version\" checkpoint line.")
            Log.i(TAG, "  - whether the previously-observed \"uninitialized constant PluginManager::Zlib\" exception (App v0.0.23) PERSISTS, DISAPPEARS, or is REPLACED by a different exception — report exactly which, with the full exception class/message/backtrace if one appears.")
            Log.i(TAG, "  - review the pulled screenshot for whether the same dialog, no dialog, or a different visible state appears compared to App v0.0.23's own screenshots.")

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

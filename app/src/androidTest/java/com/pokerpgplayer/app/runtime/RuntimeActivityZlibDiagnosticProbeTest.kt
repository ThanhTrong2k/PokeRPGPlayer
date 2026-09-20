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
 * Sprint 20 — Ruby/Zlib Runtime Diagnostic Probe.
 *
 * **Goal:** directly test whether this Android Ruby runtime exposes
 * Ruby's standard `Zlib` module, entirely independently of Pokémon
 * Essentials v21.1 and its `PluginManager` — resolving whether the
 * `NameError: uninitialized constant PluginManager::Zlib` blocker (found
 * via App v0.0.23's screenshot capture) reflects a genuine Ruby runtime
 * capability gap, or something specific to Essentials' own code.
 *
 * **The workspace used here contains zero Pokémon Essentials content of
 * any kind.** Every file — `Game.ini`, `mkxp.json`, and the Ruby probe
 * script itself — is authored directly by this test's own Kotlin source
 * and written fresh into a disposable, app-private folder before each
 * run. No PE21 file is read, copied, or referenced.
 *
 * **How a custom Ruby script runs without `Scripts.rxdata` at all:**
 * traced directly from `binding-mri.cpp` (the same source already read
 * for Sprint 14's own script-execution trace): the RGSS thread's own
 * entry point checks `conf.customScript` (mapped from `mkxp.json`'s own
 * `"customScript"` key, confirmed directly in `config.cpp`) — if set, it
 * calls `runCustomScript(customScript)` **instead of** `runRMXPScripts()`
 * entirely, bypassing `Scripts.rxdata` and any Essentials-specific code
 * path completely:
 * ```cpp
 * if (!customScript.empty())
 *     runCustomScript(customScript);
 * else
 *     runRMXPScripts(btData);
 * ```
 * `runCustomScript()` reads the named file (relative to the already-
 * `chdir()`'d workspace, the same mechanism proven since Sprint 9) and
 * evaluates its contents directly as Ruby code — with no dedicated
 * try/catch of its own, meaning an uncaught exception raised inside the
 * probe script propagates to the exact same, already-proven `showExc()`/
 * `Debug()` reporting mechanism this project has now observed multiple
 * times (Pokémon Z's `Win32API` exception, Essentials' own
 * `PluginManager::Zlib` exception).
 *
 * **Why the probe reports its findings via a deliberate `raise`, not
 * `puts`/stdout:** this project has never confirmed whether Ruby's
 * `$stdout`/`puts` output is captured anywhere visible (logcat or
 * otherwise) on this Android runtime — but it *has* repeatedly, directly
 * confirmed that uncaught Ruby exceptions reliably surface via
 * `Debug()`. Rather than betting on an unverified channel, the probe
 * script deliberately raises a single `RuntimeError` at the end, with
 * every finding encoded directly in its own message string — guaranteed
 * to surface through the already-proven mechanism regardless of any
 * stdout-capture uncertainty.
 *
 * **Deliberately does not hard-assert an outcome.** Whether `Zlib` is
 * present or absent, and whether a compress/decompress round-trip
 * succeeds, are all genuinely open questions this probe exists to
 * answer — not something to assert a specific expected result for.
 *
 * **Does not assert:** title screen, rendering, real gameplay, input,
 * audio, or save/load of any kind. No production or bridge file is
 * changed. No native C++ change. No Pokémon Essentials file is read,
 * copied, or referenced anywhere in this class.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeActivityZlibDiagnosticProbeTest {

    companion object {
        private const val TAG = "RuntimeActivityZlibDiagnosticProbeTest"

        /** Entirely test-generated, disposable — never copied from or related to any real game folder. */
        private const val PROBE_WORKSPACE_FOLDER_NAME = "sprint20-zlib-probe-workspace"

        /** Same margin as prior real-runtime tests — generous, not precisely tuned. */
        private const val NATIVE_STARTUP_WAIT_MS = 20000L
        private const val CLOSE_TIMEOUT_MS = 15000L

        /**
         * Minimal, valid Game.ini. `Scripts=` is present only for
         * structural conventionality with every other fixture in this
         * project — `customScript` in mkxp.json bypasses Scripts.rxdata
         * loading entirely, so this value is never actually read for
         * script content.
         */
        private const val PROBE_GAME_INI = """[Game]
Library=RGSS104E.dll
Scripts=Data\Scripts.rxdata
Title=Sprint20ZlibProbe
RTP1=
RTP2=
RTP3=
"""

        /**
         * `customScript` points at the probe script below, relative to
         * the workspace root. `windowTitle` is deliberately plain ASCII,
         * avoiding entirely the encoding question already characterized
         * in Sprint 16–18 — irrelevant to this specific diagnostic, so
         * not worth reintroducing as a variable here.
         */
        private const val PROBE_MKXP_JSON = """{
    "customScript": "probe.rb",
    "windowTitle": "Sprint20ZlibProbe"
}"""

        /**
         * The probe script itself. Every finding is encoded into a
         * single, deliberately-raised RuntimeError message — see this
         * class's own kdoc for why. `defined?(Zlib)` is checked
         * separately from the require's own success/failure, since a
         * require could conceivably succeed while still leaving the
         * expected constant undefined (not expected, but checked
         * explicitly rather than assumed).
         */
        private const val PROBE_RUBY_SCRIPT = """results = []

require_ok = true
begin
  require 'zlib'
rescue Exception => e
  require_ok = false
  results << "require_ok=false"
  results << "require_exception_class=#{e.class}"
  results << "require_exception_message=#{e.message}"
end

if require_ok
  results << "require_ok=true"
end

zlib_defined = !defined?(Zlib).nil?
results << "zlib_defined=#{zlib_defined}"

if zlib_defined
  begin
    original = "PokeRPGPlayer_Sprint20_probe_roundtrip"
    compressed = Zlib::Deflate.deflate(original)
    decompressed = Zlib::Inflate.inflate(compressed)
    roundtrip_ok = (decompressed == original)
    results << "roundtrip_ok=#{roundtrip_ok}"
  rescue Exception => e
    results << "roundtrip_ok=false"
    results << "roundtrip_exception_class=#{e.class}"
    results << "roundtrip_exception_message=#{e.message}"
  end
end

raise "SPRINT20_ZLIB_PROBE_RESULT: " + results.join(" | ")
"""
    }

    @Test
    fun launchAttempt_zlibProbeWorkspace_reportsRubyZlibAvailability() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(context.filesDir, PROBE_WORKSPACE_FOLDER_NAME)

        // Fresh, disposable, entirely test-generated — clear any stale
        // leftover from a previous run, same defensive pattern used
        // since Sprint 9.
        if (workspace.exists()) {
            workspace.deleteRecursively()
        }
        workspace.mkdirs()
        File(workspace, "Game.ini").writeText(PROBE_GAME_INI, Charsets.UTF_8)
        File(workspace, "mkxp.json").writeText(PROBE_MKXP_JSON, Charsets.UTF_8)
        File(workspace, "probe.rb").writeText(PROBE_RUBY_SCRIPT, Charsets.UTF_8)

        Log.i(TAG, "Created zlib probe workspace at: ${workspace.absolutePath}")
        Log.i(TAG, "Game.ini/mkxp.json/probe.rb written — zero Pokémon Essentials content, all test-authored.")

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            RuntimeActivity::class.java
        ).apply {
            putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspace.absolutePath)
        }

        val scenario = ActivityScenario.launch<RuntimeActivity>(intent)
        scenario.moveToState(Lifecycle.State.RESUMED)

        Log.i(TAG, "Reached RESUMED — waiting ${NATIVE_STARTUP_WAIT_MS}ms for native startup and probe script execution.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "Wait complete.")

        try {
            Log.i(TAG, "EVIDENCE: check logcat tag \"mkxp\" (DEBUG level) for a line starting with \"SPRINT20_ZLIB_PROBE_RESULT:\" — this is the probe script's own deliberate, encoded result, surfaced via the same showExc()/Debug() mechanism already proven for the Win32API and PluginManager::Zlib exceptions.")
            Log.i(TAG, "EVIDENCE: parse that line for require_ok, zlib_defined, and (if zlib_defined=true) roundtrip_ok — these three values directly answer whether Ruby's own Zlib module is present and functional in this runtime, independent of Essentials.")
            Log.i(TAG, "EVIDENCE: if no such line appears at all, check for ANY other showExc()-produced exception, or an \"Unable to open 'probe.rb'\" dialog (would indicate a workspace-write problem, not a Zlib finding) — report exactly what, if anything, appears instead.")
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

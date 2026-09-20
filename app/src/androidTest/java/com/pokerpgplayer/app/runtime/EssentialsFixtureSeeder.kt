package com.pokerpgplayer.app.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Test Evidence Stabilization — self-healing PE21 fixture seeding, via
 * shell-pipe rather than plain Kotlin `File` I/O.
 *
 * **Why shell-pipe, not `java.io.File.copyRecursively()`:** App v0.0.36's
 * own direct-`File`-based seeder returned `Ready`/logged successfully in
 * review, but Ti's own real-device evidence showed app-internal storage
 * afterward contained *only* `profileInstalled` — nothing seeded at all
 * — even though the external fixture at `/sdcard/Download/<fixtureName>`
 * was independently confirmed complete (`Game.exe`, `mkxp.json`, `Data`,
 * `Graphics`, `Audio`, `Plugins` all verified present). On Android 16,
 * scoped storage means the app/instrumentation process's own UID
 * generally cannot read `/sdcard/Download` directly via `java.io.File`,
 * regardless of whether the content genuinely exists there — this is
 * exactly consistent with that symptom. This class now performs both
 * the external-existence check and the actual seeding through the
 * **shell** UID instead, which retains broader storage read access on
 * Android test setups, piping data directly into the app's own private
 * storage via `run-as` — semantically equivalent to:
 * ```
 * cd /sdcard/Download && tar -cf - <fixtureName> | run-as <package> sh -c 'cd files && tar -xf -'
 * ```
 * **Deliberately does not add any storage runtime permission** (e.g.
 * `READ_EXTERNAL_STORAGE`) to make this work — the shell UID's own
 * access is what's being used here, not the app's own.
 *
 * **Internal-storage checks still use plain `java.io.File`** — reading
 * the app's *own* private storage from the app/instrumentation UID was
 * never the part that was broken; only reading `/sdcard/Download` was.
 *
 * **Tag, deliberately short and stable:** `PokeRPGSeeder`.
 *
 * **Missing fixture vs. failed copy are deliberately different
 * outcomes,** unchanged from the prior revision: [FixtureResult.Missing]
 * (neither internal nor external copy exists, confirmed via a real
 * shell-level check this time) is an expected, first-time-setup state —
 * callers should skip cleanly. [FixtureResult.CopyFailed] (external
 * exists but seeding didn't work, or key files are missing afterward)
 * is a real problem — callers should fail loudly, not silently skip.
 *
 * **Not part of the app's own production runtime** — lives in
 * `androidTest` only, never compiled into the shipped APK, never
 * touches the original PE21 files at the external staging path (only
 * ever reads/copies *from* it via the shell pipe, never writes back),
 * and does not change any runtime architecture.
 */
object EssentialsFixtureSeeder {

    private const val TAG = "PokeRPGSeeder"

    /** Where Ti pushes the fixture once, per device — outside app-private storage, so it survives app reinstalls/clear-data. */
    private const val EXTERNAL_STAGING_BASE_PATH = "/sdcard/Download"

    /** Marker appended to every shell command run by this class, so its own exit code can be parsed back out of captured stdout — `UiAutomation.executeShellCommand()` does not expose an exit code directly. */
    private const val EXIT_CODE_MARKER = "POKERPG_SHELL_EXIT"

    /** Minimal set of paths whose presence indicates a real, complete Essentials v21.1 workspace, not a partial/corrupt copy. */
    private val KEY_FIXTURE_ENTRIES = listOf("Game.exe", "Game.ini", "mkxp.json", "Data", "Graphics", "Audio", "Plugins")

    sealed class FixtureResult {
        /** Fixture is ready to use — either it was already present internally, or seeding just succeeded. */
        data class Ready(val internalPath: String) : FixtureResult()

        /** Neither internal nor external copy exists — an expected, first-time-setup state. Callers should skip cleanly. */
        data class Missing(val externalPathChecked: String) : FixtureResult()

        /** External copy exists, but seeding failed or key files are missing afterward — a real problem. Callers should fail, not skip. */
        data class CopyFailed(val reason: String) : FixtureResult()
    }

    private data class ShellResult(val exitCode: Int, val output: String)

    /** Runs [command] through the shell UID via `UiAutomation.executeShellCommand()` — which already invokes the command through a real shell internally, so pipes/redirects/single-quoted sub-expressions in [command] are interpreted correctly without any extra wrapping needed here. Parses the real exit code back out via an appended marker, since `executeShellCommand()` does not expose one directly. */
    private fun runShellCommand(command: String): ShellResult {
        val commandWithExitMarker = "$command; echo $EXIT_CODE_MARKER:\$?"
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = uiAutomation.executeShellCommand(commandWithExitMarker)
        val rawOutput = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
        val exitMarkerRegex = Regex("$EXIT_CODE_MARKER:(-?\\d+)")
        val match = exitMarkerRegex.find(rawOutput)
        val exitCode = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val cleanOutput = rawOutput.replace(exitMarkerRegex, "").trim()
        return ShellResult(exitCode, cleanOutput)
    }

    /**
     * Ensures [fixtureName] exists under [context]'s own `filesDir`,
     * seeding it via a shell-pipe `tar` from the external staging path
     * if the internal copy is missing or incomplete. Logs every step
     * under [TAG], including a log line at entry before any check
     * happens at all.
     */
    fun ensureFixtureAvailable(context: Context, fixtureName: String): FixtureResult {
        Log.i(TAG, "ensureFixtureAvailable() called for fixtureName=$fixtureName")

        val internalBasePath = context.filesDir.absolutePath
        Log.i(TAG, "app internal files absolute path=$internalBasePath")

        val internalFixture = File(context.filesDir, fixtureName)
        val internalPath = internalFixture.absolutePath
        Log.i(TAG, "internal fixture path=$internalPath")

        // Internal-storage checks use plain File — this side was never broken.
        val internalExistsBefore = internalFixture.exists() && internalFixture.isDirectory
        Log.i(TAG, "internal fixture exists before seeding=$internalExistsBefore")

        if (internalExistsBefore) {
            Log.i(TAG, "internal fixture present — verifying key files before trusting it:")
            if (hasAllKeyEntries(internalFixture)) {
                Log.i(TAG, "internal fixture already present with all key entries — nothing to seed.")
                return FixtureResult.Ready(internalPath)
            }
            Log.w(TAG, "internal fixture exists but is missing one or more key entries — treating as incomplete, will attempt to re-seed.")
        }

        val externalPath = "$EXTERNAL_STAGING_BASE_PATH/$fixtureName"
        Log.i(TAG, "external fixture path=$externalPath")

        // External existence check via the SHELL UID, not the app UID —
        // this is the specific check that was silently wrong before,
        // since a plain java.io.File check from the app/instrumentation
        // process can report "does not exist" on Android 16 scoped
        // storage even when the shell UID can see it fine.
        val existsCheck = runShellCommand("test -e '$externalPath' && echo POKERPG_EXISTS:YES || echo POKERPG_EXISTS:NO")
        val externalExistsFromShell = existsCheck.output.contains("POKERPG_EXISTS:YES")
        Log.i(TAG, "external fixture exists from shell command=$externalExistsFromShell (shell exitCode=${existsCheck.exitCode}, output=\"${existsCheck.output}\")")

        if (!externalExistsFromShell) {
            Log.w(TAG, "Missing PE21 fixture. Push it once to $externalPath")
            return FixtureResult.Missing(externalPath)
        }

        val packageName = context.packageName
        val seedCommand = "cd $EXTERNAL_STAGING_BASE_PATH && tar -cf - $fixtureName | run-as $packageName sh -c 'cd files && tar -xf -'"
        Log.i(TAG, "shell-pipe seed command start: $seedCommand")
        val seedResult = runShellCommand(seedCommand)
        Log.i(TAG, "shell-pipe seed command end: exitCode=${seedResult.exitCode}")
        Log.i(TAG, "shell-pipe seed command output=\"${seedResult.output}\"")

        val finalFileCount = if (internalFixture.exists()) internalFixture.walkTopDown().count { it.isFile } else 0
        Log.i(TAG, "final internal file count=$finalFileCount")

        if (seedResult.exitCode != 0) {
            val reason = "shell-pipe seed command exited non-zero (exitCode=${seedResult.exitCode}, output=\"${seedResult.output}\") — external fixture exists at $externalPath but did not seed successfully into $internalPath."
            Log.e(TAG, "COPY FAILED: $reason")
            return FixtureResult.CopyFailed(reason)
        }

        Log.i(TAG, "verifying key files after seeding:")
        val keyEntriesOk = hasAllKeyEntries(internalFixture)
        if (!keyEntriesOk) {
            val reason = "Shell-pipe seed command exited 0, but one or more key files/folders are missing afterward at $internalPath — see per-entry log lines above for exactly which ones."
            Log.e(TAG, "COPY FAILED: $reason")
            return FixtureResult.CopyFailed(reason)
        }

        Log.i(TAG, "seeding complete — fixture ready at $internalPath")
        return FixtureResult.Ready(internalPath)
    }

    /** Logs each key entry's own presence individually (plain File — app-private storage, unaffected by the scoped-storage issue), for exact diagnosis of a partial/corrupt fixture, and returns whether every one of them exists. */
    private fun hasAllKeyEntries(fixtureRoot: File): Boolean {
        var allPresent = true
        for (entryName in KEY_FIXTURE_ENTRIES) {
            val present = File(fixtureRoot, entryName).exists()
            Log.i(TAG, "key-check $entryName present=$present")
            if (!present) allPresent = false
        }
        return allPresent
    }
}

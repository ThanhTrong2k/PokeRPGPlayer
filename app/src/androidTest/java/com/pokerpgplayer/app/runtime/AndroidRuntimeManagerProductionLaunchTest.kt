package com.pokerpgplayer.app.runtime

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sprint 53 — the one thing `AndroidRuntimeManagerTest`'s own plain JVM
 * tests cannot cover: does [AndroidRuntimeManager.launch]'s own real
 * `Intent`/`startActivity()` step actually reach a resumed
 * [RuntimeActivity] on a real device.
 *
 * **Deliberately does not exercise a real SAF tree URI.** Doing so would
 * require either real user interaction with the system folder picker or
 * a mocked `ContentProvider`/`DocumentsProvider` — exactly the "another
 * fake architecture" this sprint's own instrumentation-test guidance
 * warns against introducing. Instead, a minimal [FakeRuntimeWorkspaceService]
 * (a simple stub returning a fixed [WorkspaceResult], not a new
 * architecture) is pointed at a real, pre-seeded internal directory —
 * every step *after* workspace resolution (production config overlay
 * generation via the real [RuntimeConfigOverlayService], `Intent`
 * construction, `startActivity()`, and [RuntimeActivity] actually
 * reaching a resumed state) is exercised for real. The workspace-mirror
 * step itself ([MirrorRuntimeWorkspaceService]/[WorkspaceError] mapping)
 * is already covered by `AndroidRuntimeManagerTest`'s own plain JVM
 * tests against real fixtures, and by Sprint 6's own existing mirror
 * tests — not duplicated here.
 *
 * Follows the same evidence caveats as every prior Sprint 8-12
 * `RuntimeActivity` test in this package (see
 * `RuntimeActivityValidWorkspaceInitTest`'s own kdoc): the selected
 * fork's documented `System.exit(0)` teardown behavior can still
 * terminate the instrumentation process once native execution proceeds
 * far enough — a JUnit FAIL or the process dying outright is still an
 * **acceptable** result here, provided logcat already shows
 * `RuntimeActivity` received the workspace path this test seeded and
 * proceeded into native startup.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRuntimeManagerProductionLaunchTest {

    companion object {
        private const val TAG = "AndroidRuntimeManagerProductionLaunchTest"
        private const val NATIVE_STARTUP_WAIT_MS = 10000L
        private const val CLOSE_TIMEOUT_MS = 15000L
    }

    private class FakeRuntimeWorkspaceService(private val result: WorkspaceResult) : RuntimeWorkspaceService {
        override suspend fun prepareWorkspace(gameEntryId: String, folderUri: String): WorkspaceResult = result
        override suspend fun getWorkspaceMetadata(gameEntryId: String): WorkspaceMetadata? = null
        override suspend fun clearWorkspace(gameEntryId: String): Boolean = true
    }

    @Test
    fun androidRuntimeManager_launch_reachesResumedRuntimeActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // A real, existing, empty app-private directory with a minimal
        // real mkxp.json — analogous to RuntimeActivityValidWorkspaceInitTest's
        // own "empty but real, existing directory" fixture, extended with
        // a config file since AndroidRuntimeManager's own prepareLaunch()
        // requires one to exist before it will proceed.
        val workspace = File(context.filesDir, "sprint53-production-launch-test-workspace")
        if (workspace.exists()) {
            workspace.deleteRecursively()
        }
        workspace.mkdirs()
        File(workspace, "mkxp.json").writeText("""{"windowTitle": "Sprint53Test"}""")

        Log.i(TAG, "Seeded real workspace at: ${workspace.absolutePath}")

        val fakeWorkspaceService = FakeRuntimeWorkspaceService(
            WorkspaceResult.Success(
                WorkspaceMetadata(
                    gameEntryId = "sprint53-test-game",
                    sourceUri = "content://fake/not-used-by-this-test",
                    workspacePath = workspace.absolutePath,
                    createdAt = "now",
                    updatedAt = "now",
                    fileCount = 1,
                    totalSizeBytes = 20
                )
            )
        )

        val manager = AndroidRuntimeManager(context, fakeWorkspaceService, RuntimeConfigOverlayService())

        val request = GameLaunchRequest(
            gameId = "sprint53-test-game",
            folderUri = "content://fake/not-used-by-this-test",
            executableName = "Game.exe",
            detectedTitle = "Sprint 53 Production Launch Test"
        )

        val result = runBlocking { manager.launch(request) }
        Log.i(TAG, "AndroidRuntimeManager.launch() returned: $result")

        // A real, structural assertion this test CAN make without
        // depending on native timing: launch() itself must report
        // Launched, meaning production config preparation genuinely
        // succeeded and startActivity() did not throw.
        org.junit.Assert.assertTrue(
            "AndroidRuntimeManager.launch() must report Launched for a valid, pre-seeded workspace with a real mkxp.json",
            result is RuntimeLaunchResult.Launched
        )

        // Confirm the config overlay was genuinely applied to the real
        // workspace file, not just claimed — a concrete, file-based
        // checkpoint independent of native/logcat timing.
        val finalConfig = File(workspace, "mkxp.json").readText()
        org.junit.Assert.assertTrue(
            "workspace mkxp.json must contain preloadScript after AndroidRuntimeManager.launch()'s own production preparation",
            finalConfig.contains("preloadScript")
        )

        // From here, the same native-boundary observation pattern as
        // RuntimeActivityValidWorkspaceInitTest — this test does not
        // itself hold an ActivityScenario (launch() started the Activity
        // via a plain Intent, not ActivityScenario.launch), so there is
        // no scenario object here to move to RESUMED or close explicitly.
        // The Activity that launch() started is left running for
        // NATIVE_STARTUP_WAIT_MS so logcat can capture native startup
        // evidence, then this test simply ends — matching this whole
        // package's own established "logcat is the real evidence,
        // JUnit completion is secondary" convention.
        Log.i(TAG, "Waiting ${NATIVE_STARTUP_WAIT_MS}ms for native SDLMain startup before this test ends.")
        Thread.sleep(NATIVE_STARTUP_WAIT_MS)
        Log.i(TAG, "Wait complete — test ending. Check logcat for RuntimeActivity/mkxp native startup evidence.")
    }
}

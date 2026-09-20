package com.pokerpgplayer.app.runtime

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 53.1 robustness-preservation correction. Covers
 * [AndroidRuntimeManager.performStartActivity] — the structured
 * `try`/`catch` around the one Android-framework step
 * ([AndroidRuntimeManager.launch]'s own `Intent`/`startActivity()`) that
 * `AndroidRuntimeManagerTest`'s own plain-JVM tests explicitly do not
 * exercise, per that file's own kdoc (it tests [RuntimeLaunchPreparer]
 * only). [performStartActivity] lives on the companion object and takes
 * the platform step as an injectable lambda specifically so it can be
 * called without ever constructing an [AndroidRuntimeManager] instance —
 * and therefore without ever needing a real [android.content.Context],
 * which is abstract in the real Android SDK jar and cannot be
 * instantiated directly in a plain JVM test anyway. Same pure-core/
 * thin-wrapper seam pattern already used throughout this codebase (see
 * [RuntimeLaunchPreparer], [MirrorRuntimeWorkspaceService]).
 */
class AndroidRuntimeManagerLaunchFailureMappingTest {

    @Test
    fun `a successful platform step reports Launched`() {
        val result = AndroidRuntimeManager.performStartActivity { /* no-op: succeeds */ }

        assertEquals(RuntimeLaunchResult.Launched, result)
    }

    @Test
    fun `an unexpected exception from the platform step becomes Failed with LaunchFailed, never a raw crash`() {
        val result = AndroidRuntimeManager.performStartActivity {
            throw IllegalStateException("ActivityNotFoundException stand-in")
        }

        assertTrue(result is RuntimeLaunchResult.Failed)
        val error = (result as RuntimeLaunchResult.Failed).error
        assertTrue(
            "an unexpected startActivity failure must map to RuntimeError.LaunchFailed, not be conflated with a preparation failure",
            error is RuntimeError.LaunchFailed
        )
        assertEquals("ActivityNotFoundException stand-in", (error as RuntimeError.LaunchFailed).reason)
    }

    @Test
    fun `a platform step with no exception message still produces a non-blank LaunchFailed reason`() {
        val result = AndroidRuntimeManager.performStartActivity {
            throw IllegalStateException()
        }

        val error = (result as RuntimeLaunchResult.Failed).error as RuntimeError.LaunchFailed
        assertTrue("a LaunchFailed reason must never be blank even when the underlying exception carries no message", error.reason.isNotBlank())
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException from the platform step propagates rather than becoming a Failed result`() {
        AndroidRuntimeManager.performStartActivity {
            throw CancellationException("cancelled")
        }
    }

    @Test
    fun `CancellationException from the platform step is never wrapped as LaunchFailed`() {
        try {
            AndroidRuntimeManager.performStartActivity {
                throw CancellationException("cancelled")
            }
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected — the whole point of this test is that this path
            // is NOT caught by the generic `catch (t: Throwable)` branch
            // and converted into RuntimeError.LaunchFailed.
        }
    }
}

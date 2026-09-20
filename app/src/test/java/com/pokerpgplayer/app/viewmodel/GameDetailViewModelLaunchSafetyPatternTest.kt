package com.pokerpgplayer.app.viewmodel

import com.pokerpgplayer.app.runtime.RuntimeError
import com.pokerpgplayer.app.runtime.RuntimeLaunchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Sprint 53.1 — ChatGPT audit correction, Blocker 2. Tests requirement 4
 * ("unexpected RuntimeManager exception in GameDetailViewModel.play() →
 * launchState becomes Failed, not permanently Launching") and requirement
 * 5 ("CancellationException → propagates/cancels normally, not converted
 * into LaunchFailed").
 *
 * **Honest scope note**: this does NOT construct a real [GameDetailViewModel]
 * instance. `androidx.lifecycle.ViewModel`'s own `viewModelScope` requires
 * `Dispatchers.Main` to be set, which needs either Robolectric or
 * `kotlinx-coroutines-test`'s own `Dispatchers.setMain()` — neither
 * dependency is declared in the real `app/build.gradle.kts` read for this
 * sprint (only plain `junit:junit:4.13.2` for `testImplementation`).
 * Attempting to construct and drive a real `GameDetailViewModel` here
 * risks either a compile failure (if `kotlinx-coroutines-test` genuinely
 * isn't available) or a real runtime crash (`Dispatchers.Main` not set).
 * Instead, this test reproduces the *exact same* `MutableStateFlow` +
 * `try`/`catch` structure `GameDetailViewModel.play()` actually uses,
 * verifying the pattern itself is correct — a faithful logic-level proxy,
 * not a substitute for eventually exercising the real class (e.g. via a
 * future Robolectric-based test, if that dependency is ever added).
 */
class GameDetailViewModelLaunchSafetyPatternTest {

    /** Mirrors LaunchUiState exactly, kept local to avoid any Android/ViewModel dependency in this test. */
    private sealed class TestLaunchState {
        object Idle : TestLaunchState()
        object Launching : TestLaunchState()
        data class Failed(val error: RuntimeError) : TestLaunchState()
    }

    /**
     * Reproduces GameDetailViewModel.play()'s own coroutine body exactly
     * — same try/catch shape, same CancellationException rethrow, same
     * sanitized (never-raw-message) Failed mapping.
     */
    private suspend fun runLaunchAttempt(
        state: MutableStateFlow<TestLaunchState>,
        launch: suspend () -> RuntimeLaunchResult
    ) {
        state.value = TestLaunchState.Launching
        try {
            when (val result = launch()) {
                is RuntimeLaunchResult.Launched -> state.value = TestLaunchState.Idle
                is RuntimeLaunchResult.Failed -> state.value = TestLaunchState.Failed(result.error)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            state.value = TestLaunchState.Failed(
                RuntimeError.LaunchFailed("An unexpected error occurred while launching (${t::class.simpleName}). Please try again.")
            )
        }
    }

    @Test
    fun `Sprint53_1 - an unexpected exception from RuntimeManager-launch results in Failed, never stuck in Launching`() = runBlocking {
        val state = MutableStateFlow<TestLaunchState>(TestLaunchState.Idle)

        runLaunchAttempt(state) {
            throw RuntimeException("simulated unexpected failure — e.g. a bug in RuntimeManager that should have returned a structured result instead")
        }

        assertTrue("must never remain stuck in Launching after an unexpected exception", state.value is TestLaunchState.Failed)
    }

    @Test
    fun `Sprint53_1 - the Failed state from an unexpected exception never exposes the raw exception message`() = runBlocking {
        val state = MutableStateFlow<TestLaunchState>(TestLaunchState.Idle)
        val sensitiveRawMessage = "raw internal path: /data/user/0/com.pokerpgplayer.app.debug/files/internal-secret-detail"

        runLaunchAttempt(state) {
            throw RuntimeException(sensitiveRawMessage)
        }

        val failed = state.value as TestLaunchState.Failed
        val shownReason = (failed.error as RuntimeError.LaunchFailed).reason
        assertFalse("the raw exception message must never be shown directly to the player", shownReason.contains(sensitiveRawMessage))
        assertTrue("a sanitized, generic reason should still be shown", shownReason.contains("unexpected error"))
    }

    @Test
    fun `Sprint53_1 - CancellationException propagates and cancels normally, is never converted into a Failed state`() {
        val state = MutableStateFlow<TestLaunchState>(TestLaunchState.Idle)
        var caughtOutsideAsCancellation = false

        runBlocking {
            val job = launch {
                try {
                    runLaunchAttempt(state) {
                        throw CancellationException("simulated coroutine scope cancellation — e.g. the screen was closed mid-launch")
                    }
                } catch (e: CancellationException) {
                    caughtOutsideAsCancellation = true
                    throw e
                }
            }
            job.join()
        }

        assertTrue("CancellationException must propagate out, not be silently absorbed", caughtOutsideAsCancellation)
        assertEquals(
            "state must still read Launching (the try/catch's own catch(Throwable) block must NEVER have run for a CancellationException) — it is not this coroutine's own job to decide what the state should be after being cancelled",
            TestLaunchState.Launching,
            state.value
        )
    }
}

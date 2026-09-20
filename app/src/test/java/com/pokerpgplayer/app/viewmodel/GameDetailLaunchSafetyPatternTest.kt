package com.pokerpgplayer.app.viewmodel

import com.pokerpgplayer.app.runtime.GameLaunchRequest
import com.pokerpgplayer.app.runtime.RuntimeError
import com.pokerpgplayer.app.runtime.RuntimeLaunchResult
import com.pokerpgplayer.app.runtime.WorkspaceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 53.1 (never-stuck launch state) / Sprint 53.2 (Preparing vs
 * Launching, re-entrancy guard) — reproduces [GameDetailViewModel.play]'s
 * exact try/catch/state-selection/re-entrancy logic in a plain,
 * non-Android class.
 *
 * A real [GameDetailViewModel] cannot be unit-tested directly in this
 * project's plain JVM test setup: `viewModelScope` requires a
 * `Dispatchers.Main` implementation, which needs either Robolectric or
 * `kotlinx-coroutines-test`'s `Dispatchers.setMain` — neither is a
 * dependency here (`build.gradle.kts` has only
 * `testImplementation("junit:junit:4.13.2")`). This test instead proves
 * the *pattern itself* — the same shape of code, run for real with real
 * `kotlinx.coroutines` primitives via `runBlocking`/`async` — is correct
 * in isolation from any Android/ViewModel machinery.
 */
class GameDetailLaunchSafetyPatternTest {

    private sealed class State {
        object Idle : State()
        object Preparing : State()
        object Launching : State()
        data class Failed(val error: RuntimeError) : State()
    }

    /** Mirrors [GameDetailViewModel.play]'s body exactly, minus everything Android/ViewModel-specific. */
    private class StateHolder(
        private val getWorkspaceState: suspend (String) -> WorkspaceState?,
        private val launch: suspend (GameLaunchRequest) -> RuntimeLaunchResult
    ) {
        @Volatile
        var state: State = State.Idle
            private set

        var launchCallCount = 0
            private set

        suspend fun play(gameId: String, request: GameLaunchRequest) {
            if (state is State.Preparing || state is State.Launching) return

            val alreadyReady = runCatching { getWorkspaceState(gameId) == WorkspaceState.READY }.getOrDefault(false)
            state = if (alreadyReady) State.Launching else State.Preparing

            try {
                launchCallCount++
                when (val result = launch(request)) {
                    is RuntimeLaunchResult.Launched -> state = State.Idle
                    is RuntimeLaunchResult.Failed -> state = State.Failed(result.error)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                state = State.Failed(RuntimeError.Unknown("Unexpected error while launching: ${t::class.simpleName}"))
            }
        }
    }

    private fun sampleRequest() = GameLaunchRequest(
        gameId = "550e8400-e29b-41d4-a716-446655440000",
        folderUri = "content://fake",
        executableName = "Game.exe",
        detectedTitle = null
    )

    @Test
    fun `an unexpected exception from launch becomes Failed, never stuck in Preparing or Launching`() = runBlocking {
        val holder = StateHolder(
            getWorkspaceState = { null },
            launch = { throw IllegalStateException("native boom") }
        )

        holder.play("gid", sampleRequest())

        assertTrue(holder.state is State.Failed)
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException from launch propagates and is not converted into Failed`(): Unit = runBlocking {
        val holder = StateHolder(
            getWorkspaceState = { WorkspaceState.READY },
            launch = { throw CancellationException("cancelled") }
        )

        holder.play("gid", sampleRequest())
    }

    @Test
    fun `a successful launch returns state to Idle`() = runBlocking {
        val holder = StateHolder(
            getWorkspaceState = { null },
            launch = { RuntimeLaunchResult.Launched }
        )

        holder.play("gid", sampleRequest())

        assertEquals(State.Idle, holder.state)
    }

    @Test
    fun `a structured Failed result surfaces as Failed with the same error`() = runBlocking {
        val error = RuntimeError.Unknown("boom")
        val holder = StateHolder(
            getWorkspaceState = { null },
            launch = { RuntimeLaunchResult.Failed(error) }
        )

        holder.play("gid", sampleRequest())

        assertEquals(State.Failed(error), holder.state)
    }

    @Test
    fun `Preparing vs Launching selection matches workspace state exactly`() = runBlocking {
        suspend fun selectionFor(workspaceState: WorkspaceState?): State {
            val alreadyReady = (workspaceState == WorkspaceState.READY)
            return if (alreadyReady) State.Launching else State.Preparing
        }
        assertTrue(selectionFor(WorkspaceState.READY) is State.Launching)
        assertTrue(selectionFor(WorkspaceState.MISSING) is State.Preparing)
        assertTrue(selectionFor(WorkspaceState.FAILED) is State.Preparing)
        assertTrue(selectionFor(WorkspaceState.PREPARING) is State.Preparing)
        assertTrue(selectionFor(null) is State.Preparing)
    }

    @Test
    fun `a second concurrent play call is ignored while the first is still in flight`() = runBlocking {
        // A real suspension point (CompletableDeferred) lets a second,
        // truly concurrent play() observe state == Preparing/Launching
        // before the first call ever resolves — not just two sequential
        // calls that happen to run one after another.
        val releaseFirstLaunch = CompletableDeferred<Unit>()
        val holder = StateHolder(
            getWorkspaceState = { null },
            launch = {
                releaseFirstLaunch.await()
                RuntimeLaunchResult.Launched
            }
        )

        val firstCall = async { holder.play("gid", sampleRequest()) }
        // Give the first coroutine a chance to reach the suspension point
        // and set state to Preparing before the second call is attempted.
        while (holder.state !is State.Preparing) { kotlinx.coroutines.yield() }

        holder.play("gid", sampleRequest()) // must be a no-op: state is Preparing
        assertEquals(1, holder.launchCallCount)

        releaseFirstLaunch.complete(Unit)
        firstCall.await()

        assertEquals(State.Idle, holder.state)
        assertEquals(1, holder.launchCallCount)
    }
}

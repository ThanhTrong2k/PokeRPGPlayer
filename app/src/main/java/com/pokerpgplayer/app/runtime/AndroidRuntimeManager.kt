package com.pokerpgplayer.app.runtime

import android.content.Context
import android.content.Intent
import com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sprint 53 — the first real [RuntimeManager], replacing
 * [StubRuntimeManager] in [com.pokerpgplayer.app.AppContainer]. Thin by
 * design: all real preparation logic lives in [RuntimeLaunchPreparer]
 * (fully unit-testable, no [Context]); this class adds only the one
 * Android-framework step that logic can't own itself — building and
 * firing the `Intent` that starts [RuntimeActivity].
 *
 * Takes an application [Context] only (never an Activity context), same
 * rule [com.pokerpgplayer.app.AppContainer] already follows everywhere
 * else — this manager's lifetime is the whole process, not one screen.
 */
class AndroidRuntimeManager(
    private val appContext: Context,
    private val preparer: RuntimeLaunchPreparer
) : RuntimeManager {

    /**
     * Build-compatibility correction: [overlayService] is a real,
     * pre-existing parameter of this constructor (see
     * `AndroidRuntimeManagerProductionLaunchTest`, which calls this
     * 3-arg form explicitly) that an earlier revision of this file
     * dropped when it only had visibility into a pre-Sprint-53 audit
     * tree. Given a default, this single constructor also covers the
     * plain `(appContext, workspaceService)` call site already used by
     * [com.pokerpgplayer.app.AppContainer] — restoring the missing
     * parameter rather than keeping two separate overloads.
     */
    constructor(
        appContext: Context,
        workspaceService: RuntimeWorkspaceService,
        overlayService: RuntimeConfigOverlayService = RuntimeConfigOverlayService()
    ) : this(appContext, RuntimeLaunchPreparer(workspaceService, overlayService))

    private val _status = MutableStateFlow(RuntimeStatus.UNAVAILABLE)
    override val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    override suspend fun launch(request: GameLaunchRequest): RuntimeLaunchResult {
        return when (val prepared = preparer.prepareLaunch(request)) {
            is LaunchPreparationResult.Failed -> RuntimeLaunchResult.Failed(prepared.error)
            is LaunchPreparationResult.Ready -> startRuntimeActivity(prepared.workspacePath)
        }
    }

    /**
     * Sprint 53.1 robustness-preservation correction. Restored: the one
     * Android-framework step this whole thin-wrapper class exists for —
     * `Intent` construction and [Context.startActivity] — gets its own
     * structured `try`/`catch`, separate from [RuntimeLaunchPreparer]'s
     * own (which only ever sees preparation failures, never this one).
     * [CancellationException] is rethrown unconverted; any other
     * `Throwable` from `startActivity` (e.g. `ActivityNotFoundException`,
     * or a real device's own launch-time rejection) becomes a structured
     * [RuntimeLaunchResult.Failed] carrying [RuntimeError.LaunchFailed]
     * rather than crashing the caller or being silently swallowed.
     * Application [Context] always requires
     * [Intent.FLAG_ACTIVITY_NEW_TASK] to start an Activity — this class's
     * own kdoc already establishes that [appContext] is always an
     * application Context, never an Activity one, so this flag is not
     * conditional on that distinction.
     */
    private fun startRuntimeActivity(workspacePath: String): RuntimeLaunchResult =
        performStartActivity {
            val intent = Intent(appContext, RuntimeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(RuntimeActivity.EXTRA_WORKSPACE_PATH, workspacePath)
            }
            appContext.startActivity(intent)
        }

    companion object {
        /**
         * Testability seam for the structured `try`/`catch` above:
         * [platformStep] is the real `Intent`/`startActivity` call in
         * production, and a plain throwing/non-throwing lambda in a JVM
         * unit test — no [Context] or `Intent` instance is needed at all
         * to call this, which matters concretely: [Context] is abstract
         * in the real Android SDK jar and cannot be constructed directly
         * even in a unit test, so this lives on the companion object
         * rather than as an instance method, specifically so a plain JVM
         * test can call it without ever needing an [AndroidRuntimeManager]
         * instance (and therefore without ever needing a [Context]) at
         * all — the same pure-core/thin-wrapper seam pattern already used
         * throughout this codebase. `internal` rather than `private` so a
         * plain JVM test (a friend module of `main`, same as every other
         * `internal` seam here) can call it directly with a fake
         * [platformStep].
         */
        internal fun performStartActivity(platformStep: () -> Unit): RuntimeLaunchResult {
            return try {
                platformStep()
                RuntimeLaunchResult.Launched
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                RuntimeLaunchResult.Failed(RuntimeError.LaunchFailed(t.message ?: t::class.simpleName ?: "Unknown launch failure"))
            }
        }
    }
}

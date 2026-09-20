package com.pokerpgplayer.app.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sprint 3's only [RuntimeManager] implementation. There is no native
 * runtime yet — this class does not attempt to load one, does not touch
 * JNI, and does not reference `System.loadLibrary` in any form. It exists
 * purely so the rest of the app has something real to depend on while the
 * native runtime doesn't exist yet.
 *
 * Named "Stub", not "Native" — calling it "Native" would imply a real
 * native binding exists, which is exactly the kind of overstatement this
 * sprint's own boundaries rule out. A name is a form of documentation; it
 * should say what the class actually is.
 *
 * [status] is backed by a `MutableStateFlow` that is never written to
 * anywhere in this class, so it is structurally impossible for this stub
 * to report anything other than [RuntimeStatus.UNAVAILABLE] — that's not
 * an implementation detail to remember to preserve, it's a property of the
 * code that can't be violated by accident.
 *
 * Takes no `Context` and no other dependency — fully framework-free, pure
 * Kotlin, and trivially unit-testable as a result.
 */
class StubRuntimeManager : RuntimeManager {
    private val _status = MutableStateFlow(RuntimeStatus.UNAVAILABLE)
    override val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    override suspend fun launch(request: GameLaunchRequest): RuntimeLaunchResult =
        RuntimeLaunchResult.Failed(RuntimeError.RuntimeNotImplemented)
}

package com.pokerpgplayer.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sprint 7 — Native Runtime Packaging / Native Load Smoke Test.
 *
 * Proves exactly one thing: whether the ARM64 native libraries packaged
 * into `app/src/main/jniLibs/arm64-v8a/` can be loaded, via
 * `System.loadLibrary()`, into a real Android test process on Ti's
 * device, in the approved dependency order.
 *
 * **Does NOT prove:** that the mkxp-z engine can run anything, that a
 * game can boot, that Ruby can execute a script, or that any window/GL
 * surface can be created. No JNI function is called, no engine
 * entrypoint is reached, no Ruby VM is initialized, no SDL window or GL
 * surface is created. See [NativeRuntimeLoader]'s own kdoc for the full
 * explanation of what a passing result does and does not mean.
 *
 * This is an `androidTest` (instrumented test), not a plain JVM unit
 * test, because `System.loadLibrary()` needs a real Android runtime with
 * the actual packaged `.so` files present in the installed test app's
 * native library directory — something Claude's environment (no Android
 * SDK, no device, no emulator) cannot provide. **This test can only
 * actually be run by Ti, locally, on a real device** — see this sprint's
 * README section for exact steps to run it in Android Studio.
 *
 * Deliberately not reachable from any UI, ViewModel, or
 * [com.pokerpgplayer.app.AppContainer] — the only way to run this is
 * Android Studio's own instrumented test runner, or
 * `./gradlew connectedAndroidTest`, never anything reachable from the
 * app's own screens.
 */
@RunWith(AndroidJUnit4::class)
class NativeLibraryLoadSmokeTest {

    @Test
    fun nativeLibraries_loadSuccessfully_orReportExactlyWhichOneFailed() {
        when (val result = NativeRuntimeLoader.loadAll()) {
            is NativeLoadResult.Loaded -> {
                // A pass here means only "native library load smoke test
                // success" for these 8 prebuilt .so files on this device —
                // not runtime integration, not game boot, not gameplay
                // support. See NativeRuntimeLoader's kdoc.
                println(
                    "Native library load smoke test: all ${NativeRuntimeLoader.LOAD_ORDER.size} " +
                        "libraries (${NativeRuntimeLoader.LOAD_ORDER.joinToString()}) loaded successfully. " +
                        "This proves ONLY that these .so files dlopen() on this device — no engine " +
                        "entrypoint, Ruby VM, window, or GL surface was touched."
                )
            }
            is NativeLoadResult.Failed -> {
                // Reports exactly which library failed and the exact
                // Throwable message, per Sprint 7's required test
                // semantics — never a bare pass/fail with no detail.
                fail(
                    "Native library load smoke test failed at \"${result.libraryName}\": ${result.message}"
                )
            }
        }
    }
}

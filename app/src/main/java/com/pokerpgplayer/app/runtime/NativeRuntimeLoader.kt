package com.pokerpgplayer.app.runtime

/**
 * The one place `System.loadLibrary()` is called for the native runtime's
 * prebuilt ARM64 libraries (Sprint 7 — Native Runtime Packaging / Native
 * Load Smoke Test).
 *
 * **Deliberately not wired into [com.pokerpgplayer.app.AppContainer] and not
 * called by any production UI, ViewModel, or [RuntimeManager]/[StubRuntimeManager]
 * code path.** Per Sprint 7's approved narrow scope, the only caller of
 * this class anywhere in this codebase is the `androidTest` smoke test
 * (`NativeLibraryLoadSmokeTest`). Nothing in the shipped app currently
 * invokes this class at all — it exists purely so that test has something
 * real to call.
 *
 * **What a [NativeLoadResult.Loaded] result does and does not prove:**
 * `System.loadLibrary()` only resolves and `dlopen()`s a shared library
 * into the current process — it does not call into any of the library's
 * own functions, does not touch the mkxp-z engine's entrypoint, does not
 * initialize the Ruby VM, does not create any SDL window or GL surface,
 * and does not attempt anything resembling running a game. "Loaded" means
 * exactly one thing: the `.so` file and its declared native dependencies
 * resolved successfully. Nothing more should be inferred from it — not
 * runtime integration readiness, not game boot capability, not gameplay
 * support.
 *
 * **Process-global caveat:** the JVM/ART only ever loads a given native
 * library once per process — a repeated `System.loadLibrary()` call for
 * an already-loaded library is a silent no-op, not a fresh re-verification.
 * Calling [loadAll] more than once within the same test process does not
 * re-prove loading from scratch the second time; a genuine re-verification
 * needs a fresh process (a new instrumented test run, or an app restart).
 */
object NativeRuntimeLoader {

    /**
     * Approved load order (Sprint 7 plan review, ChatGPT/Ti-approved):
     * dependencies before dependents, `mkxp-z` last since it is the one
     * library that depends on all the others.
     */
    val LOAD_ORDER: List<String> = listOf(
        "c++_shared",
        "SDL2",
        "SDL2_image",
        "SDL2_sound",
        "SDL2_ttf",
        "openal",
        "ruby",
        "mkxp-z"
    )

    /**
     * Attempts to load every library in [LOAD_ORDER], in order, stopping
     * at the very first failure — never silently continues past one, since
     * a later library failing to load because an earlier dependency didn't
     * load would not add any diagnostic value, only noise.
     *
     * Catches [Throwable], not just [Exception], deliberately:
     * `System.loadLibrary()`'s primary expected failure mode,
     * [UnsatisfiedLinkError], is a [LinkageError] — an [Error], not an
     * [Exception] — so a narrower `catch (e: Exception)` would let the
     * single most likely failure case escape uncaught instead of being
     * reported. This is the one place in this class where catching
     * [Throwable] broadly is the deliberately correct choice, not a
     * shortcut — the whole purpose of this function is to report exactly
     * what happened, never to let a native-loading problem crash the test
     * process unreported.
     */
    fun loadAll(): NativeLoadResult {
        for (libraryName in LOAD_ORDER) {
            try {
                System.loadLibrary(libraryName)
            } catch (t: Throwable) {
                return NativeLoadResult.Failed(
                    libraryName,
                    "${t::class.java.simpleName}: ${t.message ?: "no message"}"
                )
            }
        }
        return NativeLoadResult.Loaded
    }
}

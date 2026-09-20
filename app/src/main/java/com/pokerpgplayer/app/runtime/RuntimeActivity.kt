package com.pokerpgplayer.app.runtime

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import org.libsdl.app.SDLActivity

/**
 * Sprint 8 — First Runtime Launch Boundary / GAME_PATH Injection.
 *
 * **Sprint 53 — no longer internal/test-only.** This class was originally
 * built and reached only via `RuntimeActivityLaunchTest` (`androidTest`),
 * with no exported `LAUNCHER` intent-filter and no reachable path from any
 * app screen. As of Sprint 53, [com.pokerpgplayer.app.runtime.AndroidRuntimeManager]
 * is the real, production [RuntimeManager] wired into
 * [com.pokerpgplayer.app.AppContainer], and a player reaches this exact
 * class by tapping Play on [com.pokerpgplayer.app.ui.screens.GameDetailScreen] —
 * the same `EXTRA_WORKSPACE_PATH` contract this class has always exposed,
 * now driven by a real workspace mirror and production config overlay
 * instead of only a diagnostic test harness. This class's own launch
 * mechanism, native-boundary handling, and lifecycle overrides below are
 * unchanged by this — Sprint 53 did not modify any of that, only who
 * calls it and why.
 *
 * Deliberately does **not** reuse or subclass the selected mkxp-z fork's
 * own `MainActivity` — that class carries OBB-mounting logic and a
 * `MANAGE_EXTERNAL_STORAGE` request, neither of which PokeRPG Player's
 * SAF-first architecture uses. Extends `SDLActivity` directly instead.
 *
 * **Why this works without reusing the fork's exact class name or
 * package** (see Sprint 8 Architecture Proposal §2.2): mkxp-z's native
 * side (`main.cpp`) resolves the `GAME_PATH` field *dynamically* —
 * `SDL_AndroidGetActivity()` returns whatever Activity is actually
 * running, and `GetObjectClass(activity)` gets *that instance's* real
 * class. Any Activity that (a) extends `SDLActivity` and (b) declares its
 * own `static String GAME_PATH` field is found correctly, regardless of
 * name or package. JNI's field lookup also bypasses Java's `private`
 * access modifier entirely — irrelevant here regardless, since this is
 * PokeRPG Player's own field on PokeRPG Player's own class.
 *
 * **Timing, confirmed by reading `SDLActivity.java` directly:** the native
 * `SDLMain` thread (which eventually calls into C's `main()`, where
 * `GAME_PATH` is actually read) only starts once the render surface is
 * ready *and* the Activity reaches the resumed lifecycle state — well
 * after `onCreate()` returns. Setting [GAME_PATH] at the very start of
 * [onCreate], before calling `super.onCreate()`, is therefore correctly
 * ordered with real margin to spare.
 *
 * **Does not override** `getLibraries()`/`getMainSharedObject()`/
 * `getMainFunction()` — this fork's own `SDLActivity.java` (already
 * present since App v0.0.8) already defaults all three to the correct
 * mkxp-z values; duplicating that here would be redundant, not "minimal."
 *
 * **`onDestroy()` is not overridden either** — `SDLActivity`'s own
 * superclass chain includes the fork's documented `System.exit(0)` hack
 * (Ruby VM re-init segfaults otherwise) once real native execution
 * reaches that point. Sprint 8 does not attempt to fix or work around
 * this; see the Sprint 8 plan's own risk analysis for why real evidence
 * of this test's outcome must come from `adb logcat`, captured
 * externally, not solely from whether the instrumentation process
 * survives.
 *
 * **Sprint 9 diagnostic step 3 — `onStart()` override, the actual root
 * cause fix:** Ti's diagnostic run (App v0.0.12) showed
 * `SDLActivity.mIsResumedCalled=false` even though logcat showed
 * `"onResume()"` firing. Reading `SDLActivity.java` directly resolved
 * this precisely: `SDLActivity.mHasMultiWindow` is declared
 * `public static final boolean mHasMultiWindow = (Build.VERSION.SDK_INT >= 24)`
 * — a compile-time-deterministic *version* check, not a real multi-window
 * *state* check, and unconditionally `true` on any Android 7.0+ device,
 * including Android 16. `SDLActivity.onResume()` only calls
 * `resumeNativeThread()` when `!mHasMultiWindow` — never true on a modern
 * device — and the *matching* call in `SDLActivity.onStart()`, gated by
 * `if (mHasMultiWindow)`, is commented out in this fork's copy, with a
 * comment stating the intent was for it to be handled by `MainActivity`
 * instead (`"we are now starting SDL thread from MainActivity using the
 * runSDLThread method"`). Confirmed by reading the fork's own
 * `MainActivity.java`: it *does* override `onStart()` and correctly calls
 * `resumeNativeThread()` when `mHasMultiWindow` is true, via its own
 * `runSDLThread()` helper — but `RuntimeActivity` deliberately extends
 * `SDLActivity` directly, not `MainActivity` (to avoid inheriting OBB
 * mounting and the `MANAGE_EXTERNAL_STORAGE` request), and in doing so
 * also lost this unrelated, necessary multi-window-resume trigger. This
 * override restores exactly that one piece — nothing else from
 * `MainActivity` — without modifying the shared `SDLActivity.java` file
 * at all.
 */
class RuntimeActivity : SDLActivity() {

    private var virtualControlsOverlay: VirtualControlsOverlay? = null
    private var runtimeQuickMenuOverlay: RuntimeQuickMenuOverlay? = null

    companion object {
        private const val TAG = "RuntimeActivity"

        /** Intent extra key carrying the raw, already-resolved workspace path (e.g. [com.pokerpgplayer.app.runtime.WorkspaceMetadata.workspacePath]). */
        const val EXTRA_WORKSPACE_PATH = "com.pokerpgplayer.app.runtime.EXTRA_WORKSPACE_PATH"

        /**
         * Must be named exactly `GAME_PATH` — this is the literal string
         * mkxp-z's native code looks up via `GetStaticFieldID(cls,
         * "GAME_PATH", "Ljava/lang/String;")`. Type must be `static
         * String` from the JVM's perspective; a Kotlin `@JvmStatic var
         * String` on a `companion object` satisfies that.
         *
         * Not `private` — unlike the fork's own `MainActivity.GAME_PATH`,
         * PokeRPG Player's own code (this class's [onCreate]) needs
         * ordinary Kotlin access to set it, and there is no reason to
         * add reflection just to write to a field this class itself
         * owns.
         */
        @JvmStatic
        var GAME_PATH: String = ""
            private set

        /**
         * Sprint 37 — required by mkxp-z Android native bridge.
         * Native code looks up this exact static method on the running Activity:
         * getSystemLanguage()Ljava/lang/String;
         */
        @JvmStatic
        fun getSystemLanguage(): String {
            val lang = java.util.Locale.getDefault().toString()
            Log.i(TAG, "SPRINT37_DIAG: getSystemLanguage() called — returning \"$lang\"")
            return lang
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val workspacePath = intent?.getStringExtra(EXTRA_WORKSPACE_PATH)

        if (workspacePath.isNullOrBlank()) {
            Log.e(TAG, "RuntimeActivity started without a valid $EXTRA_WORKSPACE_PATH extra — refusing to proceed. Failing safely.")
            // finish() marks this Activity for teardown before it can ever
            // reach the resumed state the native SDL thread requires (see
            // this class's own kdoc on timing) — the safest available
            // "fail before anything native-side runs" signal. super.onCreate()
            // still has to be called below regardless (Android throws
            // SuperNotCalledException otherwise), but SDLActivity's own
            // onCreate() only loads libraries and checks versions — it does
            // not itself read GAME_PATH or start the SDLMain thread, so
            // calling it after finish() here does not undo this safety.
            finish()
            super.onCreate(savedInstanceState)
            return
        }

        Log.i(TAG, "RuntimeActivity received workspace path: $workspacePath")
        GAME_PATH = workspacePath
        Log.i(TAG, "RuntimeActivity set GAME_PATH to: $GAME_PATH (before calling super.onCreate())")

        super.onCreate(savedInstanceState)
        applySprint35ImmersiveFullscreen("onCreate")

        runtimeQuickMenuOverlay = RuntimeQuickMenuOverlay(this, workspacePath)

        virtualControlsOverlay = VirtualControlsOverlay(this) {
            runtimeQuickMenuOverlay?.toggle()
        }
        virtualControlsOverlay?.attach()

        // Attach after the controls overlay so the menu panel is visually on top when visible.
        runtimeQuickMenuOverlay?.attach()

        Log.i(TAG, "SPRINT39_DIAG: virtual controls overlay attached and retained by RuntimeActivity")
        Log.i(TAG, "SPRINT50_DIAG: Runtime Quick Menu overlay attached and wired to settings button")
    }

    private fun applySprint35ImmersiveFullscreen(reason: String) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        actionBar?.hide()
        Log.i(TAG, "SPRINT35_DIAG: immersive fullscreen applied from $reason — decorFitsSystemWindows=false, systemBars hidden")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySprint35ImmersiveFullscreen("onWindowFocusChanged")
        } else {
            virtualControlsOverlay?.dPadView?.forceReleaseAll()
            Log.i(TAG, "SPRINT39_DIAG: onWindowFocusChanged(false) — forced D-Pad release")
        }
    }

    override fun onStart() {
        super.onStart()

        // Sprint 9 diagnostic step 3 fix — see this class's own kdoc for
        // the full root-cause explanation. SDLActivity.onResume() only
        // calls resumeNativeThread() when !mHasMultiWindow, which is
        // never true on Android 7.0+ (mHasMultiWindow is a version check,
        // not a real multi-window state check) — so on any modern
        // device, nothing ever calls resumeNativeThread() unless
        // something does so explicitly here, mirroring exactly what the
        // fork's own MainActivity.runSDLThread() does for this same
        // case. Nothing else from MainActivity (OBB mounting, GAME_PATH
        // defaults, MANAGE_EXTERNAL_STORAGE) is reproduced — only this
        // one call.
        Log.i(TAG, "onStart(): mHasMultiWindow=$mHasMultiWindow")
        if (mHasMultiWindow) {
            Log.i(TAG, "onStart(): calling resumeNativeThread() — SDLActivity.onResume() alone does not on this Android version.")
            resumeNativeThread()
        }
    }

    /**
     * Sprint 28 — diagnostic-only accessor for the rendering-viewport
     * investigation. Reads `SDLActivity`'s own existing `mSurface` field
     * (`protected static`, accessible here since [RuntimeActivity] is a
     * direct subclass) and returns its current, real, laid-out pixel
     * dimensions via the standard `View.getWidth()`/`getHeight()`
     * methods every Android `View` already exposes. **Zero behavior
     * change** — this reads existing state, it does not create, modify,
     * or resize anything. Added specifically so a diagnostic test can
     * log what the SDL surface's own real Android-side size actually is
     * at a given moment, to compare against the game's own logical
     * resolution and whatever `winSize` mkxp-z's native side is using
     * for its own `glViewport` calculation (Sprint 28's own root-cause
     * hypothesis: these two values may not match on Android, causing
     * the observed lower-left-anchored rendering).
     */
    fun currentSurfaceDimensionsForDiagnostics(): Pair<Int, Int>? {
        val surface = mSurface ?: return null
        return surface.width to surface.height
    }
}

package com.pokerpgplayer.app.runtime

/**
 * Outcome of [NativeRuntimeLoader.loadAll]. Sealed class, matching this
 * codebase's established preference for explicit result types over bare
 * booleans/exceptions (see [WorkspaceResult], [RuntimeLaunchResult]).
 *
 * [Loaded] means only that every library in [NativeRuntimeLoader.LOAD_ORDER]
 * was passed to `System.loadLibrary()` with no exception or error thrown —
 * see [NativeRuntimeLoader]'s own kdoc for exactly what this does and does
 * not prove. It is not a claim of runtime integration, game boot, or
 * gameplay support.
 */
sealed class NativeLoadResult {
    data object Loaded : NativeLoadResult()

    /** [libraryName] is the bare name passed to `System.loadLibrary()` (e.g. "mkxp-z", not "libmkxp-z.so"). */
    data class Failed(val libraryName: String, val message: String) : NativeLoadResult()
}

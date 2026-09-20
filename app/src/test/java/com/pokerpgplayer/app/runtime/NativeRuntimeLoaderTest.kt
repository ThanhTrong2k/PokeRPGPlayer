package com.pokerpgplayer.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit — [NativeRuntimeLoader.LOAD_ORDER] is a bare `List<String>`
 * constant with zero Android framework dependency, so this runs for real
 * (same discipline as [WorkspacePathResolverTest]), not just static review.
 *
 * Deliberately does NOT test [NativeRuntimeLoader.loadAll] itself — that
 * function calls `System.loadLibrary()`, which requires a real Android
 * runtime with the actual packaged `.so` files present. Attempting that
 * here would just throw `UnsatisfiedLinkError` for every library,
 * correctly but meaninglessly, since none of them exist as loadable
 * native code outside a real Android device/emulator. This test exists
 * only to guard the approved dependency order itself from an accidental
 * future reordering.
 */
class NativeRuntimeLoaderTest {

    @Test
    fun `load order matches the approved dependency sequence exactly`() {
        assertEquals(
            listOf("c++_shared", "SDL2", "SDL2_image", "SDL2_sound", "SDL2_ttf", "openal", "ruby", "mkxp-z"),
            NativeRuntimeLoader.LOAD_ORDER
        )
    }

    @Test
    fun `c++_shared is loaded first, since libmkxp-z and the SDL libraries depend on the shared C++ runtime`() {
        assertEquals("c++_shared", NativeRuntimeLoader.LOAD_ORDER.first())
    }

    @Test
    fun `mkxp-z is loaded last, since it is the one library that depends on every other one`() {
        assertEquals("mkxp-z", NativeRuntimeLoader.LOAD_ORDER.last())
    }
}

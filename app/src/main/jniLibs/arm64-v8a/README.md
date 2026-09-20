# arm64-v8a native libraries — ACTION REQUIRED BEFORE BUILDING

**This folder is currently empty of real binaries.** Claude's environment has never had access to
the actual compiled `.so` files — they exist only on Ti's own WSL Ubuntu 22.04 machine, from the
real build performed for Sprint 4/5 (`ndk-build`, Android NDK r23c), archived at
`~/PokeRPGRuntime/artifacts/sprint4-arm64-native-build` per the Sprint 4 Development Journal entry.

Claude cannot fabricate placeholder `.so` files here — a fake binary would either fail to load with
a misleading error, or (worse) risk being mistaken for something real. This directory intentionally
contains only this README until Ti copies the real files in.

## What must be copied here, exactly

Copy these 8 files from Ti's WSL build output into this exact folder
(`app/src/main/jniLibs/arm64-v8a/`), with these exact filenames:

- `libc++_shared.so`
- `libSDL2.so`
- `libSDL2_image.so`
- `libSDL2_sound.so`
- `libSDL2_ttf.so`
- `libopenal.so`
- `libruby.so`
- `libmkxp-z.so`

No other files. No `armeabi-v7a` folder — Sprint 7's approved scope is `arm64-v8a` only.

## Why this location, why no build.gradle change

`app/src/main/jniLibs/<ABI>/` is Android's standard convention for prebuilt native libraries —
Gradle (any AGP version) automatically packages everything found here into the APK for that ABI,
with no `externalNativeBuild`, no CMake, and no `ndk { abiFilters }` block required. The current
`app/build.gradle.kts` has no ABI-related configuration at all, and none is expected to be needed
just for a single `arm64-v8a/` folder to be picked up correctly.

## What this does NOT set up

Placing the files here does not load them, does not wire them into any part of the app, and does
not enable anything resembling a game launch. See `runtime/NativeRuntimeLoader.kt` and
`app/src/androidTest/.../NativeLibraryLoadSmokeTest.kt` for the one, deliberately narrow, test-only
mechanism this sprint adds to actually attempt loading these — nothing in the shipped app calls
`System.loadLibrary()` for any of these libraries outside that test.

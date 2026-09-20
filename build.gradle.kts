// Top-level build file. Per-module configuration lives in app/build.gradle.kts.
//
// VERSION MATRIX — Build-system Compatibility Fix (v0.0.1 patch)
// =================================================================
// Root cause of the v0.0.1 Gradle sync failure:
//   id("org.jetbrains.kotlin.plugin.compose") was declared at version 1.9.24.
//   That plugin (org.jetbrains.kotlin.plugin.compose) is the NEW Compose
//   Compiler Gradle Plugin introduced in Kotlin 2.0.0 and does NOT exist on
//   any repository for Kotlin < 2.0. Pairing it with Kotlin 1.9.24 caused an
//   immediate "plugin not found" error on every repository.
//
// Fix:
//   Upgrade Kotlin to 2.1.21 (latest stable in the 2.1.x line, widely tested
//   in production).  AGP 8.10.1 (not 9.x — see note) + Gradle 8.11.1.
//
// Why not AGP 9.x?
//   AGP 9.0 introduces built-in Kotlin support (org.jetbrains.kotlin.android
//   becomes optional), a new DSL, and drops several previously-supported APIs.
//   This is a breaking change release. For a sprint focused on build stability,
//   upgrading to a well-understood AGP 8.x is the right call. AGP 9.x migration
//   should be a deliberate, planned step — not bundled into a build-system fix.
//
// Confirmed compatible stack (from official docs and verified community usage):
//   Kotlin:           2.1.21
//   kotlin.plugin.compose: 2.1.21 (MUST match Kotlin version exactly)
//   AGP:              8.10.1
//   Gradle Wrapper:   8.11.1  (AGP 8.10 requires minimum Gradle 8.11)
//   Compose BOM:      2025.05.01 (latest stable BOM at Kotlin 2.1.x era)
//   compileSdk/targetSdk: 36 (unchanged — per Decision Journal)
//   minSdk:           26 (unchanged)
//   JVM target:       17 (unchanged — AGP 8.x requires JDK 17)
//
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    // Compose Compiler Gradle Plugin — exists ONLY from Kotlin 2.0.0 onward.
    // Must be the same version as the Kotlin plugin above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
}

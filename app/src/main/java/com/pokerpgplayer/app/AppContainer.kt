package com.pokerpgplayer.app

import android.content.Context
import com.pokerpgplayer.app.data.detection.GameDetectionService
import com.pokerpgplayer.app.data.repository.GameLibraryRepository
import com.pokerpgplayer.app.data.repository.JsonFileGameLibraryRepository
import com.pokerpgplayer.app.data.saf.SafAccessManager
import com.pokerpgplayer.app.runtime.AndroidRuntimeManager
import com.pokerpgplayer.app.runtime.RuntimeManager
import com.pokerpgplayer.app.runtime.RuntimeWorkspaceService
import com.pokerpgplayer.app.runtime.MirrorRuntimeWorkspaceService

/**
 * Sprint 2: a small manual composition root — no DI framework yet.
 *
 * Deliberate trade-off: Hilt (or Koin) would be a reasonable addition once
 * there are enough injected dependencies to justify the setup cost, but
 * adding a new Gradle plugin right after the Prototype v0.0.1 Gradle sync
 * incident is an avoidable risk for what is currently a small dependency
 * graph. Logged as a Brainstorm/Backlog idea for a later sprint rather
 * than done here — see the Sprint 2 OS update.
 *
 * Holds application context only (never an Activity context) to avoid any
 * leak risk, since this container's lifetime is the whole process.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val gameLibraryRepository: GameLibraryRepository =
        JsonFileGameLibraryRepository(appContext.filesDir)

    val gameDetectionService: GameDetectionService =
        GameDetectionService(appContext)

    val safAccessManager: SafAccessManager =
        SafAccessManager(appContext.contentResolver)

    // Sprint 6 (Runtime Workspace Mirror Prototype) / Sprint 53.2
    // (persistent workspace + single-flight): Bridge Option B (DEC-017) —
    // mirrors a SAF-selected folder into an app-private, reusable
    // workspace. Declared before runtimeManager since AndroidRuntimeManager
    // depends on it.
    val runtimeWorkspaceService: RuntimeWorkspaceService =
        MirrorRuntimeWorkspaceService(appContext)

    // Sprint 53 (Alpha Game Library + Add Game + Play): AndroidRuntimeManager
    // replaces StubRuntimeManager as the app's only RuntimeManager —
    // real launches now resolve a workspace via runtimeWorkspaceService,
    // refresh production runtime config on it, and start RuntimeActivity.
    val runtimeManager: RuntimeManager =
        AndroidRuntimeManager(appContext, runtimeWorkspaceService)
}

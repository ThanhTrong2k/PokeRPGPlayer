package com.pokerpgplayer.app.runtime

/**
 * Sprint 53.2 — explicit lifecycle state for one game's Runtime Workspace.
 *
 * Introduced because "the workspace directory exists on disk" was never a
 * safe proxy for "this workspace is complete and safe to launch" — the
 * real-device evidence behind this sprint (partial `runtime-workspace/<id>`
 * directories left on disk after an interrupted first Play) is exactly a
 * directory that existed without ever being [READY]. A workspace is only
 * ever considered [READY] once [MirrorRuntimeWorkspaceService] has
 * recorded it as such in the registry, immediately after a full,
 * validated copy has been promoted into its final location — never merely
 * because `File.exists()` is true.
 */
enum class WorkspaceState {
    /** No registry entry, or the recorded final workspace directory no longer exists on disk. */
    MISSING,

    /** A mirror is currently being staged/copied for this game. Set only while no valid READY workspace is being protected — see [com.pokerpgplayer.app.runtime.MirrorRuntimeWorkspaceService]'s own kdoc. */
    PREPARING,

    /** A complete, validated mirror exists at the recorded workspace path and is safe to launch from immediately, with no further copying. */
    READY,

    /** The most recent preparation attempt did not complete successfully. Never overwrites a previously [READY] record — see [com.pokerpgplayer.app.runtime.MirrorRuntimeWorkspaceService]'s promotion-safety logic. */
    FAILED
}

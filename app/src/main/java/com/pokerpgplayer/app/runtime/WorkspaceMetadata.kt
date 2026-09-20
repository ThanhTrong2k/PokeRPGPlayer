package com.pokerpgplayer.app.runtime

/**
 * What's known about one game's mirrored Runtime Workspace.
 *
 * Persisted in the small JSON registry [MirrorRuntimeWorkspaceService]
 * maintains — never inside the mirrored file tree itself (see that
 * class's kdoc for why: an extra file sitting among a game's own
 * Data/Graphics/Audio folders risks being mistaken for game content, and
 * directly complicates "preserve directory structure and file names
 * exactly").
 */
data class WorkspaceMetadata(
    val gameEntryId: String,
    val sourceUri: String,
    val workspacePath: String,
    /** ISO-8601. Set once, on the first successful mirror. */
    val createdAt: String,
    /** ISO-8601. Refreshed on every successful [RuntimeWorkspaceService.prepareWorkspace] call — this prototype always fully re-mirrors, so this is effectively "last mirrored at", not "last modified". */
    val updatedAt: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    /**
     * Sprint 53.2. Defaults to [WorkspaceState.READY] so every pre-53.2
     * call site/test that constructs a [WorkspaceMetadata] without this
     * parameter keeps its original meaning: before this sprint, a
     * [WorkspaceMetadata] record only ever existed after a (blocking,
     * full-remirror) successful [RuntimeWorkspaceService.prepareWorkspace]
     * call, i.e. it was always effectively READY.
     */
    val state: WorkspaceState = WorkspaceState.READY
)

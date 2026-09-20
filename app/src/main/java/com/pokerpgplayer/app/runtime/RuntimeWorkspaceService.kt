package com.pokerpgplayer.app.runtime

/**
 * Prepares an app-private, fully-mirrored copy of a SAF-selected game
 * folder — the approved SAF-to-Runtime Bridge Strategy Option B (DEC-017).
 * Interface, not a concrete class, on purpose: this is precisely the seam
 * Runtime Integration Plan v2.0 anticipates replacing with a custom
 * PhysFS/ContentResolver bridge (Option D) later, and this codebase's own
 * established pattern (see [com.pokerpgplayer.app.data.repository.GameLibraryRepository],
 * [RuntimeManager]) is interface-first so that swap costs nothing at every
 * call site when it eventually happens.
 *
 * The original SAF-selected folder is never written to by any
 * implementation of this interface — every operation against it must be
 * read-only. The workspace this produces is app-private, disposable, and
 * never authoritative: [com.pokerpgplayer.app.data.repository.GameLibraryRepository]
 * remains the only real record of what's in the user's library.
 *
 * Consumed by [RuntimeLaunchPreparer] since Sprint 53 — every real Play
 * attempt resolves a workspace through this interface before a launch is
 * ever attempted.
 *
 * **Sprint 53.2 — persistent workspace, not full-remirror-per-call.** The
 * "always fully deletes and re-mirrors on every call" policy this
 * interface originally documented (Sprint 6 §2.5) is exactly the real-
 * device bug this sprint fixes: every Play recopying a multi-hundred-MB
 * or multi-GB game is not viable for Alpha, and a full delete-then-mkdir
 * policy left partial workspaces on disk when a copy was interrupted. See
 * [MirrorRuntimeWorkspaceService]'s own kdoc for the staging/promotion/
 * single-flight design that replaces it. From this interface's point of
 * view, the only externally-visible contract change is: once a workspace
 * has been prepared successfully, a later [prepareWorkspace] call for the
 * same [gameEntryId] and [folderUri] is expected to reuse it rather than
 * re-copy — callers should not assume every call performs real I/O
 * proportional to the game's size.
 */
interface RuntimeWorkspaceService {

    /**
     * Resolves an app-private, ready-to-launch workspace mirrored from the
     * SAF folder at [folderUri], keyed by [gameEntryId]. If a valid,
     * complete ([WorkspaceState.READY]) workspace already exists for this
     * game, it is reused immediately with no copying. Otherwise, the
     * source is mirrored into a private staging area and promoted into
     * place only once the copy is complete and validated — a caller can
     * never observe a partially-copied workspace as a success.
     *
     * Safe to call concurrently for the same [gameEntryId] from multiple
     * callers: implementations guarantee at most one physical copy is
     * ever in flight per game at a time, and every concurrent caller
     * resolves to the same outcome.
     *
     * `CancellationException` is never converted into a [WorkspaceResult] —
     * it propagates normally if the calling coroutine is cancelled. See
     * [WorkspaceError]'s kdoc for why.
     */
    suspend fun prepareWorkspace(gameEntryId: String, folderUri: String): WorkspaceResult

    /** The last-recorded mirror metadata for this game, or null if none exists. */
    suspend fun getWorkspaceMetadata(gameEntryId: String): WorkspaceMetadata?

    /**
     * Deletes this game's workspace, if any, and its registry entry.
     * Path-safe by construction (see [WorkspacePathResolver.isSafeToDeleteAsWorkspace]) —
     * this can only ever delete `<filesDir>/runtime-workspace/<gameEntryId>/`,
     * never an arbitrary path. Returns true if, after this call, no
     * workspace exists for this game (whether one existed before or not).
     */
    suspend fun clearWorkspace(gameEntryId: String): Boolean
}

package com.pokerpgplayer.app.runtime

/**
 * Why [RuntimeWorkspaceService.prepareWorkspace] didn't succeed.
 *
 * Deliberately several distinct, narrow cases instead of one catch-all —
 * an earlier draft of this design used a single vague `UnsupportedFolder`
 * for several genuinely different conditions; that was rejected in review
 * specifically because a diagnostic that can't tell "the folder doesn't
 * exist" from "one file inside it couldn't be copied" isn't much of a
 * diagnostic. See "diagnostics should explain problems, not hide them."
 *
 * No `Cancelled` case here, on purpose. Coroutine `CancellationException`
 * must propagate normally — never be caught and converted into a
 * [WorkspaceResult.Failed] — since swallowing it here would break
 * structured concurrency for whatever cancelled this operation in the
 * first place. [MirrorRuntimeWorkspaceService] cleans up any partial
 * workspace in a `finally` block regardless of *how* the operation ended
 * (success, a real error, or cancellation), so a cancelled mirror is never
 * left looking like a valid one — that's handled structurally, not via an
 * error value.
 */
sealed class WorkspaceError {
    /** The source root couldn't be opened at all, or isn't a directory — [androidx.documentfile.provider.DocumentFile.fromTreeUri] returned null, or the result isn't [androidx.documentfile.provider.DocumentFile.isDirectory]. */
    data object InvalidSourceFolder : WorkspaceError()

    /** A SAF permission grant was denied or revoked — distinct from [InvalidSourceFolder] because the folder itself may be perfectly fine; this app's access to it is the problem. */
    data object SafAccessLost : WorkspaceError()

    /**
     * A document inside the tree is neither a plain file nor a directory
     * as far as this service can tell (e.g. a virtual document requiring
     * conversion) and can't be mirrored as-is. Carries the relative path
     * where this was found, same diagnostic reasoning as [CopyFailure].
     */
    data class UnsupportedDocumentType(val relativePath: String) : WorkspaceError()

    /** One specific file failed to copy. Never silently skipped — this is always why the whole operation stopped, not a count of how many were skipped (no `skippedCount` in this prototype). */
    data class CopyFailure(val relativePath: String, val message: String) : WorkspaceError()

    /**
     * Ran out of storage — either the best-effort pre-flight estimate
     * flagged it before starting, or (more reliably, since SAF-reported
     * file sizes can be missing or wrong) an IOException during the
     * actual copy matched an out-of-space condition.
     */
    data object InsufficientStorage : WorkspaceError()

    /** Escape hatch for anything unanticipated — same defensive pattern already used by [com.pokerpgplayer.app.data.detection.GameDetectionService]. */
    data class Unknown(val message: String) : WorkspaceError()
}

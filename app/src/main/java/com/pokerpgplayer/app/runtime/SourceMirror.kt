package com.pokerpgplayer.app.runtime

import java.io.File

/**
 * Sprint 53.2 — the SAF-specific half of workspace preparation, extracted
 * behind this interface for exactly one reason: the staging/promotion/
 * single-flight lifecycle in [MirrorRuntimeWorkspaceService] is the actual
 * subject of this sprint's bug fix, and that lifecycle logic has zero
 * dependency on Android, SAF, or [android.content.Context] once "copy the
 * source into a destination directory" is pulled out behind this seam.
 * That split is what makes the lifecycle logic unit-testable in a plain
 * JVM test (this project's `build.gradle.kts` has no Robolectric/Mockito
 * — see [MirrorRuntimeWorkspaceService]'s own kdoc) with a fake
 * [SourceMirror], while [RealSafSourceMirror] remains the one place that
 * ever touches [androidx.documentfile.provider.DocumentFile] or
 * [android.content.ContentResolver].
 */
internal interface SourceMirror {
    /**
     * Cheap, non-recursive check that [folderUri] currently refers to a
     * readable directory. Does not copy anything and does not guarantee
     * every file inside is readable — that is only discovered during
     * [mirrorInto], same as the original Sprint 6 design.
     */
    fun validateSource(folderUri: String): Boolean

    /**
     * Copies every file from the source at [folderUri] into [destDir],
     * preserving directory structure and file names exactly. [destDir]
     * is guaranteed to already exist and be empty when this is called —
     * implementations must not delete or replace it, only populate it.
     * [onFileCopied] is invoked after each file with the running total
     * file count and byte count, purely for progress/metadata bookkeeping
     * by the caller.
     *
     * Never touches the source for anything other than reading. Throws
     * [WorkspaceMirrorException] (carrying a structured [WorkspaceError])
     * on any failure; [kotlinx.coroutines.CancellationException] is
     * allowed to propagate uncaught.
     */
    fun mirrorInto(folderUri: String, destDir: File, onFileCopied: (fileCount: Int, totalBytes: Long) -> Unit)
}

/**
 * Carries a structured [WorkspaceError] out of a [SourceMirror]
 * implementation without it being flattened into a generic exception
 * message — mirrors the original Sprint 6 `MirrorAbortException` pattern,
 * now scoped to the [SourceMirror] boundary specifically.
 */
internal class WorkspaceMirrorException(val error: WorkspaceError) : Exception()

package com.pokerpgplayer.app.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.File

/**
 * The real, SAF-backed [SourceMirror] — moved unchanged (in behavior)
 * from the original Sprint 6 `MirrorRuntimeWorkspaceService`, only
 * relocated behind the [SourceMirror] seam introduced in Sprint 53.2. The
 * original SAF folder is only ever read from — [ContentResolver.openInputStream]
 * remains the only operation this class performs against it.
 */
internal class RealSafSourceMirror(private val appContext: Context) : SourceMirror {

    private val contentResolver: ContentResolver get() = appContext.contentResolver

    private class CopyProgress {
        var fileCount: Int = 0
        var totalSizeBytes: Long = 0L
    }

    override fun validateSource(folderUri: String): Boolean {
        val sourceRoot = runCatching { DocumentFile.fromTreeUri(appContext, Uri.parse(folderUri)) }.getOrNull()
        return sourceRoot != null && sourceRoot.isDirectory
    }

    override fun mirrorInto(folderUri: String, destDir: File, onFileCopied: (fileCount: Int, totalBytes: Long) -> Unit) {
        val sourceRoot = runCatching { DocumentFile.fromTreeUri(appContext, Uri.parse(folderUri)) }.getOrNull()
        if (sourceRoot == null || !sourceRoot.isDirectory) {
            throw WorkspaceMirrorException(WorkspaceError.InvalidSourceFolder)
        }

        // Best-effort pre-flight only, unchanged from the original Sprint 6
        // design — SAF-reported file sizes can be missing or wrong, so
        // this can only ever fail fast in the obvious case; it never
        // blocks a copy just because sizes were unavailable, and the
        // copy-time catch in copyOneFile is what actually catches real
        // out-of-space conditions.
        preflightCheckBestEffort(sourceRoot, destDir)?.let { throw WorkspaceMirrorException(it) }

        val progress = CopyProgress()
        mirrorDirectory(sourceRoot, destDir, relativePath = "", progress = progress, onFileCopied = onFileCopied)
    }

    private fun preflightCheckBestEffort(sourceRoot: DocumentFile, destDir: File): WorkspaceError? {
        // Root-level only, not recursive — see the original Sprint 6
        // rationale: a full recursive size sum means walking the whole
        // SAF tree twice, which is exactly the unbounded repeated SAF
        // traversal cost the App v0.0.3 ANR fix exists to avoid.
        val rootLevelChildren = runCatching { sourceRoot.listFiles() }.getOrNull() ?: return null
        var estimatedBytes = 0L
        var anyReliableSize = false
        for (child in rootLevelChildren) {
            if (child.isFile) {
                val length = child.length()
                if (length > 0) {
                    estimatedBytes += length
                    anyReliableSize = true
                }
            }
        }
        if (!anyReliableSize) return null

        val usable = runCatching { destDir.parentFile?.usableSpace ?: appContext.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        return if (WorkspacePathResolver.hasEnoughSpace(estimatedBytes, usable)) null else WorkspaceError.InsufficientStorage
    }

    // ---- Recursive mirror (unchanged from Sprint 6/53 behavior) ----

    private fun mirrorDirectory(
        source: DocumentFile,
        destDir: File,
        relativePath: String,
        progress: CopyProgress,
        onFileCopied: (fileCount: Int, totalBytes: Long) -> Unit
    ) {
        val children = try {
            source.listFiles()
        } catch (e: SecurityException) {
            throw WorkspaceMirrorException(WorkspaceError.SafAccessLost)
        }
        for (child in children) {
            val name = child.name
            if (name == null || !WorkspacePathResolver.isSafeDocumentName(name)) {
                // Never silently skipped, never renamed — see
                // WorkspacePathResolver.isSafeDocumentName's own kdoc.
                val shownName = name ?: "<no name>"
                val diagnosticPath = if (relativePath.isEmpty()) shownName else "$relativePath/$shownName"
                throw WorkspaceMirrorException(
                    WorkspaceError.CopyFailure(diagnosticPath, "Document has a missing or unsafe name (\"$shownName\") and cannot be mirrored exactly and safely")
                )
            }
            val childRelativePath = if (relativePath.isEmpty()) name else "$relativePath/$name"
            when {
                child.isDirectory -> {
                    val childDest = File(destDir, name)
                    if (!childDest.exists() && !childDest.mkdirs()) {
                        throw WorkspaceMirrorException(WorkspaceError.CopyFailure(childRelativePath, "Could not create directory"))
                    }
                    mirrorDirectory(child, childDest, childRelativePath, progress, onFileCopied)
                }
                child.isFile -> {
                    copyOneFile(child, File(destDir, name), childRelativePath, progress)
                    onFileCopied(progress.fileCount, progress.totalSizeBytes)
                }
                else -> {
                    throw WorkspaceMirrorException(WorkspaceError.UnsupportedDocumentType(childRelativePath))
                }
            }
        }
    }

    private fun copyOneFile(source: DocumentFile, destFile: File, relativePath: String, progress: CopyProgress) {
        try {
            val input = contentResolver.openInputStream(source.uri)
                ?: throw WorkspaceMirrorException(WorkspaceError.CopyFailure(relativePath, "Could not open source file for reading"))
            var bytesWritten = 0L
            input.use { stream ->
                destFile.outputStream().use { out ->
                    bytesWritten = stream.copyTo(out)
                }
            }
            progress.fileCount += 1
            progress.totalSizeBytes += bytesWritten
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (looksLikeOutOfSpace(e, destFile)) {
                throw WorkspaceMirrorException(WorkspaceError.InsufficientStorage)
            }
            throw WorkspaceMirrorException(WorkspaceError.CopyFailure(relativePath, e.message ?: e.toString()))
        } catch (e: SecurityException) {
            throw WorkspaceMirrorException(WorkspaceError.SafAccessLost)
        }
    }

    /**
     * Heuristic only, explicitly not authoritative — same reasoning as the
     * original Sprint 6 design: checks the exception's own message for the
     * common Linux/Android "no space left" signal, and separately checks
     * whether the destination volume's free space is now essentially zero.
     */
    private fun looksLikeOutOfSpace(e: IOException, destFile: File): Boolean {
        if (WorkspacePathResolver.messageIndicatesOutOfSpace(e.message)) return true
        return runCatching { destFile.parentFile?.usableSpace ?: appContext.filesDir.usableSpace }
            .getOrDefault(Long.MAX_VALUE) < LOW_SPACE_THRESHOLD_BYTES
    }

    companion object {
        private const val LOW_SPACE_THRESHOLD_BYTES = 4L * 1024 * 1024 // 4 MiB — deliberately small; last-resort signal, not a safety margin.
    }
}

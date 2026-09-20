package com.pokerpgplayer.app.data.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps the single Android-framework call needed to make a SAF grant
 * survive an app restart. Kept as its own tiny class — not folded into
 * the repository or a ViewModel — so "persist this URI permission" has
 * exactly one place it's expressed, and so the repository layer stays
 * free of ContentResolver/Uri framework types (it only ever sees Strings).
 *
 * Note: Prototype v0.0.1's SAF picker deliberately did NOT call this —
 * it was a permission *request* with no follow-through, per that
 * prototype's explicit scope. Sprint 2 is the real Game Library, so
 * persisting the grant is now required; this class is the one place that
 * change lives.
 *
 * Made `suspend` + internally IO-dispatched as part of the App v0.0.2 ANR
 * fix, matching [com.pokerpgplayer.app.data.detection.GameDetectionService.detect]
 * and [com.pokerpgplayer.app.data.repository.JsonFileGameLibraryRepository] —
 * a Binder call to the system isn't guaranteed instant, and the project's
 * own convention is now that I/O-touching classes own their own dispatch
 * rather than relying on every caller to remember it.
 */
class SafAccessManager(private val contentResolver: ContentResolver) {

    /**
     * Returns true if the permission was successfully persisted. A false
     * result is not fatal — the caller should still add the game, just
     * with a warning that folder access may not survive an app restart on
     * this device/provider.
     */
    suspend fun takePersistableAccess(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        }.getOrElse { false }
    }
}

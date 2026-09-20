package com.pokerpgplayer.app.data.repository

import com.pokerpgplayer.app.data.model.GameEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * Storage boundary for the game library. UI and ViewModels depend on this
 * interface only — never on [JsonFileGameLibraryRepository] directly — so
 * swapping the backing store (e.g. to Room/SQLite, once the library is
 * large enough to need real queries instead of "load everything into
 * memory") means writing a new implementation of this interface, not
 * touching every call site that reads the library.
 */
interface GameLibraryRepository {
    /** Current library contents. Starts empty and updates once the initial load completes. */
    val games: StateFlow<List<GameEntry>>

    suspend fun addGame(entry: GameEntry)

    /** Removes the library entry only. Never touches the original game folder/files. */
    suspend fun removeGame(id: String)

    /** Full replace of one entry — used for rename, favorite toggle, and executable selection. */
    suspend fun updateGame(entry: GameEntry)

    /** Synchronous read of the current in-memory state — no IO. */
    fun getGame(id: String): GameEntry?
}

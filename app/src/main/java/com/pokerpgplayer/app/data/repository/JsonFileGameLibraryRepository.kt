package com.pokerpgplayer.app.data.repository

import com.pokerpgplayer.app.data.model.DetectedSignal
import com.pokerpgplayer.app.data.model.DetectionStatus
import com.pokerpgplayer.app.data.model.GameDetectionResult
import com.pokerpgplayer.app.data.model.GameEntry
import com.pokerpgplayer.app.data.model.MitigationAudit
import com.pokerpgplayer.app.data.model.MitigationOverride
import com.pokerpgplayer.app.data.model.OverlayStatus
import com.pokerpgplayer.app.data.model.OverrideMode
import com.pokerpgplayer.app.data.model.RuntimeConfigProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed [GameLibraryRepository]. Stores the whole library as one
 * JSON file in app-internal storage (`filesDir/game_library.json`) —
 * appropriate at this sprint's expected library size (a handful to a few
 * dozen games). If that assumption stops holding, migrate to Room behind
 * the same [GameLibraryRepository] interface rather than growing this
 * class further.
 *
 * Uses `org.json` (built into the Android SDK) rather than adding
 * kotlinx.serialization this sprint — a deliberate choice to avoid
 * introducing a new Gradle plugin / version-matrix dependency right after
 * the Prototype v0.0.1 Gradle sync incident. Trade-off, stated plainly:
 * `org.json`'s Android implementation is a stub outside a real Android
 * runtime, so the JSON (de)serialization in this file cannot be unit
 * tested on a plain JVM without Robolectric or an instrumented test.
 * Revisit if/when kotlinx.serialization is adopted elsewhere anyway.
 *
 * `schemaVersion` is written on every save so a future format change has
 * an actual version number to branch on, even though no migration exists
 * yet — same shape as PokeRPG Player OS's own SCHEMA_VERSION.
 *
 * Initial load happens once, asynchronously, in an internal coroutine
 * scope owned by this repository (not exposed as a public `load()` on the
 * interface) — keeps [GameLibraryRepository] a pure read/write contract
 * that a future Room-backed implementation wouldn't need to change at all.
 */
class JsonFileGameLibraryRepository(
    private val storageDir: File
) : GameLibraryRepository {

    private val file = File(storageDir, "game_library.json")
    private val tempFile = File(storageDir, "game_library.json.tmp")
    private val mutex = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _games = MutableStateFlow<List<GameEntry>>(emptyList())
    override val games: StateFlow<List<GameEntry>> = _games.asStateFlow()

    companion object {
        private const val SCHEMA_VERSION = 1
    }

    init {
        repositoryScope.launch {
            mutex.withLock {
                _games.value = readFromDisk()
            }
        }
    }

    override suspend fun addGame(entry: GameEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _games.value + entry
            writeToDisk(updated)
            _games.value = updated
        }
    }

    override suspend fun removeGame(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _games.value.filterNot { it.id == id }
            writeToDisk(updated)
            _games.value = updated
        }
    }

    override suspend fun updateGame(entry: GameEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _games.value.map { if (it.id == entry.id) entry else it }
            writeToDisk(updated)
            _games.value = updated
        }
    }

    override fun getGame(id: String): GameEntry? = _games.value.firstOrNull { it.id == id }

    private fun readFromDisk(): List<GameEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("games") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                runCatching { array.getJSONObject(i).toGameEntry() }.getOrNull()
            }
        } catch (e: Exception) {
            // Corrupted or unreadable file — degrade to an empty library
            // rather than crash the app on startup. The file itself is
            // left on disk untouched in case manual recovery is ever needed.
            emptyList()
        }
    }

    private fun writeToDisk(entries: List<GameEntry>) {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        root.put("games", array)

        // Write to a temp file and rename over the real one — avoids a
        // half-written, corrupted library file if the process dies mid-write.
        tempFile.writeText(root.toString())
        tempFile.renameTo(file)
    }
}

private fun GameEntry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("displayName", displayName)
    put("folderUri", folderUri)
    put("addedDate", addedDate)
    put("isFavorite", isFavorite)
    put("selectedExecutable", selectedExecutable)
    put("libraryMetadataUpdatedDate", libraryMetadataUpdatedDate)
    put("detection", detection.toJson())
    put("runtimeConfigProfile", runtimeConfigProfile.toJson())
}

private fun JSONObject.toGameEntry(): GameEntry = GameEntry(
    id = getString("id"),
    displayName = getString("displayName"),
    folderUri = getString("folderUri"),
    addedDate = getString("addedDate"),
    isFavorite = optBoolean("isFavorite", false),
    selectedExecutable = optStringOrNull("selectedExecutable"),
    libraryMetadataUpdatedDate = optStringOrNull("libraryMetadataUpdatedDate"),
    detection = getJSONObject("detection").toGameDetectionResult(),
    // Migration safety: entries saved before Sprint 24 (Stage 1) have no
    // "runtimeConfigProfile" key at all. optJSONObject() returns null in
    // that case rather than throwing, so pre-existing library entries
    // deserialize cleanly into the same safe, empty default every new
    // GameEntry already gets — never dropped by readFromDisk()'s own
    // per-entry runCatching{}.getOrNull() wrapper.
    runtimeConfigProfile = optJSONObject("runtimeConfigProfile")?.toRuntimeConfigProfile()
        ?: RuntimeConfigProfile()
)

private fun GameDetectionResult.toJson(): JSONObject = JSONObject().apply {
    put("status", status.name)
    put("gameIniExists", gameIniExists)
    put("gameSectionFound", gameSectionFound)
    put("detectedTitle", detectedTitle)
    put("libraryDll", libraryDll)
    put("scriptsPath", scriptsPath)
    put("scriptsExists", scriptsExists)
    put("dllExists", dllExists)
    put("dataFolderExists", dataFolderExists)
    put("graphicsFolderExists", graphicsFolderExists)
    put("audioFolderExists", audioFolderExists)
    put("fontsDetected", fontsDetected)
    put("pluginsDetected", pluginsDetected)
    put("pbsDetected", pbsDetected)
    put("executableCandidates", JSONArray(executableCandidates))
    put("selectedExecutable", selectedExecutable)
    put("missingItems", JSONArray(missingItems))
    put("warnings", JSONArray(warnings))
}

private fun JSONObject.toGameDetectionResult(): GameDetectionResult = GameDetectionResult(
    status = DetectionStatus.valueOf(getString("status")),
    gameIniExists = optBoolean("gameIniExists", false),
    gameSectionFound = optBoolean("gameSectionFound", false),
    detectedTitle = optStringOrNull("detectedTitle"),
    libraryDll = optStringOrNull("libraryDll"),
    scriptsPath = optStringOrNull("scriptsPath"),
    scriptsExists = optBoolean("scriptsExists", false),
    dllExists = optBoolean("dllExists", false),
    dataFolderExists = optBoolean("dataFolderExists", false),
    graphicsFolderExists = optBoolean("graphicsFolderExists", false),
    audioFolderExists = optBoolean("audioFolderExists", false),
    fontsDetected = optBoolean("fontsDetected", false),
    pluginsDetected = optBoolean("pluginsDetected", false),
    pbsDetected = optBoolean("pbsDetected", false),
    executableCandidates = getJSONArray("executableCandidates").toStringList(),
    selectedExecutable = optStringOrNull("selectedExecutable"),
    missingItems = getJSONArray("missingItems").toStringList(),
    warnings = getJSONArray("warnings").toStringList()
)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }

// --- Sprint 24, Stage 1: RuntimeConfigProfile (schema only, see that
// type's own kdoc). Every reader below is defensive/opt-based, even
// though this version of the code always writes every field, matching
// this file's own established style for GameDetectionResult above and
// keeping this nested structure safe to extend later without a second
// migration pass. ---

private fun RuntimeConfigProfile.toJson(): JSONObject = JSONObject().apply {
    put("detectedSignals", JSONArray(detectedSignals.map { it.toJson() }))
    put("recommendedMitigations", JSONArray(recommendedMitigations))
    put("enabledMitigations", JSONArray(enabledMitigations))
    put("disabledMitigations", JSONArray(disabledMitigations))
    put("overrides", overrides.toJson())
    put("mitigationAudit", mitigationAudit.toJson())
    put("overlayStatus", overlayStatus.name)
}

private fun JSONObject.toRuntimeConfigProfile(): RuntimeConfigProfile = RuntimeConfigProfile(
    detectedSignals = optJSONArray("detectedSignals")?.let { array ->
        (0 until array.length()).map { array.getJSONObject(it).toDetectedSignal() }
    } ?: emptyList(),
    recommendedMitigations = optJSONArray("recommendedMitigations")?.toStringList() ?: emptyList(),
    enabledMitigations = optJSONArray("enabledMitigations")?.toStringList() ?: emptyList(),
    disabledMitigations = optJSONArray("disabledMitigations")?.toStringList() ?: emptyList(),
    overrides = optJSONObject("overrides")?.toMitigationOverride() ?: MitigationOverride(),
    mitigationAudit = optJSONObject("mitigationAudit")?.toMitigationAudit() ?: MitigationAudit(),
    overlayStatus = optStringOrNull("overlayStatus")?.let {
        runCatching { OverlayStatus.valueOf(it) }.getOrNull()
    } ?: OverlayStatus.NOT_GENERATED
)

private fun DetectedSignal.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("source", source)
    put("confidence", confidence)
    put("reason", reason)
}

private fun JSONObject.toDetectedSignal(): DetectedSignal = DetectedSignal(
    id = getString("id"),
    source = optStringOrNull("source") ?: "",
    confidence = optStringOrNull("confidence") ?: "",
    reason = optStringOrNull("reason") ?: ""
)

private fun MitigationOverride.toJson(): JSONObject = JSONObject().apply {
    put("mode", mode.name)
    put("notes", notes)
}

private fun JSONObject.toMitigationOverride(): MitigationOverride = MitigationOverride(
    mode = optStringOrNull("mode")?.let {
        runCatching { OverrideMode.valueOf(it) }.getOrNull()
    } ?: OverrideMode.AUTO,
    notes = optStringOrNull("notes") ?: ""
)

private fun MitigationAudit.toJson(): JSONObject = JSONObject().apply {
    put("originalConfigHash", originalConfigHash)
    put("overlayConfigHash", overlayConfigHash)
    put("lastAppliedMitigations", JSONArray(lastAppliedMitigations))
    put("lastReason", lastReason)
    put("lastGeneratedAt", lastGeneratedAt)
}

private fun JSONObject.toMitigationAudit(): MitigationAudit = MitigationAudit(
    originalConfigHash = optStringOrNull("originalConfigHash"),
    overlayConfigHash = optStringOrNull("overlayConfigHash"),
    lastAppliedMitigations = optJSONArray("lastAppliedMitigations")?.toStringList() ?: emptyList(),
    lastReason = optStringOrNull("lastReason"),
    lastGeneratedAt = optStringOrNull("lastGeneratedAt")
)

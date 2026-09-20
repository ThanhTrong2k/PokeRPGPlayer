package com.pokerpgplayer.app.data.detection

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pokerpgplayer.app.data.model.DetectionStatus
import com.pokerpgplayer.app.data.model.GameDetectionResult
import com.pokerpgplayer.app.data.model.GameEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only, root-folder-scoped scan of a selected game folder.
 *
 * This is deliberately a "basic detection" pass — Game.ini field reading,
 * shallow file/folder presence checks, and root-level executable
 * scanning. It is NOT the deeper, version-aware analysis described in
 * Game Scanner Technical Specification v1.1 (Essentials version-era
 * signal, RGSS script-layout detection, save storage strategy
 * classification, PBS multi-file awareness) — that remains a distinct,
 * future module. See the Technical Note logged to PokeRPG Player OS
 * alongside this sprint for the explicit boundary between the two.
 *
 * Never writes to the scanned folder, never renames/moves/deletes
 * anything, and never executes any script or plugin content — read-only
 * analysis only, per the project's Save System Policy applied here to
 * folder scanning generally.
 *
 * Holds a [Context] (application context — safe to keep long-term, no
 * Activity reference) rather than just a [ContentResolver], because
 * [DocumentFile.fromTreeUri] requires one.
 *
 * ANR fix (App v0.0.2 bug report): [detect] is a `suspend` function that
 * internally dispatches its entire blocking body to [Dispatchers.IO] —
 * matching the pattern [com.pokerpgplayer.app.data.repository.JsonFileGameLibraryRepository]
 * already used. Previously `detect()` was a plain synchronous function,
 * and its one and only caller ([com.pokerpgplayer.app.viewmodel.HomeViewModel])
 * invoked it directly inside `viewModelScope.launch { }` — whose default
 * dispatcher is `Dispatchers.Main.immediate` — so every blocking SAF call
 * inside `detect()` (DocumentFile.fromTreeUri, listFiles(), openInputStream+
 * readBytes) ran synchronously on the main thread. Any folder whose
 * combined SAF I/O took longer than Android's ~5s input-dispatch timeout
 * produced an ANR (matching Ti's log finding: ANR, not FATAL EXCEPTION —
 * the signature of a blocked main thread, not a crash). Making the
 * dispatch-to-background the service's own responsibility (not something
 * every call site has to remember) prevents this exact class of bug from
 * recurring if another call site is added later.
 */
class GameDetectionService(private val appContext: Context) {

    suspend fun detect(rootUri: Uri): GameDetectionResult = withContext(Dispatchers.IO) {
        try {
            detectBlocking(rootUri)
        } catch (e: Exception) {
            // Defense-in-depth per the bug report's explicit requirement:
            // "All detection errors must be caught and converted into
            // DetectionResult warnings or Low/Needs Review status, never
            // app crashes or ANR." Not the currently-observed failure mode
            // (which was an ANR, not a crash) but a real gap this closes —
            // e.g. a SecurityException if SAF access is revoked mid-scan.
            errorResult(e.message)
        }
    }

    private fun detectBlocking(rootUri: Uri): GameDetectionResult {
        val root = DocumentFile.fromTreeUri(appContext, rootUri)
        if (root == null || !root.isDirectory) {
            return unreadableFolderResult()
        }

        val rawChildren = root.listFiles()

        // Safety cap per "All DocumentFile operations must be bounded and
        // safe": a real RPGXP/Essentials project root almost always has
        // well under 20 top-level entries (Data/, Graphics/, Audio/,
        // Game.ini, Game.exe, optionally Fonts/Plugins/PBS/a readme). A
        // folder with an implausibly large number of top-level entries is
        // far more likely to be the wrong folder entirely (e.g. a device's
        // whole internal storage or SD card root picked by mistake) than a
        // genuine, if heavy, game folder — heavy games are heavy *inside*
        // Data/Graphics/Audio, not at the root. Bounding here avoids ever
        // materializing and repeatedly scanning an unbounded list.
        if (rawChildren.size > MAX_ROOT_ENTRIES) {
            return tooManyRootEntriesResult(rawChildren.size)
        }

        val children = rawChildren.toList()

        val gameIniFile = children.findFileIgnoreCase("Game.ini")
        val gameIniText = gameIniFile?.readTextOrNull(appContext.contentResolver)
        val iniData = gameIniText?.let(::parseGameIni)

        val dataFolder = children.findFolderIgnoreCase("Data")
        val graphicsFolder = children.findFolderIgnoreCase("Graphics")
        val audioFolder = children.findFolderIgnoreCase("Audio")
        val pluginsFolder = children.findFolderIgnoreCase("Plugins")
        val pbsFolder = children.findFolderIgnoreCase("PBS")
        val fontsFolder = children.findFolderIgnoreCase("Fonts")
        val rootFontFiles = children.any {
            it.isFile && (it.name?.endsWith(".ttf", ignoreCase = true) == true ||
                it.name?.endsWith(".otf", ignoreCase = true) == true)
        }

        val libraryDll = iniData?.library
        val dllExists = libraryDll != null && children.findFileIgnoreCase(libraryDll) != null

        val scriptsPath = iniData?.scripts?.replace('\\', '/')
        val scriptsExists = iniData?.scripts?.let { resolveScriptsFileShallow(dataFolder, it) } ?: false

        val executableCandidates = children
            .filter { it.isFile && it.name?.endsWith(".exe", ignoreCase = true) == true }
            .mapNotNull { it.name }
            .sorted()
        val defaultExecutable = ExecutableDetector.selectDefault(executableCandidates)

        val gameIniExists = gameIniFile != null
        val gameSectionFound = iniData?.gameSectionFound == true
        val looksLikeRpgxpProject = gameIniExists || dataFolder != null

        val missingItems = buildList {
            if (!gameIniExists) add("Game.ini")
            if (gameIniExists && !gameSectionFound) add("[Game] section")
            if (iniData?.scripts.isNullOrBlank()) add("Scripts value") else if (!scriptsExists) add("Scripts file")
            if (iniData?.library.isNullOrBlank()) add("Library value") else if (!dllExists) add("Library DLL")
            if (dataFolder == null) add("Data folder")
            if (graphicsFolder == null) add("Graphics folder")
            if (audioFolder == null) add("Audio folder")
            if (executableCandidates.isEmpty()) add("Executable")
        }

        val warnings = buildList {
            if (gameIniExists && iniData == null) add("Game.ini could not be read.")
            if (gameIniExists && !gameSectionFound) add("Game.ini does not contain a [Game] section.")
            if (executableCandidates.size > 1 && defaultExecutable == null) {
                add("Multiple .exe files found — choose the correct one.")
            }
            if (!gameIniExists && dataFolder == null) {
                add("This folder does not look like a Pokémon RPGXP / RPG Maker XP project folder.")
            }
            // Bug report requirement: "Missing Library DLL / suspected RTP
            // dependency must not crash or freeze the app. Missing RTP
            // should become a warning or Needs Review status only." The
            // status side of this was already correct (see computeStatus —
            // a missing DLL alone only prevents HIGH_CONFIDENCE, it was
            // never part of the LOW_CONFIDENCE gate, so it never blocked or
            // crashed anything). What was missing was a clear, specific
            // warning explaining *why* — diagnostics should explain
            // problems, not just silently omit a field.
            if (!iniData?.library.isNullOrBlank() && !dllExists) {
                add("Library DLL (\"${iniData?.library}\") not found in this folder. This game may require the RPG Maker XP Runtime Package (RTP) on the original platform. Adding it is still allowed — this is informational only.")
            }
        }

        val status = computeStatus(
            gameIniExists = gameIniExists,
            gameSectionFound = gameSectionFound,
            scriptsDeclared = !iniData?.scripts.isNullOrBlank(),
            scriptsExists = scriptsExists,
            libraryDeclared = !iniData?.library.isNullOrBlank(),
            dllExists = dllExists,
            dataFolderExists = dataFolder != null,
            graphicsFolderExists = graphicsFolder != null,
            audioFolderExists = audioFolder != null,
            executableCandidateCount = executableCandidates.size,
            selectedExecutable = defaultExecutable,
            looksLikeRpgxpProject = looksLikeRpgxpProject
        )

        return GameDetectionResult(
            status = status,
            gameIniExists = gameIniExists,
            gameSectionFound = gameSectionFound,
            detectedTitle = iniData?.title,
            libraryDll = libraryDll,
            scriptsPath = scriptsPath,
            scriptsExists = scriptsExists,
            dllExists = dllExists,
            dataFolderExists = dataFolder != null,
            graphicsFolderExists = graphicsFolder != null,
            audioFolderExists = audioFolder != null,
            fontsDetected = fontsFolder != null || rootFontFiles,
            pluginsDetected = pluginsFolder != null,
            pbsDetected = pbsFolder != null,
            executableCandidates = executableCandidates,
            selectedExecutable = defaultExecutable,
            missingItems = missingItems,
            warnings = warnings
        )
    }

    private fun unreadableFolderResult(): GameDetectionResult = GameDetectionResult(
        status = DetectionStatus.LOW_CONFIDENCE,
        gameIniExists = false,
        gameSectionFound = false,
        detectedTitle = null,
        libraryDll = null,
        scriptsPath = null,
        scriptsExists = false,
        dllExists = false,
        dataFolderExists = false,
        graphicsFolderExists = false,
        audioFolderExists = false,
        fontsDetected = false,
        pluginsDetected = false,
        pbsDetected = false,
        executableCandidates = emptyList(),
        selectedExecutable = null,
        missingItems = listOf("Game.ini", "Data folder"),
        warnings = listOf("Could not read this folder. It may have been moved, deleted, or access was denied.")
    )

    private fun tooManyRootEntriesResult(entryCount: Int): GameDetectionResult = GameDetectionResult(
        status = DetectionStatus.LOW_CONFIDENCE,
        gameIniExists = false,
        gameSectionFound = false,
        detectedTitle = null,
        libraryDll = null,
        scriptsPath = null,
        scriptsExists = false,
        dllExists = false,
        dataFolderExists = false,
        graphicsFolderExists = false,
        audioFolderExists = false,
        fontsDetected = false,
        pluginsDetected = false,
        pbsDetected = false,
        executableCandidates = emptyList(),
        selectedExecutable = null,
        missingItems = listOf("Game.ini", "Data folder"),
        warnings = listOf(
            "This folder has $entryCount items directly inside it, which is far more than a typical game folder. " +
                "Please make sure you selected the game's own folder (the one containing Game.ini), not a parent " +
                "folder such as your device's whole internal storage or SD card."
        )
    )

    private fun errorResult(message: String?): GameDetectionResult = GameDetectionResult(
        status = DetectionStatus.LOW_CONFIDENCE,
        gameIniExists = false,
        gameSectionFound = false,
        detectedTitle = null,
        libraryDll = null,
        scriptsPath = null,
        scriptsExists = false,
        dllExists = false,
        dataFolderExists = false,
        graphicsFolderExists = false,
        audioFolderExists = false,
        fontsDetected = false,
        pluginsDetected = false,
        pbsDetected = false,
        executableCandidates = emptyList(),
        selectedExecutable = null,
        missingItems = listOf("Game.ini", "Data folder"),
        warnings = listOf("An unexpected error occurred while scanning this folder" + (message?.let { ": $it" } ?: ".") + " The game was not verified.")
    )

    companion object {
        /**
         * Root-level entry cap — see [tooManyRootEntriesResult]. Generous
         * on purpose: real game folders rarely exceed a few dozen top-level
         * entries even when unusually organized; this exists to catch
         * "wrong folder entirely" cases, not to reject legitimate games.
         */
        private const val MAX_ROOT_ENTRIES = 500

        /**
         * Single source of truth for confidence computation — used both by
         * [detect] on the initial scan and by [applySelectedExecutable]
         * when the user later resolves an ambiguous executable choice, so
         * the two paths can never silently diverge.
         *
         * Priority order (first match wins): Low > Needs Review > High > Medium.
         * This matches the Sprint 2 spec's confidence rules — Low Confidence's
         * conditions ("missing Game.ini", "missing Scripts", "doesn't look
         * like an RPGXP project") are treated as harder gates than the
         * "supporting folder missing" conditions that only drop to Medium.
         */
        fun computeStatus(
            gameIniExists: Boolean,
            gameSectionFound: Boolean,
            scriptsDeclared: Boolean,
            scriptsExists: Boolean,
            libraryDeclared: Boolean,
            dllExists: Boolean,
            dataFolderExists: Boolean,
            graphicsFolderExists: Boolean,
            audioFolderExists: Boolean,
            executableCandidateCount: Int,
            selectedExecutable: String?,
            looksLikeRpgxpProject: Boolean
        ): DetectionStatus {
            val lowConfidence = !gameIniExists || !scriptsDeclared || !scriptsExists || !looksLikeRpgxpProject
            if (lowConfidence) return DetectionStatus.LOW_CONFIDENCE

            val needsReview = executableCandidateCount > 1 && selectedExecutable == null
            if (needsReview) return DetectionStatus.NEEDS_REVIEW

            val highConfidence = gameSectionFound &&
                libraryDeclared && dllExists &&
                dataFolderExists && graphicsFolderExists && audioFolderExists &&
                selectedExecutable != null
            if (highConfidence) return DetectionStatus.HIGH_CONFIDENCE

            return DetectionStatus.MEDIUM_CONFIDENCE
        }

        /**
         * Applies a user-chosen executable to an existing [GameEntry],
         * recomputing [DetectionStatus] via the same [computeStatus] rules
         * used at scan time. Reused by both the Home add-flow's "Choose
         * Executable" dialog and the Game Detail screen's executable
         * selector — one place this logic lives, not two.
         */
        fun applySelectedExecutable(entry: GameEntry, executableName: String): GameEntry {
            val d = entry.detection
            val newStatus = computeStatus(
                gameIniExists = d.gameIniExists,
                gameSectionFound = d.gameSectionFound,
                scriptsDeclared = d.scriptsPath != null,
                scriptsExists = d.scriptsExists,
                libraryDeclared = d.libraryDll != null,
                dllExists = d.dllExists,
                dataFolderExists = d.dataFolderExists,
                graphicsFolderExists = d.graphicsFolderExists,
                audioFolderExists = d.audioFolderExists,
                executableCandidateCount = d.executableCandidates.size,
                selectedExecutable = executableName,
                looksLikeRpgxpProject = d.gameIniExists || d.dataFolderExists
            )
            return entry.copy(
                selectedExecutable = executableName,
                detection = d.copy(status = newStatus),
                libraryMetadataUpdatedDate = nowIso()
            )
        }
    }
}

// ---- Game.ini parsing (classic RPG Maker XP INI format) ----

private data class GameIniData(
    val gameSectionFound: Boolean,
    val title: String?,
    val library: String?,
    val scripts: String?
)

/**
 * Minimal INI parser scoped to exactly what Sprint 2 needs: the [Game]
 * section's Title=/Library=/Scripts= keys. Not a general-purpose INI
 * library — deliberately small and easy to read rather than pulling in a
 * dependency for three key/value pairs.
 *
 * Known limitation (flagged for Ti to verify on real games): reads the
 * file as UTF-8. Some older or Japanese-origin Game.ini files may use
 * Shift-JIS instead; Library=/Scripts= values are typically ASCII
 * regardless, but a non-ASCII Title= could come through mangled in that
 * case. Not blocking for Sprint 2 — logged as a known limitation.
 */
private fun parseGameIni(text: String): GameIniData {
    var inGameSection = false
    var sectionFound = false
    var title: String? = null
    var library: String? = null
    var scripts: String? = null

    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) return@forEach

        val sectionMatch = Regex("^\\[(.+)]$").find(line)
        if (sectionMatch != null) {
            inGameSection = sectionMatch.groupValues[1].equals("Game", ignoreCase = true)
            if (inGameSection) sectionFound = true
            return@forEach
        }

        if (!inGameSection) return@forEach

        val eqIndex = line.indexOf('=')
        if (eqIndex <= 0) return@forEach
        val key = line.substring(0, eqIndex).trim()
        val value = line.substring(eqIndex + 1).trim()

        when {
            key.equals("Title", ignoreCase = true) -> title = value.ifBlank { null }
            key.equals("Library", ignoreCase = true) -> library = value.ifBlank { null }
            key.equals("Scripts", ignoreCase = true) -> scripts = value.ifBlank { null }
        }
    }

    return GameIniData(sectionFound, title, library, scripts)
}

// ---- SAF helpers ----

private fun DocumentFile.readTextOrNull(resolver: ContentResolver): String? = runCatching {
    resolver.openInputStream(uri)?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
}.getOrNull()

private fun List<DocumentFile>.findFolderIgnoreCase(name: String): DocumentFile? =
    firstOrNull { it.isDirectory && it.name?.equals(name, ignoreCase = true) == true }

private fun List<DocumentFile>.findFileIgnoreCase(name: String): DocumentFile? =
    firstOrNull { it.isFile && it.name?.equals(name, ignoreCase = true) == true }

/**
 * ANR fix (App v0.0.2 bug report), replacing the old `resolveRelativePath`:
 * that function walked an arbitrary number of path segments via a fresh
 * `listFiles()` SAF call *per segment*, with no depth limit — for a
 * classic Scripts= value like "Data\Scripts.rxdata" this meant querying
 * the *entire* Data folder's children (which for a real Essentials game
 * can be hundreds to tens of thousands of map/asset files) just to find
 * one filename. That single SAF query could itself take long enough to
 * matter even off the main thread, and — independent of speed — it
 * violates this sprint's own "must remain shallow, do not recursively
 * scan Data" boundary.
 *
 * This replacement only ever performs at most ONE extra `listFiles()`
 * call (on the already-fetched [dataFolder], never deeper), and only for
 * the conventional, near-universal case where Scripts= points directly
 * inside Data/ (e.g. "Data/Scripts.rxdata" or "Data/Scripts/" for a
 * modern layout marker file). Any Scripts= value with more than one path
 * segment beyond Data/ — or no Data folder at all — is treated as
 * "declared but not verified" (`scriptsExists = false`) rather than
 * walked into further. That's a real, narrower guarantee than before
 * (verification, not just presence, for the common case only) — a
 * deliberate trade-off explained in the accompanying bug-fix writeup,
 * not a silent behavior change.
 */
private fun resolveScriptsFileShallow(dataFolder: DocumentFile?, rawScriptsPath: String): Boolean {
    if (dataFolder == null) return false
    val segments = rawScriptsPath.replace('\\', '/').split('/').filter { it.isNotBlank() }
    // Expect exactly ["Data", "Scripts...(.rxdata)"] — Data/ itself is
    // already resolved as dataFolder, so only the *last* segment needs
    // checking, and only when the path is the conventional depth-2 shape.
    if (segments.size != 2 || !segments[0].equals("Data", ignoreCase = true)) return false
    val targetName = segments[1]
    return dataFolder.listFiles().any { it.isFile && it.name?.equals(targetName, ignoreCase = true) == true }
}

private fun nowIso(): String = java.time.Instant.now().toString()

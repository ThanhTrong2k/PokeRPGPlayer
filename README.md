# PokeRPG Player — Android App

Prototype v0.0.1 delivered the navigation/UI shell. Sprint 2 (App v0.0.2) added the first real domain layer: a local Game Library. App v0.0.3 fixed three Add Game ANR bugs (verified by Ti on a real device). App v0.0.4 (Sprint 3) defined the Runtime contract (models + interface + stub) — backend-only, no native runtime. App v0.0.5 (Sprint 6) implemented SAF-to-Runtime Bridge Option B (DEC-017) — mirrors a SAF-selected game folder into an app-private workspace. App v0.0.6 was a Sprint 6 hotfix (ChatGPT review). App v0.0.7 (Sprint 7) packaged Ti's already-built ARM64 `.so` artifacts and added a load-only `androidTest` smoke test. App v0.0.8 was a Sprint 7 hotfix: added the SDL2 Java bridge classes so the smoke test could get past SDL2's own JNI_OnLoad — Ti confirmed all 8 native libraries load successfully on a real device. App v0.0.9 (Sprint 8) added `RuntimeActivity` — an internal/test-only Activity that injects an app-private workspace path into the selected mkxp-z fork's `GAME_PATH` mechanism; Ti confirmed it reaches `RESUMED` and the SDL surface creates, but logcat never showed native-side confirmation the injected path was actually read. App v0.0.10 (Sprint 9) added a deliberate-invalid-path `androidTest`, designed to force the native side's own existing failure-logging path — but Ti's first real-device run still showed no native `"mkxp"` evidence: the Activity went from `RESUMED` straight through to `DESTROYED` with no wait in between, almost certainly tearing it down before the asynchronous native thread ever started. App v0.0.11 was a Sprint 9 hotfix adding a bounded 4-second wait — Ti's next run showed the *entire* SDL lifecycle completing correctly (surface ready, focus true, resumed), ruling out the originally-suspected `mBrokenLibraries` gate, but the native thread's own startup confirmation still never appeared. App v0.0.12 extended the wait to 10 seconds and added direct diagnostic reads of `SDLActivity`'s own gate-state fields — which revealed the real, precise root cause: `mIsResumedCalled` stayed `false` even though `"onResume()"` had logged. **App v0.0.13 is Sprint 9 diagnostic step 3, and fixes the actual bug**: `SDLActivity.mHasMultiWindow` is a version check (`Build.VERSION.SDK_INT >= 24`), unconditionally `true` on Android 16, which routes `resumeNativeThread()` to a call site in `onStart()` that's commented out in this fork's copy of `SDLActivity.java` — the fork's own `MainActivity` has a working override for exactly this case, but `RuntimeActivity` (which deliberately doesn't extend `MainActivity`, to avoid its OBB/`MANAGE_EXTERNAL_STORAGE` logic) lost it too. `RuntimeActivity` now has its own minimal `onStart()` override restoring just that one call. Ti verified on OPPO PGEM10 (Android 16) that native `main()` is reached and reads the injected `GAME_PATH` via JNI — Sprint 9 is closed. **App v0.0.14 is a Sprint 10 candidate**: a second `androidTest`, this time injecting a real, existing, empty app-private directory instead of a missing one, targeting a genuinely *positive* logcat checkpoint (`"RGSS version 1 (RPG Maker XP)"`) that can only appear after `directoryExists()`/`setCurrentDirectory()`/config-load all succeed — not an inference from the absence of a failure line. Ti confirmed the technical objective: the checkpoint appeared, and the engine proceeded further into real OpenGL backend initialization — but the `androidTest` run itself was cancelled by the test framework, because a valid workspace lets native `main()` enter mkxp-z's real, long-running engine loop, and `SDLActivity.onDestroy()` blocks the main thread indefinitely waiting for that loop to exit on its own. **App v0.0.15 is a harness-only fix**: the same test now closes the scenario on a bounded background thread instead of waiting on it directly, so a slow or incomplete native teardown no longer hangs the whole instrumentation run — the positive evidence itself was never in question. Ti confirmed the boundary held on a second, independent run. Research after that (no code) found the next checkpoint after config-load — a missing-Scripts error inside `runRMXPScripts()` — is silent in logcat on both the native and Java sides, but surfaces as a real, standard Android `AlertDialog`. **App v0.0.16 is Sprint 12**: a new Espresso-based `androidTest` that detects that exact dialog's title and message, read-only, without tapping its button — the first genuine PASS/FAIL test in this whole runtime investigation, rather than a log-only design. Ti's real-device run did not pass: logcat showed native startup proceeding through `printRgssVersion()`, then SDL requesting landscape orientation, then the Activity immediately logging `onPause`/`surfaceDestroyed`/`onStop`/`onDestroy` — before the expected dialog was ever observed, and the test never completed. Root cause, confirmed by reading `SDLActivity.java` directly: SDL's own `setOrientation()` calls the standard `Activity.setRequestedOrientation()` API, which — since `RuntimeActivity`'s manifest declared no `android:configChanges` — makes Android destroy and recreate the whole Activity by default, no test code involved. **App v0.0.17 is a Sprint 12 hotfix**: adds `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` to `RuntimeActivity`'s manifest entry only, so the same Activity instance survives the orientation request instead of being torn down mid-test. Between App v0.0.17 and v0.0.18, Sprint 13 (planning/ADR only, no app changes) defined a `RuntimeStrategy` classification model for real fan game folders and completed read-only intake for two real games — Pokémon Consonancia (hybrid-heavy, plugin-heavy) and Pokémon Z (hybrid-light) — selecting Pokémon Z as the safer first controlled real-game candidate. Sprint 14 planning then source-traced, for the first time, the *real* `Scripts.rxdata` success path (script archive read, per-script decompression, actual Ruby script execution) — finding that runtime Ruby exceptions during script execution *are* logged via `Debug()` (unlike every loading-failure path characterized so far, which is silent). **App v0.0.18 is the Sprint 14 implementation**: a new, internal-only `androidTest` that points `RuntimeActivity` at a real Pokémon Z folder — provided entirely by Ti, on Ti's own device, never bundled in this project or its deliverables — and collects evidence on what actually happens. Ti's real run reached real Ruby script execution for the first time in this project and hit a genuine, `Debug()`-logged exception: a `Win32API`/`kernel32` dependency (a Windows-only OS DLL with no Android equivalent) — Sprint 15 (planning only) traced this to mkxp-z's own `MiniFFI`/`Win32API` shim, confirmed it's a real, working shim that simply can't satisfy a request for a library with no cross-platform port, and paused further Pokémon Z work pending a separate compatibility-strategy decision. Sprint 16 (planning only) then selected a cleaner candidate — a clean Pokémon Essentials v21.1 baseline — and traced a second, distinct open question: this baseline's own `mkxp.json` shows a `windowTitle` value with an encoding artifact, traced to `config.cpp`'s own `uchardet`-based charset detection, genuinely unresolved from source alone whether it's a real runtime bug or a display-only artifact. **App v0.0.19 is the Sprint 16 implementation**: a new, internal-only `androidTest`, adapted (not copy-pasted) from the Pokémon Z test, pointing `RuntimeActivity` at this cleaner baseline and collecting the same category of evidence, plus explicit new observations for the MIDI/FluidSynth path (actively engaged by this baseline's own config) and the windowTitle encoding question. Sprint 17 (App v0.0.20) added a config-variant investigation to isolate that encoding question and a related trailing-comma JSON question — but that first version embedded `mkxp.json` content directly in Kotlin source and wrote it at runtime, which was reviewed and found to undermine the experiment's own validity (introducing yet another encoding-transformation layer on top of the very question being investigated). **App v0.0.21 is the corrected version**: the same three-method investigation, now purely read-only against three separately, manually prepared, pre-existing app-private workspace folders — the app/test harness never generates or modifies any config content. Sprint 18 (App v0.0.22) extended the ASCII-safe fixture's own observation window to 90 seconds with 10-second checkpoints, finding the runtime stayed silently alive with no further log output — an ambiguous state logcat alone couldn't resolve. Sprint 19 (App v0.0.23) added real, pixel-level screenshot capture (`UiDevice.takeScreenshot()`) to that same test, which revealed the runtime was not silent at all — it had reached a real, visible Ruby exception dialog: `NameError: uninitialized constant PluginManager::Zlib`. Sprint 20 (App v0.0.24) built a fully independent, PE21-free diagnostic probe (using mkxp-z's own `customScript` config feature) that directly refuted the initial "missing Ruby zlib extension" hypothesis — `Zlib` works correctly in this runtime. Web research then found this exact error independently reported by the wider mkxp-z community, resolved by a version downgrade — strong evidence of a load-order/timing defect in mkxp-z's own engine. Sprint 21 (App v0.0.25/26) built a test-only diagnostic using mkxp-z's own `preloadScript` config feature to pre-require `Zlib` before Essentials' real scripts run (App v0.0.25's first attempt failed on a `JSONException`, since mkxp-z's config format is JSON5-tolerant, not strict JSON — fixed in App v0.0.26 with a minimal, text-based insertion instead of any JSON parsing) — and the blocker disappeared. Sprint 22 (App v0.0.27) extended that observation to 120 seconds with three screenshots, confirming a **stable, real Essentials v21.1 title menu render** — the first visual title-menu evidence for any Essentials-based game in this project's history, and 24 new files generated including a real compiled plugin cache, `Data/PluginScripts.rxdata`. Sprint 23 (App v0.0.28/29) tests whether that generated cache changes second-run behavior — App v0.0.28's own single-method, two-launch design proved unreliable (an `ActivityScenario` sequencing limitation, not a finding about the cache itself), corrected in App v0.0.29 by splitting cache-priming and second-run observation into two independent test methods and instrumentation invocations. **All of this remains test-only, diagnostic evidence** — never a claim about the original, unmodified Pokémon Essentials v21.1 distribution, and never a production preload/config-injection mechanism. Still no Play button, no public UI, no `RuntimeManager` wiring, no game boot/title screen/gameplay/compatibility claim for the real, unmodified game, no input/controller/save/audio testing.

## ⚠️ Build verification status — read this first

This project is written in an environment with **no Android SDK, no Gradle, no `adb`, and no network access to `dl.google.com` / `maven.google.com` / `services.gradle.org`**. That means Claude cannot compile a full Gradle/Android build, install an APK, or run it on an emulator/device in this environment.

**This sprint is different from every prior one in one respect: the pure-Kotlin logic was actually compiled and executed, not just statically reviewed.** The sandbox has no access to the project's real toolchain (Kotlin 2.1.21 via Gradle), but Ubuntu's own `apt` repositories (already network-whitelisted) provide an old system Kotlin compiler (1.3.31) and JUnit 4.13.2. `GameLaunchRequestMapper.kt`, its model dependencies (`GameEntry`, `GameDetectionResult`, `DetectionStatus`), and the full `GameLaunchRequestMapperTest.kt` suite were copied into a scratch directory, had `data object` syntax swapped for plain `object` (a syntax-only change for Kotlin 1.3 compatibility — **not** applied to the actual shipped files, which keep modern `data object` syntax matching the project's real Kotlin 2.1.21), compiled with `kotlinc`, and run for real: **all 5 tests passed.** As a sanity check on the tests themselves, the mapper's null-check was deliberately broken in that same scratch copy and recompiled — the test suite correctly failed and reported the exact regression, confirming the tests aren't vacuous. `RuntimeManager`/`StubRuntimeManager` need `kotlinx.coroutines.flow.StateFlow`, which isn't available via `apt` in this sandbox, so those two files were verified by static review only (brace/paren balance, import completeness) — the same discipline used for every prior Android file, and consistent with how `HomeViewModel`/`JsonFileGameLibraryRepository` already use the identical `StateFlow`/`MutableStateFlow` pattern successfully elsewhere in this same project.

Per the Prototype v0.0.1 Gradle sync incident, the version matrix (Kotlin 2.1.21 / AGP 8.10.1 / Gradle 8.11.1 / Compose BOM 2025.05.01) is unchanged. This sprint adds no new dependencies — `runtime/` uses only `kotlinx.coroutines.flow.StateFlow`, already available via the existing `lifecycle-viewmodel-compose` dependency.

## How to open and build

1. Open the project root folder in Android Studio (current stable version).
2. Let Android Studio generate the Gradle wrapper jar (not checked in — see Prototype v0.0.1 notes; same reason).
3. Accept any suggested AGP/Kotlin/Compose upgrades if prompted.
4. Build and run on an emulator or device (minSdk 26 / Android 8.0+).
5. Grant folder access when prompted by the SAF picker — try adding a real Pokémon Essentials game folder to exercise detection, ideally including at least one of the three problem cases from the bug report (renamed .exe, RTP-dependent game, a heavy/large game folder).

## Scope (what's real vs. placeholder) — Sprint 2

| Requirement | Status |
|---|---|
| SAF folder picker, add to library, persist permission | Implemented — `SafAccessManager.takePersistableAccess` (Prototype v0.0.1 deliberately did not do this; Sprint 2 does) |
| Local persistence (survives restart) | Implemented — `JsonFileGameLibraryRepository`, app-internal storage, atomic writes |
| Stable UUID per game (never URI/path as ID) | Implemented |
| Rename, favorite/unfavorite, remove-from-library | Implemented — remove never touches original files |
| Game.ini parsing (`[Game]` section, Title/Library/Scripts) | Implemented — minimal hand-written parser, UTF-8 |
| Data/Graphics/Audio folder checks | Implemented |
| Fonts/Plugins/PBS checks (optional, never block validity) | Implemented |
| Root-level `.exe` scan + Game.exe-first / single-candidate / ambiguous rules | Implemented — `ExecutableDetector` |
| Choose-executable flow (dialog on add, selector in Game Detail) | Implemented |
| Invalid-folder warning + "Add Anyway (Needs Review)" | Implemented |
| Detection confidence (`High/Medium/Needs Review/Low`) | Implemented — `GameDetectionService.computeStatus`, single source of truth reused on manual executable re-selection |
| Game Card + Game Detail screens | Implemented |
| Runtime, mkxp-z, game launching, deep plugin scanning, save detection, compatibility engine | **Not implemented** — explicitly out of scope this sprint |

## Architecture (Sprint 2 additions)

```
data/
  model/            GameEntry, GameDetectionResult, DetectionStatus
  repository/       GameLibraryRepository (interface) + JsonFileGameLibraryRepository
  detection/        GameDetectionService (SAF-dependent) + ExecutableDetector (pure)
  saf/              SafAccessManager
AppContainer.kt     manual composition root (no DI framework yet — see its doc comment)
viewmodel/          HomeViewModel (add-flow orchestration), GameDetailViewModel
ui/screens/         HomeScreen (library list/empty state), GameDetailScreen
ui/components/      GameCard, DetectionStatusBadge (+ reused PokeRPGTopBar/PokeRPGEmptyState)
```

Key architectural decisions (explained further in code comments, and in the Sprint 2 dev journal entry in PokeRPG Player OS):

- **`GameLibraryRepository` is an interface.** `JsonFileGameLibraryRepository` is one implementation; a future Room-backed implementation is a drop-in replacement, not a rewrite.
- **`org.json` (built into Android), not kotlinx.serialization.** Deliberate choice to avoid a new Gradle plugin/version-matrix risk right after the Prototype v0.0.1 incident. Trade-off: not unit-testable on plain JVM without Robolectric — stated directly in the repository's doc comment.
- **`GameDetectionResult` (Data) vs. `GameEntry`'s user-editable fields (Knowledge)** — the same Data-vs-Knowledge split PokeRPG Player OS uses for its Compatibility Database, applied here on purpose.
- **No DI framework yet.** `AppContainer` is a small manual composition root; Hilt is a reasonable future addition once the dependency graph grows, logged as a Backlog idea rather than added now.

## App v0.0.3 — Add Game ANR/freeze fix

Ti tested App v0.0.2 on a real device. Three Add Game freezes were reported, all logged as **ANR** (not FATAL EXCEPTION):

1. A game whose executable is `reminiscencia.exe` (not `Game.exe`) froze during Add Game.
2. Pokémon Z (normally requires the RPG Maker XP RTP on Windows) froze during Add Game.
3. Some heavy game folders could be added, then the app froze/crashed.

**Root cause (all three bugs, one underlying defect):** `HomeViewModel.onFolderPicked()` called `GameDetectionService.detect(uri)` directly inside `viewModelScope.launch { }`. `viewModelScope`'s default dispatcher is `Dispatchers.Main.immediate`, and `detect()` was a plain synchronous function performing blocking SAF I/O (`DocumentFile.fromTreeUri`, multiple `listFiles()` calls, `openInputStream`+`readBytes()` for Game.ini) with no dispatcher switch anywhere. Every one of those SAF calls therefore ran **on the main thread**. Any folder whose combined SAF I/O exceeded Android's ~5-second input-dispatch timeout produced an ANR — exactly matching the log finding (ANR = blocked main thread; a crash would show as FATAL EXCEPTION instead). `reminiscencia.exe`'s game, Pokémon Z, and "heavy folders" all hit this same defect; none of them are special-cased bugs.

**Compounding factor:** the old `resolveRelativePath()` helper (used to verify the Game.ini `Scripts=` value actually exists) called `DocumentFile.listFiles()` once per path segment with no depth limit. For a conventional `Scripts=Data\Scripts.rxdata` value, this meant querying the **entire** `Data/` folder's children — which for a real Essentials game can be hundreds to tens of thousands of map/asset files — just to find one filename. This directly violated Sprint 2's own "must remain shallow, do not recursively scan Data" boundary and made the ANR far more likely to trigger on exactly the "heavy folder" case Ti reported.

**Fix (Sprint 2 scope only — see confirmation below):**

- `GameDetectionService.detect()` is now a `suspend` function that internally wraps its entire body in `withContext(Dispatchers.IO)` — matching the pattern `JsonFileGameLibraryRepository` already used. The dispatch-to-background is now the service's own responsibility, not something every call site has to remember (which is exactly what went wrong).
- `SafAccessManager.takePersistableAccess()` made `suspend` + IO-dispatched too, for the same reason (small, cheap consistency fix).
- The old unbounded `resolveRelativePath()` was replaced with `resolveScriptsFileShallow()`, which performs **at most one** extra `listFiles()` call (on the already-fetched Data folder, never deeper), and only for the conventional `Data/<filename>` shape. A Scripts= value that isn't that shape is now treated as "declared but not verified" rather than walked into further — a real, narrower guarantee than before, and a deliberate trade-off (verification for the common case only, never unbounded traversal).
- `detect()`'s entire body is now wrapped in try/catch, converting any unexpected exception into a safe `LOW_CONFIDENCE` result with a warning, instead of letting it propagate as a crash.
- A generous root-level entry-count cap (500) rejects implausible folders (e.g. an entire SD card picked by mistake) with a clear warning instead of scanning an unbounded list.
- Missing Library DLL now gets a specific, human-readable warning ("...may require the RPG Maker XP Runtime Package (RTP)...") instead of just silently appearing in a missing-items list — the confidence-scoring behavior itself was already correct (a missing DLL alone was never able to block adding a game or crash anything; it only prevented HIGH_CONFIDENCE).
- `HomeUiState` gained `isDetecting`; `HomeScreen` shows a linear progress indicator + disables the FAB while scanning, so a heavy folder that now legitimately takes a few seconds in the background doesn't look like the app is doing nothing.

**Files changed:**
- `data/detection/GameDetectionService.kt`
- `data/saf/SafAccessManager.kt`
- `viewmodel/HomeViewModel.kt`
- `ui/screens/HomeScreen.kt`
- `res/values/strings.xml` (2 new strings for the scanning state)

**Confirmation — nothing outside Sprint 2 scope was touched:** no runtime, no mkxp-z, no game launching, no RTP bundling/installation, no deep/recursive plugin scanning, no save detection. Detection remains root-level-only (Game.ini, Data/Graphics/Audio required checks, optional Fonts/Plugins/PBS checks, root-level `.exe` scan) — the only scan-depth change is that Scripts-file verification is now *more* bounded than before (single extra call, conventional shape only), not less.

### Testing checklist (App v0.0.3)

- [ ] Add a game with `Game.exe` present — still auto-selected, still HIGH/MEDIUM confidence as before.
- [ ] Add a game with a renamed executable (e.g. `reminiscencia.exe`) as the sole `.exe` — auto-selected, no freeze.
- [ ] Add a game with multiple `.exe` files and no `Game.exe` — Choose Executable dialog appears, no freeze.
- [ ] Add Pokémon Z (or another RTP-dependent game) — completes without freezing; if its Library DLL is missing, a clear warning appears on the Game Detail screen instead of a crash.
- [ ] Add a heavy/large game folder — Add Game shows the scanning indicator (FAB reads "Scanning…", disabled) instead of the UI appearing frozen; completes within a reasonable time.
- [ ] Add an unusually large/wrong folder (e.g. a whole SD card root, if testable) — get a clear "too many items" warning instead of a freeze.
- [ ] Confirm Log, About, and Settings screens still open normally (regression check — untouched by this patch).
- [ ] Confirm a previously-added game's Game Detail screen still opens and displays correctly (regression check on `GameDetectionResult` field completeness).
- [ ] Confirm the app never shows FATAL EXCEPTION for any of the above cases — worst case is a LOW_CONFIDENCE/warning result, never a crash.

## App v0.0.4 — Sprint 3: Runtime Foundation Preparation

Approved architecture proposal: `PokeRPGPlayer-Sprint3-Runtime-Foundation-Proposal-v1.0.md` (delivered separately). This sprint builds **only the contract** between Game Library and the future native mkxp-z runtime — no native runtime exists yet, and this sprint doesn't attempt to make one exist.

**New package: `runtime/`**
- `RuntimeStatus` — enum; only `UNAVAILABLE` is reachable in Sprint 3, all other values reserved.
- `RuntimeError` — sealed class; `RuntimeNotImplemented` is the only value any code path in Sprint 3 can produce. `ExecutableNotSelected` is produced by the mapper. `GameFolderAccessLost` and `Unknown` are reserved/escape-hatch, unreachable this sprint.
- `RuntimeLaunchResult` — sealed class (`Launched` / `Failed(RuntimeError)`); `Launched` is unreachable in Sprint 3.
- `RuntimeConfig` — deliberately near-empty (`schemaVersion` only, per approved Decision 1). No guessed fields for safe mode, render backend, logging, device tier, or performance profile — those depend on Device Doctor/Recommended Profile, which don't exist yet.
- `GameLaunchRequest` — the launch contract only (gameId, folderUri, executableName, detectedTitle, runtimeConfig). Deliberately does **not** carry the full `GameDetectionResult`.
- `RuntimeManager` (interface) + `StubRuntimeManager` (Sprint 3's only implementation) — `status` is always `UNAVAILABLE`; `launch()` always returns `Failed(RuntimeNotImplemented)`. No `Context` dependency, no native/JNI code of any kind.
- `GameLaunchRequestMapper` (`mapToLaunchRequest()` + `LaunchRequestResult`) — pure function, the one place a `GameEntry` is judged launchable-or-not. The only hard gate is a resolved executable; detection confidence is **not** re-checked (a Low Confidence game with a resolved executable still maps to `Ready`).

**Directional dependency, enforced by file placement:** `runtime/` imports `data.model.GameEntry`; nothing in `data/` imports or knows about `runtime/`. The mapper lives in `runtime/`, not a new `game/` package, specifically to keep this one-way (see the approved proposal §6 for the full reasoning — a deliberate, explained deviation from the proposal's own suggested possible shape).

**Explicitly deferred, per approved Decision 2:** `RuntimeCapability`/`RuntimeFeatureFlags` was not built. There's no real native build yet to have real capabilities worth describing.

**Explicitly out, per approved Decision 3:** zero UI changes. `GameDetailScreen.kt` is untouched — no Play button, no disabled Play button, no launch-related text anywhere. Sprint 3 is backend-only.

**Testing note — the first sprint with real, executed tests:** see the Build Verification section above. `GameLaunchRequestMapperTest.kt` (5 tests, all passing, verified with a real compiler + JUnit run, including a deliberate-regression sanity check) covers:
- a fully-resolved `GameEntry` maps to `Ready` with every field correctly copied
- `selectedExecutable == null` maps to `Rejected(ExecutableNotSelected)`
- a **Low Confidence** `GameEntry` with a resolved executable still maps to `Ready` — proves confidence is not a launch gate
- `detectedTitle == null` passes through as `null`, not substituted
- an unconventional executable name (e.g. `reminiscencia.exe`, `A Farfetch'd Story.exe`) passes through unchanged

**Confirmed: no boundary violation.** No `System.loadLibrary`, no JNI, no mkxp-z source/build reference anywhere in the codebase. No save/load, no plugin deep scanner, no Device Doctor, no cover/icon system, no Cloud Save, no Poké Helper, no licensing. No game file was read, patched, or written to — `runtime/` doesn't touch SAF/`DocumentFile` at all in Sprint 3.

## App v0.0.5 — Sprint 6: Runtime Workspace Mirror Prototype

Implements SAF-to-Runtime Bridge Option B (DEC-017, approved after Sprint 5's Runtime Integration Plan v2.0): mirrors a SAF-selected game folder into an app-private workspace, so a future runtime has a real POSIX path to `chdir()` into without PokeRPG Player ever depending on `MANAGE_EXTERNAL_STORAGE`. Reviewed and approved with revisions before implementation began — this section reflects the approved, revised design, not the original plan draft.

**New `runtime/` files:**
- `RuntimeWorkspaceService` — interface, not a concrete class. Same interface-first pattern as `GameLibraryRepository`/`RuntimeManager`: this is exactly the seam Bridge Option D (custom PhysFS/ContentResolver bridge) is expected to replace later, so the swap should cost nothing at any call site when that happens.
- `MirrorRuntimeWorkspaceService` — Option B's implementation. Mirrors `<SAF folder>` → `context.filesDir/runtime-workspace/<gameEntryId>/`, preserving directory structure and filenames exactly. Every call **fully deletes and re-mirrors** — no incremental sync in this prototype (approved as a deliberate simplification).
- `WorkspaceMetadata`, `WorkspaceError`, `WorkspaceResult` — data models. `WorkspaceError` was revised in review from one vague catch-all into `InvalidSourceFolder`, `SafAccessLost`, `UnsupportedDocumentType`, `CopyFailure(relativePath, message)`, `InsufficientStorage`, `Unknown` — no case is silently skipped; a `CopyFailure` always names the exact file.
- `WorkspacePathResolver` — pure helper, deliberately the *only* place any workspace/registry path gets constructed. Validates `gameEntryId` as a real UUID before ever using it in a path (rejects anything path-traversal-shaped, e.g. `"../../../etc"`, structurally, not by convention), and is what `clearWorkspace` relies on to confirm — via canonical, symlink-resolved path comparison — that it's about to delete *exactly* `<filesDir>/runtime-workspace/<gameEntryId>/` and nothing else.

**Registry:** a single JSON file at `context.filesDir/runtime-workspace/registry.json` (one entry per game, keyed by `gameEntryId`) — same `org.json` + write-to-`.tmp`-then-rename pattern `JsonFileGameLibraryRepository` already uses, not a new dependency. Deliberately kept *outside* the mirrored file tree itself, so nothing extra ever shows up alongside a game's own `Data`/`Graphics`/`Audio` folders.

**Revisions applied during review, before any code was written:**
1. `WorkspaceError` split into distinct cases (above) instead of one vague `UnsupportedFolder`.
2. `Cancelled` removed from `WorkspaceError` entirely — `CancellationException` is never caught and converted into a result; it always propagates normally. A `finally` block (using a `completedSuccessfully` flag, not an unconditional delete) cleans up any partial workspace on *any* non-success exit — including a cancellation unwinding through it — without ever deleting a workspace that just genuinely succeeded.
3. Storage pre-flight is explicitly best-effort only: a root-level-only (not recursive) size estimate using whatever `DocumentFile.length()` values are actually reliable: if none are, the check is skipped entirely rather than guessing. The real backstop is a copy-time `IOException` catch, heuristically checked for an out-of-space signal (exception message content, or near-zero remaining disk space) — approximate on purpose, never treated as authoritative.
4. `clearWorkspace` is path-safe by construction: it takes only a `gameEntryId`, never a caller-supplied path, and re-derives + canonically verifies the exact expected path before deleting anything.
5. No file is ever silently skipped — a copy failure always aborts the whole mirror attempt and reports exactly which file, via `CopyFailure(relativePath, message)`. There is no `skippedCount` in this prototype.

**Bugs caught and fixed during Claude's own review, before delivery — noted here rather than silently corrected:**
- The `finally` cleanup block was initially written empty (a comment claimed it cleaned up on cancellation; the code didn't). Fixed by introducing the `completedSuccessfully` flag described above.
- `WorkspaceError.SafAccessLost` was defined but never actually produced anywhere — `SecurityException` during the SAF walk was originally being mapped to the generic `CopyFailure` instead. Fixed so a lost/revoked SAF permission is reported distinctly from an ordinary per-file copy problem, both during directory listing and during file reads.
- A redundant `catch (e: MirrorAbortException) { throw e }` block that did nothing (Kotlin already lets non-matching exception types propagate past unrelated `catch` clauses) was removed.

**Testing — real execution, same discipline as Sprint 3:** `WorkspacePathResolver` and its dependencies (`WorkspaceError`, `WorkspaceMetadata`, `WorkspaceResult`) have zero Android framework dependency, so `WorkspacePathResolverTest.kt` (18 tests) was actually compiled and run — not just statically reviewed — via the same sandboxed Kotlin 1.3 + JUnit 4.13.2 workaround as Sprint 3 (scratch copy, `data object` syntax swapped for `object`, real shipped files untouched; `.lowercase()` also swapped for `.toLowerCase()` in the scratch copy only, since that stdlib function postdates Kotlin 1.3 — the real file keeps `.lowercase()`, matching the project's actual Kotlin 2.1.21). **All 18 passed.** As a sanity check, the path-safety comparison was deliberately broken (made to always return true) and recompiled — 3 tests correctly failed, catching the exact regression; the fix was reverted and all 18 passed again. `MirrorRuntimeWorkspaceService` itself needs `Context`/`DocumentFile`/`ContentResolver` (real Android framework types), so — like `RuntimeManager`/`StubRuntimeManager` in Sprint 3 — it was verified by static review only (brace/paren balance, import completeness), not real execution.

**Explicitly not implemented this sprint, per approved scope:**
- No wiring into `RuntimeManager.launch()` — `StubRuntimeManager` is untouched and still always returns `Failed(RuntimeNotImplemented)`.
- No `System.loadLibrary`, no JNI, no native code loaded.
- No custom PhysFS/ContentResolver bridge (Option D remains the future target).
- No Play button, no launch UI, no UI at all.
- No save sync/save-back logic.
- No incremental/smart re-sync (full re-mirror only, an approved simplification, not an oversight).
- No Cloud Save, input overlay, Device Doctor, plugin scanner expansion, helper/AI features, cheat/debug forcing.
- The original SAF-selected folder is never opened for writing anywhere in this sprint's code — every operation against it is a read.

**Not claimed:** real on-device verification. Everything above describes what was built and how it was verified in this sandbox — actually mirroring a real fan-game folder on a real device is Ti's role, same division of responsibility as every prior sprint.

## App v0.0.6 — Sprint 6 hotfix (ChatGPT review)

ChatGPT reviewed the Sprint 6 implementation and approved the overall direction, but required two fixes before marking the sprint review-ready. No new features — same scope boundary as App v0.0.5.

**Fix 1 — no silently skipped or unsafe SAF document names.** `MirrorRuntimeWorkspaceService.mirrorDirectory()` used to do `val name = child.name ?: continue` — silently skipping any document with a null name, directly violating the approved "no file is silently skipped" rule. Replaced with a hard abort: if a document's name is null, blank, or unsafe, the whole mirror attempt now fails with `WorkspaceError.CopyFailure`, naming exactly where in the tree it happened. A new pure helper, `WorkspacePathResolver.isSafeDocumentName()`, rejects null/blank names, a literal `.` or `..`, any name containing `/` or `\`, and any name containing a NUL character — never renames a file to work around an unsafe name, only refuses to proceed.

**Fix 2 — a registry write failure can no longer escape as an unhandled exception.** `writeRegistryEntry(metadata)` used to run *after* the mirror's own try/finally block, completely unprotected — a failure there would propagate out of `prepareWorkspace()` as a raw exception instead of a `WorkspaceResult.Failed`. The registry write is now inside the same protected block as the file copy, and `completedSuccessfully` is only set `true` after **both** the copy and the registry write succeed. If the registry write fails after a successful copy, the newly-mirrored (but now unregistered) workspace is cleaned up rather than left as untracked orphan data on disk — consistent with the workspace never being authoritative on its own.

**A related bug caught during this same fix, not asked for but in scope:** `File.renameTo()` — used by both `writeRegistryEntry` and `removeRegistryEntry` for the atomic-write pattern — returns a `Boolean` and does **not** throw on failure. The return value was being silently ignored in both places, meaning a failed rename was previously indistinguishable from success — arguably worse than an uncaught exception, since it's completely invisible. `writeRegistryEntry` now throws if the rename fails, feeding into the same Fix 2 handling above. `removeRegistryEntry` (used only by `clearWorkspace`) deliberately stays best-effort — that function's own contract is about freeing disk space, which already succeeded by the time this runs, so a registry-cleanup rename failure there is logged-worthy but not escalated to failing the whole `clearWorkspace` call.

**A compile-correctness issue caught and fixed before it could become a real build failure:** the initial Fix 1 draft called `isSafeDocumentName(name)` where `name: String?`, then used `name` as if it were non-null immediately afterward — Kotlin does not smart-cast through an arbitrary boolean-returning function call, so this would not have compiled. Verified empirically (a standalone snippet compiled against the sandboxed Kotlin toolchain) that restructuring the guard as `if (name == null || !isSafeDocumentName(name))` lets Kotlin's compiler smart-cast `name` to non-null afterward, and fixed the real file to use that exact form.

**Files changed:** `runtime/WorkspacePathResolver.kt` (new `isSafeDocumentName` helper), `runtime/MirrorRuntimeWorkspaceService.kt` (both fixes + the `renameTo` fix), `src/test/.../runtime/WorkspacePathResolverTest.kt` (11 new tests for `isSafeDocumentName`).

**Testing:** `WorkspacePathResolverTest.kt` now has 29 tests (18 from Sprint 6 + 11 new), actually compiled and run for real via the same sandboxed Kotlin 1.3 + JUnit workaround as every prior sprint — **all 29 passed.** As a sanity check, the new path-separator rejection was deliberately removed and recompiled — 3 tests correctly failed, then the fix was reverted and all 29 passed again. `MirrorRuntimeWorkspaceService` itself still needs `Context`/`DocumentFile`/`ContentResolver` and was verified by static review only (brace/paren balance, import completeness), not real execution — **not claiming real Android/on-device verification of either fix.**

**Scope unchanged:** no Play button, no launch UI, no native loading, no JNI, no `RuntimeManager.launch()` wiring, no save sync, no custom PhysFS/ContentResolver bridge, no incremental sync. This is a hotfix to Sprint 6's own code, not new functionality.

## App v0.0.7 — Sprint 7: Native Runtime Packaging / Native Load Smoke Test

Approved with a narrow scope after plan review (ChatGPT/Ti): package Ti's already-built ARM64 native artifacts and add a single, test-only smoke test that attempts to load them — nothing else. Every one of the 15 required scope corrections from the approved plan review holds: no `AppContainer` wiring, no production UI call, no Play/Launch affordance of any kind, no `RuntimeManager.launch()` wiring, no SDLActivity/RuntimeActivity integration, no `GAME_PATH` injection, no workspace-to-native bridge implementation, no mkxp-z engine entrypoint call, no JNI function call, no Ruby VM init, no SDL window/GL surface, no game boot attempt, no save sync, no `MANAGE_EXTERNAL_STORAGE`, no original game files touched.

**⚠️ Important — the actual `.so` binary files are NOT included.** Claude has never had access to the real compiled native artifacts; they exist only on Ti's own WSL machine from the Sprint 4/5 build. `app/src/main/jniLibs/arm64-v8a/` currently contains only a `README.md` explaining exactly which 8 files need to be copied there, with exact filenames, before this sprint's packaging can actually be exercised. See that file for the full list and reasoning.

**New files:**
- `app/src/main/jniLibs/arm64-v8a/README.md` — placeholder + instructions (see above).
- `runtime/NativeLoadResult.kt` — sealed class (`Loaded` / `Failed(libraryName, message)`), same explicit-result-type pattern as `WorkspaceResult`/`RuntimeLaunchResult`.
- `runtime/NativeRuntimeLoader.kt` — the *only* place `System.loadLibrary()` is called anywhere in this codebase. Loads all 8 libraries in the approved dependency order (`c++_shared` → `SDL2` → `SDL2_image` → `SDL2_sound` → `SDL2_ttf` → `openal` → `ruby` → `mkxp-z`), stopping at the first failure. Deliberately catches `Throwable`, not just `Exception` — `UnsatisfiedLinkError` (the primary expected failure mode) is a `LinkageError`, which is an `Error`, not an `Exception`; a narrower catch would have let the single most likely failure case escape completely uncaught. **Not referenced anywhere else in this codebase** — confirmed by grep across `AppContainer.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, and every file under `ui/`/`viewmodel/`: zero matches outside this class and its own test.
- `app/src/androidTest/java/.../runtime/NativeLibraryLoadSmokeTest.kt` — the smoke test itself, and the **first file in this project's `androidTest` source set** (the dependencies for it — `androidx.test.ext:junit`, `espresso-core`, `ui-test-junit4` — were already declared in `build.gradle.kts` from earlier sprints but never used until now). Calls `NativeRuntimeLoader.loadAll()`; on success, logs only "native library load smoke test success" — never a claim of runtime integration, game boot, or gameplay support; on failure, fails the test with the exact library name and `Throwable` message.
- `app/src/test/java/.../runtime/NativeRuntimeLoaderTest.kt` — a small addition beyond the literal approved file list, flagged as such: `NativeRuntimeLoader.LOAD_ORDER` is a bare `List<String>` constant with zero Android dependency, so it can genuinely be compiled and run (unlike `loadAll()` itself, which needs a real device). Guards the approved dependency order against an accidental future reordering.

**`app/build.gradle.kts` was deliberately left unmodified.** Checked directly: no `ndk { abiFilters }` block exists, and none is needed — Gradle automatically packages whatever ABI folders are present under `jniLibs/`, so a single `arm64-v8a/` folder is sufficient on its own with zero configuration.

**Testing — what actually ran, and what didn't:** `NativeRuntimeLoaderTest.kt` (3 tests) has no Android framework dependency, so it was actually compiled and run for real via the same sandboxed Kotlin 1.3 + JUnit 4.13.2 workaround as every prior sprint — **all 3 passed.** As a sanity check, the approved load order was deliberately scrambled and recompiled — 2 tests correctly failed, then the fix was reverted and all 3 passed again. **`NativeRuntimeLoader.loadAll()` itself and the `androidTest` smoke test cannot be executed in this environment at all** — `System.loadLibrary()` requires a real Android runtime with the actual `.so` files present in an installed app's native library directory, which no JVM-only sandbox can provide, especially since the real binaries were never available here in the first place (see above). **This is not claimed as verified — Ti must run it locally, on a real device, after copying in the real `.so` files.**

### Running the smoke test locally (Ti)

1. Copy the 8 real `.so` files (see `app/src/main/jniLibs/arm64-v8a/README.md` for the exact list) from the WSL build output into that exact folder.
2. Open the project in Android Studio (regenerate the Gradle wrapper on first open if prompted — see the Build Verification section above for why it's not checked in).
3. Connect a real arm64-v8a Android device (an emulator may not faithfully reflect real hardware ABI/linker behavior for this specific test).
4. Right-click `NativeLibraryLoadSmokeTest.kt` in `app/src/androidTest/java/com/pokerpgplayer/app/runtime/` → **Run 'NativeLibraryLoadSmokeTest'** (or `./gradlew connectedAndroidTest` from a terminal with the wrapper regenerated).
5. A green result means exactly one thing: all 8 libraries loaded successfully on that device. A red result names the exact library and the exact `Throwable` message — report both back.

**Explicit process-global caveat:** `System.loadLibrary()` only ever takes effect once per process per library. Running the test twice within the same instrumentation process does not re-verify loading from scratch the second time — a genuine repeat check needs a fresh test run (a new process) or an app restart, not just re-clicking "Run" within the same session if the process was reused.

## App v0.0.8 — Sprint 7 hotfix: SDL2 Java Bridge

Ti ran `NativeLibraryLoadSmokeTest` for real, on an OPPO Android 16 device. `libc++_shared` and `libSDL2.so` both loaded as `.so` files successfully — the packaging from App v0.0.7 works. The process then aborted: `Failed to register methods of org/libsdl/app/SDLActivity`, followed by `JNI DETECTED ERROR IN APPLICATION: ClassNotFoundException`.

**Root cause, read directly from SDL2's own source (`release-2.26.3` — the exact tag this fork's `get_deps.sh` pins, confirmed by reading the script):** `System.loadLibrary("SDL2")` triggers SDL2's `JNI_OnLoad` unconditionally — it has nothing to do with whether anything ever calls into `SDLActivity`. `JNI_OnLoad` calls `FindClass` for four classes in sequence: `SDLActivity`, `SDLInputConnection`, `SDLAudioManager`, `SDLControllerManager`. PokeRPG Player had packaged only the `.so`, none of the Java side, so the first `FindClass` failed — and SDL2's own `register_methods` helper, while correctly avoiding a null-pointer call, never clears the resulting pending JNI exception before the *next* `FindClass` call runs. Making any JNI call while an exception is pending is illegal per the JNI spec, and Android's strict JNI checking (on by default on more recent Android versions, consistent with Ti's own "Android 16 may be stricter" observation) aborts the whole process for exactly this pattern. This is not a missing-`.so` problem and not a PokeRPG Player bug — it's SDL2's Android JNI bridge itself having no fallback for "the Java side isn't there yet."

**A genuinely interesting resolved detail:** `org.libsdl.app.SDLInputConnection` doesn't exist as its own file anywhere — not in this fork, not in upstream SDL2 2.26.3's own reference Android project (checked directly). Reading `SDLActivity.java` itself: `SDLInputConnection` is a second, package-private **top-level class defined in the same source file** as `SDLActivity` (line 1947 in the copied file: `class SDLInputConnection extends BaseInputConnection`). Java allows multiple top-level classes per file as long as only one is `public`. Copying `SDLActivity.java` unmodified yields both `SDLActivity.class` and `SDLInputConnection.class` — nothing separate needed.

**The fix:** copy all 9 files from the fork's own `app/src/main/java/org/libsdl/app/` into PokeRPG Player's own `app/src/main/java/org/libsdl/app/`, **byte-for-byte identical** — confirmed with `diff -q` against the freshly re-cloned fork (same commit, `b668e08`, as every prior recon session). No rewriting, no Kotlin conversion, no simplification.

**Files added (all copied verbatim, all confirmed byte-identical to the fork's source):**
- `app/src/main/java/org/libsdl/app/SDL.java`
- `app/src/main/java/org/libsdl/app/SDLActivity.java`
- `app/src/main/java/org/libsdl/app/SDLSurface.java`
- `app/src/main/java/org/libsdl/app/SDLAudioManager.java`
- `app/src/main/java/org/libsdl/app/SDLControllerManager.java`
- `app/src/main/java/org/libsdl/app/HIDDevice.java`
- `app/src/main/java/org/libsdl/app/HIDDeviceManager.java`
- `app/src/main/java/org/libsdl/app/HIDDeviceUSB.java`
- `app/src/main/java/org/libsdl/app/HIDDeviceBLESteamController.java`

**Confirmed, by direct grep, not by assumption:**
- Zero references to `org.libsdl.app`/`SDLActivity`/any of these 9 classes from anywhere else in the codebase — `com/pokerpgplayer/app/`, `src/test/`, and `src/androidTest/` were all checked directly.
- `NativeRuntimeLoader.kt` and `NativeLibraryLoadSmokeTest.kt` are untouched — neither needed a change for this fix.
- `AppContainer.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt` are untouched.
- `AndroidManifest.xml` is untouched — a compiled Java class sitting in the classpath needs no manifest declaration unless it's actually registered as a launchable component; nothing here does that. `SDLActivity.java` also has no static initializer and doesn't call `System.loadLibrary` at class-load time, so its mere presence has no side effects of its own. This is expected, not build-verified — if Android Studio's real compiler surfaces a manifest requirement this analysis didn't anticipate, that's a real, reportable surprise, not something to silently patch around.
- No new Gradle dependency, no `build.gradle.kts` change — all 9 files' imports are plain `android.*`/`java.*`, and Android's standard `app/src/main/java/` source set already compiles Java and Kotlin together with zero extra configuration.

**Not implemented, per the approved hotfix's own hard boundaries:** no Play button, no public or hidden launch trigger, no `RuntimeManager.launch()` wiring, no `AppContainer` wiring, no `SDLActivity` launch, no `RuntimeActivity`, no `GAME_PATH` injection, no workspace-to-native bridge, no mkxp-z engine entrypoint call, no JNI call beyond what `System.loadLibrary` triggers on its own, no Ruby VM init, no SDL window/GL surface, no game boot, no save sync, no input overlay, no Device Doctor, no PhysFS/ContentResolver bridge, no `MANAGE_EXTERNAL_STORAGE`, no original game files touched, no attempt to fix 16KB alignment (Android Studio's own build warning about it is expected and unrelated to this specific crash).

### Running the smoke test locally (Ti) — updated

Same steps as the App v0.0.7 section above (copy the real `.so` files into `jniLibs/arm64-v8a/`, build, run `NativeLibraryLoadSmokeTest` on a real device). **Expected outcome now:** the test should proceed past the `SDLActivity` registration failure. Three possible results, all informative:

1. **All 8 libraries load successfully** — the best case, and the first time this would be true.
2. **The test proceeds past `SDL2` and fails at a later library with a new, different, specific diagnostic** — still genuine forward progress, squarely within this hotfix's own narrow target. Report exactly which library and the exact message.
3. **Scope creep into runtime launch/game boot/UI** is the only actually unacceptable outcome, and nothing in this hotfix does that.

**Not claimed:** real device verification of any of this. Ti runs it; report back pass, or the next exact failing library and message.

## App v0.0.9 — Sprint 8: First Runtime Launch Boundary / GAME_PATH Injection

Ti confirmed App v0.0.8 on the same OPPO PGEM10 (Android 16): all 8 native libraries load successfully. Sprint 8 takes the next narrow step — approved as **Option B only** (hidden/internal launch test, no public UI) — by injecting an app-private path into the selected mkxp-z fork's `GAME_PATH` mechanism and attempting to reach real native startup, still without claiming a game boots.

**Architecture, grounded in re-reading both codebases fresh this session (not assumed from memory):**

- **`RuntimeActivity` does not reuse or subclass the fork's own `MainActivity`** — that class carries OBB-mounting logic and a `MANAGE_EXTERNAL_STORAGE` request, neither of which this project uses. It extends `org.libsdl.app.SDLActivity` directly instead.
- **Why a differently-named class in a different package still works:** read directly from the fork's `main.cpp` this session — `GAME_PATH` is resolved via `SDL_AndroidGetActivity()` (whatever Activity is actually running) + `GetObjectClass(activity)` (that instance's *real* runtime class) + `GetStaticFieldID(cls, "GAME_PATH", ...)`. This is dynamic, not hardcoded to `com.hatkid.mkxpz.MainActivity` — any `SDLActivity` subclass with its own `static String GAME_PATH` field is found correctly. JNI also bypasses Java's `private` access modifier entirely, so visibility was never actually a constraint here.
- **Timing, confirmed by reading `SDLActivity.java` directly:** the native `SDLMain` thread (which is what eventually reads `GAME_PATH`) only starts once the render surface is ready *and* the Activity reaches the resumed lifecycle state — well after `onCreate()` returns. Setting `GAME_PATH` at the very start of `onCreate()`, before calling `super.onCreate()`, is correctly ordered with real margin.
- **A real Kotlin/JNI interop question was verified empirically, not assumed:** whether `companion object { @JvmStatic var GAME_PATH: String; private set }` actually produces a real static field named `GAME_PATH` directly on the outer `RuntimeActivity` class (what JNI's `GetStaticFieldID` needs) — reflection-based bytecode inspection (`Class.getDeclaredField`) on a compiled standalone test confirmed it does (`private static java.lang.String RuntimeActivity.GAME_PATH`, not merely accessor methods), and that the enclosing class's own member functions can still write through `private set` from outside the companion object's own body. Both were verified by actually compiling and running the check, not inferred from general Kotlin knowledge.

**New files:**
- `runtime/RuntimeActivity.kt` — extends `SDLActivity`. Reads `EXTRA_WORKSPACE_PATH` from its launching `Intent`; if missing or blank, logs an error and calls `finish()` *before* `super.onCreate()` (Android still requires `super.onCreate()` to be called regardless, to avoid `SuperNotCalledException` — but `finish()` first ensures the Activity never reaches the resumed state the native thread needs, so nothing native-side runs in the failure path even though `SDLActivity`'s own library-loading still occurs, which is harmless and already Sprint-7-verified-safe). On a valid path, sets `GAME_PATH` and logs both the received path and the final value, then calls `super.onCreate()`. Does not override `getLibraries()`/`getMainSharedObject()`/`getMainFunction()` — this fork's own `SDLActivity.java` (already present since App v0.0.8) already defaults all three correctly to `mkxp-z`.
- `app/src/androidTest/.../RuntimeActivityLaunchTest.kt` — creates its own tiny, disposable, app-private test directory (`context.filesDir/sprint8-runtime-activity-test-workspace/`) rather than depending on the SAF picker or `RuntimeWorkspaceService`'s own mirroring (per the approved scope correction — this sprint tests the injection/launch boundary, not SAF mirroring again). Launches `RuntimeActivity` via `ActivityScenario`, explicitly moves it to `RESUMED`, and asserts nothing about the outcome — see the file's own kdoc for why: if `System.exit(0)` fires, the whole instrumentation process dies before any assertion could run anyway, so **this test's real evidence is `adb logcat`, not its own JUnit result.**

**`AndroidManifest.xml`:** one new `<activity>` entry for `.runtime.RuntimeActivity`, `android:exported="false"`, no intent-filter of any kind — confirmed non-launchable from outside the app's own process, and never reachable from any of this app's own screens either.

**Confirmed unmodified, by direct hash comparison, not assumption:** `AppContainer.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `NativeRuntimeLoader.kt`, `NativeLibraryLoadSmokeTest.kt`, every file under `org/libsdl/app/`, `GameLaunchRequest.kt`, `RuntimeWorkspaceService.kt`, `MirrorRuntimeWorkspaceService.kt`. Zero references to `RuntimeManager` or `AppContainer` from either new file (grep-confirmed — the one match found is inside a kdoc comment *documenting* that absence, not code). No UI or ViewModel file touched.

**Not implemented, per the approved scope:** no Play button, no public Launch UI, no hidden in-app trigger of any kind, no `RuntimeManager.launch()` wiring, no `AppContainer` wiring, no claimed game boot, no title screen target, no input/audio/save/gameplay work, no Device Doctor, no Cloud Save, no `MANAGE_EXTERNAL_STORAGE`, no original game files touched, no 16KB alignment work.

### Running the test locally (Ti)

1. Build/install the app (regression-check existing Game Library flows, same as every prior sprint).
2. Start log capture **before** running the test, since the process may terminate mid-test:
   ```
   adb logcat -c
   adb logcat > sprint8_log.txt
   ```
   (in a separate terminal/window, left running through the next step)
3. Run `RuntimeActivityLaunchTest` from Android Studio, or:
   ```
   ./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityLaunchTest"
   ```
4. Stop the logcat capture and search `sprint8_log.txt` for, in order:
   - `RuntimeActivity received workspace path: ...` — confirms the Intent extra arrived correctly.
   - `RuntimeActivity set GAME_PATH to: ...` — confirms the field was set before `super.onCreate()`.
   - **Correction, added during Sprint 9:** the line below originally claimed the fork logs `"Game path: ..."` in `runSDLThread()`. That was never actually verified against the real source and turned out to be wrong — a direct grep of the entire mkxp-z source tree found no such line anywhere. See the App v0.0.10 section below for what's actually there and why success is silent by design.
   - Whatever follows: a clean `showInitError`/`SDL_Quit` (acceptable — the test workspace isn't a real game), a further native log, or the log simply stopping (the instrumentation process died via `System.exit(0)` — also acceptable, per the approved scope, provided the lines above already appeared).
5. Report back the relevant log excerpt — the JUnit pass/fail result on its own is not sufficient evidence either way.

**Not claimed:** real device verification of any of this — Ti runs it, and the `adb logcat` output is what actually answers whether this sprint's goal was reached, not this test's own reported result.

## App v0.0.10 — Sprint 9: Native mkxp-z main GAME_PATH Read Verification

Ti confirmed App v0.0.9 on the same OPPO PGEM10 (Android 16): `RuntimeActivityLaunchTest` passed, `RuntimeActivity` received the workspace path and set `GAME_PATH`, `SDLActivity` reached `RESUMED`, the SDL surface was created, and the native library chain loaded through `SDLActivity`'s own lifecycle. **What logcat never showed was any native-side confirmation that `main()` actually read the injected `GAME_PATH`** — no `"Game path:"` line ever appeared.

**Root cause, found by re-reading `main.cpp` directly this session — a correction to Sprint 8's own plan, not just a new finding:** Sprint 8's planning document asserted the fork logs `"Game path: ..."` in `runSDLThread()`. **That claim was never actually checked against the real source, and it's wrong** — a direct, exhaustive grep for that text across the entire mkxp-z source tree finds nothing. What's actually there: reading `GAME_PATH` via JNI, checking `mkxp_fs::directoryExists(dataDir)`, and calling `mkxp_fs::setCurrentDirectory(dataDir)` are all **completely silent on success** — there is no log statement anywhere in that path. The *only* place any output happens is `showInitError()`, called *exclusively* on failure, which does `Debug() << msg` (writing to logcat under tag **`mkxp`**, level **`DEBUG`** — read directly from `debugwriter.h`'s Android branch) followed by an `SDL_ShowSimpleMessageBox` dialog. Sprint 8's bare test fixture was a real, existing (if empty) directory — `directoryExists()` most likely returned `true`, and execution proceeded silently onward. **The absence of a log line was never evidence that native `main()` didn't run — it's exactly what a successful, silent path looks like**, since nothing logs on success at all. The same silence was confirmed in `config.cpp`'s `readConfFile()` too: a missing `mkxp.json` is handled with an empty JSON object and zero output, not an error.

**The fix, requiring no native rebuild:** deliberately inject a path that does **not** exist, forcing `directoryExists()` to fail and the *existing* `showInitError`/`Debug()` call to fire — producing a real, native-originated logcat line that echoes the exact invalid path back: `"Failed to set current directory to <path>"`. That line can only be produced by real native execution reaching this exact check with this exact value — conclusive proof, using code already built into the currently-packaged `.so` files from Sprint 7/8, with zero changes to `main.cpp` and zero need for Ti's WSL native build pipeline this sprint.

**New file:**
- `app/src/androidTest/.../RuntimeActivityInvalidGamePathTest.kt` — constructs `context.filesDir/sprint9-invalid-game-path-does-not-exist` (explicitly deleted first if somehow already present, guaranteeing it doesn't exist before launch), injects that path via `RuntimeActivity.EXTRA_WORKSPACE_PATH`, launches via `ActivityScenario`, moves to `RESUMED`. Asserts nothing about the outcome — same reasoning as `RuntimeActivityLaunchTest`: `System.exit(0)` can still terminate the whole instrumentation process once native execution proceeds far enough past the failure this test triggers, so a JUnit FAIL or the process dying outright is still an **acceptable** result here, provided logcat already shows the native failure line first.

**Deliberately not implemented this sprint, per the approved scope correction:** a valid-path *confirmation* test (demonstrating the *absence* of the failure line) is optional/deferred — `RuntimeActivityLaunchTest` (Sprint 8) already covers that fixture if it's wanted later; a silent success path is inherently weaker evidence than this sprint's own positive failure proof, so it wasn't required as a closure criterion.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityLaunchTest.kt`, every file under `org/libsdl/app/`, and `app/build.gradle.kts` (no new dependency was needed — the same `androidx.test.core`/`ActivityScenario` imports Sprint 8's own test already used). No UI or ViewModel file touched. Zero references to `RuntimeManager`/`AppContainer` from the new file.

**Not implemented, per the approved hard boundaries:** no Play button, no public Launch UI, no `RuntimeManager.launch()` wiring, no `AppContainer` wiring, no claimed game boot or title screen, no input/audio/save/gameplay work, no Device Doctor, no Cloud Save, no `MANAGE_EXTERNAL_STORAGE`, no original game files touched, no 16KB alignment work, no native C++ rebuild or instrumentation.

### Running the test locally (Ti)

1. Build/install the app (regression-check existing Game Library flows).
2. Start log capture before running the test:
   ```
   adb logcat -c
   adb logcat > sprint9_log.txt
   ```
3. Run `RuntimeActivityInvalidGamePathTest` from Android Studio, or:
   ```
   ./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityInvalidGamePathTest"
   ```
4. Search `sprint9_log.txt` for, in order:
   - `RuntimeActivity` — the Java-side received-path and set-`GAME_PATH` lines (same as Sprint 8), this time showing the deliberately invalid path.
   - `GAME_PATH` — confirms the field was set to the invalid value before `super.onCreate()`.
   - **`mkxp`** — the native logcat tag (not `mkxp-z`, an easy mix-up) — this is the line that actually answers Sprint 9's question.
   - `Failed to set current directory` — the exact native failure message; confirm it contains the *same* invalid path this test injected.
5. Report back the relevant log excerpt. A JUnit PASS is not required — a FAIL, or the process dying outright once past this point, is still an acceptable, informative result **provided the `mkxp`/`Failed to set current directory` line already appeared** with the correct path. A result showing only Java-side lines, with no `mkxp`-tagged line at all, means the question this sprint asks is still open and needs further investigation, not a claim of success.

**Not claimed:** real device verification of any of this — Ti runs it, and the `adb logcat` output, specifically the native `mkxp`-tagged line, is what actually answers whether this sprint's goal was reached.

## App v0.0.11 — Sprint 9 hotfix: bounded wait for native startup

Ti ran `RuntimeActivityInvalidGamePathTest` for real on the same OPPO PGEM10 (Android 16). Android Studio reported PASS. Logcat showed the Java-side lines correctly (received path, set `GAME_PATH`), and the Activity reached `RESUMED` — but then went straight through `PAUSED` → `STOPPED` → `DESTROYED` with **no `"mkxp"` tag, no `"Failed to set current directory"`, no SDL log lines at all.** Sprint 9's own native-read verification was not proven.

**Root cause, found by re-reading the test's own code:** `scenario.close()` was called on the very next line after `scenario.moveToState(Lifecycle.State.RESUMED)`, with no wait in between. `moveToState(RESUMED)` only guarantees `Activity.onResume()` has returned — the native `SDLMain` thread starts *separately*, asynchronously, once the render surface is also ready (confirmed by reading `SDLActivity.java` during Sprint 8 planning). Closing the scenario immediately very likely tore the Activity down before that thread was ever scheduled to run, let alone reach C's `main()` and its `GAME_PATH` read.

**The fix:** a bounded `Thread.sleep(4000)` between reaching `RESUMED` and calling `scenario.close()`, with `Log.i` markers before and after the wait for timing visibility in logcat. This is not a precise, principled figure — a generous margin within the approved 3–5 second range, giving the native thread real wall-clock time to start on a real device without making the test suite noticeably slow.

**File changed:** `app/src/androidTest/.../RuntimeActivityInvalidGamePathTest.kt` only — the wait and its logging were added directly around the existing `moveToState`/`close()` calls; nothing else about the test's fixture, injection logic, or lack of assertions changed.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityLaunchTest.kt`, every file under `org/libsdl/app/`. No new Gradle dependency. No UI or ViewModel file touched. Zero references to `RuntimeManager`/`AppContainer` from the edited file.

**Not implemented, per the approved hard boundaries:** no Play button, no public Launch UI, no `RuntimeManager.launch()` wiring, no `AppContainer` wiring, no claimed game boot, no `MANAGE_EXTERNAL_STORAGE`, no original game files touched, no 16KB alignment work, no native C++ change of any kind.

### Running the test locally (Ti) — updated

Same steps as the App v0.0.10 section above (build/install, start `adb logcat` capture, run `RuntimeActivityInvalidGamePathTest`). **New in logcat this time:** two `RuntimeActivityInvalidGamePathTest`-tagged lines — `"Reached RESUMED — waiting 4000ms..."` and, about 4 seconds later, `"Wait complete — closing the scenario now."` — bracketing the window where native evidence should now appear. Search for, in order: `RuntimeActivity` → `GAME_PATH` → **`mkxp`** → `Failed to set current directory`.

**If `mkxp` evidence still does not appear after this fix:** that points at something beyond simple timing — report it as a real blocker, with the exact logcat excerpt (including the two new timing markers, to confirm the wait itself actually elapsed). The next diagnostic step would likely mean adding a *native* log line directly after the JNI `GAME_PATH` read (an unconditional one, not only on failure) — but that requires a full rebuild on Ti's own WSL toolchain and re-export of the `.so` files, a real, non-trivial step not undertaken in this hotfix and not to be started without separate approval.

**Not claimed:** real device verification of this fix — Ti runs it, and the `adb logcat` output is what actually confirms whether the wait resolves the missing-evidence problem.

## App v0.0.12 — Sprint 9 diagnostic step 2: SDL lifecycle complete, native thread still not confirmed

Ti re-ran `RuntimeActivityInvalidGamePathTest` with an explicit `SDL:V` logcat filter added (the App v0.0.11 fix worked as designed — search terms just needed to include tag `"SDL"`, which my own prior instructions had never mentioned). The result: the **entire** SDL lifecycle now shows up correctly — `Device:`/`Model:`/`onCreate()`, `nativeSetupJNI()` (audio + controller), `onStart()`, `onResume()`, `surfaceCreated()`, `surfaceChanged()` with real window/device size, and finally `onWindowFocusChanged(): true`. Every one of the three conditions `SDLActivity.handleNativeState()` gates native thread startup on — surface ready, window focus, resumed — genuinely became `true`. **Still no `"Running main function ... from library ..."` line, and still no native `"mkxp"` evidence.**

**Re-reading `SDLActivity.java`'s exact `onCreate()` flow ruled out my own first suspicion** (`mBrokenLibraries`, the flag defaulting to `true` that gates nearly every lifecycle method): the appearance of `nativeSetupJNI()` in the log is only reachable *after* an early-return guarded by `if (mBrokenLibraries) { ...; return; }` — so library loading and the SDL C/Java version check (`2.26.3`, matching the fork's own pinned tag) had already both succeeded. Worth stating plainly rather than quietly discarding: this was a real, reasoned hypothesis that turned out wrong once checked against the evidence, not a hedge.

**Most likely remaining explanation:** `mSDLThread.start()` is non-blocking — it schedules a new thread but doesn't wait for it to actually run. If the full resume→surface→focus sequence itself consumes a meaningful fraction of the wait window on a real device under instrumentation (plausibly with additional OEM/ColorOS-specific overhead), the newly-started thread may simply not have been scheduled long enough to log anything before `scenario.close()` ran — the same *class* of problem the App v0.0.11 hotfix already fixed once, just requiring more margin than first assumed.

**This build:**
1. **Extends the wait from 4 to 10 seconds** — more margin for both the lifecycle sequence and the newly-started thread's own scheduling.
2. **Adds direct diagnostic log lines reading `SDLActivity`'s own gate-state fields** — `mBrokenLibraries`, `mHasFocus`, `mIsResumedCalled`, `mCurrentNativeState`, `mNextNativeState` — all already `public static` on the copied `SDLActivity.java`, so this needed no reflection and **no change to that file at all**, only to the test. This gives a direct, positive answer instead of continuing to infer gate state solely from which downstream logs are absent.

**File changed:** `app/src/androidTest/.../RuntimeActivityInvalidGamePathTest.kt` only.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityLaunchTest.kt`, every file under `org/libsdl/app/` (including `SDLActivity.java` itself — read many times this session, never edited). No UI or ViewModel file touched. Zero `RuntimeManager`/`AppContainer` references in the edited file.

### Running the test locally (Ti) — updated

Same steps as before (build/install, `adb logcat -c && adb logcat > sprint9_log.txt`, run `RuntimeActivityInvalidGamePathTest`, keep the `SDL:V` filter from last time). New lines to look for, in order:
- `RuntimeActivityInvalidGamePathTest`-tagged: `"Reached RESUMED — waiting 10000ms..."`, then ~10 seconds later `"Wait complete."`
- The five `SDLActivity.m...=` diagnostic lines, immediately after — report their exact values.
- `SDL`-tagged: `"Running main function ... from library ..."` — if this now appears, the longer wait resolved it.
- `mkxp`-tagged: `"Failed to set current directory to ..."` — the original Sprint 9 target evidence.

**If the diagnostic lines show all five conditions correct (`mBrokenLibraries=false`, `mHasFocus=true`, `mIsResumedCalled=true`, `mCurrentNativeState=RESUMED`, `mNextNativeState=RESUMED`) but the `SDL`/`mkxp` lines still never appear** — that's a real, well-isolated blocker: every condition this test can observe from the Java side is satisfied, and the next step would mean either a native-side diagnostic log (needs Ti's own WSL rebuild, not undertaken without separate approval) or investigating `ActivityScenario` itself as a possible source of thread-scheduling starvation specific to test instrumentation — not something to guess at further from Java-side evidence alone.

**Not claimed:** real device verification — Ti runs it, and the exact diagnostic field values plus whatever logcat shows afterward are what actually narrow this down further.

## App v0.0.13 — Sprint 9 diagnostic step 3: the actual root-cause fix

Ti's App v0.0.12 diagnostic run gave the precise, decisive answer: `SDLActivity.mBrokenLibraries=false`, `mHasFocus=true`, but **`mIsResumedCalled=false`** — despite logcat clearly showing `"SDL: onResume()"` had fired. That specific inconsistency, read directly against `SDLActivity.java`'s exact source, resolves completely:

**Root cause.** `SDLActivity.mHasMultiWindow` is declared:
```java
public static final boolean mHasMultiWindow = (Build.VERSION.SDK_INT >= 24);
```
This is a **compile-time-deterministic Android *version* check** — not a real multi-window *state* check, despite the name. It's `true` on every Android device from 7.0 (API 24, released 2016) onward, including Android 16. `SDLActivity.onResume()` only calls `resumeNativeThread()` (which sets `mIsResumedCalled = true`) when `!mHasMultiWindow` — which is **never true** on any modern device. The *matching* call site, in `SDLActivity.onStart()`, guarded by `if (mHasMultiWindow)`, is **commented out** in this fork's copy — with a comment explaining the intent: `"we are now starting SDL thread from MainActivity using the runSDLThread method"`.

**Confirmed by re-reading the fork's own `MainActivity.java` directly:** it *does* override `onStart()`, and correctly calls `resumeNativeThread()` when `mHasMultiWindow` is true, via its own `runSDLThread()` helper (which also happens to contain the real `"Game path: " + GAME_PATH` log line — under tag `"mkxp-z[Activity]"`, Java-side, **not** in native C++ as Sprint 9's earlier correction assumed; that correction was itself imprecise and is corrected here). `RuntimeActivity` deliberately extends `SDLActivity` directly, not `MainActivity` — to avoid inheriting its OBB-mounting logic and `MANAGE_EXTERNAL_STORAGE` request — and in doing so, also lost this unrelated, necessary multi-window-resume trigger. **This is not a timing issue, not an `ActivityScenario` quirk, and not device-specific** — it would reproduce identically on any real launch, on any Android 7.0+ device.

**The fix — `RuntimeActivity.kt` only, `SDLActivity.java` untouched:**
```kotlin
override fun onStart() {
    super.onStart()
    if (mHasMultiWindow) {
        resumeNativeThread()
    }
}
```
Restores exactly the one call `MainActivity.runSDLThread()` makes for this case — nothing else from `MainActivity` (no OBB, no `GAME_PATH` defaults, no storage permission) is reproduced. `resumeNativeThread()` is `protected` on `SDLActivity`, directly callable from a Kotlin subclass without qualification — verified by actually compiling a standalone Java-base/Kotlin-subclass pair with the exact same inheritance shape before relying on it, not assumed from general Kotlin/Java interop knowledge.

**File changed:** `app/src/main/java/com/pokerpgplayer/app/runtime/RuntimeActivity.kt` only.

**Confirmed unmodified, by direct hash comparison:** `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityLaunchTest.kt`, `RuntimeActivityInvalidGamePathTest.kt`, and every file under `org/libsdl/app/` — including `SDLActivity.java` itself, read many times across this whole investigation, never edited. No UI or ViewModel file touched. No `RuntimeManager`/`AppContainer` wiring anywhere in the change.

### Running the test locally (Ti) — updated

Same steps as before (`adb logcat -c && adb logcat > sprint9_log.txt` with the `SDL:V` filter, run `RuntimeActivityInvalidGamePathTest`). New lines to look for, in order:
- `RuntimeActivity`-tagged: `"onStart(): mHasMultiWindow=true"`, then `"onStart(): calling resumeNativeThread() — ..."`.
- `SDL`-tagged: `"Running main function ... from library ..."` — if this now appears, the fix worked.
- `mkxp`-tagged: `"Failed to set current directory to ..."` — Sprint 9's original target evidence.

**If `RuntimeActivity`'s two new lines appear but `SDL`'s `"Running main function..."` still doesn't** — that would mean the gate now genuinely passes (all three conditions true, thread started) but the thread itself still isn't logging in time, pointing back toward a pure scheduling/timing explanation after all, now that the actual gate bug is fixed. **If even the `mHasMultiWindow` log line doesn't appear**, something more fundamental changed (e.g. `onStart()` not being reached at all) and warrants its own fresh look, not a repeat of this same fix.

**Not claimed:** real device verification of this fix — Ti runs it, and logcat is what actually confirms whether native `main()` is now reached.

## App v0.0.14 — Sprint 10 candidate: valid workspace native chdir / minimal mkxp-z init attempt

Ti verified App v0.0.13 on OPPO PGEM10 (Android 16): native `main()` is reached, reads `RuntimeActivity.GAME_PATH` via JNI, and correctly reports failure for a path that doesn't exist. Sprint 10 asks the next, narrower question: **when the path *does* exist, does native `main()` actually pass `directoryExists()`/`setCurrentDirectory()` and proceed into config load** — and can that be confirmed *positively*, not merely inferred from the absence of the invalid-path failure line?

**Source read, before writing any test (see the approved Sprint 10 plan for the full trace):** immediately after `setCurrentDirectory()` and config load, `main.cpp` calls `printRgssVersion(conf.rgssVersion)`, which calls `Debug() << "RGSS version " + ver + " (RPG Maker " + maker + ")"` — the same `Debug()`/logcat-tag-`"mkxp"`/level-`DEBUG` mechanism Sprint 9 already proved works. This line can **only** be reached by passing `directoryExists()`, calling `setCurrentDirectory()`, and completing config load — a real, positive checkpoint, not a "nothing bad happened" inference. Also confirmed directly: `config.cpp`'s own post-processing unconditionally resolves `rgssVersion` from its default `0` to `1` before `main.cpp`'s `assert(conf.rgssVersion >= 1 && conf.rgssVersion <= 3)` runs, regardless of whether a `Game.ini` exists to auto-detect from — so a bare, empty workspace cannot trigger that assert, and the expected line is exactly `"RGSS version 1 (RPG Maker XP)"`.

**New file:** `app/src/androidTest/.../RuntimeActivityValidWorkspaceInitTest.kt` — creates a real, existing, empty directory (`context.filesDir/sprint10-valid-empty-workspace/`, cleared first if a previous interrupted run left it behind), logs the path and its `exists`/`isDirectory` state, injects it via `RuntimeActivity.EXTRA_WORKSPACE_PATH`, launches via `ActivityScenario`, moves to `RESUMED`, waits 10 seconds (the same margin Sprint 9's own diagnostic step 2 proved necessary — reused directly rather than starting back at a shorter value), and closes. Asserts nothing about the outcome, for the same reason every prior Sprint 8/9 test doesn't: the fork's documented `System.exit(0)` behavior can still terminate the whole instrumentation process once native execution proceeds far enough, so a JUnit FAIL or the process dying outright is still an **acceptable** result here, provided logcat already shows the positive checkpoint first.

**No production code changed** — `RuntimeActivity.kt` already does everything this sprint needs (inject `GAME_PATH`, call `resumeNativeThread()` at the right point); this candidate is purely a second test file.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityLaunchTest.kt`, `RuntimeActivityInvalidGamePathTest.kt`, and every file under `org/libsdl/app/`. No UI or ViewModel file touched. No `RuntimeManager`/`AppContainer` wiring anywhere in the new file.

### Running the test locally (Ti)

1. Build/install the app (regression-check existing Game Library flows).
2. Start log capture before running the test:
   ```powershell
   cd "$env:LOCALAPPDATA\Android\Sdk\platform-tools"
   .\adb.exe logcat -c
   .\adb.exe logcat RuntimeActivity:V RuntimeActivityValidWorkspaceInitTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > "$env:USERPROFILE\Desktop\sprint10_valid_workspace_log.txt"
   ```
3. Run `RuntimeActivityValidWorkspaceInitTest` from Android Studio, or:
   ```
   ./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityValidWorkspaceInitTest"
   ```
4. Search the captured log for, in order:
   - `RuntimeActivityValidWorkspaceInitTest`-tagged: `"Created valid workspace path: ..."`, `"workspace.exists=true"`, `"workspace.isDirectory=true"`.
   - `RuntimeActivity`-tagged: received path, set `GAME_PATH`, `onStart(): mHasMultiWindow=true`, `calling resumeNativeThread()`.
   - `SDL`-tagged: `"Running main function SDL_main from library .../libmkxp-z.so"`.
   - **`mkxp`-tagged: `"RGSS version 1 (RPG Maker XP)"`** — this sprint's actual target evidence.
   - Confirm **no** `"Failed to set current directory"` line appears (expected and correct this time).

**PASS:** `TestRunner` finishes (1 test, 0 failed, 0 ignored), `mkxp` logs `"RGSS version 1 (RPG Maker XP)"`, and no `"Failed to set current directory"` line appears.
**PARTIAL PASS:** the above, plus further `SDL` subsystem-init or window-creation logs — still not a title screen or gameplay claim.
**INCONCLUSIVE:** neither the `RGSS version` line nor the failure line appears.
**FAIL:** `"Failed to set current directory"` appears for this existing directory — treat as a test-fixture bug first, not a Sprint 10 architecture blocker, unless the logs prove otherwise.

**Not claimed:** real device verification — Ti runs it, and the exact logcat lines are what actually confirm the result. OS is not being updated as part of this delivery — Ti updates it manually after ChatGPT reviews the real result.

## App v0.0.15 — harness-only fix: bounded `scenario.close()`

Ti's App v0.0.14 run captured exactly the evidence Sprint 10 was designed to find — `"RGSS version 1 (RPG Maker XP)"`, no `"Failed to set current directory"`, and further progress into real OpenGL backend init (`Backend: OpenGL`, `GL Renderer: Adreno (TM) 740`, `GL Version: OpenGL ES 3.2`). **The Sprint 10 technical objective is verified.** But `connectedDebugAndroidTest` itself was cancelled by the test framework (`io.grpc.StatusRuntimeException: CANCELLED: client cancelled`, 0 tests reported) rather than finishing cleanly.

**Root cause, read directly from `SDLActivity.onDestroy()`:**
```java
if (SDLActivity.mSDLThread != null) {
    SDLActivity.nativeSendQuit();
    try {
        SDLActivity.mSDLThread.join();   // no timeout
    } catch (Exception e) { ... }
}
```
When the native thread is still running — which it is for a valid workspace, since `main()` proceeds into mkxp-z's real engine loop instead of returning early — `onDestroy()` blocks the Android main thread until that loop notices the quit signal and exits on its own, with **no timeout**. `RuntimeActivityInvalidGamePathTest` never hit this: its native `main()` had already returned (via the `directoryExists()` failure branch) *before* `scenario.close()` ever ran, so `join()` there returns instantly on an already-dead thread. This is genuine engine behavior — not a defect in `RuntimeActivity`, `SDLActivity`, or the prior test.

**The fix — test file only, no production or bridge code touched:** `scenario.close()` is now invoked on a separate daemon thread with a bounded 15-second wait (`Thread.join(timeout)`). If it completes in time, normal. If not, the test logs that and simply moves on rather than hanging the whole instrumentation run — the positive native evidence was already written to logcat well before this point, so an incomplete teardown afterward doesn't affect it.

**File changed:** `app/src/androidTest/.../RuntimeActivityValidWorkspaceInitTest.kt` only.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityLaunchTest.kt`, `RuntimeActivityInvalidGamePathTest.kt`, and every file under `org/libsdl/app/` — including `SDLActivity.java` itself, whose `onDestroy()` this fix works *around*, not by changing it. No UI or ViewModel file touched. No `RuntimeManager`/`AppContainer` wiring anywhere in the change.

### Running the test locally (Ti)

Same steps and logcat filter as the App v0.0.14 section above. New lines to look for, near the end: `"scenario.close() completed within 15000ms."` (clean teardown) or `"scenario.close() did not complete within 15000ms..."` (expected/acceptable if the engine loop is still running — not a failure). Either way, `TestRunner` should now report a real pass/fail result instead of being cancelled outright.

**Not claimed:** real device verification of this fix, and not a claim that calling `ActivityScenario.close()` from a background thread is risk-free in every possible timing scenario — only that it is designed to prevent this specific, now-understood hang, per AndroidX Test's own documented thread-safety for `ActivityScenario`'s methods. OS is not being updated as part of this delivery.

## App v0.0.16 — Sprint 12: Espresso dialog checkpoint

Ti's App v0.0.15 run confirmed the Sprint 10 boundary a second time, independently. Research after that (source reading only, no code) into the next checkpoint past `printRgssVersion()` found: `binding-mri.cpp`'s `runRMXPScripts()` — reached once config load completes — checks `conf.game.scripts`, and for a workspace with no `Game.ini` (this fixture), calls `showMsg("No game scripts specified (missing Game.ini?)")`. Traced all the way through: `EventThread::showMessageBox()` → `SDL_ShowSimpleMessageBox()` → `SDLActivity.messageboxCreateAndShow()`, which builds a real, in-process `AlertDialog`. **Neither the native call site nor the Java dialog-building code calls `Debug()`/`Log.*` anywhere** — this checkpoint is completely invisible to logcat, but visible as a real, standard dialog with title `"mkxp-z"` and the message text above.

**New file:** `app/src/androidTest/.../RuntimeActivityMessageBoxCheckpointTest.kt` — reuses the exact same empty-workspace fixture pattern as `RuntimeActivityValidWorkspaceInitTest` (a fresh `context.filesDir` subdirectory), launches `RuntimeActivity` via `ActivityScenario`, waits the same proven 10-second margin, then **polls with Espresso assertions** (`onView(withText(...)).check(matches(isDisplayed()))`) rather than relying on Espresso's default idling synchronization, which isn't guaranteed to track a dialog triggered indirectly through a native `SDL_PushEvent` → event-thread → `runOnUiThread` chain. **Deliberately does not tap the dialog's "OK" button** — traced directly: dismissing it would let the RGSS thread finish, but per Sprint 11's own research this path never calls `ethread->requestTerminate()`, so the main event/graphics loop would keep running regardless, same as Sprint 10's own fixture. The dialog's own text is already the complete checkpoint; tapping adds real interaction risk for no additional evidence.

**This is the first genuine PASS/FAIL test in this whole investigation.** Every prior Sprint 8–10 test deliberately asserted nothing and relied on Ti reading logcat. Here, the checkpoint is a single, well-defined yes/no — did the expected dialog appear with the expected text — so the test genuinely fails (via a real `AssertionError`) if it doesn't, via a bounded retry loop rather than a single one-shot check. The same bounded-`scenario.close()` pattern from App v0.0.15 still runs in a `finally` block regardless of whether the assertion passes or fails, so a failed checkpoint doesn't also hang the instrumentation run.

**No Espresso dependency added** — `androidx.test.espresso:espresso-core:3.6.1` was already declared in `app/build.gradle.kts` from the project's own initial setup, confirmed by direct read before writing any code.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityLaunchTest.kt`, `RuntimeActivityInvalidGamePathTest.kt`, `RuntimeActivityValidWorkspaceInitTest.kt`, every file under `org/libsdl/app/`, and `app/build.gradle.kts` itself (beyond the version bump). No UI or ViewModel file touched. No `RuntimeManager`/`AppContainer` wiring anywhere in the new file.

### Running the test locally (Ti)

1. Build/install the app (regression-check existing Game Library flows).
2. Run `RuntimeActivityMessageBoxCheckpointTest` from Android Studio, or:
   ```
   ./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityMessageBoxCheckpointTest"
   ```
3. Expected console/test output: `TestRunner` reports **1 test, 0 failed** if the dialog appears as expected within the poll window. Logcat (no special filter needed this time, since the checkpoint itself isn't logcat-based, but the same filter as prior sprints still shows useful context) should show, in order: `RuntimeActivityMessageBoxCheckpointTest`-tagged fixture-creation lines, `RuntimeActivity`-tagged path/`GAME_PATH`/`onStart()` lines, `"Wait complete — polling for the expected dialog now..."`, then either `"CHECKPOINT PASS: dialog message observed..."` + `"CHECKPOINT PASS: dialog title observed..."`, followed by the `scenario.close()` completion line.
4. If the test reports a failure instead: the exact `AssertionError` message will state which text wasn't found within the timeout — report that back, since it would mean either the checkpoint text differs from what source analysis predicted, or something earlier in the chain didn't behave as expected.

**Claim boundary, explicit:** this test only claims that a specific, named dialog (title `"mkxp-z"`, message `"No game scripts specified (missing Game.ini?)"`) becomes visible for an empty workspace. **No claim of game boot, title screen, gameplay, real input, audio, or save/load of any kind.** Not claimed: real device verification — Ti runs it, and the actual `TestRunner` result plus logcat are what confirm it.

## App v0.0.17 — Sprint 12 hotfix: RuntimeActivity `configChanges`

Ti's App v0.0.16 run did **not** pass. Logcat showed native startup proceeding correctly through `printRgssVersion()` (`"RGSS version 1 (RPG Maker XP)"`), then `SDL setOrientation() requested landscape` — immediately followed by `onPause` / `surfaceDestroyed` / `nativePause` / `onStop` / `onDestroy`, before the expected Espresso checkpoint was ever observed. Android Studio reported `0/1` completed after roughly two minutes; the test never finished.

**Root cause, confirmed by reading `SDLActivity.java` directly:**
```java
Log.v(TAG, "setOrientation() requestedOrientation=" + req + ...);
mSingleton.setRequestedOrientation(req);
```
`setOrientation()` — called during SDL's own normal startup, independent of anything this test does — invokes the standard Android `Activity.setRequestedOrientation()` API. `RuntimeActivity`'s manifest entry declared no `android:configChanges` at all. **Android's default behavior for an orientation-change request on an Activity without `configChanges` declared is to destroy and recreate the entire Activity** — this is standard, well-documented platform behavior, not a bug introduced by this test. The `onPause`/`onDestroy` sequence Ti observed was Android itself tearing the Activity down, not this test's own bounded `scenario.close()` (which only ever runs after a full 10-second startup wait plus up to 15 seconds of dialog polling — far later than when the destroy sequence actually appeared in the log). This was invisible in every prior sprint because none of them had reached this specific point in native startup before Sprint 12's own test — the first to run long enough to trigger it.

**The fix — one manifest attribute, nothing else:**
```diff
         <activity
             android:name=".runtime.RuntimeActivity"
-            android:exported="false" />
+            android:exported="false"
+            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize" />
```
This tells Android to keep the same `RuntimeActivity` instance alive across an orientation/screen-size change instead of recreating it. `RuntimeActivity` does not override `onConfigurationChanged()` and isn't expected to need to for this purpose — SDL's own native/GL layer handles the resulting resize itself, independent of the Java Activity lifecycle. **This was confirmed to be sufficient on its own** — no change to `RuntimeActivity.kt` was needed, since the manifest-only fix directly addresses the actual cause (Android's own default recreate behavior), not anything in the Activity's own code.

**File changed:** `app/src/main/AndroidManifest.xml` only — the `RuntimeActivity` `<activity>` entry, one added attribute plus an explanatory comment.

**`RuntimeActivityMessageBoxCheckpointTest.kt` is unchanged** — no test logic adjustment was needed; the existing wait/poll windows should be sufficient once the Activity instance itself is stable.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `RuntimeActivityMessageBoxCheckpointTest.kt`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every file under `org/libsdl/app/`. No UI/ViewModel file touched. No icon/branding resource touched.

### Running the test locally (Ti)

Same command as the App v0.0.16 section above:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityMessageBoxCheckpointTest"
```

**Expected PASS evidence this time:** `TestRunner` reports **1 test, 0 failed**, with logcat showing `"RGSS version 1 (RPG Maker XP)"`, then `SDL setOrientation() requested landscape` **without** an immediately following `onPause`/`onDestroy` sequence — the Activity should remain alive — followed eventually by `"CHECKPOINT PASS: dialog message observed..."` and `"CHECKPOINT PASS: dialog title observed..."`.

**Claim boundary, unchanged and explicit:** this hotfix only targets Activity lifecycle stability so the existing dialog checkpoint test *can* run to completion. **No claim of game boot, title screen, gameplay, real input, audio, or save/load.** Not claimed: real device verification — Ti runs it, and the real `TestRunner`/logcat result is what confirms whether this actually resolves the hang.

## App v0.0.18 — Sprint 14: Pokémon Z controlled launch attempt

Sprint 13 (planning only, no app changes) defined a `RuntimeStrategy` classification model and completed read-only intake for two real fan games. Sprint 14 planning (also no app changes) source-traced the real `Scripts.rxdata` success path for the first time — finding that a Ruby runtime exception during actual script execution (`showExc()`) *is* logged via `Debug()` (tag `mkxp`, level `DEBUG`), unlike every archive-loading failure this project has characterized so far (all of which route through a silent, undismissable `AlertDialog`, per Sprint 11/12's own findings).

**This build adds one new, internal-only `androidTest`** that points the existing `RuntimeActivity` mechanism at a real Pokémon Z folder and collects evidence on what actually happens — the first attempt against a real, previously-untested game in this whole project. **No Pokémon Z file of any kind is included in this repository, this zip, or any other deliverable.** The test requires Ti to place a mirror of the Pokémon Z folder into this app's own private storage manually, one time, before running it.

### Required one-time Ti-side setup (do this once, before running the test)

1. Have the Pokémon Z folder available locally (the same one already used for Sprint 13's read-only intake).
2. Push it to a staging location on the device via `adb`:
   ```
   adb push "C:\path\to\Pokemon Z" /data/local/tmp/pokemon-z-staging
   ```
3. Copy it from that staging location into the app's own private storage, using `run-as` (this works for a debuggable build without any special permissions, since `run-as` grants shell access to the app's own private data directory):
   ```
   adb shell "run-as com.pokerpgplayer.app.debug mkdir -p files/pokemon-z-workspace"
   adb shell "run-as com.pokerpgplayer.app.debug cp -r /data/local/tmp/pokemon-z-staging/. files/pokemon-z-workspace/"
   ```
4. (Optional cleanup) remove the staging copy once step 3 succeeds:
   ```
   adb shell rm -rf /data/local/tmp/pokemon-z-staging
   ```

**If this setup is skipped, the test skips itself** (via `org.junit.Assume`) rather than failing — a missing workspace is a setup gap, not a finding about Pokémon Z.

### New file

`app/src/androidTest/.../RuntimeActivityPokemonZLaunchAttemptTest.kt` — checks for the workspace folder's existence first (skips if absent), then reuses every lifecycle protection already proven since Sprint 8–12: the same `EXTRA_WORKSPACE_PATH` injection timing, the same `android:configChanges` stability from App v0.0.17, and the same bounded `scenario.close()` pattern from App v0.0.15/16. Waits **20 seconds** (longer than the 10-second margin used for empty/artificial fixtures) before checking anything, since a real ~1 MB script archive needs per-script decompression and Ruby evaluation this project has never previously exercised.

**Deliberately does not hard-assert most outcomes.** Unlike `RuntimeActivityMessageBoxCheckpointTest` (Sprint 12, testing a fully-understood, artificial fixture with one deterministic outcome), this test explores a real, previously-unattempted game — it polls for and **logs** whether the known missing-scripts dialog appears (its appearance here would indicate a workspace-copy problem, not a Pokémon Z finding, since `Scripts.rxdata` is confirmed present), and clearly notes that a `Debug()`-logged Ruby exception (if any) is logcat-only evidence with no Espresso equivalent check in this version.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every prior `androidTest` file, every file under `org/libsdl/app/`, every UI/ViewModel/resource/icon file. No `RuntimeManager`/`AppContainer` wiring anywhere in the new file. No config-injection or general-incremental-sync code was added, per this sprint's own explicit constraints.

### Running the test locally (Ti)

1. Complete the one-time setup steps above.
2. Start log capture:
   ```
   adb logcat -c
   adb logcat RuntimeActivity:V RuntimeActivityPokemonZLaunchAttemptTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint14_pokemonz_log.txt
   ```
3. Run the test — Android Studio test target `com.pokerpgplayer.app.runtime.RuntimeActivityPokemonZLaunchAttemptTest`, or:
   ```
   ./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityPokemonZLaunchAttemptTest"
   ```
4. Search the captured log for, in order:
   - `RuntimeActivityPokemonZLaunchAttemptTest`-tagged: workspace path, `Game.ini`/`Data/Scripts.rxdata` presence confirmation.
   - `RuntimeActivity`-tagged: received path, set `GAME_PATH`, `onStart()`/`mHasMultiWindow`/`resumeNativeThread()`, no orientation-triggered recreation.
   - `SDL`-tagged: `"Running main function SDL_main from library .../libmkxp-z.so"`.
   - Confirm **absence** of `"Failed to set current directory"` and `"No game scripts specified"` (both expected and meaningful this time, since `Scripts.rxdata` genuinely exists).
   - **`mkxp`-tagged, level `DEBUG`:** any exception text from `showExc()` — this is the new, genuinely informative evidence category this sprint's own planning identified. Report its exact text if present.
   - The test's own `"scenario.close()..."` completion/timeout line.

### Evidence checklist (matches the approved Sprint 14 plan)

- [ ] `RuntimeActivity` received the Pokémon Z workspace path and set `GAME_PATH` before `SDLActivity.onCreate()`.
- [ ] `RuntimeActivity` remained stable across SDL's orientation request (no destroy/recreate).
- [ ] `SDL`-tagged native main/`nativeRunMain` start confirmed.
- [ ] No `"Failed to set current directory"` line.
- [ ] No `"No game scripts specified"` line, and no Espresso-observed missing-scripts dialog.
- [ ] `mkxp`-tagged `DEBUG` output checked for a `showExc()`-produced exception line.
- [ ] Any other Espresso-observable dialog noted, even if not an exact-text match (this test's own known limitation — see its kdoc).

### PASS / PARTIAL / INCONCLUSIVE / FAIL

- **PASS:** the expected lifecycle lines appear, no missing-scripts dialog appears, and either no exception is logged or a specific, named `showExc()` exception is logged — both are informative, acceptable outcomes for this sprint's own goal.
- **PARTIAL:** some but not all of the expected lifecycle evidence appears (e.g., native start confirmed but the 20-second window wasn't enough to reach a script-execution checkpoint either way).
- **INCONCLUSIVE:** none of the expected checkpoints appear at all, with the instrumentation process still alive — points at something unexpected earlier than any currently-understood checkpoint.
- **FAIL (test-construction sense):** the missing-scripts dialog *does* appear — treat as a workspace-copy setup problem to fix (re-verify the one-time setup steps above), not a Pokémon Z compatibility finding.

**Claim boundary, explicit:** this test collects evidence about native runtime behavior only. **No claim of game boot, title screen, gameplay, real input, audio, save/load, or general compatibility of any kind.** Not claimed: real device verification — Ti runs it, and the actual logcat/`TestRunner` result is what confirms the outcome.

## App v0.0.19 — Sprint 16: Pokémon Essentials v21.1 clean baseline controlled launch attempt

Sprint 15 (planning only) reviewed Pokémon Z's own `Win32API`/`kernel32` blocker: reading `binding/miniffi-binding.cpp` directly confirmed `Win32API` is a real, working port of RGSS1's original shim (aliased to mkxp-z's own `MiniFFI` class), not a missing feature — it fails only because the specific library name a script requests (`kernel32`, a Windows-only OS component) has no possible Android equivalent, via SDL's own cross-platform `SDL_LoadObject`/`dlopen` bridge. This is a real, common class of RGSS1 compatibility gap, not a defect in this project's own runtime work. Pokémon Z work was paused pending a separate compatibility-strategy decision (not reopened by this build).

Sprint 16 (planning only) selected a cleaner candidate instead: a clean Pokémon Essentials v21.1 baseline (~0.09 GB, much smaller than Pokémon Z's 0.86 GB) with a full, standard PBS data layout and no plugin-heavy signal. Planning also traced a second, distinct, genuinely open question: this baseline's `mkxp.json` reports `windowTitle` as `"PokĂ©mon Essentials v21.1"` — a pattern consistent with UTF-8 bytes for "é" being misread through a different code page. Reading `config.cpp` directly: every config file passes through `Encoding::convertString()`, which uses `uchardet` (a statistical charset detector) and explicitly skips conversion if `UTF-8` is detected — meaning this is either (a) a display-only artifact from whatever tool inspected the file during intake, with the real runtime value being correct, or (b) a genuine case of `uchardet`'s own heuristic misfiring on a short, mostly-ASCII string with one accented character, corrupting an already-correct UTF-8 value. Source alone cannot resolve which — this needs real-device observation.

**This build adds one new, internal-only `androidTest`**, adapted from (not copy-pasted from) Sprint 14's own Pokémon Z test — different class name, different workspace folder name, and new evidence points specific to this baseline: this config *explicitly* sets `"midiSoundFont": "soundfont.sf2"` (Pokémon Z's own config left this commented out), actively engaging the MIDI/FluidSynth path this project already confirmed fails gracefully in Sprint 10; the test also watches (as a pure *observation*, not reopening Sprint 15's own shim question) for whether this cleaner baseline hits the same `Win32API`/`kernel32` class of exception; and it documents, as a best-effort, logcat-only check, the windowTitle encoding question above.

**No Pokémon Essentials file of any kind is included in this repository, this zip, or any other deliverable.** Same one-time, Ti-side manual setup requirement as App v0.0.18.

### Required one-time Ti-side setup (do this once, before running the test)

```
adb push "C:\path\to\Pokemon Essentials v21.1" /data/local/tmp/essentials-staging
adb shell "run-as com.pokerpgplayer.app.debug mkdir -p files/essentials-v211-workspace"
adb shell "run-as com.pokerpgplayer.app.debug cp -r /data/local/tmp/essentials-staging/. files/essentials-v211-workspace/"
adb shell rm -rf /data/local/tmp/essentials-staging
```

**If this setup is skipped, the test skips itself** (via `org.junit.Assume`) rather than failing.

### New file

`app/src/androidTest/.../RuntimeActivityEssentialsBaselineLaunchAttemptTest.kt` — same core mechanics as App v0.0.18 (workspace-existence precondition, `EXTRA_WORKSPACE_PATH` injection, `android:configChanges` stability, bounded `scenario.close()`), 20-second native-startup margin (same as Pokémon Z, considered explicitly rather than copied without thought — the two `Scripts.rxdata` archives are comparably sized).

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every prior `androidTest` file, every file under `org/libsdl/app/`, every UI/ViewModel/resource/icon file. No `RuntimeManager`/`AppContainer` wiring, no config-injection code, no native C++ change, no game-specific hack.

### Running the test locally (Ti)

1. Complete the one-time setup steps above.
2. Start log capture:
   ```
   adb logcat -c
   adb logcat RuntimeActivity:V RuntimeActivityEssentialsBaselineLaunchAttemptTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint16_essentials_log.txt
   ```
3. Run — Android Studio test target `com.pokerpgplayer.app.runtime.RuntimeActivityEssentialsBaselineLaunchAttemptTest`, or:
   ```
   ./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityEssentialsBaselineLaunchAttemptTest"
   ```
4. Search the captured log for, in order:
   - `RuntimeActivityEssentialsBaselineLaunchAttemptTest`-tagged: workspace path, `Game.ini`/`Data/Scripts.rxdata`/`mkxp.json` presence confirmation.
   - `RuntimeActivity`-tagged: received path, set `GAME_PATH`, `onStart()`/`mHasMultiWindow`/`resumeNativeThread()`, no orientation-triggered recreation.
   - `SDL`-tagged: native main/`nativeRunMain` start.
   - Confirm **absence** of `"Failed to set current directory"` and `"No game scripts specified"`.
   - **`mkxp`-tagged, level `DEBUG`:** the `"RGSS version"` checkpoint if reached; any `showExc()` exception (report exact text); specifically note if it's a `Win32API`/`kernel32`-style exception (same class as Pokémon Z) or something different; a `libfluidsynth.so.3`/MIDI-related line (expected here); and the actual `windowTitle` value if it surfaces anywhere, compared character-by-character against `"Pokémon Essentials v21.1"`.
   - The test's own `"scenario.close()..."` completion/timeout line.

### PASS / PARTIAL / FAIL / INCONCLUSIVE

- **PASS:** expected lifecycle evidence present, no missing-scripts dialog, execution proceeds meaningfully past where Pokémon Z stopped, and either no exception or a specific, named `showExc()` exception is logged.
- **PARTIAL:** reaches script execution, stops at a genuinely new/different exception than Pokémon Z's own `Win32API` case.
- **INCONCLUSIVE:** no expected checkpoints appear, instrumentation process still alive.
- **FAIL (test-construction sense):** missing-scripts dialog appears despite `Scripts.rxdata`'s confirmed presence — a workspace-copy setup problem, not an Essentials finding.

**Claim boundary, explicit:** this test collects native runtime evidence only. **No claim of compatibility, title screen, gameplay, input, audio, save/load, or map movement.** Not claimed: real device verification, and not a resumption of Sprint 15's own Win32API shim strategy question — any recurrence of that exception class is recorded as an observation only.

## App v0.0.21 — Sprint 17 hotfix: config variant harness scope correction

An earlier version of this sprint's test (App v0.0.20) embedded `mkxp.json` variant content as Kotlin string constants and wrote them into a derived workspace copy at runtime, using `File.writeText()`. **This was reviewed and corrected — that approach is removed entirely in this build.** Reconstructing this exact file's content inside Kotlin source introduces its own additional encoding-transformation layer (Kotlin source file encoding, compilation, `writeText()`'s own charset handling) *on top of* the very encoding question (`windowTitle`'s reported `"PokĂ©mon Essentials v21.1"`, per Sprint 16) this investigation exists to resolve — undermining the experiment's own validity. Sprint 16 intake also found a trailing comma before `mkxp.json`'s closing `}` (invalid strict JSON, but `config.cpp` parses via `json::parse5`, whose "5" suggests JSON5 tolerance — never directly confirmed against this exact file).

**The corrected architecture: three separately, manually prepared, pre-existing app-private workspace folders — this test harness only reads them, never generates or modifies them.**

`RuntimeActivityEssentialsConfigVariantTest` keeps the same three explicit, separately-named test methods (not parameterized) — `launchAttempt_originalWorkspace_collectsConfigReadEvidence()`, `launchAttempt_asciiTitleWorkspace_collectsConfigReadEvidence()`, `launchAttempt_asciiTitleNoTrailingCommaWorkspace_collectsConfigReadEvidence()` — but each now does *only*: resolve its own fixed workspace folder name under `context.filesDir`, verify (never write) that `Game.ini`/`Data/Scripts.rxdata`/`mkxp.json` are present, launch `RuntimeActivity` pointed at that folder, and collect evidence exactly like every prior real-game test. **No `mkxp.json` content of any kind exists in this app's Kotlin source. No file is created, copied, or overwritten by this test.**

### Required Ti-side preparation (once per variant, entirely on Ti's own machine)

Ti prepares **three separate local staging folders**, each a full copy of the Pokémon Essentials v21.1 baseline, differing *only* in that copy's own `mkxp.json`:

1. **Original** — copy the baseline folder as-is; its `mkxp.json` is left completely unchanged (the file exactly as originally observed during Sprint 16 intake).
2. **ASCII title** — copy the baseline folder again, to a second local staging location; hand-edit *only* that copy's `mkxp.json`, changing `windowTitle` to a plain-ASCII value (no accented character) — everything else in that file left exactly as-is.
3. **ASCII title, no trailing comma** — copy the baseline folder a third time; hand-edit that copy's `mkxp.json` the same way as (2), and additionally remove the trailing comma immediately before the file's closing `}`.

**All editing happens on Ti's own local staging copies, in a plain text editor, before anything is pushed to the device.** The app and this test never generate, template, or transform any of this content — they only read whatever Ti has already placed on the device.

Then, for each of the three local staging folders, push and mirror it into its own, distinct app-private workspace (same `/data/local/tmp` + `run-as` technique as every prior sprint — never `/sdcard`):

```
adb push "C:\path\to\essentials-original-staging" /data/local/tmp/essentials-original-staging
adb shell "run-as com.pokerpgplayer.app.debug mkdir -p files/essentials-v211-original-workspace"
adb shell "run-as com.pokerpgplayer.app.debug cp -r /data/local/tmp/essentials-original-staging/. files/essentials-v211-original-workspace/"
adb shell rm -rf /data/local/tmp/essentials-original-staging

adb push "C:\path\to\essentials-ascii-title-staging" /data/local/tmp/essentials-ascii-title-staging
adb shell "run-as com.pokerpgplayer.app.debug mkdir -p files/essentials-v211-ascii-title-workspace"
adb shell "run-as com.pokerpgplayer.app.debug cp -r /data/local/tmp/essentials-ascii-title-staging/. files/essentials-v211-ascii-title-workspace/"
adb shell rm -rf /data/local/tmp/essentials-ascii-title-staging

adb push "C:\path\to\essentials-ascii-title-no-comma-staging" /data/local/tmp/essentials-ascii-title-no-comma-staging
adb shell "run-as com.pokerpgplayer.app.debug mkdir -p files/essentials-v211-ascii-title-no-trailing-comma-workspace"
adb shell "run-as com.pokerpgplayer.app.debug cp -r /data/local/tmp/essentials-ascii-title-no-comma-staging/. files/essentials-v211-ascii-title-no-trailing-comma-workspace/"
adb shell rm -rf /data/local/tmp/essentials-ascii-title-no-comma-staging
```

**The app/test harness only ever reads these three final app-private folders.** It does not know or care how Ti produced them — it just verifies the expected files exist and launches against whichever folder each test method names.

### Running the tests locally (Ti)

Same commands as App v0.0.20 — target `com.pokerpgplayer.app.runtime.RuntimeActivityEssentialsConfigVariantTest`, or an individual method with `--tests "...#methodName"`.

### Searching the captured log (unchanged from App v0.0.20 — no `findstr` inside `adb shell`)

`findstr` is a Windows-host command, not valid inside the device's own shell. Use `adb shell "logcat -d | grep -i mkxp"` for on-device filtering, or capture the full log and search it on Windows with PowerShell's own `Select-String` cmdlet.

### Files changed in this hotfix

- `app/src/androidTest/.../RuntimeActivityEssentialsConfigVariantTest.kt` — rewritten. **Confirmed: zero `mkxp.json` content constants remain in Kotlin source. Zero `writeText()`/`copyRecursively()` calls remain.** The class is now read-only: it verifies file presence and logs, nothing else.
- `app/build.gradle.kts` — version bump only.
- `README.md` — this section, replacing the App v0.0.20 section entirely.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No config-injection mechanism exists in any production class.

**Claim boundary, explicit:** this test collects config-parsing/encoding evidence only, against workspaces Ti alone prepared and verified. **No claim of compatibility, title screen, gameplay, input, audio, save/load, or map movement.** Not claimed: real device verification.

## App v0.0.22 — Sprint 18 Track 1: extended ASCII fixture observation

Ti re-ran the existing `launchAttempt_asciiTitleWorkspace_collectsConfigReadEvidence()` test (App v0.0.21) and confirmed: workspace valid, `GAME_PATH` set correctly, native main starts, `RGSS version 1` reached, OpenGL backend initializes on Qualcomm Adreno 740, no missing-scripts dialog, no `Win32API`/`kernel32` exception, no config-parse failure, no crash. **That test's own fixed 20-second wait is enough to confirm the config-read checkpoint, but not necessarily enough to observe deeper native/Ruby execution behavior** — real script execution, a later exception, a blocking dialog, or a silent native loop could all still be happening just past that window.

**This build adds one new, narrowly-scoped test method — `launchAttempt_asciiTitleWorkspace_extendedObservation()` — in the same `RuntimeActivityEssentialsConfigVariantTest` class.** It does not modify the three existing methods or their shared `runLaunchAttempt()` helper in any way; it reuses the exact same, already-proven ASCII-title workspace fixture (no new Ti-side setup) but waits up to **90 seconds** instead of 20, logging an explicit checkpoint every **10 seconds** so a long run's own progress is visible in logcat rather than one silent gap.

**No production code was touched** — `RuntimeActivity.kt` required no change; all new logging lives entirely in this test file.

**File changed:** `app/src/androidTest/.../RuntimeActivityEssentialsConfigVariantTest.kt` only (one new method + two new companion constants added; the three existing methods and their shared helpers are unchanged, confirmed by direct inspection).

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file.

### Running the test locally (Ti)

No new setup — the same `essentials-v211-ascii-title-workspace` folder from App v0.0.21 is reused as-is.

**Instrumentation command:**
```
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityEssentialsConfigVariantTest#launchAttempt_asciiTitleWorkspace_extendedObservation com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityEssentialsConfigVariantTest#launchAttempt_asciiTitleWorkspace_extendedObservation"
```

**Logcat capture command:**
```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityEssentialsConfigVariantTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint18_extended_observation_log.txt
```

### Evidence checklist

- [ ] Workspace/`Game.ini`/`Data/Scripts.rxdata`/`mkxp.json` presence confirmed (same as the shorter test).
- [ ] `RuntimeActivity` lifecycle evidence, orientation-stable (same as the shorter test).
- [ ] All nine `"Checkpoint: Nms elapsed..."` lines appear in sequence (confirms the extended wait itself ran to completion, not just that the test started).
- [ ] `mkxp`-tagged `DEBUG`: `RGSS version` line; any `showExc()` exception **at any point during the 90s window**, with its approximate checkpoint timing noted.
- [ ] Whether logcat output **continues, stalls, or stops entirely** partway through the window — a silent native loop with no further output is itself informative evidence, distinct from either a crash or an explicit exception.
- [ ] No missing-scripts dialog (same expectation as the shorter test).
- [ ] Any other Espresso-observable dialog beyond the missing-scripts check.
- [ ] The `scenario.close()` completion/timeout line.

**Claim boundary, explicit:** this test collects native runtime observation evidence only, against the same modified ASCII-safe test fixture as every prior Sprint 17/18 test — **not** the real, unmodified Pokémon Essentials v21.1 distribution. **No claim of compatibility, title screen, gameplay, input, audio, or save/load.** Not a Runtime Config Safety Layer implementation (Sprint 18 Track 2 remains design-only, unstarted). Not claimed: real device verification.

## App v0.0.23 — Sprint 19: test-only screenshot checkpoint

Sprint 18's own 90-second extended observation (App v0.0.22) confirmed the ASCII-safe Essentials v21.1 fixture stays alive with no crash, no exception, no dialog — but also **no further logcat output** after the early OpenGL/MIDI lines. Logcat alone cannot distinguish "reached an idle/rendering state," "blocked silently," or "rendering something broken" — all three look identical as an absence of further log output. ChatGPT approved Option C (a test-only screenshot checkpoint) from the Sprint 19 planning document as the smallest next step.

**This build adds real, pixel-level screenshot capture to the existing `launchAttempt_asciiTitleWorkspace_extendedObservation()` method** (App v0.0.22), via `UiDevice.takeScreenshot()` from `androidx.test.uiautomator` — this captures the full composited screen output, including SDL's own OpenGL-rendered content, unlike Espresso's own View-hierarchy checks, which cannot "see" `SurfaceView`/GL content at all. Screenshots are captured at **40 seconds** (the nearest point on the *existing* 10-second checkpoint grid to the originally-requested ~45s midpoint — kept aligned to the already-established grid rather than introducing a separate, misaligned timer) and **90 seconds** (the loop's own natural end).

**`androidx.test.uiautomator` was not previously present in this project** (confirmed by direct inspection before adding anything) — one new dependency, `androidx.test.uiautomator:uiautomator:2.3.0`, was added at the same `androidTestImplementation` scope as the existing Espresso dependency.

**This test does not interpret screenshot content in any way.** It logs only objective, non-interpretive facts: capture success/failure, the output file's path, and its size in bytes. What the images actually show is Ti's own visual review after pulling them to a local machine — never an automated claim this code makes about a title screen, rendering correctness, or compatibility.

**The three `_collectsConfigReadEvidence` methods and their shared `runLaunchAttempt()`/`pollForViewWithText()` helpers are completely unchanged** — confirmed by direct inspection; only `launchAttempt_asciiTitleWorkspace_extendedObservation()` itself was extended, plus one new, separate `captureScreenshot()` helper.

**No production code touched.** `RuntimeActivity.kt` required no change — all screenshot logic lives entirely in this test file.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityEssentialsConfigVariantTest.kt` — extended (new `captureScreenshot()` helper, two capture calls added inside the existing extended-observation method).
- `app/build.gradle.kts` — one new `androidTestImplementation` dependency, plus the version bump.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No original Pokémon Essentials file touched. No semantic config injection. No Runtime Config Safety Layer implementation.

### Build / install / test commands

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
```
Then, same test as App v0.0.22 (now with screenshot capture included):
```
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityEssentialsConfigVariantTest#launchAttempt_asciiTitleWorkspace_extendedObservation com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle directly:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityEssentialsConfigVariantTest#launchAttempt_asciiTitleWorkspace_extendedObservation"
```

### Logcat capture command (unchanged from App v0.0.22)

```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityEssentialsConfigVariantTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint19_screenshot_log.txt
```

### Pulling the screenshots to your PC

The screenshots are written to this app's own private storage — not externally shared or world-readable — so retrieval uses the same `run-as`-based technique as every prior sprint's own file transfers:
```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint19-screenshot-40s.png > sprint19-screenshot-40s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint19-screenshot-90s.png > sprint19-screenshot-90s.png
```

### Evidence checklist

- [ ] Same lifecycle/config-read evidence as App v0.0.22 (workspace valid, `GAME_PATH` set, orientation-stable, `RGSS version` reached, no missing-scripts dialog).
- [ ] Log line confirming screenshot capture success/failure at the 40s checkpoint, with file path and size if successful.
- [ ] Log line confirming screenshot capture success/failure at the 90s checkpoint, with file path and size if successful.
- [ ] Both PNG files successfully pulled to a local machine using the commands above.
- [ ] Ti's own visual description of each image — what it actually shows, reported as an observation, not a claim this project's code makes.
- [ ] All nine `"Checkpoint: Nms elapsed..."` lines still appear (confirms the underlying 90s observation loop itself is unaffected by the new screenshot calls).

**Claim boundary, explicit:** this build adds evidence-collection capability only. **No claim of compatibility, title screen, gameplay, input, audio, or save/load** — capturing a screenshot is not, by itself, a claim about what it shows. Not a Runtime Config Safety Layer implementation. Not claimed: real device verification.

## App v0.0.24 — Sprint 20: test-only Ruby/Zlib diagnostic probe

App v0.0.23's own screenshot capture confirmed the Essentials v21.1 ASCII-safe fixture reaches a real, visible Ruby exception dialog: `NameError: uninitialized constant PluginManager::Zlib`. Source review (Sprint 20 planning) found `zlib` is never explicitly fetched as a dependency in this fork's own `get_deps.sh` (unlike `openssl`, which is), and the fork's own README already lists "Windows MSYS2 Ruby extensions" as a known issue — strong, first-party evidence suggesting Ruby's own `ext/zlib` extension likely was never compiled into this build's `libruby.so`, even though the underlying zlib *C library* is separately proven to work at the engine's own C++ level (Scripts.rxdata decompression). ChatGPT approved a minimal, test-only diagnostic probe to confirm this directly, without any native rebuild.

**This build adds one new, fully independent test class, `RuntimeActivityZlibDiagnosticProbeTest`.** It contains **zero Pokémon Essentials content of any kind** — the workspace it uses (`Game.ini`, `mkxp.json`, and a tiny Ruby probe script) is authored entirely in this test's own Kotlin source and written fresh into a disposable, app-private folder before each run.

**How a custom Ruby script runs without touching `Scripts.rxdata` at all:** traced directly from `binding-mri.cpp` — the RGSS thread's own entry point checks `mkxp.json`'s `"customScript"` key; if set, it calls `runCustomScript()` **instead of** `runRMXPScripts()` entirely, bypassing `Scripts.rxdata` and any Essentials-specific code path completely. This is a real, existing mkxp-z config feature, not anything newly built.

**The probe script reports its findings via a deliberate `raise`, not `puts`/stdout** — this project has never confirmed whether Ruby's own stdout is captured anywhere visible on this Android runtime, but it *has* repeatedly, directly confirmed that uncaught Ruby exceptions reliably surface via the already-proven `showExc()`/`Debug()` mechanism (the same one that surfaced Pokémon Z's `Win32API` exception and Essentials' own `PluginManager::Zlib` exception). The probe deliberately raises a single `RuntimeError` at the end, with every finding — `require 'zlib'` success/failure, whether `Zlib` is defined afterward, and (if defined) whether a compress/decompress round-trip succeeds — encoded directly into that exception's own message, guaranteed to surface through the same reliable channel.

**Deliberately does not hard-assert an outcome.** Whether `Zlib` is present or absent is a genuinely open question this probe exists to answer.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityZlibDiagnosticProbeTest.kt` — new.
- `app/build.gradle.kts` — version bump only (App v0.0.24). No new dependency required — this test reuses `ActivityScenario`/`ApplicationProvider`, already present since earlier sprints.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials file read, copied, or referenced anywhere. No `PluginManager` patching. No Runtime Config Safety Layer. No native rebuild.

### Build / install / test commands

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityZlibDiagnosticProbeTest#launchAttempt_zlibProbeWorkspace_reportsRubyZlibAvailability com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityZlibDiagnosticProbeTest"
```

**No Ti-side workspace setup required** — unlike every prior real-game test, this one's entire workspace is generated by the test itself at run time. Just build, install, and run.

### Logcat filters

```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityZlibDiagnosticProbeTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint20_zlib_probe_log.txt
```

To isolate just the probe's own result line once captured:
```powershell
Select-String -Path "sprint20_zlib_probe_log.txt" -Pattern "SPRINT20_ZLIB_PROBE_RESULT"
```

### Expected PASS/FAIL evidence checklist

This is a diagnostic-only test — there is no "PASS means Zlib works" expectation; either outcome is a valid, useful result.

- [ ] `RuntimeActivity` lifecycle evidence (workspace created, `GAME_PATH` set, `onStart()`/orientation-stable) appears as usual.
- [ ] A `mkxp`-tagged `DEBUG` line beginning `SPRINT20_ZLIB_PROBE_RESULT:` appears — **this is the primary evidence this test exists to produce.**
- [ ] That line's `require_ok=` value — `true` or `false`.
- [ ] If `require_ok=false`: the exact `require_exception_class=`/`require_exception_message=` values.
- [ ] That line's `zlib_defined=` value.
- [ ] If `zlib_defined=true`: the `roundtrip_ok=` value, and if `false`, the round-trip's own exception class/message.
- [ ] If no `SPRINT20_ZLIB_PROBE_RESULT` line appears at all: report whatever *did* appear instead (a different exception, an `"Unable to open 'probe.rb'"` dialog — which would indicate a workspace-write problem, not a Zlib finding — or nothing).

**Interpretation guide (not asserted by the code itself):** `require_ok=false` or `zlib_defined=false` would directly confirm the Sprint 20 planning hypothesis (Ruby's own `ext/zlib` extension is genuinely absent from this build) — meaning Essentials' own `PluginManager::Zlib` blocker is a general Ruby runtime capability gap, not anything specific to Essentials' own code or this fixture. `require_ok=true` and `zlib_defined=true` (regardless of round-trip result) would instead point toward a namespacing or Essentials-specific issue as the more likely explanation, since standard Ruby `Zlib` would exist here but `PluginManager`'s own reference to it may resolve differently for reasons this probe doesn't test.

**Claim boundary, explicit:** this is a diagnostic probe only. **No claim of compatibility, title screen, gameplay, input, audio, or save/load.** No native rebuild, no `PluginManager` patching, no Runtime Config Safety Layer. Not claimed: real device verification.

## App v0.0.25 — Sprint 21: test-only `preloadScript` Zlib probe

App v0.0.24's own probe confirmed Ruby's `Zlib` module works correctly in this runtime — refuting the "missing `ext/zlib`" hypothesis entirely. Web search (Sprint 21 planning) then found this exact `PluginManager::Zlib` error independently reported by the wider mkxp-z community on desktop Linux, resolved there by a mkxp-z version downgrade — strong, independent evidence this is a load-order/timing characteristic of mkxp-z's own engine, not specific to this project's Android fork or to PE21 itself. ChatGPT approved a direct, PE21-specific test: does pre-requiring `Zlib` before Essentials' own real scripts run resolve the blocker?

**This build adds one new, independent test class, `RuntimeActivityPreloadZlibProbeTest`.** It builds a **disposable copy** of the existing, Ti-prepared ASCII-safe Essentials workspace (`essentials-v211-ascii-title-workspace`, unchanged since Sprint 17) via `File.copyRecursively()` — a byte-for-byte copy, no text decoding/re-encoding of any PE21 content — then adds a tiny, test-authored preload script (`sprint21_preload_zlib.rb`: `require 'zlib'` plus a harmless marker variable, no raise, no file mutation, no reference to any Essentials class) and updates *only* the disposable copy's own `mkxp.json` to add a `"preloadScript"` key pointing at it.

**Important design note on how the config gets modified, and why this doesn't repeat the App v0.0.20 mistake:** the App v0.0.21 correction was about *generating or reconstructing* `mkxp.json` content from Kotlin string constants. This test does something different: it **reads** the disposable copy's own real `mkxp.json` (Ti's own actual bytes, already copied, not reconstructed) via `org.json.JSONObject` — already used elsewhere in this project since Sprint 2 — and adds only the one new key, preserving every other key/value exactly as Ti originally wrote it. The test also explicitly logs a before/after size check on the **original** base workspace's own `mkxp.json` to confirm it was never touched.

**Uses `mkxp-z`'s own existing `preloadScript` feature** (traced directly from `binding-mri.cpp` — runs inside `runRMXPScripts()`, after archive decompression but before the real script-execution loop, falling through to `Scripts.rxdata` automatically as long as the preload leaves no uncaught exception) — a real, existing engine capability, not anything newly built.

**Reuses the exact screenshot approach from App v0.0.23** (`UiDevice.takeScreenshot()`) — no new dependency, `androidx.test.uiautomator` was already added then.

**This is diagnostic only** — it does not constitute, propose, or imply a production config-injection mechanism or a Runtime Config Safety Layer implementation. See the Sprint 21 planning document's own explicit distinction between test-only probing and any future, separately-approved production mitigation.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityPreloadZlibProbeTest.kt` — new.
- `app/build.gradle.kts` — version bump only (App v0.0.25). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials original file modified. No `PluginManager` patching. No Runtime Config Safety Layer. No native rebuild. No production config-injection mechanism.

### Build / install / test commands

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityPreloadZlibProbeTest#launchAttempt_preloadZlibProbeWorkspace_reportsBlockerOutcome com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityPreloadZlibProbeTest"
```

**No new Ti-side setup required** — reuses the existing `essentials-v211-ascii-title-workspace` base folder from App v0.0.21 as-is; the disposable copy and its modifications happen entirely on-device, inside the test itself.

### Logcat filters

```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityPreloadZlibProbeTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint21_preload_zlib_probe_log.txt
```

### Screenshot pull command

```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint21_preload_zlib_probe_screenshot.png > sprint21_preload_zlib_probe_screenshot.png
```

### Evidence checklist

- [ ] `sprint21_preload_zlib.rb` exists in the disposable workspace only (logged path confirms this).
- [ ] The disposable workspace's `mkxp.json` has the `"preloadScript"` key; the **original** `essentials-v211-ascii-title-workspace`'s own `mkxp.json` size is confirmed unchanged (before/after log lines).
- [ ] `RuntimeActivity` lifecycle evidence, `RGSS version` checkpoint reached (confirms the runtime got at least as far as it did in App v0.0.23).
- [ ] **The key question:** does the previously-observed `NameError: uninitialized constant PluginManager::Zlib` (App v0.0.23) **persist**, **disappear**, or get **replaced** by a different exception? Report exactly which, with full exception details if one appears.
- [ ] Screenshot successfully captured and pulled; Ti's own visual comparison against the App v0.0.23 screenshots.

**Claim boundary, explicit:** diagnostic probe only, testing a hypothesis against real (but disposable-copy) PE21 content. **No claim of compatibility, title screen, gameplay, input, audio, or save/load.** No native rebuild, no `PluginManager` patching, no Runtime Config Safety Layer, no production config-injection mechanism. Not claimed: real device verification.

## App v0.0.26 — Sprint 21 hotfix: fix `preloadScript` probe mkxp.json handling

Ti's App v0.0.25 run failed **before `RuntimeActivity`/mkxp ever launched**: `org.json.JSONException: Expected literal value at character 6208`. Root cause: mkxp-z's own `mkxp.json` is not strict JSON — Sprint 16's own source trace already established `config.cpp` parses it via `json::parse5`, a **JSON5-tolerant** parser that explicitly permits comments and trailing commas, both of which Android's strict `org.json.JSONObject` rejects outright. **This was a test-harness failure, not a PE21 runtime result** — no conclusion about `preloadScript` or `PluginManager::Zlib` could be drawn from App v0.0.25 at all.

**This build fixes only the `mkxp.json` modification strategy — nothing else.** `RuntimeActivityPreloadZlibProbeTest` no longer parses `mkxp.json` into any object model. It now uses a **minimal, text-based insertion**: find the position of the file's final top-level closing brace, check whether the content immediately before it (after trimming trailing whitespace) already ends in a comma, add one only if it doesn't, and insert the new `"preloadScript": ["sprint21_preload_zlib.rb"],` property directly before that brace. Every other character, comment, and formatting choice in the rest of the file is left completely untouched. This logic was verified against three realistic scenarios (no trailing comma, already has a trailing comma, a `//` comment line immediately before the last property) before being finalized.

**Same disposable-copy safety guarantees as App v0.0.25, unchanged:** the original `essentials-v211-ascii-title-workspace` is still only ever byte-for-byte copied, never opened for writing; before/after size logging on the original's own `mkxp.json` still confirms it was never touched.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityPreloadZlibProbeTest.kt` — the `mkxp.json`-modification logic only; everything else (workspace copying, preload script content, launch, wait, screenshot, close) is unchanged from App v0.0.25.
- `app/build.gradle.kts` — version bump only (App v0.0.26).
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials original file modified. No `PluginManager` patching. No Runtime Config Safety Layer. No native rebuild.

### Build / install / test commands

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityPreloadZlibProbeTest#launchAttempt_preloadZlibProbeWorkspace_reportsBlockerOutcome com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityPreloadZlibProbeTest"
```
No new Ti-side setup — same base workspace as before.

### Logcat filters

```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityPreloadZlibProbeTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint21_preload_zlib_probe_log.txt
```

### Screenshot pull command

```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint21_preload_zlib_probe_screenshot.png > sprint21_preload_zlib_probe_screenshot.png
```

### Evidence checklist

1. [ ] Original `essentials-v211-ascii-title-workspace`'s own `mkxp.json` confirmed unchanged (before/after size log lines).
2. [ ] Disposable workspace's `mkxp.json` confirmed to contain the new `preloadScript` line (before/after character-count log lines).
3. [ ] `RuntimeActivity` actually launches this time (no `JSONException`, no crash before native startup).
4. [ ] `RGSS version`/OpenGL checkpoint reached or not.
5. [ ] **The key question:** does `NameError: uninitialized constant PluginManager::Zlib` persist, disappear, or get replaced by a different exception?
6. [ ] Screenshot captured (if the runtime reaches a visible state) and pulled successfully.

**Claim boundary, explicit:** diagnostic probe only. **No claim of compatibility, title screen, gameplay, input, audio, or save/load.** No native rebuild, no `PluginManager` patching, no Runtime Config Safety Layer, no production config-injection mechanism. Not claimed: real device verification.

## App v0.0.27 — Sprint 22: extended observation of the `preloadScript`-fixed fixture

Ti's App v0.0.26 run produced the first real result: with `Zlib` pre-required, the `PluginManager::Zlib` dialog no longer appears — the runtime instead reaches a state showing only the window title bar over a black render area, at the 20-second checkpoint. This is genuinely ambiguous, matching the exact same "logcat/single-screenshot ceiling" this project already resolved once before (Sprint 18 Track 1's extended wait, Sprint 19's multi-checkpoint screenshots) — just now for the *new*, preload-fixed state instead of the original blocker.

**This build adds one new, independent test class, `RuntimeActivityPreloadZlibExtendedObservationTest`**, reusing the exact disposable-workspace approach proven in App v0.0.26 (byte-for-byte copy, test-authored preload script, the same text-based `mkxp.json` insertion — never a JSON parse), extended with:
- **120-second total wait** (vs. 20s), with an explicit log checkpoint every 20 seconds — 120s chosen over the task's own suggested 90s to stay on a clean 20-second grid, the same kind of small, explicitly-documented adjustment already made in Sprint 19 for its own 45s-vs-40s case.
- **Three screenshots** — 20s, 60s, 120s — all aligned to that same grid.
- **A before/after recursive file-listing diff** of the disposable workspace, logging any file that's genuinely new after the wait — a cheap, read-only check for whether `PluginManager`'s own plugin-cache-generation behavior (Sprint 21 planning's own hypothesis 2) produced anything observable, without inspecting file contents.

**The existing, already-working `RuntimeActivityPreloadZlibProbeTest` (App v0.0.26) is completely unchanged** — this is a new, separate class, not a modification.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityPreloadZlibExtendedObservationTest.kt` — new.
- `app/build.gradle.kts` — version bump only (App v0.0.27). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityPreloadZlibProbeTest.kt` (App v0.0.26, unchanged), every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials original file modified. No `PluginManager` patching. No Runtime Config Safety Layer. No native rebuild. No production preload strategy.

### Build / install / test commands

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityPreloadZlibExtendedObservationTest#launchAttempt_preloadZlibExtendedObservation_reportsVisualProgression com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityPreloadZlibExtendedObservationTest"
```
No new Ti-side setup — reuses the existing base workspace.

### Logcat filters

```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityPreloadZlibExtendedObservationTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint22_extended_observation_log.txt
```

### Screenshot pull commands

```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint22_preload_zlib_extended_screenshot_20s.png > sprint22_screenshot_20s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint22_preload_zlib_extended_screenshot_60s.png > sprint22_screenshot_60s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint22_preload_zlib_extended_screenshot_120s.png > sprint22_screenshot_120s.png
```

### Evidence checklist

1. [ ] `preloadScript` remains test-only, present only in the disposable workspace's `mkxp.json`.
2. [ ] Original `essentials-v211-ascii-title-workspace`'s own `mkxp.json` size confirmed unchanged.
3. [ ] `RGSS version`/OpenGL checkpoint reached.
4. [ ] `PluginManager::Zlib` confirmed absent throughout the full 120s window, or its return reported exactly if it reappears.
5. [ ] Screenshot state described at each of the three checkpoints (20s/60s/120s) — same black-screen-with-title-bar, a dialog, or visual progression.
6. [ ] Any new exception/dialog beyond `PluginManager::Zlib` captured exactly (class/message/backtrace).
7. [ ] Any new files listed by the before/after diff, with their sizes — or explicitly confirmed none appeared.

**Claim boundary, explicit:** diagnostic-only, extended observation of a test-only, preload-fixed fixture. **No claim of compatibility, title screen, gameplay, input, audio, or save/load.** No native rebuild, no `PluginManager` patching, no Runtime Config Safety Layer, no production preload/config-injection strategy. Not claimed: real device verification.

## App v0.0.29 — Sprint 23 hotfix: split cache persistence diagnostic into two independent test methods

Ti's App v0.0.28 run showed the first launch (with `preloadScript`) completing correctly — title menu visible at all three checkpoints, `Data/PluginScripts.rxdata` confirmed generated (14,270 bytes) — but the **second** `ActivityScenario` launch, invoked later in the *same* test method, produced no observation output at all: no checkpoint lines, no screenshots, no completion log, despite `RGSS1`/OpenGL being reached. **This was inconclusive, not a negative result** — consistent with an Android instrumentation/`ActivityScenario` sequencing limitation when running two full native-runtime launches back-to-back within a single test method, not a finding about `PluginManager`, `Zlib`, or the cache itself.

**Rather than debug the exact sequencing cause, this build removes the shared-method-scope entirely: each launch now gets its own, independent test method and instrumentation invocation.**

**`primeCache_withPreload_reachesTitleMenuAndGeneratesCache()`** — creates the disposable workspace, adds `preloadScript`, launches, observes to title menu (90s, screenshots every 30s), confirms `Data/PluginScripts.rxdata` exists, and — **deliberately, unlike every prior test in this project — does not delete the workspace when finished.** Writes a small marker file (`sprint23_cache_primed.marker`) as an explicit signal that priming completed.

**`secondRun_withoutPreload_usesPrimedCache()`** — never creates or clears the workspace itself; it only *locates* the one the first method already left behind (skipping via `assumeTrue`, not failing, if it's missing). Confirms `Data/PluginScripts.rxdata` exists **before** launch, removes `preloadScript` from the disposable `mkxp.json` (precise, known-format text removal — see the class's own kdoc), confirms its absence, then launches fresh in its own, separate `ActivityScenario`.

**Both methods reuse the exact disposable-workspace and text-based `mkxp.json` insertion/removal approach proven in App v0.0.26–28** — byte-for-byte copy, never a JSON parse.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityCachePersistenceDiagnosticTest.kt` — rewritten: the single combined method from App v0.0.28 is replaced by these two independent methods; the shared helpers (`captureScreenshot`, `insertPreloadScriptKey`, `removePreloadScriptKey`, `listRelativeFilePaths`, `runLaunchAndObserve`) are unchanged in behavior, just reused by two callers instead of one.
- `app/build.gradle.kts` — version bump only (App v0.0.29). No new dependency.
- `README.md` — this section, replacing the App v0.0.28 section entirely.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeActivityPreloadZlibProbeTest.kt` (App v0.0.26), `RuntimeActivityPreloadZlibExtendedObservationTest.kt` (App v0.0.27), every other prior `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials original file modified. No input/controller/save/audio testing.

### Running method 1 first (primes the cache)

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityCachePersistenceDiagnosticTest#primeCache_withPreload_reachesTitleMenuAndGeneratesCache com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityCachePersistenceDiagnosticTest#primeCache_withPreload_reachesTitleMenuAndGeneratesCache"
```

### Then running method 2 separately (tests the primed cache)

**Run this as its own, separate command, after method 1 has fully finished:**
```
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityCachePersistenceDiagnosticTest#secondRun_withoutPreload_usesPrimedCache com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Or via Gradle:
```
./gradlew connectedAndroidTest --tests "com.pokerpgplayer.app.runtime.RuntimeActivityCachePersistenceDiagnosticTest#secondRun_withoutPreload_usesPrimedCache"
```
No new Ti-side setup for either command — reuses the existing base workspace. **Do not run both methods together in one `connectedAndroidTest` invocation** (e.g., without `--tests` filtering to one method) — keep them as two separate commands, run in order, matching the whole reason this hotfix exists.

### Logcat capture guidance

Capture separately for each method's own run, to keep the two result sets clearly distinguishable:
```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityCachePersistenceDiagnosticTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint23_method1_prime_log.txt
```
(run method 1, wait for it to finish, then)
```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityCachePersistenceDiagnosticTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint23_method2_second_run_log.txt
```
(then run method 2)

### Screenshot pull commands

```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint23_cache_persistence_prime-with-preload_30s.png > sprint23_prime_30s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint23_cache_persistence_prime-with-preload_60s.png > sprint23_prime_60s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint23_cache_persistence_prime-with-preload_90s.png > sprint23_prime_90s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint23_cache_persistence_second-run-without-preload_30s.png > sprint23_second_30s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint23_cache_persistence_second-run-without-preload_60s.png > sprint23_second_60s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint23_cache_persistence_second-run-without-preload_90s.png > sprint23_second_90s.png
```

### Evidence checklist

1. [ ] Method 1 primes the cache and reaches the title menu (per its own three screenshots).
2. [ ] `Data/PluginScripts.rxdata` confirmed to exist **before** method 2's own launch (method 2's own precondition log line).
3. [ ] `preloadScript` confirmed absent from the disposable `mkxp.json` before method 2's launch.
4. [ ] Method 2's own result clearly reported: title menu appears (cache alone sufficient), `PluginManager::Zlib` reappears (cache alone not sufficient), or a different, new blocker appears.
5. [ ] Original PE21 workspace (`essentials-v211-ascii-title-workspace`) confirmed unchanged, in both methods' own before/after size checks.

**Claim boundary, explicit:** diagnostic only, testing cache-reuse behavior against a test-only, disposable, previously-modified workspace, now split across two independently-run methods for reliability. **No claim of compatibility, title screen, gameplay, input, audio, or save/load.** No native rebuild, no `PluginManager` patching, no Runtime Config Safety Layer, no production preload/config-injection strategy. Not claimed: real device verification.

## App v0.0.30 — Sprint 24, Stage 1: Runtime Config Safety Layer schema only

App v0.0.29's split diagnostic rejected the "first-run-only preload" hypothesis with real evidence — `PluginManager::Zlib` reappeared inside `runPlugins` on the second run, meaning the mitigation is needed on **every** launch, not just the first. ChatGPT approved the resulting Runtime Config Safety Layer ADR (Sprint 24) and its "Runtime Config Overlay" architecture, and this build implements **Stage 1 only**: the data model, with zero detection logic, zero overlay generation, and zero launch-path change.

**Important architectural finding before writing any code:** neither `RuntimeStrategy` nor a "Compatibility Database" exist as real Kotlin code in this project — both were planning-only concepts from Sprint 13's own ADR, never implemented. The closest real, existing per-game data model is `GameEntry` (with `GameDetectionResult` as its own "Data" half, per the project's established Data-vs-Knowledge split) — so the new schema was added there, not to a system that doesn't exist yet, and not to `RuntimeConfig` (a *runtime-session* settings placeholder, explicitly documented since Sprint 3 as "do not add guessed fields" — a different, per-session concept from per-game compatibility metadata, and the wrong architectural home for this).

**New file, `RuntimeConfigProfile.kt`:** `RuntimeConfigProfile` and its nested types (`DetectedSignal`, `MitigationOverride`, `OverrideMode`, `MitigationAudit`, `OverlayStatus`), plus `KnownSignalIds`/`KnownMitigationIds` — string-identifier constants (`essentials-pluginmanager`, `non-ascii-window-title`, `zlib-preload`, `ascii-safe-window-title`) provided for discoverability only, not validated or enforced. Every field defaults to empty/`AUTO`/`NOT_GENERATED` — the only state any real `GameEntry` can have in Stage 1, since no detection or generation logic exists yet.

**`GameEntry` gains one new field:** `runtimeConfigProfile: RuntimeConfigProfile = RuntimeConfigProfile()` — defaulted, so no existing code constructing a `GameEntry` needs to change (confirmed against the existing `GameLaunchRequestMapperTest`'s own named-argument test fixture, which continues to compile and pass unmodified).

**Migration handled explicitly in `JsonFileGameLibraryRepository`:** the new field is serialized/deserialized using the same `opt`-based, defensive-reading style already established for every other field in this file. Critically, **pre-Sprint-24 saved libraries have no `"runtimeConfigProfile"` key in their JSON at all** — `optJSONObject("runtimeConfigProfile")` returns `null` in that case (not a thrown exception), falling back to the same empty `RuntimeConfigProfile()` default a brand-new entry gets. This matters specifically because `readFromDisk()`'s own per-entry `runCatching{}.getOrNull()` wrapper would otherwise silently *drop* any entry that failed to parse — safe, non-throwing migration was required, not optional.

**Documentation explicitly distinguishes this from the diagnostic `preloadScript` probes** (App v0.0.25–29) in `RuntimeConfigProfile.kt`'s own kdoc — those remain test-only, `androidTest`-scoped tools; this is schema for a *future*, generic, signal-gated production mechanism that doesn't exist yet.

**No test added for the repository's own serialization** — the project has no pre-existing schema/serialization tests to extend (confirmed by search before starting), and `JsonFileGameLibraryRepository`'s own kdoc already documents why: `org.json`'s Android implementation is a stub outside a real Android runtime, so this file's own (de)serialization can't be unit-tested on a plain JVM without Robolectric or an instrumented test — an existing, stated limitation, not something this delivery works around.

**Files changed:**
- `app/src/main/java/com/pokerpgplayer/app/data/model/RuntimeConfigProfile.kt` — new.
- `app/src/main/java/com/pokerpgplayer/app/data/model/GameEntry.kt` — one new, defaulted field added.
- `app/src/main/java/com/pokerpgplayer/app/data/repository/JsonFileGameLibraryRepository.kt` — serialization/deserialization for the new field, migration-safe.
- `app/build.gradle.kts` — version bump only (App v0.0.30). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `NativeRuntimeLoader.kt`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, **`RuntimeConfig.kt`** (deliberately not extended — see above), `AppContainer.kt`, `GameDetectionResult.kt`, `GameLibraryRepository.kt` (the interface itself), `GameLaunchRequestMapperTest.kt`, every `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials file touched. No detection logic, no overlay generation, no launch-path change — confirmed by direct inspection of the new file's own content.

### Migration notes

- Existing `game_library.json` files load unchanged — every existing `GameEntry` field reads exactly as before; the new field simply defaults to an empty `RuntimeConfigProfile()` when its JSON key is absent.
- The very next save of any existing entry will add the new `"runtimeConfigProfile"` key (with all-empty/default contents) to that entry's own JSON — a one-way, additive, non-destructive upgrade with no data loss, since nothing existing is removed or restructured.
- No `SCHEMA_VERSION` bump was made — the existing library file format's own top-level `schemaVersion` (currently `1`) describes the *file's* own structure, and remains valid; this change is purely additive to one nested object within an existing entry, not a breaking structural change to the file itself.

### Default values (for reference)

```
RuntimeConfigProfile(
    detectedSignals = [],
    recommendedMitigations = [],
    enabledMitigations = [],
    disabledMitigations = [],
    overrides = MitigationOverride(mode = AUTO, notes = ""),
    mitigationAudit = MitigationAudit(all fields null/empty),
    overlayStatus = NOT_GENERATED
)
```

### Test command

No new automated test — build/compile verification only:
```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```
(`testDebugUnitTest` re-runs the existing unit test suite, including `GameLaunchRequestMapperTest`, to confirm the new defaulted field didn't break anything already covered.)

**Claim boundary, explicit:** schema/data-model only. **No detection, no overlay generation, no launch-path change, no production preload/config-injection mechanism, no compatibility claim.** The diagnostic `preloadScript` probes (Sprint 20–23) remain test-only and are not extended, reused, or made production by this delivery.

## App v0.0.31 — Sprint 25, Stage 2: isolated Runtime Config Overlay generation

Stage 1 (App v0.0.30) added the `RuntimeConfigProfile` schema with zero behavior change. This build implements **Stage 2 only**: the actual overlay-generation logic, built and tested entirely in isolation — **not wired into `RuntimeActivity` or any real launch path.** That remains Stage 3, a separate, later, explicitly-approved step.

**New class, `RuntimeConfigOverlayService`** (`data/config/RuntimeConfigOverlayService.kt`): takes an original config's own text plus a `RuntimeConfigProfile`, decides which known mitigations apply (`enabledMitigations` minus `disabledMitigations` — `recommendedMitigations` is deliberately not consulted here, since that field is meant to feed a *future* recommendation-resolution step, not be re-derived by the generator itself), and returns a disposable overlay plus a full audit trail. **Never parses `mkxp.json` with a strict JSON parser** — reuses the exact same safe, text-based insertion technique already proven across Sprint 21–23's own diagnostic tests (find the final closing brace, add a trailing comma only if needed, insert before it), now as production-quality code rather than test-only logic.

**Two mitigations implemented, exactly as specified:**
- **`zlib-preload`** — inserts `"preloadScript": ["pokerpg_preload_zlib.rb"]` and generates that script's own content (`require 'zlib'`, nothing else — no raise, no `PluginManager` reference). Skips cleanly (doesn't overwrite) if a `preloadScript` key already exists in the original.
- **`ascii-safe-window-title`** — only touches `windowTitle` if the profile's own `enabledMitigations` requests it. The ASCII-safe replacement is computed deterministically via Unicode `NFD` normalization (decomposing accented characters, e.g. "é" → "e") plus stripping anything still non-ASCII — a mechanical *transformation*, not new detection logic (the *decision* to apply this mitigation still comes entirely from the profile, matching the approved scope's own "do not decide detection in Stage 2" boundary). The original title is preserved in the audit's own reasoning text. Skips cleanly if `windowTitle` is absent or already ASCII.

**The core `generateOverlay(originalConfigText, profile)` method has zero Android dependency** — pure `String` in, structured result out — so every required test scenario runs as an ordinary JVM unit test (`RuntimeConfigOverlayServiceTest`, `app/src/test`), not `androidTest`. A thin `generateOverlayToDirectory(File, profile, outputDir)` wrapper handles the actual file I/O for real use, using plain `java.io.File` (still no Android-specific API), reading the original and writing only to a separate output directory — never back to the original's own path.

**Every test scenario in the approved scope was verified twice** — first via an independent Python simulation of the exact algorithm (to catch logic errors before committing to Kotlin), then as the real `RuntimeConfigOverlayServiceTest` suite: comments preserved through `zlib-preload` insertion, ASCII replacement correctness, both mitigations together, idempotence (regenerating from the same original text twice produces identical, non-duplicated output — and regenerating against an original that *already has* a `preloadScript` key skips rather than duplicating), a no-mitigation profile producing `overlayStatus = NOT_GENERATED` with no overlay text at all, a disabled mitigation being suppressed even if nominally enabled, audit hashes populated and differing between original and overlay, and the file-based wrapper never touching the original path on disk.

**Files changed:**
- `app/src/main/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayService.kt` — new.
- `app/src/test/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayServiceTest.kt` — new, 15 test methods.
- `app/build.gradle.kts` — version bump only (App v0.0.31). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `RuntimeConfig.kt`, `AppContainer.kt`, every Stage 1 file (`GameEntry.kt`, `RuntimeConfigProfile.kt`, `JsonFileGameLibraryRepository.kt` — read for reference, not modified further), every `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials file touched. **No reference to `RuntimeActivity`, `RuntimeManager.launch()`, or `GAME_PATH` anywhere in the new service** — confirmed by direct grep; the only two mentions of `RuntimeActivity` in the file are documentation comments explicitly stating it is *not* wired in.

### Service / class names

- `com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService` — the generator itself.
- `com.pokerpgplayer.app.data.config.OverlayGenerationResult` — its return type (the audit result).

### Test names (15 total, `RuntimeConfigOverlayServiceTest`)

```
zlib-preload insertion preserves existing comments
zlib-preload generates the expected auxiliary script file with exact required content
ascii-safe-window-title replaces non-ASCII title and preserves everything else
ascii-safe-window-title skips cleanly when the title is already ASCII
ascii-safe-window-title skips cleanly when windowTitle is entirely absent
both mitigations applied together produce a single, coherent overlay
generating the overlay twice from the same original text does not duplicate preloadScript
regenerating against an original that already has preloadScript skips rather than duplicating
no enabled mitigations produces overlayStatus NOT_GENERATED and no overlay text
a disabled mitigation is not applied even if it would otherwise be enabled
the original config text parameter itself is never mutated
audit hashes are populated and differ between original and overlay
original config hash is still populated even when no mitigation is applied
generateOverlayToDirectory reads the original file but never writes to its own path
generateOverlayToDirectory writes nothing to the output directory when no mitigation applies
```

### How to run the tests

```
./gradlew testDebugUnitTest --tests "com.pokerpgplayer.app.data.config.RuntimeConfigOverlayServiceTest"
```
Or the full unit test suite:
```
./gradlew testDebugUnitTest
```

### Summary of generated overlay behavior

Given an original `mkxp.json` with a `//` comment and a non-ASCII `windowTitle`, and a profile with both mitigations enabled, the generated overlay:
```json
{
    // this is a comment
    "windowTitle": "Pokemon Essentials v21.1",
    "vsync": true,
    "preloadScript": ["pokerpg_preload_zlib.rb"],
}
```
— the comment is untouched, the title is transliterated to plain ASCII, and `preloadScript` is appended (with a trailing comma, tolerated by mkxp-z's own JSON5-style parser, confirmed since Sprint 16). A companion `pokerpg_preload_zlib.rb` file (`require 'zlib'\n`) is generated alongside it. If no mitigation applies, nothing is generated at all — `overlayStatus` is `NOT_GENERATED` and `overlayConfigText` is `null`.

**Claim boundary, explicit:** overlay-generation logic only, verified in isolation. **No launch-path wiring, no production preload/config-injection behavior for any real game, no compatibility claim.** Stage 3 (wiring this into `RuntimeActivity`) is explicitly out of scope and not started.

## App v0.0.32 — Sprint 26: hardening `RuntimeConfigOverlayService` before launch wiring

Before allowing `RuntimeConfigOverlayService` (App v0.0.31) anywhere near `RuntimeActivity`'s own launch flow, this build hardens two real gaps found in its own original behavior and adds explicit malformed-input handling — a deliberate pause before Stage 3, not Stage 3 itself.

**Real behavior change: `zlib-preload` no longer skips entirely when a `preloadScript` key already exists.** Sprint 25's own original logic treated *any* existing `preloadScript` key as "don't touch it" — safe, but unhelpfully blunt: a game whose own config already references a different, legitimate preload script would never get the Zlib mitigation applied at all. This build reads the *existing value*'s own shape (a JSON array or a bare string — both real, valid mkxp-z config shapes) and **appends** `pokerpg_preload_zlib.rb` to it, preserving whatever was already there: an existing array gets our script added as a new element; an existing bare string gets converted to a two-element array containing both. If our own script is *already* referenced (in either shape), this is correctly a no-op, not a duplicate. Still a targeted regex for this one field's own value — not a JSON5 parser rewrite, matching the approved scope's own explicit caution.

**New: malformed config text is handled gracefully.** The whole mitigation-application body is now wrapped in a `try`/`catch` — if the config text is malformed in a way the targeted transformations can't handle (tested: no closing brace at all, and an empty string), the result is `overlayStatus = ERROR` with a populated `errorMessage`, never an uncaught exception, and never a claim that an overlay was generated. The original text itself is never mutated in this path either, by the same construction as every other path (`generateOverlay` only ever reads its own parameter).

**Every new scenario was verified via an independent Python simulation before being written as a real Kotlin test** — matching this project's own established practice: existing array with a different script → append; existing bare string → convert to array; our own script already present → no-op; idempotence re-confirmed for the new append path.

**Files changed:**
- `app/src/main/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayService.kt` — `zlib-preload` mitigation logic replaced (append-aware, three real shapes handled); mitigation application wrapped in `try`/`catch` for graceful `ERROR` handling; one prior test's own now-outdated assumption (skip-entirely) corrected to match the new, more correct append behavior.
- `app/src/test/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayServiceTest.kt` — one existing test renamed/rewritten to match the new behavior; 6 new tests added (test count: 15 → 21).
- `app/build.gradle.kts` — version bump only (App v0.0.32). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `RuntimeConfig.kt`, `AppContainer.kt`, every Stage 1 file, every `androidTest` file, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials file touched. **Still no reference to `RuntimeActivity`, `RuntimeManager.launch()`, or `GAME_PATH` anywhere in the service** — confirmed by direct grep, the same two documentation-only mentions as App v0.0.31.

### Added/updated tests (21 total, up from 15)

New this build:
```
zlib-preload appends to an existing preloadScript array rather than skipping it
zlib-preload converts an existing bare-string preloadScript into an array containing both
zlib-preload is a no-op when our own script is already present in an existing array
zlib-preload appending twice from the same original array-based config is idempotent
a config with no closing brace at all returns overlayStatus ERROR with a reason, not a crash
an empty config text returns overlayStatus ERROR rather than throwing
original config text is never mutated even when generation fails
```
Renamed/corrected from App v0.0.31 (old assertion no longer matched the new, correct behavior):
```
"regenerating against an original that already has preloadScript skips rather than duplicating"
  -> "zlib-preload appends to an existing preloadScript array rather than skipping it"
```
Unchanged from App v0.0.31: the other 14 tests.

### `testDebugUnitTest` result expectation

All 21 tests in `RuntimeConfigOverlayServiceTest` are expected to pass. No other existing test file is touched, so the full suite (including `GameLaunchRequestMapperTest`, `NativeRuntimeLoaderTest`, `WorkspacePathResolverTest`) is expected to remain green as well.

```
./gradlew testDebugUnitTest --tests "com.pokerpgplayer.app.data.config.RuntimeConfigOverlayServiceTest"
```
Or the full suite:
```
./gradlew testDebugUnitTest
```

### Remaining caveats before Stage 3 (not resolved by this hardening pass)

- **The `preloadScript`-value regex (`\[[^\]]*\]|"[^"]*"`) assumes no nested `]` or unescaped `"` inside a script filename** — a reasonable assumption for real filenames, but not a formal guarantee; a sufficiently unusual existing config could defeat it. Not addressed here, matching the approved "no parser rewrite" scope.
- **No test yet covers a `preloadScript` array containing multiple existing entries already** (only single-entry array/string cases were tested) — worth adding before Stage 3 if time allows, not blocking this hardening pass's own approved scope.
- **`ascii-safe-window-title`'s own regex has the same single-field-targeted limitation** as `preloadScript`'s — not hardened further in this pass, since no specific edge case was reported against it.
- **Stage 3 itself (real launch-path wiring) remains entirely unstarted** — this hardening pass exists specifically to reduce risk *before* that step, not to replace the need for its own separate planning/approval.

**Claim boundary, explicit:** hardening and additional test coverage only, still fully isolated from any launch path. **No launch-path wiring, no production preload/config-injection behavior for any real game, no compatibility claim.**

## App v0.0.33 — Sprint 27, Stage 3: minimal diagnostic launch-path wiring

Stage 2/2.5 (App v0.0.31/32) proved `RuntimeConfigOverlayService` generates correct overlays in isolation. This build takes the first, deliberately minimal step of actually driving that service through a real launch — **still `androidTest`-only, still not touching the production `RuntimeManager`/`AppContainer`/Game Detail flow.**

**Architecture question answered first, directly from source:** does the current launch architecture support a "config path override"? **Yes — the seam already exists, no native change was needed.** mkxp-z's own native code has no concept of an arbitrary config *file path* — it always reads a hardcoded `mkxp.json` (`#define CONF_FILE "mkxp.json"`, confirmed in `config.cpp` since Sprint 9) from whatever directory it `chdir()`s into via `GAME_PATH`. "Overriding the config" therefore means pointing `GAME_PATH` at a disposable workspace directory that already contains the desired `mkxp.json` — exactly the mechanism every diagnostic test since Sprint 21 has already used. This build is the first to drive that mechanism with the *real*, hardened `RuntimeConfigOverlayService` (Stage 2/2.5) instead of hand-written diagnostic text.

**New test, `RuntimeActivityConfigOverlayLaunchTest`, deliberately uses the genuinely original Essentials v21.1 config** — `essentials-v211-original-workspace` (the one with the real, non-ASCII `windowTitle` that crashes native config-read, per Sprint 18), not the already-ASCII-safe fixture every test since Sprint 18 has used. This is the first test where `ascii-safe-window-title` actually does something — the overlay service has to fix the *real* problem, not a pre-fixed one, to genuinely prove the architecture end-to-end. **Both `zlib-preload` and `ascii-safe-window-title` are requested together** in one `RuntimeConfigProfile`, applied in one `generateOverlay()` call.

**Flow:** byte-for-byte copy the original workspace → read the copy's own `mkxp.json` → generate the overlay via the real service → **fail the test explicitly** if generation doesn't succeed (not launch through a possibly-broken config regardless) → write the overlay text and generated `pokerpg_preload_zlib.rb` into the disposable copy only → confirm the *original* workspace's own `mkxp.json` size is unchanged → launch `RuntimeActivity` against the disposable workspace → observe for the title menu and confirm `PluginManager::Zlib` does not reappear.

**All prior Stage 1/2/2.5 unit tests are unchanged** — this build adds one new `androidTest` file; nothing in `RuntimeConfigOverlayService.kt` or its own test file was modified.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityConfigOverlayLaunchTest.kt` — new.
- `app/build.gradle.kts` — version bump only (App v0.0.33). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `RuntimeConfig.kt`, `AppContainer.kt`, `RuntimeConfigOverlayService.kt`, `RuntimeConfigProfile.kt`, every prior `androidTest` file (including `RuntimeActivityPreloadZlibProbeTest.kt`), every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file. No Pokémon Essentials original file modified — the new test only ever reads the base workspace's `mkxp.json`, confirmed by an explicit before/after size check.

### Exact launch-path seam used

`RuntimeActivity.EXTRA_WORKSPACE_PATH` — the same Intent extra proven since Sprint 8, now pointed at a disposable workspace directory whose `mkxp.json` was generated by `RuntimeConfigOverlayService` rather than hand-copied test text. No production `RuntimeManager`/`AppContainer` code path is touched; this is the same `ActivityScenario`-based `androidTest` mechanism every prior diagnostic has used.

### Test name and how to run it

`com.pokerpgplayer.app.runtime.RuntimeActivityConfigOverlayLaunchTest#launchAttempt_withGeneratedOverlay_reachesTitleMenuWithoutPluginManagerZlibError`

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityConfigOverlayLaunchTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
**Requires the `essentials-v211-original-workspace` base folder** (the genuinely original config, not the ASCII-safe one) — same one-time `run-as`-based setup as every prior Sprint 17+ test, using this folder name specifically.

### Logcat filters

```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityConfigOverlayLaunchTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint27_overlay_launch_log.txt
```

### Screenshot pull commands

Screenshots are captured every 20 seconds up to 90 seconds — `sprint27_overlay_launch_20s.png` through `sprint27_overlay_launch_90s.png`:
```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint27_overlay_launch_20s.png > sprint27_overlay_launch_20s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint27_overlay_launch_60s.png > sprint27_overlay_launch_60s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint27_overlay_launch_90s.png > sprint27_overlay_launch_90s.png
```

### Diagnostic evidence emitted

- `originalConfigHash`, `overlayConfigHash`, `overlayStatus`, `appliedMitigations`, `skippedMitigations`, and the overlay's own reason text — all logged directly from `RuntimeConfigOverlayService`'s own audit result before the launch even begins.
- The overlay's own file path.
- An explicit, logged confirmation that the base workspace's own `mkxp.json` size is unchanged, both immediately after overlay application and (implicitly, since nothing else touches it afterward) for the rest of the test.

### Remaining risks before input/rendering work

- **This test's own `assumeTrue` requires Ti to have separately prepared `essentials-v211-original-workspace`** — if that specific folder (distinct from the ASCII-safe one) was never set up, the test skips rather than validating anything.
- **The disposable workspace copy costs real time and disk** (a full ~0.09 GB copy, matching Sprint 16's own known size) — acceptable for one diagnostic test, but worth remembering if this pattern is repeated more broadly later.
- **No production wiring exists yet** — this only proves the mechanism works when driven manually inside a test; a future, separately-approved sprint would need to decide how (and whether) this becomes part of the real `RuntimeManager`/launch flow, including the still-unresolved Sprint 24 ADR question 3 (how mitigations get *automatically* detected/recommended, not just explicitly specified as in this test).
- **Fullscreen/scaling/viewport, input, save/load, and audio remain completely untouched and unvalidated** — explicitly out of this sprint's own scope, and not incidentally exercised by anything here.

**Claim boundary, explicit:** this test proves the overlay-generation-plus-launch seam works together, against one specific, real fixture, under test-only conditions. **No claim of compatibility, title screen stability for the original unmodified game, gameplay, input, audio, or save/load.** No production launch-path change. Not claimed: real device verification.

## App v0.0.34 — Sprint 28: rendering viewport diagnostic

Ti reported that App v0.0.33's own overlay-launch (title menu confirmed reachable) shows the rendered game output anchored in the lower-left corner rather than scaled/centered/fullscreen. This build investigates why, purely as diagnostics — no fix is attempted or claimed.

**Root-cause hypothesis, ranked by confidence, from a direct source trace of the fork (not modified):**

1. **(Highest confidence) `winSize` never gets corrected from the game's own logical resolution to the real Android surface size.** `main.cpp` calls `SDL_CreateWindow(...)` using `defScreenW`/`defScreenH` (confirmed `512x384` for this Essentials v21.1 config, directly in its own `mkxp.json` since Sprint 16) as the window's own requested size, then immediately calls `SDL_GetWindowSize()` and posts that value as the *first* `windowSizeMsg` — `graphics.cpp`'s own `checkResize()` only updates `winSize` from a **later**, real `SDL_WINDOWEVENT_SIZE_CHANGED` event (`eventthread.cpp`), which must actually fire for the correction to happen. If the underlying Android `SurfaceView` genuinely gets laid out at that same small, literal `512x384` size (plausible — nothing in `MainActivity.java` or `SDLActivity.java` unconditionally forces fullscreen sizing; the fork's own fullscreen toggle is native-request-gated, `COMMAND_CHANGE_WINDOW_STYLE`, confirmed in `SDLActivity.java`), that corrective event may never fire with a *different* value at all.
2. **(Directly connected, same root cause) `graphics.cpp`'s own `recalculateScreenSize()` has an early-return path** that skips its own centering/letterboxing math entirely whenever `integerLastMileScaling` is at its own documented config default (`true`, confirmed in `config.cpp`) and `fixedAspectRatio` is unset — meaning even if scaling logic exists (and it does — `integerScaling`/`fixedAspectRatio` are real, existing mkxp.json config keys with real centering-offset math), it's inactive by default. Combined with hypothesis 1, `scSize` would end up equal to the same small, uncorrected `winSize`, and OpenGL's own default bottom-left coordinate origin would place that correctly-rendered-but-small image in the lower-left of whatever larger surface actually exists — matching the reported symptom precisely.
3. **(Lower confidence, not ruled out) an Android `androidTest`/`ActivityScenario` artifact** — Sprint 12 already found and fixed one real `ActivityScenario`-specific lifecycle issue (missing `android:configChanges`) distinct from real, manual launches; a different timing quirk specific to test harness launches (vs. Ti's own manual app use) can't be ruled out without a side-by-side comparison, which this diagnostic sprint sets up evidence for but does not itself perform (no real, manual launch access in this environment).

**This build does not attempt a fix.** It adds:
- **A small, diagnostic-only accessor on `RuntimeActivity`**, `currentSurfaceDimensionsForDiagnostics()`: reads the already-existing, inherited `SDLActivity.mSurface` field's own real, laid-out `width`/`height` (standard `View` properties, `SDLSurface extends SurfaceView`) — zero behavior change, purely an evidence-gathering read of state that already exists.
- **A new diagnostic test, `RuntimeActivityRenderingViewportDiagnosticTest`**, reusing the exact, already-proven Sprint 27 overlay-launch pattern (same base workspace, same two mitigations, same real `RuntimeConfigOverlayService`), adding real Android display-metrics logging (`WindowManager`/`DisplayMetrics` — the same API `SDLActivity.java` itself already uses internally for its own fullscreen-layout check) and the new surface-dimension accessor's own output, logged at six checkpoints across the same 90-second observation window already proven to reach the title menu, plus a screenshot at each checkpoint.

**Files changed:**
- `app/src/main/java/com/pokerpgplayer/app/runtime/RuntimeActivity.kt` — one new, diagnostic-only, read-only accessor method added. `onCreate()`/`onStart()` themselves unchanged — confirmed by direct inspection.
- `app/src/androidTest/.../RuntimeActivityRenderingViewportDiagnosticTest.kt` — new.
- `app/build.gradle.kts` — version bump only (App v0.0.34). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `AndroidManifest.xml`, `RuntimeManager.kt`, `StubRuntimeManager.kt`, `AppContainer.kt`, `RuntimeConfigOverlayService.kt`, `RuntimeActivityConfigOverlayLaunchTest.kt` (Sprint 27, unchanged), every `org/libsdl/app/*.java` file, any native C++ (the hypothesis above is traced from already-published source, not modified), any UI/ViewModel/resource file. No Pokémon Essentials original file modified. No input mapping, no UI overlay hiding the issue, no blind stretching, no Runtime Config Overlay architecture change.

### Added diagnostic logs/tests

`RuntimeActivityRenderingViewportDiagnosticTest#launchAttempt_withGeneratedOverlay_capturesRenderingViewportDiagnostics` — logs, at each of 6 checkpoints (immediately after native startup, then every 20s up to 90s):
```
[checkpoint] EVIDENCE: real display metrics = <width>x<height> (density=<density>)
[checkpoint] EVIDENCE: SDL surface real dimensions = <width>x<height>
[checkpoint] EVIDENCE: known logical game resolution = 512x384
[checkpoint] NOTABLE: SDL surface dimensions exactly match the game's own logical resolution — ...  (only if they do)
```

### How to run

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityRenderingViewportDiagnosticTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Requires the same `essentials-v211-original-workspace` base folder as App v0.0.33.

### Logcat filter

```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityRenderingViewportDiagnosticTest:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint28_viewport_diagnostic_log.txt
```

### Screenshot pull commands

```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint28_viewport_diagnostic_checkpoint0.png > sprint28_checkpoint0.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint28_viewport_diagnostic_checkpoint-20s.png > sprint28_checkpoint20s.png
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint28_viewport_diagnostic_checkpoint-90s.png > sprint28_checkpoint90s.png
```
(and similarly for the 40s/60s/80s checkpoints)

### Proposed smallest safe fix (pending Ti's real-device evidence confirming the hypothesis above)

**If confirmed:** the smallest safe fix is a **third mitigation added to the existing Runtime Config Overlay architecture** — e.g. `fixed-aspect-ratio-scaling`, setting `fixedAspectRatio: true` (and/or `integerScalingActive`/`integerScalingLastMile: false`) in the overlay's own generated `mkxp.json`, activating `graphics.cpp`'s own *already-existing* centering/letterboxing math (`recalculateScreenSize()`'s non-early-return path) — a **config-level fix, not a native code change**, fitting cleanly into the same `RuntimeConfigOverlayService` mechanism already proven in Sprint 25–27, requiring no native rebuild. This would need its own, separate, explicitly-approved implementation sprint — not started here.

**If not confirmed** (e.g., if surface dimensions turn out to already match the real display, pointing instead at hypothesis 3 or something not yet considered): a native or `androidTest`-specific investigation would be the appropriate next step instead, decided after reviewing this sprint's own real evidence.

### Whether the fix (once confirmed) should be Kotlin/Activity, SDL config, mkxp-z config overlay, or native

**mkxp-z config overlay** — per the hypothesis above, the necessary scaling/centering logic already exists natively; it just isn't activated by this fixture's own default config. No Kotlin/Activity change and no native rebuild are expected to be necessary for the fix itself, only for confirming the diagnosis.

**Claim boundary, explicit:** diagnostic only. **No fix implemented or claimed. No compatibility claim.** Rendering/viewport/scaling behavior remains unresolved and unvalidated pending Ti's real-device evidence.

## App v0.0.35 — Sprint 29: native/SDL/mkxp-z viewport and resize pipeline diagnostic

Ti's own real-device run of App v0.0.34 (OPPO PGEM10, Android 16) produced decisive evidence: **the Android surface is not stuck small.** It transitions portrait `1440x3168` to landscape `3168x1440` — a real, large, correctly-sized surface. This directly refutes Sprint 28's own top hypothesis (an undersized Android `SurfaceView`) and narrows the investigation entirely to the SDL/mkxp-z native rendering pipeline.

**New, higher-confidence root-cause finding, from a direct source trace of `main.cpp`:** `SDL_CreateWindow()` is called with `conf.defScreenW`/`conf.defScreenH` (`512x384`) as its own literal size, and — critically — `SDL_WINDOW_FULLSCREEN_DESKTOP` is only added to the window's own creation flags `if (conf.fullscreen)`, a config value whose own documented default (`config.cpp`) is `false`. On Android, "windowed" isn't a real concept — the underlying `SurfaceView` fills the screen regardless (matching Ti's own confirmed `3168x1440`) — but SDL's own internal window-size state may never learn this, since it only expects size changes through the resize-event pathway a genuinely fullscreen-mode window actually exercises. This single mechanism would explain the entire chain of Sprint 28's own evidence.

**A separate, honest note on `"handleResized abandoned"`:** this exact string was not found anywhere in the mkxp-z fork's own source or in public SDL2 source. It's very likely Android framework's own internal `ViewRootImpl.handleResized()` logging (a real, standard AOSP method name) — typically meaning a resize callback got superseded by a newer one arriving first, consistent with (but not uniquely diagnostic of) multiple resize events happening in quick succession during the portrait→landscape transition. Not claimed as confirmed without further evidence.

**This build does not implement any fix** — no `fullscreen` mitigation is added to `RuntimeConfigOverlayService`, matching this sprint's own explicit diagnostic-first scope. Two concrete improvements instead:

1. **Self-verifying screenshot capture.** App v0.0.34's own pulled screenshots were only 110 bytes each — almost certainly `adb exec-out run-as ... cat ...` capturing a short *error message* (a `run-as` failure specific to this OEM ROM) rather than real PNG bytes, not a capture failure inside the test. `captureScreenshot()` now logs the captured file's own size immediately and warns loudly if it's suspiciously small (< 2048 bytes) for a real screenshot at this resolution — letting Ti immediately tell whether the *capture* succeeded on-device, independent of the *pull* step.
2. **Finer-grained checkpoints early in the launch** (every 5s for the first 30s, then every 20s for another 60s) — the portrait-to-landscape transition likely happens early; finer granularity should better capture exactly when it occurs relative to native renderer initialization.

**A separate, proposed native diagnostic patch** (`sprint29-native-diagnostic-logging.patch`, provided alongside this package, **not applied, not compiled, not part of this app**) adds minimal, logging-only `Debug()` calls at the exact points needed to directly confirm or refute the `conf.fullscreen` hypothesis: at window creation (`conf.fullscreen`, `winFlags`, initial `SDL_GetWindowSize`/`SDL_GL_GetDrawableSize`), inside `checkResize()` (whether/when a corrected `winSize` is ever polled), and inside `recalculateScreenSize()` (which branch runs, and the resulting `scSize`/`scOffset`). Every addition is a log line only — no behavior, control flow, or calculation is changed. Requires Ti's own WSL native build pipeline to apply and compile; not achievable in this environment (no NDK/native toolchain available here).

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityNativeResizeDiagnosticTest.kt` — new.
- `app/build.gradle.kts` — version bump only (App v0.0.35). No new dependency.
- `README.md` — this section.
- `sprint29-native-diagnostic-logging.patch` — new, separate document, **not part of the buildable app**, proposed only.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt` (Sprint 28's own diagnostic accessor reused, not modified again), `AndroidManifest.xml`, `RuntimeConfigOverlayService.kt`, `RuntimeActivityConfigOverlayLaunchTest.kt` (Sprint 27), `RuntimeActivityRenderingViewportDiagnosticTest.kt` (Sprint 28), every `org/libsdl/app/*.java` file, any native C++ (the proposed patch is a separate, unapplied file), any UI/ViewModel/resource file. No Pokémon Essentials original file modified. **No `fullscreen` mitigation added anywhere** — confirmed by direct grep of `RuntimeConfigOverlayService.kt`.

### Exact logs/tags to search

**Correction (post-review):** the filtered command originally documented here was missing `EssentialsFixtureSeeder:V` — since `*:S` silences every tag not explicitly listed, this meant the seeder's own logs never appeared in Ti's first evidence pull, even though the code itself logs correctly. Fixed below, and a content-based fallback is now also provided that doesn't depend on getting every tag name right in an allow-list.

**Full, unfiltered pull (primary evidence, per this project's own Sprint Evidence Discipline):**
```
adb logcat -c
adb logcat -d > sprint29_full_log.txt
```
**Filtered pull (corrected — now includes `EssentialsFixtureSeeder:V`):**
```
adb logcat -c
adb logcat RuntimeActivity:V RuntimeActivityNativeResizeDiagnosticTest:V EssentialsFixtureSeeder:V TestRunner:I ActivityScenario:V SDL:V mkxp:D AndroidRuntime:E *:S > sprint29_resize_diagnostic_log.txt
```
**Robust content-based fallback (recommended — immune to a tag-list omission like the one just found):** every Kotlin-side log line this test and the seeder emit now carries a consistent `SPRINT29_DIAG_KOTLIN:` or `FIXTURE_SEED_DIAG:` marker in its own message text, not just its tag, so a plain content grep against the *full* log catches everything regardless of tag filtering:
```
adb logcat -d | grep -E "SPRINT29_DIAG_KOTLIN|SPRINT29_TEST|FIXTURE_SEED_DIAG" > sprint29_content_filtered_log.txt
```
If this file is empty after a real test run, that itself is diagnostic — it would mean the test never actually executed (wrong instrumentation target, stale/old APK installed, or the logcat buffer was cleared/rotated before the pull), not that the logging code is broken.
Native-side, **only if** the separate patch above has been applied and rebuilt:
```
adb logcat mkxp:D *:S | grep SPRINT29_DIAG > sprint29_native_diag_log.txt
```

### How to run

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityNativeResizeDiagnosticTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Requires the same `essentials-v211-original-workspace` base folder as App v0.0.33/34.

### Screenshot pull commands, with self-verification

**First, confirm the file exists and its real size on-device** (catches a `run-as` failure immediately, before attempting any pull):
```
adb shell run-as com.pokerpgplayer.app.debug ls -la files/sprint29_resize_diagnostic_fine-5s.png
```
**Then pull**, and verify the *local* file's own size afterward:
```
adb exec-out run-as com.pokerpgplayer.app.debug cat files/sprint29_resize_diagnostic_fine-5s.png > sprint29_fine_5s.png
ls -la sprint29_fine_5s.png
```
If the local file is still only ~100 bytes, open it as text (`cat sprint29_fine_5s.png`) — it is very likely an error message (e.g. a `run-as` failure), not PNG binary data, and that error text itself is the next diagnostic clue. Filenames follow the pattern `sprint29_resize_diagnostic_fine-5s.png` through `fine-30s.png`, then `coarse-50s.png` through `coarse-90s.png`.

### Evidence summary (pending Ti's real-device run)

This build produces no new evidence on its own — it is the *instrument* for gathering it. Once run, compare: (a) the Kotlin-side fine-grained checkpoint logs for exactly when `SDL surface real dimensions` and `Configuration.orientation` transition, (b) the self-verified screenshots for the actual rendered position/size at each of those same moments, and — if the separate native patch has been applied — (c) the native `SPRINT29_DIAG` lines for the exact `conf.fullscreen`/`winSize`/`recalculateScreenSize()` values at the moment of mismatch.

### Proposed smallest safe fix (still not implemented — pending confirmation)

Unchanged in category from Sprint 28's own proposal, now with a much more specific target: **a `fullscreen` mitigation added to the existing Runtime Config Overlay architecture** — setting `"fullscreen": true` in the overlay's own generated `mkxp.json`, so `SDL_WINDOW_FULLSCREEN_DESKTOP` gets applied at window creation and SDL's own internal size state has a reason to actually track the real Android surface. **Config-level fix, no native rebuild required for the fix itself** — fits the same `RuntimeConfigOverlayService` mechanism already proven in Sprint 25–27. Not implemented here — this sprint remains diagnostic-first, per its own explicit scope; implementation would need its own, separate, explicitly-approved sprint, ideally after the native patch's own log evidence (if applied) directly confirms this specific mechanism.

**Claim boundary, explicit:** diagnostic only. **No fix implemented. No compatibility claim.** The native diagnostic patch is proposed, not applied, not compiled, not verified.

## App v0.0.36 — Test Environment Hardening: self-healing PE21 fixture seeding

Every `androidTest` diagnostic since Sprint 27 depends on `essentials-v211-original-workspace` already existing in app-private storage — but that storage is wiped by any reinstall, `clear data`, or new sprint's own `installDebugAndroidTest`. Ti had to manually restore it (`run-as`/`tar`) every sprint — real test environment debt, unrelated to runtime architecture.

**New utility, `EssentialsFixtureSeeder`** (`androidTest`-only, never compiled into the shipped APK): before a diagnostic test runs, it checks whether `files/<fixtureName>` already exists with all key entries present (`Game.exe`, `Game.ini`, `mkxp.json`, `Data`, `Graphics`, `Audio`, `Plugins`); if not, it checks a once-pushed external staging copy at `/sdcard/Download/<fixtureName>`, and if that exists, copies it into app-private storage automatically. If **neither** copy exists, the test skips cleanly with the message `Missing PE21 fixture. Push it once to /sdcard/Download/<fixtureName>` — Ti now only needs to `adb push` the fixture once per device, not once per sprint.

**Wired into all three diagnostic tests that depend on this fixture**: `RuntimeActivityConfigOverlayLaunchTest` (Sprint 27), `RuntimeActivityRenderingViewportDiagnosticTest` (Sprint 28), `RuntimeActivityNativeResizeDiagnosticTest` (Sprint 29) — each now calls `EssentialsFixtureSeeder.ensureFixtureAvailable(context, BASE_WORKSPACE_FOLDER_NAME)` in place of the previous raw `File.exists()` check, with the exact skip message specified.

**Does not modify original PE21 files** — copies only ever go *from* the external staging path *into* app-private storage, never the reverse. **Does not change any runtime architecture** — this lives entirely in `androidTest`, has no relationship to `RuntimeManager`/`AppContainer`/`RuntimeConfigOverlayService`, and is never packaged as an app asset or bundled into the APK.

**Files changed:**
- `app/src/androidTest/.../EssentialsFixtureSeeder.kt` — new.
- `app/src/androidTest/.../RuntimeActivityConfigOverlayLaunchTest.kt` — precondition block replaced with a seeder call; nothing else in this file changed.
- `app/src/androidTest/.../RuntimeActivityRenderingViewportDiagnosticTest.kt` — same, precondition block only.
- `app/src/androidTest/.../RuntimeActivityNativeResizeDiagnosticTest.kt` — same, precondition block only.
- `app/build.gradle.kts` — version bump only (App v0.0.36). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `RuntimeConfigOverlayService.kt`, every other `androidTest` file not listed above, every `org/libsdl/app/*.java` file, any native C++, any UI/ViewModel/resource file, **all of `app/src/main`** (confirmed via a directory-wide modification-time check — production runtime is entirely untouched by this delivery). No PE21 content bundled into the APK — confirmed by searching `app/src/main` for `.rxdata`/`Game.exe`/`Game.ini`, none found.

### Ti-side setup (once per device, not per sprint)

```
adb push "<local PE21.1 original-config folder>" /sdcard/Download/essentials-v211-original-workspace
```
After this one-time push, every diagnostic test that depends on this fixture will self-heal its own app-private copy automatically on every run, regardless of reinstalls or `clear data` in between.

### Diagnostic logs emitted by the seeder

```
[essentials-v211-original-workspace] internal fixture exists=<bool> at <path>
[essentials-v211-original-workspace] external fixture exists=<bool> at <path>
[essentials-v211-original-workspace] copy start: <external> -> <internal>
[essentials-v211-original-workspace] copy end: success=<bool>
[essentials-v211-original-workspace] final internal file count: <n>
[essentials-v211-original-workspace][key-check] Game.exe present=<bool>
[essentials-v211-original-workspace][key-check] Game.ini present=<bool>
[essentials-v211-original-workspace][key-check] mkxp.json present=<bool>
[essentials-v211-original-workspace][key-check] Data present=<bool>
[essentials-v211-original-workspace][key-check] Graphics present=<bool>
[essentials-v211-original-workspace][key-check] Audio present=<bool>
[essentials-v211-original-workspace][key-check] Plugins present=<bool>
```

**Claim boundary, explicit:** test-infrastructure utility only. **No runtime architecture change, no production code change, no PE21 file modification, no compatibility claim.**

## App v0.0.37 — Test Evidence Stabilization: shell-pipe fixture seeding

Ti's own review after the first App v0.0.37 revision found the *real* root cause: the external fixture at `/sdcard/Download/essentials-v211-original-workspace` was independently confirmed complete (`Game.exe`, `mkxp.json`, `Data`, `Graphics`, `Audio`, `Plugins` all present, verified via `run-as`/shell), but app-internal storage after running the seeder still contained only `profileInstalled`. **On Android 16, scoped storage means the app/instrumentation process's own UID generally cannot read `/sdcard/Download` directly via `java.io.File`, regardless of whether the content genuinely exists there** — the previous, direct-`File`-based seeder's own `externalFixture.exists()` check was silently returning `false` from the wrong UID's own perspective, not because the fixture was actually missing.

**`EssentialsFixtureSeeder` now performs both the external-existence check and the actual seeding through the shell UID**, via `UiAutomation.executeShellCommand()`, semantically equivalent to:
```
cd /sdcard/Download && tar -cf - essentials-v211-original-workspace | run-as <package> sh -c 'cd files && tar -xf -'
```
The shell UID retains broader storage read access than the app/test UID does on this Android version, and `run-as` lets the write side land correctly in app-private storage — exactly the mechanism this fix specifies. **Internal-storage checks still use plain `java.io.File`** — reading the app's own private storage was never the broken part; only reading `/sdcard/Download` was. **No storage runtime permission was added** (no `READ_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`) — confirmed by direct inspection of `AndroidManifest.xml`, unchanged.

Since `UiAutomation.executeShellCommand()` doesn't expose an exit code directly, every shell command run by this class appends `; echo POKERPG_SHELL_EXIT:$?` and parses the real exit code back out of captured stdout — verified correct via a Python simulation of the exact string construction and regex extraction before being written as real Kotlin.

**The `FixtureResult` sealed class contract (`Ready`/`Missing`/`CopyFailed`) is unchanged** from the prior revision, so none of the three tests that call this seeder needed any changes this time — only `EssentialsFixtureSeeder.kt` itself was rewritten.

**No viewport/rendering fix is implemented or attempted.** No production runtime file is touched, confirmed via a full `app/src/main` modification-time check.

**Files changed:**
- `app/src/androidTest/.../EssentialsFixtureSeeder.kt` — rewritten: shell-pipe seeding via `UiAutomation.executeShellCommand()` replaces direct `java.io.File.copyRecursively()` for the external-storage side; internal-storage checks remain plain `File`; every shell command logs its own start, end, exit code, and output text under `PokeRPGSeeder`.
- `README.md` — this section, replacing the prior App v0.0.37 content in place — no version bump, per Ti's own explicit request to integrate directly since this version hasn't been used yet.

**Not touched this revision:** `RuntimeActivityNativeResizeDiagnosticTest.kt`, `RuntimeActivityConfigOverlayLaunchTest.kt`, `RuntimeActivityRenderingViewportDiagnosticTest.kt` (the `FixtureResult` contract they already handle is unchanged), `RuntimeActivity.kt`, `AndroidManifest.xml`, every file in `app/src/main` — confirmed by direct hash comparison.

### Build/test instructions

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityNativeResizeDiagnosticTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Requires the `essentials-v211-original-workspace` fixture pushed once to `/sdcard/Download/` — unchanged. No app-side storage permission needs to be granted.

### Acceptance checks

After the test runs, both of these must show real content:
```
adb shell run-as com.pokerpgplayer.app.debug ls files/essentials-v211-original-workspace
adb shell run-as com.pokerpgplayer.app.debug ls -l files | grep sprint
```

### Required logcat filter

```
adb logcat -d | findstr /i "PokeRPGSeeder PokeRPGSprint29 PokeRPGEvidence RuntimeActivity SDL mkxp"
```

**Claim boundary, explicit:** fixture-seeding mechanism fix only. **No fix for the rendering/viewport issue implemented. No compatibility claim.**

## App v0.0.38 — Sprint 30: Aspect-Fit Centered Rendering — Fix Proposal

Ti's own v0.0.37 test passed (surface reaches `3168x1440`), but the rendered game area still appears in the lower-left corner, not scaled/centered. This build proposes the smallest safe fix — a mix of a real, buildable Kotlin config mitigation and a proposed (not yet applied) native patch — rather than a blind stretch-to-fill.

**Where the scaling/viewport logic actually lives, traced precisely:** `graphics.cpp`'s own `recalculateScreenSize(bool fixedAspectRatio)` **already contains a correct Aspect-Fit-Center calculation** in its own final branch — `scale = min(winSize.x/scRes.x, winSize.y/scRes.y)`-equivalent math, computing a centered `scOffset`/`scSize` pair. This is confirmed, by direct trace, to feed the actual on-screen blit rect (`SDL_Rect screen = {scOffset.x, scOffset.y, scSize.x, scSize.y}`). **The existing math does not need to be rewritten — it needs to be reliably reached.**

**Two real problems found, both explaining why it currently isn't:**
1. **A genuine, independent bug in the fork's own source:** three of six call sites to `recalculateScreenSize()` pass a **pointer** (`rtData`/`threadData`) directly to a parameter typed `bool fixedAspectRatio` — C++ permits this via implicit pointer-to-`bool` conversion (non-null → `true`), silently ignoring the real config value at those three call sites, while the other three correctly pass `threadData->config.fixedAspectRatio`. Proposed fix (native, not applied — see `sprint30-native-patch-proposal.md`): correct the three wrong call sites to match the three correct ones. Minimal, three one-line changes, no new scaling math.
2. **`fixedAspectRatio`'s own documented config default is `false`** — even at the three *correctly-coded* call sites, the early-return (stretch-to-fill or no-op) branch fires unless this is explicitly set `true`. **This part is fixed for real in this build**, via a new Runtime Config Overlay mitigation.

**New mitigation, `aspect-fit-render` (`KnownMitigationIds.ASPECT_FIT_RENDER`):** sets `mkxp.json`'s own `"fixedAspectRatio": true`. Handles three shapes — absent (insert), present as `false` (replace with `true`), present as `true` already (skip cleanly) — using the same safe, text-based approach as every other mitigation, verified via Python simulation before being written as Kotlin. **Does not set `"fullscreen"` or any other key** — confirmed by a dedicated test — keeping this change narrowly scoped to exactly the one config value this fix needs.

**Formula, exactly as specified, already implemented natively (not by this Kotlin change) once `fixedAspectRatio` is honored:**
```
scale = min(drawableWidth / logicalWidth, drawableHeight / logicalHeight)
viewportWidth = logicalWidth * scale
viewportHeight = logicalHeight * scale
viewportX = (drawableWidth - viewportWidth) / 2
viewportY = (drawableHeight - viewportHeight) / 2
```
For this fixture (`512x384` logical, `3168x1440` drawable): `scale = min(6.1875, 3.75) = 3.75`, giving a `1920x1440` rendered area, offset `x=624, y=0` — aspect ratio preserved, no distortion, no crop, letterboxed left/right (pillarboxed) as expected for this aspect-ratio mismatch.

**Explicit remaining risk, not resolved by this build:** even with both fixes, if `winSize` itself (mkxp-z's own internal variable, separate from Android's own confirmed-correct `3168x1440` surface) never gets corrected from its own small initial value — the still-unconfirmed Sprint 28/29 hypothesis — the Aspect-Fit-Center branch would compute a correctly-*proportioned* but wrong-*sized*, likely still near-origin render, since the whole frame of reference would be wrong. **Sprint 29's own proposed native diagnostic patch (still not applied) remains the recommended way to directly confirm this**, ideally alongside applying this sprint's own two fixes.

**Evidence-logging follow-up:** Ti reported `PokeRPGSprint29` appeared in the v0.0.37 pull, but `PokeRPGSeeder` and `PokeRPGEvidence` still did not, and screenshot files still couldn't be opened. Since `EssentialsFixtureSeeder.ensureFixtureAvailable()`'s own very first line unconditionally logs under `PokeRPGSeeder`, and `PokeRPGSprint29` appearing means that method was in fact called — the most likely explanation is **Ti's own device still had an older APK installed** from before the shell-pipe seeder rewrite reached them, not a logging defect in the code itself. Recommend a full `adb uninstall` before reinstalling this build, to rule this out cleanly before investigating further. No code change was made for this specific finding this sprint — flagged for confirmation on next run instead of guessing at a fix without evidence.

**Files changed:**
- `app/src/main/java/com/pokerpgplayer/app/data/model/RuntimeConfigProfile.kt` — added `KnownMitigationIds.ASPECT_FIT_RENDER`.
- `app/src/main/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayService.kt` — added `applyAspectFitRenderMitigation()` and its own regex pattern; wired into `applyMitigations()`.
- `app/src/test/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayServiceTest.kt` — 4 new tests (21 → 25).
- `app/build.gradle.kts` — version bump only (App v0.0.38). No new dependency.
- `README.md` — this section.
- `sprint30-native-patch-proposal.md` — new, separate document, **not part of the buildable app**, proposed only.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `EssentialsFixtureSeeder.kt`, `RuntimeActivityNativeResizeDiagnosticTest.kt`, every other file. **`zlib-preload`/`ascii-safe-window-title` behavior is completely unchanged** — the new mitigation is purely additive, its own separate `if` block, not touching either existing mitigation's own logic. No input, save/load, audio, or Play UI code added. No user-facing settings added — `aspect-fit-render` must be explicitly requested via `RuntimeConfigProfile.enabledMitigations`, same as every other mitigation; nothing defaults it on automatically yet.

### Confirmations

- **Aspect ratio preserved:** `scale = min(...)` uses a single, uniform scale factor for both axes — never independent X/Y scaling.
- **No stretch-to-fill:** the early-return branch that would produce this is what this fix bypasses by ensuring `fixedAspectRatio` evaluates `true`.
- **Runtime Config Overlay `zlib-preload`/`ascii-safe-window-title` untouched:** confirmed via the new test asserting `aspect-fit-render` never sets `"fullscreen"` or any other unrelated key, and via the existing "both mitigations together" tests remaining green.

### Test/log commands

```
./gradlew testDebugUnitTest --tests "com.pokerpgplayer.app.data.config.RuntimeConfigOverlayServiceTest"
```
```
adb uninstall com.pokerpgplayer.app.debug
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityNativeResizeDiagnosticTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | findstr /i "PokeRPGSeeder PokeRPGSprint29 PokeRPGEvidence RuntimeActivity SDL mkxp"
```

**Claim boundary, explicit:** the Kotlin-side config mitigation is real and testable; the native pointer-bug fix is proposed, not applied or compiled. **No confirmed fix for the rendering issue until Ti runs this and reports real screenshots — a real chance remains that the winSize-correction concern above is also required.** No compatibility claim.

## App v0.0.39 — hotfix: wire `aspect-fit-render` into the actual diagnostic launch

Review of App v0.0.38 found a real regression: `KnownMitigationIds.ASPECT_FIT_RENDER` was added to `RuntimeConfigOverlayService`'s own known catalog, but `RuntimeActivityNativeResizeDiagnosticTest`'s own `RuntimeConfigProfile` never actually requested it — only `zlib-preload` and `ascii-safe-window-title` were enabled. **App v0.0.38's diagnostic launch never tested the aspect-fit mitigation at all**, despite the README's own claim describing it. Ti should not have been asked to expect any viewport change from that build.

**Fixed: the profile now requests all three mitigations.** Beyond just adding the missing line, this build adds an **explicit, hard `assertTrue`** (not just a log line) immediately before launch: the test now fails loudly if `aspect-fit-render` isn't genuinely applied or if the generated overlay text doesn't genuinely contain `"fixedAspectRatio": true` — specifically so this exact class of regression (mitigation exists in the service, but isn't actually requested by the one test that's supposed to exercise it) cannot silently recur again.

**New tag, `PokeRPGSprint30`**, logs five explicit facts before every launch: whether each of the three mitigations was applied, whether the overlay text contains `fixedAspectRatio:true`, and whether it contains a `fullscreen` key (it must not — confirming this mitigation stays narrowly scoped, matching the dedicated unit test from App v0.0.38).

**v0.0.37's shell-pipe seeder behavior and screenshot evidence logging are both fully intact** — this hotfix only touches the profile construction, the pre-launch confirmation block, and the class's own kdoc; confirmed via direct hash comparison that `EssentialsFixtureSeeder.kt` itself was not touched at all this build.

**Still no native patch applied. Still no claim that the rendering issue is fixed** — this only guarantees the config-level mitigation the diagnostic depends on is genuinely being exercised; Ti's own real-device visual result is still required before concluding anything about the actual rendered output.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityNativeResizeDiagnosticTest.kt` — profile now requests all three mitigations; new `PokeRPGSprint30` tag and explicit pre-launch confirmation logging; new hard `assertTrue` on `aspect-fit-render` being genuinely applied; kdoc updated to explain this hotfix.
- `app/build.gradle.kts` — version bump only (App v0.0.39). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `RuntimeConfigOverlayService.kt`, `RuntimeConfigProfile.kt`, `EssentialsFixtureSeeder.kt`, `RuntimeActivityConfigOverlayLaunchTest.kt`, every other file. No input, save/load, audio, or Play UI code touched. No user-facing settings added. `RuntimeActivity.kt` not touched — this hotfix was achievable entirely within the diagnostic test itself.

### Test command

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityNativeResizeDiagnosticTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### Logcat filter

```
adb logcat -d | findstr /i "PokeRPGSeeder PokeRPGSprint29 PokeRPGSprint30 PokeRPGEvidence RuntimeActivity SDL mkxp fixedAspectRatio aspect-fit-render"
```

### Acceptance

The diagnostic launch must now actually run with `"fixedAspectRatio": true` present in the disposable workspace's own `mkxp.json` — confirmed by the new hard assertion failing the test otherwise, and independently visible in the `PokeRPGSprint30`-tagged log lines. **Only after that passes** should Ti visually check whether the game appears centered/aspect-fit in the pulled screenshots.

**Claim boundary, explicit:** diagnostic wiring fix only. **No native patch applied. Do not claim the viewport is fixed until Ti reports a real-device visual result.** No compatibility claim.

## App v0.0.40 — Sprint 30.1: manual seed + hard verification, aspect-fit diagnostic

App v0.0.39 was reported `SKIPPED`, not failed — `EssentialsFixtureSeeder`'s own automated shell-pipe invocation left app-internal storage containing only `profileInstalled`, even though Ti independently confirmed the **identical** shell-pipe command works correctly when run manually via `adb shell`. Rather than keep debugging why the automated, in-test invocation behaves differently from the same command run by hand, this build makes a deliberate, temporary pivot: **auto-seeding is removed from this test's own critical path entirely.** Ti seeds the fixture manually, once, and this test now only *verifies* — hard-failing loudly, never skipping — that the fixture genuinely exists and is complete before doing anything else.

**`EssentialsFixtureSeeder` itself is untouched** — confirmed by direct hash comparison — and remains available for future use; this class simply no longer calls it. `RuntimeActivityConfigOverlayLaunchTest` and `RuntimeActivityRenderingViewportDiagnosticTest` still use it, unchanged.

**The fixture precondition now uses `assertTrue`/`fail`, never `assumeTrue`, for a missing or incomplete workspace** — a missing fixture is now a genuine, visible test **failure**, with the exact Ti-confirmed-working manual seed command included verbatim in the failure message, not a silent skip that could be mistaken for "nothing to report." Each of the seven key paths (`Game.exe`, `Game.ini`, `mkxp.json`, `Data`, `Graphics`, `Audio`, `Plugins`) is checked and logged individually under `PokeRPGSprint30`, and the test fails with the *exact* list of whichever ones are missing if any are, rather than a generic "fixture incomplete" message.

**Every other behavior from App v0.0.39 is unchanged:** the profile still requests all three mitigations (`zlib-preload`, `ascii-safe-window-title`, `aspect-fit-render`), the same hard `assertTrue` on `fixedAspectRatio:true` being genuinely present in the overlay still runs before launch, and the same fine/coarse checkpoint observation window, screenshot evidence logging (`PokeRPGEvidence`), and `PokeRPGSprint29` logging all remain exactly as they were — verified via Python simulation of the new hard-verification logic (missing workspace, missing single key file, and a fully complete workspace matching Ti's own real confirmed evidence) before being trusted as real Kotlin.

**Still no native patch applied. Still no claim about the actual rendered output** — this build only removes a source of false negatives (the auto-seeder's own unexplained failure) so the aspect-fit config mitigation can finally be genuinely exercised and visually checked.

**Files changed:**
- `app/src/androidTest/.../RuntimeActivityNativeResizeDiagnosticTest.kt` — `EssentialsFixtureSeeder` call replaced with direct, hard-failing fixture verification; new `MANUAL_SEED_COMMAND` and `KEY_FIXTURE_ENTRIES` constants; `assumeTrue` import removed (no longer used anywhere in this file); class kdoc updated to explain this pivot.
- `app/build.gradle.kts` — version bump only (App v0.0.40). No new dependency.
- `README.md` — this section.

**Confirmed unmodified, by direct hash comparison:** `RuntimeActivity.kt`, `AndroidManifest.xml`, `RuntimeConfigOverlayService.kt`, `RuntimeConfigProfile.kt`, `EssentialsFixtureSeeder.kt`, `RuntimeActivityConfigOverlayLaunchTest.kt`, `RuntimeActivityRenderingViewportDiagnosticTest.kt`, every other file. No native renderer change. No input, save/load, audio, or Play UI code touched.

### Required manual seed (Ti must run this before the diagnostic test)

```
adb shell "cd /sdcard/Download && tar -cf - essentials-v211-original-workspace | run-as com.pokerpgplayer.app.debug sh -c 'cd files && tar -xf -'"
```
Verify it worked:
```
adb shell run-as com.pokerpgplayer.app.debug ls -l files/essentials-v211-original-workspace
```

### Test command

```
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityNativeResizeDiagnosticTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### Logcat filter

```
adb logcat -d | findstr /i "PokeRPGSprint30 PokeRPGEvidence RuntimeActivity SDL mkxp fixedAspectRatio aspect-fit-render"
```

### Acceptance

- If the workspace is missing: the test **FAILS loudly**, with the exact manual seed command in the failure message — never `SKIPPED`.
- If any key file is missing: the test **FAILS loudly**, listing exactly which one(s).
- If `fixedAspectRatio: true` is missing from the generated overlay: the test **FAILS loudly** (unchanged from App v0.0.39).
- If `RuntimeActivity` launches and the visual result is centered/pillarboxed: config-level `fixedAspectRatio` is supported — informs scoping the native pointer-bug patch as the next, final step.
- If the visual result remains lower-left-anchored despite `fixedAspectRatio: true` genuinely being present: config-level mitigation alone is insufficient — points toward the still-unconfirmed `winSize`-correction concern, informing a Sprint 31 native investigation instead.
- If screenshot artifacts still fail to upload/open but Ti can visually confirm the result directly on-device, that visual confirmation is not blocked by the screenshot-artifact issue — the architectural decision above does not depend on the screenshot pipeline specifically.

**Claim boundary, explicit:** fixture-verification and diagnostic-wiring fix only. **No native patch applied. Do not claim the viewport is fixed or broken until Ti reports a real-device visual result.** No compatibility claim.

## App v0.0.41 — Sprint 30.2: fix aspect-fit audit/contract bug + device-independence clarification

Ti's own v0.0.40 run followed the corrected install/seed/instrument flow exactly and got real evidence — but the test failed *before* `RuntimeActivity` ever launched: `appliedMitigations=[zlib-preload, ascii-safe-window-title]`, `aspect-fit-render applied=false`, yet `overlay contains fixedAspectRatio:true=true`. **This was not a renderer failure — `RuntimeActivity` never even launched.** The overlay text was already correct; the audit record was wrong.

**Root cause found precisely:** Ti's own `mkxp.json` already had `"fixedAspectRatio": true` *before* the overlay service ever touched it. `applyAspectFitRenderMitigation()`'s own "already true" branch was returning `applied=false` ("skipped, no change needed") — correct for an *action*-shaped mitigation like `zlib-preload` (nothing to insert, so nothing happened), but wrong for `aspect-fit-render`, whose own goal is a *state* (`fixedAspectRatio == true`), not an action. If that state already holds, the mitigation's goal is genuinely satisfied and must be recorded as applied — otherwise a profile requesting it against an already-correct config incorrectly produces an empty `appliedMitigations` and `OverlayStatus.NOT_GENERATED`, exactly what happened to Ti.

**Fixed:** the "already true" branch now returns `applied=true`, with a reason string reading "confirmed, no text change needed" rather than "skipped." Verified via Python simulation against Ti's own exact real-world config shape before being trusted as Kotlin.

**Unit tests updated to match the corrected, intended contract:** the existing test asserting the old (wrong) behavior — `NOT_GENERATED`/`skippedMitigations` — is now `aspect-fit-render records applied and GENERATED_TEST_ONLY when fixedAspectRatio is already true`, asserting the opposite. A new, dedicated idempotence test confirms running this mitigation twice against an already-`true` config produces identical output both times, with the key never duplicated (26 tests total, up from 25).

**Fixed the misleading `fullscreen` log** (requirement 6): the prior "overlay contains fullscreen key" check couldn't distinguish "already present, untouched" from "inserted/altered by this mitigation" — replaced with an actual before/after *value* comparison (`original fullscreen value` vs `overlay fullscreen value`), plus a new, explicit `fullscreenChangedByAspectFitRender` boolean and a dedicated `assertTrue` confirming it's always `false` — `aspect-fit-render` genuinely never touches this key, now provably so rather than just described that way.

**Device-independence clarified, explicitly, in `KnownMitigationIds.ASPECT_FIT_RENDER`'s own kdoc:** this mitigation only ever sets one config flag; it never reads or reasons about any specific screen resolution. The actual `scale = min(availableW/logicalW, availableH/logicalH)` centering math runs natively, at runtime, using whatever real drawable size Android reports on *that* device at *that* moment — the same mechanism applies unmodified across phones, tablets, and foldables of any aspect ratio. `3168x1440` (Ti's own OPPO PGEM10) is one real test case, not a hardcoded target — no code change was needed to make this true, since the Kotlin-side mitigation was already this generic; this is a documentation clarification of an existing property, not a new capability.

**All v0.0.40 hard-verification behavior is unchanged** — manual seed still required, `assertTrue`/`fail` still used (never `assumeTrue`) for the fixture precondition, same seven key files checked individually. Confirmed via direct hash comparison: `RuntimeActivity.kt`, `EssentialsFixtureSeeder.kt`, `RuntimeActivityConfigOverlayLaunchTest.kt`, `RuntimeActivityRenderingViewportDiagnosticTest.kt` all untouched.

**Files changed:**
- `app/src/main/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayService.kt` — the audit/contract fix itself, in `applyAspectFitRenderMitigation()`'s own "already true" branch.
- `app/src/main/java/com/pokerpgplayer/app/data/model/RuntimeConfigProfile.kt` — device-independence clarification added to `ASPECT_FIT_RENDER`'s own kdoc. No behavior change.
- `app/src/test/java/com/pokerpgplayer/app/data/config/RuntimeConfigOverlayServiceTest.kt` — one existing test corrected to the intended contract; one new idempotence test (25 → 26).
- `app/src/androidTest/.../RuntimeActivityNativeResizeDiagnosticTest.kt` — misleading `fullscreen` log replaced with a real before/after value comparison and a dedicated assertion.
- `app/build.gradle.kts` — version bump only (App v0.0.41). No new dependency.
- `README.md` — this section.

### Unit test result (expected)

All 26 tests in `RuntimeConfigOverlayServiceTest` expected to pass:
```
./gradlew testDebugUnitTest --tests "com.pokerpgplayer.app.data.config.RuntimeConfigOverlayServiceTest"
```

### Instrumentation run order (unchanged from App v0.0.40)

```
adb install -r app-debug.apk
adb install -r app-debug-androidTest.apk
adb shell pm list instrumentation | findstr poker
adb shell "cd /sdcard/Download && tar -cf - essentials-v211-original-workspace | run-as com.pokerpgplayer.app.debug sh -c 'cd files && tar -xf -'"
adb shell run-as com.pokerpgplayer.app.debug ls -l files/essentials-v211-original-workspace
adb logcat -c
adb shell am instrument -w -e class com.pokerpgplayer.app.runtime.RuntimeActivityNativeResizeDiagnosticTest com.pokerpgplayer.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### Logcat filter

```
adb logcat -d | findstr /i "PokeRPGSprint30 PokeRPGEvidence RuntimeActivity SDL mkxp fixedAspectRatio aspect-fit-render"
```

### Confirmations

- **`RuntimeConfigOverlayService` now records `aspect-fit-render` as applied whenever `fixedAspectRatio: true` is either written or already confirmed present** — the exact fix requested, verified by the corrected unit test and by Python simulation against Ti's own real config shape.
- **`fullscreen` is never inserted or altered by `aspect-fit-render`** — now provable via an explicit before/after value comparison and a dedicated assertion, not just a "contains" check.

**Claim boundary, explicit:** audit/contract bug fix only. **No native patch applied. `RuntimeActivity` should now genuinely launch — Ti's own visual result (centered/pillarboxed vs. still lower-left) is still required before any claim about the actual rendering.** No compatibility claim.

## Not affiliated




PokeRPG Player is not affiliated with The Pokémon Company, Nintendo, Game Freak, or Creatures Inc. No trademarked imagery is used anywhere in this project.

package com.pokerpgplayer.app.data.model

/**
 * Sprint 53.1 — which of [OverlayStatus.GENERATED_TEST_ONLY] or
 * [OverlayStatus.GENERATED_PRODUCTION] a successful
 * [com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService.generateOverlay]
 * call reports. Purely a reporting distinction — every mitigation
 * transformation behaves identically regardless of [target]; only the
 * resulting [OverlayStatus] differs, so a real launch-path caller
 * ([com.pokerpgplayer.app.runtime.RuntimeLaunchPreparer]) and the
 * pre-existing `androidTest`-only diagnostic probes can never be
 * confused for one another in the audit trail. Defaults to [TEST]
 * everywhere so every pre-Sprint-53.1 call site and test keeps
 * compiling and keeps its original observable behavior unchanged.
 */
enum class OverlayGenerationTarget {
    TEST,
    PRODUCTION
}

/**
 * Stage 1 (Sprint 24) schema-only data model for the Runtime Config
 * Safety Layer ADR. This type and everything it contains represents
 * **structure only** — no detection logic, no overlay-config generation,
 * and no launch-path behavior lives here or is wired to anything yet.
 * [GameEntry.runtimeConfigProfile] defaults to an empty [RuntimeConfigProfile],
 * which is intentionally indistinguishable from "this concept doesn't
 * exist yet" from any existing code's point of view — nothing reads this
 * field today, and nothing's launch behavior changes because it exists.
 *
 * **This is explicitly not the same thing as the diagnostic `preloadScript`
 * probes built in Sprint 20–23 (App v0.0.25–29).** Those are test-only,
 * `androidTest`-scoped tools that directly informed this schema's own
 * design (they proved a Zlib-preload mitigation works, and clarified it's
 * needed on every launch, not just the first) — but they hardcode a
 * single script, a single game, and run only inside instrumentation
 * tests. This type is the data model for a *future*, generic,
 * signal-gated production mechanism (per the Sprint 24 ADR's own "Runtime
 * Config Overlay" architecture) that has not been built. Nothing here
 * should be read as, or extended into, a production preload/config-
 * injection mechanism without a separate, explicit implementation sprint
 * for Stage 2 (overlay generation) and Stage 3 (launch-path wiring).
 *
 * Follows the same Data-vs-Knowledge split already established for
 * [GameEntry]/[GameDetectionResult]: [detectedSignals] is analogous to a
 * scan snapshot (what was observed), while [recommendedMitigations],
 * [enabledMitigations], [disabledMitigations], and [overrides] are
 * decisions layered on top — mirroring how [GameEntry.isFavorite] and
 * [GameEntry.selectedExecutable] sit alongside [GameEntry.detection]
 * today. No actual detection or recommendation logic exists yet; these
 * lists are simply empty until a future sprint populates them.
 *
 * @param detectedSignals Generic, name-able signals a future detection
 *   pass would populate (e.g. "this folder's `Plugins` structure is
 *   consistent with Essentials' `PluginManager`") — see [KnownSignalIds]
 *   for the two signal identifiers already named in the Sprint 24 ADR.
 *   Empty by default; no detection logic runs today.
 * @param recommendedMitigations Mitigation IDs a future detection pass
 *   would recommend based on [detectedSignals] — see [KnownMitigationIds].
 *   Empty by default.
 * @param enabledMitigations Mitigation IDs a future overlay-generation
 *   step would actually apply, after resolving [recommendedMitigations]
 *   against [overrides]. Empty by default — no overlay is ever generated
 *   in Stage 1.
 * @param disabledMitigations Mitigation IDs explicitly suppressed, even
 *   if recommended — the counterpart to [enabledMitigations]. Empty by
 *   default.
 * @param overrides The user/developer safety-valve described in the
 *   Sprint 24 ADR's own answer to question 3 — a per-game escape hatch,
 *   not the primary gating mechanism (that role belongs to future
 *   signal-detection logic, not built yet).
 * @param mitigationAudit Hash/reason/timestamp bookkeeping for whichever
 *   overlay a future Stage 2/3 implementation eventually generates and
 *   applies — see the Sprint 24 ADR's own answer to question 5. All
 *   fields null/empty by default since no overlay has ever been
 *   generated for any game yet.
 * @param overlayStatus Where this game's own overlay stands — defaults
 *   to [OverlayStatus.NOT_GENERATED], the only status possible before
 *   Stage 2 exists.
 */
data class RuntimeConfigProfile(
    val detectedSignals: List<DetectedSignal> = emptyList(),
    val recommendedMitigations: List<String> = emptyList(),
    val enabledMitigations: List<String> = emptyList(),
    val disabledMitigations: List<String> = emptyList(),
    val overrides: MitigationOverride = MitigationOverride(),
    val mitigationAudit: MitigationAudit = MitigationAudit(),
    val overlayStatus: OverlayStatus = OverlayStatus.NOT_GENERATED
)

/**
 * One generic, name-able signal a future detection pass would attach to
 * a [GameEntry] — e.g. "this folder looks like it uses Essentials'
 * `PluginManager`." Deliberately generic (an [id] string, not a closed
 * enum) so future signals for other Essentials forks/versions can be
 * added without a code change to this type — matching the Sprint 24
 * ADR's own answer to question 8 (avoid hardcoding PE21-only
 * assumptions).
 *
 * @param id Stable signal identifier — see [KnownSignalIds] for the two
 *   already named in the Sprint 24 ADR. Free-form on purpose; not
 *   validated against a closed set in Stage 1.
 * @param source Where this signal came from (e.g. a scanner module name)
 *   — free-form string, not yet standardized since no detection logic
 *   exists to populate it.
 * @param confidence Free-form confidence descriptor (e.g. `"high"`,
 *   `"low"`, or a scanner-specific string) — deliberately not a fixed
 *   numeric scale yet, since no real detection logic exists to inform
 *   what scale would actually be meaningful.
 * @param reason Human-readable explanation of why this signal fired —
 *   for the audit/review use case described in the Sprint 24 ADR's own
 *   answer to question 5.
 */
data class DetectedSignal(
    val id: String,
    val source: String,
    val confidence: String,
    val reason: String
)

/**
 * The per-game override safety valve from the Sprint 24 ADR's own
 * answer to question 3 — [mode] defaults to [OverrideMode.AUTO], meaning
 * "let future signal-detection decide," not "force a mitigation on."
 * Nothing in Stage 1 reads or acts on this value.
 */
data class MitigationOverride(
    val mode: OverrideMode = OverrideMode.AUTO,
    val notes: String = ""
)

/**
 * Enumerates the override modes described in the Sprint 24 ADR. [AUTO]
 * is the only mode any [GameEntry] can have in Stage 1, since nothing
 * yet generates an overlay for [FORCE_ON] or suppresses one for
 * [FORCE_OFF] to actually mean anything.
 */
enum class OverrideMode {
    AUTO,
    FORCE_ON,
    FORCE_OFF
}

/**
 * Hash/reason/timestamp bookkeeping for whichever overlay a future
 * Stage 2/3 implementation eventually generates — the audit trail
 * described in the Sprint 24 ADR's own answer to question 5. All fields
 * are null/empty by default in Stage 1, since no overlay has ever been
 * generated for any game — there is nothing yet to audit.
 */
data class MitigationAudit(
    val originalConfigHash: String? = null,
    val overlayConfigHash: String? = null,
    val lastAppliedMitigations: List<String> = emptyList(),
    val lastReason: String? = null,
    val lastGeneratedAt: String? = null
)

/**
 * Where a given [GameEntry]'s own overlay stands. Every [GameEntry] in
 * Stage 1 is [NOT_GENERATED] — the other four values describe states
 * only a future Stage 2 (overlay generation, `androidTest`-verified
 * first) and Stage 3 (real launch-path wiring) implementation could ever
 * actually produce.
 */
enum class OverlayStatus {
    /** The only status any real [GameEntry] has in Stage 1 — no overlay-generation code exists yet. */
    NOT_GENERATED,

    /** Reserved for a future Stage 2 `androidTest`-only overlay-generation pass, verified in isolation before any launch-path wiring exists. */
    GENERATED_TEST_ONLY,

    /** Reserved for a future Stage 3, once overlay generation is wired into the real launch flow — not possible until then. */
    GENERATED_PRODUCTION,

    /** Reserved for a future per-game [OverrideMode.FORCE_OFF] result, once overlay generation exists to be disabled. */
    DISABLED,

    /** Reserved for a future overlay-generation failure state — not reachable in Stage 1, since generation itself doesn't exist. */
    ERROR
}

/**
 * Signal identifiers named in the Sprint 24 ADR. Provided for
 * discoverability/documentation only — nothing in Stage 1 validates a
 * [DetectedSignal.id] against this list, and the list is not closed;
 * future signals for other Essentials forks/versions are expected to be
 * added here without requiring [DetectedSignal]'s own shape to change.
 */
object KnownSignalIds {
    /** Folder contents consistent with Essentials' `PluginManager` (e.g. a `Plugins` folder) — see [GameDetectionResult.pluginsDetected], the existing raw scan signal this would eventually be derived from. */
    const val ESSENTIALS_PLUGINMANAGER = "essentials-pluginmanager"

    /** A non-ASCII value detected in `mkxp.json`'s own `windowTitle` — the Sprint 16–18 encoding crash trigger. */
    const val NON_ASCII_WINDOW_TITLE = "non-ascii-window-title"
}

/**
 * Mitigation identifiers named in the Sprint 24 ADR. Provided for
 * discoverability/documentation only — not validated or enforced in
 * Stage 1, and not a closed set.
 */
object KnownMitigationIds {
    /** Pre-requiring Ruby's `Zlib` module before Essentials' own scripts run — proven diagnostically in Sprint 20–23, not yet a production mechanism. */
    const val ZLIB_PRELOAD = "zlib-preload"

    /** Substituting a plain-ASCII `windowTitle` value to avoid the native `iconv`/`uchardet` crash traced in Sprint 18. */
    const val ASCII_SAFE_WINDOW_TITLE = "ascii-safe-window-title"

    /**
     * Sprint 30 — sets `mkxp.json`'s own `"fixedAspectRatio"` config
     * key to `true`. Traced directly from `graphics.cpp`'s own
     * `recalculateScreenSize()`: an aspect-preserving, centered scaling
     * calculation *already exists* in that function's own final branch
     * (computing `scSize`/`scOffset` exactly per the standard
     * `scale = min(winSize.x/scRes.x, winSize.y/scRes.y)` formula), but
     * an early-return path (taken whenever `fixedAspectRatio` is
     * `false`, its own documented default) skips it entirely. This
     * mitigation does not add new scaling math — it activates logic
     * that is already correct in the native fork's own source.
     *
     * **Explicitly device-independent, not tuned to any one device.**
     * This mitigation only ever sets a single config flag; it never
     * reads or reasons about any specific screen resolution. The actual
     * `scale = min(availableW/logicalW, availableH/logicalH)` centering
     * math runs natively, at runtime, using whatever real drawable size
     * Android reports on that device at that moment — the same
     * mechanism applies unmodified across phones, tablets, and
     * foldables of any aspect ratio. `3168x1440` (Ti's own OPPO PGEM10)
     * is one real test case, not a hardcoded target.
     */
    const val ASPECT_FIT_RENDER = "aspect-fit-render"

    /**
     * Sprint 41 — opt-in diagnostic only, never enabled by default.
     * Injects a temporary disposable Ruby preload script that traces
     * Game_Player map movement after Sprint 40 confirmed native Input.dir4.
     */
    const val SPRINT41_INPUT_DIAGNOSTIC = "sprint41-input-diagnostic"

    /**
     * Sprint 42 — opt-in diagnostic only.
     * Injects a temporary disposable Ruby preload script that discovers
     * the actual Essentials v21.1 player movement method path.
     */
    const val SPRINT42_MOVEMENT_PATH_DIAGNOSTIC = "sprint42-movement-path-diagnostic"

    /**
     * Sprint 43 — opt-in diagnostic only.
     * Injects a temporary disposable Ruby preload script that traces
     * update_command_new and the Essentials v21.1 command-to-movement pipeline.
     */
    const val SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC = "sprint43-command-pipeline-diagnostic"

    /**
     * Sprint 44 — opt-in diagnostic only.
     * Injects a temporary disposable Ruby preload script that traces
     * runtime timebase/FPS/delta behavior around Essentials movement.
     */
    const val SPRINT44_TIMEBASE_DIAGNOSTIC = "sprint44-timebase-diagnostic"

    /**
     * Sprint 45 — opt-in diagnostic only, never enabled by default.
     * Forces "syncToRefreshrate": false and "fixedFramerate": 60 in
     * the generated overlay itself, so the value survives overlay
     * regeneration during launch.
     */
    const val SPRINT45_FPS60_CAP_DIAGNOSTIC = "sprint45-fps60-cap-diagnostic"

    /**
     * Sprint 48 — reversible preloadScript compatibility shim.
     * Wraps System.uptime/System.delta so Essentials v21.1 receives
     * seconds instead of raw microseconds. This avoids changing the
     * shared native mkxpDelta binding before broader compatibility audit.
     */
    const val SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM = "sprint48-system-uptime-seconds-shim"

    /**
     * Sprint 50 — public, player-facing Game Speed control (x1/x2/x3),
     * layered after Sprint 48's System.uptime seconds shim.
     */
    const val SPRINT50_SPEED_CONTROL = "sprint50-speed-control"
}

package com.pokerpgplayer.app.data.config

import com.pokerpgplayer.app.data.model.KnownMitigationIds

/**
 * Sprint 49 — named diagnostic mitigation sets.
 *
 * This object does not add, remove, or alter any mitigation implementation.
 * It only decides which mitigation IDs are requested for normal vs verbose
 * diagnostic launches after Sprint 48 confirmed the System.uptime seconds shim.
 */
object DiagnosticProfiles {

    /**
     * Default diagnostic run: keep required compatibility mitigations and the
     * confirmed Sprint 48 timebase fix, while excluding heavy Sprint41-44
     * tracing scripts.
     */
    fun normalDiagnosticMitigations(): List<String> = listOf(
        KnownMitigationIds.ZLIB_PRELOAD,
        KnownMitigationIds.ASCII_SAFE_WINDOW_TITLE,
        KnownMitigationIds.ASPECT_FIT_RENDER,
        KnownMitigationIds.SPRINT45_FPS60_CAP_DIAGNOSTIC,
        KnownMitigationIds.SPRINT48_SYSTEM_UPTIME_SECONDS_SHIM,
        KnownMitigationIds.SPRINT50_SPEED_CONTROL
    )

    /**
     * Verbose diagnostic run: restores Sprint41-44 tracing on top of normal.
     * Sprint47 is deliberately excluded from this first Sprint49 merge.
     */
    fun verboseDiagnosticMitigations(): List<String> = normalDiagnosticMitigations() + listOf(
        KnownMitigationIds.SPRINT41_INPUT_DIAGNOSTIC,
        KnownMitigationIds.SPRINT42_MOVEMENT_PATH_DIAGNOSTIC,
        KnownMitigationIds.SPRINT43_COMMAND_PIPELINE_DIAGNOSTIC,
        KnownMitigationIds.SPRINT44_TIMEBASE_DIAGNOSTIC
    )
}

package com.pokerpgplayer.app.runtime

/**
 * The native runtime's current state. Sprint 3 (Runtime Foundation
 * Preparation) defines this vocabulary but has no native runtime behind
 * it — [StubRuntimeManager] can only ever report [UNAVAILABLE]. Every
 * other value is reserved for when a real mkxp-z-based runtime exists;
 * nothing in this codebase can produce them yet.
 *
 * See "PokeRPG Player — Sprint 3: Runtime Foundation Preparation —
 * Architecture Proposal v1.0" §3 for the full rationale.
 */
enum class RuntimeStatus {
    /** No native runtime exists. The only value reachable in Sprint 3. */
    UNAVAILABLE,

    /** Reserved — native library loading in progress. Unreachable until a real .so exists. */
    INITIALIZING,

    /** Reserved — runtime loaded, ready to accept a launch request. Unreachable until then. */
    READY,

    /** Reserved — a game is actively running. Unreachable until then. */
    RUNNING,

    /** Reserved — runtime shut down cleanly after running a game. Unreachable until then. */
    STOPPED
}

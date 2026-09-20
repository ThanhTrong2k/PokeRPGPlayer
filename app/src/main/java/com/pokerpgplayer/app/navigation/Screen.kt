package com.pokerpgplayer.app.navigation

/** Backing constant for [Screen.GameDetail.ARG_GAME_ID] — top-level so it
 * can be referenced inside that route's own constructor call below (an
 * object's own member can't forward-reference itself there). Named
 * distinctly from the public alias to avoid self-shadowing. */
private const val GAME_DETAIL_ARG_GAME_ID_KEY = "gameId"

/**
 * Single source of truth for nav-graph routes. Sealed class instead of raw
 * strings scattered through the codebase — cheap to extend without
 * hunting down string literals.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object About : Screen("about")
    data object Log : Screen("log")

    /**
     * Sprint 2: Game Detail — rename, favorite, remove, choose executable.
     * Takes the game's UUID as a nav argument, never the folder URI/path
     * (same identity rule as [com.pokerpgplayer.app.data.model.GameEntry.id]).
     */
    data object GameDetail : Screen("gameDetail/{$GAME_DETAIL_ARG_GAME_ID_KEY}") {
        const val ARG_GAME_ID = GAME_DETAIL_ARG_GAME_ID_KEY
        fun createRoute(gameId: String) = "gameDetail/$gameId"
    }
}

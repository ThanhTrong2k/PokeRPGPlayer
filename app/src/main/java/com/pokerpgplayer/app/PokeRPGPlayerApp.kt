package com.pokerpgplayer.app

import android.app.Application

/**
 * Sprint 2: now holds [AppContainer], the app's manual composition root
 * (see that class's doc comment for why no DI framework yet).
 */
class PokeRPGPlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

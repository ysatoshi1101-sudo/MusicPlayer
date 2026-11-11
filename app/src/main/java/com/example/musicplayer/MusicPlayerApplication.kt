package com.example.musicplayer

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MusicPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = runBlocking { ThemePreferences(this@MusicPlayerApplication).themeMode.first() }
        applyNightMode(mode)
    }
}
package com.watermarkhu.mijn3park

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            ThemePrefs.nightMode(ThemePrefs(this).theme)
        )
    }
}

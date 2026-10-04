package com.watermarkhu.mijn3park

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/**
 * Lightweight, unencrypted storage for UI preferences (theme). Kept separate
 * from the Keystore-backed [Prefs] so [App] can read it before any encrypted
 * storage is unlocked. Holds no sensitive data.
 */
class ThemePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var theme: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** Whether to use the Material 3 Expressive theme. */
    var expressive: Boolean
        get() = prefs.getBoolean(KEY_EXPRESSIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_EXPRESSIVE, value) }

    companion object {
        const val PREFS_NAME = "mijn3park_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_EXPRESSIVE = "expressive"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** Map a stored theme name to an [AppCompatDelegate] night mode. */
        fun nightMode(theme: String): Int = when (theme) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        /** Activity theme matching the saved appearance preferences. */
        fun themeRes(context: Context): Int =
            if (ThemePrefs(context).expressive) R.style.Theme_App_Expressive else R.style.Theme_App
    }
}

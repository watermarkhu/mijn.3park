package com.watermarkhu.mijn3park

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            ThemePrefs.nightMode(ThemePrefs(this).theme)
        )
        NotificationChannels.ensure(this)
        installSessionStore()
    }

    /**
     * Hand [TwoParkApi] the encrypted store, so it can try the saved session
     * ("refresh token") first and fall back to the saved credentials only when
     * that session is gone.
     *
     * [Prefs] is created lazily on purpose: unlocking the Keystore costs tens of
     * milliseconds and the first user of it is a background API call, not app
     * startup (which only reads [ThemePrefs]).
     */
    private fun installSessionStore() {
        val store = lazy { Prefs(applicationContext) }
        val api = TwoParkApi.instance
        api.sessionStore = object : SessionStore {
            override fun load(): String? = store.value.sessionCookie.takeIf { it.isNotBlank() }

            override fun save(cookie: String?) {
                store.value.sessionCookie = cookie.orEmpty()
            }
        }
        api.savedCredentials = {
            val prefs = store.value
            if (prefs.hasCredentials) prefs.email to prefs.password else null
        }
    }
}

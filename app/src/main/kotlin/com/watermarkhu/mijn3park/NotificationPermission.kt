package com.watermarkhu.mijn3park

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Helpers for the API 33+ `POST_NOTIFICATIONS` runtime permission.
 *
 * The "already asked" flag is stored in plain (unencrypted) preferences — the
 * same file as [ThemePrefs] — because it must be readable even when the
 * encrypted [Prefs] store is unavailable, and it holds nothing sensitive. It
 * prevents re-prompting a user who already answered (including "don't ask
 * again"); the Settings row and the startup toast cover that case instead.
 */
object NotificationPermission {

    /**
     * Permission name as a plain constant: referencing
     * `Manifest.permission.POST_NOTIFICATIONS` directly trips lint's
     * `InlinedApi` warning on our minSdk 24 (it is an inlined String anyway).
     */
    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    /** API 33+ runtime permission; always true below that. */
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED

    /** True only while the user has never been asked and notifications are off. */
    fun shouldPrompt(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(context) &&
            !askedBefore(context)

    /** Remember that the system prompt has been shown at least once. */
    fun markAsked(context: Context) {
        prefs(context).edit { putBoolean(KEY_ASKED, true) }
    }

    private fun askedBefore(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED, false)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(ThemePrefs.PREFS_NAME, Context.MODE_PRIVATE)

    private const val KEY_ASKED = "notification_permission_asked"
}

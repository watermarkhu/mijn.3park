package com.watermarkhu.mijn3park

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Official 2Park web app, opened when the API cannot be checked. */
private const val TWO_PARK_URL = "https://mijn.2park.nl"

/**
 * Build and show the "cannot check 2Park" reminder.
 *
 * The user is warned that the app cannot guarantee the status of their parking
 * and pointed at mijn.2park.nl. The Ok button only dismisses the dialog; the
 * caller decides what Ok means (log out on the main screen, nothing on login).
 * The other button opens mijn.2park.nl in the default browser.
 */
fun showHealthAlertDialog(context: Context, onOk: () -> Unit): AlertDialog =
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.health_alert_title)
        .setMessage(R.string.health_alert_message)
        // Back/outside dismisses without logging out.
        .setCancelable(true)
        .setPositiveButton(R.string.health_alert_ok) { _, _ -> onOk() }
        .setNegativeButton(R.string.health_alert_open_site) { _, _ -> openTwoPark(context) }
        .show()

/** Open mijn.2park.nl in whatever browser the user has, if any. */
fun openTwoPark(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, TWO_PARK_URL.toUri())
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.health_alert_open_failed, Toast.LENGTH_LONG).show()
    }
}

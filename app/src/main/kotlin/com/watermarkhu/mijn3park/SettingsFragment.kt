package com.watermarkhu.mijn3park

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs

    private lateinit var accountEmail: TextView
    private lateinit var productDropdown: MaterialAutoCompleteTextView
    private lateinit var defaultProductStar: MaterialButton
    private lateinit var themeSystem: MaterialRadioButton
    private lateinit var themeLight: MaterialRadioButton
    private lateinit var themeDark: MaterialRadioButton
    private lateinit var notificationStatus: TextView
    private lateinit var notificationOpenButton: MaterialButton
    private lateinit var reminderDropdown: MaterialAutoCompleteTextView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // A second denial ("don't ask again") returns here immediately:
            // send the user to the system settings so the button stays useful.
            val canAskAgain = requireActivity()
                .shouldShowRequestPermissionRationale(NotificationPermission.PERMISSION)
            if (!granted && !canAskAgain) {
                openNotificationSettings()
            }
            refreshNotificationStatus()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        accountEmail = view.findViewById(R.id.accountEmail)
        productDropdown = view.findViewById(R.id.productDropdown)
        defaultProductStar = view.findViewById(R.id.defaultProductStar)
        themeSystem = view.findViewById(R.id.themeSystem)
        themeLight = view.findViewById(R.id.themeLight)
        themeDark = view.findViewById(R.id.themeDark)
        notificationStatus = view.findViewById(R.id.notificationStatus)
        notificationOpenButton = view.findViewById(R.id.notificationOpenButton)
        reminderDropdown = view.findViewById(R.id.reminderDropdown)

        accountEmail.text = prefs.email

        productDropdown.setOnItemClickListener { _, _, position, _ ->
            vm.state.value.products.getOrNull(position)?.let { vm.selectProduct(it) }
        }
        defaultProductStar.setOnClickListener {
            vm.setDefaultProduct(defaultProductStar.isChecked)
            if (defaultProductStar.isChecked) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.default_product_set, prefs.productName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        setUpTheme()
        refreshNotificationStatus()
        notificationOpenButton.setOnClickListener { onNotificationButton() }
        setUpReminders()

        view.findViewById<MaterialButton>(R.id.logoutButton).setOnClickListener {
            (activity as? MainActivity)?.performLogout()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    productDropdown.setAdapter(
                        ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_list_item_1,
                            state.products.map { it.displayName },
                        ),
                    )
                    productDropdown.setText(state.selectedProduct?.displayName ?: prefs.productName, false)
                    defaultProductStar.isVisible = state.products.size > 1
                    defaultProductStar.isChecked =
                        prefs.productId.isNotBlank() && prefs.productId == prefs.defaultProductId
                }
            }
        }
    }

    private fun setUpTheme() {
        val themePrefs = ThemePrefs(requireContext())
        when (themePrefs.theme) {
            ThemePrefs.THEME_LIGHT -> themeLight.isChecked = true
            ThemePrefs.THEME_DARK -> themeDark.isChecked = true
            else -> themeSystem.isChecked = true
        }
        val listener = { theme: String ->
            if (themePrefs.theme != theme) {
                themePrefs.theme = theme
                AppCompatDelegate.setDefaultNightMode(ThemePrefs.nightMode(theme))
            }
        }
        themeSystem.setOnClickListener { listener(ThemePrefs.THEME_SYSTEM) }
        themeLight.setOnClickListener { listener(ThemePrefs.THEME_LIGHT) }
        themeDark.setOnClickListener { listener(ThemePrefs.THEME_DARK) }
    }

    override fun onResume() {
        super.onResume()
        // The permission can change while the app is backgrounded (system
        // settings), so re-read it whenever the fragment comes back.
        if (::notificationStatus.isInitialized) refreshNotificationStatus()
        if (::reminderDropdown.isInitialized) renderReminder()
    }

    private fun refreshNotificationStatus() {
        val granted = NotificationPermission.granted(requireContext())
        notificationStatus.setText(
            if (granted) {
                R.string.notification_permission_status_granted
            } else {
                R.string.notification_permission_status_not_granted
            },
        )
        notificationStatus.setTextColor(
            MaterialColors.getColor(
                notificationStatus,
                if (granted) {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                } else {
                    androidx.appcompat.R.attr.colorError
                },
            ),
        )
        notificationOpenButton.isVisible = !granted
    }

    /** Re-request on API 33+, or deep-link to the app's notification settings. */
    private fun onNotificationButton() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Always attempt the in-app request: a dismissed dialog leaves the
            // permission state unchanged and shouldShowRequestPermissionRationale
            // stays false, so gating on it would wrongly open Settings instead.
            // A second denial makes the launcher return immediately, and the
            // callback falls back to the system settings screen.
            notificationPermissionLauncher.launch(NotificationPermission.PERMISSION)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val context = requireContext()
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        }
        startActivity(intent)
    }

    private fun setUpReminders() {
        val options = reminderOptions()
        reminderDropdown.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                options.map { getString(it.first) },
            ),
        )
        renderReminder()
        reminderDropdown.setOnItemClickListener { _, _, position, _ ->
            val minutes = options.getOrNull(position)?.second ?: return@setOnItemClickListener
            prefs.reminderIntervalMinutes = minutes
            SessionScheduler.setReminderInterval(requireContext(), minutes)
            renderReminder()
        }
    }

    private fun renderReminder() {
        val minutes = prefs.reminderIntervalMinutes
        val option = reminderOptions().firstOrNull { it.second == minutes } ?: reminderOptions().first()
        reminderDropdown.setText(getString(option.first), false)
    }

    /** Reminder choices and their interval in minutes (0 = off). */
    private fun reminderOptions(): List<Pair<Int, Int>> = listOf(
        R.string.reminder_off to 0,
        R.string.reminder_30m to 30,
        R.string.reminder_1h to 60,
        R.string.reminder_2h to 120,
        R.string.reminder_4h to 240,
        R.string.reminder_8h to 480,
        R.string.reminder_16h to 960,
        R.string.reminder_24h to 1440,
    )
}

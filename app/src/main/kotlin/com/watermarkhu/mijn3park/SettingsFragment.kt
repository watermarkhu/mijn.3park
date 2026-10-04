package com.watermarkhu.mijn3park

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch

/**
 * Settings screen built from [R.xml.settings]: account, active/default product,
 * reminder cadence, notification permission, appearance (theme + Material 3
 * Expressive), language, and about (version + GitHub).
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // A second denial ("don't ask again") returns here immediately:
            // send the user to the system settings so the entry stays useful.
            val canAskAgain = requireActivity()
                .shouldShowRequestPermissionRationale(NotificationPermission.PERMISSION)
            if (!granted && !canAskAgain) {
                openNotificationSettings()
            }
            renderNotificationStatus()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpAccount()
        setUpParking()
        setUpReminders()
        setUpNotifications()
        setUpAppearance()
        setUpLanguage()
        setUpAbout()
    }

    override fun onResume() {
        super.onResume()
        // The permission can change while the app is backgrounded (system
        // settings), so re-read it whenever the fragment comes back.
        renderNotificationStatus()
    }

    private fun setUpAccount() {
        findPreference<Preference>("email")?.summary = prefs.email
    }

    private fun setUpParking() {
        val productPref = findPreference<ListPreference>("product")
        val defaultPref = findPreference<SwitchPreferenceCompat>("default_product")

        productPref?.setOnPreferenceChangeListener { _, newValue ->
            val id = newValue as? String ?: return@setOnPreferenceChangeListener false
            vm.state.value.products.firstOrNull { it.id == id }?.let { vm.selectProduct(it) }
            true
        }
        defaultPref?.setOnPreferenceChangeListener { _, newValue ->
            vm.setDefaultProduct(newValue as? Boolean ?: false)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    val products = state.products
                    productPref?.entries = products.map { it.displayName as CharSequence }.toTypedArray()
                    productPref?.entryValues = products.map { it.id as CharSequence }.toTypedArray()
                    productPref?.value = prefs.productId
                    defaultPref?.isVisible = products.size > 1
                    defaultPref?.isChecked =
                        prefs.productId.isNotBlank() && prefs.productId == prefs.defaultProductId
                }
            }
        }
    }

    private fun setUpReminders() {
        val reminderPref = findPreference<ListPreference>("reminder") ?: return
        val options = reminderOptions()
        reminderPref.entries = options.map { getString(it.first) as CharSequence }.toTypedArray()
        reminderPref.entryValues = options.map { it.second.toString() as CharSequence }.toTypedArray()
        reminderPref.value = prefs.reminderIntervalMinutes.toString()
        reminderPref.setOnPreferenceChangeListener { _, newValue ->
            val minutes = (newValue as? String)?.toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            prefs.reminderIntervalMinutes = minutes
            SessionScheduler.setReminderInterval(requireContext(), minutes)
            true
        }
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

    private fun setUpNotifications() {
        findPreference<Preference>("notifications")?.setOnPreferenceClickListener {
            onNotificationButton()
            true
        }
        renderNotificationStatus()
    }

    private fun renderNotificationStatus() {
        findPreference<Preference>("notifications")?.summary =
            if (NotificationPermission.granted(requireContext())) {
                getString(R.string.notification_permission_status_granted)
            } else {
                getString(R.string.notification_permission_status_not_granted)
            }
    }

    /** Re-request on API 33+, or deep-link to the app's notification settings. */
    private fun onNotificationButton() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Always attempt the in-app request: a dismissed dialog leaves the
            // permission state unchanged and shouldShowRequestPermissionRationale
            // stays false, so gating on it would wrongly open Settings instead.
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

    private fun setUpAppearance() {
        val themePrefs = ThemePrefs(requireContext())

        val themePref = findPreference<ListPreference>("theme")
        themePref?.entries = arrayOf(
            getString(R.string.settings_theme_system) as CharSequence,
            getString(R.string.settings_theme_light) as CharSequence,
            getString(R.string.settings_theme_dark) as CharSequence,
        )
        themePref?.entryValues = arrayOf(
            ThemePrefs.THEME_SYSTEM as CharSequence,
            ThemePrefs.THEME_LIGHT as CharSequence,
            ThemePrefs.THEME_DARK as CharSequence,
        )
        themePref?.value = themePrefs.theme
        themePref?.setOnPreferenceChangeListener { _, newValue ->
            val theme = newValue as? String ?: return@setOnPreferenceChangeListener false
            if (themePrefs.theme != theme) {
                themePrefs.theme = theme
                AppCompatDelegate.setDefaultNightMode(ThemePrefs.nightMode(theme))
            }
            true
        }

        val expressivePref = findPreference<SwitchPreferenceCompat>("expressive")
        expressivePref?.isChecked = themePrefs.expressive
        expressivePref?.setOnPreferenceChangeListener { _, newValue ->
            val expressive = newValue as? Boolean ?: false
            if (themePrefs.expressive != expressive) {
                themePrefs.expressive = expressive
                requireActivity().recreate()
            }
            true
        }
    }

    private fun setUpLanguage() {
        val languagePref = findPreference<ListPreference>("language") ?: return
        languagePref.entries = arrayOf(
            getString(R.string.settings_language_system) as CharSequence,
            getString(R.string.settings_language_en) as CharSequence,
            getString(R.string.settings_language_nl) as CharSequence,
        )
        languagePref.entryValues = arrayOf(
            "system" as CharSequence,
            "en" as CharSequence,
            "nl" as CharSequence,
        )
        languagePref.value = currentLanguageTag()
        languagePref.setOnPreferenceChangeListener { _, newValue ->
            val tag = newValue as? String ?: return@setOnPreferenceChangeListener false
            val locales = if (tag == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
            true
        }
    }

    /** "system" when following the system, otherwise the overridden language tag. */
    private fun currentLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "system" else locales.toLanguageTags().substringBefore(',')
    }

    private fun setUpAbout() {
        findPreference<Preference>("disclaimer")?.summary = getString(R.string.disclaimer)
        findPreference<Preference>("version")?.summary = appVersion()
        findPreference<Preference>("github")?.setOnPreferenceClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/watermarkhu/mijn.3park".toUri()))
            true
        }
        findPreference<Preference>("logout")?.setOnPreferenceClickListener {
            (activity as? MainActivity)?.performLogout()
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String {
        val context = requireContext()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return getString(
            R.string.settings_version_value,
            info.versionName.orEmpty(),
            PackageInfoCompat.getLongVersionCode(info),
        )
    }
}

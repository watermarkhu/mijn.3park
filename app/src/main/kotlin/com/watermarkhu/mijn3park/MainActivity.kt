package com.watermarkhu.mijn3park

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.core.view.MenuItemCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var navHost: View
    private lateinit var editorHost: View
    private lateinit var editorBackCallback: OnBackPressedCallback

    private lateinit var statusContainer: View
    private lateinit var statusIcon: View
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusRetry: MaterialButton

    private var healthAlertDialog: AlertDialog? = null

    /** Cold start is already covered by [AppViewModel.start]; later onStarts refresh. */
    private var firstStart = true

    private val vm: AppViewModel by viewModels()

    /** Result is surfaced in Settings; here we only avoid prompting twice. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.hasCredentials) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        bottomNav = findViewById(R.id.bottomNav)
        navHost = findViewById(R.id.navHost)
        editorHost = findViewById(R.id.editorHost)

        // Explicit tint lists: Material's defaults barely dim a disabled item,
        // and the active-indicator pill stays visible regardless of enabled
        // state, so a disabled tab must be reset to its unchecked form.
        applyPlannedAvailability(isPermit = false)

        // The planned-session editor is a full-screen overlay; back closes it.
        editorBackCallback = onBackPressedDispatcher.addCallback(this) { closePlanEditor() }
        editorBackCallback.isEnabled = false

        statusContainer = findViewById(R.id.statusContainer)
        statusIcon = findViewById(R.id.statusIcon)
        statusProgress = findViewById(R.id.statusProgress)
        statusTitle = findViewById(R.id.statusTitle)
        statusMessage = findViewById(R.id.statusMessage)
        statusRetry = findViewById(R.id.statusRetry)
        statusRetry.setOnClickListener { vm.retry() }

        bottomNav.setOnItemSelectedListener { item ->
            switchTo(item.itemId)
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.health.collect { applyHealth(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sessionExpired.collect {
                    Toast.makeText(this@MainActivity, R.string.session_expired, Toast.LENGTH_LONG).show()
                    performLogout()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.healthAlert.collect { showHealthAlert() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.messages.collect { message ->
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        // Permits cannot be planned: grey out the Planned tab for them.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    val permit = state.selectedProduct?.hasFixedPlate == true || state.details?.fixedPlate != null
                    applyPlannedAvailability(permit)
                }
            }
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_park
            setUpNotificationPermission()
        } else {
            toolbar.setTitle(titleFor(bottomNav.selectedItemId))
            applyHealth(vm.health.value)
        }

        vm.start()
    }

    override fun onStart() {
        super.onStart()
        if (firstStart) {
            firstStart = false
            return
        }
        // Re-opened from the background: converge planned sessions with the server.
        if (vm.health.value == HealthState.OK) vm.refreshPlannedSessions()
    }

    /**
     * Grey out and disable the Planned tab for permit products, and describe
     * why to screen readers. Material keeps the active-indicator pill visible on
     * a checked item even when disabled, so a disabled Planned tab is also
     * un-checked and the bar is forced off it; the explicit tints then make it
     * read as unavailable rather than merely unselected.
     */
    private fun applyPlannedAvailability(isPermit: Boolean) {
        val item = bottomNav.menu.findItem(R.id.nav_planned) ?: return
        item.isEnabled = !isPermit
        if (isPermit) {
            // A disabled item must not stay selected (or keep its pill/tint).
            if (bottomNav.selectedItemId == R.id.nav_planned) {
                bottomNav.selectedItemId = R.id.nav_park
            }
            item.isChecked = false
        }
        // The menu item's accessibility description explains, to screen
        // readers, why the tab is unavailable. MenuItemCompat handles the
        // API < 26 path (plain MenuItem has no content description there).
        MenuItemCompat.setContentDescription(
            item,
            if (isPermit) getString(R.string.planned_permit_unavailable) else null,
        )
        // Menu changes don't refresh already-inflated item views: toggling the
        // public tints makes Material re-propagate them to every item so the
        // newly disabled item picks up the faint colour.
        bottomNav.itemIconTintList = null
        bottomNav.itemTextColor = null
        bottomNav.itemIconTintList =
            AppCompatResources.getColorStateList(this, R.color.nav_item_icon_tint)
        bottomNav.itemTextColor =
            AppCompatResources.getColorStateList(this, R.color.nav_item_text_color)
    }

    /**
     * On first set-up, ask for the notification permission once. If the user
     * already answered (granted or not), only remind them with a toast instead
     * of re-prompting; the Settings row shows the detailed state.
     */
    private fun setUpNotificationPermission() {
        when {
            NotificationPermission.shouldPrompt(this) -> {
                NotificationPermission.markAsked(this)
                notificationPermissionLauncher.launch(NotificationPermission.PERMISSION)
            }
            !NotificationPermission.granted(this) -> {
                Toast.makeText(this, R.string.notification_permission_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun titleFor(itemId: Int): Int = when (itemId) {
        R.id.nav_history -> R.string.nav_history
        R.id.nav_transactions -> R.string.nav_transactions
        R.id.nav_planned -> R.string.nav_planned
        R.id.nav_settings -> R.string.nav_settings
        else -> R.string.nav_park
    }

    /** Show [itemId]'s fragment, hiding the others so their state survives. */
    private fun switchTo(itemId: Int) {
        val tag = itemId.toString()
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        var target = fm.findFragmentByTag(tag)
        for (fragment in fm.fragments) {
            if (fragment !== target) tx.hide(fragment)
        }
        if (target == null) {
            target = createFragment(itemId)
            tx.add(R.id.navHost, target, tag)
        } else {
            tx.show(target)
        }
        tx.commit()
        toolbar.setTitle(titleFor(itemId))
        applyHealth(vm.health.value)
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_history -> HistoryFragment()
        R.id.nav_transactions -> TransactionsFragment()
        R.id.nav_planned -> PlannedFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> ParkFragment()
    }

    /**
     * When 2Park is unusable, Park/History/Transactions are replaced by a
     * failure screen. Settings stays reachable (logout, theme, product).
     */
    private fun applyHealth(health: HealthState) {
        val onSettings = bottomNav.selectedItemId == R.id.nav_settings
        val showStatus = (health != HealthState.OK) && !onSettings
        // The editor is a full-screen overlay above the status view: close it so
        // the failure screen is actually visible.
        if (showStatus && supportFragmentManager.findFragmentByTag(TAG_EDITOR) != null) {
            closePlanEditor(restoreHealth = false)
        }
        statusContainer.isVisible = showStatus
        navHost.isVisible = !showStatus
        if (!showStatus) return

        when (health) {
            HealthState.CHECKING -> {
                statusIcon.isVisible = false
                statusProgress.isVisible = true
                statusTitle.setText(R.string.health_checking_title)
                statusMessage.isVisible = false
                statusRetry.isVisible = false
            }
            HealthState.UNAVAILABLE -> {
                statusIcon.isVisible = true
                statusProgress.isVisible = false
                statusTitle.setText(R.string.health_unavailable_title)
                statusMessage.setText(R.string.health_unavailable_message)
                statusMessage.isVisible = true
                statusRetry.isVisible = true
            }
            HealthState.UNRELIABLE -> {
                statusIcon.isVisible = true
                statusProgress.isVisible = false
                statusTitle.setText(R.string.health_unreliable_title)
                statusMessage.setText(R.string.health_unreliable_message)
                statusMessage.isVisible = true
                statusRetry.isVisible = true
            }
        }
    }

    /** Open the full-screen planned-session editor for a new plan. */
    fun openCreatePlan() = showPlanEditor(PlanEditFragment.newCreate(), R.string.planned_add)

    /** Open the editor for a merged session (edit = cancel + recreate). */
    fun openEditPlan(plate: String, startAt: Long, endAt: Long, legIds: List<String>) =
        showPlanEditor(PlanEditFragment.newEdit(plate, startAt, endAt, legIds), R.string.planned_edit)

    private fun showPlanEditor(fragment: PlanEditFragment, titleRes: Int) {
        if (vm.health.value != HealthState.OK) return
        if (supportFragmentManager.findFragmentByTag(TAG_EDITOR) != null) return
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.editorHost, fragment, TAG_EDITOR)
            .commit()
        editorHost.isVisible = true
        bottomNav.isVisible = false
        toolbar.setTitle(titleRes)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { closePlanEditor() }
        editorBackCallback.isEnabled = true
    }

    /** Close the planned-session editor if open. */
    fun closePlanEditor(restoreHealth: Boolean = true) {
        val fragment = supportFragmentManager.findFragmentByTag(TAG_EDITOR) ?: return
        supportFragmentManager.beginTransaction().remove(fragment).commit()
        editorHost.isVisible = false
        bottomNav.isVisible = true
        toolbar.setNavigationOnClickListener(null)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.setTitle(titleFor(bottomNav.selectedItemId))
        editorBackCallback.isEnabled = false
        if (restoreHealth) applyHealth(vm.health.value)
    }

    /**
     * Warn that 2Park cannot be checked: Ok logs the user out, the other
     * button opens mijn.2park.nl. Shown on top of the failure screen, once per
     * failure episode (guarded in [AppViewModel] and by [healthAlertDialog]).
     */
    private fun showHealthAlert() {
        if (healthAlertDialog?.isShowing == true) return
        healthAlertDialog = showHealthAlertDialog(this) { performLogout() }
    }

    /** Clear session, stop the service and return to Login. */
    fun performLogout() {
        vm.logout()
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }

    private companion object {
        const val TAG_EDITOR = "plan_editor"
    }
}

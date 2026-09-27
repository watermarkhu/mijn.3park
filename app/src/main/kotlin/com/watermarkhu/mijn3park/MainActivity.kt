package com.watermarkhu.mijn3park

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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

    private val vm: AppViewModel by viewModels()

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
                    bottomNav.menu.findItem(R.id.nav_planned)?.isEnabled = !permit
                }
            }
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_park
        } else {
            toolbar.setTitle(titleFor(bottomNav.selectedItemId))
            applyHealth(vm.health.value)
        }

        vm.start()
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

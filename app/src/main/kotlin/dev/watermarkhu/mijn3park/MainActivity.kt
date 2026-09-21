package dev.watermarkhu.mijn3park

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
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
        R.id.nav_settings -> SettingsFragment()
        else -> ParkFragment()
    }

    /**
     * When 2Park is unusable, Park/History/Transactions are replaced by a
     * failure screen. Settings stays reachable (logout, theme, product).
     */
    private fun applyHealth(health: HealthState) {
        val onSettings = bottomNav.selectedItemId == R.id.nav_settings
        val showStatus = health != HealthState.OK && !onSettings
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
            HealthState.OK -> Unit
        }
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
}

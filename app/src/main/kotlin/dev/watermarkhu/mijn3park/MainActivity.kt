package dev.watermarkhu.mijn3park

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val api get() = TwoParkApi.instance

    private lateinit var productDropdown: MaterialAutoCompleteTextView
    private lateinit var plateInput: MaterialAutoCompleteTextView
    private lateinit var plateChips: ChipGroup
    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    private lateinit var balanceText: TextView

    private var products: List<Product> = emptyList()
    private var serverMembers: List<Member> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.hasCredentials) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        productDropdown = findViewById(R.id.productDropdown)
        plateInput = findViewById(R.id.plateInput)
        plateChips = findViewById(R.id.plateChips)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        progress = findViewById(R.id.progress)
        balanceText = findViewById(R.id.balanceText)

        if (prefs.lastBalance.isNotBlank()) {
            balanceText.text = getString(R.string.balance_label, prefs.lastBalance)
            balanceText.visibility = View.VISIBLE
        }

        productDropdown.setText(prefs.productName, false)
        productDropdown.setOnItemClickListener { _, _, position, _ ->
            products.getOrNull(position)?.let { product ->
                prefs.productId = product.id
                prefs.productName = product.displayName
                prefs.productLocation = product.location.orEmpty()
                refreshRemoteData()
            }
        }

        toggleButton.setOnClickListener { onToggleParking() }

        renderPlateChips()
        renderState()
        loadProducts()
        refreshRemoteData()
    }

    override fun onResume() {
        super.onResume()
        ParkingService.onStateChanged = {
            renderState()
            ParkingService.lastError?.let { error ->
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                ParkingService.lastError = null
            }
            setBusy(false)
        }
        renderState()
    }

    override fun onPause() {
        ParkingService.onStateChanged = null
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            loadProducts()
            refreshRemoteData()
            true
        }
        R.id.action_logout -> {
            ParkingService.stop(this)
            prefs.clearAll()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // --- Actions ---

    private fun onToggleParking() {
        if (prefs.isParking) {
            setBusy(true)
            ParkingService.stop(this)
            return
        }

        val plate = normalizePlate(plateInput.text?.toString().orEmpty())
        if (plate.isBlank()) {
            Toast.makeText(this, R.string.error_no_plate, Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.productId.isBlank()) {
            Toast.makeText(this, R.string.error_no_product, Toast.LENGTH_SHORT).show()
            return
        }

        prefs.rememberPlate(plate)
        renderPlateChips()
        setBusy(true)
        ParkingService.start(this, plate)
    }

    // --- Data loading ---

    private fun loadProducts() {
        lifecycleScope.launch {
            try {
                ensureLoggedIn()
                products = api.getProducts()
                productDropdown.setAdapter(
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        products.map { it.displayName },
                    )
                )
                // Keep stored selection valid.
                if (products.none { it.id == prefs.productId }) {
                    val first = products.first()
                    prefs.productId = first.id
                    prefs.productName = first.displayName
                    prefs.productLocation = first.location.orEmpty()
                    productDropdown.setText(first.displayName, false)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Pull server-side members: suggest their plates and sync active state. */
    private fun refreshRemoteData() {
        if (prefs.productId.isBlank()) return
        lifecycleScope.launch {
            try {
                ensureLoggedIn()
                val members = api.getMembers(prefs.productId)
                serverMembers = members
                renderPlateChips()

                val suggestions = (members.map { it.plate } + prefs.savedPlates).distinct()
                plateInput.setAdapter(
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        suggestions,
                    )
                )

                val balance = api.getBalance(prefs.productId)
                prefs.lastBalance = balance.formatted
                balanceText.text = getString(R.string.balance_label, balance.formatted)
                balanceText.visibility = View.VISIBLE

                // Sync local state with the server (e.g. parking started/stopped elsewhere).
                val activeMember = members.firstOrNull { it.active && it.actionId != null }
                if (activeMember != null && !prefs.isParking) {
                    prefs.activePlate = activeMember.plate
                    prefs.activeSince = System.currentTimeMillis()
                    ParkingService.start(this@MainActivity, activeMember.plate)
                } else if (activeMember == null && prefs.isParking) {
                    prefs.clearActiveParking()
                    ParkingService.stop(this@MainActivity)
                }
                renderState()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun ensureLoggedIn() {
        if (api.email.isBlank()) {
            api.login(prefs.email, prefs.password)
        }
    }

    // --- Rendering ---

    private fun renderPlateChips() {
        plateChips.removeAllViews()

        // Plates saved on the 2park account (with nickname when set).
        val serverPlates = serverMembers.map { it.plate }.toSet()
        serverMembers.forEach { member ->
            val chip = Chip(this).apply {
                text = member.nickname?.let { "$it · ${member.plate}" } ?: member.plate
                isCheckable = true
                setOnClickListener { plateInput.setText(member.plate, false) }
            }
            plateChips.addView(chip)
        }

        // Locally remembered plates not already on the account (removable).
        prefs.savedPlates.filter { it !in serverPlates }.forEach { plate ->
            val chip = Chip(this).apply {
                text = plate
                isCheckable = true
                isCloseIconVisible = true
                setOnClickListener { plateInput.setText(plate, false) }
                setOnCloseIconClickListener {
                    prefs.savedPlates = prefs.savedPlates.filter { it != plate }
                    renderPlateChips()
                }
            }
            plateChips.addView(chip)
        }
    }

    private fun renderState() {
        if (prefs.isParking) {
            val since = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(prefs.activeSince))
            statusText.text = getString(R.string.status_active, prefs.activePlate, since)
            toggleButton.setText(R.string.stop_parking)
            plateInput.setText(prefs.activePlate, false)
            plateInput.isEnabled = false
            productDropdown.isEnabled = false
        } else {
            statusText.setText(R.string.status_idle)
            toggleButton.setText(R.string.start_parking)
            plateInput.isEnabled = true
            productDropdown.isEnabled = true
        }
    }

    private fun setBusy(busy: Boolean) {
        toggleButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
}

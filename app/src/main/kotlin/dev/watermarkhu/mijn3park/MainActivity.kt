package dev.watermarkhu.mijn3park

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

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
    private lateinit var topupChip: View
    private lateinit var defaultProductStar: MaterialButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusIcon: ImageView
    private lateinit var endTimeRow: View
    private lateinit var endTimeLabel: TextView
    private lateinit var endTimeClear: MaterialButton
    private lateinit var endCountdown: TextView
    private lateinit var plateChipsScroll: View

    /** Planned end picked for the next start; 0 means open-ended. */
    private var selectedEndAt: Long = 0L
    private var countdownJob: Job? = null

    private var products: List<Product> = emptyList()
    private var serverMembers: List<Member> = emptyList()
    private var productDetails: ProductDetails? = null
    private var appliedDefaultProduct = false
    private var refreshOnResume = false

    private val currentProduct: Product?
        get() = products.firstOrNull { it.id == prefs.productId }

    /** Permit products (fixed plate) have no balance, no start/stop. */
    private val isPermitProduct: Boolean
        get() = (productDetails?.fixedPlate != null) || (currentProduct?.hasFixedPlate == true)

    private companion object {
        const val KEY_SELECTED_END_AT = "selected_end_at"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.hasCredentials) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        productDropdown = findViewById(R.id.productDropdown)
        plateInput = findViewById(R.id.plateInput)
        plateChips = findViewById(R.id.plateChips)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        progress = findViewById(R.id.progress)
        balanceText = findViewById(R.id.balanceText)
        topupChip = findViewById(R.id.topupChip)
        topupChip.setOnClickListener { showTopupDialog() }
        statusCard = findViewById(R.id.statusCard)
        statusIcon = findViewById(R.id.statusIcon)
        endTimeRow = findViewById(R.id.endTimeRow)
        endTimeLabel = findViewById(R.id.endTimeLabel)
        endTimeClear = findViewById(R.id.endTimeClear)
        endCountdown = findViewById(R.id.endCountdown)
        plateChipsScroll = findViewById(R.id.plateChipsScroll)

        selectedEndAt = savedInstanceState?.getLong(KEY_SELECTED_END_AT, 0L) ?: 0L

        endTimeLabel.setOnClickListener { showEndDatePicker() }
        endTimeClear.setOnClickListener {
            selectedEndAt = 0L
            renderState()
        }

        defaultProductStar = findViewById(R.id.defaultProductStar)

        if (prefs.lastBalance.isNotBlank()) {
            balanceText.text = getString(R.string.balance_label, prefs.lastBalance)
            balanceText.visibility = View.VISIBLE
            topupChip.visibility = View.VISIBLE
        }

        defaultProductStar.setOnClickListener {
            if (defaultProductStar.isChecked) {
                prefs.defaultProductId = prefs.productId
                Toast.makeText(
                    this,
                    getString(R.string.default_product_set, prefs.productName),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                prefs.defaultProductId = ""
            }
        }

        productDropdown.setText(prefs.productName, false)
        productDropdown.setOnItemClickListener { _, _, position, _ ->
            products.getOrNull(position)?.let { selectProduct(it) }
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
            setBusy(busy = false)
        }
        renderState()
        if (refreshOnResume) {
            refreshOnResume = false
            refreshRemoteData()
        }
    }

    override fun onPause() {
        countdownJob?.cancel()
        ParkingService.onStateChanged = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_SELECTED_END_AT, selectedEndAt)
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
            performLogout()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // --- Actions ---

    private fun onToggleParking() {
        if (prefs.isParking) {
            setBusy(busy = true)
            ParkingService.stop(this)
            return
        }

        val plate = normalizePlate(plateInput.text?.toString().orEmpty())
        if (plate.isBlank()) {
            Toast.makeText(this, R.string.error_no_plate, Toast.LENGTH_SHORT).show()
            return
        }
        productDetails?.let { details ->
            if ((plate == details.fixedPlate) && details.fixedPlateActive) {
                Toast.makeText(
                    this,
                    getString(R.string.fixed_plate_covered, plate),
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }
        if (prefs.productId.isBlank()) {
            Toast.makeText(this, R.string.error_no_product, Toast.LENGTH_SHORT).show()
            return
        }

        // Drop stale end times that passed while idle; never start in the past.
        if ((selectedEndAt > 0L) && (selectedEndAt <= System.currentTimeMillis())) {
            selectedEndAt = 0L
            renderState()
            Toast.makeText(this, R.string.error_past_time, Toast.LENGTH_LONG).show()
            return
        }

        val endAt = selectedEndAt
        prefs.rememberPlate(plate)
        renderPlateChips()
        setBusy(busy = true)
        ParkingService.start(this, plate, endAt)
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
                    ),
                )
                // Preselect the default product once per app start.
                if (!appliedDefaultProduct) {
                    appliedDefaultProduct = true
                    products.firstOrNull { it.id == prefs.defaultProductId }
                        ?.takeIf { it.id != prefs.productId && !prefs.isParking }
                        ?.let { selectProduct(it) }
                }

                // Keep stored selection valid.
                if (products.none { it.id == prefs.productId }) {
                    selectProduct(products.first())
                }
                updateDefaultStar()
            } catch (_: AuthFailedException) {
                autoLogout()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun selectProduct(product: Product) {
        prefs.productId = product.id
        prefs.productName = product.displayName
        prefs.productLocation = product.location.orEmpty()
        prefs.productCategoryId = product.categoryId
        productDropdown.setText(product.displayName, false)
        productDetails = null
        serverMembers = emptyList()
        renderPlateChips()
        renderState()
        updateDefaultStar()
        refreshRemoteData()
    }

    private fun updateDefaultStar() {
        defaultProductStar.visibility = if (products.size > 1) View.VISIBLE else View.GONE
        defaultProductStar.isChecked =
            prefs.productId.isNotBlank() && prefs.productId == prefs.defaultProductId
    }

    /** Pull server-side members: suggest their plates and sync active state. */
    private fun refreshRemoteData() {
        if (prefs.productId.isBlank()) return
        lifecycleScope.launch {
            try {
                ensureLoggedIn()
                val details = api.getDetails(prefs.productId)
                productDetails = details
                val members = details.members
                serverMembers = members
                renderPlateChips()
                invalidateOptionsMenu()

                // Prefill the fixed plate when the field is still empty.
                details.fixedPlate?.let { fixed ->
                    if (plateInput.text.isNullOrBlank() && !prefs.isParking) {
                        plateInput.setText(fixed, false)
                    }
                }

                val suggestions =
                    (listOfNotNull(details.fixedPlate) + members.map { it.plate } + prefs.savedPlates).distinct()
                plateInput.setAdapter(
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        suggestions,
                    ),
                )

                // Permits are not prepaid: no balance to show or top up.
                if (details.fixedPlate == null) {
                    val balance = api.getBalance(prefs.productId)
                    prefs.lastBalance = balance.formatted
                    balanceText.text = getString(R.string.balance_label, balance.formatted)
                    balanceText.visibility = View.VISIBLE
                    topupChip.visibility = View.VISIBLE
                } else {
                    balanceText.visibility = View.GONE
                    topupChip.visibility = View.GONE
                }

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
            } catch (_: AuthFailedException) {
                autoLogout()
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

    /** Full wipe shared by manual logout and expired-session auto-logout. */
    private fun performLogout() {
        ParkingService.stop(this)
        api.logout()
        prefs.clearAll()
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }

    private var autoLoggedOut = false

    /** Saved credentials were rejected: wipe everything, return to Login. */
    private fun autoLogout() {
        if (autoLoggedOut) return
        autoLoggedOut = true
        Toast.makeText(this, R.string.session_expired, Toast.LENGTH_LONG).show()
        performLogout()
    }

    // --- Planned end time ---

    private fun showEndDatePicker() {
        val constraints = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointForward.now())
            .build()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pick_end_date)
            .setCalendarConstraints(constraints)
            .setSelection(
                selectedEndAt.takeIf { it > 0L }
                    ?: MaterialDatePicker.todayInUtcMilliseconds()
            )
            .build()
        picker.addOnPositiveButtonClickListener { showEndTimePicker(it) }
        picker.show(supportFragmentManager, "end_date")
    }

    private fun showEndTimePicker(dateUtcMillis: Long) {
        val preset = if (selectedEndAt > 0L) {
            Calendar.getInstance().apply { timeInMillis = selectedEndAt }
        } else {
            Calendar.getInstance()
        }
        val picker = MaterialTimePicker.Builder()
            .setTitleText(R.string.pick_end_time)
            .setHour(preset[Calendar.HOUR_OF_DAY])
            .setMinute(preset[Calendar.MINUTE])
            .setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H
                else TimeFormat.CLOCK_12H
            )
            .build()
        picker.addOnPositiveButtonClickListener {
            // The date picker returns a UTC midnight; interpret its fields in
            // the device zone so the day matches what was shown.
            val zoneDay = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = dateUtcMillis
            }
            val picked = Calendar.getInstance().apply {
                set(Calendar.YEAR, zoneDay[Calendar.YEAR])
                set(Calendar.MONTH, zoneDay[Calendar.MONTH])
                set(Calendar.DAY_OF_MONTH, zoneDay[Calendar.DAY_OF_MONTH])
                set(Calendar.HOUR_OF_DAY, picker.hour)
                set(Calendar.MINUTE, picker.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (picked.timeInMillis <= System.currentTimeMillis()) {
                Toast.makeText(this, R.string.error_past_time, Toast.LENGTH_LONG).show()
                showEndTimePicker(dateUtcMillis)
                return@addOnPositiveButtonClickListener
            }
            selectedEndAt = picked.timeInMillis
            renderState()
        }
        picker.show(supportFragmentManager, "end_time")
    }

    /** "18:00" when the end is today, "EEE d MMM, HH:mm" otherwise. */
    private fun formatEndShort(endAtMillis: Long): String {
        val endDay = Calendar.getInstance().apply { timeInMillis = endAtMillis }
        val today = Calendar.getInstance()
        val sameDay = endDay[Calendar.YEAR] == today[Calendar.YEAR] &&
            endDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "EEE d MMM, HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(endAtMillis))
    }

    private fun formatRemaining(millis: Long): String {
        val totalSeconds = millis / 1000L
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            days > 0L -> "%dd %02d:%02d:%02d".format(Locale.ROOT, days, hours, minutes, seconds)
            hours > 0L -> "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
            else -> "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    private fun updateCountdown() {
        val endAt = prefs.activeEndAt
        if (!prefs.isParking || endAt <= 0L) {
            endCountdown.visibility = View.GONE
            return
        }
        val remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
        endCountdown.text = getString(R.string.ends_in, formatRemaining(remaining))
        endCountdown.visibility = View.VISIBLE
    }

    private fun startCountdownTicker() {
        countdownJob?.cancel()
        if (!prefs.isParking || prefs.activeEndAt <= 0L) return
        countdownJob = lifecycleScope.launch {
            while (isActive) {
                delay(1.seconds)
                updateCountdown()
            }
        }
    }

    // --- Rendering ---

    private fun renderPlateChips() {
        plateChips.removeAllViews()

        // Fixed plate bound to the permit (FLPN products), always first.
        productDetails?.fixedPlate?.let { fixed ->
            val chip = Chip(this).apply {
                text = getString(R.string.fixed_plate_chip, fixed)
                isCheckable = false
                isClickable = true
                isFocusable = true
                setOnClickListener { plateInput.setText(fixed, false) }
            }
            plateChips.addView(chip)
        }

        // Plates saved on the 2park account (with nickname when set).
        val fixedPlate = productDetails?.fixedPlate
        val serverPlates =
            serverMembers.asSequence().map { it.plate }.toSet() + setOfNotNull(fixedPlate)
        serverMembers.filter { it.plate != fixedPlate }.forEach { member ->
            val chip = Chip(this).apply {
                text = member.nickname?.let { "$it · ${member.plate}" } ?: member.plate
                isCheckable = false
                isClickable = true
                isFocusable = true
                setOnClickListener { plateInput.setText(member.plate, false) }
                setOnLongClickListener {
                    showFavoriteDialog(member)
                    true
                }
            }
            plateChips.addView(chip)
        }

        // Locally remembered plates not already on the account (removable).
        prefs.savedPlates.filter { it !in serverPlates }.forEach { plate ->
            val chip = Chip(this).apply {
                text = plate
                isCheckable = false
                isClickable = true
                isFocusable = true
                isCloseIconVisible = true
                setOnClickListener { plateInput.setText(plate, false) }
                setOnCloseIconClickListener {
                    prefs.savedPlates = prefs.savedPlates.filter { it != plate }
                    renderPlateChips()
                }
                setOnLongClickListener {
                    // Promote a local plate to an account favorite.
                    showFavoriteDialog(null, prefillPlate = plate)
                    true
                }
            }
            plateChips.addView(chip)
        }

        // "+" chip to save a new named plate to the account.
        plateChips.addView(
            Chip(this).apply {
                text = getString(R.string.add)
                isCheckable = false
                isClickable = true
                isFocusable = true
                setChipIconResource(R.drawable.ic_add)
                isChipIconVisible = true
                chipIconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
                )
                contentDescription = getString(R.string.add_plate)
                setOnClickListener { showFavoriteDialog(null) }
            }
        )
    }

    // --- Balance top-up ---

    private fun showTopupDialog() {
        if (isPermitProduct) {
            Toast.makeText(this, R.string.topup_none, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                ensureLoggedIn()
                val options = api.getTopupOptions(prefs.productId)
                if (options.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.topup_none, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val labels = options.map { "€ " + it.replace('.', ',') }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.topup_title)
                    .setItems(labels) { _, which -> startTopup(options[which]) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (_: AuthFailedException) {
                autoLogout()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTopup(amount: String) {
        val categoryId = products.firstOrNull { it.id == prefs.productId }?.categoryId
            ?.takeIf { it.isNotBlank() }
            ?: prefs.productCategoryId
        lifecycleScope.launch {
            try {
                ensureLoggedIn()
                val forward = api.startTopup(categoryId, prefs.productId, amount)
                val browserUrl = api.resolveTopupBrowserUrl(forward)
                refreshOnResume = true
                startActivity(Intent(Intent.ACTION_VIEW, browserUrl.toUri()))
            } catch (_: AuthFailedException) {
                autoLogout()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Account favorites (named plates) ---

    private fun showFavoriteDialog(member: Member?, prefillPlate: String? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_favorite, null)
        val plateField = view.findViewById<TextInputEditText>(R.id.favPlate)
        val nameField = view.findViewById<TextInputEditText>(R.id.favName)

        if (member != null) {
            plateField.setText(member.plate)
            nameField.setText(member.nickname.orEmpty())
        } else {
            plateField.setText(
                prefillPlate ?: normalizePlate(plateInput.text?.toString().orEmpty())
            )
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (member == null) R.string.add_plate else R.string.edit_plate)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val plate = normalizePlate(plateField.text?.toString().orEmpty())
                val name = nameField.text?.toString()?.trim().orEmpty()
                if (plate.isBlank()) {
                    Toast.makeText(this, R.string.error_no_plate, Toast.LENGTH_SHORT).show()
                } else {
                    saveFavorite(member, plate, name)
                }
            }
            .setNegativeButton(R.string.cancel, null)

        if (member != null) {
            builder.setNeutralButton(R.string.delete) { _, _ -> deleteFavorite(member) }
        }
        builder.show()
    }

    private fun saveFavorite(existing: Member?, plate: String, name: String) {
        lifecycleScope.launch {
            try {
                ensureLoggedIn()
                if (existing != null) {
                    if (existing.plate == plate && existing.nickname.orEmpty() == name) {
                        return@launch // nothing changed
                    }
                    // The API has no update: replace by remove + add.
                    api.removeFavorite(prefs.productId, existing.plate, existing.nickname)
                }
                api.addFavorite(prefs.productId, plate, name.ifBlank { null })
                // No longer needed as a local-only plate.
                prefs.savedPlates = prefs.savedPlates.filter { it != plate }
                Toast.makeText(this@MainActivity, R.string.favorite_saved, Toast.LENGTH_SHORT).show()
                refreshRemoteData()
            } catch (_: AuthFailedException) {
                autoLogout()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                refreshRemoteData()
            }
        }
    }

    private fun deleteFavorite(member: Member) {
        lifecycleScope.launch {
            try {
                ensureLoggedIn()
                api.removeFavorite(prefs.productId, member.plate, member.nickname)
                Toast.makeText(this@MainActivity, R.string.favorite_deleted, Toast.LENGTH_SHORT).show()
                refreshRemoteData()
            } catch (_: AuthFailedException) {
                autoLogout()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderState() {
        val parking = prefs.isParking
        if (parking) {
            val since = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(prefs.activeSince))
            statusText.text = getString(R.string.status_active, prefs.activePlate, since)
            toggleButton.setText(R.string.stop_parking)
            plateInput.setText(prefs.activePlate, false)
            plateInput.isEnabled = false
            productDropdown.isEnabled = false
        } else {
            val details = productDetails
            if (details?.fixedPlate != null && details.fixedPlateActive) {
                statusText.text = getString(R.string.status_fixed_plate, details.fixedPlate)
            } else {
                statusText.setText(R.string.status_idle)
            }
            toggleButton.setText(R.string.start_parking)
            plateInput.isEnabled = !isPermitProduct
            productDropdown.isEnabled = true
        }

        // Permits are always-on: hide start/stop entirely (unless a session
        // is somehow running, so it can still be stopped).
        toggleButton.visibility = if (isPermitProduct && !parking) View.GONE else View.VISIBLE

        // Plate chips only make sense before starting: hide them while parking.
        plateChipsScroll.visibility = if (parking) View.GONE else View.VISIBLE

        // Planned end time: selector while idle (permits excluded, there is
        // nothing to end), live countdown while parking. The idle pick is
        // consumed once parking starts; the service owns prefs.activeEndAt.
        if (parking) selectedEndAt = 0L
        endTimeRow.visibility = if (!parking && !isPermitProduct) View.VISIBLE else View.GONE
        if (!parking) {
            endTimeLabel.text = if (selectedEndAt > 0L) {
                getString(R.string.ends_at, formatEndShort(selectedEndAt))
            } else {
                getString(R.string.set_end_time)
            }
            endTimeClear.visibility = if (selectedEndAt > 0L) View.VISIBLE else View.GONE
        }
        updateCountdown()
        startCountdownTicker()

        // MD3 tonal states: primary container while parking or while the
        // permit's fixed plate is covered, neutral otherwise.
        val highlighted = parking || productDetails?.fixedPlateActive == true
        val cardBg = MaterialColors.getColor(
            statusCard,
            if (highlighted) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHighest,
        )
        val cardFg = MaterialColors.getColor(
            statusCard,
            if (highlighted) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurface,
        )
        statusCard.setCardBackgroundColor(cardBg)
        statusText.setTextColor(cardFg)
        statusIcon.imageTintList = ColorStateList.valueOf(cardFg)
        balanceText.setTextColor(
            if (highlighted) cardFg
            else MaterialColors.getColor(balanceText, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        endCountdown.setTextColor(balanceText.currentTextColor)

        // Stop is a destructive action: switch the button to error tones.
        toggleButton.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                toggleButton,
                if (parking) androidx.appcompat.R.attr.colorError
                else androidx.appcompat.R.attr.colorPrimary,
            )
        )
        toggleButton.setTextColor(
            MaterialColors.getColor(
                toggleButton,
                if (parking) com.google.android.material.R.attr.colorOnError
                else com.google.android.material.R.attr.colorOnPrimary,
            )
        )
    }

    private fun setBusy(busy: Boolean) {
        toggleButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
}

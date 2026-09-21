package com.watermarkhu.mijn3park

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class ParkFragment : Fragment(R.layout.fragment_park) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs
    private val api get() = TwoParkApi.instance

    private lateinit var currentProduct: TextView
    private lateinit var plateInput: MaterialAutoCompleteTextView
    private lateinit var plateChips: ChipGroup
    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var balanceText: TextView
    private lateinit var topupChip: View
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

    private var appState = AppState()
    private var refreshOnResume = false

    /** Permit products (fixed plate) have no balance, no start/stop. */
    private val isPermitProduct: Boolean
        get() = (appState.details?.fixedPlate != null) || (appState.selectedProduct?.hasFixedPlate == true)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        currentProduct = view.findViewById(R.id.currentProduct)
        plateInput = view.findViewById(R.id.plateInput)
        plateChips = view.findViewById(R.id.plateChips)
        statusText = view.findViewById(R.id.statusText)
        toggleButton = view.findViewById(R.id.toggleButton)
        progress = view.findViewById(R.id.progress)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        balanceText = view.findViewById(R.id.balanceText)
        topupChip = view.findViewById(R.id.topupChip)
        statusCard = view.findViewById(R.id.statusCard)
        statusIcon = view.findViewById(R.id.statusIcon)
        endTimeRow = view.findViewById(R.id.endTimeRow)
        endTimeLabel = view.findViewById(R.id.endTimeLabel)
        endTimeClear = view.findViewById(R.id.endTimeClear)
        endCountdown = view.findViewById(R.id.endCountdown)
        plateChipsScroll = view.findViewById(R.id.plateChipsScroll)

        selectedEndAt = savedInstanceState?.getLong(KEY_SELECTED_END_AT, 0L) ?: 0L

        topupChip.setOnClickListener { showTopupDialog() }
        endTimeLabel.setOnClickListener { showEndDatePicker() }
        endTimeClear.setOnClickListener {
            selectedEndAt = 0L
            renderState()
        }
        toggleButton.setOnClickListener { onToggleParking() }

        swipeRefresh.setOnRefreshListener { refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    appState = state
                    renderProduct()
                    renderPlateAdapter()
                    renderPlateChips()
                    renderBalance()
                    renderState()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.refreshDone.collect { swipeRefresh.isRefreshing = false }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ParkingService.onStateChanged = {
            renderState()
            ParkingService.lastError?.let { error ->
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                ParkingService.lastError = null
            }
            setBusy(busy = false)
        }
        renderState()
        if (refreshOnResume) {
            refreshOnResume = false
            refresh()
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

    private fun refresh() {
        swipeRefresh.isRefreshing = true
        vm.refresh()
    }

    // --- Actions ---

    private fun onToggleParking() {
        if (vm.health.value != HealthState.OK) {
            Toast.makeText(requireContext(), R.string.health_unreliable_message, Toast.LENGTH_LONG).show()
            return
        }
        if (prefs.isParking) {
            setBusy(busy = true)
            ParkingService.stop(requireContext())
            return
        }

        val plate = normalizePlate(plateInput.text?.toString().orEmpty())
        if (plate.isBlank()) {
            Toast.makeText(requireContext(), R.string.error_no_plate, Toast.LENGTH_SHORT).show()
            return
        }
        appState.details?.let { details ->
            if ((plate == details.fixedPlate) && details.fixedPlateActive) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.fixed_plate_covered, plate),
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }
        if (prefs.productId.isBlank()) {
            Toast.makeText(requireContext(), R.string.error_no_product, Toast.LENGTH_SHORT).show()
            return
        }

        // Drop stale end times that passed while idle; never start in the past.
        if ((selectedEndAt > 0L) && (selectedEndAt <= System.currentTimeMillis())) {
            selectedEndAt = 0L
            renderState()
            Toast.makeText(requireContext(), R.string.error_past_time, Toast.LENGTH_LONG).show()
            return
        }

        val endAt = selectedEndAt
        prefs.rememberPlate(plate)
        renderPlateChips()
        setBusy(busy = true)
        ParkingService.start(requireContext(), plate, endAt)
    }

    // --- Rendering ---

    private fun renderProduct() {
        currentProduct.text = appState.selectedProduct?.displayName ?: prefs.productName
    }

    private fun renderPlateAdapter() {
        val details = appState.details ?: return
        val suggestions =
            (listOfNotNull(details.fixedPlate) + appState.members.map { it.plate } + prefs.savedPlates).distinct()
        plateInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, suggestions),
        )
        // Prefill the fixed plate when the field is still empty.
        details.fixedPlate?.let { fixed ->
            if (plateInput.text.isNullOrBlank() && !prefs.isParking) {
                plateInput.setText(fixed, false)
            }
        }
    }

    private fun renderBalance() {
        val details = appState.details
        if (details?.fixedPlate != null) {
            balanceText.isVisible = false
            topupChip.isVisible = false
            return
        }
        val balance = appState.balance.ifBlank { prefs.lastBalance }
        if (balance.isNotBlank()) {
            balanceText.text = getString(R.string.balance_label, balance)
            balanceText.isVisible = true
            topupChip.isVisible = true
        } else {
            balanceText.isVisible = false
            topupChip.isVisible = false
        }
    }

    private fun renderPlateChips() {
        plateChips.removeAllViews()

        // Fixed plate bound to the permit (FLPN products), always first.
        appState.details?.fixedPlate?.let { fixed ->
            val chip = Chip(requireContext()).apply {
                text = getString(R.string.fixed_plate_chip, fixed)
                isCheckable = false
                isClickable = true
                isFocusable = true
                setOnClickListener { plateInput.setText(fixed, false) }
            }
            plateChips.addView(chip)
        }

        // Plates saved on the 2park account (with nickname when set).
        val fixedPlate = appState.details?.fixedPlate
        val serverPlates =
            appState.members.asSequence().map { it.plate }.toSet() + setOfNotNull(fixedPlate)
        appState.members.filter { it.plate != fixedPlate }.forEach { member ->
            val chip = Chip(requireContext()).apply {
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
            val chip = Chip(requireContext()).apply {
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
            Chip(requireContext()).apply {
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
            Toast.makeText(requireContext(), R.string.topup_none, Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val options = api.getTopupOptions(prefs.productId)
                if (options.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.topup_none, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val labels = options.map { "€ " + it.replace('.', ',') }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.topup_title)
                    .setItems(labels) { _, which -> startTopup(options[which]) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTopup(amount: String) {
        val categoryId = appState.products.firstOrNull { it.id == prefs.productId }?.categoryId
            ?.takeIf { it.isNotBlank() }
            ?: prefs.productCategoryId
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val forward = api.startTopup(categoryId, prefs.productId, amount)
                val browserUrl = api.resolveTopupBrowserUrl(forward)
                refreshOnResume = true
                startActivity(Intent(Intent.ACTION_VIEW, browserUrl.toUri()))
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Account favorites (named plates) ---

    private fun showFavoriteDialog(member: Member?, prefillPlate: String? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_favorite, null)
        val plateField = dialogView.findViewById<TextInputEditText>(R.id.favPlate)
        val nameField = dialogView.findViewById<TextInputEditText>(R.id.favName)

        if (member != null) {
            plateField.setText(member.plate)
            nameField.setText(member.nickname.orEmpty())
        } else {
            plateField.setText(
                prefillPlate ?: normalizePlate(plateInput.text?.toString().orEmpty())
            )
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (member == null) R.string.add_plate else R.string.edit_plate)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val plate = normalizePlate(plateField.text?.toString().orEmpty())
                val name = nameField.text?.toString()?.trim().orEmpty()
                if (plate.isBlank()) {
                    Toast.makeText(requireContext(), R.string.error_no_plate, Toast.LENGTH_SHORT).show()
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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
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
                Toast.makeText(requireContext(), R.string.favorite_saved, Toast.LENGTH_SHORT).show()
                vm.refreshRemoteData()
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                vm.refreshRemoteData()
            }
        }
    }

    private fun deleteFavorite(member: Member) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                api.removeFavorite(prefs.productId, member.plate, member.nickname)
                Toast.makeText(requireContext(), R.string.favorite_deleted, Toast.LENGTH_SHORT).show()
                vm.refreshRemoteData()
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
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
        } else {
            val details = appState.details
            if (details?.fixedPlate != null && details.fixedPlateActive) {
                statusText.text = getString(R.string.status_fixed_plate, details.fixedPlate)
            } else {
                statusText.setText(R.string.status_idle)
            }
            toggleButton.setText(R.string.start_parking)
            plateInput.isEnabled = !isPermitProduct
        }

        // Permits are always-on: hide start/stop entirely (unless a session
        // is somehow running, so it can still be stopped).
        toggleButton.visibility = if (isPermitProduct && !parking) View.GONE else View.VISIBLE

        // Plate chips only make sense before starting: hide them while parking.
        plateChipsScroll.visibility = if (parking) View.GONE else View.VISIBLE

        // Planned end time: selector while idle (permits excluded), live
        // countdown while parking. The idle pick is consumed once parking
        // starts; the service owns prefs.activeEndAt.
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
        val highlighted = parking || appState.details?.fixedPlateActive == true
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
        picker.show(childFragmentManager, "end_date")
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
                if (android.text.format.DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H
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
                Toast.makeText(requireContext(), R.string.error_past_time, Toast.LENGTH_LONG).show()
                showEndTimePicker(dateUtcMillis)
                return@addOnPositiveButtonClickListener
            }
            selectedEndAt = picked.timeInMillis
            renderState()
        }
        picker.show(childFragmentManager, "end_time")
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
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(1.seconds)
                updateCountdown()
            }
        }
    }

    private companion object {
        const val KEY_SELECTED_END_AT = "selected_end_at"
    }
}

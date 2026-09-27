package com.watermarkhu.mijn3park

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * Full-screen editor for a planned ("Gepland") parking session. Reuses the
 * Park screen's plate field + chips via [PlatePicker]. Creating a new plan
 * starts empty (no assumed values); editing prefills the merged session.
 *
 * A session that crosses midnight is saved by [TwoParkApi.planAction] as
 * consecutive same-day legs.
 */
class PlanEditFragment : Fragment(R.layout.fragment_plan_edit) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs
    private val api get() = TwoParkApi.instance

    private lateinit var platePicker: PlatePicker
    private lateinit var startButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    private var startAt = 0L
    private var endAt = 0L
    private val editingIds = mutableListOf<String>()

    private val isEdit: Boolean get() = editingIds.isNotEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val plateInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.plateInput)
        val plateChips = view.findViewById<ChipGroup>(R.id.plateChips)
        startButton = view.findViewById(R.id.planStartButton)
        endButton = view.findViewById(R.id.planEndButton)
        saveButton = view.findViewById(R.id.planSaveButton)
        progress = view.findViewById(R.id.progress)

        val initialSaveMarginBottom = (saveButton.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(saveButton) { v, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.bottomMargin = initialSaveMarginBottom + bottomInset
                v.layoutParams = lp
            }
            insets
        }

        // Selection-only: no favorite add/edit on the editor screen.
        platePicker = PlatePicker(requireContext(), plateInput, plateChips)

        arguments?.let { args ->
            if (args.containsKey(KEY_START)) {
                startAt = args.getLong(KEY_START)
                endAt = args.getLong(KEY_END)
                editingIds.addAll(args.getStringArrayList(KEY_IDS).orEmpty())
                platePicker.setText(args.getString(KEY_PLATE).orEmpty())
            }
        }

        plateInput.doAfterTextChanged { render() }
        startButton.setOnClickListener {
            pickDateTime(startAt, R.string.planned_pick_start) { picked ->
                startAt = picked
                render()
            }
        }
        endButton.setOnClickListener {
            pickDateTime(endAt, R.string.planned_pick_end) { picked ->
                endAt = picked
                render()
            }
        }
        saveButton.setOnClickListener { save() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    val suggestions =
                        (listOfNotNull(state.details?.fixedPlate) + state.members.map { it.plate } + prefs.savedPlates)
                            .distinct()
                    platePicker.setSuggestions(suggestions)
                    platePicker.render(state.members, state.details?.fixedPlate, prefs.savedPlates)
                    render()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.health.collect { render() }
            }
        }
        render()
    }

    private fun render() {
        startButton.text = if (startAt > 0L) {
            getString(R.string.planned_start_value, prettyTime(Planning.formatTimestamp(startAt)))
        } else {
            getString(R.string.planned_pick_start)
        }
        endButton.text = if (endAt > 0L) {
            getString(R.string.planned_end_value, prettyTime(Planning.formatTimestamp(endAt)))
        } else {
            getString(R.string.planned_pick_end)
        }
        saveButton.setText(if (isEdit) R.string.save else R.string.planned_add)
        saveButton.isEnabled = vm.health.value == HealthState.OK &&
            platePicker.selectedPlate().isNotBlank() &&
            startAt > System.currentTimeMillis() &&
            endAt > startAt
    }

    private fun save() {
        val plate = platePicker.selectedPlate()
        when {
            plate.isBlank() -> toast(R.string.error_no_plate)
            startAt <= System.currentTimeMillis() -> toast(R.string.error_planned_start_past)
            endAt <= startAt -> toast(R.string.error_planned_end_before_start)
            else -> persist(plate)
        }
    }

    private fun persist(plate: String) {
        val edit = isEdit
        val productId = prefs.productId
        viewLifecycleOwner.lifecycleScope.launch {
            progress.isVisible = true
            try {
                // Refuse overlaps with other planned sessions for the same plate.
                val existing = api.getPlanned(productId).filter { it.id !in editingIds }
                if (Planning.overlaps(existing, plate, startAt, endAt)) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.planned_overlap, plate),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                for (id in editingIds) api.cancelPlanned(productId, id)
                api.planAction(productId, prefs.productLocation.ifBlank { null }, plate, startAt, endAt)
                Toast.makeText(
                    requireContext(),
                    if (edit) R.string.planned_changed else R.string.planned_saved,
                    Toast.LENGTH_SHORT,
                ).show()
                // Refresh shared state so the Planned list reloads on return.
                vm.refreshRemoteData()
                (activity as? MainActivity)?.closePlanEditor()
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: ApiUnavailableException) {
                vm.reportApiFailure(e)
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            } catch (e: ApiIncompatibleException) {
                vm.reportApiFailure(e)
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            } finally {
                progress.isVisible = false
            }
        }
    }

    // --- Date/time pickers ---

    private fun pickDateTime(initial: Long, titleRes: Int, onPicked: (Long) -> Unit) {
        val constraints = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointForward.now())
            .build()
        val seeded = if (initial > 0L) initial else System.currentTimeMillis()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(titleRes)
            .setCalendarConstraints(constraints)
            .setSelection(dateUtcMidnight(seeded))
            .build()
        picker.addOnPositiveButtonClickListener { showTimePicker(it, seeded, onPicked) }
        picker.show(childFragmentManager, "plan_date")
    }

    private fun showTimePicker(dateUtcMillis: Long, initial: Long, onPicked: (Long) -> Unit) {
        val preset = Calendar.getInstance().apply { timeInMillis = initial }
        val picker = MaterialTimePicker.Builder()
            .setHour(preset[Calendar.HOUR_OF_DAY])
            .setMinute(preset[Calendar.MINUTE])
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H
                else TimeFormat.CLOCK_12H
            )
            .build()
        picker.addOnPositiveButtonClickListener {
            // The date picker returns a UTC midnight; interpret its fields in
            // the device zone so the day matches what was shown.
            val zoneDay = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
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
            onPicked(picked.timeInMillis)
        }
        picker.show(childFragmentManager, "plan_time")
    }

    /** UTC midnight for the device-zone day [millis] falls in. */
    private fun dateUtcMidnight(millis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = millis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local[Calendar.YEAR], local[Calendar.MONTH], local[Calendar.DAY_OF_MONTH])
        }.timeInMillis
    }

    private fun toast(resId: Int) =
        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_PLATE = "plate"
        private const val KEY_START = "start"
        private const val KEY_END = "end"
        private const val KEY_IDS = "leg_ids"

        fun newCreate(): PlanEditFragment = PlanEditFragment()

        fun newEdit(plate: String, startAt: Long, endAt: Long, legIds: List<String>): PlanEditFragment =
            PlanEditFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_PLATE, plate)
                    putLong(KEY_START, startAt)
                    putLong(KEY_END, endAt)
                    putStringArrayList(KEY_IDS, ArrayList(legIds))
                }
            }
    }
}

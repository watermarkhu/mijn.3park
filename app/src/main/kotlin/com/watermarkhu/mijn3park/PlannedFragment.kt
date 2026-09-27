package com.watermarkhu.mijn3park

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * "Gepland" tab: list, create, edit and cancel future planned parking sessions
 * for the selected product. Sessions that cross midnight are shown as one row
 * but stored as consecutive same-day legs (see [Planning]).
 */
class PlannedFragment : Fragment(R.layout.fragment_planned) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs
    private val api get() = TwoParkApi.instance

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var permitNotice: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var planButton: MaterialButton
    private val adapter = PlannedAdapter()

    private val isPermitProduct: Boolean
        get() {
            val state = vm.state.value
            return state.selectedProduct?.hasFixedPlate == true || state.details?.fixedPlate != null
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        permitNotice = view.findViewById(R.id.permitNotice)
        progress = view.findViewById(R.id.progress)
        planButton = view.findViewById(R.id.planButton)
        empty.setText(R.string.planned_empty)
        permitNotice.setText(R.string.planned_permit_unavailable)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        adapter.onEdit = { showPlanDialog(it) }
        adapter.onCancel = { confirmCancel(it) }

        planButton.setOnClickListener { showPlanDialog(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { load() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val permit = isPermitProduct
        permitNotice.isVisible = permit
        planButton.isVisible = !permit
        if (permit) {
            recycler.isVisible = false
            empty.isVisible = false
            progress.isVisible = false
            return
        }
        recycler.isVisible = true
        val productId = prefs.productId
        if (productId.isBlank()) return
        progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val planned = api.getPlanned(productId)
                if (productId != prefs.productId) return@launch
                val groups = Planning.mergeGroups(planned)
                adapter.submit(groups)
                empty.isVisible = groups.isEmpty()
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: ApiUnavailableException) {
                vm.reportApiFailure(e)
            } catch (e: ApiIncompatibleException) {
                vm.reportApiFailure(e)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            } finally {
                progress.isVisible = false
            }
        }
    }

    // --- Create / edit ---

    private fun showPlanDialog(group: List<PlannedAction>?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_planned, null)
        val plateField = dialogView.findViewById<TextInputEditText>(R.id.planPlate)
        val startButton = dialogView.findViewById<MaterialButton>(R.id.planStartButton)
        val endButton = dialogView.findViewById<MaterialButton>(R.id.planEndButton)

        var startAt = group?.firstOrNull()?.let { Planning.parseTimestamp(it.timeStart) }
            ?: (System.currentTimeMillis() + 60_000L)
        var endAt = group?.lastOrNull()?.let { Planning.parseTimestamp(it.timeEnd) }
            ?: (startAt + 60_000L)

        group?.firstOrNull()?.let { plateField.setText(it.plate) }

        fun renderTimes() {
            startButton.text = getString(
                R.string.planned_start_value,
                prettyTime(Planning.formatTimestamp(startAt)),
            )
            endButton.text = getString(
                R.string.planned_end_value,
                prettyTime(Planning.formatTimestamp(endAt)),
            )
        }
        renderTimes()

        startButton.setOnClickListener {
            pickDateTime(startAt, R.string.planned_pick_start) { picked ->
                startAt = picked
                renderTimes()
            }
        }
        endButton.setOnClickListener {
            pickDateTime(endAt, R.string.planned_pick_end) { picked ->
                endAt = picked
                renderTimes()
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (group == null) R.string.planned_add else R.string.planned_edit)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val plate = normalizePlate(plateField.text?.toString().orEmpty())
                when {
                    plate.isBlank() -> toast(R.string.error_no_plate)
                    startAt <= System.currentTimeMillis() -> toast(R.string.error_planned_start_past)
                    endAt <= startAt -> toast(R.string.error_planned_end_before_start)
                    else -> savePlan(group, plate, startAt, endAt)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun savePlan(group: List<PlannedAction>?, plate: String, startAt: Long, endAt: Long) {
        val isEdit = group != null
        val productId = prefs.productId
        viewLifecycleOwner.lifecycleScope.launch {
            progress.isVisible = true
            try {
                // Refuse overlaps with other planned sessions for the same plate.
                val editingIds = group?.map { it.id }.orEmpty()
                val existing = api.getPlanned(productId).filter { it.id !in editingIds }
                if (Planning.overlaps(existing, plate, startAt, endAt)) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.planned_overlap, plate),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                group?.forEach { api.cancelPlanned(productId, it.id) }
                api.planAction(productId, prefs.productLocation.ifBlank { null }, plate, startAt, endAt)
                Toast.makeText(
                    requireContext(),
                    if (isEdit) R.string.planned_changed else R.string.planned_saved,
                    Toast.LENGTH_SHORT,
                ).show()
                load()
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: ApiUnavailableException) {
                vm.reportApiFailure(e)
            } catch (e: ApiIncompatibleException) {
                vm.reportApiFailure(e)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                load()
            } finally {
                progress.isVisible = false
            }
        }
    }

    private fun confirmCancel(group: List<PlannedAction>) {
        val plate = group.first().plate
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.planned_confirm_cancel, plate))
            .setPositiveButton(R.string.planned_cancel_yes) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        group.forEach { api.cancelPlanned(prefs.productId, it.id) }
                        Toast.makeText(requireContext(), R.string.planned_removed, Toast.LENGTH_SHORT).show()
                        load()
                    } catch (_: AuthFailedException) {
                        vm.reportSessionExpired()
                    } catch (_: SessionExpiredException) {
                        vm.reportSessionExpired()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Date/time pickers ---

    private fun pickDateTime(initial: Long, titleRes: Int, onPicked: (Long) -> Unit) {
        val constraints = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointForward.now())
            .build()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(titleRes)
            .setCalendarConstraints(constraints)
            .setSelection(dateUtcMidnight(initial))
            .build()
        picker.addOnPositiveButtonClickListener { showTimePicker(it, initial, onPicked) }
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

    // --- List ---

    private class PlannedAdapter : RecyclerView.Adapter<PlannedAdapter.ViewHolder>() {

        private val items = mutableListOf<List<PlannedAction>>()
        var onEdit: ((List<PlannedAction>) -> Unit)? = null
        var onCancel: ((List<PlannedAction>) -> Unit)? = null

        fun submit(newItems: List<List<PlannedAction>>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_planned, parent, false)
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val plate: TextView = view.findViewById(R.id.plate)
            private val period: TextView = view.findViewById(R.id.period)
            private val editButton: MaterialButton = view.findViewById(R.id.editButton)
            private val cancelButton: MaterialButton = view.findViewById(R.id.cancelButton)

            fun bind(group: List<PlannedAction>) {
                val context = itemView.context
                val first = group.first()
                val last = group.last()
                plate.text = first.plate
                period.text = context.getString(
                    R.string.history_period,
                    prettyTime(first.timeStart),
                    prettyTime(last.timeEnd),
                )
                editButton.setOnClickListener { onEdit?.invoke(group) }
                cancelButton.setOnClickListener { onCancel?.invoke(group) }
            }
        }
    }
}

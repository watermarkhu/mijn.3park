package com.watermarkhu.mijn3park

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared "pick a date and time" dialog. The date defaults to today and is shown
 * as its own button, so picking a time for today needs no date interaction; the
 * time opens the clock first.
 */
object DateTimePicker {

    fun show(
        fragment: Fragment,
        titleRes: Int,
        initialMillis: Long,
        onPicked: (Long) -> Unit,
    ) {
        val context = fragment.requireContext()
        val picked = Calendar.getInstance().apply {
            timeInMillis = initialMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_datetime, null)
        val dateButton = view.findViewById<MaterialButton>(R.id.dateButton)
        val timeButton = view.findViewById<MaterialButton>(R.id.timeButton)

        fun render() {
            dateButton.text = context.getString(R.string.date_value, formatDate(context, picked))
            timeButton.text = context.getString(R.string.time_value, formatTime(picked))
        }
        render()

        dateButton.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.pick_date)
                .setCalendarConstraints(
                    CalendarConstraints.Builder()
                        .setValidator(DateValidatorPointForward.now())
                        .build(),
                )
                .setSelection(localDayToUtcMillis(picked))
                .build()
            picker.addOnPositiveButtonClickListener { utcMillis ->
                // The date picker returns a UTC midnight; interpret its fields in
                // the device zone so the day matches what was shown.
                val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = utcMillis
                }
                picked.set(Calendar.YEAR, day[Calendar.YEAR])
                picked.set(Calendar.MONTH, day[Calendar.MONTH])
                picked.set(Calendar.DAY_OF_MONTH, day[Calendar.DAY_OF_MONTH])
                render()
            }
            picker.show(fragment.childFragmentManager, "datetime_date")
        }

        timeButton.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTitleText(R.string.pick_time)
                .setHour(picked[Calendar.HOUR_OF_DAY])
                .setMinute(picked[Calendar.MINUTE])
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setTimeFormat(
                    if (DateFormat.is24HourFormat(context)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H,
                )
                .build()
            picker.addOnPositiveButtonClickListener {
                picked.set(Calendar.HOUR_OF_DAY, picker.hour)
                picked.set(Calendar.MINUTE, picker.minute)
                render()
            }
            picker.show(fragment.childFragmentManager, "datetime_time")
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(picked.timeInMillis) }
            .show()
    }

    /** "Today"/"Tomorrow" when applicable, otherwise e.g. "Thu 12 Jun". */
    private fun formatDate(context: Context, cal: Calendar): String {
        val today = Calendar.getInstance()
        if (cal[Calendar.YEAR] == today[Calendar.YEAR] &&
            cal[Calendar.DAY_OF_YEAR] == today[Calendar.DAY_OF_YEAR]
        ) {
            return context.getString(R.string.date_today)
        }
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        if (cal[Calendar.YEAR] == tomorrow[Calendar.YEAR] &&
            cal[Calendar.DAY_OF_YEAR] == tomorrow[Calendar.DAY_OF_YEAR]
        ) {
            return context.getString(R.string.date_tomorrow)
        }
        return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(cal.timeInMillis))
    }

    private fun formatTime(cal: Calendar): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cal.timeInMillis))

    /** UTC midnight for the device-zone day [cal] falls in. */
    private fun localDayToUtcMillis(cal: Calendar): Long {
        val local = Calendar.getInstance().apply { timeInMillis = cal.timeInMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local[Calendar.YEAR], local[Calendar.MONTH], local[Calendar.DAY_OF_MONTH])
        }.timeInMillis
    }
}

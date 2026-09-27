package com.watermarkhu.mijn3park

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * Shared license-plate field + plate chips used by the Park screen and the
 * planned-session editor. The host inflates `view_plate_picker.xml` and hands
 * the views to this picker, which fills the field when a chip is tapped.
 *
 * Optional callbacks wire the Park screen's favorite management; the editor
 * leaves them null and gets selection-only chips.
 */
class PlatePicker(
    private val context: Context,
    private val plateInput: MaterialAutoCompleteTextView,
    private val plateChips: ChipGroup,
) {

    /** Long-press on a local chip: promote it to a named account favorite. */
    var onAddFavorite: ((String?) -> Unit)? = null

    /** Long-press on an account plate chip: edit that favorite. */
    var onEditFavorite: ((Member) -> Unit)? = null

    /** Close icon on a local chip: forget the locally saved plate. */
    var onRemoveLocal: ((String) -> Unit)? = null

    fun selectedPlate(): String =
        normalizePlate(plateInput.text?.toString().orEmpty())

    fun setText(plate: String) {
        plateInput.setText(plate, false)
    }

    fun setEnabled(enabled: Boolean) {
        plateInput.isEnabled = enabled
    }

    fun setSuggestions(plates: List<String>) {
        plateInput.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_list_item_1, plates),
        )
    }

    fun render(members: List<Member>, fixedPlate: String?, savedPlates: List<String>) {
        plateChips.removeAllViews()

        // Fixed plate bound to the permit (FLPN products), always first.
        fixedPlate?.let { fixed ->
            plateChips.addView(
                chip(
                    text = context.getString(R.string.fixed_plate_chip, fixed),
                    onClick = { plateInput.setText(fixed, false) },
                )
            )
        }

        // Plates saved on the 2park account (with nickname when set).
        val serverPlates = members.asSequence().map { it.plate }.toSet() + setOfNotNull(fixedPlate)
        members.filter { it.plate != fixedPlate }.forEach { member ->
            plateChips.addView(
                chip(
                    text = member.nickname?.let { "$it · ${member.plate}" } ?: member.plate,
                    onClick = { plateInput.setText(member.plate, false) },
                    onLongClick = onEditFavorite?.let { cb -> { cb(member); true } },
                )
            )
        }

        // Locally remembered plates not already on the account (removable).
        savedPlates.filter { it !in serverPlates }.forEach { plate ->
            plateChips.addView(
                chip(
                    text = plate,
                    onClick = { plateInput.setText(plate, false) },
                    onLongClick = onAddFavorite?.let { cb -> { cb(plate); true } },
                    onClose = onRemoveLocal?.let { cb -> { cb(plate) } },
                )
            )
        }

        // "+" chip to save a new named plate to the account (Park screen only).
        if (onAddFavorite != null) {
            plateChips.addView(
                Chip(context).apply {
                    text = context.getString(R.string.add)
                    isCheckable = false
                    isClickable = true
                    isFocusable = true
                    setChipIconResource(R.drawable.ic_add)
                    isChipIconVisible = true
                    chipIconTint = ColorStateList.valueOf(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary),
                    )
                    contentDescription = context.getString(R.string.add_plate)
                    setOnClickListener { onAddFavorite?.invoke(null) }
                }
            )
        }
    }

    private fun chip(
        text: String,
        onClick: () -> Unit,
        onLongClick: (() -> Boolean)? = null,
        onClose: (() -> Unit)? = null,
    ): Chip = Chip(context).apply {
        this.text = text
        isCheckable = false
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        onLongClick?.let { listener -> setOnLongClickListener { listener() } }
        onClose?.let { listener ->
            isCloseIconVisible = true
            setOnCloseIconClickListener { listener() }
        }
    }
}

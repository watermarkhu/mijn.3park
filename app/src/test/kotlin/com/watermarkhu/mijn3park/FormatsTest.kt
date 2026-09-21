package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatsTest {

    @Test
    fun prettyTime_rendersDashForMissingValue() {
        assertEquals("—", prettyTime(null))
        assertEquals("—", prettyTime(""))
        assertEquals("—", prettyTime("   "))
    }

    @Test
    fun prettyTime_formatsKnownServerTimestamps() {
        assertEquals("01-02-2024 09:05", prettyTime("01-02-2024 09:05:00"))
        assertEquals("01-02-2024 09:05", prettyTime("01-02-2024 09:05"))
    }

    @Test
    fun prettyTime_passesUnknownValuesThrough() {
        assertEquals("not a date", prettyTime("not a date"))
    }

    @Test
    fun formatAmount_handlesMissingAmount() {
        assertNull(formatAmount(null, "€"))
        assertNull(formatAmount("   ", "€"))
    }

    @Test
    fun formatAmount_formatsEurosWithCommaDecimal() {
        assertEquals("€ 10,00", formatAmount("10.00", "€"))
        // A missing unit is treated as euro.
        assertEquals("€ 10,00", formatAmount("10.00", null))
        assertEquals("€ 10,00", formatAmount("10.00", ""))
    }

    @Test
    fun formatAmount_handlesNonCurrencyUnits() {
        assertEquals("10", formatAmount("10", "#"))
        assertEquals("12 min", formatAmount("12", "Minuut"))
        assertEquals("km 3", formatAmount("3", "km"))
    }
}

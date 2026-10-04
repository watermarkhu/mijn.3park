package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderMathTest {

    private val minute = 60_000L

    @Test
    fun offProducesNoReminder() {
        assertNull(ReminderMath.nextReminderAt(now = 1_000L, intervalMinutes = 0, endAt = 0L))
    }

    @Test
    fun negativeIntervalProducesNoReminder() {
        assertNull(ReminderMath.nextReminderAt(now = 1_000L, intervalMinutes = -30, endAt = 0L))
    }

    @Test
    fun openEndedSchedulesNextTick() {
        assertEquals(1_000L + 30 * minute, ReminderMath.nextReminderAt(1_000L, 30, 0L))
    }

    @Test
    fun nextTickBeforeEndIsKept() {
        assertEquals(30 * minute, ReminderMath.nextReminderAt(0L, 30, 100 * minute))
    }

    @Test
    fun noReminderWhenNextTickIsAfterEnd() {
        assertNull(ReminderMath.nextReminderAt(0L, 30, 10 * minute))
    }

    @Test
    fun reminderExactlyAtEndIsDropped() {
        assertNull(ReminderMath.nextReminderAt(0L, 30, 30 * minute))
    }

    @Test
    fun intervalOptionsMatchSettingsChoices() {
        assertEquals(listOf(0, 30, 60, 120, 240, 480, 960, 1440), ReminderMath.INTERVAL_OPTIONS)
    }
}

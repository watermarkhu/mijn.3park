package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PlanningTest {

    @Test
    fun splitByDay_singleDayStaysOneLeg() {
        val start = timestamp(2026, 9, 28, 10, 0)
        val end = timestamp(2026, 9, 28, 15, 0)
        assertEquals(listOf(start to end), Planning.splitByDay(start, end))
    }

    @Test
    fun splitByDay_crossMidnightSplitsAtMidnight() {
        val start = timestamp(2026, 9, 28, 22, 0)
        val end = timestamp(2026, 9, 29, 6, 0)
        val legs = Planning.splitByDay(start, end)
        assertEquals(2, legs.size)
        assertEquals(start, legs[0].first)
        assertEquals(endOfDay(2026, 9, 28), legs[0].second)
        assertEquals(startOfDay(2026, 9, 29), legs[1].first)
        assertEquals(end, legs[1].second)
    }

    @Test
    fun splitByDay_multiDayProducesFullMidnightLegs() {
        val start = timestamp(2026, 9, 28, 22, 0)
        val end = timestamp(2026, 9, 30, 6, 0)
        val legs = Planning.splitByDay(start, end)
        assertEquals(3, legs.size)
        assertEquals(endOfDay(2026, 9, 29), legs[1].second)
        assertEquals(startOfDay(2026, 9, 30), legs[2].first)
        // Middle leg spans the whole day.
        assertEquals(startOfDay(2026, 9, 29), legs[1].first)
    }

    @Test
    fun mergeGroups_joinsMidnightLegsAndKeepsGapsSeparate() {
        val plate = "AB12CD"
        val night1 = planned(plate, "28-09-2026 22:00:00", "28-09-2026 23:59:59")
        val night2 = planned(plate, "29-09-2026 00:00:00", "29-09-2026 06:00:00")
        val later = planned(plate, "01-10-2026 08:00:00", "01-10-2026 10:00:00")
        val groups = Planning.mergeGroups(listOf(later, night1, night2))
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].size)
        assertEquals(1, groups[1].size)
    }

    @Test
    fun mergeGroups_keepsDifferentPlatesSeparate() {
        val a = planned("AB12CD", "28-09-2026 22:00:00", "28-09-2026 23:59:59")
        val b = planned("EF34GH", "29-09-2026 00:00:00", "29-09-2026 06:00:00")
        assertEquals(2, Planning.mergeGroups(listOf(a, b)).size)
    }

    @Test
    fun overlaps_detectsIntersectionButNotTouchingEdges() {
        val existing = listOf(planned("AB12CD", "28-09-2026 10:00:00", "28-09-2026 12:00:00"))
        assertTrue(
            Planning.overlaps(existing, "ab-12cd", timestamp(2026, 9, 28, 11, 0), timestamp(2026, 9, 28, 13, 0))
        )
        // Adjacent (starts exactly when the other ends) is not an overlap.
        assertFalse(
            Planning.overlaps(existing, "AB12CD", timestamp(2026, 9, 28, 12, 0), timestamp(2026, 9, 28, 14, 0))
        )
        // Different plate never overlaps.
        assertFalse(
            Planning.overlaps(existing, "EF34GH", timestamp(2026, 9, 28, 11, 0), timestamp(2026, 9, 28, 13, 0))
        )
    }

    @Test
    fun formatAndParseTimestamp_roundTrip() {
        val millis = timestamp(2026, 9, 28, 14, 30)
        assertEquals("28-09-2026 14:30:00", Planning.formatTimestamp(millis))
        assertEquals(millis, Planning.parseTimestamp("28-09-2026 14:30:00"))
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun endOfDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 23, 59, 59)
        }.timeInMillis

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis

    private fun planned(plate: String, start: String, end: String) =
        PlannedAction(
            id = "id",
            plate = plate,
            nickname = null,
            timeStart = start,
            timeEnd = end,
            location = null,
        )
}

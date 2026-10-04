package com.watermarkhu.mijn3park

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Helpers around the 2Park same-day planning constraint (the "Gepland" tab).
 *
 * The web API rejects a planned session that crosses midnight: a single
 * `start_action` call can only span within one calendar day. Longer plans are
 * therefore split into per-day legs (each ending 23:59:59, the next starting
 * 00:00:00) and merged again for display into one session.
 */
object Planning {

    const val TIME_FORMAT = "dd-MM-yyyy HH:mm:ss"

    /** Gap tolerated when joining legs into one session (23:59:59 -> 00:00:00). */
    const val JOIN_TOLERANCE_MS = 60_000L

    private val timeFormat = SimpleDateFormat(TIME_FORMAT, Locale.ROOT)

    fun parseTimestamp(value: String): Long =
        timeFormat.parse(value)?.time ?: 0L

    fun formatTimestamp(millis: Long): String =
        timeFormat.format(Date(millis))

    /** 23:59:59 of the day [millis] falls in. */
    fun endOfDay(millis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 00:00:00 of the day after [millis]. */
    fun startOfNextDay(millis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Split [startAt]..[endAt] into per-day legs that never cross midnight. */
    fun splitByDay(startAt: Long, endAt: Long): List<Pair<Long, Long>> {
        require(startAt < endAt) { "Start must be before end" }
        val legs = mutableListOf<Pair<Long, Long>>()
        var cursor = startAt
        while (cursor < endAt) {
            val legEnd = minOf(endOfDay(cursor), endAt)
            legs.add(cursor to legEnd)
            if (legEnd >= endAt) break
            cursor = startOfNextDay(legEnd)
        }
        return legs
    }

    /**
     * Group scheduled legs into single sessions: same plate and consecutive
     * legs where the previous ends and the next starts around midnight.
     */
    fun mergeGroups(actions: List<PlannedAction>): List<List<PlannedAction>> {
        val sorted = actions.sortedBy { parseTimestamp(it.timeStart) }
        val groups = mutableListOf<List<PlannedAction>>()
        val current = mutableListOf<PlannedAction>()
        for (action in sorted) {
            val start = parseTimestamp(action.timeStart)
            val prev = current.lastOrNull()
            val contiguous = prev != null &&
                prev.plate == action.plate &&
                parseTimestamp(prev.timeEnd) + JOIN_TOLERANCE_MS >= start
            if (contiguous) {
                current.add(action)
            } else {
                if (current.isNotEmpty()) groups.add(current.toList())
                current.clear()
                current.add(action)
            }
        }
        if (current.isNotEmpty()) groups.add(current.toList())
        return groups
    }

    /** True when [startAt]..[endAt] intersects any existing planned leg for [plate]. */
    fun overlaps(existing: List<PlannedAction>, plate: String, startAt: Long, endAt: Long): Boolean {
        val norm = normalizePlate(plate)
        return existing.any {
            it.plate == norm &&
                startAt < parseTimestamp(it.timeEnd) &&
                endAt > parseTimestamp(it.timeStart)
        }
    }

    /**
     * Collapse the server's same-day legs into absolute-time sessions, ready to
     * be persisted and scheduled.
     */
    fun mergeToSessions(actions: List<PlannedAction>): List<PlannedSession> =
        mergeGroups(actions).mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val start = parseTimestamp(first.timeStart)
            val end = parseTimestamp(group.last().timeEnd)
            if (start <= 0L || end <= start) null else PlannedSession(first.plate, start, end)
        }
}

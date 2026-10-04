package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPruneTest {

    private val now = 1_000_000L
    private val plate = "AB123C"

    private fun session(startOffset: Long, endOffset: Long, p: String = plate) =
        PlannedSession(p, now + startOffset, now + endOffset)

    @Test
    fun futureServerSessionIsKept() {
        val merged = listOf(session(60_000, 120_000))
        assertEquals(merged, SessionPrune.valid(emptyList(), merged, emptyMap(), now))
    }

    @Test
    fun endedServerSessionIsDropped() {
        val merged = listOf(session(-120_000, -60_000))
        assertEquals(emptyList<PlannedSession>(), SessionPrune.valid(emptyList(), merged, emptyMap(), now))
    }

    @Test
    fun inProgressSessionIsClampedToActiveActionEnd() {
        val previous = listOf(session(-60_000, 600_000))
        val result = SessionPrune.valid(previous, emptyList(), mapOf(plate to now + 60_000), now)
        assertEquals(listOf(session(-60_000, 60_000)), result)
    }

    @Test
    fun inProgressSessionWithoutActiveActionIsDropped() {
        val stale = session(-60_000, 60_000)
        assertEquals(emptyList<PlannedSession>(), SessionPrune.valid(listOf(stale), emptyList(), emptyMap(), now))
    }

    @Test
    fun canceledFutureLegIsNotRevived() {
        // A multi-day plan whose remaining scheduled legs were canceled: the
        // running leg stays, but the old extended end must not come back.
        val previous = listOf(session(-60_000, 600_000))
        val result = SessionPrune.valid(previous, emptyList(), mapOf(plate to now + 60_000), now)
        assertEquals(listOf(session(-60_000, 60_000)), result)
    }

    @Test
    fun runningLegMergesWithLaterScheduledLeg() {
        val previous = listOf(session(-60_000, 60_000))
        val laterLeg = session(61_000, 600_000)
        val result = SessionPrune.valid(previous, listOf(laterLeg), mapOf(plate to now + 60_000), now)
        assertEquals(listOf(session(-60_000, 600_000)), result)
    }

    @Test
    fun endedCarryOverIsDroppedEvenWhenActive() {
        val ended = session(-120_000, -1)
        assertEquals(
            emptyList<PlannedSession>(),
            SessionPrune.valid(listOf(ended), emptyList(), mapOf(plate to now + 60_000), now),
        )
    }

    @Test
    fun serverSessionAndRunningLegAreDeduplicated() {
        val running = session(-60_000, 60_000)
        val result = SessionPrune.valid(listOf(running), listOf(running), mapOf(plate to now + 60_000), now)
        assertEquals(listOf(running), result)
    }

    @Test
    fun differentPlatesAreNotMerged() {
        val a = session(-60_000, 60_000, "AA111A")
        val b = session(61_000, 120_000, "BB222B")
        val result = SessionPrune.valid(emptyList(), listOf(a, b), emptyMap(), now)
        assertEquals(listOf(a, b), result)
    }

    @Test
    fun emptyInputsProduceEmptyStore() {
        assertEquals(
            emptyList<PlannedSession>(),
            SessionPrune.valid(emptyList(), emptyList(), mapOf(plate to now), now),
        )
    }
}

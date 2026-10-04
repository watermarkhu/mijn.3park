package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPruneTest {

    private val now = 1_000_000L
    private val active = setOf("AB123C")

    private fun session(startOffset: Long, endOffset: Long, plate: String = "AB123C") =
        PlannedSession(plate, now + startOffset, now + endOffset)

    @Test
    fun futureServerSessionIsKept() {
        val merged = listOf(session(60_000, 120_000))
        assertEquals(merged, SessionPrune.valid(emptyList(), merged, emptySet(), now))
    }

    @Test
    fun endedServerSessionIsDropped() {
        val merged = listOf(session(-120_000, -60_000))
        assertEquals(emptyList<PlannedSession>(), SessionPrune.valid(emptyList(), merged, emptySet(), now))
    }

    @Test
    fun inProgressSessionWithActivePlateIsCarriedOver() {
        val running = session(-60_000, 60_000)
        assertEquals(listOf(running), SessionPrune.valid(listOf(running), emptyList(), active, now))
    }

    @Test
    fun inProgressSessionWithoutActivePlateIsDropped() {
        // Cancelled or otherwise gone from the server: no SCHEDULED row and no
        // active action, so the stale entry must not linger.
        val stale = session(-60_000, 60_000)
        assertEquals(emptyList<PlannedSession>(), SessionPrune.valid(listOf(stale), emptyList(), emptySet(), now))
    }

    @Test
    fun endedCarryOverIsDroppedEvenWhenPlateActive() {
        val ended = session(-120_000, -1)
        assertEquals(emptyList<PlannedSession>(), SessionPrune.valid(listOf(ended), emptyList(), active, now))
    }

    @Test
    fun serverSessionAndActiveCarryOverAreDeduplicated() {
        val running = session(-60_000, 60_000)
        val result = SessionPrune.valid(listOf(running), listOf(running), active, now)
        assertEquals(listOf(running), result)
    }

    @Test
    fun emptyInputsProduceEmptyStore() {
        assertEquals(emptyList<PlannedSession>(), SessionPrune.valid(emptyList(), emptyList(), active, now))
    }
}

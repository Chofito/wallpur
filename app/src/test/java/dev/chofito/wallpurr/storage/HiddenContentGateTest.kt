package dev.chofito.wallpurr.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenContentGateTest {
    private var clock = 0L
    private val gate = HiddenContentGate(now = { clock })

    @Test
    fun startsHidden() {
        assertFalse(gate.revealed.value)
    }

    @Test
    fun onLeaveHidesWhenBackgrounded() {
        gate.policy = HiddenAutoLock.OnLeave
        gate.reveal()
        gate.onAppBackgrounded()
        assertFalse(gate.revealed.value)
    }

    @Test
    fun manualSurvivesBackground() {
        gate.policy = HiddenAutoLock.Manual
        gate.reveal()
        gate.onAppBackgrounded()
        clock += 24 * 60 * 60_000L
        gate.onAppForegrounded()
        assertTrue(gate.revealed.value)
        gate.hide()
        assertFalse(gate.revealed.value)
    }

    @Test
    fun afterMinutesHidesOnlyWhenTheDelayElapsed() {
        gate.policy = HiddenAutoLock.AfterMinutes(5)
        gate.reveal()
        gate.onAppBackgrounded()
        clock += 4 * 60_000L
        gate.onAppForegrounded()
        assertTrue(gate.revealed.value)

        gate.onAppBackgrounded()
        clock += 5 * 60_000L
        gate.onAppForegrounded()
        assertFalse(gate.revealed.value)
    }

    @Test
    fun foregroundWithoutBackgroundKeepsState() {
        gate.policy = HiddenAutoLock.AfterMinutes(1)
        gate.reveal()
        clock += 10 * 60_000L
        gate.onAppForegrounded()
        assertTrue(gate.revealed.value)
    }

    @Test
    fun policyRoundTripsAndFallsBackToOnLeave() {
        listOf(HiddenAutoLock.OnLeave, HiddenAutoLock.Manual, HiddenAutoLock.AfterMinutes(15))
            .forEach { assertEquals(it, HiddenAutoLock.decode(it.encode())) }
        assertEquals(HiddenAutoLock.OnLeave, HiddenAutoLock.decode(null))
        assertEquals(HiddenAutoLock.OnLeave, HiddenAutoLock.decode("AFTER:0"))
        assertEquals(HiddenAutoLock.OnLeave, HiddenAutoLock.decode("garbage"))
    }
}

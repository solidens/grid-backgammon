package com.gridbackgammon.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingTest {

    @Test
    fun `a throw starts fast, only ever slows, and takes exactly its duration`() {
        val gaps = (1..Timing.TUMBLES).map(Timing::tumbleGap)

        assertEquals(Timing.TUMBLE_MS.toLong(), gaps.sum())
        assertTrue(gaps.toString(), gaps.zipWithNext().all { (a, b) -> b >= a })
        // The first faces are a blur; the last one is slow enough to read.
        assertTrue(gaps.first() < 40)
        assertTrue(gaps.last() > 150)
    }
}

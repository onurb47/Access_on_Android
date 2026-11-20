package com.bdfutsal.campro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MatchSessionTest {
    @Test
    fun linkRoundTrips() {
        val s = MatchSession("Uni vs Coventry & friends", "AB3CD", 1_795_000_000_000)
        assertEquals(s, MatchSession.parse(s.toLink()))
    }

    @Test
    fun parsesLinkInsideShareMessage() {
        val s = MatchSession("Match", "XYZ12", 1_795_000_000_000)
        val msg = "BD Cam Pro match\nOpen: ${s.toLink()}\nStarts 15:00"
        assertEquals(s, MatchSession.parse(msg))
    }

    @Test
    fun createRoundsStartUpToWholeSecond() {
        val s = MatchSession.create("  ", 120, now = 1_000_500, random = Random(1))
        assertEquals(1_121_000, s.startEpochMs)
        assertEquals(MatchSession.DEFAULT_NAME, s.name)
        assertEquals(5, s.code.length)
        assertTrue(s.startEpochMs % 1000 == 0L)
    }

    @Test
    fun rejectsBadLinks() {
        assertNull(MatchSession.parse("hello"))
        assertNull(MatchSession.parse("bdfutsalcam://match?c=AB3CD"))
        assertNull(MatchSession.parse("bdfutsalcam://match?c=a!&t=5"))
        assertNull(MatchSession.parse("bdfutsalcam://match?c=AB3CD&t=-4"))
    }
}

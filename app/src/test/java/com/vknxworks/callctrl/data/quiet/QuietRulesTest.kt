package com.vknxworks.callctrl.data.quiet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietRulesTest {
    private val night = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60, days = setOf(1, 2, 3, 4, 5))

    @Test fun offMeansNever() {
        assertFalse(QuietRules.active(night.copy(enabled = false), 1, 23 * 60))
        assertFalse(QuietRules.active(night.copy(days = emptySet()), 1, 23 * 60))
    }

    @Test fun aNightBelongsToTheDayItStartsOn() {
        // Monday 23:00 and Tuesday 06:59 are in Monday's night.
        assertTrue(QuietRules.active(night, 1, 23 * 60))
        assertTrue(QuietRules.active(night, 2, 6 * 60 + 59))
        assertFalse(QuietRules.active(night, 2, 7 * 60))
        assertFalse(QuietRules.active(night, 1, 21 * 60 + 59))
        // Friday's night runs into Saturday morning; Saturday's own night isn't chosen.
        assertTrue(QuietRules.active(night, 6, 3 * 60))
        assertFalse(QuietRules.active(night, 6, 23 * 60))
        // Sunday night isn't chosen, so Monday morning is not quiet.
        assertFalse(QuietRules.active(night, 1, 3 * 60))
        assertFalse(QuietRules.active(night, 7, 23 * 60))
    }

    @Test fun aPeriodWithinOneDay() {
        val nap = QuietHours(enabled = true, startMinute = 13 * 60, endMinute = 14 * 60, days = setOf(7))
        assertTrue(QuietRules.active(nap, 7, 13 * 60))
        assertTrue(QuietRules.active(nap, 7, 13 * 60 + 59))
        assertFalse(QuietRules.active(nap, 7, 14 * 60))
        assertFalse(QuietRules.active(nap, 6, 13 * 60 + 30))
        assertFalse(QuietRules.active(nap, 1, 13 * 60 + 30))
    }

    @Test fun sameStartAndEndIsTheWholeDay() {
        val all = QuietHours(enabled = true, startMinute = 8 * 60, endMinute = 8 * 60, days = setOf(3))
        assertTrue(QuietRules.active(all, 3, 8 * 60))
        assertTrue(QuietRules.active(all, 3, 23 * 60 + 59))
        assertTrue(QuietRules.active(all, 4, 7 * 60 + 59))
        assertFalse(QuietRules.active(all, 4, 8 * 60))
        assertFalse(QuietRules.active(all, 3, 7 * 60))
    }

    @Test fun whoStillRings() {
        val q = night.copy(favouritesRing = true, contactsRing = false)
        assertTrue(QuietRules.silences(q, 1, 23 * 60, emergency = false, starred = false, contact = false))
        assertTrue(QuietRules.silences(q, 1, 23 * 60, emergency = false, starred = false, contact = true))
        assertFalse(QuietRules.silences(q, 1, 23 * 60, emergency = false, starred = true, contact = true))
        assertFalse(QuietRules.silences(q, 1, 23 * 60, emergency = true, starred = false, contact = false))
        assertFalse(QuietRules.silences(q.copy(contactsRing = true), 1, 23 * 60, emergency = false, starred = false, contact = true))
        assertTrue(QuietRules.silences(q.copy(favouritesRing = false), 1, 23 * 60, emergency = false, starred = true, contact = true))
        // Outside quiet time nothing is silenced.
        assertFalse(QuietRules.silences(q, 1, 12 * 60, emergency = false, starred = false, contact = false))
    }

    @Test fun clockAndCleaning() {
        assertEquals("22:00", QuietRules.clock(22 * 60))
        assertEquals("07:05", QuietRules.clock(7 * 60 + 5))
        assertEquals("00:00", QuietRules.clock(QuietRules.DAY))
        val odd = QuietHours(startMinute = -5, endMinute = 5000, days = setOf(0, 3, 9)).cleaned()
        assertEquals(0, odd.startMinute)
        assertEquals(QuietRules.DAY - 1, odd.endMinute)
        assertEquals(setOf(3), odd.days)
    }
}

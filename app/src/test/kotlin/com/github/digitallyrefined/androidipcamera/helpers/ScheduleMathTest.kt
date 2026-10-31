package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleMathTest {
    private val tz: TimeZone = TimeZone.getTimeZone("America/Cancun") // UTC-5, no DST
    private val allDays = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()

    /** 2026-11-02 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
    }.timeInMillis

    private fun rule(start: Int, end: Int, days: Set<Int> = allDays, enabled: Boolean = true) =
        ScheduleRule("r$start-$end", enabled, days, start, end)

    @Test
    fun `same-day window is active only between start and end`() {
        val r = listOf(rule(8 * 60, 10 * 60))
        assertFalse(ScheduleMath.shouldRecord(r, at(2, 7, 59), tz))
        assertTrue(ScheduleMath.shouldRecord(r, at(2, 8, 0), tz))
        assertTrue(ScheduleMath.shouldRecord(r, at(2, 9, 59), tz))
        assertFalse(ScheduleMath.shouldRecord(r, at(2, 10, 0), tz))
    }

    @Test
    fun `overnight window continues after midnight`() {
        val r = listOf(rule(22 * 60, 6 * 60, setOf(Calendar.MONDAY)))
        assertTrue(ScheduleMath.shouldRecord(r, at(2, 23, 0), tz))   // Monday night
        assertTrue(ScheduleMath.shouldRecord(r, at(3, 5, 30), tz))   // Tuesday early morning
        assertFalse(ScheduleMath.shouldRecord(r, at(3, 22, 30), tz)) // Tuesday night: not a start day
    }

    @Test
    fun `next change is the next start, then the end`() {
        val r = listOf(rule(22 * 60, 6 * 60))
        assertEquals(at(2, 22), ScheduleMath.nextChange(r, at(2, 12), tz))
        assertEquals(at(3, 6), ScheduleMath.nextChange(r, at(2, 23), tz))
    }

    @Test
    fun `back to back windows merge into one stop`() {
        val r = listOf(rule(20 * 60, 22 * 60), rule(22 * 60, 23 * 60))
        assertEquals(at(2, 23), ScheduleMath.nextChange(r, at(2, 21), tz))
    }

    @Test
    fun `day filter skips other days`() {
        val r = listOf(rule(9 * 60, 10 * 60, setOf(Calendar.SATURDAY)))
        // Monday noon -> next is Saturday 2026-11-07 09:00
        assertEquals(at(7, 9), ScheduleMath.nextChange(r, at(2, 12), tz))
    }

    @Test
    fun `disabled or empty rules never record`() {
        assertNull(ScheduleMath.nextChange(emptyList(), at(2, 12), tz))
        val off = listOf(rule(0, 23 * 60, enabled = false))
        assertFalse(ScheduleMath.shouldRecord(off, at(2, 12), tz))
        assertNull(ScheduleMath.nextChange(off, at(2, 12), tz))
    }

    @Test
    fun `start equal to end is invalid`() {
        assertFalse(rule(600, 600).isValid())
        assertFalse(ScheduleRule("x", true, emptySet(), 1, 2).isValid())
    }
}

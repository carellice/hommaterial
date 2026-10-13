package app.hommaterial.data

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTest {
    private fun clock() = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))

    private fun at(day: Int, hour: Int, minute: Int): Long = clock().apply {
        clear()
        set(2026, Calendar.OCTOBER, day, hour, minute)
    }.timeInMillis

    // 14 October 2026 is a Wednesday.
    @Test
    fun laterTodayIsToday() {
        assertEquals(at(14, 16, 0), nextTime(16, 0, emptySet(), at(14, 9, 30), clock()))
    }

    @Test
    fun aTimeAlreadyPassedIsTomorrow() {
        assertEquals(at(15, 8, 0), nextTime(8, 0, emptySet(), at(14, 9, 30), clock()))
        assertEquals(at(15, 9, 30), nextTime(9, 30, emptySet(), at(14, 9, 30), clock()))
    }

    @Test
    fun onlyTheChosenDaysCount() {
        val weekend = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        assertEquals(at(17, 16, 0), nextTime(16, 0, weekend, at(14, 9, 30), clock()))
        assertEquals(at(18, 16, 0), nextTime(16, 0, weekend, at(17, 16, 0), clock()))
        // The same day of next week, when today's time has passed.
        assertEquals(at(21, 8, 0), nextTime(8, 0, setOf(Calendar.WEDNESDAY), at(14, 9, 30), clock()))
    }
}

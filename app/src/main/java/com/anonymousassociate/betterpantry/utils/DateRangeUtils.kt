package com.anonymousassociate.betterpantry.utils

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

object DateRangeUtils {
    fun getCoworkerQueryRange(): Pair<String, String> {
        val today = LocalDate.now()
        val calendarStart = if (today.dayOfWeek == DayOfWeek.WEDNESDAY) {
            today
        } else {
            today.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY))
        }
        
        // Start from previous Wednesday (the start of the current calendar period) at 00:00:00
        val startDateTime = calendarStart.atStartOfDay()
        
        // End at the end of the 5th period (35 days from the start) at 23:59:59
        // This covers the 4 periods shown on the calendar plus 7 days extra to be safe and cover future changes/shifts
        val endDateTime = calendarStart.plusDays(35).atTime(23, 59, 59)
        
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        return Pair(startDateTime.format(formatter), endDateTime.format(formatter))
    }
}

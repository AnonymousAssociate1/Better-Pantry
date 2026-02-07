package com.anonymousassociate.betterpantry.models

data class AvailabilityResponse(
    val approved: AvailabilityStatus?,
    val pending: AvailabilityStatus?,
    val denied: List<AvailabilityStatus>?
)

data class AvailabilityStatus(
    val availabilityId: Long?,
    val effectiveFrom: String?,
    val effectiveTo: String?,
    val status: String?,
    val availableTime: Map<String, List<TimeSlot>>?
)

data class TimeSlot(
    val day: String?,
    val allDay: Boolean?,
    val start: String?,
    val end: String?
)

data class MaxHoursResponse(
    val pending: MaxHoursStatus?,
    val approved: MaxHoursStatus?
)

data class MaxHoursStatus(
    val maxHoursDaily: Int?,
    val maxHoursWeekly: Int?,
    val status: String?,
    val paneraId: String?
)

package com.anonymousassociate.betterpantry.models

data class TimeOffRequest(
    val approvedAt: String?,
    val approvedBy: String?,
    val associateComments: String?,
    val endTime: String?,
    val entryId: String?,
    val requestId: String?,
    val requestedAt: String?,
    val startTime: String?,
    val status: String?,
    val timeOffDate: String?
)
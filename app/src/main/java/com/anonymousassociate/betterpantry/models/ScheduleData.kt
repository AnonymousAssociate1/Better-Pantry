package com.anonymousassociate.betterpantry.models

import com.google.gson.annotations.SerializedName

data class ScheduleData(
    val currentShifts: List<Shift>?,
    val track: List<TrackItem>?,
    val cafeList: List<CafeInfo>?,
    val employeeInfo: List<EmployeeInfo>?
)

data class Shift(
    val businessDate: String?,
    val startDateTime: String?,
    val endDateTime: String?,
    val workstationId: String?,
    val workstationName: String?,
    val workstationGroupDisplayName: String?,
    val cafeNumber: String?,
    val companyCode: String?,
    val employeeId: String?,
    val shiftId: String?,
    val workstationCode: String?
)

data class TrackItem(
    val type: String?,
    val primaryShiftRequest: PrimaryShiftRequest?,
    val relatedShiftRequests: List<RelatedShiftRequest>?
)

data class PrimaryShiftRequest(
    @SerializedName("requestId") val requestId: String?,
    val shift: Shift?,
    val requesterId: String?,
    val requestedAt: String?,
    val managerNotes: String?,
    val state: String?
)

data class RelatedShiftRequest(
    @SerializedName("requestId") val requestId: String?,
    val requesterId: String?,
    val requestedAt: String?,
    val state: String?
)

data class CafeInfo(
    val departmentName: String?,
    val phoneNumber: String?,
    val address: Address?
)

data class Address(
    val addressLine: String?,
    val city: String?,
    val state: String?,
    val zipCode: String?
)

data class EmployeeInfo(
    val employeeId: String?,
    val firstName: String?,
    val lastName: String?
)

data class NotificationData(
    val notificationId: String?,
    val workFlowId: String?,
    val subject: String?,
    val message: String?,
    val createDateTime: String?,
    val read: Boolean?,
    val deleted: Boolean?,
    val appData: String?
)

data class NotificationResponse(
    val content: List<NotificationData>?
)

// Team Members API Response
data class TeamMembersResponse(
    val data: List<TeamMember>?
)

data class TeamMember(
    val associate: Associate?,
    val shifts: List<TeamShift>?
)

data class Associate(
    val employeeId: String?,
    val firstName: String?,
    val lastName: String?,
    val preferredName: String?
)

data class TeamShift(
    val startDateTime: String?,
    val endDateTime: String?,
    val workstationId: String?,
    val workstationName: String?,
    val workstationGroupDisplayName: String?,
    val workstationCode: String?,
    val shiftId: Long?,
    val cafeNumber: String?,
    val companyCode: String?,
    val businessDate: String?,
    val employeeId: String?,
    val managerNotes: String? = null,
    val requesterName: String? = null,
    val requestedAt: String? = null,
    val requestId: String? = null,
    val myPickupRequestId: String? = null,
    val pickupRequests: List<String>? = null
)

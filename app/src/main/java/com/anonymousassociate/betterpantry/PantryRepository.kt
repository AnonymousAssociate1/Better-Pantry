package com.anonymousassociate.betterpantry

import android.content.Context
import com.anonymousassociate.betterpantry.models.NotificationResponse
import com.anonymousassociate.betterpantry.models.ScheduleData
import com.anonymousassociate.betterpantry.models.TeamMember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PantryRepository(private val apiService: PantryApiService, private val scheduleCache: ScheduleCache) {

    suspend fun getSchedule(forceRefresh: Boolean = false): ScheduleData? {
        if (!forceRefresh && !scheduleCache.isScheduleStale()) {
            val cached = scheduleCache.getSchedule()
            if (cached != null) return filterSchedule(cached)
        }
        val data = apiService.getSchedule(30)
        if (data != null) {
            scheduleCache.saveSchedule(data)
        }
        return data?.let { filterSchedule(it) }
    }

    suspend fun getNotificationCount(): Int {
        // Always fetch count fresh as it's a lightweight call and needs to be accurate
        return apiService.getNotificationCount()
    }

    suspend fun getTeamMembers(
        cafeNo: String,
        companyCode: String,
        startDateTime: String,
        endDateTime: String,
        forceRefresh: Boolean = false
    ): List<TeamMember>? {
        if (!forceRefresh && !scheduleCache.isTeamScheduleStale()) {
            val cached = scheduleCache.getTeamSchedule()
            if (cached != null && cached.isNotEmpty()) {
                 return filterTeamMembers(cached)
            }
        }
        val data = apiService.getTeamMembers(cafeNo, companyCode, startDateTime, endDateTime)
        if (data != null) {
            scheduleCache.mergeTeamSchedule(data)
        }
        // Fetch from cache to ensure merge result is returned (and filtered)
        // Or just filter 'data' if it was a fresh fetch? 
        // mergeTeamSchedule returns void. It updates cache. 
        // We should return the merged result if we want consistency, or just 'data'.
        // But 'data' is what we just fetched.
        // If we want "Shared data", we should return the cache state.
        val fullData = if (data != null) scheduleCache.getTeamSchedule() else data ?: scheduleCache.getTeamSchedule()
        return fullData?.let { filterTeamMembers(it) }
    }

    // ... (keep existing methods)

    private fun filterSchedule(data: ScheduleData): ScheduleData {
        val todayStart = java.time.LocalDate.now().atStartOfDay()
        
        val filteredShifts = data.currentShifts?.filter { 
            try {
                val end = java.time.LocalDateTime.parse(it.endDateTime)
                !end.isBefore(todayStart)
            } catch (e: Exception) { true }
        }
        
        val filteredTrack = data.track?.filter { 
            try {
                val end = java.time.LocalDateTime.parse(it.primaryShiftRequest?.shift?.endDateTime)
                !end.isBefore(todayStart)
            } catch (e: Exception) { true }
        }
        
        return data.copy(currentShifts = filteredShifts, track = filteredTrack)
    }

    private fun filterTeamMembers(members: List<TeamMember>): List<TeamMember> {
        val todayStart = java.time.LocalDate.now().atStartOfDay()
        
        return members.map { member ->
            val filteredShifts = member.shifts?.filter {
                try {
                    val end = java.time.LocalDateTime.parse(it.endDateTime)
                    !end.isBefore(todayStart)
                } catch (e: Exception) { true }
            }
            member.copy(shifts = filteredShifts)
        }.filter { 
            // Optional: Filter out members with no shifts? 
            // The user says "When showing any data like schedules...".
            // If a member has no shifts left today/future, maybe hide them?
            // "scheduleFragment they should show as shifts belonging to whoever picked them up last"
            // If they have 0 shifts in the future, they won't show up in the schedule list anyway (empty rows usually hidden or empty).
            // Let's keep the member object but with empty shifts, UI handles display.
            true 
        }
    }

    suspend fun getNotifications(forceRefresh: Boolean = false, page: Int = 0, size: Int = 100): NotificationResponse? {
        // Notifications are tricky to cache fully because of pages, but we can cache the "latest" list.
        // The user asked for specific update intervals.
        // We will fetch fresh if forced or needed.
        // The existing cache is just a list of NotificationData. 
        // We might want to just return API response here and let caller handle UI.
        // But for background checks, we need to compare.
        return apiService.getNotifications(page, size)
    }

    suspend fun checkAndSendNewNotifications(context: Context) {
        // Delegate to the static worker method which handles the logic
        NotificationWorker.checkAndSendNewNotifications(context)
    }

    // Actions that trigger updates

    suspend fun acceptShiftPickup(payload: String): Boolean {
        val success = apiService.acceptShiftPickup(payload)
        if (success) {
            getSchedule(forceRefresh = true)
        }
        return success
    }

    suspend fun declineShiftPickup(payload: String): Boolean {
        val success = apiService.declineShiftPickup(payload)
        if (success) {
            getSchedule(forceRefresh = true)
        }
        return success
    }

    suspend fun postShift(payload: String): Boolean {
        val success = apiService.postShift(payload)
        if (success) {
            getSchedule(forceRefresh = true)
        }
        return success
    }

    suspend fun cancelPostShift(payload: String): Int {
        val code = apiService.cancelPostShift(payload)
        if (code in 200..299) {
            getSchedule(forceRefresh = true)
        }
        return code
    }

    suspend fun markNotificationAsRead(notificationId: String): Boolean {
        val success = apiService.markNotificationAsRead(notificationId)
        if (success) {
            // Optionally refresh notifications? User said "relevant data should be updated"
            // But getting full notification list might be heavy. 
            // However, the user explicitly asked for "getNotifications" in the 5 min / 20 sec loops.
            // So we can assume the next loop will catch it, or we force it here if UI needs it.
            // Let's leave it to the loop or manual refresh for the *list*, 
            // but the action itself is done.
        }
        return success
    }

    suspend fun deleteNotification(notificationId: String): Boolean {
        return apiService.deleteNotification(notificationId)
    }

    suspend fun undeleteNotification(notificationId: String): Boolean {
        return apiService.undeleteNotification(notificationId)
    }
    
    suspend fun getLatestRelease() = apiService.getLatestRelease()

    fun sendTestNotification(context: Context, notification: com.anonymousassociate.betterpantry.models.NotificationData) {
        // Run in IO scope
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
             NotificationWorker.sendNotification(context, notification, apiService)
        }
    }
}

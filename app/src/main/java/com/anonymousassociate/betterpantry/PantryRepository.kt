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
            if (cached != null) return cached
        }
        val data = apiService.getSchedule(30)
        if (data != null) {
            scheduleCache.saveSchedule(data)
        }
        return data
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
                 // Optimization: We could check if cached data covers the requested range
                 // For now, per user instruction "one shared cache", we return what we have
                 // unless it's stale or forced.
                 return cached
            }
        }
        val data = apiService.getTeamMembers(cafeNo, companyCode, startDateTime, endDateTime)
        if (data != null) {
            // Merge or Replace? 
            // If we are fetching 30 days, we should probably replace or merge.
            // scheduleCache.mergeTeamSchedule(data) // The cache has a merge method
            // But mergeTeamSchedule distincts by ID.
            // Let's use saveTeamSchedule for now to be "consistent" with "last update time"
            // Actually, mergeTeamSchedule calls saveTeamSchedule internally.
            scheduleCache.mergeTeamSchedule(data)
        }
        return data ?: scheduleCache.getTeamSchedule()
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

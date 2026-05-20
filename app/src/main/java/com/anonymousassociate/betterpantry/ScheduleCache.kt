package com.anonymousassociate.betterpantry

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.anonymousassociate.betterpantry.models.NotificationData
import com.anonymousassociate.betterpantry.models.ScheduleData
import com.anonymousassociate.betterpantry.models.TeamMember

class ScheduleCache(context: Context) {

    companion object {
        @Volatile
        private var cachedSchedule: ScheduleData? = null
        @Volatile
        private var cachedScheduleJson: String? = null

        @Volatile
        private var cachedTeamSchedule: List<TeamMember>? = null
        @Volatile
        private var cachedTeamScheduleJson: String? = null

        fun clearCache() {
            cachedSchedule = null
            cachedScheduleJson = null
            cachedTeamSchedule = null
            cachedTeamScheduleJson = null
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("pantry_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun saveSchedule(schedule: ScheduleData) {
        val json = gson.toJson(schedule)
        cachedSchedule = schedule
        cachedScheduleJson = json
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("cached_schedule", json)
            .putLong("schedule_timestamp", now)
            .putLong("last_update_time", now)
            .apply()
    }

    @Synchronized
    fun getSchedule(): ScheduleData? {
        val cachedVal = cachedSchedule
        if (cachedVal != null) return cachedVal
        val json = prefs.getString("cached_schedule", null) ?: return null
        return try {
            val parsed = gson.fromJson(json, ScheduleData::class.java)
            cachedSchedule = parsed
            cachedScheduleJson = json
            parsed
        } catch (e: Exception) {
            null
        }
    }

    fun isScheduleStale(): Boolean {
        // Use specific timestamp for staleness
        val lastUpdate = prefs.getLong("schedule_timestamp", 0)
        if (lastUpdate == 0L) {
             // Fallback for migration: check old key if new one missing?
             // Or just force refresh. Force refresh is safer.
             // But checking old key prevents one unnecessary refresh on update.
             val oldKey = prefs.getLong("last_update_time", 0)
             if (oldKey != 0L && !prefs.contains("schedule_timestamp")) {
                 // We have an old timestamp and haven't migrated. Assume it was schedule.
                 val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
                 return oldKey < fiveMinutesAgo
             }
             return true
        }
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return lastUpdate < fiveMinutesAgo
    }

    fun hasCachedSchedule(): Boolean {
        return prefs.contains("cached_schedule")
    }

    fun getLastUpdateText(): String {
        val lastUpdate = getLastUpdateTime()
        if (lastUpdate == 0L) return "Never updated"
        
        val now = System.currentTimeMillis()
        val diffMs = now - lastUpdate
        val diffMinutes = diffMs / 1000 / 60

        return when {
            diffMinutes < 1 -> "Updated now"
            diffMinutes == 1L -> "Updated 1 minute ago"
            diffMinutes < 60 -> "Updated $diffMinutes minutes ago"
            else -> {
                val hours = diffMinutes / 60
                if (hours == 1L) "Updated 1 hour ago" else "Updated $hours hours ago"
            }
        }
    }

    fun getLastUpdateTime(): Long {
        return prefs.getLong("last_update_time", 0)
    }

    fun saveNotifications(notifications: List<NotificationData>) {
        val json = gson.toJson(notifications)
        prefs.edit().putString("cached_notifications", json).apply()
    }

    fun getCachedNotifications(): List<NotificationData>? {
        val json = prefs.getString("cached_notifications", null) ?: return null
        val type = object : TypeToken<List<NotificationData>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun saveTeamMembers(shiftId: String, members: List<TeamMember>) {
        val json = gson.toJson(members)
        prefs.edit().putString("team_members_$shiftId", json).apply()
    }

    fun getTeamMembers(shiftId: String): List<TeamMember>? {
        val json = prefs.getString("team_members_$shiftId", null) ?: return null
        val type = object : TypeToken<List<TeamMember>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun saveTeamSchedule(members: List<TeamMember>) {
        val json = gson.toJson(members)
        cachedTeamSchedule = members
        cachedTeamScheduleJson = json
        prefs.edit()
            .putString("team_schedule_full", json)
            .putLong("team_schedule_last_update_time", System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun mergeTeamSchedule(newMembers: List<TeamMember>) {
        val current = getTeamSchedule() ?: emptyList()
        val currentMap = current.associateBy { it.associate?.employeeId }.toMutableMap()
        
        // Build lookups for new members' shifts to avoid O(N * S * M * K) complexity
        val shiftIdToNewOwner = mutableMapOf<Long, String>()
        val shiftKeyToNewOwner = mutableMapOf<String, String>()
        
        newMembers.forEach { newMember ->
            val empId = newMember.associate?.employeeId ?: return@forEach
            newMember.shifts?.forEach { shift ->
                shift.shiftId?.let { id ->
                    shiftIdToNewOwner[id] = empId
                }
                val key = "${shift.startDateTime}-${shift.workstationId}"
                shiftKeyToNewOwner[key] = empId
            }
        }
        
        // Iterate over current cache map once to filter shifts belonging to someone else now
        val updatedMap = currentMap.mapValues { (empId, member) ->
            val shifts = member.shifts
            if (shifts.isNullOrEmpty()) {
                member
            } else {
                val filteredShifts = shifts.filter { s ->
                    val sId = s.shiftId
                    val sKey = "${s.startDateTime}-${s.workstationId}"
                    val newOwnerId = sId?.let { shiftIdToNewOwner[it] } ?: shiftKeyToNewOwner[sKey]
                    newOwnerId == null || newOwnerId == empId
                }
                if (filteredShifts.size == shifts.size) {
                    member
                } else {
                    member.copy(shifts = filteredShifts)
                }
            }
        }.toMutableMap()

        // Combine new shifts and details into the updatedMap
        newMembers.forEach { newMember ->
            val empId = newMember.associate?.employeeId ?: return@forEach
            val existingMember = updatedMap[empId]
            if (existingMember != null) {
                val existingShifts = existingMember.shifts ?: emptyList()
                val newShifts = newMember.shifts ?: emptyList()
                
                val combinedShifts = (existingShifts + newShifts).distinctBy { 
                    it.shiftId ?: "${it.startDateTime}-${it.workstationId}" 
                }
                
                val existingAssoc = existingMember.associate
                val newAssoc = newMember.associate
                val combinedLoaned = (existingAssoc?.loanedCafeList.orEmpty() + newAssoc?.loanedCafeList.orEmpty()).distinct()
                val mergedAssoc = existingAssoc?.copy(
                    cafeNumber = newAssoc?.cafeNumber ?: existingAssoc.cafeNumber,
                    loanedCafeList = if (combinedLoaned.isNotEmpty()) combinedLoaned else null,
                    preferredName = newAssoc?.preferredName ?: existingAssoc.preferredName
                )
                
                updatedMap[empId] = existingMember.copy(associate = mergedAssoc, shifts = combinedShifts)
            } else {
                updatedMap[empId] = newMember
            }
        }
        
        saveTeamSchedule(updatedMap.values.toList())
    }

    @Synchronized
    fun getTeamSchedule(): List<TeamMember>? {
        val cachedVal = cachedTeamSchedule
        if (cachedVal != null) return cachedVal
        val json = prefs.getString("team_schedule_full", null) ?: return null
        val type = object : TypeToken<List<TeamMember>>() {}.type
        return try {
            val parsed = gson.fromJson<List<TeamMember>>(json, type)
            cachedTeamSchedule = parsed
            cachedTeamScheduleJson = json
            parsed
        } catch (e: Exception) {
            null
        }
    }

    fun isTeamScheduleStale(cafeNo: String? = null): Boolean {
        val key = if (cafeNo != null) "team_schedule_last_update_time_$cafeNo" else "team_schedule_last_update_time"
        val lastUpdate = prefs.getLong(key, 0)
        if (lastUpdate == 0L) return true
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return lastUpdate < fiveMinutesAgo
    }

    fun setTeamScheduleUpdateTime(cafeNo: String, timestamp: Long) {
        prefs.edit()
            .putLong("team_schedule_last_update_time_$cafeNo", timestamp)
            .apply()
    }

    fun saveTeamRoster(members: List<TeamMember>) {
        saveTeamSchedule(members)
    }

    fun getTeamRoster(): List<TeamMember>? {
        return getTeamSchedule()
    }

    fun isTeamRosterStale(cafeNo: String? = null): Boolean {
        return isTeamScheduleStale(cafeNo)
    }

    fun getFavorites(): Set<String> {
        return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    fun toggleFavorite(employeeId: String) {
        val current = getFavorites().toMutableSet()
        if (current.contains(employeeId)) {
            current.remove(employeeId)
        } else {
            current.add(employeeId)
        }
        prefs.edit().putStringSet("favorites", current).apply()
    }

    fun clearFavorites() {
        prefs.edit().remove("favorites").apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun saveAvailability(response: com.anonymousassociate.betterpantry.models.AvailabilityResponse) {
        val json = gson.toJson(response)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("cached_availability", json)
            .putLong("availability_timestamp", now)
            .putLong("last_update_time", now)
            .apply()
    }

    fun getAvailability(): com.anonymousassociate.betterpantry.models.AvailabilityResponse? {
        val json = prefs.getString("cached_availability", null) ?: return null
        return try {
            gson.fromJson(json, com.anonymousassociate.betterpantry.models.AvailabilityResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun saveMaxHours(response: com.anonymousassociate.betterpantry.models.MaxHoursResponse) {
        val json = gson.toJson(response)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("cached_max_hours", json)
            .putLong("availability_timestamp", now) // Max hours is part of availability
            .putLong("last_update_time", now)
            .apply()
    }

    fun getMaxHours(): com.anonymousassociate.betterpantry.models.MaxHoursResponse? {
        val json = prefs.getString("cached_max_hours", null) ?: return null
        return try {
            gson.fromJson(json, com.anonymousassociate.betterpantry.models.MaxHoursResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun saveTimeOff(requests: List<com.anonymousassociate.betterpantry.models.TimeOffRequest>) {
        val json = gson.toJson(requests)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("cached_time_off", json)
            .putLong("availability_timestamp", now) // Time off is part of availability/requests
            .putLong("last_update_time", now)
            .apply()
    }

    fun getTimeOff(): List<com.anonymousassociate.betterpantry.models.TimeOffRequest>? {
        val json = prefs.getString("cached_time_off", null) ?: return null
        val type = object : TypeToken<List<com.anonymousassociate.betterpantry.models.TimeOffRequest>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun isAvailabilityStale(): Boolean {
        val lastUpdate = prefs.getLong("availability_timestamp", 0)
        if (lastUpdate == 0L) {
            // Fallback to old key if exists
             val oldKey = prefs.getLong("availability_last_update_time", 0)
             if (oldKey != 0L && !prefs.contains("availability_timestamp")) {
                 val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
                 return oldKey < fiveMinutesAgo
             }
            return true
        }
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return lastUpdate < fiveMinutesAgo
    }

    fun getAvailabilityLastUpdateText(): String {
        return getLastUpdateText()
    }
}

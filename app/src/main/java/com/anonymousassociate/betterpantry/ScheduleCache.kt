package com.anonymousassociate.betterpantry

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.anonymousassociate.betterpantry.models.NotificationData
import com.anonymousassociate.betterpantry.models.ScheduleData
import com.anonymousassociate.betterpantry.models.TeamMember

class ScheduleCache(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pantry_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveSchedule(schedule: ScheduleData) {
        val json = gson.toJson(schedule)
        prefs.edit()
            .putString("cached_schedule", json)
            .putLong("last_update_time", System.currentTimeMillis())
            .apply()
    }

    fun getSchedule(): ScheduleData? {
        val json = prefs.getString("cached_schedule", null) ?: return null
        return try {
            gson.fromJson(json, ScheduleData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun isScheduleStale(): Boolean {
        val lastUpdate = getLastUpdateTime()
        if (lastUpdate == 0L) return true
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

    fun saveTeamSchedule(members: List<TeamMember>) {
        val json = gson.toJson(members)
        prefs.edit()
            .putString("team_schedule_full", json)
            .putLong("team_schedule_last_update_time", System.currentTimeMillis())
            .apply()
    }

    fun getTeamSchedule(): List<TeamMember>? {
        val json = prefs.getString("team_schedule_full", null) ?: return null
        val type = object : TypeToken<List<TeamMember>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun isTeamScheduleStale(): Boolean {
        val lastUpdate = prefs.getLong("team_schedule_last_update_time", 0)
        if (lastUpdate == 0L) return true
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return lastUpdate < fiveMinutesAgo
    }

    fun saveTeamRoster(members: List<TeamMember>) {
        val json = gson.toJson(members)
        prefs.edit()
            .putString("team_roster_full", json)
            .putLong("team_roster_last_update_time", System.currentTimeMillis())
            .apply()
    }

    fun getTeamRoster(): List<TeamMember>? {
        val json = prefs.getString("team_roster_full", null) ?: return null
        val type = object : TypeToken<List<TeamMember>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun isTeamRosterStale(): Boolean {
        val lastUpdate = prefs.getLong("team_roster_last_update_time", 0)
        if (lastUpdate == 0L) return true
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return lastUpdate < fiveMinutesAgo
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

    fun clear() {
        prefs.edit().clear().apply()
    }
}

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
        prefs.edit().putString("team_schedule_full", json).apply()
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

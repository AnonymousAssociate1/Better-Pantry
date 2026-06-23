package com.anonymousassociate.betterpantry.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.google.gson.Gson

class WearScheduleCache(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wear_pantry_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Immutable
    data class NextShiftSyncData(
        val startDateTime: String?,
        val endDateTime: String?,
        val role: String?,
        val cafeNumber: String?,
        val managerText: String?,
        val shiftId: String?,
        val coworkers: List<CoworkerSyncInfo>,
        val lastUpdatedMs: Long,
        val formattedDay: String?,
        val formattedTime: String?,
        val formattedSyncedTime: String?
    )

    @Immutable
    data class CoworkerSyncInfo(
        val name: String,
        val workstation: String,
        val timeRange: String,
        val syncId: String,
        val endDateTime: String?
    )

    fun saveNextShiftData(data: NextShiftSyncData) {
        val json = gson.toJson(data)
        prefs.edit().putString("next_shift_data_json", json).apply()
    }

    fun getNextShiftData(): NextShiftSyncData? {
        val json = prefs.getString("next_shift_data_json", null) ?: return null
        return try {
            gson.fromJson(json, NextShiftSyncData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

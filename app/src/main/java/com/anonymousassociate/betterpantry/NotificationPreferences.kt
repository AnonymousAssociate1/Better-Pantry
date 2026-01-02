package com.anonymousassociate.betterpantry

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NotificationPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val KEY_SHIFT_PICKUPS = "pref_shift_pickups"
        const val KEY_MANAGER_CALLS = "pref_manager_calls"
        const val KEY_SCHEDULE_PUBLISHED = "pref_schedule_published"
        const val KEY_SHIFT_APPROVED = "pref_shift_approved"
        const val KEY_PAYCHECK = "pref_paycheck"
        const val KEY_OTHER = "pref_other"
        const val KEY_PAYCHECK_CONFIG = "pref_paycheck_config"
    }

    var shiftPickupsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIFT_PICKUPS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHIFT_PICKUPS, value).apply()

    var managerCallsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MANAGER_CALLS, true)
        set(value) = prefs.edit().putBoolean(KEY_MANAGER_CALLS, value).apply()

    var schedulePublishedEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHEDULE_PUBLISHED, true)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULE_PUBLISHED, value).apply()

    var shiftApprovedEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIFT_APPROVED, true)
        set(value) = prefs.edit().putBoolean(KEY_SHIFT_APPROVED, value).apply()

    var paycheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAYCHECK, false)
        set(value) = prefs.edit().putBoolean(KEY_PAYCHECK, value).apply()

    var otherEnabled: Boolean
        get() = prefs.getBoolean(KEY_OTHER, true)
        set(value) = prefs.edit().putBoolean(KEY_OTHER, value).apply()

    var paycheckConfig: PaycheckConfig?
        get() {
            val json = prefs.getString(KEY_PAYCHECK_CONFIG, null)
            return if (json != null) {
                try {
                    gson.fromJson(json, PaycheckConfig::class.java)
                } catch (e: Exception) { null }
            } else null
        }
        set(value) {
            val json = gson.toJson(value)
            prefs.edit().putString(KEY_PAYCHECK_CONFIG, json).apply()
        }
}

data class PaycheckConfig(
    val frequencyType: String, // WEEKLY, BIWEEKLY, SEMIMONTHLY_DATE, SEMIMONTHLY_DAY, MONTHLY_DATE, MONTHLY_DAY
    val startDate: String? = null, // ISO Date string for reference (e.g. 2024-01-01)
    val dayOfWeek: Int? = null, // 1=Monday, 7=Sunday
    val dayOfMonth1: Int? = null, // e.g., 1, 15
    val dayOfMonth2: Int? = null, // e.g., 15, 30
    val weekIndex1: Int? = null, // e.g., 1 (1st), 2 (2nd), 3 (3rd), 4 (4th), -1 (Last)
    val weekIndex2: Int? = null  // e.g., 3 (3rd)
)

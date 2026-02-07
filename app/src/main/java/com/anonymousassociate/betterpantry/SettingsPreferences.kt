package com.anonymousassociate.betterpantry

import android.content.Context
import android.content.SharedPreferences

class SettingsPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    var showAvailabilityOnCalendar: Boolean
        get() = prefs.getBoolean("show_availability_calendar", true)
        set(value) = prefs.edit().putBoolean("show_availability_calendar", value).apply()
}

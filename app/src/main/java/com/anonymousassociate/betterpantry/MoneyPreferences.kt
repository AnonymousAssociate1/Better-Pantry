package com.anonymousassociate.betterpantry

import android.content.Context
import android.content.SharedPreferences

class MoneyPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("money_prefs", Context.MODE_PRIVATE)

    var showMoney: Boolean
        get() = prefs.getBoolean("show_money", false)
        set(value) = prefs.edit().putBoolean("show_money", value).apply()

    var hourlyWage: Float
        get() = prefs.getFloat("hourly_wage", 0f)
        set(value) = prefs.edit().putFloat("hourly_wage", value).apply()
}

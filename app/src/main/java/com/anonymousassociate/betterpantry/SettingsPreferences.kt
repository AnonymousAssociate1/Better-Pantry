package com.anonymousassociate.betterpantry

import android.content.Context
import android.content.SharedPreferences
import com.anonymousassociate.betterpantry.models.CafeInfo

class SettingsPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    var showAvailabilityOnCalendar: Boolean
        get() = prefs.getBoolean("show_availability_calendar", true)
        set(value) = prefs.edit().putBoolean("show_availability_calendar", value).apply()

    var authFrequency: String
        get() = prefs.getString("auth_frequency", "15_MINUTES") ?: "15_MINUTES"
        set(value) = prefs.edit().putString("auth_frequency", value).apply()

    var combineShifts: Boolean
        get() = prefs.getBoolean("combine_shifts", true)
        set(value) = prefs.edit().putBoolean("combine_shifts", value).apply()

    private val nicknameFirstCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val nicknameLastCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val hideLastNameCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val customCafeNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cafeEnabledCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val cafeNotificationsEnabledCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val cafeDisplayNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        val all = prefs.all
        for ((key, value) in all) {
            if (key.startsWith("nickname_first_")) {
                val empId = key.substring("nickname_first_".length)
                (value as? String)?.let { nicknameFirstCache[empId] = it }
            } else if (key.startsWith("nickname_last_")) {
                val empId = key.substring("nickname_last_".length)
                (value as? String)?.let { nicknameLastCache[empId] = it }
            } else if (key.startsWith("hide_last_name_")) {
                val empId = key.substring("hide_last_name_".length)
                (value as? Boolean)?.let { hideLastNameCache[empId] = it }
            } else if (key.startsWith("cafe_name_")) {
                val cafeNo = key.substring("cafe_name_".length)
                (value as? String)?.let { customCafeNameCache[cafeNo] = it }
            } else if (key.startsWith("cafe_enabled_")) {
                val cafeNo = key.substring("cafe_enabled_".length)
                (value as? Boolean)?.let { cafeEnabledCache[cafeNo] = it }
            } else if (key.startsWith("cafe_notifications_enabled_")) {
                val cafeNo = key.substring("cafe_notifications_enabled_".length)
                (value as? Boolean)?.let { cafeNotificationsEnabledCache[cafeNo] = it }
            } else if (key.startsWith("cafe_disp_name_")) {
                val cafeNo = key.substring("cafe_disp_name_".length)
                (value as? String)?.let { cafeDisplayNameCache[cafeNo] = it }
            }
        }
    }

    fun getCustomCafeName(cafeNo: String): String? {
        return customCafeNameCache[cafeNo]
    }

    fun setCustomCafeName(cafeNo: String, name: String?) {
        if (name.isNullOrBlank()) {
            customCafeNameCache.remove(cafeNo)
            prefs.edit().remove("cafe_name_$cafeNo").apply()
        } else {
            val trimmed = name.trim()
            customCafeNameCache[cafeNo] = trimmed
            prefs.edit().putString("cafe_name_$cafeNo", trimmed).apply()
        }
    }

    fun isCafeEnabled(cafeNo: String?): Boolean {
        if (cafeNo == null) return true
        return cafeEnabledCache[cafeNo] ?: true
    }

    fun setCafeEnabled(cafeNo: String, enabled: Boolean) {
        cafeEnabledCache[cafeNo] = enabled
        prefs.edit().putBoolean("cafe_enabled_$cafeNo", enabled).apply()
    }

    fun isCafeNotificationsEnabled(cafeNo: String?): Boolean {
        if (cafeNo == null) return true
        return cafeNotificationsEnabledCache[cafeNo] ?: true
    }

    fun setCafeNotificationsEnabled(cafeNo: String, enabled: Boolean) {
        cafeNotificationsEnabledCache[cafeNo] = enabled
        prefs.edit().putBoolean("cafe_notifications_enabled_$cafeNo", enabled).apply()
    }

    fun getCafeNumberFromNotification(notification: com.anonymousassociate.betterpantry.models.NotificationData): String? {
        val appDataStr = notification.appData ?: return null
        try {
            val value = org.json.JSONTokener(appDataStr).nextValue()
            val json = if (value is org.json.JSONObject) value else if (value is String) org.json.JSONTokener(value).nextValue() as? org.json.JSONObject else null
            if (json != null) {
                val initiatorShift = json.optJSONObject("initiatorShift")
                if (initiatorShift != null) {
                    val cafe = initiatorShift.optString("cafeNumber").trim()
                    if (cafe.isNotEmpty()) return cafe
                }
                val cafe = json.optString("cafeNumber").trim()
                if (cafe.isNotEmpty()) return cafe
                val cafeNo = json.optString("cafeNo").trim()
                if (cafeNo.isNotEmpty()) return cafeNo
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun saveCafeDisplayName(cafeNo: String, displayName: String) {
        cafeDisplayNameCache[cafeNo] = displayName
        prefs.edit().putString("cafe_disp_name_$cafeNo", displayName).apply()
    }

    fun getCafeDisplayName(cafeNo: String?, cafeList: List<CafeInfo>? = null): String {
        if (cafeNo == null) return ""
        val customName = getCustomCafeName(cafeNo)
        if (!customName.isNullOrBlank()) {
            return customName
        }
        val savedName = cafeDisplayNameCache[cafeNo]
        if (!savedName.isNullOrBlank() && cafeList == null) {
            return savedName
        }
        val cafeInfo = cafeList?.firstOrNull {
            getCafeNumberFromDepartment(it.departmentName, it.address?.addressLine) == cafeNo
        }
        val assembledName = cafeInfo?.let { cafe ->
            val address = cafe.address
            val addressLine = address?.addressLine ?: ""
            val city = address?.city ?: ""
            val state = address?.state ?: ""
            val suffix = listOf(addressLine, city, state).filter { it.isNotEmpty() }.joinToString(", ")
            if (suffix.isNotEmpty()) {
                "#$cafeNo - $suffix"
            } else {
                "#$cafeNo"
            }
        } ?: savedName ?: "#$cafeNo"

        if (assembledName != "#$cafeNo" && assembledName != savedName && !assembledName.contains("Address Unavailable")) {
            saveCafeDisplayName(cafeNo, assembledName)
        }
        return assembledName
    }

    fun getCafeNumberFromDepartment(deptName: String?, addressLine: String? = null): String? {
        val name = deptName?.uppercase() ?: ""
        val addr = addressLine?.uppercase() ?: ""
        if (name.contains("RIVERDALE") || addr.contains("RIVERDALE")) {
            return "202924"
        }
        if (name.contains("MURRAY") || addr.contains("MURRAY")) {
            return "202927"
        }
        
        val regex = Regex("(\\d{4,6})")
        val parsed = regex.find(name)?.groupValues?.get(1) ?: return null
        return when (parsed) {
            "5900" -> "202927"
            "5905" -> "202924"
            else -> parsed
        }
    }

    fun getAddressFromSavedName(cafeNo: String): com.anonymousassociate.betterpantry.models.Address? {
        val savedName = prefs.getString("cafe_disp_name_$cafeNo", null) ?: return null
        val prefix = "#$cafeNo - "
        if (savedName.startsWith(prefix)) {
            val addrStr = savedName.substring(prefix.length)
            val parts = addrStr.split(",")
            val line = parts.getOrNull(0)?.trim() ?: ""
            val cityState = parts.getOrNull(1)?.trim() ?: ""
            val cityStateParts = cityState.trim().split(" ")
            val state = cityStateParts.lastOrNull()?.trim() ?: ""
            val city = cityState.substring(0, cityState.length - state.length).trim()
            return com.anonymousassociate.betterpantry.models.Address(addressLine = line, city = city, state = state, zipCode = "")
        }
        return null
    }

    fun getAssignedCafeNumbers(
        schedule: com.anonymousassociate.betterpantry.models.ScheduleData?,
        teamMembers: List<com.anonymousassociate.betterpantry.models.TeamMember>?,
        homeCafe: String?,
        userId: String?
    ): List<String> {
        val cafeNos = mutableSetOf<String>()
        if (homeCafe != null) cafeNos.add(homeCafe)

        // Only add cafes from the logged-in user's own shifts
        schedule?.currentShifts?.mapNotNull { it.cafeNumber }?.forEach { cafeNos.add(it) }

        // Only add cafes from the logged-in user's associate record
        if (userId != null && teamMembers != null) {
            val myAssociate = teamMembers.find { it.associate?.employeeId == userId }?.associate
            myAssociate?.cafeNumber?.let { cafeNos.add(it) }
            myAssociate?.loanedCafeList?.forEach { cafeNos.add(it) }
        }

        // Add from schedule.cafeList as well so that all system cafes are discoverable
        schedule?.cafeList?.mapNotNull {
            getCafeNumberFromDepartment(it.departmentName, it.address?.addressLine)
        }?.forEach { cafeNos.add(it) }

        return cafeNos.sorted()
    }

    fun getEnabledCafeNumbers(
        schedule: com.anonymousassociate.betterpantry.models.ScheduleData?,
        teamMembers: List<com.anonymousassociate.betterpantry.models.TeamMember>?,
        homeCafe: String?,
        userId: String?
    ): List<String> {
        val assigned = getAssignedCafeNumbers(schedule, teamMembers, homeCafe, userId)
        val enabled = assigned.filter { isCafeEnabled(it) }.sorted()
        return if (enabled.isEmpty() && homeCafe != null) listOf(homeCafe) else enabled
    }

    fun getCoworkerNicknameFirst(employeeId: String): String? {
        return nicknameFirstCache[employeeId]
    }

    fun getCoworkerNicknameLast(employeeId: String): String? {
        return nicknameLastCache[employeeId]
    }

    fun setCoworkerNickname(employeeId: String, first: String?, last: String?) {
        val editor = prefs.edit()
        if (first.isNullOrBlank()) {
            nicknameFirstCache.remove(employeeId)
            editor.remove("nickname_first_$employeeId")
        } else {
            val trimmed = first.trim()
            nicknameFirstCache[employeeId] = trimmed
            editor.putString("nickname_first_$employeeId", trimmed)
        }
        if (last.isNullOrBlank()) {
            nicknameLastCache.remove(employeeId)
            editor.remove("nickname_last_$employeeId")
        } else {
            val trimmed = last.trim()
            nicknameLastCache[employeeId] = trimmed
            editor.putString("nickname_last_$employeeId", trimmed)
        }
        editor.apply()
    }

    fun getCoworkerDisplayName(employeeId: String?, firstName: String?, lastName: String?, preferredName: String?): String {
        if (employeeId == null) {
            val first = if (!preferredName.isNullOrEmpty()) preferredName else firstName ?: ""
            return "$first ${lastName ?: ""}".trim()
        }
        val nickFirst = nicknameFirstCache[employeeId]
        val nickLast = nicknameLastCache[employeeId]
        val hideLast = hideLastNameCache[employeeId] ?: false
        
        val finalFirst = if (!nickFirst.isNullOrBlank()) nickFirst else (if (!preferredName.isNullOrEmpty()) preferredName else firstName ?: "")
        val finalLast = if (hideLast) "" else (if (!nickLast.isNullOrBlank()) nickLast else (lastName ?: ""))
        
        return "$finalFirst $finalLast".trim()
    }

    fun getCoworkerFirstResolved(employeeId: String?, firstName: String?, preferredName: String?): String {
        if (employeeId == null) return if (!preferredName.isNullOrEmpty()) preferredName else firstName ?: ""
        val nickFirst = nicknameFirstCache[employeeId]
        return if (!nickFirst.isNullOrBlank()) nickFirst else (if (!preferredName.isNullOrEmpty()) preferredName else firstName ?: "")
    }

    fun getCoworkerLastResolved(employeeId: String?, lastName: String?): String {
        if (employeeId == null) return lastName ?: ""
        val hideLast = hideLastNameCache[employeeId] ?: false
        if (hideLast) return ""
        val nickLast = nicknameLastCache[employeeId]
        return if (!nickLast.isNullOrBlank()) nickLast else (lastName ?: "")
    }

    fun getCoworkerHideLastName(employeeId: String): Boolean {
        return hideLastNameCache[employeeId] ?: false
    }

    fun setCoworkerHideLastName(employeeId: String, hide: Boolean) {
        hideLastNameCache[employeeId] = hide
        prefs.edit().putBoolean("hide_last_name_$employeeId", hide).apply()
    }

    fun resetAllNicknamesAndHideLastName() {
        nicknameFirstCache.clear()
        nicknameLastCache.clear()
        hideLastNameCache.clear()
        val editor = prefs.edit()
        val allKeys = prefs.all
        for (key in allKeys.keys) {
            if (key.startsWith("nickname_first_") || key.startsWith("nickname_last_") || key.startsWith("hide_last_name_")) {
                editor.remove(key)
            }
        }
        editor.apply()
    }
}



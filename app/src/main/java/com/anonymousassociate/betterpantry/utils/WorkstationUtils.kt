package com.anonymousassociate.betterpantry.utils

object WorkstationUtils {
    private val customNames = mapOf(
        "QC_2" to "QC 2",
        "1ST_CASHIER_1" to "Cashier 1",
        "SANDWICH_2" to "Sandwich 2",
        "SANDWICH_1" to "Sandwich 1",
        "SALAD_1" to "Salad 1",
        "SALAD_2" to "Salad 2",
        "DTORDERTAKER_1" to "DriveThru",
        "1ST_DR_1" to "Dining Room",
        "1st_Cashier" to "Cashier 1",
        "1st_Dr" to "Dining Room",
        "DtOrderTaker" to "DriveThru",
        "Sandwich_1" to "Sandwich 1",
        "Sandwich_2" to "Sandwich 2",
        "Qc_2" to "QC 2",
        "1ST_SANDWICH_1" to "Sandwich 1",
        "Bake" to "Baker",
        "BAKER" to "Baker",
        "SALAD" to "Salad 1",
        "SANDWICH" to "Sandwich 1",
        "1ST_CASHIER" to "Cashier 1",
        "QC_1" to "QC 1",
        "QC_2" to "QC 2",
        "DTORDERTAKER" to "DriveThru",
        "1ST_DR" to "Dining Room",
        "1st_DR" to "Dining Room",
        "1st _DR" to "Dining Room",
        "1st _Dr" to "Dining Room",
        "1st_dr" to "Dining Room",
        "1st _dr" to "Dining Room",
        "MANAGER_1" to "Manager",
        "MANAGER" to "Manager",
        "MANAGERADMIN_1" to "Manager",
        "MANAGERADMIN" to "Manager",
        "PEOPLEMANAGEMENT_1" to "Manager",
        "PEOPLEMANAGEMENT" to "Manager",
        "LABOR_MANAGEMENT" to "Manager",
        "LABORMANAGEMENT" to "Manager",
        "Labor Management" to "Manager"
    )

    fun isServiceWorkstation(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("service ambassador") || 
               lower.contains("drivethru") || 
               lower.contains("drive-thru") || 
               lower.contains("drive thru") || 
               lower.contains("cashier")
    }

    fun getDisplayName(workstationId: String?, fallbackName: String?, workstationCode: String? = null): String {
        var finalName: String? = null
        if (workstationId != null) {
            finalName = customNames[workstationId] ?: customNames[workstationId.trim()]
        }
        if (finalName == null && workstationCode != null) {
            finalName = customNames[workstationCode] ?: customNames[workstationCode.trim()]
        }
        if (finalName == null && fallbackName != null) {
            finalName = customNames[fallbackName] ?: customNames[fallbackName.trim()]
        }
        return finalName ?: fallbackName ?: workstationId ?: workstationCode ?: "Unknown"
    }

    fun replaceWorkstationNamesInText(text: String?): String {
        if (text == null) return ""
        var result: String = text
        val sortedKeys = customNames.keys.sortedByDescending { it.length }
        for (key in sortedKeys) {
            val value = customNames[key] ?: continue
            result = result.replace(key, value, ignoreCase = true)
        }
        return result
    }
}

package com.anonymousassociate.betterpantry.utils

import com.anonymousassociate.betterpantry.models.Shift
import com.anonymousassociate.betterpantry.models.TeamShift
import java.time.LocalDateTime

object ShiftCombiner {
    fun combineShifts(shifts: List<Shift>): List<Shift> {
        if (shifts.isEmpty()) return emptyList()

        // Group shifts by employeeId and businessDate
        // Available shifts (employeeId null, blank, or "AVAILABLE_SHIFT") should NOT be combined.
        val (available, assigned) = shifts.partition { 
            it.employeeId == null || it.employeeId == "AVAILABLE_SHIFT" || it.employeeId.isBlank() 
        }

        val grouped = assigned.groupBy { (it.employeeId ?: "") to (it.businessDate ?: "") }
        val mergedList = mutableListOf<Shift>()

        for ((_, group) in grouped) {
            val sorted = group.sortedBy { it.startDateTime }
            if (sorted.isEmpty()) continue

            var current = sorted[0]
            val constituent = mutableListOf<Shift>(current)

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                val currentEnd = try { LocalDateTime.parse(current.endDateTime) } catch (e: Exception) { null }
                val nextStart = try { LocalDateTime.parse(next.startDateTime) } catch (e: Exception) { null }

                if (currentEnd != null && nextStart != null && !nextStart.isAfter(currentEnd)) {
                    // Overlapping or consecutive: merge them
                    val nextEnd = try { LocalDateTime.parse(next.endDateTime) } catch (e: Exception) { null }
                    val newEndStr = if (nextEnd != null && currentEnd.isBefore(nextEnd)) next.endDateTime else current.endDateTime
                    
                    constituent.add(next)
                    
                    val names = constituent.map { getTranslatedName(it.workstationId, it.workstationName, it.workstationCode) }.distinct()
                    val mergedName = if (names.isNotEmpty()) names.joinToString(", ") else current.workstationName

                    current = current.copy(
                        endDateTime = newEndStr,
                        workstationName = mergedName
                    )
                } else {
                    // Non-consecutive: save current and start new
                    mergedList.add(if (constituent.size > 1) current.copy(combinedShifts = ArrayList(constituent)) else current)
                    current = next
                    constituent.clear()
                    constituent.add(current)
                }
            }
            mergedList.add(if (constituent.size > 1) current.copy(combinedShifts = ArrayList(constituent)) else current)
        }

        return (mergedList + available).sortedBy { it.startDateTime }
    }

    fun combineTeamShifts(shifts: List<TeamShift>): List<TeamShift> {
        if (shifts.isEmpty()) return emptyList()

        // Group team shifts by employeeId and businessDate
        // Available shifts should NOT be combined
        val (available, assigned) = shifts.partition { 
            it.employeeId == null || it.employeeId == "AVAILABLE_SHIFT" || it.employeeId.isBlank() 
        }

        val grouped = assigned.groupBy { (it.employeeId ?: "") to (it.businessDate ?: "") }
        val mergedList = mutableListOf<TeamShift>()

        for ((_, group) in grouped) {
            val sorted = group.sortedBy { it.startDateTime }
            if (sorted.isEmpty()) continue

            var current = sorted[0]
            val constituent = mutableListOf<TeamShift>(current)

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                val currentEnd = try { LocalDateTime.parse(current.endDateTime) } catch (e: Exception) { null }
                val nextStart = try { LocalDateTime.parse(next.startDateTime) } catch (e: Exception) { null }

                if (currentEnd != null && nextStart != null && !nextStart.isAfter(currentEnd)) {
                    // Overlapping or consecutive
                    val nextEnd = try { LocalDateTime.parse(next.endDateTime) } catch (e: Exception) { null }
                    val newEndStr = if (nextEnd != null && currentEnd.isBefore(nextEnd)) next.endDateTime else current.endDateTime
                    
                    constituent.add(next)
                    
                    val names = constituent.map { getTranslatedName(it.workstationId, it.workstationName, it.workstationCode) }.distinct()
                    val mergedName = if (names.isNotEmpty()) names.joinToString(", ") else current.workstationName

                    current = current.copy(
                        endDateTime = newEndStr,
                        workstationName = mergedName
                    )
                } else {
                    mergedList.add(if (constituent.size > 1) current.copy(combinedShifts = ArrayList(constituent)) else current)
                    current = next
                    constituent.clear()
                    constituent.add(current)
                }
            }
            mergedList.add(if (constituent.size > 1) current.copy(combinedShifts = ArrayList(constituent)) else current)
        }

        return (mergedList + available).sortedBy { it.startDateTime }
    }

    private fun getTranslatedName(id: String?, name: String?, code: String? = null): String {
        val customNames = mapOf(
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
        if (id != null) {
            val mapped = customNames[id]
            if (mapped != null) return mapped
        }
        if (code != null) {
            val mapped = customNames[code]
            if (mapped != null) return mapped
        }
        if (name != null) {
            val mapped = customNames[name]
            if (mapped != null) return mapped
        }
        return name ?: id ?: code ?: "Unknown"
    }
}

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
        return com.anonymousassociate.betterpantry.utils.WorkstationUtils.getDisplayName(id, name, code)
    }
}

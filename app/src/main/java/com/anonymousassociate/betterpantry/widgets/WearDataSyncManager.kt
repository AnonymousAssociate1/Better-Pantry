package com.anonymousassociate.betterpantry.widgets

import android.content.Context
import android.util.Log
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.SettingsPreferences
import com.anonymousassociate.betterpantry.models.Shift
import com.anonymousassociate.betterpantry.utils.WorkstationUtils
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object WearDataSyncManager {
    private const val TAG = "WearDataSyncManager"
    private val gson = Gson()

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

    data class CoworkerSyncInfo(
        val name: String,
        val workstation: String,
        val timeRange: String,
        val syncId: String,
        val endDateTime: String?
    )

    fun syncNextShift(context: Context) {
        // Run on background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduleCache = ScheduleCache(context)
                val settingsPreferences = SettingsPreferences(context)
                
                val now = LocalDateTime.now()
                val mySchedule = scheduleCache.getSchedule()
                
                val allShifts = mySchedule?.currentShifts ?: emptyList()
                val combinedShifts = com.anonymousassociate.betterpantry.utils.ShiftCombiner.combineShifts(allShifts)
                
                val nextCombinedShift = combinedShifts.filter { shift ->
                    try {
                        val end = LocalDateTime.parse(shift.endDateTime)
                        end.isAfter(now)
                    } catch (e: Exception) {
                        false
                    }
                }.sortedBy { it.startDateTime }.firstOrNull()

                if (nextCombinedShift != null) {
                    val constituentShifts = nextCombinedShift.combinedShifts ?: listOf(nextCombinedShift)
                    val roles = constituentShifts.map { s ->
                        WorkstationUtils.getDisplayName(s.workstationId, s.workstationName, s.workstationCode)
                    }.distinct()
                    val wName = roles.joinToString(", ")
                    
                    val managers = findManagersForShift(nextCombinedShift, scheduleCache, settingsPreferences)
                    val managerText = if (managers.isNotEmpty()) {
                        "Manager: ${managers.joinToString(", ")}"
                    } else {
                        "Manager: None scheduled"
                    }

                    val coworkersList = compileCoworkers(nextCombinedShift, wName, scheduleCache, settingsPreferences)

                    // Format dates once on the phone
                    var fDay = "Next Shift"
                    var fTime = ""
                    try {
                        val start = LocalDateTime.parse(nextCombinedShift.startDateTime)
                        val end = LocalDateTime.parse(nextCombinedShift.endDateTime)
                        val dayFormatter = DateTimeFormatter.ofPattern("EEEE M/d")
                        val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
                        fDay = start.format(dayFormatter)
                        fTime = "${start.format(timeFormatter).lowercase()} - ${end.format(timeFormatter).lowercase()}"
                    } catch (e: Exception) {}

                    val nowTime = LocalDateTime.now()
                    val syncTimeFormatter = DateTimeFormatter.ofPattern("h:mma")
                    val fSynced = "Synced: ${nowTime.format(syncTimeFormatter).lowercase()}"

                    val cleanRole = wName
                        .replace(" Room", "")
                        .replace(" room", "")

                    val syncData = NextShiftSyncData(
                        startDateTime = nextCombinedShift.startDateTime,
                        endDateTime = nextCombinedShift.endDateTime,
                        role = cleanRole,
                        cafeNumber = nextCombinedShift.cafeNumber,
                        managerText = managerText,
                        shiftId = nextCombinedShift.shiftId?.toString(),
                        coworkers = coworkersList,
                        lastUpdatedMs = System.currentTimeMillis(),
                        formattedDay = fDay,
                        formattedTime = fTime,
                        formattedSyncedTime = fSynced
                    )

                    val jsonStr = gson.toJson(syncData)
                    sendToWearableDevice(context, jsonStr)
                } else {
                    // Send empty sync data if no shifts scheduled
                    val syncData = NextShiftSyncData(
                        startDateTime = null,
                        endDateTime = null,
                        role = null,
                        cafeNumber = null,
                        managerText = null,
                        shiftId = null,
                        coworkers = emptyList(),
                        lastUpdatedMs = System.currentTimeMillis(),
                        formattedDay = null,
                        formattedTime = null,
                        formattedSyncedTime = null
                    )
                    val jsonStr = gson.toJson(syncData)
                    sendToWearableDevice(context, jsonStr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing shift data to Wear OS device", e)
            }
        }
    }

    private fun sendToWearableDevice(context: Context, jsonStr: String) {
        try {
            val dataClient = Wearable.getDataClient(context)
            val putDataMapReq = PutDataMapRequest.create("/next_shift_data")
            putDataMapReq.dataMap.putString("payload_json", jsonStr)
            putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
            dataClient.putDataItem(putDataReq)
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully synced next shift data to Wear OS device")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to sync next shift data to Wear OS device", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data to DataClient", e)
        }
    }

    private fun compileCoworkers(
        nextShift: Shift,
        userRole: String,
        scheduleCache: ScheduleCache,
        settingsPreferences: SettingsPreferences
    ): List<CoworkerSyncInfo> {
        val tempCoworkerList = mutableListOf<CoworkerShiftInfo>()

        val nextShiftStartStr = nextShift.startDateTime ?: return emptyList()
        val nextShiftEndStr = nextShift.endDateTime ?: return emptyList()
        val nextShiftCafe = nextShift.cafeNumber ?: ""

        val shiftStart = LocalDateTime.parse(nextShiftStartStr)
        val shiftEnd = LocalDateTime.parse(nextShiftEndStr)

        val rawTeamSchedule = scheduleCache.getTeamSchedule() ?: return emptyList()
        val cleanTeamSchedule = rawTeamSchedule.filter { member ->
            val empId = member.associate?.employeeId
            empId != null && empId != "AVAILABLE_SHIFT" && empId.isNotBlank()
        }
        val groupedMembers = cleanTeamSchedule.groupBy { it.associate?.employeeId }
        val teamSchedule = groupedMembers.map { (empId, members) ->
            val firstMember = members.first()
            if (members.size == 1) {
                firstMember
            } else {
                val allShifts = members.flatMap { it.shifts ?: emptyList() }.distinctBy { 
                    it.shiftId ?: "${it.startDateTime}-${it.workstationId}" 
                }
                firstMember.copy(shifts = allShifts)
            }
        }
        val timeFormatter = DateTimeFormatter.ofPattern("h:mma")

        teamSchedule.forEach { member ->
            val assoc = member.associate ?: return@forEach
            val empId = assoc.employeeId
            if (empId == null || empId == "AVAILABLE_SHIFT") return@forEach

            // Filter: same cafe, strictly overlapping, and hasn't ended yet
            val now = LocalDateTime.now()
            val overlappingShifts = member.shifts?.filter { shift ->
                try {
                    val start = LocalDateTime.parse(shift.startDateTime)
                    val end = LocalDateTime.parse(shift.endDateTime)
                    val cafe = shift.cafeNumber ?: ""
                    
                    cafe == nextShiftCafe && start.isBefore(shiftEnd) && end.isAfter(shiftStart) && end.isAfter(now)
                } catch (e: Exception) {
                    false
                }
            } ?: emptyList()

            if (overlappingShifts.isNotEmpty()) {
                val sortedShifts = overlappingShifts.sortedBy { it.startDateTime }
                
                val roles = sortedShifts.map { s ->
                    WorkstationUtils.getDisplayName(s.workstationId, s.workstationName, s.workstationCode)
                }
                val combinedRole = roles.joinToString(", ")

                val minStart = sortedShifts.map { LocalDateTime.parse(it.startDateTime) }.minOrNull() ?: shiftStart
                val maxEnd = sortedShifts.map { LocalDateTime.parse(it.endDateTime) }.maxOrNull() ?: shiftEnd

                val displayName = settingsPreferences.getCoworkerDisplayName(empId, assoc.firstName, assoc.lastName, assoc.preferredName)
                val timeStr = "${minStart.format(timeFormatter).lowercase()} - ${maxEnd.format(timeFormatter).lowercase()}"

                tempCoworkerList.add(CoworkerShiftInfo(displayName, combinedRole, timeStr, minStart, maxEnd))
            }
        }

        val isUserOnService = WorkstationUtils.isServiceWorkstation(userRole)
        val sortedList = if (isUserOnService) {
            val serviceCoworkers = tempCoworkerList.filter { 
                WorkstationUtils.isServiceWorkstation(it.workstation) 
            }.sortedWith(compareBy<CoworkerShiftInfo> { it.minStart }.thenBy { it.name })

            val otherCoworkers = tempCoworkerList.filter { 
                !WorkstationUtils.isServiceWorkstation(it.workstation) 
            }.sortedWith(compareBy<CoworkerShiftInfo> { it.minStart }.thenBy { it.name })

            serviceCoworkers + otherCoworkers
        } else {
            tempCoworkerList.sortWith(compareBy<CoworkerShiftInfo> { it.minStart }.thenBy { it.name })
            tempCoworkerList
        }

        return sortedList.map {
            val cleanWorkstation = it.workstation
                .replace(" Room", "")
                .replace(" room", "")
            val uniqueId = "${it.name}_${cleanWorkstation}_${it.timeStr}"
            CoworkerSyncInfo(
                name = it.name,
                workstation = cleanWorkstation,
                timeRange = it.timeStr,
                syncId = uniqueId,
                endDateTime = it.maxEnd.toString()
            )
        }
    }

    private fun findManagersForShift(
        myShift: Shift,
        scheduleCache: ScheduleCache,
        settingsPreferences: SettingsPreferences
    ): List<String> {
        val start = LocalDateTime.parse(myShift.startDateTime)
        val end = LocalDateTime.parse(myShift.endDateTime)
        val targetCafe = myShift.cafeNumber ?: ""
        val teamSchedule = scheduleCache.getTeamSchedule() ?: return emptyList()

        val managersList = mutableListOf<String>()

        teamSchedule.forEach { member ->
            val assoc = member.associate ?: return@forEach
            val empId = assoc.employeeId
            if (empId == null || empId == "AVAILABLE_SHIFT") return@forEach

            val overlappingShifts = member.shifts?.filter { shift ->
                try {
                    val sStart = LocalDateTime.parse(shift.startDateTime)
                    val sEnd = LocalDateTime.parse(shift.endDateTime)
                    val sCafe = shift.cafeNumber ?: ""
                    
                    sCafe == targetCafe && sStart.isBefore(end) && sEnd.isAfter(start)
                } catch (e: Exception) {
                    false
                }
            } ?: emptyList()

            if (overlappingShifts.isNotEmpty()) {
                val sortedShifts = overlappingShifts.sortedBy { it.startDateTime }
                val roles = sortedShifts.map { shift ->
                    WorkstationUtils.getDisplayName(shift.workstationId, shift.workstationName, shift.workstationCode)
                }
                val isManager = roles.any { it.contains("Manager", ignoreCase = true) }
                if (isManager) {
                    val displayName = settingsPreferences.getCoworkerDisplayName(empId, assoc.firstName, assoc.lastName, assoc.preferredName)
                    managersList.add(displayName)
                }
            }
        }
        return managersList.distinct()
    }

    private data class CoworkerShiftInfo(
        val name: String,
        val workstation: String,
        val timeStr: String,
        val minStart: LocalDateTime,
        val maxEnd: LocalDateTime
    )
}

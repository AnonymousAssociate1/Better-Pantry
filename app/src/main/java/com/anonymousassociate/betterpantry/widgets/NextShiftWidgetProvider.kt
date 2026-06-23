package com.anonymousassociate.betterpantry.widgets

import android.app.PendingIntent
import android.os.Bundle
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.anonymousassociate.betterpantry.MainActivity
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.SettingsPreferences
import com.anonymousassociate.betterpantry.models.Shift
import com.anonymousassociate.betterpantry.utils.WorkstationUtils
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class NextShiftWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
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

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_next_shift)

            if (nextCombinedShift != null) {
                views.setViewVisibility(R.id.nextShiftListView, View.VISIBLE)
                views.setViewVisibility(R.id.emptyStateText, View.GONE)

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

                // Setup RemoteAdapter for the single scrollable ListView
                val serviceIntent = Intent(context, NextShiftCoworkersService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra("next_shift_start", nextCombinedShift.startDateTime)
                    putExtra("next_shift_end", nextCombinedShift.endDateTime)
                    putExtra("next_shift_cafe", nextCombinedShift.cafeNumber)
                    putExtra("next_shift_role", wName)
                    putExtra("next_shift_manager", managerText)
                    putExtra("next_shift_id", nextCombinedShift.shiftId?.toString())
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.nextShiftListView, serviceIntent)

                // Setup PendingIntent template for item clicks (header & coworkers)
                val clickIntent = Intent(context, MainActivity::class.java)
                val clickPendingIntent = PendingIntent.getActivity(
                    context,
                    widgetId, // Use widgetId to keep the PendingIntent request code unique
                    clickIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.nextShiftListView, clickPendingIntent)
            } else {
                views.setViewVisibility(R.id.nextShiftListView, View.GONE)
                views.setViewVisibility(R.id.emptyStateText, View.VISIBLE)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.nextShiftListView)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
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

            // Filter: same cafe, strict overlap
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
}

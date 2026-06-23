package com.anonymousassociate.betterpantry.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.SettingsPreferences
import com.anonymousassociate.betterpantry.utils.WorkstationUtils
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class NextShiftCoworkersService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return NextShiftCoworkersFactory(applicationContext, intent)
    }
}

class NextShiftCoworkersFactory(private val context: Context, private val intent: Intent) : RemoteViewsService.RemoteViewsFactory {

    private val scheduleCache = ScheduleCache(context)
    private val settingsPreferences = SettingsPreferences(context)
    @Volatile
    private var coworkerList: List<CoworkerShiftInfo> = emptyList()
    private var isNarrow = false

    data class CoworkerShiftInfo(
        val name: String,
        val workstation: String,
        val timeRange: String,
        val minStart: LocalDateTime
    )

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val tempCoworkerList = mutableListOf<CoworkerShiftInfo>()
        val now = LocalDateTime.now()

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val options = appWidgetManager.getAppWidgetOptions(widgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        isNarrow = minWidth < 180

        val nextShiftStartStr = intent.getStringExtra("next_shift_start") ?: return
        val nextShiftEndStr = intent.getStringExtra("next_shift_end") ?: return
        val nextShiftCafe = intent.getStringExtra("next_shift_cafe") ?: ""

        val shiftStart = LocalDateTime.parse(nextShiftStartStr)
        val shiftEnd = LocalDateTime.parse(nextShiftEndStr)

        val rawTeamSchedule = scheduleCache.getTeamSchedule() ?: return
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
                // Merge multiple shifts for the same person
                val sortedShifts = overlappingShifts.sortedBy { it.startDateTime }
                
                // Combine workstations in chronological order
                val roles = sortedShifts.map { s ->
                    WorkstationUtils.getDisplayName(s.workstationId, s.workstationName, s.workstationCode)
                }
                val combinedRole = roles.distinct().joinToString(", ")

                // Combine time range (minimum start time to maximum end time of overlapping shifts)
                val minStart = sortedShifts.map { LocalDateTime.parse(it.startDateTime) }.minOrNull() ?: shiftStart
                val maxEnd = sortedShifts.map { LocalDateTime.parse(it.endDateTime) }.maxOrNull() ?: shiftEnd

                val displayName = settingsPreferences.getCoworkerDisplayName(empId, assoc.firstName, assoc.lastName, assoc.preferredName)
                val timeStr = "${minStart.format(timeFormatter).lowercase()} - ${maxEnd.format(timeFormatter).lowercase()}"

                tempCoworkerList.add(CoworkerShiftInfo(displayName, combinedRole, timeStr, minStart))
            }
        }

        val userRole = intent.getStringExtra("next_shift_role") ?: ""
        val isUserOnService = com.anonymousassociate.betterpantry.utils.WorkstationUtils.isServiceWorkstation(userRole)

        if (isUserOnService) {
            val serviceCoworkers = tempCoworkerList.filter { 
                com.anonymousassociate.betterpantry.utils.WorkstationUtils.isServiceWorkstation(it.workstation) 
            }.sortedWith(compareBy<CoworkerShiftInfo> { it.minStart }.thenBy { it.name })

            val otherCoworkers = tempCoworkerList.filter { 
                !com.anonymousassociate.betterpantry.utils.WorkstationUtils.isServiceWorkstation(it.workstation) 
            }.sortedWith(compareBy<CoworkerShiftInfo> { it.minStart }.thenBy { it.name })

            coworkerList = serviceCoworkers + otherCoworkers
        } else {
            // Sort chronologically by shift start time (minStart)
            tempCoworkerList.sortWith(compareBy<CoworkerShiftInfo> { it.minStart }.thenBy { it.name })
            coworkerList = tempCoworkerList
        }
    }

    override fun onDestroy() {
        coworkerList = emptyList()
    }

    override fun getCount(): Int {
        // Position 0 is always the Next Shift header details card
        // If no coworkers, we show 1 "Working alone" row
        return if (coworkerList.isEmpty()) 2 else coworkerList.size + 1
    }

    override fun getViewAt(position: Int): RemoteViews {
        if (position == 0) {
            // Render scrollable Header Card
            val views = RemoteViews(context.packageName, R.layout.item_widget_next_shift_header)
            
            val startStr = intent.getStringExtra("next_shift_start")
            val endStr = intent.getStringExtra("next_shift_end")
            val role = intent.getStringExtra("next_shift_role")
            val manager = intent.getStringExtra("next_shift_manager")
            val shiftId = intent.getStringExtra("next_shift_id")

            var dateTimeText = "Scheduled Shift"
            if (startStr != null && endStr != null) {
                try {
                    val start = LocalDateTime.parse(startStr)
                    val end = LocalDateTime.parse(endStr)
                    val dayFormatter = DateTimeFormatter.ofPattern("EEEE M/d")
                    val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
                    dateTimeText = "${start.format(dayFormatter)}, ${start.format(timeFormatter).lowercase()} - ${end.format(timeFormatter).lowercase()}"
                } catch (e: Exception) {}
            }

            views.setTextViewText(R.id.shiftDateTime, dateTimeText)
            views.setTextViewText(R.id.shiftWorkstation, "Role: $role")
            views.setTextViewText(R.id.shiftManager, manager)
            
            // Show subheader only if there are coworkers
            views.setViewVisibility(R.id.coworkersHeader, if (coworkerList.isNotEmpty()) View.VISIBLE else View.GONE)

            // Setup click deep-link
            val fillInIntent = Intent().apply {
                action = "OPEN_SHIFT_DETAILS"
                putExtra("shift_id", shiftId)
                putExtra("shift_start", startStr)
                putExtra("shift_end", endStr)
            }
            views.setOnClickFillInIntent(R.id.headerCardContainer, fillInIntent)

            return views
        } else {
            // Render Coworker or "Working alone" placeholder row
            val layoutId = if (isNarrow) R.layout.item_widget_staff_narrow else R.layout.item_widget_staff
            val views = RemoteViews(context.packageName, layoutId)
            
            if (coworkerList.isEmpty()) {
                views.setTextViewText(R.id.staffName, "Working alone")
                views.setTextViewText(R.id.staffWorkstation, "")
                views.setTextViewText(R.id.staffTime, "")
            } else {
                val idx = position - 1
                if (idx >= 0 && idx < coworkerList.size) {
                    val coworker = coworkerList[idx]
                    views.setTextViewText(R.id.staffName, coworker.name)
                    views.setTextViewText(R.id.staffWorkstation, coworker.workstation)
                    views.setTextViewText(R.id.staffTime, coworker.timeRange)

                    val fillInIntent = Intent().apply {
                        action = "OPEN_SHIFT_DETAILS"
                        val shiftId = intent.getStringExtra("next_shift_id")
                        val startStr = intent.getStringExtra("next_shift_start")
                        val endStr = intent.getStringExtra("next_shift_end")
                        putExtra("shift_id", shiftId)
                        putExtra("shift_start", startStr)
                        putExtra("shift_end", endStr)
                    }
                    views.setOnClickFillInIntent(R.id.staffItemContainer, fillInIntent)
                } else {
                    views.setTextViewText(R.id.staffName, "")
                    views.setTextViewText(R.id.staffWorkstation, "")
                    views.setTextViewText(R.id.staffTime, "")
                }
            }

            return views
        }
    }

    override fun getLoadingView(): RemoteViews {
        // Return a mock placeholder of the staff row to prevent blank flicker
        val layoutId = if (isNarrow) R.layout.item_widget_staff_narrow else R.layout.item_widget_staff
        val views = RemoteViews(context.packageName, layoutId)
        views.setTextViewText(R.id.staffName, "Loading...")
        views.setTextViewText(R.id.staffWorkstation, "")
        views.setTextViewText(R.id.staffTime, "")
        return views
    }

    override fun getViewTypeCount(): Int = 3

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}

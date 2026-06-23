package com.anonymousassociate.betterpantry.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.models.ScheduleData
import java.time.LocalDate

class CalendarAdapter(
    private val dates: List<LocalDate>,
    private val today: LocalDate,
    private val onDateClick: (LocalDate) -> Unit
) : RecyclerView.Adapter<CalendarAdapter.DateViewHolder>() {

    private var scheduleData: ScheduleData? = null
    private val workDates = mutableSetOf<LocalDate>()
    private val availableShiftDates = mutableSetOf<LocalDate>()
    private val approvedTimeOffDates = mutableSetOf<LocalDate>()
    private val pendingTimeOffDates = mutableSetOf<LocalDate>()

    fun updateSchedule(schedule: ScheduleData, timeOffRequests: List<com.anonymousassociate.betterpantry.models.TimeOffRequest>? = null, showAvailability: Boolean = true, context: android.content.Context, cafeFilter: Set<String>? = null) {
        val settingsPreferences = com.anonymousassociate.betterpantry.SettingsPreferences(context)
        
        val tempWorkDates = mutableSetOf<LocalDate>()
        val tempAvailableShiftDates = mutableSetOf<LocalDate>()
        val tempApprovedTimeOffDates = mutableSetOf<LocalDate>()
        val tempPendingTimeOffDates = mutableSetOf<LocalDate>()

        // Parse work dates from currentShifts
        schedule.currentShifts?.forEach { shift ->
            if (settingsPreferences.isCafeEnabled(shift.cafeNumber)) {
                shift.startDateTime?.let { dateTimeStr ->
                    try {
                        val date = LocalDate.parse(dateTimeStr.substring(0, 10))
                        tempWorkDates.add(date)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Parse available shift dates
        if (showAvailability) {
            schedule.track?.forEach { track ->
                if (track.type == "AVAILABLE" && track.primaryShiftRequest?.state == "AVAILABLE") {
                    val shift = track.primaryShiftRequest?.shift
                    if (shift != null && settingsPreferences.isCafeEnabled(shift.cafeNumber)) {
                        if (cafeFilter == null || cafeFilter.contains(shift.cafeNumber)) {
                            shift.startDateTime?.let { dateTimeStr ->
                                try {
                                    val date = LocalDate.parse(dateTimeStr.substring(0, 10))
                                    tempAvailableShiftDates.add(date)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Parse Time Off
        if (showAvailability) {
            timeOffRequests?.forEach { req ->
                if (req.timeOffDate != null) {
                    try {
                        val date = LocalDate.parse(req.timeOffDate)
                        if (req.status == "APPROVED") {
                            tempApprovedTimeOffDates.add(date)
                        } else if (req.status == "PENDING") {
                            tempPendingTimeOffDates.add(date)
                        }
                    } catch(e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val hasChanged = scheduleData != schedule ||
                workDates != tempWorkDates ||
                availableShiftDates != tempAvailableShiftDates ||
                approvedTimeOffDates != tempApprovedTimeOffDates ||
                pendingTimeOffDates != tempPendingTimeOffDates

        if (hasChanged) {
            scheduleData = schedule
            workDates.clear()
            workDates.addAll(tempWorkDates)
            availableShiftDates.clear()
            availableShiftDates.addAll(tempAvailableShiftDates)
            approvedTimeOffDates.clear()
            approvedTimeOffDates.addAll(tempApprovedTimeOffDates)
            pendingTimeOffDates.clear()
            pendingTimeOffDates.addAll(tempPendingTimeOffDates)
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_date, parent, false)
        return DateViewHolder(view)
    }

    override fun onBindViewHolder(holder: DateViewHolder, position: Int) {
        val date = dates[position]
        holder.bind(date)
    }

    override fun getItemCount() = dates.size

    inner class DateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateCard: MaterialCardView = itemView.findViewById(R.id.dateCard)
        private val dateText: TextView = itemView.findViewById(R.id.dateText)
        private val todayCircle: View = itemView.findViewById(R.id.todayCircle)
        private val availableDot: View = itemView.findViewById(R.id.availableDot)

        fun bind(date: LocalDate) {
            val dayNumber = date.dayOfMonth.toString()
            dateText.text = dayNumber

            val isToday = date == today
            val hasShift = workDates.contains(date)
            val isApprovedOff = approvedTimeOffDates.contains(date)
            val isPendingOff = pendingTimeOffDates.contains(date)

            // Reset styling
            todayCircle.visibility = View.GONE

            // Determine Background & Text Color
            // Priority: Shift > Approved Off > Pending Off > Default
            when {
                // Shift (Work)
                hasShift -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.work_day_green))
                    dateText.setTextColor(Color.WHITE)
                    if (isToday) {
                        // User requested primary color for Today
                        dateText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                        todayCircle.visibility = View.VISIBLE
                        todayCircle.setBackgroundResource(R.drawable.today_background_white)
                    }
                }
                
                // Approved Time Off (Darker Yellow)
                isApprovedOff -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.time_off_pastel_yellow))
                    dateText.setTextColor(Color.WHITE)
                    if (isToday) {
                        dateText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                        todayCircle.visibility = View.VISIBLE
                        todayCircle.setBackgroundResource(R.drawable.today_background_white)
                    }
                }
                
                // Pending Time Off (Orange)
                isPendingOff -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark))
                    dateText.setTextColor(Color.WHITE)
                    if (isToday) {
                        dateText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                        todayCircle.visibility = View.VISIBLE
                        todayCircle.setBackgroundResource(R.drawable.today_background_white)
                    }
                }
                
                // Default
                else -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.card_background_color))
                    dateText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                    if (isToday) {
                        todayCircle.visibility = View.VISIBLE
                        todayCircle.setBackgroundResource(R.drawable.today_background)
                    }
                }
            }

            // Show dot for available shifts
            availableDot.visibility = if (availableShiftDates.contains(date)) {
                View.VISIBLE
            } else {
                View.GONE
            }

            // Click listener
            dateCard.setOnClickListener {
                onDateClick(date)
            }
        }
    }
}

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

    fun updateSchedule(schedule: ScheduleData) {
        scheduleData = schedule
        workDates.clear()
        availableShiftDates.clear()

        // Parse work dates from currentShifts
        schedule.currentShifts?.forEach { shift ->
            shift.startDateTime?.let { dateTimeStr ->
                try {
                    val date = LocalDate.parse(dateTimeStr.substring(0, 10))
                    workDates.add(date)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Parse available shift dates
        schedule.track?.forEach { track ->
            if (track.type == "AVAILABLE" && track.primaryShiftRequest?.state == "AVAILABLE") {
                track.primaryShiftRequest?.shift?.startDateTime?.let { dateTimeStr ->
                    try {
                        val date = LocalDate.parse(dateTimeStr.substring(0, 10))
                        availableShiftDates.add(date)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        notifyDataSetChanged()
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

            // Reset styling
            todayCircle.visibility = View.GONE

            when {
                // Today with shift: green background, white text, white circle (inner indicator)
                isToday && hasShift -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.work_day_green))
                    dateText.setTextColor(Color.WHITE)
                    dateText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                    todayCircle.visibility = View.VISIBLE
                    todayCircle.setBackgroundResource(R.drawable.today_background_white)
                }
                // Today without shift: card background, primary text, card-colored circle with border
                isToday && !hasShift -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.card_background_color))
                    dateText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                    todayCircle.visibility = View.VISIBLE
                    todayCircle.setBackgroundResource(R.drawable.today_background)
                }
                // Day with shift: green background, white text
                !isToday && hasShift -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.work_day_green))
                    dateText.setTextColor(Color.WHITE)
                }
                // Day without shift: card background, primary text
                else -> {
                    dateCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.card_background_color))
                    dateText.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
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

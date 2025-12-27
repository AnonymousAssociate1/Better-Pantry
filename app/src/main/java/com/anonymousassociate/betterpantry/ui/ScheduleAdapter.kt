package com.anonymousassociate.betterpantry.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.models.TeamShift
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class DaySchedule(val date: LocalDate, val shifts: List<EnrichedShift>)
data class EnrichedShift(
    val shift: TeamShift,
    val firstName: String,
    val lastName: String?,
    val isMe: Boolean = false,
    val isAvailable: Boolean = false,
    val managerNotes: String? = null,
    val requesterName: String? = null,
    val requestedAt: String? = null,
    val requestId: String? = null,
    val myPickupRequestId: String? = null,
    val pickupRequests: List<String>? = null,
    val coworkers: List<String>? = null,
    val coworkerShifts: List<EnrichedShift>? = null,
    val location: String? = null
)

interface ScheduleInteractionListener {
    fun onExpandClick(day: DaySchedule)
    fun onShiftClick(shift: EnrichedShift)
}

class DayScheduleAdapter(
    private var days: List<DaySchedule>,
    private val listener: ScheduleInteractionListener
) : RecyclerView.Adapter<DayScheduleAdapter.DayViewHolder>() {

    val scrollStates: MutableMap<String, Int> = mutableMapOf()

    fun updateData(newDays: List<DaySchedule>) {
        days = newDays
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_day_schedule, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(days[position])
    }

    override fun getItemCount() = days.size

    inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateHeader: TextView = itemView.findViewById(R.id.dateHeader)
        private val expandButton: ImageButton = itemView.findViewById(R.id.expandButton)
        private val chartContainer: RelativeLayout = itemView.findViewById(R.id.chartContainer)
        val scrollView: HorizontalScrollView = itemView.findViewById(R.id.chartScrollView) // Made public for Adapter access

        fun bind(day: DaySchedule) {
            dateHeader.text = day.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
            
            expandButton.setOnClickListener { listener.onExpandClick(day) }

            // Draw chart and get "now" position
            val result = ChartRenderer.drawChart(itemView.context, chartContainer, day, isExpanded = false, listener = listener)
            val nowX = result.first
            
            // Handle Scroll Persistence
            val key = day.date.toString()
            scrollView.setOnScrollChangeListener(null) // Clear previous to avoid loops during restore
            
            val adapter = this@DayScheduleAdapter
            val savedScroll = adapter.scrollStates[key]
            
            scrollView.post {
                if (savedScroll != null) {
                    scrollView.scrollTo(savedScroll, 0)
                } else if (nowX != null) {
                    // Initial scroll to now (centered)
                    val screenWidth = scrollView.width
                    scrollView.scrollTo(nowX - screenWidth / 2, 0)
                    // Save this initial state so we don't jump again if user scrolls back
                    adapter.scrollStates[key] = nowX - screenWidth / 2
                } else {
                    scrollView.scrollTo(0,0)
                }
                
                // Re-attach listener
                scrollView.setOnScrollChangeListener { _, scrollX, _, _, _ ->
                    adapter.scrollStates[key] = scrollX
                }
            }
        }
    }
}

object ChartRenderer {
    private const val hourWidthDp = 60 
    private const val barHeightDp = 32
    private const val laneSpacingDp = 4
    private const val timeHeaderHeightDp = 24
    private const val sidePaddingDp = 24

    fun drawChart(
        context: Context,
        chartContainer: RelativeLayout,
        day: DaySchedule,
        isExpanded: Boolean,
        containerWidth: Int = 0,
        fixedStartTime: LocalDateTime? = null,
        fixedEndTime: LocalDateTime? = null,
        listener: ScheduleInteractionListener? = null,
        fitToWidth: Boolean = false,
        focusTime: LocalDateTime? = null,
        focusEndTime: LocalDateTime? = null
    ): Pair<Int?, Int?> {
        chartContainer.removeAllViews()
        if (day.shifts.isEmpty()) return Pair(null, null)

        val sortedShifts = day.shifts.sortedBy { it.shift.startDateTime }
        
        // Determine Time Range
        val firstShiftStart = fixedStartTime ?: LocalDateTime.parse(sortedShifts.first().shift.startDateTime)
        val lastShiftEnd = fixedEndTime ?: day.shifts.maxOf { LocalDateTime.parse(it.shift.endDateTime) }.truncatedTo(ChronoUnit.MINUTES)

        val chartStartTime = firstShiftStart.truncatedTo(ChronoUnit.HOURS)
        var chartEndTime = lastShiftEnd.truncatedTo(ChronoUnit.HOURS)
        
        if (lastShiftEnd.minute > 0) {
            chartEndTime = chartEndTime.plusHours(1)
        }
        
        if (!chartEndTime.isAfter(chartStartTime)) {
            chartEndTime = chartStartTime.plusHours(1)
        }

        val totalMinutes = ChronoUnit.MINUTES.between(chartStartTime, chartEndTime)
        val paddingPx = dpToPx(context, sidePaddingDp)

        val pixelsPerMinute = if (isExpanded || fitToWidth) {
            val availableWidth = (containerWidth - 2 * paddingPx).coerceAtLeast(1)
            availableWidth.toFloat() / totalMinutes.toFloat()
        } else {
            dpToPx(context, hourWidthDp) / 60f
        }
        
        // Fix: Set explicit width to prevent scroll jumping
        val contentWidth = (totalMinutes * pixelsPerMinute).toInt() + 2 * paddingPx
        if (chartContainer.layoutParams == null) {
             chartContainer.layoutParams = android.view.ViewGroup.LayoutParams(contentWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
             chartContainer.layoutParams.width = contentWidth
        }

        val grouped = day.shifts.groupBy { 
            getWorkstationDisplayName(it.shift.workstationId ?: it.shift.workstationCode, it.shift.workstationName) 
        }
        
        val sortedGroups = grouped.entries.sortedBy { entry ->
            entry.value.minOf { LocalDateTime.parse(it.shift.startDateTime) }
        }

        var contentHeight = dpToPx(context, timeHeaderHeightDp)
        sortedGroups.forEach { (_, shifts) ->
            val lanes = calculateLanes(shifts)
            contentHeight += dpToPx(context, (lanes.size * (barHeightDp + laneSpacingDp)) + laneSpacingDp)
        }
        contentHeight += dpToPx(context, 16)

        // Draw Time Markers
        var currentHour = chartStartTime
        while (!currentHour.isAfter(chartEndTime)) {
            val minutesFromStart = ChronoUnit.MINUTES.between(chartStartTime, currentHour)
            val xPos = (minutesFromStart * pixelsPerMinute).toInt() + paddingPx

            val timeLabel = TextView(context).apply {
                val pattern = if (isExpanded) "h" else "h a"
                text = currentHour.format(DateTimeFormatter.ofPattern(pattern))
                textSize = 10f
                setSingleLine()
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 0
                }
            }
            
            // Measure to center
            timeLabel.measure(0, 0)
            val labelWidth = timeLabel.measuredWidth
            (timeLabel.layoutParams as RelativeLayout.LayoutParams).leftMargin = xPos - (labelWidth / 2)
            
            chartContainer.addView(timeLabel)
            
            val line = View(context).apply {
                setBackgroundColor(0x20888888) 
                val lineHeight = contentHeight - dpToPx(context, timeHeaderHeightDp)
                layoutParams = RelativeLayout.LayoutParams(dpToPx(context, 1), lineHeight).apply {
                    leftMargin = xPos
                    topMargin = dpToPx(context, timeHeaderHeightDp)
                }
            }
            chartContainer.addView(line)

            currentHour = currentHour.plusHours(1)
        }

        var currentY = dpToPx(context, timeHeaderHeightDp)

        sortedGroups.forEach { (_, shifts) ->
            val lanes = calculateLanes(shifts)

            lanes.forEach { lane ->
                lane.forEach { s ->
                    val sStart = LocalDateTime.parse(s.shift.startDateTime)
                    val sEnd = LocalDateTime.parse(s.shift.endDateTime)
                    
                    // Clamp to chart range
                    val effectiveStart = if (sStart.isBefore(chartStartTime)) chartStartTime else sStart
                    val effectiveEnd = if (sEnd.isAfter(chartEndTime)) chartEndTime else sEnd
                    
                    if (effectiveStart.isBefore(effectiveEnd)) {
                        val durationMins = ChronoUnit.MINUTES.between(effectiveStart, effectiveEnd)
                        val startOffsetMins = ChronoUnit.MINUTES.between(chartStartTime, effectiveStart)

                        val x = (startOffsetMins * pixelsPerMinute).toInt() + paddingPx
                        val width = ((durationMins * pixelsPerMinute).toInt() - dpToPx(context, 2)).coerceAtLeast(1) 
                        val height = dpToPx(context, barHeightDp)

                        val barView = TextView(context).apply {
                            val station = getWorkstationDisplayName(s.shift.workstationId ?: s.shift.workstationCode, s.shift.workstationName)
                            text = "$station - ${s.firstName}"
                            textSize = 10f
                            setTextColor(android.graphics.Color.WHITE)
                            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                            setSingleLine()
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(dpToPx(context, 4), 0, dpToPx(context, 4), 0)
                            
                            val bgColor = when {
                                s.isAvailable -> ContextCompat.getColor(context, R.color.purple_500)
                                s.isMe -> ContextCompat.getColor(context, R.color.work_day_green)
                                else -> 0xFF00695C.toInt()
                            }
                            setBackgroundColor(bgColor)
                            
                            elevation = 4f * context.resources.displayMetrics.density

                            layoutParams = RelativeLayout.LayoutParams(width, height).apply {
                                leftMargin = x
                                topMargin = currentY
                            }
                            
                            setOnClickListener {
                                listener?.onShiftClick(s)
                            }
                        }
                        chartContainer.addView(barView)
                    }
                }
                currentY += dpToPx(context, barHeightDp + laneSpacingDp)
            }
            currentY += dpToPx(context, laneSpacingDp)
        }

        var nowX: Int? = null
        val now = LocalDateTime.now()
        if (now.toLocalDate() == day.date && now.isAfter(chartStartTime) && now.isBefore(chartEndTime)) {
            val minsFromStart = ChronoUnit.MINUTES.between(chartStartTime, now)
            val x = (minsFromStart * pixelsPerMinute).toInt() + paddingPx

            val redLine = View(context).apply {
                setBackgroundColor(0xB3FF0000.toInt())
                val lineHeight = contentHeight - dpToPx(context, timeHeaderHeightDp)
                layoutParams = RelativeLayout.LayoutParams(dpToPx(context, 2), lineHeight).apply {
                    leftMargin = x
                    topMargin = dpToPx(context, timeHeaderHeightDp)
                }
                elevation = 10f 
            }
            chartContainer.addView(redLine)
            
            nowX = x
        }
        
        var focusX: Int? = null
        if (focusTime != null && !focusTime.isBefore(chartStartTime) && !focusTime.isAfter(chartEndTime)) {
             val minsFromStart = ChronoUnit.MINUTES.between(chartStartTime, focusTime)
             val startX = (minsFromStart * pixelsPerMinute).toInt() + paddingPx
             
             if (focusEndTime != null && !focusEndTime.isBefore(focusTime)) {
                 val durationMins = ChronoUnit.MINUTES.between(focusTime, focusEndTime)
                 val width = (durationMins * pixelsPerMinute).toInt()
                 focusX = startX + (width / 2)
             } else {
                 focusX = startX
             }
        }
        
        return Pair(nowX, focusX)
    }

    private fun calculateLanes(shifts: List<EnrichedShift>): List<List<EnrichedShift>> {
        val lanes = mutableListOf<MutableList<EnrichedShift>>()
        shifts.sortedBy { it.shift.startDateTime }.forEach { s ->
            var placed = false
            for (lane in lanes) {
                val lastInLane = lane.last()
                val lastEnd = LocalDateTime.parse(lastInLane.shift.endDateTime)
                val thisStart = LocalDateTime.parse(s.shift.startDateTime)
                
                if (!thisStart.isBefore(lastEnd)) { 
                    lane.add(s)
                    placed = true
                    break
                }
            }
            if (!placed) {
                lanes.add(mutableListOf(s))
            }
        }
        return lanes
    }
}

fun dpToPx(context: Context, dp: Int): Int {
    return (dp * context.resources.displayMetrics.density).toInt()
}

fun getWorkstationDisplayName(workstationId: String?, fallbackName: String?): String {
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
        "PEOPLEMANAGEMENT" to "Manager"
    )
    if (workstationId != null) {
        val mapped = customNames[workstationId]
        if (mapped != null) return mapped
    }
    return fallbackName ?: workstationId ?: "Unknown"
}

class DateDividerItemDecoration(context: android.content.Context) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        outRect.bottom = (16 * view.resources.displayMetrics.density).toInt()
    }
}
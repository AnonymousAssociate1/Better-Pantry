package com.anonymousassociate.betterpantry.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache

class AvailabilityFragment : Fragment() {

    private lateinit var scheduleCache: ScheduleCache
    private lateinit var updatedText: TextView
    private lateinit var dailyMaxText: TextView
    private lateinit var weeklyMaxText: TextView
    private lateinit var availabilityListContainer: android.widget.LinearLayout
    private lateinit var timeOffListContainer: android.widget.LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var updateTimeRunnable: Runnable? = null
    
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    
    private val repository by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_availability, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scheduleCache = ScheduleCache(requireContext())

        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        updatedText = view.findViewById(R.id.updatedText)
        dailyMaxText = view.findViewById(R.id.dailyMaxText)
        weeklyMaxText = view.findViewById(R.id.weeklyMaxText)
        availabilityListContainer = view.findViewById(R.id.availabilityListContainer)
        timeOffListContainer = view.findViewById(R.id.timeOffListContainer)
        val editButton = view.findViewById<ImageButton>(R.id.editAvailabilityButton)
        val addTimeOffButton = view.findViewById<ImageButton>(R.id.addTimeOffButton)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(swipeRefreshLayout) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Add top padding to the content inside SwipeRefreshLayout via nested scroll view if possible, 
            // but SwipeRefreshLayout ignores padding. We usually pad the target.
            // The NestedScrollView is the target.
            // But we already have header outside.
            // Adjust progress view offset
            val refreshStart = (60 * resources.displayMetrics.density).toInt() // Below header
            val refreshEnd = (100 * resources.displayMetrics.density).toInt()
            swipeRefreshLayout.setProgressViewOffset(false, refreshStart, refreshEnd)
            insets
        }

        val greenColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.work_day_green)
        val backgroundColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.card_background_color)
        swipeRefreshLayout.setColorSchemeColors(greenColor)
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(backgroundColor)

        swipeRefreshLayout.setOnRefreshListener {
            loadAvailability(forceRefresh = true)
        }

        backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        editButton.setOnClickListener {
            (requireActivity() as? com.anonymousassociate.betterpantry.MainActivity)?.openBrowser("https://pantry.panerabread.com/gateway/home/#/self-service/availability")
        }
        
        addTimeOffButton.setOnClickListener {
            TimeOffRequestFragment().show(parentFragmentManager, "time_off_request")
        }
        
        parentFragmentManager.setFragmentResultListener("time_off_request_result", viewLifecycleOwner) { _, _ ->
            refreshDataFromCache()
        }
        
        // Load cache immediately
        refreshDataFromCache()
        
        loadAvailability()
    }
    
    fun refreshDataFromCache() {
        if (!isAdded) return
        val av = scheduleCache.getAvailability()
        val max = scheduleCache.getMaxHours()
        val timeOff = scheduleCache.getTimeOff()
        
        displayData(av, max)
        displayTimeOff(timeOff)
        updateTimestamp()
        startUpdateTimer()
    }
    
    private fun loadAvailability(forceRefresh: Boolean = false) {
        if (!forceRefresh && !scheduleCache.isAvailabilityStale()) {
            swipeRefreshLayout.isRefreshing = false
            return
        }
        
        swipeRefreshLayout.post { swipeRefreshLayout.isRefreshing = true }
        
        lifecycleScope.launchWhenStarted {
            try {
                // Fetch all data to keep app in sync
                val av = repository.getAvailability(forceRefresh)
                val max = repository.getMaxHours(forceRefresh)
                val timeOff = repository.getTimeOff(forceRefresh)
                
                // Also fetch schedule if forcing refresh to keep "Updated" text consistent with data freshness
                if (forceRefresh) {
                    launch(Dispatchers.IO) {
                        repository.getSchedule(true)
                    }
                }
                
                displayData(av, max)
                displayTimeOff(timeOff)
                updateTimestamp()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }
    
    private fun displayTimeOff(requests: List<com.anonymousassociate.betterpantry.models.TimeOffRequest>?) {
        timeOffListContainer.removeAllViews()
        if (requests == null) return
        
        // Sort chronologically (closest date first)
        // Filter out past requests? User didn't specify, but "closest to furthest" usually implies future.
        // If we include past, Date ASC puts oldest first. 
        // Let's assume upcoming requests are the focus. 
        // If I filter >= today, then ASC is "closest to furthest".
        val today = java.time.LocalDate.now()
        val sorted = requests.filter { 
            try {
                val date = java.time.LocalDate.parse(it.timeOffDate)
                !date.isBefore(today)
            } catch (e: Exception) { true }
        }.sortedBy { it.timeOffDate } // ASC
        
        val context = requireContext()
        
        sorted.forEach { req ->
            val card = androidx.cardview.widget.CardView(context)
            val params = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            card.layoutParams = params
            card.radius = (8 * resources.displayMetrics.density)
            card.cardElevation = (2 * resources.displayMetrics.density)
            card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.card_background_color))
            
            val layout = android.widget.LinearLayout(context)
            layout.orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            layout.setPadding(pad, pad, pad, pad)
            
            // Header Row: Date (Left) + Status Column (Right)
            val headerRow = android.widget.LinearLayout(context)
            headerRow.orientation = android.widget.LinearLayout.HORIZONTAL
            headerRow.layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            headerRow.gravity = android.view.Gravity.CENTER_VERTICAL
            
            // Date
            val dateText = TextView(context)
            val dateStr = try {
                if (req.timeOffDate != null) {
                    val date = java.time.LocalDate.parse(req.timeOffDate)
                    date.format(java.time.format.DateTimeFormatter.ofPattern("E, MMM d, yyyy"))
                } else ""
            } catch (e: Exception) {
                req.timeOffDate ?: ""
            }
            
            dateText.text = dateStr
            dateText.textSize = 16f
            dateText.setTypeface(null, android.graphics.Typeface.BOLD)
            dateText.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            dateText.layoutParams = android.widget.LinearLayout.LayoutParams(
                0, 
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            
            // Status Text
            val statusText = TextView(context)
            statusText.text = req.status ?: "UNKNOWN"
            statusText.textSize = 14f
            statusText.setTypeface(null, android.graphics.Typeface.BOLD)
            statusText.gravity = android.view.Gravity.END
            
            val color = when (req.status) {
                "APPROVED" -> R.color.work_day_green
                "PENDING" -> android.R.color.holo_orange_dark
                "DENIED", "CANCELLED" -> android.R.color.holo_red_dark
                else -> R.color.text_secondary
            }
            statusText.setTextColor(androidx.core.content.ContextCompat.getColor(context, color))
            
            headerRow.addView(dateText)
            headerRow.addView(statusText)
            layout.addView(headerRow)
            
            // Middle Row: Time (Left) + Trash (Right)
            val middleRow = android.widget.LinearLayout(context)
            middleRow.orientation = android.widget.LinearLayout.HORIZONTAL
            val middleParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            middleParams.topMargin = (4 * resources.displayMetrics.density).toInt()
            middleRow.layoutParams = middleParams
            middleRow.gravity = android.view.Gravity.CENTER_VERTICAL
            
            // Times
            val timeText = TextView(context)
            if (req.startTime != null && req.endTime != null) {
                val timeStr = try {
                    val start = java.time.LocalDateTime.parse(req.startTime)
                    val end = java.time.LocalDateTime.parse(req.endTime)
                    val timeFmt = java.time.format.DateTimeFormatter.ofPattern("h:mma")
                    "${start.format(timeFmt)} - ${end.format(timeFmt)}"
                } catch (e: Exception) {
                    "${req.startTime} - ${req.endTime}"
                }
                timeText.text = timeStr
                timeText.textSize = 14f
                timeText.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary))
            }
            timeText.layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            middleRow.addView(timeText)
            
            // Trash Button
            if (req.status == "PENDING" || req.status == "APPROVED") {
                val trashButton = ImageButton(context)
                trashButton.setImageResource(R.drawable.ic_delete)
                trashButton.background = null
                trashButton.setColorFilter(androidx.core.content.ContextCompat.getColor(context, android.R.color.holo_red_dark))
                
                trashButton.setPadding(8, 8, 8, 8)
                val btnParams = android.widget.LinearLayout.LayoutParams(
                    (32 * resources.displayMetrics.density).toInt(),
                    (32 * resources.displayMetrics.density).toInt()
                )
                // Gravity end is redundant if it's the last item in horizontal layout with weight 1 before it,
                // but explicit gravity handles alignment if weight fails.
                // However, middleRow has gravity CENTER_VERTICAL.
                // Trash should be right aligned. Weight on timeText pushes it right.
                trashButton.layoutParams = btnParams
                
                trashButton.setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setTitle("Cancel Time Off")
                        .setMessage("Are you sure you want to cancel this time off?")
                        .setPositiveButton("Yes") { _, _ ->
                            cancelTimeOff(req)
                        }
                        .setNegativeButton("No", null)
                        .show()
                }
                middleRow.addView(trashButton)
            }
            
            layout.addView(middleRow)
            
            // Comments
            if (!req.associateComments.isNullOrEmpty()) {
                val commentText = TextView(context)
                commentText.text = req.associateComments
                commentText.textSize = 14f
                commentText.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                commentText.layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                }
                layout.addView(commentText)
            }
            
            card.addView(layout)
            timeOffListContainer.addView(card)
        }
    }
    
    private fun cancelTimeOff(req: com.anonymousassociate.betterpantry.models.TimeOffRequest) {
        lifecycleScope.launchWhenStarted {
            try {
                val payload = org.json.JSONObject().apply {
                    put("thirdPartyId", "Self-Service") // Hardcoded from example
                    put("requestId", req.requestId)
                    put("status", "CANCELLED")
                }
                
                val success = repository.cancelTimeOff(payload.toString())
                if (success) {
                    android.widget.Toast.makeText(requireContext(), "Request cancelled", android.widget.Toast.LENGTH_SHORT).show()
                    // UI refresh handled by repository updating cache and refreshing?
                    // Repository updates cache. AvailabilityFragment needs to refresh.
                    // cancelTimeOff calls getTimeOff(forceRefresh=true) which updates cache.
                    // We need to re-fetch from cache.
                    refreshDataFromCache() 
                    // Actually, forceRefresh updates the cache, but doesn't push to UI unless observed.
                    // Since we are in the same scope, we can just call loadAvailability(forceRefresh=false) to read fresh cache?
                    // Better: call refreshDataFromCache() which reads from cache.
                    // But cache might not be updated *immediately* in this thread if async?
                    // cancelTimeOff is suspend and waits for api + cache update. So it should be ready.
                    // Wait, refreshDataFromCache reads cache.
                    // If repository.cancelTimeOff returns, cache IS updated.
                    refreshDataFromCache()
                } else {
                    android.widget.Toast.makeText(requireContext(), "Failed to cancel request", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(requireContext(), "Error cancelling request", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun displayData(
        avResponse: com.anonymousassociate.betterpantry.models.AvailabilityResponse?, 
        maxResponse: com.anonymousassociate.betterpantry.models.MaxHoursResponse?
    ) {
        // Max Hours
        if (maxResponse?.approved != null) {
            dailyMaxText.text = maxResponse.approved.maxHoursDaily?.toString() ?: "--"
            weeklyMaxText.text = maxResponse.approved.maxHoursWeekly?.toString() ?: "--"
        } else {
            dailyMaxText.text = "--"
            weeklyMaxText.text = "--"
        }

        // Availability
        availabilityListContainer.removeAllViews()
        val days = listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")
        
        if (avResponse?.approved?.availableTime != null) {
            val map = avResponse.approved.availableTime
            
            days.forEach { dayKey ->
                val slots = map[dayKey] ?: emptyList()
                val dayView = createDayView(dayKey, slots)
                availabilityListContainer.addView(dayView)
            }
        }
    }

    private fun createDayView(dayKey: String, slots: List<com.anonymousassociate.betterpantry.models.TimeSlot>): View {
        val context = requireContext()
        val card = androidx.cardview.widget.CardView(context)
        val params = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = (8 * resources.displayMetrics.density).toInt()
        card.layoutParams = params
        card.radius = (8 * resources.displayMetrics.density)
        card.cardElevation = (2 * resources.displayMetrics.density)
        card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.card_background_color))
        
        val layout = android.widget.LinearLayout(context)
        layout.orientation = android.widget.LinearLayout.VERTICAL // Changed to VERTICAL for times under day? No, design was Day LEFT, Times RIGHT?
        // Layout was horizontal in my previous code: layout.orientation = android.widget.LinearLayout.HORIZONTAL
        // I'll stick to horizontal but fix padding.
        layout.orientation = android.widget.LinearLayout.HORIZONTAL
        
        val pad = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(pad, pad, pad, pad)
        
        val dayText = TextView(context)
        dayText.text = dayKey.lowercase().replaceFirstChar { it.uppercase() }
        dayText.textSize = 16f
        dayText.setTypeface(null, android.graphics.Typeface.BOLD)
        dayText.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
        dayText.layoutParams = android.widget.LinearLayout.LayoutParams(
            (100 * resources.displayMetrics.density).toInt(), 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        val timesLayout = android.widget.LinearLayout(context)
        timesLayout.orientation = android.widget.LinearLayout.VERTICAL
        timesLayout.layoutParams = android.widget.LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        timesLayout.gravity = android.view.Gravity.END
        
        if (slots.isEmpty()) {
            val tv = TextView(context)
            tv.text = "Not Available"
            tv.textSize = 16f
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary))
            tv.gravity = android.view.Gravity.END
            tv.layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            timesLayout.addView(tv)
        } else {
            slots.forEach { slot ->
                val tv = TextView(context)
                val start = formatTime(slot.start)
                val end = formatTime(slot.end)
                tv.text = "$start - $end"
                tv.textSize = 16f
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                tv.gravity = android.view.Gravity.END
                tv.layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                timesLayout.addView(tv)
            }
        }
        
        layout.addView(dayText)
        layout.addView(timesLayout)
        card.addView(layout)
        
        return card
    }
    
    private fun formatTime(time: String?): String {
        if (time == null) return ""
        try {
            val parsed = java.time.LocalTime.parse(time)
            return parsed.format(java.time.format.DateTimeFormatter.ofPattern("h:mma"))
        } catch (e: Exception) {
            return time
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdateTimer()
    }

    private fun updateTimestamp() {
        updatedText.text = scheduleCache.getLastUpdateText()
    }

    private fun startUpdateTimer() {
        stopUpdateTimer()
        updateTimeRunnable = object : Runnable {
            override fun run() {
                updateTimestamp()
                handler.postDelayed(this, 60000L)
            }
        }
        handler.post(updateTimeRunnable!!)
    }

    private fun stopUpdateTimer() {
        updateTimeRunnable?.let {
            handler.removeCallbacks(it)
        }
        updateTimeRunnable = null
    }
}

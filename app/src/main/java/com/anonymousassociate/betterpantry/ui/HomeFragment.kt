package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.anonymousassociate.betterpantry.AuthManager
import com.anonymousassociate.betterpantry.PantryApiService
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.models.Associate
import com.anonymousassociate.betterpantry.models.ScheduleData
import com.anonymousassociate.betterpantry.models.Shift
import com.anonymousassociate.betterpantry.models.TeamMember
import com.anonymousassociate.betterpantry.models.TeamShift
import com.anonymousassociate.betterpantry.ui.adapters.CalendarAdapter
import com.anonymousassociate.betterpantry.ui.adapters.ShiftAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

import com.anonymousassociate.betterpantry.NotificationWorker
import com.anonymousassociate.betterpantry.models.NotificationData

class HomeFragment : Fragment() {

    private lateinit var authManager: AuthManager
    private lateinit var apiService: PantryApiService
    private lateinit var scheduleCache: ScheduleCache
    private lateinit var calendarAdapter: CalendarAdapter
    private lateinit var calendarRecyclerView: RecyclerView
    private lateinit var shiftsRecyclerView: RecyclerView
    private lateinit var availableShiftsRecyclerView: RecyclerView
    private lateinit var dateRangeText: TextView
    private lateinit var availableShiftsTitle: TextView
    private lateinit var updatedText: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var scheduleData: ScheduleData? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateTimeRunnable: Runnable? = null
    private var detailDialog: Dialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authManager = AuthManager(requireContext())
        apiService = PantryApiService(authManager)
        scheduleCache = ScheduleCache(requireContext())

        calendarRecyclerView = view.findViewById(R.id.calendarRecyclerView)
        shiftsRecyclerView = view.findViewById(R.id.shiftsRecyclerView)
        availableShiftsRecyclerView = view.findViewById(R.id.availableShiftsRecyclerView)
        availableShiftsRecyclerView.addItemDecoration(DateDividerItemDecoration(requireContext()))
        dateRangeText = view.findViewById(R.id.dateRangeText)
        availableShiftsTitle = view.findViewById(R.id.availableShiftsTitle)
        updatedText = view.findViewById(R.id.updatedText)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        val settingsButton: android.widget.ImageButton = view.findViewById(R.id.settingsButton)

        settingsButton.setOnClickListener { showSettingsMenu(it) }

        setupCalendar()
        setupSwipeRefresh()

        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedSchedule != null) {
            scheduleData = cachedSchedule
            displaySchedule(cachedSchedule)
            updateTimestamp(scheduleCache.getLastUpdateTime())
            startUpdateTimer()
        }

        loadSchedule()
    }

    private fun showSettingsMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.settings_menu, popup.menu)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true)
        }

        popup.setOnMenuItemClickListener { item ->
            val mainActivity = requireActivity() as? com.anonymousassociate.betterpantry.MainActivity
            when (item.itemId) {
                R.id.menu_workday -> {
                    mainActivity?.openBrowser("https://wd5.myworkday.com/panerabread/learning")
                    true
                }
                R.id.menu_availability -> {
                    mainActivity?.openBrowser("https://pantry.panerabread.com/gateway/home/#/self-service/availability")
                    true
                }
                R.id.menu_time_off -> {
                    mainActivity?.openBrowser("https://pantry.panerabread.com/gateway/home/#/self-service/rto-franchise")
                    true
                }
                R.id.menu_corc -> {
                    mainActivity?.openBrowser("https://login.microsoftonline.com/login.srf?wa=wsignin1.0&whr=panerabread.com&wreply=https://panerabread.sharepoint.com/sites/Home/SitePages/CORCHome.aspx")
                    true
                }
                R.id.menu_logout -> {
                    mainActivity?.logout()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    fun loadScheduleFromActivity() {
        if (isAdded) {
            loadSchedule()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdateTimer()
    }

    private fun setupCalendar() {
        val today = LocalDate.now()
        val startDate = if (today.dayOfWeek == DayOfWeek.WEDNESDAY) {
            today
        } else {
            today.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY))
        }

        val dates = (0 until 28).map { startDate.plusDays(it.toLong()) }
        val endDate = startDate.plusDays(27)
        val formatter = DateTimeFormatter.ofPattern("MMMM d")
        dateRangeText.text = "${startDate.format(formatter)} - ${endDate.format(formatter)}"

        calendarAdapter = CalendarAdapter(dates, today) { date ->
            onDateClicked(date)
        }

        calendarRecyclerView.apply {
            layoutManager = GridLayoutManager(context, 7)
            adapter = calendarAdapter
        }
    }

    private fun setupSwipeRefresh() {
        val greenColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.work_day_green)
        val backgroundColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.card_background_color)
        
        swipeRefreshLayout.setColorSchemeColors(greenColor)
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(backgroundColor)

        swipeRefreshLayout.setOnRefreshListener {
            loadSchedule(forceRefresh = true)
        }
    }

    private fun loadSchedule(forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            val lastUpdate = scheduleCache.getLastUpdateTime()
            val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
            if (scheduleData != null && lastUpdate > fiveMinutesAgo) {
                swipeRefreshLayout.isRefreshing = false
                return
            }
        }

        swipeRefreshLayout.isRefreshing = true
        lifecycleScope.launch {
            try {
                val schedule = apiService.getSchedule(30)
                schedule?.let {
                    scheduleData = it
                    scheduleCache.saveSchedule(it)
                    displaySchedule(it)
                    updateTimestamp(System.currentTimeMillis())
                    startUpdateTimer()
                    
                    prefetchTeamMembers(it)
                }
                checkNotifications()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    fun checkNotifications() {
        lifecycleScope.launch {
            try {
                val count = apiService.getNotificationCount()
                (requireActivity() as? com.anonymousassociate.betterpantry.MainActivity)?.updateNotificationBadge(count)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun prefetchTeamMembers(schedule: ScheduleData) {
        val myShifts = schedule.currentShifts ?: emptyList()
        val availableShifts = schedule.track?.filter { track ->
            track.type == "AVAILABLE" && track.primaryShiftRequest?.state == "AVAILABLE"
        }?.mapNotNull { it.primaryShiftRequest?.shift } ?: emptyList()

        val allShifts = myShifts + availableShifts
        
        lifecycleScope.launch {
            allShifts.forEach { shiftItem ->
                val currentShift = shiftItem
                launch(Dispatchers.IO) {
                    if (currentShift.cafeNumber != null && currentShift.companyCode != null &&
                        currentShift.startDateTime != null && currentShift.endDateTime != null) {
                        
                        try {
                            val shiftId = currentShift.shiftId ?: "${currentShift.startDateTime}-${currentShift.workstationId ?: currentShift.workstationCode}"
                            
                            if (scheduleCache.getTeamMembers(shiftId) == null) {
                                val startOfDay = LocalDateTime.parse(currentShift.startDateTime)
                                    .with(java.time.LocalTime.MIN)
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                                val teamMembers = apiService.getTeamMembers(
                                    currentShift.cafeNumber,
                                    currentShift.companyCode,
                                    startOfDay,
                                    currentShift.endDateTime
                                )

                                if (teamMembers != null) {
                                    scheduleCache.saveTeamMembers(shiftId, teamMembers)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun displaySchedule(schedule: ScheduleData) {
        calendarAdapter.updateSchedule(schedule)

        val distinctShifts = schedule.currentShifts?.distinctBy { it.shiftId }
        if (distinctShifts != null) {
            val shiftAdapter = ShiftAdapter(
                shifts = distinctShifts,
                onShiftClick = { shift ->
                    showShiftDetailDialog(listOf(shift), emptyList())
                }
            )
            shiftsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = shiftAdapter
            }
        }

        val availableShifts = schedule.track?.filter { track ->
            track.type == "AVAILABLE" && track.primaryShiftRequest?.state == "AVAILABLE"
        }?.mapNotNull { it.primaryShiftRequest?.shift }
         ?.distinctBy { it.shiftId }
         ?.sortedBy { it.startDateTime } ?: emptyList()

        if (availableShifts.isNotEmpty()) {
            availableShiftsTitle.visibility = View.VISIBLE
            val availableAdapter = ShiftAdapter(
                shifts = availableShifts,
                onShiftClick = { shift ->
                    showShiftDetailDialog(emptyList(), listOf(shift))
                },
                subtitleProvider = { shift ->
                    var subtitle = ""
                    try {
                        val trackItem = schedule.track?.find { 
                            it.type == "AVAILABLE" && it.primaryShiftRequest?.shift == shift 
                        }
                        val requester = trackItem?.primaryShiftRequest
                        val requesterName = getEmployeeName(requester?.requesterId)
                        val timeAgo = getTimeAgo(requester?.requestedAt)
                        
                        val workstationId = shift.workstationId ?: shift.workstationCode ?: ""
                        val workstationName = getWorkstationDisplayName(workstationId, shift.workstationName)
                        
                        subtitle = "$workstationName - Posted by $requesterName $timeAgo"
                    } catch (e: Exception) {
                        subtitle = shift.workstationName ?: "Shift"
                    }
                    subtitle
                }
            )
            availableShiftsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = availableAdapter
            }
        } else {
            availableShiftsTitle.visibility = View.GONE
        }
    }

    private fun updateTimestamp(timestamp: Long) {
        val now = System.currentTimeMillis()
        val diffMs = now - timestamp
        val diffMinutes = diffMs / 1000 / 60

        updatedText.text = when {
            diffMinutes < 1 -> "Updated now"
            diffMinutes == 1L -> "Updated 1 minute ago"
            diffMinutes < 60 -> "Updated $diffMinutes minutes ago"
            else -> {
                val hours = diffMinutes / 60
                if (hours == 1L) "Updated 1 hour ago" else "Updated $hours hours ago"
            }
        }
    }

    private fun startUpdateTimer() {
        stopUpdateTimer()
        updateTimeRunnable = object : Runnable {
            override fun run() {
                val lastUpdate = scheduleCache.getLastUpdateTime()
                if (lastUpdate > 0) {
                    updateTimestamp(lastUpdate)
                }
                handler.postDelayed(this, 60000)
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

    private fun onDateClicked(date: LocalDate) {
        val schedule = scheduleData ?: return

        val myShiftsOnDate = schedule.currentShifts?.filter { shift ->
            try {
                val shiftDate = LocalDate.parse(shift.startDateTime?.substring(0, 10))
                shiftDate == date
            } catch (e: Exception) {
                false
            }
        } ?: emptyList()

        val availableShiftsOnDate = schedule.track?.filter { track ->
            track.type == "AVAILABLE" && track.primaryShiftRequest?.state == "AVAILABLE"
        }?.mapNotNull { it.primaryShiftRequest?.shift }?.filter { shift ->
            try {
                val shiftDate = LocalDate.parse(shift.startDateTime?.substring(0, 10))
                shiftDate == date
            } catch (e: Exception) {
                false
            }
        }?.distinctBy { it.shiftId }?.sortedBy { it.startDateTime } ?: emptyList()

        if (myShiftsOnDate.isNotEmpty() || availableShiftsOnDate.isNotEmpty()) {
            showShiftDetailDialog(myShiftsOnDate, availableShiftsOnDate, fromCalendarClick = true)
        } else {
            // Open full schedule for the day
            val teamMembers = scheduleCache.getTeamSchedule() ?: emptyList()
            val mergedMembers = mergeData(
                teamMembers, 
                scheduleData?.currentShifts ?: emptyList(), 
                scheduleData?.track ?: emptyList(), 
                scheduleData?.employeeInfo ?: emptyList()
            )
            
            val allShiftsForDay = mutableListOf<EnrichedShift>()
            val myId = authManager.getUserId()
            
            mergedMembers.forEach { tm ->
                val isMe = tm.associate?.employeeId == myId
                val isAvailable = tm.associate?.employeeId == "AVAILABLE_SHIFT"
                val firstName = tm.associate?.firstName ?: "Unknown"
                val lastName = tm.associate?.lastName
                
                tm.shifts?.forEach { s ->
                    try {
                        if (s.startDateTime?.startsWith(date.toString()) == true) {
                            val cafeInfo = scheduleData?.cafeList?.firstOrNull { 
                                it.departmentName?.contains(s.cafeNumber ?: "") == true 
                            } ?: scheduleData?.cafeList?.firstOrNull()
                            
                            val location = cafeInfo?.let { cafe ->
                                val address = cafe.address
                                "#${s.cafeNumber ?: ""} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
                            } ?: "#${s.cafeNumber ?: ""}"

                            allShiftsForDay.add(
                                EnrichedShift(
                                    shift = s,
                                    firstName = firstName,
                                    lastName = lastName,
                                    isMe = isMe,
                                    isAvailable = isAvailable,
                                    location = location
                                )
                            )
                        }
                    } catch(e: Exception) {}
                }
            }
            
            showDayScheduleDialog(DaySchedule(date, allShiftsForDay))
        }
    }

    private fun showShiftDetailDialog(myShifts: List<Shift>, availableShifts: List<Shift>, fromCalendarClick: Boolean = false, customTitle: String? = null, isNested: Boolean = false) {
        val dialog = Dialog(requireContext())
        detailDialog = dialog
        dialog.setContentView(R.layout.dialog_shift_detail)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val shiftsContainer = dialog.findViewById<LinearLayout>(R.id.shiftsContainer)
        val closeButton = dialog.findViewById<View>(R.id.closeButton)

        var titleText = customTitle ?: "Shift Details"
        var hideCoworkersForMyShifts = isNested
        var hideCoworkersForAvailable = isNested

        // Determine context if no custom title
        if (customTitle == null) {
            if (myShifts.size == 1 && availableShifts.isEmpty()) {
                val shift = myShifts[0]
                if (shift.employeeId != authManager.getUserId()) {
                    // Coworker
                    titleText = getEmployeeName(shift.employeeId)
                    hideCoworkersForMyShifts = true
                } else {
                    // Me
                    titleText = "Shift Details"
                }
            } else if (availableShifts.size == 1 && myShifts.isEmpty()) {
                titleText = "Available Shift"
            }
        } else {
            // Apply hiding logic even if custom title is provided
             if (myShifts.size == 1 && availableShifts.isEmpty()) {
                val shift = myShifts[0]
                if (shift.employeeId != authManager.getUserId()) {
                     hideCoworkersForMyShifts = true
                }
            } else if (availableShifts.size == 1 && myShifts.isEmpty()) {
                // Keep default false
            }
        }

        dialogTitle.text = titleText
        shiftsContainer.removeAllViews()

        myShifts.forEach { shift ->
            addShiftCard(shiftsContainer, shift, isAvailable = false, hideCoworkers = hideCoworkersForMyShifts)
        }

        val showAsTitle = fromCalendarClick && myShifts.isEmpty() && availableShifts.isNotEmpty()
        val showAsSeparator = myShifts.isNotEmpty() && availableShifts.isNotEmpty()

        if (showAsTitle || showAsSeparator) {
            val separator = TextView(requireContext()).apply {
                text = "AVAILABLE SHIFTS"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.work_day_green, null))
                letterSpacing = 0.05f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                    topMargin = if (showAsTitle) 0 else 24.dpToPx()
                    bottomMargin = 16.dpToPx()
                }
            }
            shiftsContainer.addView(separator)
        }

        availableShifts.forEach { shift ->
            addShiftCard(shiftsContainer, shift, isAvailable = true, hideCoworkers = hideCoworkersForAvailable)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDayScheduleDialog(daySchedule: DaySchedule, focusShift: Shift? = null) {
        val dialog = Dialog(requireContext())
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_day_schedule_wrapper, null)
        dialog.setContentView(view)
        
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val dateHeader = view.findViewById<TextView>(R.id.dateHeader)
        val expandButton = view.findViewById<android.widget.ImageButton>(R.id.expandButton)
        val closeButton = view.findViewById<android.widget.ImageButton>(R.id.closeButton)
        val chartContainer = view.findViewById<RelativeLayout>(R.id.chartContainer)
        val scrollView = view.findViewById<android.widget.HorizontalScrollView>(R.id.chartScrollView)
        
        val noScheduleText = view.findViewById<View>(R.id.noScheduleText)
        
        dateHeader.text = daySchedule.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        
        closeButton.visibility = View.VISIBLE
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        if (daySchedule.shifts.isEmpty()) {
            noScheduleText.visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            expandButton.visibility = View.GONE
        } else {
            noScheduleText.visibility = View.GONE
            scrollView.visibility = View.VISIBLE
            expandButton.visibility = View.VISIBLE
            
            expandButton.setOnClickListener {
                val fragment = ExpandedScheduleFragment.newInstance(daySchedule)
                fragment.show(parentFragmentManager, "ExpandedSchedule")
            }
            
                    scrollView.post {
                         val isToday = daySchedule.date == LocalDate.now()
                         
                         val focusTime = try { 
                             LocalDateTime.parse(focusShift?.startDateTime) 
                         } catch(e: Exception) { 
                             if (isToday) LocalDateTime.now() else null
                         }
                         val focusEndTime = try { 
                             LocalDateTime.parse(focusShift?.endDateTime) 
                         } catch(e: Exception) { 
                             null 
                         }
                         
                         val result = ChartRenderer.drawChart(                    requireContext(),
                    chartContainer,
                    daySchedule,
                    isExpanded = false,
                    focusTime = focusTime,
                    focusEndTime = focusEndTime,
                    listener = object : ScheduleInteractionListener {
                        override fun onExpandClick(day: DaySchedule) {
                            expandButton.performClick()
                        }
                        override fun onShiftClick(clickedShift: EnrichedShift) {
                            val newShift = clickedShift.shift.toShift()
                            val title = if (clickedShift.isAvailable) {
                                "Available Shift"
                            } else {
                                "${clickedShift.firstName} ${clickedShift.lastName ?: ""}".trim()
                            }
                            if (clickedShift.isAvailable) {
                                 showShiftDetailDialog(emptyList(), listOf(newShift), customTitle = title, isNested = true)
                            } else {
                                 showShiftDetailDialog(listOf(newShift), emptyList(), customTitle = title, isNested = true)
                            }
                        }
                    }
                )
                
                scrollView.post {
                    val focusX = result.second
                    if (focusX != null) {
                        val screenWidth = scrollView.width
                        scrollView.scrollTo(focusX - screenWidth / 2, 0)
                    }
                }
            }
        }
        
        dialog.show()
    }

    private fun addShiftCard(container: LinearLayout, shift: Shift, isAvailable: Boolean, hideCoworkers: Boolean = false) {
        val cardView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_shift_detail_card, container, false)

        val shiftDateTime = cardView.findViewById<TextView>(R.id.shiftDateTime)
        val shiftPosition = cardView.findViewById<TextView>(R.id.shiftPosition)
                val postedByText = cardView.findViewById<TextView>(R.id.postedByText)
                val coworkersHeaderWrapper = cardView.findViewById<View>(R.id.coworkersHeaderWrapper)
                val expandCoworkersButton = cardView.findViewById<View>(R.id.expandCoworkersButton)
                val coworkersContainer = cardView.findViewById<LinearLayout>(R.id.coworkersContainer)
                val chartScrollView = cardView.findViewById<android.widget.HorizontalScrollView>(R.id.coworkersChartScrollView)
                val chartContainer = cardView.findViewById<RelativeLayout>(R.id.coworkersChartContainer)
                val pickupAttemptsText = cardView.findViewById<TextView>(R.id.pickupAttemptsText)
                val pickupRequestsContainer = cardView.findViewById<LinearLayout>(R.id.pickupRequestsContainer)
                val shiftLocation = cardView.findViewById<TextView>(R.id.shiftLocation)
                val actionButton = cardView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cardActionButton)
        
                try {
                    val startDateTime = LocalDateTime.parse(shift.startDateTime)
                    val endDateTime = LocalDateTime.parse(shift.endDateTime)
                    val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
                    val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
                    shiftDateTime.text = "${startDateTime.format(dayFormatter)} ${startDateTime.format(timeFormatter)} - ${endDateTime.format(timeFormatter)}"
                } catch (e: Exception) {
                    shiftDateTime.text = "Unknown date"
                }
        
                val workstationId = shift.workstationId ?: shift.workstationCode ?: ""
                val workstationName = getWorkstationDisplayName(workstationId, shift.workstationName)
                shiftPosition.text = workstationName
        
                if (isAvailable) {
                    val trackItem = scheduleData?.track?.find { it.primaryShiftRequest?.shift?.shiftId == shift.shiftId && it.primaryShiftRequest?.state == "AVAILABLE" }
                    if (trackItem != null) {
                        val requester = trackItem.primaryShiftRequest
                        if (requester != null) {
                            postedByText.text = "Posted by ${getEmployeeName(requester.requesterId)} ${getTimeAgo(requester.requestedAt)}"
                            postedByText.visibility = View.VISIBLE
                        }
        
                        val myPickupRequest = trackItem.relatedShiftRequests?.find { it.requesterId == authManager.getUserId() }
                        if (myPickupRequest != null && (myPickupRequest.state == "PENDING" || myPickupRequest.state == "APPROVED")) {
                            postedByText.text = "Status: Pickup Requested"
                            postedByText.visibility = View.VISIBLE
                            actionButton.visibility = View.VISIBLE
                            actionButton.text = "Cancel Pickup"
                            actionButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                            actionButton.setOnClickListener {
                                showConfirmationDialog("Cancel Pickup", "Are you sure you want to cancel your pickup request?") {
                                    performCancelPickup(myPickupRequest.requestId, shift)
                                }
                            }
                        } else {
                            actionButton.visibility = View.VISIBLE
                            actionButton.text = "Pick Up"
                            actionButton.setBackgroundColor(resources.getColor(R.color.work_day_green, null))
                            actionButton.setOnClickListener {
                                showConfirmationDialog("Pick Up Shift", "Are you sure you want to pick up this shift?") {
                                    performPickup(shift, trackItem.primaryShiftRequest?.requestId)
                                }
                            }
                        }
                    }
                } else {
                    val isFuture = try { LocalDateTime.parse(shift.startDateTime).isAfter(LocalDateTime.now()) } catch (e: Exception) { true }
                    if (isFuture) {
                        val latestActivePost = scheduleData?.track?.filter {
                            it.primaryShiftRequest?.shift?.shiftId == shift.shiftId &&
                            it.primaryShiftRequest?.state == "AVAILABLE"
                        }?.maxByOrNull { it.primaryShiftRequest?.requestedAt ?: "" }
        
                        if (latestActivePost != null) {
                            postedByText.text = "Status: Posted for Pickup"
                            postedByText.visibility = View.VISIBLE
                            
                            if (latestActivePost.primaryShiftRequest?.requesterId == authManager.getUserId()) {
                                actionButton.visibility = View.VISIBLE
                                actionButton.text = "Cancel Post"
                                actionButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                                actionButton.setOnClickListener {
                                    showConfirmationDialog("Cancel Post", "Are you sure you want to cancel this post?") {
                                        performCancelPost(latestActivePost.primaryShiftRequest?.requestId, shift)
                                    }
                                }
                            } else {
                                actionButton.visibility = View.GONE
                            }
                        } else {
                            if (shift.employeeId == authManager.getUserId()) {
                                actionButton.visibility = View.VISIBLE
                                actionButton.text = "Post"
                                actionButton.setBackgroundColor(resources.getColor(R.color.work_day_green, null))
                                actionButton.setOnClickListener {
                                    showConfirmationDialog("Post Shift", "Are you sure you want to post your shift?") {
                                        performPostShift(shift)
                                    }
                                }
                            } else {
                                actionButton.visibility = View.GONE
                            }
                        }
                    }
                }
        
                // Coworkers Chart Loading
                if (!hideCoworkers && shift.cafeNumber != null && shift.companyCode != null &&
                    shift.startDateTime != null && shift.endDateTime != null) {
        
                    val shiftId = shift.shiftId ?: "${shift.startDateTime}-${shift.workstationId ?: shift.workstationCode}"
                    
                    // Function to update chart
                    fun updateChart(teamMembers: List<TeamMember>) {
                        val myId = authManager.getUserId()
                        
                        // Merge all shifts for complete view (Me + Available + Team)
                        val myShifts = scheduleData?.currentShifts ?: emptyList()
                        val availableTracks = scheduleData?.track ?: emptyList()
                        val employeeInfo = scheduleData?.employeeInfo ?: emptyList()
                        
                        // Reuse merge logic (locally implemented)
                        val mergedMembers = mergeData(teamMembers, myShifts, availableTracks, employeeInfo)
                        
                        val coworkerShifts = findCoworkerShifts(shift, mergedMembers, myId)
                        
                        if (coworkerShifts.isNotEmpty()) {
                            coworkersHeaderWrapper.visibility = View.VISIBLE
                            coworkersContainer.visibility = View.GONE // Legacy
                            chartScrollView.visibility = View.VISIBLE
                            
                            val daySchedule = DaySchedule(LocalDate.now(), coworkerShifts)
                            val shiftStart = try { LocalDateTime.parse(shift.startDateTime) } catch(e: Exception) { null }
                            val shiftEnd = try { LocalDateTime.parse(shift.endDateTime) } catch(e: Exception) { null }
                            
                            chartScrollView.post {
                                val width = chartScrollView.width
                                val safeWidth = if (width > 0) width else resources.displayMetrics.widthPixels - 110.dpToPx() // Approx padding (16+20+16 = 52 * 2 = 104)
        
                                ChartRenderer.drawChart(
                                    requireContext(),
                                    chartContainer,
                                    daySchedule,
                                    isExpanded = false,
                                    containerWidth = safeWidth,
                                    fixedStartTime = shiftStart,
                                    fixedEndTime = shiftEnd,
                                    listener = object : ScheduleInteractionListener {
                                        override fun onExpandClick(day: DaySchedule) {}
                                        override fun onShiftClick(clickedShift: EnrichedShift) {
                                            // Recursion logic
                                            if (clickedShift.shift.shiftId != shift.shiftId?.toLongOrNull()) {
                                                val newShift = clickedShift.shift.toShift()
                                                val title = if (clickedShift.isAvailable) {
                                                    "Available Shift"
                                                } else {
                                                    "${clickedShift.firstName} ${clickedShift.lastName ?: ""}".trim()
                                                }
                                                if (clickedShift.isAvailable) {
                                                     showShiftDetailDialog(emptyList(), listOf(newShift), customTitle = title, isNested = true)
                                                } else {
                                                     showShiftDetailDialog(listOf(newShift), emptyList(), customTitle = title, isNested = true)
                                                }
                                            }
                                        }
                                    },
                                    fitToWidth = true
                                )
                            }
        
                            expandCoworkersButton.setOnClickListener {
                                val day = try { LocalDate.parse(shift.startDateTime?.substring(0, 10)) } catch (e: Exception) { LocalDate.now() }
                                val allShiftsForDay = mutableListOf<EnrichedShift>()
                                
                                mergedMembers.forEach { tm ->
                                    val isMe = tm.associate?.employeeId == myId
                                    val isAvailable = tm.associate?.employeeId == "AVAILABLE_SHIFT"
                                    val firstName = tm.associate?.firstName ?: "Unknown"
                                    val lastName = tm.associate?.lastName
                                    
                                    tm.shifts?.forEach { s ->
                                        try {
                                            if (s.startDateTime?.startsWith(day.toString()) == true) {
                                                val cafeInfo = scheduleData?.cafeList?.firstOrNull { 
                                                    it.departmentName?.contains(s.cafeNumber ?: "") == true 
                                                } ?: scheduleData?.cafeList?.firstOrNull()
                                                
                                                val location = cafeInfo?.let { cafe ->
                                                    val address = cafe.address
                                                    "#${s.cafeNumber ?: ""} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
                                                } ?: "#${s.cafeNumber ?: ""}"

                                                allShiftsForDay.add(
                                                    EnrichedShift(
                                                        shift = s,
                                                        firstName = firstName,
                                                        lastName = lastName,
                                                        isMe = isMe,
                                                        isAvailable = isAvailable,
                                                        location = location
                                                    )
                                                )
                                            }
                                        } catch(e: Exception) {}
                                    }
                                }
                                
                                                        showDayScheduleDialog(DaySchedule(day, allShiftsForDay), shift)
                    }
                }
            }

            val cachedMembers = scheduleCache.getTeamMembers(shiftId)
            if (cachedMembers != null) {
                updateChart(cachedMembers)
            }
        
                    lifecycleScope.launch {
                        try {
                                                val startOfDay = LocalDateTime.parse(shift.startDateTime)
                                                    .with(java.time.LocalTime.MIN)
                                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                                
                                                val endOfDay = LocalDateTime.parse(shift.startDateTime)
                                                    .with(java.time.LocalTime.MAX)
                                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            
                                                val teamMembers = apiService.getTeamMembers(
                                                    shift.cafeNumber,
                                                    shift.companyCode,
                                                    startOfDay,
                                                    endOfDay
                                                )        
                            if (teamMembers != null) {
                                scheduleCache.saveTeamMembers(shiftId, teamMembers)
                                updateChart(teamMembers)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    coworkersHeaderWrapper.visibility = View.GONE
                    coworkersContainer.visibility = View.GONE
                    chartScrollView.visibility = View.GONE
                }

        if (isAvailable) {
            val trackItem = scheduleData?.track?.find { it.primaryShiftRequest?.shift?.shiftId == shift.shiftId && it.primaryShiftRequest?.state == "AVAILABLE" }
            if (trackItem != null) {
                val pendingRequests = trackItem.relatedShiftRequests?.filter {
                    it.state == "PENDING"
                } ?: emptyList()

                pickupAttemptsText.text = "Pickup Requests (${pendingRequests.size})"
                pickupAttemptsText.visibility = View.VISIBLE

                if (pendingRequests.isNotEmpty()) {
                    pickupRequestsContainer.visibility = View.VISIBLE
                    pickupRequestsContainer.removeAllViews()

                    pendingRequests.forEach { request ->
                        val requesterName = getEmployeeName(request.requesterId)
                        val timeAgo = getTimeAgo(request.requestedAt)
                        val requestView = TextView(requireContext()).apply {
                            text = "• $requesterName - $timeAgo"
                            textSize = 13f
                            setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
                            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                        }
                        pickupRequestsContainer.addView(requestView)
                    }
                }
            }
        }

        val cafeInfo = scheduleData?.cafeList?.firstOrNull { 
            it.departmentName?.contains(shift.cafeNumber ?: "") == true 
        } ?: scheduleData?.cafeList?.firstOrNull()
        
        val location = cafeInfo?.let { cafe ->
            val address = cafe.address
            "#${shift.cafeNumber ?: ""} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
        } ?: ""
        shiftLocation.text = location

        container.addView(cardView)
    }
    
    // Copy of mergeData from ScheduleFragment
    private fun mergeData(
        teamMembers: List<TeamMember>, 
        myShifts: List<Shift>, 
        tracks: List<com.anonymousassociate.betterpantry.models.TrackItem>,
        employeeInfo: List<com.anonymousassociate.betterpantry.models.EmployeeInfo>
    ): List<TeamMember> {
        val myId = authManager.getUserId()
        
        // 1. My Shifts
        val myTeamShifts = myShifts.map { s ->
            TeamShift(
                shiftId = s.shiftId?.toLongOrNull(),
                startDateTime = s.startDateTime,
                endDateTime = s.endDateTime,
                workstationId = s.workstationId ?: s.workstationCode,
                workstationName = s.workstationName,
                workstationCode = s.workstationCode,
                workstationGroupDisplayName = s.workstationGroupDisplayName,
                cafeNumber = s.cafeNumber,
                companyCode = s.companyCode,
                businessDate = s.startDateTime?.substring(0, 10),
                employeeId = myId
            )
        }

        val me = TeamMember(
            associate = Associate(
                employeeId = myId,
                firstName = authManager.getFirstName(),
                lastName = authManager.getLastName(),
                preferredName = authManager.getPreferredName()
            ),
            shifts = myTeamShifts
        )

        // 2. Available Shifts (from track)
        val availableMembers = tracks
            .filter { it.type == "AVAILABLE" }
            .distinctBy { it.primaryShiftRequest?.shift?.shiftId } // Deduplicate
            .filter { 
                val state = it.primaryShiftRequest?.state
                state == "AVAILABLE"
            }
            .mapNotNull { track ->
            val s = track.primaryShiftRequest?.shift
            val req = track.primaryShiftRequest
            if (s != null) {
                 val ts = TeamShift(
                    shiftId = s.shiftId?.toLongOrNull(),
                    startDateTime = s.startDateTime,
                    endDateTime = s.endDateTime,
                    workstationId = s.workstationId ?: s.workstationCode,
                    workstationName = s.workstationName,
                    workstationCode = s.workstationCode,
                    workstationGroupDisplayName = s.workstationGroupDisplayName,
                    cafeNumber = s.cafeNumber,
                    companyCode = s.companyCode,
                    businessDate = s.startDateTime?.substring(0, 10),
                    employeeId = "AVAILABLE_SHIFT",
                    managerNotes = req?.managerNotes,
                    requesterName = getEmployeeName(req?.requesterId, employeeInfo),
                    requestId = req?.requestId
                )
                TeamMember(
                    associate = Associate(
                        employeeId = "AVAILABLE_SHIFT",
                        firstName = "AVAILABLE",
                        lastName = "PICK UP",
                        preferredName = "Available"
                    ),
                    shifts = listOf(ts)
                )
            } else null
        }

        val availableShiftIds = availableMembers
            .flatMap { it.shifts ?: emptyList() }
            .mapNotNull { it.shiftId }
            .toSet()

        val filteredTeam = teamMembers
            .filter { it.associate?.employeeId != myId }
            .map { member ->
                val cleanShifts = member.shifts?.filter { shift ->
                    shift.shiftId !in availableShiftIds
                }
                member.copy(shifts = cleanShifts)
            }
            .filter { !it.shifts.isNullOrEmpty() } 
        
        return filteredTeam + me + availableMembers
    }

    // Helper method adapted for HomeFragment (Shift object)
    private fun findCoworkerShifts(targetShift: Shift, teamMembers: List<TeamMember>, myId: String?): List<EnrichedShift> {
        val coworkerShifts = mutableListOf<EnrichedShift>()
        try {
            val myStart = LocalDateTime.parse(targetShift.startDateTime)
            val myEnd = LocalDateTime.parse(targetShift.endDateTime)
            
            teamMembers.forEach { tm: TeamMember ->
                val isMe = tm.associate?.employeeId == myId
                
                val isAvailable = tm.associate?.employeeId == "AVAILABLE_SHIFT"
                val firstName = tm.associate?.firstName ?: "Unknown"
                val lastName = tm.associate?.lastName
                
                tm.shifts?.forEach { s: TeamShift ->
                    try {
                        val sStart = LocalDateTime.parse(s.startDateTime)
                        val sEnd = LocalDateTime.parse(s.endDateTime)
                        
                        if (sStart.isBefore(myEnd) && sEnd.isAfter(myStart)) {
                            coworkerShifts.add(
                                EnrichedShift(
                                    shift = s,
                                    firstName = firstName,
                                    lastName = lastName,
                                    isMe = isMe,
                                    isAvailable = isAvailable
                                )
                            )
                        }
                    } catch(e: Exception) {}
                }
            }
        } catch (e: Exception) { }
        return coworkerShifts.distinctBy { it.shift.shiftId }
    }
    
    private fun TeamShift.toShift(): Shift {
        return Shift(
            businessDate = this.businessDate,
            startDateTime = this.startDateTime,
            endDateTime = this.endDateTime,
            workstationId = this.workstationId,
            workstationName = this.workstationName,
            workstationGroupDisplayName = this.workstationGroupDisplayName,
            cafeNumber = this.cafeNumber,
            companyCode = this.companyCode,
            employeeId = this.employeeId,
            shiftId = this.shiftId?.toString(),
            workstationCode = this.workstationCode
        )
    }

    private fun reloadShiftDetails(originalShift: Shift) {
        if (detailDialog?.isShowing != true) return

        lifecycleScope.launch {
            try {
                val newSchedule = apiService.getSchedule(30)
                if (newSchedule != null) {
                    scheduleData = newSchedule
                    scheduleCache.saveSchedule(newSchedule)
                    displaySchedule(newSchedule)
                    updateTimestamp(System.currentTimeMillis())

                    val date = LocalDate.parse(originalShift.startDateTime?.substring(0, 10))
                    val myShiftsOnDate = newSchedule.currentShifts?.filter {
                        LocalDate.parse(it.startDateTime?.substring(0, 10)) == date
                    } ?: emptyList()
                    val availableShiftsOnDate = newSchedule.track?.filter { track ->
                        track.type == "AVAILABLE" && track.primaryShiftRequest?.state == "AVAILABLE"
                    }?.mapNotNull { it.primaryShiftRequest?.shift }?.filter {
                        LocalDate.parse(it.startDateTime?.substring(0, 10)) == date
                    }?.distinctBy { it.shiftId }?.sortedBy { it.startDateTime } ?: emptyList()

                    detailDialog?.let { dialog ->
                        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
                        val shiftsContainer = dialog.findViewById<LinearLayout>(R.id.shiftsContainer)
                        
                        var titleText = "Shift Details"
                        var hideCoworkersForMyShifts = false
                        var hideCoworkersForAvailable = false

                        if (myShiftsOnDate.size == 1 && availableShiftsOnDate.isEmpty()) {
                            val shift = myShiftsOnDate[0]
                            if (shift.employeeId != authManager.getUserId()) {
                                titleText = getEmployeeName(shift.employeeId)
                                hideCoworkersForMyShifts = true
                            } else {
                                titleText = "Shift Details"
                            }
                        } else if (availableShiftsOnDate.size == 1 && myShiftsOnDate.isEmpty()) {
                            titleText = "Available Shift"
                        }
                        
                        dialogTitle.text = titleText
                        shiftsContainer.removeAllViews()

                        myShiftsOnDate.forEach { shift ->
                            addShiftCard(shiftsContainer, shift, isAvailable = false, hideCoworkers = hideCoworkersForMyShifts)
                        }
                        
                        val fromCalendarClick = myShiftsOnDate.isEmpty() && availableShiftsOnDate.isNotEmpty()
                        val showAsTitle = fromCalendarClick && myShiftsOnDate.isEmpty() && availableShiftsOnDate.isNotEmpty()
                        val showAsSeparator = myShiftsOnDate.isNotEmpty() && availableShiftsOnDate.isNotEmpty()

                        if (showAsTitle || showAsSeparator) {
                            val separator = TextView(requireContext()).apply {
                                text = "AVAILABLE SHIFTS"
                                textSize = 18f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(resources.getColor(R.color.work_day_green, null))
                                letterSpacing = 0.05f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    gravity = android.view.Gravity.CENTER
                                    topMargin = if (showAsTitle) 0 else 24.dpToPx()
                                    bottomMargin = 16.dpToPx()
                                }
                            }
                            shiftsContainer.addView(separator)
                        }

                        availableShiftsOnDate.forEach { shift ->
                            addShiftCard(shiftsContainer, shift, isAvailable = true, hideCoworkers = hideCoworkersForAvailable)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        if (!isAdded) return
        val confirmDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onConfirm() }
            .setNegativeButton("No", null)
            .create()
        confirmDialog.show()
    }

    private fun performPickup(shift: Shift, primaryRequestId: String?) {
        if (primaryRequestId == null) return
        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("associateResponse", "Accepted")
                    put("requestId", primaryRequestId)
                    put("shiftId", shift.shiftId?.toLongOrNull() ?: 0)
                    val receiveAssociate = org.json.JSONObject().apply {
                        put("firstName", authManager.getFirstName())
                        put("lastName", authManager.getLastName())
                        put("preferredName", authManager.getPreferredName())
                        put("employeeId", authManager.getUserId())
                    }
                    put("receiveAssociate", receiveAssociate)
                }
                val success = apiService.acceptShiftPickup(payload.toString())
                if (success) {
                    reloadShiftDetails(shift)
                    checkNotifications()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun performCancelPickup(requestId: String?, shift: Shift) {
        if (requestId == null) return

        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("requestId", requestId.toLongOrNull() ?: 0)
                    val giveAssociate = org.json.JSONObject().apply {
                        put("firstName", authManager.getFirstName())
                        put("lastName", authManager.getLastName())
                        put("preferredName", authManager.getPreferredName())
                        put("employeeId", authManager.getUserId())
                    }
                    put("giveAssociate", giveAssociate)
                }
                
                println("DEBUG: HomeFragment performing cancel pickup with payload: $payload")

                val responseCode = apiService.cancelPostShift(payload.toString())
                if (responseCode in 200..299) {
                    reloadShiftDetails(shift)
                    checkNotifications()
                } else if (responseCode == 500) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "There was an error with code 500, preventing the pickup from being canceled",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Failed to cancel pickup (Code: $responseCode)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun performCancelPost(requestId: String?, shift: Shift) {
        if (requestId == null) return

        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("requestId", requestId.toLongOrNull() ?: 0)
                    val giveAssociate = org.json.JSONObject().apply {
                        put("firstName", authManager.getFirstName())
                        put("lastName", authManager.getLastName())
                        put("preferredName", authManager.getPreferredName())
                        put("employeeId", authManager.getUserId())
                    }
                    put("giveAssociate", giveAssociate)
                }
                
                println("DEBUG: HomeFragment performing cancel post with payload: $payload")

                val responseCode = apiService.cancelPostShift(payload.toString())
                if (responseCode in 200..299) {
                    reloadShiftDetails(shift)
                    checkNotifications()
                } else if (responseCode == 500) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "There was an error with code 500, preventing the post from being canceled",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Failed to cancel post (Code: $responseCode)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun performPostShift(shift: Shift) {
        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("cafeNo", shift.cafeNumber?.toIntOrNull() ?: 0)
                    put("companyCode", shift.companyCode)
                    
                    val giveAssociate = org.json.JSONObject().apply {
                        put("firstName", authManager.getFirstName())
                        put("lastName", authManager.getLastName())
                        put("preferredName", authManager.getPreferredName())
                        put("employeeId", authManager.getUserId())
                    }
                    put("giveAssociate", giveAssociate)
                    
                    val giveShift = org.json.JSONObject().apply {
                        put("shiftId", shift.shiftId?.toLongOrNull() ?: 0)
                        put("startDateTime", shift.startDateTime)
                        put("endDateTime", shift.endDateTime)
                    }
                    put("giveShift", giveShift)
                }
                
                val success = apiService.postShift(payload.toString())
                if (success) {
                    reloadShiftDetails(shift)
                    checkNotifications()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getEmployeeName(employeeId: String?, infoList: List<com.anonymousassociate.betterpantry.models.EmployeeInfo>? = null): String {
        if (employeeId == null) return "Unknown"
        val list = infoList ?: scheduleData?.employeeInfo
        val employee = list?.find { it.employeeId == employeeId }
        return if (employee != null) {
            "${employee.firstName ?: ""} ${employee.lastName ?: ""}".trim().ifEmpty { "Unknown" }
        } else {
            "Unknown"
        }
    }

    private fun getWorkstationDisplayName(workstationId: String, fallbackName: String?): String {
        val customNames = mapOf(
            "QC_2" to "QC 2",
            "1ST_CASHIER_1" to "Cashier 1",
            "SANDWICH_2" to "Sandwich 2",
            "SANDWICH_1" to "Sandwich 1",
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
            "1ST_DR" to "Dining Room"
        )
        var name = customNames[workstationId]
        if (name == null && fallbackName != null) {
            name = customNames[fallbackName]
        }
        return name ?: fallbackName ?: "Unknown"
    }

    private fun getTimeAgo(requestedAt: String?): String {
        if (requestedAt == null) return ""
        return try {
            val requestTime = java.time.Instant.parse(requestedAt)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(requestTime, now)
            val minutes = duration.toMinutes()
            val safeMinutes = if (minutes < 0) 0 else minutes
            when {
                safeMinutes < 60 -> "$safeMinutes minute${if (safeMinutes != 1L) "s" else ""} ago"
                safeMinutes < 1440 -> {
                    val hours = safeMinutes / 60
                    "$hours hour${if (hours != 1L) "s" else ""} ago"
                }
                else -> {
                    val days = safeMinutes / 1440
                    "$days day${if (days != 1L) "s" else ""} ago"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    inner class DateDividerItemDecoration(context: android.content.Context) : RecyclerView.ItemDecoration() {
        private val paint = android.graphics.Paint().apply {
            color = androidx.core.content.ContextCompat.getColor(context, R.color.calendar_border)
            strokeWidth = 1.dpToPx().toFloat()
        }

        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val adapter = parent.adapter as? ShiftAdapter ?: return
            
            if (position < adapter.shifts.size - 1) {
                val currentShift = adapter.shifts[position]
                val nextShift = adapter.shifts[position + 1]
                
                if (!isSameDay(currentShift.startDateTime, nextShift.startDateTime)) {
                    outRect.bottom = 28.dpToPx()
                }
            }
        }

        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter as? ShiftAdapter ?: return
            val left = parent.paddingLeft
            val right = parent.width - parent.paddingRight

            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                
                if (position != RecyclerView.NO_POSITION && position < adapter.shifts.size - 1) {
                    val currentShift = adapter.shifts[position]
                    val nextShift = adapter.shifts[position + 1]

                    if (!isSameDay(currentShift.startDateTime, nextShift.startDateTime)) {
                        val params = child.layoutParams as RecyclerView.LayoutParams
                        val top = child.bottom + params.bottomMargin + 8.dpToPx()
                        c.drawLine(left.toFloat(), top.toFloat(), right.toFloat(), top.toFloat(), paint)
                    }
                }
            }
        }

        private fun isSameDay(dateStr1: String?, dateStr2: String?): Boolean {
            if (dateStr1 == null || dateStr2 == null) return false
            return try {
                val d1 = LocalDate.parse(dateStr1.substring(0, 10))
                val d2 = LocalDate.parse(dateStr2.substring(0, 10))
                d1 == d2
            } catch (e: Exception) {
                false
            }
        }
    }
}

package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.anonymousassociate.betterpantry.AuthManager
import com.anonymousassociate.betterpantry.PantryApiService
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.models.Associate
import com.anonymousassociate.betterpantry.models.Shift
import com.anonymousassociate.betterpantry.models.TeamShift
import com.anonymousassociate.betterpantry.models.TeamMember
import com.anonymousassociate.betterpantry.models.ScheduleData
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ScheduleFragment : Fragment(), ScheduleInteractionListener {

    private lateinit var authManager: AuthManager
    private lateinit var apiService: PantryApiService
    private lateinit var scheduleCache: ScheduleCache
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingText: TextView
    private lateinit var updatedText: TextView
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    
    private var scheduleData: ScheduleData? = null
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateTimeRunnable: Runnable? = null

    private val hourWidthDp = 60 
    private val barHeightDp = 32
    private val laneSpacingDp = 4
    private val timeHeaderHeightDp = 24

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authManager = AuthManager(requireContext())
        apiService = PantryApiService(authManager)
        scheduleCache = ScheduleCache(requireContext())

        recyclerView = view.findViewById(R.id.scheduleRecyclerView)
        loadingText = view.findViewById(R.id.loadingText)
        updatedText = view.findViewById(R.id.updatedText)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        val settingsButton: ImageButton = view.findViewById(R.id.settingsButton)
        
        val nestedScrollView = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.nestedScrollView)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(nestedScrollView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            
            // Adjust refresh indicator position
            val refreshTarget = bars.top + (32 * resources.displayMetrics.density).toInt()
            swipeRefreshLayout.setProgressViewOffset(false, 0, refreshTarget)
            
            insets
        }

        recyclerView.layoutManager = LinearLayoutManager(context)
        
        // Setup SwipeRefresh
        val greenColor = ContextCompat.getColor(requireContext(), R.color.work_day_green)
        val backgroundColor = ContextCompat.getColor(requireContext(), R.color.card_background_color)
        swipeRefreshLayout.setColorSchemeColors(greenColor)
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(backgroundColor)
        swipeRefreshLayout.setOnRefreshListener {
            loadScheduleData(forceRefresh = true)
        }
        
        settingsButton.setOnClickListener { showSettingsMenu(it) }

        // Load cached first
        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedSchedule != null) {
            scheduleData = cachedSchedule
            displayScheduleFromData(cachedSchedule)
            updateTimestamp(scheduleCache.getLastUpdateTime())
            startUpdateTimer()
        }

        loadScheduleData()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdateTimer()
    }

    private fun loadScheduleData(forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            val lastUpdate = scheduleCache.getLastUpdateTime()
            val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
            if (scheduleData != null && lastUpdate > fiveMinutesAgo) {
                swipeRefreshLayout.isRefreshing = false
                return
            }
        }

        if (scheduleData == null) {
            loadingText.visibility = View.VISIBLE
        }

        // Ensure spinner shows up reliably
        swipeRefreshLayout.post {
            swipeRefreshLayout.isRefreshing = true
        }
        
        lifecycleScope.launch {
            try {
                // Update from Network
                val mySchedule = try { apiService.getSchedule(30) } catch(e: Exception) { null }
                if (mySchedule != null) {
                    scheduleCache.saveSchedule(mySchedule)
                    scheduleData = mySchedule
                    if (isAdded) updateTimestamp(System.currentTimeMillis())
                    
                    fetchTeamMembers(mySchedule)
                } else {
                    if (forceRefresh && isAdded) {
                        // Toast?
                    }
                }
                
                if (scheduleData == null && isAdded) {
                     loadingText.text = "Failed to load schedule."
                } else if (isAdded) {
                     loadingText.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isAdded) {
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }
    
    private suspend fun fetchTeamMembers(mySchedule: com.anonymousassociate.betterpantry.models.ScheduleData) {
        val sampleShift = mySchedule.currentShifts?.firstOrNull { 
            it.cafeNumber != null && it.companyCode != null 
        }
        
        if (sampleShift == null) return
        
        val cafeNo = sampleShift.cafeNumber!!
        val companyCode = sampleShift.companyCode!!
        
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val startStr = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).format(formatter)
        val endStr = LocalDateTime.now().plusDays(14).withHour(23).withMinute(59).withSecond(59).format(formatter)

        val teamMembers = try {
            apiService.getTeamMembers(cafeNo, companyCode, startStr, endStr)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (teamMembers != null) {
            scheduleCache.saveTeamSchedule(teamMembers)
            if (isAdded) {
                displayScheduleFromData(mySchedule, teamMembers)
                loadingText.visibility = View.GONE
            }
        }
    }
    
    private fun displayScheduleFromData(
        mySchedule: com.anonymousassociate.betterpantry.models.ScheduleData, 
        teamMembers: List<TeamMember>? = null
    ) {
        val members = teamMembers ?: scheduleCache.getTeamSchedule() ?: return
        
        val myShifts = mySchedule.currentShifts ?: emptyList()
        val availableTracks = mySchedule.track ?: emptyList()
        val employeeInfo = mySchedule.employeeInfo ?: emptyList()

        val mergedMembers = mergeData(members, myShifts, availableTracks, employeeInfo)
        
        val startDate = LocalDate.now()
        val endDate = startDate.plusDays(14)
        processAndDisplay(mergedMembers, startDate, endDate)
    }

    private fun mergeData(
        teamMembers: List<TeamMember>, 
        myShifts: List<Shift>, 
        tracks: List<com.anonymousassociate.betterpantry.models.TrackItem>,
        employeeInfo: List<com.anonymousassociate.betterpantry.models.EmployeeInfo>
    ): List<TeamMember> {
        val myId = authManager.getUserId()
        
        // 1. My Shifts
        val myTeamShifts = myShifts.map {
            TeamShift(
                shiftId = it.shiftId?.toLongOrNull(),
                startDateTime = it.startDateTime,
                endDateTime = it.endDateTime,
                workstationId = it.workstationId ?: it.workstationCode,
                workstationName = it.workstationName,
                workstationCode = it.workstationCode,
                workstationGroupDisplayName = it.workstationGroupDisplayName,
                cafeNumber = it.cafeNumber,
                companyCode = it.companyCode,
                businessDate = it.startDateTime?.substring(0, 10),
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
            .mapNotNull {
            val s = it.primaryShiftRequest?.shift
            val req = it.primaryShiftRequest
            if (s != null) {
                 val myRequest = it.relatedShiftRequests?.find { 
                     it.requesterId == myId && (it.state == "PENDING" || it.state == "APPROVED")
                 }

                 val pendingRequests = it.relatedShiftRequests
                     ?.filter { it.state == "PENDING" }
                     ?.map {
                         val name = getEmployeeName(it.requesterId, employeeInfo)
                         val timeAgo = getTimeAgo(it.requestedAt)
                         "$name - $timeAgo"
                     }

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
                    // Extra fields
                    managerNotes = req?.managerNotes,
                    requesterName = getEmployeeName(req?.requesterId, employeeInfo),
                    requestedAt = req?.requestedAt,
                    requestId = req?.requestId,
                    myPickupRequestId = myRequest?.requestId,
                    pickupRequests = pendingRequests
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

    private fun getEmployeeName(id: String?, infoList: List<com.anonymousassociate.betterpantry.models.EmployeeInfo>?): String {
        if (id == null) return "Unknown"
        val info = infoList?.find { it.employeeId == id }
        return if (info != null) "${info.firstName} ${info.lastName}" else "Coworker"
    }

    private fun processAndDisplay(
        teamMembers: List<TeamMember>, 
        startDate: LocalDate, 
        endDate: LocalDate
    ) {
        val days = mutableListOf<DaySchedule>()
        var currentDate = startDate
        val myId = authManager.getUserId()
        
        while (!currentDate.isAfter(endDate)) {
            val shiftsForDay = mutableListOf<EnrichedShift>()
            teamMembers.forEach { member: TeamMember ->
                val isMe = member.associate?.employeeId == myId
                val isAvailable = member.associate?.employeeId == "AVAILABLE_SHIFT"

                member.shifts?.forEach { shift: TeamShift ->
                    try {
                        val shiftStart = LocalDateTime.parse(shift.startDateTime)
                        if (shiftStart.toLocalDate() == currentDate) {
                            
                            // Find coworkers shifts for the mini-chart
                            val coworkerShifts = if (isAvailable || isMe) {
                                findCoworkerShifts(shift, teamMembers, myId)
                            } else null
                            
                            // Calculate location
                            val cafeInfo = scheduleData?.cafeList?.firstOrNull { 
                                it.departmentName?.contains(shift.cafeNumber ?: "") == true 
                            } ?: scheduleData?.cafeList?.firstOrNull()
                            
                            val location = cafeInfo?.let { cafe ->
                                val address = cafe.address
                                "#${shift.cafeNumber ?: ""} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
                            } ?: "#${shift.cafeNumber ?: ""}"

                            shiftsForDay.add(
                                EnrichedShift(
                                    shift = shift,
                                    firstName = member.associate?.firstName ?: "Unknown",
                                    lastName = member.associate?.lastName,
                                    isMe = isMe,
                                    isAvailable = isAvailable,
                                    managerNotes = shift.managerNotes,
                                    requesterName = shift.requesterName,
                                    requestedAt = shift.requestedAt,
                                    requestId = shift.requestId,
                                    myPickupRequestId = shift.myPickupRequestId,
                                    pickupRequests = shift.pickupRequests,
                                    coworkerShifts = coworkerShifts,
                                    location = location
                                )
                            )
                        }
                    } catch (e: Exception) { }
                }
            }
            if (shiftsForDay.isNotEmpty()) {
                days.add(DaySchedule(currentDate, shiftsForDay.sortedBy { it.shift.startDateTime }))
            }
            currentDate = currentDate.plusDays(1)
        }

        if (recyclerView.adapter is DayScheduleAdapter) {
            (recyclerView.adapter as DayScheduleAdapter).updateData(days)
        } else {
            recyclerView.adapter = DayScheduleAdapter(days, this)
        }
    }

    override fun onExpandClick(day: DaySchedule) {
        showExpandedView(day)
    }

    override fun onShiftClick(shift: EnrichedShift) {
        showShiftDetailsDialog(shift)
    }

    private fun showExpandedView(day: DaySchedule) {
        val fragment = ExpandedScheduleFragment.newInstance(day)
        fragment.show(parentFragmentManager, "ExpandedSchedule")
    }

    private fun showShiftDetailsDialog(enrichedShift: EnrichedShift, isNested: Boolean = false) {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_shift_detail)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val title = dialog.findViewById<TextView>(R.id.dialogTitle)
        val container = dialog.findViewById<LinearLayout>(R.id.shiftsContainer)
        val close = dialog.findViewById<View>(R.id.closeButton)
        
        container.removeAllViews()

        // Inflate item_shift_detail_card for ALL shifts
        val cardView = LayoutInflater.from(requireContext()).inflate(R.layout.item_shift_detail_card, container, false)
        
        val shiftDateTime = cardView.findViewById<TextView>(R.id.shiftDateTime)
        val shiftPosition = cardView.findViewById<TextView>(R.id.shiftPosition)
        val postedByText = cardView.findViewById<TextView>(R.id.postedByText)
        val actionButton = cardView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cardActionButton)
        val shiftLocation = cardView.findViewById<TextView>(R.id.shiftLocation)
        val coworkersHeaderWrapper = cardView.findViewById<View>(R.id.coworkersHeaderWrapper)
        val expandCoworkersButton = cardView.findViewById<View>(R.id.expandCoworkersButton)
        val coworkersContainer = cardView.findViewById<LinearLayout>(R.id.coworkersContainer)
        val chartScrollView = cardView.findViewById<android.widget.HorizontalScrollView>(R.id.coworkersChartScrollView)
        val chartContainer = cardView.findViewById<RelativeLayout>(R.id.coworkersChartContainer)
        val pickupAttemptsText = cardView.findViewById<TextView>(R.id.pickupAttemptsText)
        val pickupRequestsContainer = cardView.findViewById<LinearLayout>(R.id.pickupRequestsContainer)
        
        val s = enrichedShift.shift
        try {
            val start = LocalDateTime.parse(s.startDateTime)
            val end = LocalDateTime.parse(s.endDateTime)
            val formatter = DateTimeFormatter.ofPattern("E M/d")
            val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
            shiftDateTime.text = "${start.format(formatter)} ${start.format(timeFormatter)} - ${end.format(timeFormatter)}"
        } catch(e: Exception) {
            shiftDateTime.text = s.startDateTime
        }
        
        val station = getWorkstationDisplayName(s.workstationId ?: s.workstationCode, s.workstationName)
        shiftPosition.text = station
        
        // Location
        shiftLocation.text = enrichedShift.location ?: "#${s.cafeNumber ?: ""}"
        
        // Posted By / Status
        if (enrichedShift.isAvailable) {
            title.text = "Available Shift"
            if (enrichedShift.myPickupRequestId != null) {
                postedByText.text = "Status: Pickup Requested"
                postedByText.visibility = View.VISIBLE
            } else if (!enrichedShift.requesterName.isNullOrEmpty()) {
                val timeAgo = getTimeAgo(enrichedShift.requestedAt)
                postedByText.text = "Posted by ${enrichedShift.requesterName} $timeAgo"
                postedByText.visibility = View.VISIBLE
            } else {
                postedByText.visibility = View.GONE
            }
        } else {
            title.text = "${enrichedShift.firstName} ${enrichedShift.lastName ?: ""}"
            postedByText.visibility = View.GONE
        }
        
        // Manager Notes
        if (!enrichedShift.managerNotes.isNullOrEmpty()) {
            val existing = if (postedByText.visibility == View.VISIBLE) postedByText.text.toString() + "\n" else ""
            postedByText.text = "${existing}Note: ${enrichedShift.managerNotes}".trim()
            postedByText.visibility = View.VISIBLE
        }
        
        // Coworkers Chart
        // Hide if isNested OR if it's my own shift (per user request)
        val shouldShowChart = !isNested && !enrichedShift.isAvailable && !enrichedShift.isMe && !enrichedShift.coworkerShifts.isNullOrEmpty()
        
        if (shouldShowChart) {
            coworkersHeaderWrapper.visibility = View.VISIBLE
            coworkersContainer.visibility = View.GONE // Hide old list
            chartScrollView.visibility = View.VISIBLE
            
            // Draw Mini Chart
            val shifts = enrichedShift.coworkerShifts
            val daySchedule = DaySchedule(LocalDate.now(), shifts) // Date doesn't matter for rendering relative to start/end
            
            // Determine range
            val shiftStart = try { LocalDateTime.parse(s.startDateTime) } catch(e: Exception) { null }
            val shiftEnd = try { LocalDateTime.parse(s.endDateTime) } catch(e: Exception) { null }
            
            ChartRenderer.drawChart(
                requireContext(),
                chartContainer,
                daySchedule,
                isExpanded = false, // Keep compact
                fixedStartTime = shiftStart,
                fixedEndTime = shiftEnd,
                listener = object : ScheduleInteractionListener {
                    override fun onExpandClick(day: DaySchedule) {}
                    override fun onShiftClick(clickedShift: EnrichedShift) {
                        // Prevent infinite stack of same shift
                        if (clickedShift.shift.shiftId != enrichedShift.shift.shiftId) {
                            showShiftDetailsDialog(clickedShift, isNested = true)
                        }
                    }
                }
            )

            expandCoworkersButton.setOnClickListener {
                val day = try { LocalDate.parse(s.startDateTime?.substring(0, 10)) } catch (e: Exception) { LocalDate.now() }
                
                // Regenerate full day schedule
                val myShifts = scheduleData?.currentShifts ?: emptyList()
                val tracks = scheduleData?.track ?: emptyList()
                val employeeInfo = scheduleData?.employeeInfo ?: emptyList()
                val members = scheduleCache.getTeamSchedule() ?: emptyList()
                val mergedMembers = mergeData(members, myShifts, tracks, employeeInfo)
                
                val allShiftsForDay = mutableListOf<EnrichedShift>()
                val myId = authManager.getUserId()

                mergedMembers.forEach { tm ->
                    val isMe = tm.associate?.employeeId == myId
                    val isAvailable = tm.associate?.employeeId == "AVAILABLE_SHIFT"
                    val firstName = tm.associate?.firstName ?: "Unknown"
                    val lastName = tm.associate?.lastName
                    
                    tm.shifts?.forEach { shift ->
                        try {
                            if (shift.startDateTime?.startsWith(day.toString()) == true) {
                                // Re-enrich
                                val cafeInfo = scheduleData?.cafeList?.firstOrNull { 
                                    it.departmentName?.contains(shift.cafeNumber ?: "") == true 
                                } ?: scheduleData?.cafeList?.firstOrNull()
                                
                                val location = cafeInfo?.let { cafe ->
                                    val address = cafe.address
                                    "#${shift.cafeNumber ?: ""} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
                                } ?: "#${shift.cafeNumber ?: ""}"

                                val myRequest = tracks.find { t -> 
                                    t.primaryShiftRequest?.shift?.shiftId == shift.shiftId.toString()
                                }?.relatedShiftRequests?.find { 
                                     it.requesterId == myId && (it.state == "PENDING" || it.state == "APPROVED")
                                }

                                allShiftsForDay.add(
                                    EnrichedShift(
                                        shift = shift,
                                        firstName = firstName,
                                        lastName = lastName,
                                        isMe = isMe,
                                        isAvailable = isAvailable,
                                        managerNotes = shift.managerNotes,
                                        requesterName = shift.requesterName,
                                        requestedAt = shift.requestedAt,
                                        requestId = shift.requestId,
                                        myPickupRequestId = shift.myPickupRequestId ?: myRequest?.requestId,
                                        location = location
                                    )
                                )
                            }
                        } catch(e: Exception) {}
                    }
                }
                
                showDayScheduleDialog(DaySchedule(day, allShiftsForDay.sortedBy { it.shift.startDateTime }), s)
            }
        } else {
            coworkersHeaderWrapper.visibility = View.GONE
            coworkersContainer.visibility = View.GONE
            chartScrollView.visibility = View.GONE
        }

        // Pickup Requests
        if (enrichedShift.isAvailable) {
            val requests = enrichedShift.pickupRequests ?: emptyList()
            pickupAttemptsText.text = "Pickup Requests (${requests.size})"
            pickupAttemptsText.visibility = View.VISIBLE
            
            if (requests.isNotEmpty()) {
                pickupRequestsContainer.visibility = View.VISIBLE
                pickupRequestsContainer.removeAllViews()
                requests.forEach {
                    val tv = TextView(requireContext()).apply {
                        text = "• $it"
                        textSize = 13f
                        setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    }
                    pickupRequestsContainer.addView(tv)
                }
            } else {
                pickupRequestsContainer.visibility = View.GONE
            }
        } else {
            pickupAttemptsText.visibility = View.GONE
            pickupRequestsContainer.visibility = View.GONE
        }

        // Buttons
        if (enrichedShift.isAvailable) {
            actionButton.visibility = View.VISIBLE
            if (enrichedShift.myPickupRequestId != null) {
                // Cancel Pickup
                actionButton.text = "Cancel Pickup"
                actionButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                actionButton.setOnClickListener {
                    showConfirmationDialog(dialog, "Cancel Pickup", "Are you sure you want to cancel your pickup request?") {
                        performCancelPickup(enrichedShift.myPickupRequestId, dialog)
                    }
                }
            } else {
                // Pick Up
                actionButton.text = "Pick Up"
                actionButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.work_day_green))
                actionButton.setOnClickListener {
                    showConfirmationDialog(dialog, "Pick Up Shift", "Are you sure you want to pick up this shift?") {
                        performPickup(enrichedShift, dialog)
                    }
                }
            }
        } else {
            actionButton.visibility = View.GONE
        }
        
        container.addView(cardView)
        
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
    
    private fun showDayScheduleDialog(daySchedule: DaySchedule, focusShift: TeamShift? = null) {
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
                         val focusTime = if (isToday) {
                             LocalDateTime.now()
                         } else {
                             try { LocalDateTime.parse(focusShift?.startDateTime) } catch(e: Exception) { null }
                         }
                         val focusEndTime = if (isToday) {
                             null
                         } else {
                             try { LocalDateTime.parse(focusShift?.endDateTime) } catch(e: Exception) { null }
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
                            showShiftDetailsDialog(clickedShift, isNested = true)
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

    private fun showConfirmationDialog(parentDialog: Dialog, title: String, message: String, onConfirm: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onConfirm() }
            .setNegativeButton("No", null)
            .create()
            .show()
    }

    private fun performPickup(enrichedShift: EnrichedShift, dialog: Dialog) {
        val requestId = enrichedShift.requestId ?: return
        
        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("associateResponse", "Accepted")
                    put("requestId", requestId)
                    put("shiftId", enrichedShift.shift.shiftId ?: 0)
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
                    dialog.dismiss()
                    loadScheduleData() // Refresh
                } else {
                    android.widget.Toast.makeText(requireContext(), "Failed to pick up shift", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun performCancelPickup(requestId: String?, dialog: Dialog) {
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
                
                val responseCode = apiService.cancelPostShift(payload.toString())
                if (responseCode in 200..299) {
                    dialog.dismiss()
                    loadScheduleData()
                } else {
                     android.widget.Toast.makeText(requireContext(), "Failed to cancel (Code: $responseCode)", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
            ""
        }
    }

    private fun findCoworkerShifts(shift: TeamShift, teamMembers: List<TeamMember>, myId: String?): List<EnrichedShift> {
        val coworkerShifts = mutableListOf<EnrichedShift>()
        try {
            val myStart = LocalDateTime.parse(shift.startDateTime)
            val myEnd = LocalDateTime.parse(shift.endDateTime)
            
            teamMembers.forEach { tm: TeamMember ->
                val isMe = tm.associate?.employeeId == myId
                if (isMe) return@forEach
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
        return coworkerShifts.distinctBy { it.shift.shiftId } // Deduplicate
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

    private fun getWorkstationDisplayName(workstationId: String?, fallbackName: String?): String {
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
            "PEOPLEMANAGEMENT" to "Manager",
            "LABOR_MANAGEMENT" to "Manager",
            "LABORMANAGEMENT" to "Manager",
            "Labor Management" to "Manager"
        )
        if (workstationId != null) {
            val mapped = customNames[workstationId]
            if (mapped != null) return mapped
        }
        return fallbackName ?: workstationId ?: "Unknown"
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
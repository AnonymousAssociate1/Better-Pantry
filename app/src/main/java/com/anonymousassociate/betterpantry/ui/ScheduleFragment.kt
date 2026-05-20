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
import android.widget.AutoCompleteTextView
import android.widget.Toast
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ScheduleFragment : Fragment(), ScheduleInteractionListener {

    private lateinit var authManager: AuthManager
    private val repository by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository }
    private val scheduleCache by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository.let { ScheduleCache(requireContext()) } }
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingContainer: View
    private lateinit var updatedText: TextView
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var settingsPreferences: com.anonymousassociate.betterpantry.SettingsPreferences

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
        settingsPreferences = com.anonymousassociate.betterpantry.SettingsPreferences(requireContext())
        // apiService and scheduleCache from repository logic

        recyclerView = view.findViewById(R.id.scheduleRecyclerView)
        loadingContainer = view.findViewById(R.id.loadingContainer)
        updatedText = view.findViewById(R.id.updatedText)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)

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

        // Load cached first
        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedSchedule != null) {
            scheduleData = cachedSchedule
            displayScheduleFromData(cachedSchedule)
            updateTimestamp()
            startUpdateTimer()
        }

        loadScheduleData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdateTimer()
    }

    fun refreshDataFromCache() {
        if (!isAdded) return
        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedSchedule != null) {
            scheduleData = cachedSchedule
            // Assume we also want to display it
            val cachedTeam = scheduleCache.getTeamSchedule()
            displayScheduleFromData(cachedSchedule, cachedTeam)
            updateTimestamp()
        }
    }

    private fun loadScheduleData(forceRefresh: Boolean = false) {
        // Try cache first
        refreshDataFromCache()

        val isStale = scheduleCache.isScheduleStale()
        val isTeamStale = scheduleCache.isTeamScheduleStale()
        val hasTeamData = scheduleCache.getTeamSchedule() != null

        if (!forceRefresh && !isStale && !isTeamStale && scheduleData != null && hasTeamData) {
            swipeRefreshLayout.isRefreshing = false
            return
        }

        if (scheduleData == null) {
            loadingContainer.visibility = View.VISIBLE
        }

        // Trigger animation immediately for auto-refresh
        swipeRefreshLayout.post {
            swipeRefreshLayout.isRefreshing = true
        }

        lifecycleScope.launch {
            try {
                // Update from Network via Repository
                val mySchedule = repository.getSchedule(forceRefresh) // Handles caching

                if (forceRefresh) {
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        repository.getAvailability(true)
                        repository.getMaxHours(true)
                        repository.getTimeOff(true)
                    }
                }

                if (mySchedule != null) {
                    scheduleData = mySchedule
                    if (isAdded) updateTimestamp()

                    fetchTeamMembers(mySchedule, forceRefresh)
                } else {
                    if (forceRefresh && isAdded) {
                        // Toast?
                    }
                }

                if (scheduleData == null && isAdded) {
                    Toast.makeText(context, "Failed to load schedule.", Toast.LENGTH_SHORT).show()
                } else if (isAdded) {
                    // We hide loading text only after team members attempt (inside fetchTeamMembers or here if it failed)
                    if (scheduleCache.getTeamSchedule() != null) {
                        loadingContainer.visibility = View.GONE
                    }
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

    private var selectedCafe: String? = null

    private fun setupCafeFilterSwitcher(schedule: ScheduleData) {
        val switcherScroll = view?.findViewById<View>(R.id.cafeSwitcherScroll) ?: return
        val chipGroup = view?.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cafeChipGroup) ?: return

        val homeCafe = authManager.getCafeNo()
        val userId = authManager.getUserId()
        val enabledCafeNumbers = settingsPreferences.getEnabledCafeNumbers(
            schedule,
            scheduleCache.getTeamSchedule(),
            homeCafe,
            userId
        )

        val sortedCafeNos = enabledCafeNumbers.sorted()
        if (sortedCafeNos.size <= 1) {
            switcherScroll.visibility = View.GONE
            selectedCafe = sortedCafeNos.firstOrNull() ?: homeCafe
            chipGroup.removeAllViews()
            return
        }

        switcherScroll.visibility = View.VISIBLE

        chipGroup.removeAllViews()

        // Create chip for each cafe
        sortedCafeNos.forEach { cafeNo ->
            val displayName = settingsPreferences.getCafeDisplayName(cafeNo, schedule.cafeList)
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = displayName
                isCheckable = true
                
                val shouldBeChecked = (selectedCafe == cafeNo) || (selectedCafe == null && cafeNo == (sortedCafeNos.firstOrNull { it == homeCafe } ?: sortedCafeNos.firstOrNull()))
                isChecked = shouldBeChecked
                
                if (shouldBeChecked && selectedCafe != cafeNo) {
                    selectedCafe = cafeNo
                }
                
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && selectedCafe != cafeNo) {
                        selectedCafe = cafeNo
                        val currentMembers = scheduleCache.getTeamSchedule()
                        if (currentMembers != null) {
                            displayScheduleFromData(schedule, currentMembers)
                        }
                    }
                }
            }
            chipGroup.addView(chip)
        }
    }

    private suspend fun fetchTeamMembers(mySchedule: com.anonymousassociate.betterpantry.models.ScheduleData, forceRefresh: Boolean) {
        val sampleShift = mySchedule.currentShifts?.firstOrNull {
            it.cafeNumber != null && it.companyCode != null
        }

        if (sampleShift == null) return

        val companyCode = sampleShift.companyCode!!
        val enabledCafeNos = settingsPreferences.getEnabledCafeNumbers(
            mySchedule,
            scheduleCache.getTeamSchedule(),
            authManager.getCafeNo(),
            authManager.getUserId()
        )
        val finalCafes = if (enabledCafeNos.isEmpty()) listOf(sampleShift.cafeNumber!!) else enabledCafeNos

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val startStr = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).format(formatter)
        val endStr = LocalDateTime.now().plusDays(30).withHour(23).withMinute(59).withSecond(59).format(formatter)

        val forceThisBatch = forceRefresh || scheduleCache.isTeamScheduleStale()
        val lastTeamMembers: List<TeamMember>? = coroutineScope {
            finalCafes.map { cNo ->
                async {
                    try {
                        repository.getTeamMembers(cNo, companyCode, startStr, endStr, forceThisBatch)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            }.awaitAll().filterNotNull().lastOrNull()
        }

        if (lastTeamMembers != null || scheduleCache.getTeamSchedule() != null) {
            if (isAdded) {
                displayScheduleFromData(mySchedule, lastTeamMembers ?: scheduleCache.getTeamSchedule())
                loadingContainer.visibility = View.GONE
            }
        }
    }

    private fun displayScheduleFromData(
        mySchedule: com.anonymousassociate.betterpantry.models.ScheduleData,
        teamMembers: List<TeamMember>? = null
    ) {
        val members = teamMembers ?: scheduleCache.getTeamSchedule() ?: return

        setupCafeFilterSwitcher(mySchedule)

        // Clear adapter to avoid showing the old cafe schedule while loading
        if (recyclerView.adapter is DayScheduleAdapter) {
            (recyclerView.adapter as DayScheduleAdapter).updateData(emptyList())
        }

        // Show loading progress bar
        loadingContainer.visibility = View.VISIBLE

        lifecycleScope.launch {
            val startDate = LocalDate.now()
            val endDate = startDate.plusDays(14)

            // Perform heavy processing on background thread
            val days = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                processScheduleData(mySchedule, members, startDate, endDate)
            }

            // Update UI on main thread
            if (recyclerView.adapter is DayScheduleAdapter) {
                (recyclerView.adapter as DayScheduleAdapter).updateData(days)
            } else {
                recyclerView.adapter = DayScheduleAdapter(days, this@ScheduleFragment)
            }
            loadingContainer.visibility = View.GONE
        }
    }

    private suspend fun processScheduleData(
        mySchedule: com.anonymousassociate.betterpantry.models.ScheduleData,
        members: List<TeamMember>,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<DaySchedule> {
        val myShifts = mySchedule.currentShifts ?: emptyList()
        val availableTracks = mySchedule.track ?: emptyList()
        val employeeInfo = mySchedule.employeeInfo ?: emptyList()

        val mergedMembers = mergeData(members, myShifts, availableTracks, employeeInfo)

        val days = mutableListOf<DaySchedule>()
        var currentDate = startDate
        val myId = authManager.getUserId()

        while (!currentDate.isAfter(endDate)) {
            val shiftsForDay = mutableListOf<EnrichedShift>()
            mergedMembers.forEach { member: TeamMember ->
                val isMe = member.associate?.employeeId == myId
                val isAvailable = member.associate?.employeeId == "AVAILABLE_SHIFT"

                member.shifts?.forEach { shift: TeamShift ->
                    try {
                        val shiftStart = LocalDateTime.parse(shift.startDateTime)
                        val shiftCafeNo = shift.cafeNumber ?: selectedCafe ?: ""
                        if (shiftStart.toLocalDate() == currentDate && shiftCafeNo == selectedCafe) {

                            // Find coworkers shifts for the mini-chart
                            val coworkerShifts = if (isAvailable || isMe) {
                                findCoworkerShifts(shift, mergedMembers, myId)
                            } else null

                            val location = settingsPreferences.getCafeDisplayName(shift.cafeNumber, mySchedule.cafeList)

                            shiftsForDay.add(
                                EnrichedShift(
                                    shift = shift,
                                    firstName = settingsPreferences.getCoworkerFirstResolved(member.associate?.employeeId, member.associate?.firstName, member.associate?.preferredName),
                                    lastName = settingsPreferences.getCoworkerLastResolved(member.associate?.employeeId, member.associate?.lastName),
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
        return days
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
            .filter {
                val state = it.primaryShiftRequest?.state
                val isClaimed = it.relatedShiftRequests?.any { r -> r.state == "APPROVED" } == true
                val s = it.primaryShiftRequest?.shift
                val isCafeEnabled = s?.cafeNumber?.let { num -> settingsPreferences.isCafeEnabled(num) } ?: true
                (state == "AVAILABLE" || state == "APPROVED") && !isClaimed && isCafeEnabled
            }
            .sortedByDescending { it.primaryShiftRequest?.requestedAt }
            .distinctBy { it.primaryShiftRequest?.shift?.shiftId } // Deduplicate
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

        // 1. Try Team Cache (Richer data)
        val teamMembers = scheduleCache.getTeamSchedule()
        val associate = teamMembers?.find { it.associate?.employeeId == id }?.associate
        if (associate != null) {
            val first = if (!associate.preferredName.isNullOrEmpty()) {
                associate.preferredName
            } else {
                associate.firstName
            }
            return "$first ${associate.lastName ?: ""}".trim().ifEmpty { "Unknown" }
        }

        // 2. Try infoList (EmployeeInfo)
        val info = infoList?.find { it.employeeId == id }
        if (info != null) {
            return "${info.firstName} ${info.lastName}".trim()
        }

        return "Coworker"
    }

    override fun onExpandClick(day: DaySchedule) {
        showExpandedView(day)
    }

    override fun onShiftClick(shift: EnrichedShift) {
        showShiftDetailsDialog(shift)
    }

    private fun showExpandedView(day: DaySchedule) {
        val fragment = ExpandedScheduleFragment.newInstance(
            day,
            initialCafeNo = day.shifts.firstOrNull()?.shift?.cafeNumber
        )
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
        val shareCoworkersButton = cardView.findViewById<View>(R.id.shareCoworkersButton)
        val coworkersContainer = cardView.findViewById<LinearLayout>(R.id.coworkersContainer)
        val chartScrollView = cardView.findViewById<android.widget.HorizontalScrollView>(R.id.coworkersChartScrollView)
        val chartContainer = cardView.findViewById<RelativeLayout>(R.id.coworkersChartContainer)
        val pickupAttemptsText = cardView.findViewById<TextView>(R.id.pickupAttemptsText)
        val pickupRequestsContainer = cardView.findViewById<LinearLayout>(R.id.pickupRequestsContainer)

        var displayShift = enrichedShift
        val originalS = enrichedShift.shift
        if (settingsPreferences.combineShifts && !enrichedShift.isAvailable && originalS.employeeId != null) {
            val cachedTeam = scheduleCache.getTeamSchedule()
            val myId = authManager.getUserId()
            val myShifts = scheduleData?.currentShifts ?: emptyList()
            val tracks = scheduleData?.track ?: emptyList()
            val employeeInfo = scheduleData?.employeeInfo ?: emptyList()
            val mergedMembers = mergeData(cachedTeam ?: emptyList(), myShifts, tracks, employeeInfo)
            
            val personShifts = mergedMembers.find { it.associate?.employeeId == originalS.employeeId }?.shifts ?: emptyList()
            val sameDayShifts = personShifts.filter { it.businessDate == originalS.businessDate }
            if (sameDayShifts.size > 1) {
                val combined = com.anonymousassociate.betterpantry.utils.ShiftCombiner.combineTeamShifts(sameDayShifts)
                val matchingCombined = combined.find { cs ->
                    cs.shiftId == originalS.shiftId || cs.combinedShifts?.any { it.shiftId == originalS.shiftId } == true
                }
                if (matchingCombined != null && matchingCombined.combinedShifts != null) {
                    displayShift = enrichedShift.copy(shift = matchingCombined)
                }
            }
        }
        val s = displayShift.shift
        try {
            val start = LocalDateTime.parse(s.startDateTime)
            val end = LocalDateTime.parse(s.endDateTime)
            val formatter = DateTimeFormatter.ofPattern("E M/d")
            val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
            shiftDateTime.text = "${start.format(formatter)} ${start.format(timeFormatter)} - ${end.format(timeFormatter)}"
        } catch(e: Exception) {
            shiftDateTime.text = s.startDateTime
        }

        val station = if (s.combinedShifts != null) {
            s.workstationName ?: "Shift"
        } else {
            getWorkstationDisplayName(s.workstationId ?: s.workstationCode, s.workstationName)
        }
        shiftPosition.text = station

        // Location
        shiftLocation.text = settingsPreferences.getCafeDisplayName(s.cafeNumber, scheduleData?.cafeList)

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

            shareCoworkersButton.setOnClickListener {
                val dateStr = try {
                    val s = LocalDateTime.parse(enrichedShift.shift.startDateTime)
                    s.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
                } catch (e: Exception) { "Schedule" }

                val workstationId = enrichedShift.shift.workstationId ?: enrichedShift.shift.workstationCode ?: ""
                val workstationName = getWorkstationDisplayName(workstationId, enrichedShift.shift.workstationName)

                val owner = if (enrichedShift.isAvailable) "Available Shift" else {
                    "${enrichedShift.firstName} ${enrichedShift.lastName ?: ""}".trim()
                }
                val subHeader = "$workstationName - $owner"

                com.anonymousassociate.betterpantry.utils.ShareUtil.shareView(requireContext(), chartContainer, "Share Schedule", headerText = dateStr, subHeaderText = subHeader)
            }

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
                            if (shift.startDateTime?.startsWith(day.toString()) == true && settingsPreferences.isCafeEnabled(shift.cafeNumber)) {
                                // Re-enrich
                                val location = settingsPreferences.getCafeDisplayName(shift.cafeNumber, scheduleData?.cafeList)

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
        val shareButton = view.findViewById<android.widget.ImageButton>(R.id.shareButton)
        val closeButton = view.findViewById<android.widget.ImageButton>(R.id.closeButton)
        val chartContainer = view.findViewById<RelativeLayout>(R.id.chartContainer)
        val scrollView = view.findViewById<com.anonymousassociate.betterpantry.ui.views.TwoDimensionalScrollView>(R.id.chartScrollView)

        val noScheduleText = view.findViewById<View>(R.id.noScheduleText)

        dateHeader.text = daySchedule.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))

        if (daySchedule.shifts.isEmpty()) {
            shareButton.visibility = View.GONE
        } else {
            shareButton.visibility = View.VISIBLE
            shareButton.setOnClickListener {
                val dateStr = daySchedule.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
                com.anonymousassociate.betterpantry.utils.ShareUtil.shareView(requireContext(), chartContainer, "Share Schedule", headerText = dateStr)
            }
        }

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
                val isToday = daySchedule.date == LocalDate.now()
                val focusTime = if (isToday) {
                    LocalDateTime.now()
                } else {
                    try { LocalDateTime.parse(focusShift?.startDateTime) } catch(e: Exception) { null }
                }
                val fragment = ExpandedScheduleFragment.newInstance(
                    daySchedule,
                    focusTime = focusTime,
                    focusShiftId = focusShift?.shiftId?.toString(),
                    initialCafeNo = focusShift?.cafeNumber ?: daySchedule.shifts.firstOrNull()?.shift?.cafeNumber
                )
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

                val success = repository.acceptShiftPickup(payload.toString())
                if (success) {
                    dialog.dismiss()
                    loadScheduleData(forceRefresh = true) // Refresh to show update
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

                val responseCode = repository.cancelPostShift(payload.toString())
                if (responseCode in 200..299) {
                    dialog.dismiss()
                    loadScheduleData(forceRefresh = true)
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
                val firstName = settingsPreferences.getCoworkerFirstResolved(tm.associate?.employeeId, tm.associate?.firstName, tm.associate?.preferredName)
                val lastName = settingsPreferences.getCoworkerLastResolved(tm.associate?.employeeId, tm.associate?.lastName)

                tm.shifts?.forEach { s: TeamShift ->
                    try {
                        val sStart = LocalDateTime.parse(s.startDateTime)
                        val sEnd = LocalDateTime.parse(s.endDateTime)

                        if (sStart.isBefore(myEnd) && sEnd.isAfter(myStart) && 
                            settingsPreferences.isCafeEnabled(s.cafeNumber) && 
                            (s.cafeNumber == null || s.cafeNumber == shift.cafeNumber)) {
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

    private fun updateTimestamp() {
        updatedText.text = scheduleCache.getLastUpdateText()
    }

    private fun startUpdateTimer() {
        stopUpdateTimer()
        updateTimeRunnable = object : Runnable {
            override fun run() {
                updateTimestamp()

                if (scheduleCache.isScheduleStale() || scheduleCache.isTeamScheduleStale()) {
                    loadScheduleData()
                }

                val lastUpdate = scheduleCache.getLastUpdateTime()
                val delay = if (lastUpdate == 0L) {
                    60000L
                } else {
                    val now = System.currentTimeMillis()
                    val diff = now - lastUpdate
                    60000L - (diff % 60000L) + 50L
                }

                handler.postDelayed(this, delay)
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
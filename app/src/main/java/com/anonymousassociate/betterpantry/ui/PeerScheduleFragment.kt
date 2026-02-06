package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.os.Bundle
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
import com.anonymousassociate.betterpantry.AuthManager
import com.anonymousassociate.betterpantry.PantryApiService
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.models.*
import com.anonymousassociate.betterpantry.ui.adapters.CalendarAdapter
import com.anonymousassociate.betterpantry.ui.adapters.ShiftAdapter
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

class PeerScheduleFragment : Fragment() {

    private lateinit var authManager: AuthManager
    private val repository by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository }
    private val scheduleCache by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository.let { ScheduleCache(requireContext()) } }
    private lateinit var calendarAdapter: CalendarAdapter
    private lateinit var calendarRecyclerView: RecyclerView
    private lateinit var shiftsRecyclerView: RecyclerView
    private lateinit var availableShiftsRecyclerView: RecyclerView
    private lateinit var dateRangeText: TextView
    private lateinit var nameText: TextView
    private lateinit var updatedText: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var peer: Associate? = null
    private var peerScheduleData: ScheduleData? = null
    private var detailDialog: Dialog? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateTimeRunnable: Runnable? = null

    companion object {
        private const val ARG_PEER_JSON = "peer_json"
        fun newInstance(peer: Associate): PeerScheduleFragment {
            return PeerScheduleFragment().apply {
                arguments = Bundle().apply { putString(ARG_PEER_JSON, Gson().toJson(peer)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_PEER_JSON)?.let { peer = Gson().fromJson(it, Associate::class.java) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_peer_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupListeners()
        setupCalendar()

        refreshDataFromCache()

        loadPeerSchedule()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdateTimer()
    }

    private fun initViews(view: View) {
        authManager = AuthManager(requireContext())
        // apiService and scheduleCache from repository logic

        calendarRecyclerView = view.findViewById(R.id.calendarRecyclerView)
        shiftsRecyclerView = view.findViewById(R.id.shiftsRecyclerView)
        availableShiftsRecyclerView = view.findViewById(R.id.availableShiftsRecyclerView)
        dateRangeText = view.findViewById(R.id.dateRangeText)
        nameText = view.findViewById(R.id.nameText)
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

        availableShiftsRecyclerView.addItemDecoration(DateDividerItemDecoration(requireContext()))
        
        val settingsButton: android.widget.ImageButton = view.findViewById(R.id.settingsButton)
        settingsButton.setOnClickListener { showSettingsMenu(it) }
        
        val firstName = if (!peer?.preferredName.isNullOrEmpty()) peer?.preferredName else peer?.firstName
        val name = "$firstName ${peer?.lastName}"
        nameText.text = name.uppercase()
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

    private fun setupListeners() {
        view?.findViewById<View>(R.id.closeButton)?.setOnClickListener { parentFragmentManager.popBackStack() }
        
        swipeRefreshLayout.isEnabled = true
        val greenColor = ContextCompat.getColor(requireContext(), R.color.work_day_green)
        val backgroundColor = ContextCompat.getColor(requireContext(), R.color.card_background_color)
        swipeRefreshLayout.setColorSchemeColors(greenColor)
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(backgroundColor)
        
        swipeRefreshLayout.setOnRefreshListener {
            loadPeerSchedule(forceRefresh = true)
        }
    }

    private fun setupCalendar() {
        val today = LocalDate.now()
        val startDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
        val dates = (0 until 28).map { startDate.plusDays(it.toLong()) }
        dateRangeText.text = "${startDate.format(DateTimeFormatter.ofPattern("MMMM d"))} - ${dates.last().format(DateTimeFormatter.ofPattern("MMMM d"))}"

        calendarAdapter = CalendarAdapter(dates, today) { date -> onDateClicked(date) }
        calendarRecyclerView.layoutManager = GridLayoutManager(context, 7)
        calendarRecyclerView.adapter = calendarAdapter
    }

    fun refreshDataFromCache() {
        if (!isAdded) return
        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedSchedule != null) {
            val cachedTeam = scheduleCache.getTeamSchedule()
            if (cachedTeam != null) {
                val myId = authManager.getUserId()
                val peerShifts = cachedTeam.find { it.associate?.employeeId == peer?.employeeId }?.shifts?.map { it.toShift(peer?.employeeId) } ?: emptyList()
                val myShifts = cachedTeam.find { it.associate?.employeeId == myId }?.shifts?.map { it.toShift(myId) } ?: emptyList()
                
                val today = LocalDate.now()
                val allPersonalShifts = (peerShifts + myShifts)
                    .distinctBy { it.shiftId }
                    .filter {
                        try {
                            val shiftDate = LocalDateTime.parse(it.startDateTime).toLocalDate()
                            !shiftDate.isBefore(today)
                        } catch (e: Exception) { true }
                    }
                    .sortedBy { it.startDateTime }
                                            
                peerScheduleData = ScheduleData(
                    currentShifts = allPersonalShifts,
                    track = cachedSchedule.track,
                    cafeList = cachedSchedule.cafeList,
                    employeeInfo = cachedSchedule.employeeInfo
                )
                
                displaySchedule(peerScheduleData!!, peerShifts.filter {
                    try {
                        val shiftDate = LocalDateTime.parse(it.startDateTime).toLocalDate()
                        !shiftDate.isBefore(today)
                    } catch (e: Exception) { true }
                })
                updateTimestamp()
            }
        }
    }

    private fun loadPeerSchedule(forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            val lastUpdate = scheduleCache.getLastUpdateTime()
            val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
            if (peerScheduleData != null && lastUpdate > fiveMinutesAgo) {
                 swipeRefreshLayout.isRefreshing = false
                 return
            }
        }
        
        swipeRefreshLayout.isRefreshing = true
        lifecycleScope.launch {
            try {
                // Always fetch fresh base schedule to get updated Available Shifts and Cafe info
                val schedule = repository.getSchedule(forceRefresh) // Handles caching
                
                if (schedule != null) {
                    val sampleShift = schedule.currentShifts?.firstOrNull { it.cafeNumber != null && it.companyCode != null }
                    if (sampleShift != null) {
                        val startStr = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                        val endStr = LocalDateTime.now().plusDays(30).withHour(23).withMinute(59).withSecond(59).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                        
                        val teamMembers = try {
                            repository.getTeamMembers(sampleShift.cafeNumber!!, sampleShift.companyCode!!, startStr, endStr, forceRefresh)
                        } catch(e: Exception) { null }
                        
                        if (teamMembers != null) {
                            updateTimestamp()
                            // Refresh UI from newly cached data
                            refreshDataFromCache()
                            startUpdateTimer()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }
    
    private fun updateTimestamp() {
        updatedText.text = scheduleCache.getLastUpdateText()
    }

    private fun startUpdateTimer() {
        stopUpdateTimer()
        updateTimeRunnable = object : Runnable {
            override fun run() {
                updateTimestamp()
                
                if (scheduleCache.isScheduleStale()) {
                    loadPeerSchedule()
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

    private fun displaySchedule(schedule: ScheduleData, shiftsForList: List<Shift>) {
        calendarAdapter.updateSchedule(schedule)
        val distinctShifts = shiftsForList.sortedBy { it.startDateTime }
        shiftsRecyclerView.adapter = ShiftAdapter(distinctShifts, { showShiftDetailDialog(listOf(it), emptyList()) })
        shiftsRecyclerView.layoutManager = LinearLayoutManager(context)

        val availableShifts = schedule.track?.filter { it.type == "AVAILABLE" && it.primaryShiftRequest?.state == "AVAILABLE" }?.mapNotNull { it.primaryShiftRequest?.shift }?.distinctBy { it.shiftId }?.sortedBy { it.startDateTime } ?: emptyList()
        val availableShiftsTitle = view?.findViewById<View>(R.id.availableShiftsTitle)
        if (availableShifts.isNotEmpty()) {
            availableShiftsRecyclerView.visibility = View.VISIBLE
            availableShiftsTitle?.visibility = View.VISIBLE
            availableShiftsRecyclerView.adapter = ShiftAdapter(availableShifts, { showShiftDetailDialog(emptyList(), listOf(it)) })
            availableShiftsRecyclerView.layoutManager = LinearLayoutManager(context)
        } else {
            availableShiftsRecyclerView.visibility = View.GONE
            availableShiftsTitle?.visibility = View.GONE
        }
    }

    private fun onDateClicked(date: LocalDate) {
        val schedule = peerScheduleData ?: return

        val peerShiftsOnDate = schedule.currentShifts?.filter { it.startDateTime?.startsWith(date.toString()) == true && it.employeeId == peer?.employeeId } ?: emptyList()
        val availableShiftsOnDate = schedule.track?.asSequence()?.filter { it.type == "AVAILABLE" }?.mapNotNull { it.primaryShiftRequest?.shift }?.filter { it.startDateTime?.startsWith(date.toString()) == true }?.distinctBy { it.shiftId }?.toList() ?: emptyList()

        if (peerShiftsOnDate.isNotEmpty() || availableShiftsOnDate.isNotEmpty()) {
            showShiftDetailDialog(peerShiftsOnDate, availableShiftsOnDate, fromCalendarClick = true)
        } else {
            showDayScheduleDialog(date)
        }
    }

    private fun showShiftDetailDialog(personalShifts: List<Shift>, availableShifts: List<Shift>, isNested: Boolean = false, fromCalendarClick: Boolean = false, customTitle: String? = null) {
        if (!isNested && detailDialog?.isShowing == true) return

        val dialog = Dialog(requireContext())
        if (!isNested) detailDialog = dialog

        dialog.setContentView(R.layout.dialog_shift_detail)
        dialog.window?.apply { setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setBackgroundDrawableResource(android.R.color.transparent) }

        setupDialogContent(dialog, personalShifts, availableShifts, isNested, fromCalendarClick, customTitle)
        dialog.findViewById<View>(R.id.closeButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setupDialogContent(dialog: Dialog, personalShifts: List<Shift>, availableShifts: List<Shift>, isNested: Boolean, fromCalendarClick: Boolean, customTitle: String?) {
        val container = dialog.findViewById<LinearLayout>(R.id.shiftsContainer)
        container.removeAllViews()

        val myId = authManager.getUserId()
        val peerId = peer?.employeeId
        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)

        // Set title based on first item or default
        val firstItem = personalShifts.firstOrNull() ?: availableShifts.firstOrNull()
        if (customTitle != null) {
            dialogTitle.text = customTitle
        } else if (firstItem != null) {
             if (firstItem.employeeId == "AVAILABLE_SHIFT" || availableShifts.contains(firstItem)) dialogTitle.text = "Available Shift"
             else if (firstItem.employeeId == peerId) {
                 val first = if (!peer?.preferredName.isNullOrEmpty()) peer?.preferredName else peer?.firstName
                 dialogTitle.text = "$first ${peer?.lastName}"
             }
             else if (firstItem.employeeId == myId) dialogTitle.text = "My Shift"
             else dialogTitle.text = getEmployeeName(firstItem.employeeId)
        } else {
             dialogTitle.text = "Shift Details"
        }

        personalShifts.sortedBy { it.startDateTime }.forEach { addShiftCard(container, it, false, it.employeeId == myId, isNested) }
        if (personalShifts.isNotEmpty() && availableShifts.isNotEmpty()) {
             container.addView(TextView(requireContext()).apply{
                 text = "AVAILABLE SHIFTS"
                 textSize = 18f
                 setTypeface(null, android.graphics.Typeface.BOLD)
                 setTextColor(ContextCompat.getColor(context, R.color.work_day_green))
                 letterSpacing = 0.05f
                 layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                     gravity = android.view.Gravity.CENTER
                     topMargin = 24.dpToPx()
                     bottomMargin = 16.dpToPx()
                 }
             })
        }
        availableShifts.sortedBy { it.startDateTime }.forEach { addShiftCard(container, it, true, false, isNested) }
    }

    private fun addShiftCard(container: LinearLayout, shift: Shift, isAvailable: Boolean, isMe: Boolean, isNested: Boolean) {
        val cardView = LayoutInflater.from(requireContext()).inflate(R.layout.item_shift_detail_card, container, false)
        val shiftDateTime = cardView.findViewById<TextView>(R.id.shiftDateTime)
        val shiftPosition = cardView.findViewById<TextView>(R.id.shiftPosition)
        val shiftLocation = cardView.findViewById<TextView>(R.id.shiftLocation)
        val coworkersHeaderWrapper = cardView.findViewById<View>(R.id.coworkersHeaderWrapper)
        val expandCoworkersButton = cardView.findViewById<View>(R.id.expandCoworkersButton)
        val shareCoworkersButton = cardView.findViewById<View>(R.id.shareCoworkersButton)

        try {
            shiftDateTime.text = "${LocalDateTime.parse(shift.startDateTime).format(DateTimeFormatter.ofPattern("E M/d h:mma"))} - ${LocalDateTime.parse(shift.endDateTime).format(DateTimeFormatter.ofPattern("h:mma"))}"
        } catch (e: Exception) { shiftDateTime.text = "Unknown time" }

        shiftPosition.text = getWorkstationDisplayName(shift.workstationId, shift.workstationName, shift.workstationCode)
        val cafeInfo = peerScheduleData?.cafeList?.firstOrNull { it.departmentName?.contains(shift.cafeNumber ?: "") == true } ?: peerScheduleData?.cafeList?.firstOrNull()
        val addressStr = cafeInfo?.address?.let { "${it.addressLine ?: ""}, ${it.city ?: ""}, ${it.state ?: ""}" }?.trim(',',' ') ?: ""
        shiftLocation.text = if (addressStr.isNotEmpty()) "#${shift.cafeNumber} - $addressStr" else "#${shift.cafeNumber}"

        if (!isNested) {
            coworkersHeaderWrapper.visibility = View.VISIBLE
            val teamMembers = getMergedTeamMembers()
            val coworkerShifts = findCoworkerShifts(shift.toTeamShift(), teamMembers, shift.employeeId)

            if (coworkerShifts.isNotEmpty()) {
                val chartScrollView = cardView.findViewById<android.widget.HorizontalScrollView>(R.id.coworkersChartScrollView)
                chartScrollView.visibility = View.VISIBLE
                expandCoworkersButton.setOnClickListener { showDayScheduleDialog(LocalDate.parse(shift.startDateTime?.substring(0, 10)), shift) }
                val chartContainer = cardView.findViewById<RelativeLayout>(R.id.coworkersChartContainer)
                
                shareCoworkersButton.setOnClickListener {
                     val dateStr = try {
                         val s = LocalDateTime.parse(shift.startDateTime)
                         s.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
                     } catch (e: Exception) { "Schedule" }
                     
                     val workstationId = shift.workstationId ?: shift.workstationCode ?: ""
                     val workstationName = getWorkstationDisplayName(workstationId, shift.workstationName)
                     
                     val owner = if (isAvailable) "Available Shift" else {
                         val name = getEmployeeName(shift.employeeId)
                         if (shift.employeeId == authManager.getUserId()) "${authManager.getFirstName()} ${authManager.getLastName()}" else name
                     }
                     val subHeader = "$workstationName - $owner"
                     
                     com.anonymousassociate.betterpantry.utils.ShareUtil.shareView(requireContext(), chartContainer, "Share Schedule", headerText = dateStr, subHeaderText = subHeader)
                }
                
                chartScrollView.post {
                    val width = chartScrollView.width
                    val safeWidth = if (width > 0) width else resources.displayMetrics.widthPixels - 100
                    ChartRenderer.drawChart(requireContext(), chartContainer, DaySchedule(LocalDate.now(), coworkerShifts), false,
                        containerWidth = safeWidth,
                        fixedStartTime = LocalDateTime.parse(shift.startDateTime), fixedEndTime = LocalDateTime.parse(shift.endDateTime),
                        listener = object : ScheduleInteractionListener {
                            override fun onExpandClick(day: DaySchedule) { expandCoworkersButton.performClick() }
                            override fun onShiftClick(clickedShift: EnrichedShift) {
                                if (clickedShift.shift.shiftId.toString() != shift.shiftId) {
                                    val title = if (clickedShift.isAvailable) {
                                        "Available Shift"
                                    } else {
                                        "${clickedShift.firstName} ${clickedShift.lastName ?: ""}".trim()
                                    }
                                    showShiftDetailDialog(listOf(clickedShift.shift.toShift(clickedShift.shift.employeeId)), emptyList(), isNested = true, customTitle = title)
                                }
                            }
                        }, fitToWidth = true)
                }
            } else {
                coworkersHeaderWrapper.visibility = View.GONE
            }
        } else {
            coworkersHeaderWrapper.visibility = View.GONE
        }

        container.addView(cardView)
    }

    private fun showDayScheduleDialog(date: LocalDate, focusShift: Shift? = null) {
        val teamMembers = getMergedTeamMembers()
        val myId = authManager.getUserId()
        val allShiftsForDay = teamMembers.flatMap { member ->
            member.shifts?.mapNotNull { s ->
                if (s.startDateTime?.startsWith(date.toString()) == true) {
                    val isFocal = focusShift != null && s.shiftId == focusShift.shiftId?.toLongOrNull()
                    val isMe = member.associate?.employeeId == myId
                    
                    val cafeInfo = peerScheduleData?.cafeList?.firstOrNull { 
                        it.departmentName?.contains(s.cafeNumber ?: "") == true 
                    } ?: peerScheduleData?.cafeList?.firstOrNull()
                    
                    val location = cafeInfo?.let { cafe ->
                        val address = cafe.address
                        "#${s.cafeNumber ?: ""} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
                    } ?: "#${s.cafeNumber ?: ""}"

                    val firstName = if (!member.associate?.preferredName.isNullOrEmpty()) member.associate?.preferredName ?: "Unknown" else member.associate?.firstName ?: "Unknown"

                    EnrichedShift(
                        shift = s.copy(employeeId = member.associate?.employeeId), firstName = firstName, lastName = member.associate?.lastName,
                        isMe = isMe || isFocal, isAvailable = member.associate?.employeeId == "AVAILABLE_SHIFT",
                        location = location
                    )
                } else null
            } ?: emptyList()
        }.sortedBy { it.shift.startDateTime }

        val dialog = Dialog(requireContext())
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_day_schedule_wrapper, null)
        dialog.setContentView(view)
        dialog.window?.apply { setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setBackgroundDrawableResource(android.R.color.transparent) }
        view.findViewById<TextView>(R.id.dateHeader).text = date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        view.findViewById<View>(R.id.closeButton).setOnClickListener { dialog.dismiss() }
        val chartContainer = view.findViewById<RelativeLayout>(R.id.chartContainer)
        val scrollView = view.findViewById<android.widget.HorizontalScrollView>(R.id.chartScrollView)

        val expandButton = view.findViewById<View>(R.id.expandButton)
        val shareButton = view.findViewById<View>(R.id.shareButton)
        
        shareButton.setOnClickListener {
             val dateStr = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
             com.anonymousassociate.betterpantry.utils.ShareUtil.shareView(requireContext(), chartContainer, "Share Schedule", headerText = dateStr)
        }

        val focusTime = focusShift?.let { LocalDateTime.parse(it.startDateTime) } ?: if (date == LocalDate.now()) LocalDateTime.now() else null
        val focusEndTime = focusShift?.let { LocalDateTime.parse(it.endDateTime) }

        expandButton.setOnClickListener { ExpandedScheduleFragment.newInstance(DaySchedule(date, allShiftsForDay), focusTime).show(parentFragmentManager, "ExpandedSchedule") }

        if (allShiftsForDay.isEmpty()) {
            view.findViewById<View>(R.id.noScheduleText).visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            expandButton.visibility = View.GONE
            shareButton.visibility = View.GONE
        } else {
            scrollView.post {
                val result = ChartRenderer.drawChart(requireContext(), chartContainer, DaySchedule(date, allShiftsForDay), isExpanded = false, focusTime = focusTime, focusEndTime = focusEndTime, listener = object : ScheduleInteractionListener {
                    override fun onExpandClick(day: DaySchedule) { expandButton.performClick() }
                    override fun onShiftClick(clickedShift: EnrichedShift) {
                        val title = if (clickedShift.isAvailable) {
                            "Available Shift"
                        } else {
                            "${clickedShift.firstName} ${clickedShift.lastName ?: ""}".trim()
                        }
                        showShiftDetailDialog(listOf(clickedShift.shift.toShift(clickedShift.shift.employeeId)), emptyList(), isNested = true, customTitle = title)
                    }
                })
                
                scrollView.post {
                    val focusX = result.second
                    if (focusX != null) {
                        scrollView.scrollTo(focusX - scrollView.width / 2, 0)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun findCoworkerShifts(targetShift: TeamShift, teamMembers: List<TeamMember>, focalId: String?): List<EnrichedShift> {
        val coworkerShifts = mutableListOf<EnrichedShift>()
        val myId = authManager.getUserId()
        try {
            val targetStart = LocalDateTime.parse(targetShift.startDateTime)
            val targetEnd = LocalDateTime.parse(targetShift.endDateTime)
            teamMembers.forEach { tm ->
                val memberId = tm.associate?.employeeId
                // Include everyone including focal (Peer) and Me (User)
                tm.shifts?.forEach { s ->
                    try {
                        if (s.startDateTime?.startsWith(targetStart.toLocalDate().toString()) == true && LocalDateTime.parse(s.startDateTime).isBefore(targetEnd) && LocalDateTime.parse(s.endDateTime).isAfter(targetStart)) {
                            val isFocal = memberId == focalId
                            val isMe = memberId == myId
                            val firstName = if (!tm.associate?.preferredName.isNullOrEmpty()) tm.associate?.preferredName ?: "Unknown" else tm.associate?.firstName ?: "Unknown"
                            coworkerShifts.add(EnrichedShift(shift = s.copy(employeeId = memberId), firstName = firstName, lastName = tm.associate?.lastName, isMe = isMe || isFocal, isAvailable = memberId == "AVAILABLE_SHIFT"))
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        return coworkerShifts.distinctBy { it.shift.shiftId }
    }

    private fun getMergedTeamMembers(): List<TeamMember> {
        val teamMembers = scheduleCache.getTeamSchedule()?.toMutableList() ?: mutableListOf()
        val myId = authManager.getUserId()
        val mySchedule = scheduleCache.getSchedule()
        
        // 1. Ensure "Me" is in the list
        if (teamMembers.none { it.associate?.employeeId == myId }) {
            val myTeamShifts = mySchedule?.currentShifts?.map {
                TeamShift(
                    startDateTime = it.startDateTime, endDateTime = it.endDateTime,
                    workstationId = it.workstationId, workstationName = it.workstationName,
                    workstationGroupDisplayName = it.workstationGroupDisplayName, workstationCode = it.workstationCode,
                    shiftId = it.shiftId?.toLongOrNull(), cafeNumber = it.cafeNumber, companyCode = it.companyCode,
                    businessDate = it.businessDate, employeeId = myId
                )
            } ?: emptyList()
            teamMembers.add(TeamMember(Associate(myId, authManager.getFirstName(), authManager.getLastName(), authManager.getPreferredName()), myTeamShifts))
        }

        // 2. Prepare Available Shifts
        val tracks = mySchedule?.track ?: emptyList()
        val employeeInfo = mySchedule?.employeeInfo ?: emptyList()
        
        val availableMembers = tracks
            .filter { it.type == "AVAILABLE" }
            .distinctBy { it.primaryShiftRequest?.shift?.shiftId }
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
                        requesterName = getEmployeeName(req?.requesterId), // Use local helper which checks infoList
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

        // 3. Filter out available shifts from original owners
        val availableShiftIds = availableMembers
            .flatMap { it.shifts ?: emptyList() }
            .mapNotNull { it.shiftId }
            .toSet()

        val filteredTeam = teamMembers.map { member ->
            // Don't filter "Me" here to ensure I always see my shifts even if posted? 
            // Actually HomeFragment filters "Me" out of "teamMembers" entirely and re-adds "Me".
            // Here "teamMembers" might contain "Me" or not.
            // If I posted a shift, it is in availableShiftIds.
            // If I filter it out, it shows as Available only. This is usually desired.
            val cleanShifts = member.shifts?.filter { shift ->
                shift.shiftId !in availableShiftIds
            }
            member.copy(shifts = cleanShifts)
        }.filter { !it.shifts.isNullOrEmpty() }

        // 4. Combine: Filtered Team + Available (Avoid adding Available if already present in filteredTeam? No, filteredTeam are real people)
        // Check if "AVAILABLE_SHIFT" is already in filteredTeam (unlikely from API)
        val finalTeam = filteredTeam.filter { it.associate?.employeeId != "AVAILABLE_SHIFT" } + availableMembers
        
        return finalTeam
    }

    private fun TeamShift.toShift(empId: String?): Shift {
        return Shift(
            businessDate = this.businessDate,
            startDateTime = this.startDateTime,
            endDateTime = this.endDateTime,
            workstationId = this.workstationId,
            workstationName = this.workstationName,
            workstationGroupDisplayName = this.workstationGroupDisplayName,
            cafeNumber = this.cafeNumber,
            companyCode = this.companyCode,
            employeeId = empId ?: this.employeeId,
            shiftId = this.shiftId?.toString(),
            workstationCode = this.workstationCode
        )
    }

    private fun Shift.toTeamShift(): TeamShift {
        return TeamShift(
            businessDate = this.businessDate,
            startDateTime = this.startDateTime,
            endDateTime = this.endDateTime,
            workstationId = this.workstationId,
            workstationName = this.workstationName,
            workstationGroupDisplayName = this.workstationGroupDisplayName,
            cafeNumber = this.cafeNumber,
            companyCode = this.companyCode,
            employeeId = this.employeeId,
            shiftId = this.shiftId?.toLongOrNull(),
            workstationCode = this.workstationCode,
            requestId = null, myPickupRequestId = null, pickupRequests = null
        )
    }

    private fun getEmployeeName(employeeId: String?): String {
        if (employeeId == null) return "Unknown"
        
        // 1. Try Team Cache (Richer data)
        val teamMembers = scheduleCache.getTeamSchedule()
        val associate = teamMembers?.find { it.associate?.employeeId == employeeId }?.associate
        if (associate != null) {
            val first = if (!associate.preferredName.isNullOrEmpty()) {
                associate.preferredName
            } else {
                associate.firstName
            }
            return "$first ${associate.lastName ?: ""}".trim().ifEmpty { "Unknown" }
        }

        // 2. Try peerScheduleData (EmployeeInfo)
        val info = peerScheduleData?.employeeInfo?.find { it.employeeId == employeeId }
        if (info != null) {
            return "${info.firstName} ${info.lastName}".trim()
        }
        
        return "Unknown"
    }

    private fun getWorkstationDisplayName(workstationId: String?, fallbackName: String?, workstationCode: String? = null): String {
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
            "SALAD" to "Salad 1",
            "SANDWICH" to "Sandwich 1",
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
        
        var finalName: String? = null

        if (workstationId != null) {
            finalName = customNames[workstationId]
        }
        if (finalName == null && workstationCode != null) {
            finalName = customNames[workstationCode]
        }
        if (finalName == null && fallbackName != null) {
            finalName = customNames[fallbackName]
        }

        return finalName ?: fallbackName ?: workstationId ?: "Unknown"
    }



    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
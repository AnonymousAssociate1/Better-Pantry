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
    private val repository by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository }
    private val scheduleCache by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository.let { 
        // We can access cache via repository if we expose it or just use repository methods. 
        // For now, let's keep scheduleCache access if needed for specific non-repo things, 
        // but prefer repo. Actually, MainActivity creates ScheduleCache. 
        // Let's just create a new instance if needed or access via Activity? 
        // Better: ScheduleCache(requireContext()) is fine as it uses SharedPreferences (singleton-ish underlying).
        // BUT, to ensure "shared cache" logic, we should probably stick to what the Repo uses.
        // For read-only access to helpers like getLastUpdateText, local instance is fine.
        ScheduleCache(requireContext())
    }}
    
    private lateinit var calendarAdapter: CalendarAdapter
    private lateinit var calendarRecyclerView: RecyclerView
    private lateinit var shiftsRecyclerView: RecyclerView
    private lateinit var availableShiftsRecyclerView: RecyclerView
    private lateinit var dateRangeText: TextView
    private lateinit var availableShiftsTitle: TextView
    private lateinit var updatedText: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var updateCard: androidx.cardview.widget.CardView
    private lateinit var updateDivider: View
    private lateinit var moneyPreferences: com.anonymousassociate.betterpantry.MoneyPreferences

    private var scheduleData: ScheduleData? = null
    private var moneyDialog: Dialog? = null
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
        moneyPreferences = com.anonymousassociate.betterpantry.MoneyPreferences(requireContext())

        calendarRecyclerView = view.findViewById(R.id.calendarRecyclerView)
        shiftsRecyclerView = view.findViewById(R.id.shiftsRecyclerView)
        availableShiftsRecyclerView = view.findViewById(R.id.availableShiftsRecyclerView)
        availableShiftsRecyclerView.addItemDecoration(DateDividerItemDecoration(requireContext()))
        dateRangeText = view.findViewById(R.id.dateRangeText)
        availableShiftsTitle = view.findViewById(R.id.availableShiftsTitle)
        updatedText = view.findViewById(R.id.updatedText)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        updateCard = view.findViewById(R.id.updateCard)
        updateDivider = view.findViewById(R.id.updateDivider)
        val settingsButton: android.widget.ImageButton = view.findViewById(R.id.settingsButton)
        val moneyButton: android.widget.ImageButton = view.findViewById(R.id.moneyButton)
        
        val nestedScrollView = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.nestedScrollView)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(nestedScrollView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            
            // Adjust refresh indicator position
            val refreshTarget = bars.top + (32 * resources.displayMetrics.density).toInt()
            swipeRefreshLayout.setProgressViewOffset(false, 0, refreshTarget)
            
            insets
        }

        settingsButton.setOnClickListener { showSettingsMenu(it) }
        moneyButton.setOnClickListener { showMoneySettingsDialog() }

        setupCalendar()
        setupSwipeRefresh()

        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedSchedule != null) {
            scheduleData = cachedSchedule
            displaySchedule(cachedSchedule)
            updateTimestamp()
            startUpdateTimer()
        }

        loadSchedule()
        checkForUpdates()
    }

    private fun showMoneySettingsDialog() {
        if (moneyDialog?.isShowing == true) return

        val dialog = Dialog(requireContext())
        moneyDialog = dialog
        dialog.setContentView(R.layout.dialog_money_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val switch = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.showMoneySwitch)
        val closeButton = dialog.findViewById<View>(R.id.closeButton)
        val container = dialog.findViewById<View>(R.id.moneySettingsContainer)

        closeButton.setOnClickListener { dialog.dismiss() }
        val wageInput = dialog.findViewById<android.widget.EditText>(R.id.hourlyWageInput)
        val hoursInput = dialog.findViewById<android.widget.EditText>(R.id.hoursInput)
        val resultText = dialog.findViewById<TextView>(R.id.calculatedMoneyText)
        val weekRangeText = dialog.findViewById<TextView>(R.id.weekRangeText)

        // Initialize state
        switch.isChecked = moneyPreferences.showMoney
        container.visibility = if (switch.isChecked) View.VISIBLE else View.GONE
        
        val savedWage = moneyPreferences.hourlyWage
        if (savedWage > 0) {
            wageInput.setText(savedWage.toString())
        }

        // Calculate scheduled hours for current week (Wed-Tue)
        val scheduledHours = calculateScheduledHoursForCurrentWeek()
        // Format hours: if whole number, no decimal. Else, up to 2 decimals? "Least decimals".
        val scheduledHoursStr = if (scheduledHours % 1.0 == 0.0) {
            scheduledHours.toInt().toString()
        } else {
            String.format("%.2f", scheduledHours).trimEnd('0').trimEnd('.')
        }
        
        hoursInput.setText(scheduledHoursStr)
        
        // Setup Date Range Text
        if (scheduledHours > 0) {
            val range = getWeekDateRangeText()
            weekRangeText.text = "$range you're scheduled: $scheduledHoursStr hours"
            weekRangeText.visibility = View.VISIBLE
        } else {
            weekRangeText.visibility = View.GONE
        }

        fun calculate() {
            val wageStr = wageInput.text.toString()
            val hoursStr = hoursInput.text.toString()
            
            val wage = wageStr.toFloatOrNull() ?: 0f
            val hours = hoursStr.toFloatOrNull() ?: 0f
            
            val total = wage * hours
            resultText.text = String.format("$%.2f", total)
            
            // Auto-save logic
            if (switch.isChecked) {
                moneyPreferences.hourlyWage = wage
                // Also update list if changed? Maybe too frequent. 
                // We can update on dismiss or delay.
                // Let's rely on dismiss for list refresh, but save preference immediately.
            }
            
            // Show/Hide range text based on if input matches scheduled
            val inputHours = hours
            // Compare with tolerance
            if (Math.abs(inputHours - scheduledHours) < 0.01) {
                 if (scheduledHours > 0) weekRangeText.visibility = View.VISIBLE
            } else {
                 weekRangeText.visibility = View.GONE
            }
        }
        
        // Initial calc
        calculate()

        switch.setOnCheckedChangeListener { _, isChecked ->
            moneyPreferences.showMoney = isChecked
            container.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                // If turned off, refresh list immediately
                scheduleData?.let { displaySchedule(it) }
            } else {
                // If turned on, calculate (which saves wage)
                calculate()
            }
        }

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { calculate() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        wageInput.addTextChangedListener(watcher)
        hoursInput.addTextChangedListener(watcher)

        dialog.setOnDismissListener {
            // Refresh list on dismiss to reflect new settings
            scheduleData?.let { displaySchedule(it) }
        }

        dialog.show()
    }

    private fun getWeekDateRangeText(): String {
        val schedule = scheduleData ?: return ""
        val myShifts = schedule.currentShifts ?: return ""
        
        val today = LocalDate.now()
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
        val endOfWeek = startOfWeek.plusDays(6)

        val shiftsInWeek = myShifts.filter {
            try {
                val date = LocalDateTime.parse(it.startDateTime).toLocalDate()
                !date.isBefore(startOfWeek) && !date.isAfter(endOfWeek)
            } catch(e: Exception) { false }
        }.sortedBy { it.startDateTime }

        if (shiftsInWeek.isEmpty()) return ""

        val first = LocalDateTime.parse(shiftsInWeek.first().startDateTime)
        val last = LocalDateTime.parse(shiftsInWeek.last().startDateTime)
        
        val formatter = DateTimeFormatter.ofPattern("M/d")
        return "${first.format(formatter)} - ${last.format(formatter)}"
    }

    private fun calculateScheduledHoursForCurrentWeek(): Float {
        val schedule = scheduleData ?: return 0f
        val myShifts = schedule.currentShifts ?: return 0f
        
        val today = LocalDate.now()
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
        val endOfWeek = startOfWeek.plusDays(6)

        var totalHours = 0.0
        
        myShifts.forEach { shift ->
            try {
                val start = LocalDateTime.parse(shift.startDateTime)
                val date = start.toLocalDate()
                if (!date.isBefore(startOfWeek) && !date.isAfter(endOfWeek)) {
                    val end = LocalDateTime.parse(shift.endDateTime)
                    val duration = java.time.Duration.between(start, end).toMinutes() / 60.0
                    totalHours += duration
                }
            } catch (e: Exception) {}
        }
        
        return totalHours.toFloat()
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

    fun refreshDataFromCache() {
        if (!isAdded) return
        val cached = scheduleCache.getSchedule()
        if (cached != null) {
            scheduleData = cached
            displaySchedule(cached)
            updateTimestamp()
        }
    }

    private fun loadSchedule(forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            if (!scheduleCache.isScheduleStale() && scheduleData != null) {
                swipeRefreshLayout.isRefreshing = false
                return
            }
        }

        // Trigger animation immediately for auto-refresh
        swipeRefreshLayout.post {
            swipeRefreshLayout.isRefreshing = true
        }

        lifecycleScope.launch {
            try {
                // Use Repository
                val schedule = repository.getSchedule(forceRefresh)
                schedule?.let {
                    scheduleData = it
                    // Cache save handled by repository
                    displaySchedule(it)
                    updateTimestamp()
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
                val count = repository.getNotificationCount()
                (requireActivity() as? com.anonymousassociate.betterpantry.MainActivity)?.updateNotificationBadge(count)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun prefetchTeamMembers(schedule: ScheduleData) {
        val sampleShift = schedule.currentShifts?.firstOrNull { 
            it.cafeNumber != null && it.companyCode != null 
        } ?: schedule.track?.mapNotNull { it.primaryShiftRequest?.shift }?.firstOrNull { 
            it.cafeNumber != null && it.companyCode != null 
        }

        if (sampleShift == null) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Fetch for a wide range (e.g. today to +30 days) to cover the calendar view
                val now = LocalDateTime.now()
                val start = now.minusDays(1).withHour(0).withMinute(0).withSecond(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                val end = now.plusDays(35).withHour(23).withMinute(59).withSecond(59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                // Use Repository
                repository.getTeamMembers(
                    sampleShift.cafeNumber!!,
                    sampleShift.companyCode!!,
                    start,
                    end
                )
                // Repository handles caching/merging
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun displaySchedule(schedule: ScheduleData) {
        calendarAdapter.updateSchedule(schedule)

        val distinctShifts = schedule.currentShifts?.distinctBy { it.shiftId }?.sortedBy { it.startDateTime }
        if (distinctShifts != null) {
            val shiftAdapter = ShiftAdapter(
                shifts = distinctShifts,
                onShiftClick = { shift ->
                    showShiftDetailDialog(listOf(shift), emptyList())
                },
                showMoney = moneyPreferences.showMoney,
                hourlyWage = moneyPreferences.hourlyWage
            )
            shiftsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = shiftAdapter
            }
        }

        val availableShifts = schedule.track?.filter { track ->
            val isTypeAvailable = track.type == "AVAILABLE"
            val primaryState = track.primaryShiftRequest?.state
            val isStateOpen = primaryState == "AVAILABLE" || primaryState == "APPROVED"
            val isClaimed = track.relatedShiftRequests?.any { it.state == "APPROVED" } == true
            
            isTypeAvailable && isStateOpen && !isClaimed
        }?.sortedByDescending { it.primaryShiftRequest?.requestedAt }
         ?.mapNotNull { it.primaryShiftRequest?.shift }
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
                        val trackItem = schedule.track?.filter { 
                            it.type == "AVAILABLE" && it.primaryShiftRequest?.shift?.shiftId == shift.shiftId 
                        }?.maxByOrNull { it.primaryShiftRequest?.requestedAt ?: "" }
                        
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
                },
                showMoney = moneyPreferences.showMoney,
                hourlyWage = moneyPreferences.hourlyWage
            )
            availableShiftsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = availableAdapter
            }
        } else {
            availableShiftsTitle.visibility = View.GONE
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
                    loadSchedule()
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
            // Open full schedule for the day using cached data
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
                val firstName = if (!tm.associate?.preferredName.isNullOrEmpty()) tm.associate?.preferredName ?: "Unknown" else tm.associate?.firstName ?: "Unknown"
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
            
            showDayScheduleDialog(DaySchedule(date, allShiftsForDay.sortedBy { it.shift.startDateTime }))
        }
    }

    private fun showShiftDetailDialog(myShifts: List<Shift>, availableShifts: List<Shift>, fromCalendarClick: Boolean = false, customTitle: String? = null, isNested: Boolean = false) {
        val sortedMyShifts = myShifts.sortedBy { it.startDateTime }
        val sortedAvailableShifts = availableShifts.sortedBy { it.startDateTime }

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
        // estimatedMoneyText was removed from layout

        var titleText = customTitle ?: "Shift Details"
        var hideCoworkersForMyShifts = isNested
        var hideCoworkersForAvailable = isNested

        // Determine context if no custom title
        if (customTitle == null) {
            if (sortedMyShifts.size == 1 && sortedAvailableShifts.isEmpty()) {
                val shift = sortedMyShifts[0]
                if (shift.employeeId != authManager.getUserId()) {
                    // Coworker
                    titleText = getEmployeeName(shift.employeeId)
                    hideCoworkersForMyShifts = true
                } else {
                    // Me
                    titleText = "Shift Details"
                }
            } else if (sortedAvailableShifts.size == 1 && sortedMyShifts.isEmpty()) {
                titleText = "Available Shift"
            }
        } else {
            // Apply hiding logic even if custom title is provided
             if (sortedMyShifts.size == 1 && sortedAvailableShifts.isEmpty()) {
                val shift = sortedMyShifts[0]
                if (shift.employeeId != authManager.getUserId()) {
                     hideCoworkersForMyShifts = true
                }
            } else if (sortedAvailableShifts.size == 1 && sortedMyShifts.isEmpty()) {
                // Keep default false
            }
        }

        dialogTitle.text = titleText
        
        shiftsContainer.removeAllViews()

        sortedMyShifts.forEach { shift ->
            addShiftCard(shiftsContainer, shift, isAvailable = false, hideCoworkers = hideCoworkersForMyShifts)
        }

        val showAsSeparator = sortedMyShifts.isNotEmpty() && sortedAvailableShifts.isNotEmpty()

        if (showAsSeparator) {
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
                    topMargin = 24.dpToPx()
                    bottomMargin = 16.dpToPx()
                }
            }
            shiftsContainer.addView(separator)
        }

        sortedAvailableShifts.forEach { shift ->
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
        val shareButton = view.findViewById<android.widget.ImageButton>(R.id.shareButton)
        val closeButton = view.findViewById<android.widget.ImageButton>(R.id.closeButton)
        val chartContainer = view.findViewById<RelativeLayout>(R.id.chartContainer)
        val scrollView = view.findViewById<android.widget.HorizontalScrollView>(R.id.chartScrollView)
        
        val noScheduleText = view.findViewById<View>(R.id.noScheduleText)
        
        dateHeader.text = daySchedule.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        
        shareButton.setOnClickListener {
            val dateStr = daySchedule.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
            com.anonymousassociate.betterpantry.utils.ShareUtil.shareView(requireContext(), chartContainer, "Share Schedule", headerText = dateStr)
        }
        
        closeButton.visibility = View.VISIBLE
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        if (daySchedule.shifts.isEmpty()) {
            noScheduleText.visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            expandButton.visibility = View.GONE
            shareButton.visibility = View.GONE
        } else {
            noScheduleText.visibility = View.GONE
            scrollView.visibility = View.VISIBLE
            expandButton.visibility = View.VISIBLE
            shareButton.visibility = View.VISIBLE
            
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
        val cardMoneyText = cardView.findViewById<TextView>(R.id.cardMoneyText)
        val postedByText = cardView.findViewById<TextView>(R.id.postedByText)
        val coworkersHeaderWrapper = cardView.findViewById<View>(R.id.coworkersHeaderWrapper)
        val expandCoworkersButton = cardView.findViewById<View>(R.id.expandCoworkersButton)
        val shareCoworkersButton = cardView.findViewById<View>(R.id.shareCoworkersButton)
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
            
            // Calculate money for THIS shift
            if (moneyPreferences.showMoney && moneyPreferences.hourlyWage > 0) {
                // Show only if it's My Shift or Available shift
                if (shift.employeeId == authManager.getUserId() || isAvailable) {
                    val duration = java.time.Duration.between(startDateTime, endDateTime).toMinutes() / 60.0
                    val money = duration * moneyPreferences.hourlyWage
                    cardMoneyText.text = String.format("$%.2f", money)
                    cardMoneyText.visibility = View.VISIBLE
                } else {
                    cardMoneyText.visibility = View.GONE
                }
            } else {
                cardMoneyText.visibility = View.GONE
            }
        } catch (e: Exception) {
            shiftDateTime.text = "Unknown date"
            cardMoneyText.visibility = View.GONE
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
                            val firstName = if (!tm.associate?.preferredName.isNullOrEmpty()) tm.associate?.preferredName ?: "Unknown" else tm.associate?.firstName ?: "Unknown"
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
                        
                        showDayScheduleDialog(DaySchedule(day, allShiftsForDay.sortedBy { it.shift.startDateTime }), shift)
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

                    val teamMembers = repository.getTeamMembers(
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
            .filter { 
                val state = it.primaryShiftRequest?.state
                val isClaimed = it.relatedShiftRequests?.any { r -> r.state == "APPROVED" } == true
                (state == "AVAILABLE" || state == "APPROVED") && !isClaimed
            }
            .sortedByDescending { it.primaryShiftRequest?.requestedAt }
            .distinctBy { it.primaryShiftRequest?.shift?.shiftId }
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
                val firstName = if (!tm.associate?.preferredName.isNullOrEmpty()) tm.associate?.preferredName ?: "Unknown" else tm.associate?.firstName ?: "Unknown"
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
                val newSchedule = repository.getSchedule(forceRefresh = true) // Force refresh to get latest state
                if (newSchedule != null) {
                    scheduleData = newSchedule
                    // scheduleCache.saveSchedule(newSchedule) // Handled by repository
                    displaySchedule(newSchedule)
                    updateTimestamp()

                    val date = LocalDate.parse(originalShift.startDateTime?.substring(0, 10))
                    val myShiftsOnDate = newSchedule.currentShifts?.filter {
                        LocalDate.parse(it.startDateTime?.substring(0, 10)) == date
                    }?.sortedBy { it.startDateTime } ?: emptyList()
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
                        
                        val showAsSeparator = myShiftsOnDate.isNotEmpty() && availableShiftsOnDate.isNotEmpty()

                        if (showAsSeparator) {
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
                                    topMargin = 24.dpToPx()
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
                val success = repository.acceptShiftPickup(payload.toString())
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

                val responseCode = repository.cancelPostShift(payload.toString())
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

                val responseCode = repository.cancelPostShift(payload.toString())
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
                
                val success = repository.postShift(payload.toString())
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
        
        // 1. Try Team Cache (Richer data with preferredName)
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

        // 2. Try provided info list or scheduleData (EmployeeInfo)
        val list = infoList ?: scheduleData?.employeeInfo
        val employee = list?.find { it.employeeId == employeeId }
        if (employee != null) {
            return "${employee.firstName ?: ""} ${employee.lastName ?: ""}".trim().ifEmpty { "Unknown" }
        }
        
        return "Unknown"
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

    private fun checkForUpdates() {
        if (updateAvailable) {
            showUpdateCard(updateUrl)
            return
        }
        
        if (hasCheckedForUpdates) return

        hasCheckedForUpdates = true
        lifecycleScope.launch {
            try {
                val release = repository.getLatestRelease()
                if (release != null) {
                    val currentVersion = com.anonymousassociate.betterpantry.BuildConfig.VERSION_NAME
                    if (isNewerVersion(currentVersion, release.tag_name)) {
                        updateAvailable = true
                        updateUrl = release.html_url
                        showUpdateCard(release.html_url)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateCard(url: String?) {
        if (url == null) return
        updateCard.visibility = View.VISIBLE
        updateDivider.visibility = View.VISIBLE
        updateCard.setOnClickListener {
            (requireActivity() as? com.anonymousassociate.betterpantry.MainActivity)?.openBrowser(url)
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

        val length = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until length) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    companion object {
        private var hasCheckedForUpdates = false
        private var updateAvailable = false
        private var updateUrl: String? = null
    }
}
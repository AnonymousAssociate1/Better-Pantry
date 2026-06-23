package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.AutoCompleteTextView
import android.widget.ImageView
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import com.anonymousassociate.betterpantry.utils.ShiftCombiner
import com.anonymousassociate.betterpantry.MainActivity

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
    private lateinit var updateCardText: TextView
    private lateinit var updateDivider: View
    private var pendingApkFile: java.io.File? = null
    private val REQUEST_CODE_INSTALL_PERMISSION = 1001
    private lateinit var moneyPreferences: com.anonymousassociate.betterpantry.MoneyPreferences
    private lateinit var settingsPreferences: com.anonymousassociate.betterpantry.SettingsPreferences

    private lateinit var exportCalendarTitleHeader: TextView
    private lateinit var exportCalendarCardContainer: FrameLayout
    private lateinit var exportCalendarCard: androidx.cardview.widget.CardView
    private lateinit var exportSubtitleText: TextView
    private lateinit var calendarDropdown: android.widget.AutoCompleteTextView
    private var pendingShowExportDialog = false
    private var activeDialogCalendarDropdown: android.widget.AutoCompleteTextView? = null
    private var pendingShiftId: String? = null
    private var pendingShiftStart: String? = null

    private var isYourShiftsExpanded = true
    private var isAvailableShiftsExpanded = true

    private lateinit var yourShiftsHeaderLayout: View
    private lateinit var yourShiftsTitleContainer: View
    private lateinit var yourShiftsChevron: ImageView
    private lateinit var exportButtonContainer: View

    private lateinit var availableShiftsHeaderLayout: View
    private lateinit var availableShiftsTitleContainer: View
    private lateinit var availableShiftsChevron: ImageView

    private var currentScheduledShifts: List<Shift> = emptyList()
    private var selectedStartDate: LocalDate = LocalDate.now().minusDays(7)
    private var selectedEndDate: LocalDate = LocalDate.now()
    private var systemCalendars: List<AndroidCalendar> = emptyList()
    private var selectedCalendarId: Long? = null

    private val calendarPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[android.Manifest.permission.READ_CALENDAR] ?: false
        val writeGranted = permissions[android.Manifest.permission.WRITE_CALENDAR] ?: false
        if (readGranted && writeGranted) {
            loadCalendars()
        } else {
            pendingShowExportDialog = false
            android.widget.Toast.makeText(
                context,
                "Calendar permissions are required to export shifts.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private var scheduleData: ScheduleData? = null
    private var moneyDialog: Dialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateTimeRunnable: Runnable? = null
    private var detailDialog: Dialog? = null
    private var actionMenuDialog: Dialog? = null
    private var selectAssociateDialog: Dialog? = null
    private var selectShiftDialog: Dialog? = null
    private var confirmationDialog: Dialog? = null

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
        settingsPreferences = com.anonymousassociate.betterpantry.SettingsPreferences(requireContext())

        calendarRecyclerView = view.findViewById(R.id.calendarRecyclerView)
        shiftsRecyclerView = view.findViewById(R.id.shiftsRecyclerView)
        availableShiftsRecyclerView = view.findViewById(R.id.availableShiftsRecyclerView)
        availableShiftsRecyclerView.addItemDecoration(DateDividerItemDecoration(requireContext()))
        dateRangeText = view.findViewById(R.id.dateRangeText)
        availableShiftsTitle = view.findViewById(R.id.availableShiftsTitle)
        updatedText = view.findViewById(R.id.updatedText)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        updateCard = view.findViewById(R.id.updateCard)
        updateCardText = view.findViewById(R.id.updateCardText)
        updateDivider = view.findViewById(R.id.updateDivider)

        exportCalendarTitleHeader = view.findViewById(R.id.exportCalendarTitleHeader)
        exportCalendarCardContainer = view.findViewById(R.id.exportCalendarCardContainer)
        exportCalendarCard = view.findViewById(R.id.exportCalendarCard)
        exportSubtitleText = view.findViewById(R.id.exportSubtitleText)
        calendarDropdown = view.findViewById(R.id.calendarDropdown)

        yourShiftsHeaderLayout = view.findViewById(R.id.yourShiftsHeaderLayout)
        yourShiftsTitleContainer = view.findViewById(R.id.yourShiftsTitleContainer)
        yourShiftsChevron = view.findViewById(R.id.yourShiftsChevron)
        exportButtonContainer = view.findViewById(R.id.exportButtonContainer)

        availableShiftsHeaderLayout = view.findViewById(R.id.availableShiftsHeaderLayout)
        availableShiftsTitleContainer = view.findViewById(R.id.availableShiftsTitleContainer)
        availableShiftsChevron = view.findViewById(R.id.availableShiftsChevron)

        updateExportSubtitleText()

        calendarDropdown.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (!hasCalendarPermissions()) {
                    pendingShowExportDialog = true
                    requestCalendarPermissions()
                    return@setOnTouchListener true
                }
            }
            false
        }

        exportCalendarCard.setOnClickListener {
            if (hasCalendarPermissions()) {
                showExportDialog()
            } else {
                pendingShowExportDialog = true
                requestCalendarPermissions()
            }
        }

        yourShiftsTitleContainer.setOnClickListener {
            isYourShiftsExpanded = !isYourShiftsExpanded
            shiftsRecyclerView.visibility = if (isYourShiftsExpanded) View.VISIBLE else View.GONE
            yourShiftsChevron.setImageResource(
                if (isYourShiftsExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
        }

        availableShiftsTitleContainer.setOnClickListener {
            isAvailableShiftsExpanded = !isAvailableShiftsExpanded
            availableShiftsRecyclerView.visibility = if (isAvailableShiftsExpanded) View.VISIBLE else View.GONE
            availableShiftsChevron.setImageResource(
                if (isAvailableShiftsExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
        }

        exportButtonContainer.setOnClickListener {
            if (hasCalendarPermissions()) {
                showExportDialog()
            } else {
                pendingShowExportDialog = true
                requestCalendarPermissions()
            }
        }

        if (hasCalendarPermissions()) {
            loadCalendars()
        }

        val availabilityButton: android.widget.ImageButton = view.findViewById(R.id.availabilityButton)
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

        availabilityButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AvailabilityFragment())
                .addToBackStack(null)
                .commit()
        }
        
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

        val focusShiftId = arguments?.getString("focus_shift_id")
        val focusShiftStart = arguments?.getString("focus_shift_start")
        if (focusShiftId != null) {
            arguments?.remove("focus_shift_id")
            arguments?.remove("focus_shift_start")
            deepLinkToShift(focusShiftId, focusShiftStart)
        }
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
        val willRefreshSchedule = forceRefresh || scheduleData == null || scheduleCache.isScheduleStale()
        val willRefreshTeam = forceRefresh || scheduleCache.isTeamScheduleStale()

        if (!willRefreshSchedule && !willRefreshTeam) {
            swipeRefreshLayout.isRefreshing = false
            return
        }

        // Trigger animation immediately
        swipeRefreshLayout.post {
            swipeRefreshLayout.isRefreshing = true
        }

        lifecycleScope.launch {
            try {
                if (willRefreshSchedule) {
                    // Use Repository
                    val schedule = repository.getSchedule(forceRefresh)
                    
                    // Also fetch Availability/TimeOff/MaxHours to keep cache fresh
                    if (forceRefresh) {
                        launch(Dispatchers.IO) {
                            repository.getAvailability(true)
                            repository.getMaxHours(true)
                            repository.getTimeOff(true)
                        }
                    }

                    schedule?.let {
                        scheduleData = it
                        prefetchTeamMembers(it, forceRefresh = forceRefresh)
                        // Cache save handled by repository
                        displaySchedule(it)
                        updateTimestamp()
                        startUpdateTimer()
                    }
                    checkNotifications()
                } else {
                    // Schedule is fresh, but team is stale. Prefetch team members
                    val snapSchedule = scheduleData
                    if (snapSchedule != null) {
                        prefetchTeamMembers(snapSchedule, forceRefresh = false)
                        // Re-render so shift cards pick up the freshly-populated team cache
                        displaySchedule(snapSchedule)
                    }
                }
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

    private suspend fun prefetchTeamMembers(schedule: ScheduleData, forceRefresh: Boolean = false) {
        val sampleShift = schedule.currentShifts?.firstOrNull { 
            it.cafeNumber != null && it.companyCode != null 
        } ?: schedule.track?.mapNotNull { it.primaryShiftRequest?.shift }?.firstOrNull { 
            it.cafeNumber != null && it.companyCode != null 
        }

        val companyCode = sampleShift?.companyCode ?: "101"
        val enabledCafeNos = settingsPreferences.getEnabledCafeNumbers(
            schedule,
            scheduleCache.getTeamSchedule(),
            authManager.getCafeNo(),
            authManager.getUserId()
        )
        val finalCafes = if (enabledCafeNos.isEmpty()) {
            val homeCafe = authManager.getCafeNo()
            val sampleCafe = sampleShift?.cafeNumber
            if (homeCafe != null) listOf(homeCafe) else (if (sampleCafe != null) listOf(sampleCafe) else emptyList())
        } else {
            enabledCafeNos
        }

        if (finalCafes.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                // Fetch for a wide range (e.g. today to +30 days) to cover the calendar view
                val range = com.anonymousassociate.betterpantry.utils.DateRangeUtils.getCoworkerQueryRange()
                val start = range.first
                val end = range.second

                val fetchedCafes = mutableSetOf<String>()
                // First pass
                coroutineScope {
                    finalCafes.map { cafeNo ->
                        fetchedCafes.add(cafeNo)
                        async {
                            try {
                                repository.getTeamMembers(
                                    cafeNo,
                                    companyCode,
                                    start,
                                    end,
                                    forceRefresh = forceRefresh
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }.awaitAll()
                }

                // Second pass to check if any new cafes were discovered/enabled from the newly populated cache
                val updatedEnabledCafes = settingsPreferences.getEnabledCafeNumbers(
                    schedule,
                    scheduleCache.getTeamSchedule(),
                    authManager.getCafeNo(),
                    authManager.getUserId()
                )
                val newCafes = updatedEnabledCafes.filter { it !in fetchedCafes }
                if (newCafes.isNotEmpty()) {
                    coroutineScope {
                        newCafes.map { cafeNo ->
                            async {
                                try {
                                    repository.getTeamMembers(
                                        cafeNo,
                                        companyCode,
                                        start,
                                        end,
                                        forceRefresh = forceRefresh
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }.awaitAll()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun displaySchedule(schedule: ScheduleData) {
        val distinctShifts = schedule.currentShifts?.filter { settingsPreferences.isCafeEnabled(it.cafeNumber) }?.distinctBy { it.shiftId }?.sortedBy { it.startDateTime }
        
        val availableShifts = schedule.track?.filter { track ->
            val isTypeAvailable = track.type == "AVAILABLE"
            val primaryState = track.primaryShiftRequest?.state
            val isStateOpen = primaryState == "AVAILABLE" || primaryState == "APPROVED"
            val isClaimed = track.relatedShiftRequests?.any { it.state == "APPROVED" } == true
            
            isTypeAvailable && isStateOpen && !isClaimed
        }?.sortedByDescending { it.primaryShiftRequest?.requestedAt }
         ?.mapNotNull { it.primaryShiftRequest?.shift }
         ?.filter { settingsPreferences.isCafeEnabled(it.cafeNumber) }
         ?.distinctBy { it.shiftId }
         ?.sortedBy { it.startDateTime } ?: emptyList()

        calendarAdapter.updateSchedule(schedule, scheduleCache.getTimeOff(), settingsPreferences.showAvailabilityOnCalendar, requireContext())

        currentScheduledShifts = distinctShifts ?: emptyList()

        // Calculate the default week range on data load and update the export card subtitle
        val today = java.time.LocalDate.now()
        val startDate = if (today.dayOfWeek == java.time.DayOfWeek.WEDNESDAY) {
            today
        } else {
            today.with(java.time.temporal.TemporalAdjusters.previous(java.time.DayOfWeek.WEDNESDAY))
        }
        val dates = (0 until 28).map { startDate.plusDays(it.toLong()) }
        val defaultRange = getDefaultWeekRange(dates)
        selectedStartDate = defaultRange.first
        selectedEndDate = defaultRange.second
        updateExportSubtitleText()
        val vis = if (currentScheduledShifts.isNotEmpty()) View.VISIBLE else View.GONE
        exportButtonContainer.visibility = vis
        exportCalendarCardContainer.visibility = View.GONE
        exportCalendarTitleHeader.visibility = View.GONE

        if (distinctShifts != null && distinctShifts.isNotEmpty()) {
            yourShiftsHeaderLayout.visibility = View.VISIBLE
            shiftsRecyclerView.visibility = if (isYourShiftsExpanded) View.VISIBLE else View.GONE
            yourShiftsChevron.setImageResource(
                if (isYourShiftsExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            val existingAdapter = shiftsRecyclerView.adapter as? ShiftAdapter
            if (existingAdapter != null) {
                existingAdapter.updateData(distinctShifts)
            } else {
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
        } else {
            yourShiftsHeaderLayout.visibility = View.GONE
            shiftsRecyclerView.visibility = View.GONE
        }

        if (availableShifts.isNotEmpty()) {
            availableShiftsHeaderLayout.visibility = View.VISIBLE
            availableShiftsRecyclerView.visibility = if (isAvailableShiftsExpanded) View.VISIBLE else View.GONE
            availableShiftsChevron.setImageResource(
                if (isAvailableShiftsExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            val existingAdapter = availableShiftsRecyclerView.adapter as? ShiftAdapter
            if (existingAdapter != null) {
                existingAdapter.updateData(availableShifts)
            } else {
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
            }
        } else {
            availableShiftsHeaderLayout.visibility = View.GONE
            availableShiftsRecyclerView.visibility = View.GONE
        }
        checkPendingDeepLink()
    }

    private fun updateTimestamp() {
        updatedText.text = scheduleCache.getLastUpdateText()
    }

    fun deepLinkToShift(shiftId: String?, shiftStart: String?) {
        pendingShiftId = shiftId
        pendingShiftStart = shiftStart
        checkPendingDeepLink()
    }

    fun checkPendingDeepLink() {
        val mainActivity = activity as? MainActivity
        val shiftId = pendingShiftId ?: mainActivity?.pendingShiftId ?: return
        val shiftStart = pendingShiftStart ?: mainActivity?.pendingShiftStart

        pendingShiftId = null
        pendingShiftStart = null
        if (mainActivity != null) {
            mainActivity.pendingShiftId = null
            mainActivity.pendingShiftStart = null
        }

        if (currentScheduledShifts.isEmpty()) return

        val myShift = currentScheduledShifts.find { it.shiftId?.toString() == shiftId || it.startDateTime == shiftStart }
        if (myShift != null) {
            val shiftDate = myShift.startDateTime?.substring(0, 10)
            val dayShifts = currentScheduledShifts.filter { it.startDateTime?.startsWith(shiftDate ?: "") == true }
            showShiftDetailDialog(dayShifts, emptyList(), clickedDate = LocalDate.parse(shiftDate))
        }
    }

    private fun startUpdateTimer() {
        stopUpdateTimer()
        updateTimeRunnable = object : Runnable {
            override fun run() {
                updateTimestamp()
                
                // Reload if either the personal schedule OR team schedule is stale
                if (scheduleCache.isScheduleStale() || scheduleCache.isTeamScheduleStale()) {
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
        
        val lastUpdate = scheduleCache.getLastUpdateTime()
        val initialDelay = if (lastUpdate == 0L) {
            60000L
        } else {
            val now = System.currentTimeMillis()
            val diff = now - lastUpdate
            60000L - (diff % 60000L) + 50L
        }
        handler.postDelayed(updateTimeRunnable!!, initialDelay)
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
                shiftDate == date && settingsPreferences.isCafeEnabled(shift.cafeNumber)
            } catch (e: Exception) {
                false
            }
        } ?: emptyList()

        val availableShiftsOnDate = schedule.track?.filter { track ->
            track.type == "AVAILABLE" && track.primaryShiftRequest?.state == "AVAILABLE"
        }?.mapNotNull { it.primaryShiftRequest?.shift }?.filter { shift ->
            try {
                val shiftDate = LocalDate.parse(shift.startDateTime?.substring(0, 10))
                shiftDate == date && settingsPreferences.isCafeEnabled(shift.cafeNumber)
            } catch (e: Exception) {
                false
            }
        }?.distinctBy { it.shiftId }?.sortedBy { it.startDateTime } ?: emptyList()

        if (myShiftsOnDate.isNotEmpty() || availableShiftsOnDate.isNotEmpty()) {
            showShiftDetailDialog(myShiftsOnDate, availableShiftsOnDate, fromCalendarClick = true, clickedDate = date)
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
                val firstName = settingsPreferences.getCoworkerFirstResolved(tm.associate?.employeeId, tm.associate?.firstName, tm.associate?.preferredName)
                val lastName = settingsPreferences.getCoworkerLastResolved(tm.associate?.employeeId, tm.associate?.lastName)
                
                tm.shifts?.forEach { s ->
                    try {
                        if (s.startDateTime?.startsWith(date.toString()) == true && settingsPreferences.isCafeEnabled(s.cafeNumber)) {
                            val location = settingsPreferences.getCafeDisplayName(s.cafeNumber, scheduleData?.cafeList)

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

    private fun showShiftDetailDialog(myShifts: List<Shift>, availableShifts: List<Shift>, fromCalendarClick: Boolean = false, customTitle: String? = null, isNested: Boolean = false, clickedDate: LocalDate? = null) {
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

        val dialogTimeOffContainer = dialog.findViewById<LinearLayout>(R.id.dialogTimeOffContainer)
        val dialogTimeOffTitle = dialog.findViewById<TextView>(R.id.dialogTimeOffTitle)
        val dialogTimeOffComment = dialog.findViewById<TextView>(R.id.dialogTimeOffComment)

        val targetDate = clickedDate ?: try {
            val dateTimeStr = myShifts.firstOrNull()?.startDateTime ?: availableShifts.firstOrNull()?.startDateTime
            dateTimeStr?.let { LocalDate.parse(it.substring(0, 10)) }
        } catch(e: Exception) {
            null
        }

        if (targetDate != null && dialogTimeOffContainer != null && dialogTimeOffTitle != null && dialogTimeOffComment != null) {
            val targetDateStr = targetDate.toString()
            val timeOffReq = scheduleCache.getTimeOff()?.firstOrNull {
                it.timeOffDate == targetDateStr && (it.status == "APPROVED" || it.status == "PENDING")
            }
            if (timeOffReq != null) {
                val isAllDay = try {
                    val start = java.time.OffsetDateTime.parse(timeOffReq.startTime)
                    val end = java.time.OffsetDateTime.parse(timeOffReq.endTime)
                    val startLocalTime = start.toLocalTime()
                    val endLocalTime = end.toLocalTime()
                    (startLocalTime == java.time.LocalTime.MIDNIGHT && endLocalTime == java.time.LocalTime.MIDNIGHT && java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) == 1L)
                } catch(e: Exception) {
                    false
                }

                val timeRangeStr = if (isAllDay) {
                    "ALL DAY"
                } else {
                    try {
                        val start = java.time.OffsetDateTime.parse(timeOffReq.startTime)
                        val end = java.time.OffsetDateTime.parse(timeOffReq.endTime)
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
                        "${start.format(formatter)} - ${end.format(formatter)}"
                    } catch(e: Exception) {
                        "ALL DAY"
                    }
                }

                val spannableTitle = android.text.SpannableStringBuilder().apply {
                    val startYellow = length
                    append("TIME OFF: ")
                    setSpan(
                        android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.time_off_pastel_yellow)),
                        startYellow,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        startYellow,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    
                    val startGray = length
                    append(timeRangeStr)
                    setSpan(
                        android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.nav_text_secondary)),
                        startGray,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                dialogTimeOffTitle.text = spannableTitle
                dialogTimeOffContainer.visibility = View.VISIBLE

                if (!timeOffReq.associateComments.isNullOrBlank()) {
                    dialogTimeOffComment.text = timeOffReq.associateComments
                    dialogTimeOffComment.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.nav_text_secondary))
                    dialogTimeOffComment.visibility = View.VISIBLE
                } else {
                    dialogTimeOffComment.visibility = View.GONE
                }
            } else {
                dialogTimeOffContainer.visibility = View.GONE
            }
        } else {
            dialogTimeOffContainer?.visibility = View.GONE
        }

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

        val displayedMyShifts = if (settingsPreferences.combineShifts) {
            ShiftCombiner.combineShifts(myShifts)
        } else {
            sortedMyShifts
        }

        displayedMyShifts.forEach { shift ->
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

        dialog.setOnDismissListener {
            dismissIntermediateDialogs()
        }

        dialog.show()
    }

    private fun showManageCombinedShiftsDialog(combinedShift: Shift) {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_shift_detail)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val shiftsContainer = dialog.findViewById<LinearLayout>(R.id.shiftsContainer)
        val closeButton = dialog.findViewById<View>(R.id.closeButton)

        dialogTitle.text = "Manage Combined Shifts"
        shiftsContainer.removeAllViews()

        val constituentShifts = combinedShift.combinedShifts ?: listOf(combinedShift)
        constituentShifts.forEach { shift ->
            val cardView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_shift_detail_card, shiftsContainer, false)

            val shiftDateTime = cardView.findViewById<TextView>(R.id.shiftDateTime)
            val shiftPosition = cardView.findViewById<TextView>(R.id.shiftPosition)
            val cardMoneyText = cardView.findViewById<TextView>(R.id.cardMoneyText)
            val postedByText = cardView.findViewById<TextView>(R.id.postedByText)
            val actionButton = cardView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cardActionButton)
            val shiftLocation = cardView.findViewById<TextView>(R.id.shiftLocation)

            try {
                val start = LocalDateTime.parse(shift.startDateTime)
                val end = LocalDateTime.parse(shift.endDateTime)
                val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
                val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
                shiftDateTime.text = "${start.format(dayFormatter)} ${start.format(timeFormatter)} - ${end.format(timeFormatter)}"
            } catch (e: Exception) {
                shiftDateTime.text = "Unknown time"
            }

            val workstationName = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)
            shiftPosition.text = workstationName

            shiftLocation.text = settingsPreferences.getCafeDisplayName(shift.cafeNumber, scheduleData?.cafeList)

            if (moneyPreferences.showMoney && moneyPreferences.hourlyWage > 0) {
                try {
                    val duration = java.time.Duration.between(LocalDateTime.parse(shift.startDateTime), LocalDateTime.parse(shift.endDateTime)).toMinutes() / 60.0
                    cardMoneyText.text = String.format("$%.2f", duration * moneyPreferences.hourlyWage)
                    cardMoneyText.visibility = View.VISIBLE
                } catch(e: Exception) { cardMoneyText.visibility = View.GONE }
            } else {
                cardMoneyText.visibility = View.GONE
            }

            val latestActivePost = scheduleData?.track?.filter {
                it.primaryShiftRequest?.shift?.shiftId == shift.shiftId &&
                (it.primaryShiftRequest?.state == "AVAILABLE" || it.primaryShiftRequest?.state == "PENDING")
            }?.maxByOrNull { it.primaryShiftRequest?.requestedAt ?: "" }

            if (latestActivePost != null) {
                val reqType = latestActivePost.type ?: latestActivePost.primaryShiftRequest?.type ?: "POST"
                if (reqType == "TRADE") {
                    postedByText.text = "Status: Pending Trade"
                } else if (reqType == "COVER") {
                    postedByText.text = "Status: Pending Cover"
                } else {
                    postedByText.text = "Status: Posted for Pickup"
                }
                postedByText.visibility = View.VISIBLE

                if (latestActivePost.primaryShiftRequest?.requesterId == authManager.getUserId()) {
                    actionButton.visibility = View.VISIBLE
                    actionButton.text = if (reqType == "TRADE") "Cancel Trade" else if (reqType == "COVER") "Cancel Cover" else "Cancel Post"
                    actionButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                    actionButton.setOnClickListener {
                        val title = if (reqType == "TRADE") "Cancel Trade" else if (reqType == "COVER") "Cancel Cover" else "Cancel Post"
                        val msg = if (reqType == "TRADE") "Are you sure you want to cancel this trade request?" else if (reqType == "COVER") "Are you sure you want to cancel this cover request?" else "Are you sure you want to cancel this post?"
                        showConfirmationDialog(title, msg) {
                            dialog.dismiss()
                            if (reqType == "TRADE") {
                                performCancelTrade(latestActivePost.primaryShiftRequest?.requestId, latestActivePost.primaryShiftRequest?.recipientId)
                            } else if (reqType == "COVER") {
                                performCancelCover(latestActivePost.primaryShiftRequest?.requestId, latestActivePost.primaryShiftRequest?.recipientId)
                            } else {
                                performCancelPost(latestActivePost.primaryShiftRequest?.requestId, shift)
                            }
                        }
                    }
                } else {
                    actionButton.visibility = View.GONE
                }
            } else {
                postedByText.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Post"
                actionButton.setBackgroundColor(resources.getColor(R.color.work_day_green, null))
                actionButton.setOnClickListener {
                    dialog.dismiss()
                    showShiftActionMenu(shift, onCancel = {
                        showManageCombinedShiftsDialog(combinedShift)
                    })
                }
            }

            shiftsContainer.addView(cardView)
        }

        closeButton.setOnClickListener { dialog.dismiss() }
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
        val scrollView = view.findViewById<com.anonymousassociate.betterpantry.ui.views.TwoDimensionalScrollView>(R.id.chartScrollView)
        val noScheduleText = view.findViewById<View>(R.id.noScheduleText)
        
        val dialogCafeSwitcherScroll = view.findViewById<View>(R.id.dialogCafeSwitcherScroll)
        val dialogCafeChipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.dialogCafeChipGroup)
        var currentSelectedCafe: String? = null
        
        dateHeader.text = daySchedule.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))

        val dialogTimeOffContainer = view.findViewById<LinearLayout>(R.id.dialogTimeOffContainer)
        val dialogTimeOffTitle = view.findViewById<TextView>(R.id.dialogTimeOffTitle)
        val dialogTimeOffComment = view.findViewById<TextView>(R.id.dialogTimeOffComment)

        if (dialogTimeOffContainer != null && dialogTimeOffTitle != null && dialogTimeOffComment != null) {
            val targetDateStr = daySchedule.date.toString()
            val timeOffReq = scheduleCache.getTimeOff()?.firstOrNull {
                it.timeOffDate == targetDateStr && (it.status == "APPROVED" || it.status == "PENDING")
            }
            if (timeOffReq != null) {
                val isAllDay = try {
                    val start = java.time.OffsetDateTime.parse(timeOffReq.startTime)
                    val end = java.time.OffsetDateTime.parse(timeOffReq.endTime)
                    val startLocalTime = start.toLocalTime()
                    val endLocalTime = end.toLocalTime()
                    (startLocalTime == java.time.LocalTime.MIDNIGHT && endLocalTime == java.time.LocalTime.MIDNIGHT && java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) == 1L)
                } catch(e: Exception) {
                    false
                }

                val timeRangeStr = if (isAllDay) {
                    "ALL DAY"
                } else {
                    try {
                        val start = java.time.OffsetDateTime.parse(timeOffReq.startTime)
                        val end = java.time.OffsetDateTime.parse(timeOffReq.endTime)
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
                        "${start.format(formatter)} - ${end.format(formatter)}"
                    } catch(e: Exception) {
                        "ALL DAY"
                    }
                }

                val spannableTitle = android.text.SpannableStringBuilder().apply {
                    val startYellow = length
                    append("TIME OFF: ")
                    setSpan(
                        android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.time_off_pastel_yellow)),
                        startYellow,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        startYellow,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    
                    val startGray = length
                    append(timeRangeStr)
                    setSpan(
                        android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.nav_text_secondary)),
                        startGray,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                dialogTimeOffTitle.text = spannableTitle
                dialogTimeOffContainer.visibility = View.VISIBLE

                if (!timeOffReq.associateComments.isNullOrBlank()) {
                    dialogTimeOffComment.text = timeOffReq.associateComments
                    dialogTimeOffComment.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.nav_text_secondary))
                    dialogTimeOffComment.visibility = View.VISIBLE
                } else {
                    dialogTimeOffComment.visibility = View.GONE
                }
            } else {
                dialogTimeOffContainer.visibility = View.GONE
            }
        }
        
        closeButton.visibility = View.VISIBLE
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        val homeCafe = authManager.getCafeNo()
        val userId = authManager.getUserId()
        val enabledCafeNumbers = settingsPreferences.getEnabledCafeNumbers(
            scheduleData,
            scheduleCache.getTeamSchedule(),
            homeCafe,
            userId
        )
        
        val unfilteredShifts = daySchedule.shifts
        
        fun renderForCafe(selectedCafeNo: String?) {
            val filteredShifts = unfilteredShifts.filter { it.shift.cafeNumber == selectedCafeNo }
            val filteredDaySchedule = DaySchedule(daySchedule.date, filteredShifts)
            
            shareButton.setOnClickListener {
                val dateStr = filteredDaySchedule.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
                com.anonymousassociate.betterpantry.utils.ShareUtil.shareView(requireContext(), chartContainer, "Share Schedule", headerText = dateStr)
            }
            
            if (filteredShifts.isEmpty()) {
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
                    val isToday = daySchedule.date == java.time.LocalDate.now()
                    val focusTime = try { 
                        java.time.LocalDateTime.parse(focusShift?.startDateTime) 
                    } catch(e: Exception) { 
                        if (isToday) java.time.LocalDateTime.now() else null
                    }
                    val fragment = ExpandedScheduleFragment.newInstance(
                        daySchedule,
                        focusTime = focusTime,
                        focusShiftId = focusShift?.shiftId,
                        initialCafeNo = currentSelectedCafe
                    )
                    fragment.show(parentFragmentManager, "ExpandedSchedule")
                }
                
                chartContainer.removeAllViews()
                scrollView.post {
                    val isToday = filteredDaySchedule.date == LocalDate.now()
                    
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
                    
                    val result = ChartRenderer.drawChart(
                        requireContext(),
                        chartContainer,
                        filteredDaySchedule,
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
        }
        
        val sortedCafeNos = enabledCafeNumbers.sorted()
        if (sortedCafeNos.size > 1 && dialogCafeSwitcherScroll != null && dialogCafeChipGroup != null) {
            dialogCafeSwitcherScroll.visibility = View.VISIBLE
            dialogCafeChipGroup.removeAllViews()

            val focusCafe = focusShift?.cafeNumber 
                ?: unfilteredShifts.firstOrNull { it.isMe }?.shift?.cafeNumber
                ?: homeCafe

            val initialIndex = sortedCafeNos.indexOf(focusCafe).let { if (it != -1) it else 0 }
            val initialCafeNo = sortedCafeNos.getOrNull(initialIndex)
            currentSelectedCafe = initialCafeNo

            sortedCafeNos.forEach { cafeNo ->
                val displayName = settingsPreferences.getCafeDisplayName(cafeNo, scheduleData?.cafeList)
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    text = displayName
                    isCheckable = true
                    isChecked = (cafeNo == initialCafeNo)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked && currentSelectedCafe != cafeNo) {
                            currentSelectedCafe = cafeNo
                            renderForCafe(cafeNo)
                        }
                    }
                }
                dialogCafeChipGroup.addView(chip)
            }

            renderForCafe(initialCafeNo)
        } else {
            if (dialogCafeSwitcherScroll != null) {
                dialogCafeSwitcherScroll.visibility = View.GONE
            }
            val defaultCafe = sortedCafeNos.firstOrNull() ?: homeCafe
            renderForCafe(defaultCafe)
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
                    val totalMinutes = if (shift.combinedShifts != null) {
                        shift.combinedShifts.sumOf {
                            val s = LocalDateTime.parse(it.startDateTime)
                            val e = LocalDateTime.parse(it.endDateTime)
                            java.time.Duration.between(s, e).toMinutes()
                        }
                    } else {
                        java.time.Duration.between(startDateTime, endDateTime).toMinutes()
                    }
                    val duration = totalMinutes / 60.0
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
                if (shift.combinedShifts != null) {
                    if (shift.employeeId == authManager.getUserId()) {
                        actionButton.visibility = View.VISIBLE
                        actionButton.text = "Post"
                        actionButton.setBackgroundColor(resources.getColor(R.color.work_day_green, null))
                        actionButton.setOnClickListener {
                            showManageCombinedShiftsDialog(shift)
                        }
                    } else {
                        actionButton.visibility = View.GONE
                    }
                } else {
                    val latestActivePost = scheduleData?.track?.filter {
                        it.primaryShiftRequest?.shift?.shiftId == shift.shiftId &&
                        (it.primaryShiftRequest?.state == "AVAILABLE" || it.primaryShiftRequest?.state == "PENDING")
                    }?.maxByOrNull { it.primaryShiftRequest?.requestedAt ?: "" }

                    if (latestActivePost != null) {
                        val reqType = latestActivePost.type ?: latestActivePost.primaryShiftRequest?.type ?: "POST"
                        if (reqType == "TRADE") {
                            postedByText.text = "Status: Pending Trade"
                        } else if (reqType == "COVER") {
                            postedByText.text = "Status: Pending Cover"
                        } else {
                            postedByText.text = "Status: Posted for Pickup"
                        }
                        postedByText.visibility = View.VISIBLE
                        
                        if (latestActivePost.primaryShiftRequest?.requesterId == authManager.getUserId()) {
                            actionButton.visibility = View.VISIBLE
                            actionButton.text = if (reqType == "TRADE") "Cancel Trade" else if (reqType == "COVER") "Cancel Cover" else "Cancel Post"
                            actionButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                            actionButton.setOnClickListener {
                                val title = if (reqType == "TRADE") "Cancel Trade" else if (reqType == "COVER") "Cancel Cover" else "Cancel Post"
                                val msg = if (reqType == "TRADE") "Are you sure you want to cancel this trade request?" else if (reqType == "COVER") "Are you sure you want to cancel this cover request?" else "Are you sure you want to cancel this post?"
                                showConfirmationDialog(title, msg) {
                                    detailDialog?.dismiss()
                                    if (reqType == "TRADE") {
                                        performCancelTrade(latestActivePost.primaryShiftRequest?.requestId, latestActivePost.primaryShiftRequest?.recipientId)
                                    } else if (reqType == "COVER") {
                                        performCancelCover(latestActivePost.primaryShiftRequest?.requestId, latestActivePost.primaryShiftRequest?.recipientId)
                                    } else {
                                        performCancelPost(latestActivePost.primaryShiftRequest?.requestId, shift)
                                    }
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
                                showShiftActionMenu(shift)
                            }
                        } else {
                            actionButton.visibility = View.GONE
                        }
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
                
                lifecycleScope.launch(Dispatchers.Default) {
                    // Reuse merge logic (locally implemented)
                    val mergedMembers = mergeData(teamMembers, myShifts, availableTracks, employeeInfo)
                    
                    val coworkerShifts = findCoworkerShifts(shift, mergedMembers, myId)
                    
                    withContext(Dispatchers.Main) {
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
                                    val firstName = settingsPreferences.getCoworkerFirstResolved(tm.associate?.employeeId, tm.associate?.firstName, tm.associate?.preferredName)
                                    val lastName = settingsPreferences.getCoworkerLastResolved(tm.associate?.employeeId, tm.associate?.lastName)
                                    
                                    tm.shifts?.forEach { s ->
                                        try {
                                            if (s.startDateTime?.startsWith(day.toString()) == true && settingsPreferences.isCafeEnabled(s.cafeNumber)) {
                                                 val location = settingsPreferences.getCafeDisplayName(s.cafeNumber, scheduleData?.cafeList)

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
                        } else {
                            coworkersHeaderWrapper.visibility = View.GONE
                            coworkersContainer.visibility = View.GONE
                            chartScrollView.visibility = View.GONE
                        }
                    }
                }
            }

            val globalTeam = scheduleCache.getTeamSchedule()
            if (globalTeam != null) {
                updateChart(globalTeam)
            }

            if (globalTeam == null || scheduleCache.isTeamScheduleStale()) {
                lifecycleScope.launch {
                    try {
                        scheduleData?.let { prefetchTeamMembers(it, forceRefresh = false) }
                        val updatedTeam = scheduleCache.getTeamSchedule()
                        if (updatedTeam != null) {
                            updateChart(updatedTeam)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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

        val location = settingsPreferences.getCafeDisplayName(shift.cafeNumber, scheduleData?.cafeList)
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
        var totalChecked = 0
        var overlapped = 0
        var sameDayNonOverlapping = 0
        try {
            val myStart = LocalDateTime.parse(targetShift.startDateTime)
            val myEnd = LocalDateTime.parse(targetShift.endDateTime)
            val targetDate = myStart.toLocalDate()
            
            teamMembers.forEach { tm: TeamMember ->
                val isMe = tm.associate?.employeeId == myId
                
                val isAvailable = tm.associate?.employeeId == "AVAILABLE_SHIFT"
                val firstName = settingsPreferences.getCoworkerFirstResolved(tm.associate?.employeeId, tm.associate?.firstName, tm.associate?.preferredName)
                val lastName = settingsPreferences.getCoworkerLastResolved(tm.associate?.employeeId, tm.associate?.lastName)
                
                tm.shifts?.forEach { s: TeamShift ->
                    try {
                        val sStart = LocalDateTime.parse(s.startDateTime)
                        val sEnd = LocalDateTime.parse(s.endDateTime)
                        
                        if (sStart.toLocalDate() == targetDate && (s.cafeNumber == null || s.cafeNumber == targetShift.cafeNumber)) {
                            totalChecked++
                            if (sStart.isBefore(myEnd) && sEnd.isAfter(myStart)) {
                                overlapped++
                                coworkerShifts.add(
                                    EnrichedShift(
                                        shift = s,
                                        firstName = firstName,
                                        lastName = lastName,
                                        isMe = isMe,
                                        isAvailable = isAvailable
                                    )
                                )
                            } else {
                                sameDayNonOverlapping++
                            }
                        }
                    } catch(e: Exception) {}
                }
            }
            android.util.Log.d("BetterPantry", "findCoworkerShifts (Home): target=${targetShift.startDateTime}-${targetShift.endDateTime}, totalChecked=$totalChecked, overlapped=$overlapped, sameDayNonOverlapping=$sameDayNonOverlapping")
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

                        val displayedMyShifts = if (settingsPreferences.combineShifts) {
                            ShiftCombiner.combineShifts(myShiftsOnDate)
                        } else {
                            myShiftsOnDate
                        }
                        displayedMyShifts.forEach { shift ->
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

    private fun dismissIntermediateDialogs() {
        actionMenuDialog?.dismiss()
        actionMenuDialog = null
        selectAssociateDialog?.dismiss()
        selectAssociateDialog = null
        selectShiftDialog?.dismiss()
        selectShiftDialog = null
        confirmationDialog?.dismiss()
        confirmationDialog = null
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        showConfirmationDialog(title, message, null, onConfirm)
    }

    private fun showConfirmationDialog(
        title: String,
        message: String,
        onCancel: (() -> Unit)?,
        onConfirm: () -> Unit
    ) {
        if (!isAdded) return
        val confirmDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ ->
                confirmationDialog = null
                onConfirm()
            }
            .setNegativeButton("No") { _, _ ->
                confirmationDialog = null
                onCancel?.invoke()
            }
            .setOnCancelListener {
                confirmationDialog = null
                onCancel?.invoke()
            }
            .create()
        confirmationDialog = confirmDialog
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

    private fun performCancelTrade(requestId: String?, recipientId: String?) {
        if (requestId == null) return
        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    val reqIdParsed: Any = requestId.toLongOrNull() ?: requestId
                    put("requestId", reqIdParsed)
                    put("employeeId", authManager.getUserId())
                    put("giveAssociate", org.json.JSONObject().apply {
                        put("employeeId", authManager.getUserId())
                    })
                    put("receiveAssociate", org.json.JSONObject().apply {
                        put("employeeId", recipientId ?: "")
                    })
                }

                println("DEBUG: HomeFragment performing cancel trade with payload: $payload")

                val responseCode = repository.cancelTradeShift(payload.toString())
                if (responseCode in 200..299) {
                    android.widget.Toast.makeText(context, "Trade request canceled successfully", android.widget.Toast.LENGTH_SHORT).show()
                    checkNotifications()
                    loadSchedule(forceRefresh = true)
                } else {
                    android.widget.Toast.makeText(context, "Failed to cancel trade request (Code: $responseCode)", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun performCancelCover(requestId: String?, recipientId: String?) {
        if (requestId == null) return
        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    val reqIdParsed: Any = requestId.toLongOrNull() ?: requestId
                    put("requestId", reqIdParsed)
                    put("employeeId", authManager.getUserId())
                    put("giveAssociate", org.json.JSONObject().apply {
                        put("employeeId", authManager.getUserId())
                    })
                    put("receiveAssociate", org.json.JSONObject().apply {
                        put("employeeId", recipientId ?: "")
                    })
                }

                println("DEBUG: HomeFragment performing cancel cover with payload: $payload")

                val responseCode = repository.cancelCoverShift(payload.toString())
                if (responseCode in 200..299) {
                    android.widget.Toast.makeText(context, "Cover request canceled successfully", android.widget.Toast.LENGTH_SHORT).show()
                    checkNotifications()
                    loadSchedule(forceRefresh = true)
                } else {
                    android.widget.Toast.makeText(context, "Failed to cancel cover request (Code: $responseCode)", android.widget.Toast.LENGTH_SHORT).show()
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
                    dismissIntermediateDialogs()
                    reloadShiftDetails(shift)
                    checkNotifications()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showShiftActionMenu(shift: Shift, onCancel: (() -> Unit)? = null) {
        val dialog = Dialog(requireContext())
        actionMenuDialog = dialog
        dialog.setContentView(R.layout.dialog_shift_action_menu)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val closeButton = dialog.findViewById<View>(R.id.closeButton)
        val btnPost = dialog.findViewById<View>(R.id.btnPost)
        val btnTrade = dialog.findViewById<View>(R.id.btnTrade)
        val btnCover = dialog.findViewById<View>(R.id.btnCover)

        closeButton.setOnClickListener { dialog.cancel() }

        btnPost.setOnClickListener {
            dialog.dismiss()
            startPostFlow(shift, onCancel = { showShiftActionMenu(shift, onCancel) })
        }

        btnTrade.setOnClickListener {
            dialog.dismiss()
            startTradeFlow(shift, onCancel = { showShiftActionMenu(shift, onCancel) })
        }

        btnCover.setOnClickListener {
            dialog.dismiss()
            startCoverFlow(shift, onCancel = { showShiftActionMenu(shift, onCancel) })
        }

        dialog.setOnCancelListener {
            onCancel?.invoke()
        }

        dialog.setOnDismissListener {
            if (actionMenuDialog == dialog) {
                actionMenuDialog = null
            }
        }

        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun startPostFlow(shift: Shift, onCancel: (() -> Unit)? = null) {
        showConfirmationDialog(
            title = "Post Shift",
            message = "Are you sure you want to post your shift?",
            onCancel = {
                onCancel?.invoke()
            }
        ) {
            performPostShift(shift)
        }
    }

    private fun loadCoworkersForShift(shift: Shift, onLoaded: (List<TeamMember>) -> Unit) {
        val loadingDialog = Dialog(requireContext())
        loadingDialog.setContentView(R.layout.dialog_loading)
        loadingDialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        loadingDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        loadingDialog.setCancelable(false)
        loadingDialog.show()

        lifecycleScope.launch {
            try {
                val cafeNo = shift.cafeNumber
                val companyCode = shift.companyCode ?: scheduleData?.currentShifts?.firstOrNull()?.companyCode
                
                if (cafeNo != null && companyCode != null) {
                    // Use the same date range as prefetchTeamMembers and PeopleFragment so we always
                    // get a cache hit instead of triggering a divergent API call with a different window.
                    val range = com.anonymousassociate.betterpantry.utils.DateRangeUtils.getCoworkerQueryRange()
                    val start = range.first
                    val end = range.second
                    
                    val teamMembers = repository.getTeamMembers(cafeNo, companyCode, start, end) ?: emptyList()
                    loadingDialog.dismiss()
                    onLoaded(teamMembers)
                } else {
                    loadingDialog.dismiss()
                    onLoaded(scheduleCache.getTeamSchedule() ?: emptyList())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loadingDialog.dismiss()
                onLoaded(scheduleCache.getTeamSchedule() ?: emptyList())
            }
        }
    }

    private fun createCardItem(context: Context, childView: View, onClick: () -> Unit): View {
        val frameLayout = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8.dpToPx())
            }
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            clipChildren = false
            clipToPadding = false
        }
        val cardView = com.google.android.material.card.MaterialCardView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            radius = 8.dpToPx().toFloat()
            cardElevation = 4.dpToPx().toFloat()
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background_color))
            isClickable = true
            isFocusable = true
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            foreground = ContextCompat.getDrawable(context, typedValue.resourceId)
            setOnClickListener { onClick() }
            addView(childView)
        }
        frameLayout.addView(cardView)
        return frameLayout
    }

    private fun startCoverFlow(shift: Shift, onCancel: (() -> Unit)? = null) {
        loadCoworkersForShift(shift) { teamMembers ->
            val cafeNo = shift.cafeNumber
            val filteredCoworkers = teamMembers.filter { tm ->
                val assoc = tm.associate
                val id = assoc?.employeeId
                id != null && id != authManager.getUserId() && id != "AVAILABLE_SHIFT" &&
                (assoc.cafeNumber == cafeNo ||
                 assoc.loanedCafeList?.contains(cafeNo) == true ||
                 tm.shifts?.any { it.cafeNumber == cafeNo } == true)
            }
            val coworkerNames = filteredCoworkers.associate { it.associate?.employeeId.orEmpty() to getEmployeeName(it.associate?.employeeId) }
            val favorites = scheduleCache.getFavorites()
            val coworkers = filteredCoworkers.sortedWith(
                compareByDescending<TeamMember> { favorites.contains(it.associate?.employeeId) }
                    .thenBy { coworkerNames[it.associate?.employeeId.orEmpty()].orEmpty() }
            )

            if (coworkers.isEmpty()) {
                android.widget.Toast.makeText(context, "No eligible coworkers found for this cafe.", android.widget.Toast.LENGTH_LONG).show()
                return@loadCoworkersForShift
            }

            val dialog = Dialog(requireContext())
            selectAssociateDialog = dialog
            dialog.setContentView(R.layout.dialog_select_item)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
            dialogTitle.text = "SELECT ASSOCIATE"

            val itemsContainer = dialog.findViewById<LinearLayout>(R.id.itemsContainer)
            val closeButton = dialog.findViewById<View>(R.id.closeButton)
            closeButton.setOnClickListener { dialog.cancel() }

            dialog.setOnCancelListener {
                onCancel?.invoke()
            }
            dialog.setOnDismissListener {
                if (selectAssociateDialog == dialog) {
                    selectAssociateDialog = null
                }
            }

            val searchContainer = dialog.findViewById<View>(R.id.searchContainer)
            val searchEditText = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchEditText)

            searchContainer.visibility = View.VISIBLE

            fun populateList(query: String) {
                itemsContainer.removeAllViews()
                val filtered = if (query.isBlank()) {
                    coworkers
                } else {
                    coworkers.filter { member ->
                        val name = coworkerNames[member.associate?.employeeId.orEmpty()].orEmpty()
                        name.contains(query, ignoreCase = true)
                    }
                }
                filtered.forEach { member ->
                    val name = coworkerNames[member.associate?.employeeId.orEmpty()].orEmpty()
                    val isFav = favorites.contains(member.associate?.employeeId)
                    val horizontalLayout = LinearLayout(requireContext()).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
                        
                        val nameTv = TextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                            text = name
                            textSize = 16f
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        }
                        addView(nameTv)
                        
                        if (isFav) {
                            val starIv = android.widget.ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    24.dpToPx(),
                                    24.dpToPx()
                                ).apply {
                                    marginStart = 8.dpToPx()
                                }
                                setImageResource(R.drawable.ic_star_filled)
                                setContentDescription("Favorite")
                                setColorFilter(ContextCompat.getColor(context, R.color.work_day_green))
                            }
                            addView(starIv)
                        }
                    }
                    val cardView = createCardItem(requireContext(), horizontalLayout) {
                        dialog.dismiss()
                        onCoworkerSelectedForCover(shift, member, onCancel)
                    }
                    itemsContainer.addView(cardView)
                }
            }

            searchEditText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    populateList(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            populateList("")
            dialog.show()
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun onCoworkerSelectedForCover(shift: Shift, coworker: TeamMember, onCancel: (() -> Unit)? = null) {
        val hasOverlap = checkCoverOverlap(coworker.shifts ?: emptyList(), shift)
        val coworkerName = getEmployeeName(coworker.associate?.employeeId)
        if (hasOverlap) {
            android.widget.Toast.makeText(context, "$coworkerName is already scheduled during this shift.", android.widget.Toast.LENGTH_LONG).show()
            startCoverFlow(shift, onCancel)
            return
        }

        val shiftStart = LocalDateTime.parse(shift.startDateTime)
        val shiftEnd = LocalDateTime.parse(shift.endDateTime)
        val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
        val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
        val shiftTimeText = "${shiftStart.format(dayFormatter)} ${shiftStart.format(timeFormatter)} - ${shiftEnd.format(timeFormatter)}"
        val positionText = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)

        val confirmMessage = "Are you sure you want to ask $coworkerName to cover your $shiftTimeText $positionText shift?"
        showConfirmationDialog(
            title = "Request Cover",
            message = confirmMessage,
            onCancel = {
                startCoverFlow(shift, onCancel)
            }
        ) {
            performCoverShift(shift, coworker)
        }
    }

    private fun checkCoverOverlap(coworkerShifts: List<TeamShift>, giveShift: Shift): Boolean {
        val giveStart = try { LocalDateTime.parse(giveShift.startDateTime) } catch(e: Exception) { return false }
        val giveEnd = try { LocalDateTime.parse(giveShift.endDateTime) } catch(e: Exception) { return false }

        return coworkerShifts.any { s ->
            try {
                val sStart = LocalDateTime.parse(s.startDateTime)
                val sEnd = LocalDateTime.parse(s.endDateTime)
                sStart.isBefore(giveEnd) && sEnd.isAfter(giveStart)
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun performCoverShift(shift: Shift, coworker: TeamMember) {
        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("cafeNo", shift.cafeNumber?.toIntOrNull() ?: 0)
                    
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

                    val receiveAssociate = org.json.JSONObject().apply {
                        put("firstName", coworker.associate?.firstName)
                        put("lastName", coworker.associate?.lastName)
                        put("preferredName", coworker.associate?.preferredName)
                        put("employeeId", coworker.associate?.employeeId)
                    }
                    put("receiveAssociate", receiveAssociate)
                }

                val success = repository.coverShift(payload.toString())
                if (success) {
                    android.widget.Toast.makeText(context, "Cover request sent successfully", android.widget.Toast.LENGTH_SHORT).show()
                    dismissIntermediateDialogs()
                    reloadShiftDetails(shift)
                    checkNotifications()
                } else {
                    android.widget.Toast.makeText(context, "Failed to send cover request", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startTradeFlow(shift: Shift, onCancel: (() -> Unit)? = null) {
        loadCoworkersForShift(shift) { teamMembers ->
            val cafeNo = shift.cafeNumber
            val filteredCoworkers = teamMembers.filter { tm ->
                val assoc = tm.associate
                val id = assoc?.employeeId
                id != null && id != authManager.getUserId() && id != "AVAILABLE_SHIFT" &&
                (assoc.cafeNumber == cafeNo ||
                 assoc.loanedCafeList?.contains(cafeNo) == true ||
                 tm.shifts?.any { it.cafeNumber == cafeNo } == true)
            }
            val coworkerNames = filteredCoworkers.associate { it.associate?.employeeId.orEmpty() to getEmployeeName(it.associate?.employeeId) }
            val favorites = scheduleCache.getFavorites()
            val coworkers = filteredCoworkers.sortedWith(
                compareByDescending<TeamMember> { favorites.contains(it.associate?.employeeId) }
                    .thenBy { coworkerNames[it.associate?.employeeId.orEmpty()].orEmpty() }
            )

            if (coworkers.isEmpty()) {
                android.widget.Toast.makeText(context, "No eligible coworkers found for this cafe.", android.widget.Toast.LENGTH_LONG).show()
                return@loadCoworkersForShift
            }

            val dialog = Dialog(requireContext())
            selectAssociateDialog = dialog
            dialog.setContentView(R.layout.dialog_select_item)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
            dialogTitle.text = "SELECT ASSOCIATE"

            val itemsContainer = dialog.findViewById<LinearLayout>(R.id.itemsContainer)
            val closeButton = dialog.findViewById<View>(R.id.closeButton)
            closeButton.setOnClickListener { dialog.cancel() }

            dialog.setOnCancelListener {
                onCancel?.invoke()
            }
            dialog.setOnDismissListener {
                if (selectAssociateDialog == dialog) {
                    selectAssociateDialog = null
                }
            }

            val searchContainer = dialog.findViewById<View>(R.id.searchContainer)
            val searchEditText = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchEditText)

            searchContainer.visibility = View.VISIBLE

            fun populateList(query: String) {
                itemsContainer.removeAllViews()
                val filtered = if (query.isBlank()) {
                    coworkers
                } else {
                    coworkers.filter { member ->
                        val name = coworkerNames[member.associate?.employeeId.orEmpty()].orEmpty()
                        name.contains(query, ignoreCase = true)
                    }
                }
                filtered.forEach { member ->
                    val name = coworkerNames[member.associate?.employeeId.orEmpty()].orEmpty()
                    val isFav = favorites.contains(member.associate?.employeeId)
                    val horizontalLayout = LinearLayout(requireContext()).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
                        
                        val nameTv = TextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                            text = name
                            textSize = 16f
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        }
                        addView(nameTv)
                        
                        if (isFav) {
                            val starIv = android.widget.ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    24.dpToPx(),
                                    24.dpToPx()
                                ).apply {
                                    marginStart = 8.dpToPx()
                                }
                                setImageResource(R.drawable.ic_star_filled)
                                setContentDescription("Favorite")
                                setColorFilter(ContextCompat.getColor(context, R.color.work_day_green))
                            }
                            addView(starIv)
                        }
                    }
                    val cardView = createCardItem(requireContext(), horizontalLayout) {
                        dialog.dismiss()
                        onCoworkerSelectedForTrade(shift, member, onCancel)
                    }
                    itemsContainer.addView(cardView)
                }
            }

            searchEditText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    populateList(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            populateList("")
            dialog.show()
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun onCoworkerSelectedForTrade(shift: Shift, coworker: TeamMember, onCancel: (() -> Unit)? = null) {
        val today = LocalDate.now()
        val futureShifts = (coworker.shifts ?: emptyList()).filter { s ->
            try {
                val shiftDate = LocalDateTime.parse(s.startDateTime).toLocalDate()
                !shiftDate.isBefore(today)
            } catch(e: Exception) { true }
        }.sortedBy { it.startDateTime }

        if (futureShifts.isEmpty()) {
            val name = getEmployeeName(coworker.associate?.employeeId)
            android.widget.Toast.makeText(context, "$name has no upcoming shifts to trade with.", android.widget.Toast.LENGTH_LONG).show()
            startTradeFlow(shift, onCancel)
            return
        }

        val dialog = Dialog(requireContext())
        selectShiftDialog = dialog
        dialog.setContentView(R.layout.dialog_select_item)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        dialogTitle.text = "SELECT SHIFT"

        val itemsContainer = dialog.findViewById<LinearLayout>(R.id.itemsContainer)
        val closeButton = dialog.findViewById<View>(R.id.closeButton)
        closeButton.setOnClickListener { dialog.cancel() }

        dialog.setOnCancelListener {
            startTradeFlow(shift, onCancel)
        }
        dialog.setOnDismissListener {
            if (selectShiftDialog == dialog) {
                selectShiftDialog = null
            }
        }

        val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
        val timeFormatter = DateTimeFormatter.ofPattern("h:mma")

        futureShifts.forEach { coworkerShift ->
            val start = LocalDateTime.parse(coworkerShift.startDateTime)
            val end = LocalDateTime.parse(coworkerShift.endDateTime)
            val timeText = "${start.format(dayFormatter)} ${start.format(timeFormatter)} - ${end.format(timeFormatter)}"
            val posText = getWorkstationDisplayName(coworkerShift.workstationId ?: coworkerShift.workstationCode, coworkerShift.workstationName)

            val linearLayout = LinearLayout(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.VERTICAL
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
                
                val timeTv = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = timeText
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                
                val posTv = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4.dpToPx()
                    }
                    text = posText
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                
                addView(timeTv)
                addView(posTv)
            }

            val cardView = createCardItem(requireContext(), linearLayout) {
                dialog.dismiss()
                onCoworkerShiftSelectedForTrade(shift, coworker, coworkerShift, onCancel)
            }
            itemsContainer.addView(cardView)
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onCoworkerShiftSelectedForTrade(shift: Shift, coworker: TeamMember, coworkerShift: TeamShift, onCancel: (() -> Unit)? = null) {
        val hasOverlap = checkTradeOverlap(scheduleData?.currentShifts ?: emptyList(), coworker.shifts ?: emptyList(), shift, coworkerShift)
        if (hasOverlap) {
            android.widget.Toast.makeText(context, "Cannot trade: this trade would create an overlapping shift.", android.widget.Toast.LENGTH_LONG).show()
            onCoworkerSelectedForTrade(shift, coworker, onCancel)
            return
        }

        val myStart = LocalDateTime.parse(shift.startDateTime)
        val myEnd = LocalDateTime.parse(shift.endDateTime)
        val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
        val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
        val myTimeText = "${myStart.format(dayFormatter)} ${myStart.format(timeFormatter)} - ${myEnd.format(timeFormatter)}"
        val myPositionText = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)

        val partnerStart = LocalDateTime.parse(coworkerShift.startDateTime)
        val partnerEnd = LocalDateTime.parse(coworkerShift.endDateTime)
        val partnerTimeText = "${partnerStart.format(dayFormatter)} ${partnerStart.format(timeFormatter)} - ${partnerEnd.format(timeFormatter)}"
        val partnerPositionText = getWorkstationDisplayName(coworkerShift.workstationId ?: coworkerShift.workstationCode, coworkerShift.workstationName)

        val partnerName = getEmployeeName(coworker.associate?.employeeId)

        val confirmMessage = "Are you sure you want to trade your $myTimeText $myPositionText shift for $partnerName's $partnerTimeText $partnerPositionText shift?"
        showConfirmationDialog(
            title = "Request Trade",
            message = confirmMessage,
            onCancel = {
                onCoworkerSelectedForTrade(shift, coworker, onCancel)
            }
        ) {
            performTradeShift(shift, coworker, coworkerShift)
        }
    }

    private fun checkTradeOverlap(myShifts: List<Shift>, coworkerShifts: List<TeamShift>, giveShift: Shift, receiveShift: TeamShift): Boolean {
        val giveStart = try { LocalDateTime.parse(giveShift.startDateTime) } catch(e: Exception) { return false }
        val giveEnd = try { LocalDateTime.parse(giveShift.endDateTime) } catch(e: Exception) { return false }
        val receiveStart = try { LocalDateTime.parse(receiveShift.startDateTime) } catch(e: Exception) { return false }
        val receiveEnd = try { LocalDateTime.parse(receiveShift.endDateTime) } catch(e: Exception) { return false }

        // Check my overlap (excluding the shift I am giving away)
        val myOverlap = myShifts.any { s ->
            s.shiftId != giveShift.shiftId &&
            try {
                val sStart = LocalDateTime.parse(s.startDateTime)
                val sEnd = LocalDateTime.parse(s.endDateTime)
                sStart.isBefore(receiveEnd) && sEnd.isAfter(receiveStart)
            } catch (e: Exception) {
                false
            }
        }
        if (myOverlap) return true

        // Check coworker overlap (excluding the shift they are giving away)
        val coworkerOverlap = coworkerShifts.any { s ->
            s.shiftId != receiveShift.shiftId &&
            try {
                val sStart = LocalDateTime.parse(s.startDateTime)
                val sEnd = LocalDateTime.parse(s.endDateTime)
                sStart.isBefore(giveEnd) && sEnd.isAfter(giveStart)
            } catch (e: Exception) {
                false
            }
        }
        return coworkerOverlap
    }

    private fun performTradeShift(shift: Shift, coworker: TeamMember, coworkerShift: TeamShift) {
        lifecycleScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("cafeNo", shift.cafeNumber?.toIntOrNull() ?: 0)
                    
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

                    val receiveAssociate = org.json.JSONObject().apply {
                        put("firstName", coworker.associate?.firstName)
                        put("lastName", coworker.associate?.lastName)
                        put("preferredName", coworker.associate?.preferredName)
                        put("employeeId", coworker.associate?.employeeId)
                    }
                    put("receiveAssociate", receiveAssociate)

                    val receiveShift = org.json.JSONObject().apply {
                        put("shiftId", coworkerShift.shiftId ?: 0)
                        put("startDateTime", coworkerShift.startDateTime)
                        put("endDateTime", coworkerShift.endDateTime)
                    }
                    put("receiveShift", receiveShift)
                }

                val success = repository.tradeShift(payload.toString())
                if (success) {
                    android.widget.Toast.makeText(context, "Trade request sent successfully", android.widget.Toast.LENGTH_SHORT).show()
                    dismissIntermediateDialogs()
                    reloadShiftDetails(shift)
                    checkNotifications()
                } else {
                    android.widget.Toast.makeText(context, "Failed to send trade request", android.widget.Toast.LENGTH_SHORT).show()
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
            return settingsPreferences.getCoworkerDisplayName(employeeId, associate.firstName, associate.lastName, associate.preferredName)
        }

        // 2. Try provided info list or scheduleData (EmployeeInfo)
        val list = infoList ?: scheduleData?.employeeInfo
        val employee = list?.find { it.employeeId == employeeId }
        if (employee != null) {
            return settingsPreferences.getCoworkerDisplayName(employeeId, employee.firstName, employee.lastName, null)
        }

        
        return "Unknown"
    }
    private fun getWorkstationDisplayName(workstationId: String, fallbackName: String?): String {
        return com.anonymousassociate.betterpantry.utils.WorkstationUtils.getDisplayName(workstationId, fallbackName)
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
                        val apkAsset = release.assets?.find { it.name.endsWith(".apk") }
                        updateUrl = apkAsset?.browser_download_url ?: release.html_url
                        showUpdateCard(updateUrl)
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
            if (url.endsWith(".apk", ignoreCase = true)) {
                updateCard.isEnabled = false
                downloadAndInstallApk(url)
            } else {
                (requireActivity() as? com.anonymousassociate.betterpantry.MainActivity)?.openBrowser(url)
            }
        }
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        lifecycleScope.launch {
            try {
                updateCardText.text = "Downloading Update... (0%)"
                val file = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(apkUrl).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw java.io.IOException("Unexpected response code: $response")

                    val body = response.body ?: throw java.io.IOException("Response body is null")
                    val contentLength = body.contentLength()
                    val cacheDir = requireContext().cacheDir
                    val apksDir = java.io.File(cacheDir, "apks").apply { mkdirs() }
                    val apkFile = java.io.File(apksDir, "update.apk")

                    val inputStream = body.byteStream()
                    val outputStream = java.io.FileOutputStream(apkFile)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            withContext(Dispatchers.Main) {
                                updateCardText.text = "Downloading Update... ($progress%)"
                            }
                        }
                    }
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    apkFile
                }

                updateCardText.text = "Installing Update..."
                checkInstallPermissionAndInstall(file)
            } catch (e: Exception) {
                e.printStackTrace()
                updateCardText.text = "Failed to download update"
                updateCard.isEnabled = true
                Toast.makeText(context, "Failed to download update: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkInstallPermissionAndInstall(file: java.io.File) {
        val ctx = context ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                pendingApkFile = file
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${ctx.packageName}")
                )
                startActivityForResult(intent, REQUEST_CODE_INSTALL_PERMISSION)
                Toast.makeText(ctx, "Please enable permission to install updates", Toast.LENGTH_LONG).show()
                return
            }
        }
        installApk(file)
    }

    private fun installApk(file: java.io.File) {
        val ctx = context ?: return
        try {
            val authority = "${ctx.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, authority, file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            updateCard.isEnabled = true
            updateCardText.text = "New update available"
        } catch (e: Exception) {
            e.printStackTrace()
            updateCard.isEnabled = true
            updateCardText.text = "New update available"
            Toast.makeText(ctx, "Installation failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_INSTALL_PERMISSION) {
            val file = pendingApkFile
            if (file != null && file.exists()) {
                pendingApkFile = null
                checkInstallPermissionAndInstall(file)
            }
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

    private fun hasCalendarPermissions(): Boolean {
        val ctx = context ?: return false
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.READ_CALENDAR
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.WRITE_CALENDAR
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestCalendarPermissions() {
        calendarPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            )
        )
    }

    private fun loadCalendars() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val calendars = withContext(Dispatchers.IO) {
                val list = mutableListOf<AndroidCalendar>()
                val projection = arrayOf(
                    android.provider.CalendarContract.Calendars._ID,
                    android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    android.provider.CalendarContract.Calendars.ACCOUNT_NAME,
                    android.provider.CalendarContract.Calendars.ACCOUNT_TYPE,
                    android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    android.provider.CalendarContract.Calendars.IS_PRIMARY
                )
                val uri = android.provider.CalendarContract.Calendars.CONTENT_URI
                try {
                    // Filter: only show contributor or editor calendars (access level >= 500)
                    val selection = "${android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= 500"
                    ctx.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndex(android.provider.CalendarContract.Calendars._ID)
                        val nameCol = cursor.getColumnIndex(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                        val accountCol = cursor.getColumnIndex(android.provider.CalendarContract.Calendars.ACCOUNT_NAME)
                        val accountTypeCol = cursor.getColumnIndex(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE)
                        val accessCol = cursor.getColumnIndex(android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                        val primaryCol = cursor.getColumnIndex(android.provider.CalendarContract.Calendars.IS_PRIMARY)
                        
                        if (idCol != -1 && nameCol != -1 && accountCol != -1 && accountTypeCol != -1 && accessCol != -1 && primaryCol != -1) {
                            while (cursor.moveToNext()) {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "Calendar"
                                val account = cursor.getString(accountCol) ?: "Unknown"
                                val accountType = cursor.getString(accountTypeCol) ?: "com.google"
                                val access = cursor.getInt(accessCol)
                                val primary = cursor.getInt(primaryCol)
                                list.add(AndroidCalendar(id, name, account, accountType, access, primary))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // Sort so that: Work contains first, then Home contains, then isPrimary == 1, then alphabetical
                list.sortedWith(compareBy<AndroidCalendar> {
                    val name = it.displayName.lowercase()
                    !name.contains("work")
                }.thenBy {
                    val name = it.displayName.lowercase()
                    !name.contains("home")
                }.thenBy {
                    it.isPrimary != 1
                }.thenBy {
                    it.displayName
                })
            }
            systemCalendars = calendars
            setupCalendarDropdown()
            syncSelectedCalendar()
            if (pendingShowExportDialog) {
                pendingShowExportDialog = false
                showExportDialog()
            }
        }
    }

    private fun selectCalendar(id: Long) {
        val ctx = context ?: return
        selectedCalendarId = id
        val prefs = ctx.getSharedPreferences("BetterPantryPrefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_selected_calendar_id", id).apply()
        
        // Update home screen dropdown text
        val selectedCal = systemCalendars.firstOrNull { it.id == id }
        selectedCal?.let {
            calendarDropdown.setText(it.displayName, false)
        }
        
        // If export dialog's dropdown is visible, update it too
        activeDialogCalendarDropdown?.setText(selectedCal?.displayName ?: "", false)

        syncSelectedCalendar()
    }

    private fun syncSelectedCalendar() {
        val ctx = context ?: return
        val calId = selectedCalendarId ?: return
        val targetCal = systemCalendars.firstOrNull { it.id == calId } ?: return
        try {
            val account = android.accounts.Account(targetCal.accountName, targetCal.accountType)
            val extras = android.os.Bundle().apply {
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            android.content.ContentResolver.requestSync(
                account,
                android.provider.CalendarContract.AUTHORITY,
                extras
            )
            android.util.Log.d("BetterPantryDebug", "Requested manual sync for account: ${targetCal.accountName}, type: ${targetCal.accountType}")
        } catch (e: Exception) {
            android.util.Log.e("BetterPantryDebug", "Failed to request sync: ${e.message}", e)
        }
    }

    private fun updateCalendarDropdownSelection() {
        val ctx = context ?: return
        if (systemCalendars.isNotEmpty()) {
            val prefs = ctx.getSharedPreferences("BetterPantryPrefs", Context.MODE_PRIVATE)
            val lastSelectedId = prefs.getLong("last_selected_calendar_id", -1L)
            var defaultIndex = 0
            if (lastSelectedId != -1L) {
                val idx = systemCalendars.indexOfFirst { it.id == lastSelectedId }
                if (idx != -1) {
                    defaultIndex = idx
                }
            }
            val id = systemCalendars[defaultIndex].id
            selectCalendar(id)
        } else {
            calendarDropdown.setText("", false)
        }
    }

    private fun setupCalendarDropdown() {
        val ctx = context ?: return
        val names = systemCalendars.map { it.displayName }
        
        calendarDropdown.setDropDownBackgroundDrawable(
            androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_dropdown_popup)
        )
        
        val adapter = android.widget.ArrayAdapter(
            ctx,
            R.layout.item_dropdown_menu,
            names
        )
        calendarDropdown.setAdapter(adapter)

        calendarDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position in systemCalendars.indices) {
                val selected = systemCalendars[position]
                selectCalendar(selected.id)
            }
        }

        updateCalendarDropdownSelection()
    }

    private fun updateExportSubtitleText() {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("M/d/yy")
        val rangeStr = "${selectedStartDate.format(formatter)} - ${selectedEndDate.format(formatter)}"
        exportSubtitleText.text = rangeStr
    }

    private fun getDefaultWeekRange(dates: List<LocalDate>): Pair<LocalDate, LocalDate> {
        val shifts = currentScheduledShifts
        val weeks = listOf(
            dates.subList(0, 7),
            dates.subList(7, 14),
            dates.subList(14, 21),
            dates.subList(21, 28)
        )
        
        var bestWeek = weeks.first()
        for (week in weeks.reversed()) {
            val weekStart = week.first()
            val weekEnd = week.last()
            val count = shifts.count {
                try {
                    val date = LocalDateTime.parse(it.startDateTime).toLocalDate()
                    !date.isBefore(weekStart) && !date.isAfter(weekEnd)
                } catch(e: Exception) { false }
            }
            if (count > 0) {
                bestWeek = week
                break
            }
        }
        return Pair(bestWeek.first(), bestWeek.last())
    }

    private fun showExportDialog() {
        val ctx = context ?: return
        syncSelectedCalendar()
        val dialog = Dialog(ctx)
        dialog.setContentView(R.layout.dialog_export_calendar)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val dialogCloseButton = dialog.findViewById<android.widget.ImageButton>(R.id.dialogCloseButton)
        val dialogCalendarRecyclerView = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.dialogCalendarRecyclerView)
        val dialogCalendarDropdown = dialog.findViewById<android.widget.AutoCompleteTextView>(R.id.dialogCalendarDropdown)
        val dialogExportButton = dialog.findViewById<android.widget.Button>(R.id.dialogExportButton)

        // Track dialog calendar dropdown for bidirectional sync
        activeDialogCalendarDropdown = dialogCalendarDropdown
        dialog.setOnDismissListener {
            activeDialogCalendarDropdown = null
        }

        // Close button click listener
        dialogCloseButton?.setOnClickListener {
            dialog.dismiss()
        }

        // Calculate the same 28 dates shown on the home page calendar
        val today = LocalDate.now()
        val startDate = if (today.dayOfWeek == DayOfWeek.WEDNESDAY) {
            today
        } else {
            today.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY))
        }
        val dates = (0 until 28).map { startDate.plusDays(it.toLong()) }

        // Find the default range: latest week with shifts, or fallback to first week
        val defaultRange = getDefaultWeekRange(dates)
        var tempStartDate = defaultRange.first
        var tempEndDate = defaultRange.second

        // Setup dialog calendar dropdown
        val names = systemCalendars.map { it.displayName }
        dialogCalendarDropdown?.setDropDownBackgroundDrawable(
            androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_dropdown_popup)
        )
        val dialogAdapter = android.widget.ArrayAdapter(
            ctx,
            R.layout.item_dropdown_menu,
            names
        )
        dialogCalendarDropdown?.setAdapter(dialogAdapter)
        
        val selectedCal = systemCalendars.firstOrNull { it.id == selectedCalendarId }
        dialogCalendarDropdown?.setText(selectedCal?.displayName ?: "", false)

        dialogCalendarDropdown?.setOnItemClickListener { _, _, position, _ ->
            if (position in systemCalendars.indices) {
                val selected = systemCalendars[position]
                selectCalendar(selected.id)
            }
        }

        // Compute workDates (days containing work shifts)
        val workDates = mutableSetOf<LocalDate>()
        for (shift in currentScheduledShifts) {
            val startStr = shift.startDateTime ?: continue
            try {
                val date = java.time.LocalDateTime.parse(startStr).toLocalDate()
                workDates.add(date)
            } catch (e: Exception) {}
        }

        // Parse time off dates (approved and pending)
        val approvedTimeOffDates = mutableSetOf<LocalDate>()
        val pendingTimeOffDates = mutableSetOf<LocalDate>()
        scheduleCache.getTimeOff()?.forEach { req ->
            if (req.timeOffDate != null) {
                try {
                    val date = LocalDate.parse(req.timeOffDate)
                    if (req.status == "APPROVED") {
                        approvedTimeOffDates.add(date)
                    } else if (req.status == "PENDING") {
                        pendingTimeOffDates.add(date)
                    }
                } catch(e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Setup the range calendar grid recycler view
        dialogCalendarRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 7)
        val adapter = CalendarRangeAdapter(dates, today, tempStartDate, tempEndDate, workDates, approvedTimeOffDates, pendingTimeOffDates) { start, end ->
            tempStartDate = start
            tempEndDate = end
        }
        dialogCalendarRecyclerView.adapter = adapter

        dialogExportButton.setOnClickListener {
            val calId = selectedCalendarId
            if (calId == null) {
                android.widget.Toast.makeText(ctx, "Please select a target calendar first.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show progress dialog during sync request and calculation
            val loadingDialog = android.app.Dialog(ctx)
            loadingDialog.setContentView(R.layout.dialog_loading)
            loadingDialog.window?.setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            loadingDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            loadingDialog.setCancelable(false)
            loadingDialog.findViewById<android.widget.TextView>(R.id.loadingMessage).text = "Syncing calendar..."
            loadingDialog.show()

            syncSelectedCalendar()
            dialogExportButton.isEnabled = false

            lifecycleScope.launch {
                val events = getMergedEventsForRange(tempStartDate, tempEndDate)
                val changes = withContext(Dispatchers.IO) {
                    calculateCalendarChanges(events, tempStartDate, tempEndDate, calId)
                }

                loadingDialog.dismiss()
                dialogExportButton.isEnabled = true

                if (changes == null) {
                    android.widget.Toast.makeText(ctx, "Error calculating changes.", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val totalChanges = changes.additions + changes.modifications + changes.deletions
                if (totalChanges == 0) {
                    val message = if (events.isEmpty()) {
                        "No shifts to modify"
                    } else {
                        "Shifts already on calendar. If you recently deleted shifts, please wait a moment for sync to complete."
                    }

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val sb = java.lang.StringBuilder()
                    if (changes.deletions > 0) {
                        val word = if (changes.deletions == 1) "event" else "events"
                        sb.append("This will delete ${changes.deletions} calendar $word\n")
                    }
                    if (changes.additions > 0) {
                        val word = if (changes.additions == 1) "event" else "events"
                        sb.append("This will add ${changes.additions} calendar $word\n")
                    }
                    if (changes.modifications > 0) {
                        val word = if (changes.modifications == 1) "event" else "events"
                        sb.append("This will modify ${changes.modifications} calendar $word\n")
                    }

                    val confirmMessage = sb.toString().trim()

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("Are you sure?")
                        .setMessage(confirmMessage)
                        .setPositiveButton("Yes") { _, _ ->
                            selectedStartDate = tempStartDate
                            selectedEndDate = tempEndDate
                            updateExportSubtitleText()

                            dialog.dismiss()
                            exportEventsToCalendar(changes)
                        }
                        .setNegativeButton("No", null)
                        .show()
                }
            }
        }

        dialog.show()
        
        // Post-show layout sizing to ensure 90% screen width constraint
        val metrics = ctx.resources.displayMetrics
        val width = (metrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun getMergedEventsForRange(startDate: LocalDate, endDate: LocalDate): List<CalendarEvent> {
        val segments = mutableListOf<ShiftSegment>()
        for (shift in currentScheduledShifts) {
            val startStr = shift.startDateTime ?: continue
            val endStr = shift.endDateTime ?: continue
            val startDateTime = try {
                java.time.LocalDateTime.parse(startStr)
            } catch (e: Exception) {
                continue
            }
            val shiftDate = startDateTime.toLocalDate()
            if (shiftDate.isBefore(startDate) || shiftDate.isAfter(endDate)) {
                continue
            }

            val constituent = shift.combinedShifts ?: listOf(shift)
            for (subShift in constituent) {
                val subStartStr = subShift.startDateTime ?: continue
                val subEndStr = subShift.endDateTime ?: continue
                val subStart = try {
                    java.time.LocalDateTime.parse(subStartStr)
                } catch (e: Exception) {
                    continue
                }
                val subEnd = try {
                    java.time.LocalDateTime.parse(subEndStr)
                } catch (e: Exception) {
                    continue
                }
                val pos = getWorkstationDisplayName(
                    subShift.workstationId ?: subShift.workstationCode ?: "",
                    subShift.workstationName
                )
                segments.add(ShiftSegment(subStart, subEnd, pos, subShift.cafeNumber, subShift.shiftId))
            }
        }

        val sortedSegments = segments.sortedWith(compareBy({ it.start }, { it.end }))

        val positionCombined = mutableListOf<ShiftSegment>()
        for (seg in sortedSegments) {
            if (positionCombined.isEmpty()) {
                positionCombined.add(seg)
            } else {
                val last = positionCombined.last()
                val gap = java.time.Duration.between(last.end, seg.start).toMinutes()
                if (last.position == seg.position && last.cafeNumber == seg.cafeNumber && gap <= 5 && !seg.start.isBefore(last.start)) {
                    val newEnd = if (seg.end.isAfter(last.end)) seg.end else last.end
                    val newShiftId = listOfNotNull(last.shiftId, seg.shiftId).filter { it.isNotEmpty() }.joinToString(",")
                    positionCombined[positionCombined.size - 1] = last.copy(end = newEnd, shiftId = newShiftId)
                } else {
                    positionCombined.add(seg)
                }
            }
        }

        val events = mutableListOf<CalendarEvent>()
        for (seg in positionCombined) {
            if (events.isEmpty()) {
                events.add(CalendarEvent(seg.start, seg.end, seg.cafeNumber, mutableListOf(seg)))
            } else {
                val lastEvent = events.last()
                val gap = java.time.Duration.between(lastEvent.end, seg.start).toMinutes()
                if (lastEvent.cafeNumber == seg.cafeNumber && gap <= 5 && !seg.start.isBefore(lastEvent.start)) {
                    if (seg.end.isAfter(lastEvent.end)) {
                        lastEvent.end = seg.end
                    }
                    lastEvent.segments.add(seg)
                } else {
                    events.add(CalendarEvent(seg.start, seg.end, seg.cafeNumber, mutableListOf(seg)))
                }
            }
        }

        return events
    }

    private data class ExistingCalendarEvent(
        val id: Long,
        val title: String,
        val description: String,
        val start: Long,
        val end: Long,
        val shiftIds: List<String>
    )

    private fun exportEventsToCalendar(changes: CalendarChanges) {
        val ctx = context ?: return

        // Show progress dialog during export execution
        val loadingDialog = android.app.Dialog(ctx)
        loadingDialog.setContentView(R.layout.dialog_loading)
        loadingDialog.window?.setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        loadingDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        loadingDialog.setCancelable(false)
        loadingDialog.findViewById<android.widget.TextView>(R.id.loadingMessage).text = "Exporting shifts..."
        loadingDialog.show()

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    if (changes.operations.isNotEmpty()) {
                        ctx.contentResolver.applyBatch(android.provider.CalendarContract.AUTHORITY, changes.operations)
                    }
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    android.util.Log.e("BetterPantryDebug", "Failed to apply batch calendar operations", e)
                    false
                }
            }

            loadingDialog.dismiss()

            if (success) {
                syncSelectedCalendar()
                android.widget.Toast.makeText(ctx, "Calendar sync complete.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx, "Failed to sync shifts. Please try again.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private data class CalendarChanges(
        val additions: Int,
        val modifications: Int,
        val deletions: Int,
        val operations: java.util.ArrayList<android.content.ContentProviderOperation>,
        val targetEvents: List<CalendarEvent>,
        val hasExistingEvents: Boolean
    )

    private fun getSegmentIds(event: CalendarEvent, zoneId: java.time.ZoneId): List<String> {
        return event.segments.flatMap { seg ->
            if (!seg.shiftId.isNullOrEmpty()) {
                seg.shiftId.split(",")
            } else {
                val sM = seg.start.atZone(zoneId).toInstant().toEpochMilli()
                val eM = seg.end.atZone(zoneId).toInstant().toEpochMilli()
                listOf("$sM-$eM-${seg.position}")
            }
        }
    }

    private fun isShiftPickedUp(shiftIds: List<String>, start: java.time.LocalDateTime, end: java.time.LocalDateTime, position: String): Boolean {
        val track = scheduleData?.track ?: return false
        return track.any { item ->
            val request = item.primaryShiftRequest
            val shift = request?.shift
            if (shift != null) {
                val requestShiftId = shift.shiftId
                val match = if (requestShiftId != null && shiftIds.isNotEmpty()) {
                    shiftIds.contains(requestShiftId)
                } else {
                    try {
                        val s = java.time.LocalDateTime.parse(shift.startDateTime)
                        val e = java.time.LocalDateTime.parse(shift.endDateTime)
                        s == start && e == end && (shift.workstationName == position || shift.workstationId == position)
                    } catch (ex: Exception) { false }
                }
                match && (request.state == "APPROVED" || request.state == "COMPLETED" || request.state == "ACCEPTED")
            } else false
        }
    }

    private fun calculateCalendarChanges(
        targetEvents: List<CalendarEvent>,
        startDate: LocalDate,
        endDate: LocalDate,
        calendarId: Long
    ): CalendarChanges? {
        val ctx = context ?: return null
        try {
            val zoneId = java.time.ZoneId.systemDefault()
            val startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endMillis = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

            val existingBpEvents = mutableListOf<ExistingCalendarEvent>()
            val eventUri = android.provider.CalendarContract.Events.CONTENT_URI

            // DEBUG LOGGING START
            android.util.Log.d("BetterPantryDebug", "--- CHANGES: startDate=$startDate, endDate=$endDate, calendarId=$calendarId ---")
            android.util.Log.d("BetterPantryDebug", "Target events list count: ${targetEvents.size}")
            targetEvents.forEachIndexed { i, ev ->
                android.util.Log.d("BetterPantryDebug", "Target event #$i: start=${ev.start}, end=${ev.end}, shiftIds=${getSegmentIds(ev, zoneId)}")
            }
            try {
                val debugProjection = arrayOf(
                    android.provider.CalendarContract.Events._ID,
                    android.provider.CalendarContract.Events.TITLE,
                    android.provider.CalendarContract.Events.DESCRIPTION,
                    android.provider.CalendarContract.Events.DTSTART,
                    android.provider.CalendarContract.Events.DTEND,
                    android.provider.CalendarContract.Events.DELETED
                )
                val debugSelection = "${android.provider.CalendarContract.Events.CALENDAR_ID} = ? AND " +
                                     "${android.provider.CalendarContract.Events.DTSTART} >= ? AND " +
                                     "${android.provider.CalendarContract.Events.DTSTART} <= ?"
                val debugSelectionArgs = arrayOf(calendarId.toString(), startMillis.toString(), endMillis.toString())
                ctx.contentResolver.query(eventUri, debugProjection, debugSelection, debugSelectionArgs, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(android.provider.CalendarContract.Events._ID)
                    val titleCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.TITLE)
                    val descCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DESCRIPTION)
                    val startCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DTSTART)
                    val endCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DTEND)
                    val deletedCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DELETED)
                    android.util.Log.d("BetterPantryDebug", "CHANGES RAW EVENTS COUNT: ${cursor.count}")
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: ""
                        val desc = cursor.getString(descCol) ?: ""
                        val startVal = cursor.getLong(startCol)
                        val endVal = cursor.getLong(endCol)
                        val deletedVal = cursor.getInt(deletedCol)
                        val startDt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startVal), zoneId)
                        val endDt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(endVal), zoneId)
                        android.util.Log.d("BetterPantryDebug", "CHANGES RAW EVENT: id=$id, title='$title', deleted=$deletedVal, start=$startDt, end=$endDt, hasBP=${desc.contains("[BetterPantry]", ignoreCase = true)}")
                    }
                }
            } catch (ex: Exception) {
                android.util.Log.e("BetterPantryDebug", "Error running debug query: ${ex.message}", ex)
            }
            // DEBUG LOGGING END

            val projection = arrayOf(
                android.provider.CalendarContract.Events._ID,
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DESCRIPTION,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND
            )
            
            val selection = "${android.provider.CalendarContract.Events.CALENDAR_ID} = ? AND " +
                            "${android.provider.CalendarContract.Events.DTSTART} >= ? AND " +
                            "${android.provider.CalendarContract.Events.DTSTART} <= ? AND " +
                            "${android.provider.CalendarContract.Events.DELETED} = 0"
            val selectionArgs = arrayOf(calendarId.toString(), startMillis.toString(), endMillis.toString())

            ctx.contentResolver.query(eventUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(android.provider.CalendarContract.Events._ID)
                val titleCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.TITLE)
                val descCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DESCRIPTION)
                val startCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DTSTART)
                val endCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DTEND)
                
                if (idCol != -1 && titleCol != -1 && descCol != -1 && startCol != -1 && endCol != -1) {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: ""
                        val desc = cursor.getString(descCol) ?: ""
                        val startVal = cursor.getLong(startCol)
                        val endVal = cursor.getLong(endCol)
                        
                        if (desc.contains("[BetterPantry]", ignoreCase = true)) {
                            val shiftIds = mutableListOf<String>()
                            val lines = desc.split("\n")
                            val shiftLine = lines.find { it.startsWith("Shift IDs:") }
                            if (shiftLine != null) {
                                val idsStr = shiftLine.removePrefix("Shift IDs:").trim()
                                if (idsStr.isNotEmpty()) {
                                    shiftIds.addAll(idsStr.split(","))
                                }
                            }
                            existingBpEvents.add(ExistingCalendarEvent(id, title, desc, startVal, endVal, shiftIds))
                        }
                    }
                }
            }

            var additions = 0
            var modifications = 0
            var deletions = 0

            val operations = java.util.ArrayList<android.content.ContentProviderOperation>()
            val matchedExistingEventIds = mutableSetOf<Long>()
            val targetEventsToInsert = targetEvents.toMutableList()

            for (existing in existingBpEvents) {
                val matchedTargetEvent = targetEventsToInsert.find { target ->
                    val targetIds = getSegmentIds(target, zoneId)
                    val hasOverlap = existing.shiftIds.any { it in targetIds }
                    
                    hasOverlap || run {
                        val existingStart = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(existing.start), zoneId)
                        val existingEnd = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(existing.end), zoneId)
                        existingStart == target.start && existingEnd == target.end
                    }
                }

                if (matchedTargetEvent != null) {
                    val targetStartMillis = matchedTargetEvent.start.atZone(zoneId).toInstant().toEpochMilli()
                    val targetEndMillis = matchedTargetEvent.end.atZone(zoneId).toInstant().toEpochMilli()

                    val descBody = if (matchedTargetEvent.segments.size == 1) {
                        matchedTargetEvent.segments[0].position
                    } else {
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mma", java.util.Locale.US)
                        matchedTargetEvent.segments.joinToString("\n") { seg ->
                            val startStr = seg.start.format(formatter)
                            val endStr = seg.end.format(formatter)
                            "$startStr - $endStr ${seg.position}"
                        }
                    }
                    val targetDesc = "$descBody\n\n[BetterPantry]\nShift IDs: ${getSegmentIds(matchedTargetEvent, zoneId).joinToString(",")}"

                    val cafeNo = matchedTargetEvent.cafeNumber
                    val cafeInfo = scheduleData?.cafeList?.firstOrNull {
                        settingsPreferences.getCafeNumberFromDepartment(it.departmentName, it.address?.addressLine) == cafeNo
                    }
                    val address = cafeInfo?.address ?: cafeNo?.let { settingsPreferences.getAddressFromSavedName(it) }
                    val addressLine = address?.addressLine ?: ""
                    val city = address?.city ?: ""
                    val state = address?.state ?: ""
                    val zipCode = address?.zipCode ?: ""
                    val suffix = listOf(addressLine, city, state, zipCode).filter { it.isNotEmpty() }.joinToString(", ")
                    val targetLocation = if (suffix.isNotEmpty()) suffix else settingsPreferences.getCafeDisplayName(cafeNo, scheduleData?.cafeList)

                    if (existing.start != targetStartMillis || existing.end != targetEndMillis || 
                        existing.description != targetDesc || existing.title != "Panera Shift") {
                        modifications++

                        val updateValues = android.content.ContentValues().apply {
                            put(android.provider.CalendarContract.Events.DTSTART, targetStartMillis)
                            put(android.provider.CalendarContract.Events.DTEND, targetEndMillis)
                            put(android.provider.CalendarContract.Events.DESCRIPTION, targetDesc)
                            put(android.provider.CalendarContract.Events.EVENT_LOCATION, targetLocation)
                            put(android.provider.CalendarContract.Events.TITLE, "Panera Shift")
                        }
                        val updateUri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, existing.id)
                        val op = android.content.ContentProviderOperation.newUpdate(updateUri)
                            .withValues(updateValues)
                            .build()
                        operations.add(op)
                    }

                    matchedExistingEventIds.add(existing.id)
                    targetEventsToInsert.remove(matchedTargetEvent)
                } else {
                    val existingStart = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(existing.start), zoneId)
                    val existingEnd = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(existing.end), zoneId)
                    
                    val descBody = existing.description.substringBefore("\n\n")
                    val positionName = if (descBody.contains(" - ")) {
                        val postDash = descBody.substringAfterLast(" - ")
                        postDash.substringAfter(" ").trim()
                    } else {
                        descBody.trim()
                    }
                    
                    if (isShiftPickedUp(existing.shiftIds, existingStart, existingEnd, positionName)) {
                        deletions++

                        val deleteUri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, existing.id)
                        val op = android.content.ContentProviderOperation.newDelete(deleteUri).build()
                        operations.add(op)
                    }
                }
            }

            additions = targetEventsToInsert.size
            for (event in targetEventsToInsert) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(android.provider.CalendarContract.Events.TITLE, "Panera Shift")

                    val descBody = if (event.segments.size == 1) {
                        event.segments[0].position
                    } else {
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mma", java.util.Locale.US)
                        event.segments.joinToString("\n") { seg ->
                            val startStr = seg.start.format(formatter)
                            val endStr = seg.end.format(formatter)
                            "$startStr - $endStr ${seg.position}"
                        }
                    }
                    val desc = "$descBody\n\n[BetterPantry]\nShift IDs: ${getSegmentIds(event, zoneId).joinToString(",")}"
                    put(android.provider.CalendarContract.Events.DESCRIPTION, desc)

                    val cafeNo = event.cafeNumber
                    val cafeInfo = scheduleData?.cafeList?.firstOrNull {
                        settingsPreferences.getCafeNumberFromDepartment(it.departmentName, it.address?.addressLine) == cafeNo
                    }
                    val address = cafeInfo?.address ?: cafeNo?.let { settingsPreferences.getAddressFromSavedName(it) }
                    val addressLine = address?.addressLine ?: ""
                    val city = address?.city ?: ""
                    val state = address?.state ?: ""
                    val zipCode = address?.zipCode ?: ""
                    val suffix = listOf(addressLine, city, state, zipCode).filter { it.isNotEmpty() }.joinToString(", ")
                    val locationStr = if (suffix.isNotEmpty()) suffix else settingsPreferences.getCafeDisplayName(cafeNo, scheduleData?.cafeList)

                    put(android.provider.CalendarContract.Events.EVENT_LOCATION, locationStr)

                    val startVal = event.start.atZone(zoneId).toInstant().toEpochMilli()
                    val endVal = event.end.atZone(zoneId).toInstant().toEpochMilli()

                    put(android.provider.CalendarContract.Events.DTSTART, startVal)
                    put(android.provider.CalendarContract.Events.DTEND, endVal)
                    put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, zoneId.id)
                }

                val op = android.content.ContentProviderOperation.newInsert(android.provider.CalendarContract.Events.CONTENT_URI)
                    .withValues(values)
                    .build()
                operations.add(op)
            }

            return CalendarChanges(additions, modifications, deletions, operations, targetEvents, existingBpEvents.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    companion object {
        private var hasCheckedForUpdates = false
        private var updateAvailable = false
        private var updateUrl: String? = null
    }
}

private data class AndroidCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val accessLevel: Int,
    val isPrimary: Int
)

private data class ShiftSegment(
    val start: java.time.LocalDateTime,
    val end: java.time.LocalDateTime,
    val position: String,
    val cafeNumber: String?,
    val shiftId: String? = null
)

private data class CalendarEvent(
    var start: java.time.LocalDateTime,
    var end: java.time.LocalDateTime,
    val cafeNumber: String?,
    val segments: MutableList<ShiftSegment>
)

private class CalendarRangeAdapter(
    private val dates: List<java.time.LocalDate>,
    private val today: java.time.LocalDate,
    private var selectedStart: java.time.LocalDate,
    private var selectedEnd: java.time.LocalDate,
    private val workDates: Set<java.time.LocalDate>,
    private val approvedTimeOffDates: Set<java.time.LocalDate>,
    private val pendingTimeOffDates: Set<java.time.LocalDate>,
    private val onRangeChanged: (java.time.LocalDate, java.time.LocalDate) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<CalendarRangeAdapter.RangeViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RangeViewHolder {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_range_day, parent, false)
        return RangeViewHolder(view)
    }

    override fun onBindViewHolder(holder: RangeViewHolder, position: Int) {
        holder.bind(dates[position])
    }

    override fun getItemCount() = dates.size

    inner class RangeViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        private val card: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.rangeDateCard)
        private val dayText: android.widget.TextView = itemView.findViewById(R.id.rangeDateText)

        fun bind(date: java.time.LocalDate) {
            dayText.text = date.dayOfMonth.toString()

            val isSelected = !date.isBefore(selectedStart) && !date.isAfter(selectedEnd)
            val isToday = date == today
            val hasWorkShift = workDates.contains(date)
            val isApprovedOff = approvedTimeOffDates.contains(date)
            val isPendingOff = pendingTimeOffDates.contains(date)

            // Determine Background and Text color
            // Priority: Shift > Approved Off > Pending Off > Default
            when {
                hasWorkShift -> {
                    card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.work_day_green))
                    dayText.setTextColor(android.graphics.Color.WHITE)
                }
                isApprovedOff -> {
                    card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.time_off_pastel_yellow))
                    dayText.setTextColor(android.graphics.Color.WHITE)
                }
                isPendingOff -> {
                    card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark))
                    dayText.setTextColor(android.graphics.Color.WHITE)
                }
                else -> {
                    card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.card_background_color))
                    dayText.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.text_primary))
                }
            }

            // Determine border stroke width and color
            val density = itemView.context.resources.displayMetrics.density
            if (isSelected) {
                card.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK))
                card.setStrokeWidth((3 * density).toInt())
            } else if (isToday) {
                card.setStrokeColor(androidx.core.content.ContextCompat.getColorStateList(itemView.context, R.color.work_day_green))
                card.setStrokeWidth((2 * density).toInt())
            } else {
                card.setStrokeColor(androidx.core.content.ContextCompat.getColorStateList(itemView.context, R.color.calendar_border))
                card.setStrokeWidth((1 * density).toInt())
            }

            card.setOnClickListener {
                if (selectedStart == selectedEnd && !date.isBefore(selectedStart)) {
                    selectedEnd = date
                } else {
                    selectedStart = date
                    selectedEnd = date
                }
                notifyDataSetChanged()
                onRangeChanged(selectedStart, selectedEnd)
            }
        }
    }
}


package com.anonymousassociate.betterpantry.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.anonymousassociate.betterpantry.AuthManager
import com.anonymousassociate.betterpantry.PantryApiService
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.models.Associate
import com.anonymousassociate.betterpantry.models.TeamMember
import com.anonymousassociate.betterpantry.ui.adapters.PeopleAdapter
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.Lifecycle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PeopleFragment : Fragment() {

    private lateinit var authManager: AuthManager
    private val repository by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository }
    private val scheduleCache by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository.let { ScheduleCache(requireContext()) } }
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchBar: EditText
    private lateinit var emptyStateText: TextView
    private lateinit var updatedText: TextView
    private lateinit var adapter: PeopleAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var settingsPreferences: com.anonymousassociate.betterpantry.SettingsPreferences

    private var allAssociates: List<Associate> = emptyList()
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateTimeRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_people, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authManager = AuthManager(requireContext())
        settingsPreferences = com.anonymousassociate.betterpantry.SettingsPreferences(requireContext())
        // apiService and scheduleCache initialized via lazy/local
        
        recyclerView = view.findViewById(R.id.peopleRecyclerView)
        searchBar = view.findViewById(R.id.searchBar)
        
        if (searchBar.text.isNullOrEmpty()) {
            searchBar.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_people, 0, 0, 0)
        }
        
        val calendarButton = view.findViewById<ImageButton>(R.id.calendarButton)
        calendarButton.setOnClickListener {
            val scheduleFragment = ScheduleFragment()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, scheduleFragment)
                .addToBackStack(null)
                .commit()
        }
        
        emptyStateText = view.findViewById(R.id.emptyStateText)
        updatedText = view.findViewById(R.id.updatedText)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(swipeRefreshLayout) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Apply top padding to move header down. 
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            
            // Adjust refresh indicator position
            val refreshTarget = bars.top + (32 * resources.displayMetrics.density).toInt()
            (v as androidx.swiperefreshlayout.widget.SwipeRefreshLayout).setProgressViewOffset(false, 0, refreshTarget)
            
            insets
        }

        val greenColor = ContextCompat.getColor(requireContext(), R.color.work_day_green)
        val backgroundColor = ContextCompat.getColor(requireContext(), R.color.card_background_color)
        swipeRefreshLayout.setColorSchemeColors(greenColor)
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(backgroundColor)

        swipeRefreshLayout.setOnRefreshListener {
            loadPeople(forceRefresh = true)
        }

        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = PeopleAdapter(
            emptyList(),
            scheduleCache.getFavorites(),
            emptyMap(),
            emptyList(),
            settingsPreferences,
            emptySet(),
            { associate ->
                val fragment = PeerScheduleFragment.newInstance(associate)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            { employeeId ->
                scheduleCache.toggleFavorite(employeeId)
                adapter.updateFavorites(scheduleCache.getFavorites())
                com.anonymousassociate.betterpantry.widgets.WidgetUpdater.updateAllWidgets(requireContext())
            },
            { associate ->
                showNicknameDialog(associate)
            },
            {
                settingsPreferences.resetAllNicknamesAndHideLastName()
                scheduleCache.clearFavorites()
                adapter.updateFavorites(emptySet())
                com.anonymousassociate.betterpantry.widgets.WidgetUpdater.updateAllWidgets(requireContext())
                refreshDataFromCache()
            }
        )
        recyclerView.adapter = adapter

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updateEmptyState()
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }
        })


        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (searchBar.hasFocus()) {
                        searchBar.clearFocus()
                        val imm = ContextCompat.getSystemService(requireContext(), android.view.inputmethod.InputMethodManager::class.java)
                        imm?.hideSoftInputFromWindow(searchBar.windowToken, 0)
                    }
                }
            }
        })
        
        // Detect keyboard close to clear focus
        val rootView = view.rootView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.height
            val keypadHeight = screenHeight - r.bottom
            
            // If keyboard height is small (closed) and search has focus, clear it
            if (keypadHeight < screenHeight * 0.15 && searchBar.hasFocus()) {
                searchBar.clearFocus()
            }
        }

        searchBar.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val drawableEnd = searchBar.compoundDrawablesRelative[2]
                if (drawableEnd != null) {
                    val bounds = drawableEnd.bounds
                    val x = event.x.toInt()
                    // Check if touch is on the drawable (right side)
                    // Simplified check: x > width - paddingEnd - drawableWidth - extra buffer
                    // Or just x > width - paddingEnd - drawableWidth
                    if (x >= (v.width - v.paddingEnd - bounds.width())) {
                        searchBar.text.clear()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
                updateEmptyState()
                
                val endDrawable = if (s.isNullOrEmpty()) 0 else R.drawable.ic_close
                searchBar.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_people, 
                    0, 
                    endDrawable, 
                    0
                )
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val lastUpdate = scheduleCache.getLastUpdateTime()
        if (lastUpdate > 0) {
            updateTimestamp()
            startUpdateTimer()
        }

        loadPeople()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdateTimer()
    }

    override fun onResume() {
        super.onResume()
        updateTimestamp()
        loadPeople()
        startUpdateTimer()
    }

    private fun startUpdateTimer() {
        stopUpdateTimer()
        updateTimeRunnable = object : Runnable {
            override fun run() {
                updateTimestamp()
                
                if (scheduleCache.isTeamScheduleStale()) {
                    loadPeople()
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



    fun refreshDataFromCache() {
        if (!isAdded) return
        val cachedTeam = scheduleCache.getTeamSchedule() // Unified cache
        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedTeam != null) {
             processTeamMembers(cachedTeam, cachedSchedule)
        }
        updateTimestamp()
    }

    private fun loadPeople(forceRefresh: Boolean = false) {
        // Always try to load from cache first to show something immediately
        refreshDataFromCache()

        val cachedTeam = scheduleCache.getTeamSchedule()
        val hasCachedData = cachedTeam != null && cachedTeam.isNotEmpty()
        val willRefresh = forceRefresh || !hasCachedData || scheduleCache.isTeamScheduleStale()

        if (!willRefresh) {
            swipeRefreshLayout.isRefreshing = false
            return
        }

        // Show refresh indicator since we are updating something
        swipeRefreshLayout.post {
            swipeRefreshLayout.isRefreshing = true
        }
        
        lifecycleScope.launch {
            try {
                fetchTeamMembers(forceRefresh)
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private suspend fun fetchTeamMembers(forceRefresh: Boolean) {
        // Try cache first to ensure we have valid data if network fails.
        // On a force-refresh, attempt to fetch a fresh schedule.
        var schedule = repository.getSchedule(forceRefresh = false)
        if (forceRefresh || schedule == null) {
            val freshSchedule = repository.getSchedule(forceRefresh = true)
            if (freshSchedule != null) {
                schedule = freshSchedule
            }
        }

        if (forceRefresh) {
            // Keep availability/max hours/time off in sync on force refresh
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                repository.getAvailability(true)
                repository.getMaxHours(true)
                repository.getTimeOff(true)
            }
        }

        if (schedule != null) {
            val enabledCafesList = settingsPreferences.getEnabledCafeNumbers(
                schedule,
                scheduleCache.getTeamSchedule(),
                authManager.getCafeNo(),
                authManager.getUserId()
            )

            val sampleShift = schedule.currentShifts?.firstOrNull {
                it.cafeNumber != null && it.companyCode != null
            }
            val companyCode = sampleShift?.companyCode ?: "101"

            val enabledCafes = if (enabledCafesList.isEmpty()) {
                val sampleCafe = sampleShift?.cafeNumber
                if (sampleCafe != null) listOf(sampleCafe) else emptyList()
            } else {
                enabledCafesList
            }

            val range = com.anonymousassociate.betterpantry.utils.DateRangeUtils.getCoworkerQueryRange()
            val startStr = range.first
            val endStr = range.second

            val forceThisBatch = forceRefresh || scheduleCache.isTeamScheduleStale()
            val fetchedCafes = mutableSetOf<String>()
            coroutineScope {
                enabledCafes.map { cafeNo ->
                    fetchedCafes.add(cafeNo)
                    async {
                        try {
                            repository.getTeamMembers(
                                cafeNo, 
                                companyCode, 
                                startStr, 
                                endStr, 
                                forceRefresh = forceThisBatch
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    }
                }.awaitAll()
            }

            // Re-evaluate to check for newly discovered/enabled cafes from cache
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
                                    startStr,
                                    endStr,
                                    forceRefresh = forceThisBatch
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                    }.awaitAll()
                }
            }

            val finalTeamSchedule = scheduleCache.getTeamSchedule() ?: emptyList()
            updateTimestamp()
            startUpdateTimer()
            processTeamMembers(finalTeamSchedule, schedule)
        }
    }

    private fun processTeamMembers(teamMembers: List<TeamMember>, schedule: com.anonymousassociate.betterpantry.models.ScheduleData? = null) {
        val associatesFromTeam = teamMembers.mapNotNull { it.associate }
        
        val associatesFromInfo = schedule?.employeeInfo?.map { info ->
            Associate(
                employeeId = info.employeeId,
                firstName = info.firstName,
                lastName = info.lastName,
                preferredName = null 
            )
        } ?: emptyList()

        // Merge: prefer associatesFromTeam because they have preferredName
        val allMap = associatesFromInfo.associateBy { it.employeeId }.toMutableMap()
        associatesFromTeam.forEach { 
             if (it.employeeId != null) allMap[it.employeeId] = it 
        }

        val uniqueAssociates = allMap.values
            .sortedBy { associate ->
                if (!associate.preferredName.isNullOrEmpty()) {
                    associate.preferredName
                } else {
                    associate.firstName
                }
            }
            .filter { it.employeeId != "AVAILABLE_SHIFT" }

        // Build associateCafes map: employeeId -> Set of cafe numbers
        val associateCafes = mutableMapOf<String, Set<String>>()
        teamMembers.forEach { member ->
            val empId = member.associate?.employeeId
            if (empId != null) {
                val cafes = mutableSetOf<String>()
                
                // Add home cafe
                member.associate.cafeNumber?.let { cafes.add(it) }
                
                // Add loaned cafes
                member.associate.loanedCafeList?.forEach { cafes.add(it) }
                
                // Add shift cafes
                member.shifts?.mapNotNull { it.cafeNumber }?.forEach { cafes.add(it) }
                
                associateCafes[empId] = cafes
            }
        }

        val homeCafe = authManager.getCafeNo()
        val userId = authManager.getUserId()
        val userEnabledCafes = settingsPreferences.getEnabledCafeNumbers(schedule, teamMembers, homeCafe, userId).toSet()

        val uniqueAssociatesList = uniqueAssociates.toList()
        allAssociates = uniqueAssociatesList
        adapter.updateData(uniqueAssociatesList, associateCafes, schedule?.cafeList, userEnabledCafes)
        
        val currentQuery = searchBar.text.toString()
        if (currentQuery.isNotEmpty()) {
            adapter.filter(currentQuery)
        }
        
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            emptyStateText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun updateTimestamp() {
        updatedText.text = scheduleCache.getLastUpdateText()
    }

    private fun showNicknameDialog(associate: Associate) {
        val employeeId = associate.employeeId ?: return
        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_nickname_edit)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val firstInput = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nicknameFirstInput)
        val lastInput = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nicknameLastInput)
        val firstInputLayout = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.nicknameFirstInputLayout)
        val lastInputLayout = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.nicknameLastInputLayout)
        val hideLastNameSwitch = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.hideLastNameSwitch)
        val dialogTitle = dialog.findViewById<android.widget.TextView>(R.id.dialogTitle)
        val closeBtn = dialog.findViewById<android.view.View>(R.id.closeButton)

        // Set existing nickname or blank
        firstInput.setText(settingsPreferences.getCoworkerNicknameFirst(employeeId) ?: "")
        lastInput.setText(settingsPreferences.getCoworkerNicknameLast(employeeId) ?: "")
        hideLastNameSwitch.isChecked = settingsPreferences.getCoworkerHideLastName(employeeId)

        // Hints should be the actual names
        val defaultFirst = if (!associate.preferredName.isNullOrEmpty()) associate.preferredName else associate.firstName ?: ""
        val defaultLast = associate.lastName ?: ""
        firstInputLayout.hint = defaultFirst
        lastInputLayout.hint = defaultLast
        firstInput.hint = defaultFirst
        lastInput.hint = defaultLast

        // Dialog title
        dialogTitle.text = "$defaultFirst $defaultLast NICKNAME".uppercase(java.util.Locale.US)

        dialog.setOnDismissListener {
            val firstVal = firstInput.text?.toString()
            val lastVal = lastInput.text?.toString()
            settingsPreferences.setCoworkerNickname(employeeId, firstVal, lastVal)
            settingsPreferences.setCoworkerHideLastName(employeeId, hideLastNameSwitch.isChecked)
            com.anonymousassociate.betterpantry.widgets.WidgetUpdater.updateAllWidgets(requireContext())
            refreshDataFromCache()
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
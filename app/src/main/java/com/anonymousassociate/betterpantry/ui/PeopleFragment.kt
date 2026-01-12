package com.anonymousassociate.betterpantry.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import androidx.lifecycle.Lifecycle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PeopleFragment : Fragment() {

    private lateinit var authManager: AuthManager
    private lateinit var apiService: PantryApiService
    private lateinit var scheduleCache: ScheduleCache
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchBar: EditText
    private lateinit var emptyStateText: TextView
    private lateinit var updatedText: TextView
    private lateinit var adapter: PeopleAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

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
        apiService = PantryApiService(authManager)
        scheduleCache = ScheduleCache(requireContext())

        recyclerView = view.findViewById(R.id.peopleRecyclerView)
        searchBar = view.findViewById(R.id.searchBar)
        
        if (searchBar.text.isNullOrEmpty()) {
            searchBar.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_people, 0, 0, 0)
        }
        
        emptyStateText = view.findViewById(R.id.emptyStateText)
        updatedText = view.findViewById(R.id.updatedText)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        
        val settingsButton: android.widget.ImageButton = view.findViewById(R.id.settingsButton)
        settingsButton.setOnClickListener { showSettingsMenu(it) }

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
            }
        )
        recyclerView.adapter = adapter

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

    private fun startUpdateTimer() {
        stopUpdateTimer()
        updateTimeRunnable = object : Runnable {
            override fun run() {
                updateTimestamp()
                
                if (scheduleCache.isScheduleStale()) {
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
        handler.post(updateTimeRunnable!!)
    }

    private fun stopUpdateTimer() {
        updateTimeRunnable?.let {
            handler.removeCallbacks(it)
        }
        updateTimeRunnable = null
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

    private fun loadPeople(forceRefresh: Boolean = false) {
        // Always try to load from cache first to show something immediately
        val cachedTeam = scheduleCache.getTeamSchedule()
        if (cachedTeam != null) {
            processTeamMembers(cachedTeam)
        }

        if (!forceRefresh) {
            if (!scheduleCache.isScheduleStale() && cachedTeam != null && cachedTeam.isNotEmpty()) {
                // Cache is fresh enough, don't refresh from network
                swipeRefreshLayout.isRefreshing = false
                return
            }
        }

        // Use post to ensure the spinner actually shows up if the view is just being created
        swipeRefreshLayout.post {
            swipeRefreshLayout.isRefreshing = true
        }
        
        lifecycleScope.launch {
            try {
                fetchTeamMembers()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private suspend fun fetchTeamMembers() {
        var schedule = scheduleCache.getSchedule()
        if (schedule == null) {
            schedule = try { apiService.getSchedule(30) } catch (e: Exception) { null }
            if (schedule != null) {
                scheduleCache.saveSchedule(schedule)
            }
        }

        if (schedule != null) {
            val sampleShift = schedule.currentShifts?.firstOrNull {
                it.cafeNumber != null && it.companyCode != null
            }

            if (sampleShift != null) {
                val cafeNo = sampleShift.cafeNumber!!
                val companyCode = sampleShift.companyCode!!

                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                val startStr = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).format(formatter)
                val endStr = LocalDateTime.now().plusDays(30).withHour(23).withMinute(59).withSecond(59).format(formatter)

                val teamMembers = try {
                    apiService.getTeamMembers(cafeNo, companyCode, startStr, endStr)
                } catch (e: Exception) {
                    null
                }

                if (teamMembers != null) {
                    scheduleCache.saveTeamSchedule(teamMembers)
                    scheduleCache.saveSchedule(schedule) 
                    updateTimestamp()
                    startUpdateTimer()
                    processTeamMembers(teamMembers)
                }
            }
        }
    }

    private fun processTeamMembers(teamMembers: List<TeamMember>) {
        val uniqueAssociates = teamMembers.mapNotNull { it.associate }
            .distinctBy { it.employeeId }
            .sortedBy { it.firstName }
            .filter { it.employeeId != "AVAILABLE_SHIFT" }

        allAssociates = uniqueAssociates
        adapter.updateList(allAssociates)
        
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
}
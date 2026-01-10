package com.anonymousassociate.betterpantry.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.gson.Gson
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.anonymousassociate.betterpantry.AuthManager
import com.anonymousassociate.betterpantry.MainActivity
import com.anonymousassociate.betterpantry.NotificationWorker
import com.anonymousassociate.betterpantry.PantryApiService
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.models.NotificationData
import com.anonymousassociate.betterpantry.models.ScheduleData
import com.anonymousassociate.betterpantry.models.Shift
import com.anonymousassociate.betterpantry.ui.adapters.NotificationAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.anonymousassociate.betterpantry.ui.ChartRenderer
import com.anonymousassociate.betterpantry.ui.DaySchedule
import com.anonymousassociate.betterpantry.ui.EnrichedShift
import com.anonymousassociate.betterpantry.ui.ScheduleInteractionListener
import com.anonymousassociate.betterpantry.ui.ExpandedScheduleFragment
import android.widget.RelativeLayout
import android.widget.HorizontalScrollView
import com.anonymousassociate.betterpantry.models.TeamMember
import com.anonymousassociate.betterpantry.models.TeamShift
import com.anonymousassociate.betterpantry.models.Associate
import java.time.LocalDate

class NotificationsFragment : Fragment() {

    private lateinit var authManager: AuthManager
    private lateinit var apiService: PantryApiService
    private lateinit var scheduleCache: ScheduleCache
    private lateinit var adapter: NotificationAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var emptyStateText: android.widget.TextView
    private lateinit var permissionButton: ImageButton

    private var allNotifications: List<NotificationData> = emptyList()
    private var hasLoaded = false
    private var scheduleData: ScheduleData? = null
    
    // Track active dialog for updates
    private var activeShiftDialog: Dialog? = null
    private var currentShiftId: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        updatePermissionButtonVisibility()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notifications, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authManager = AuthManager(requireContext())
        apiService = PantryApiService(authManager)
        scheduleCache = ScheduleCache(requireContext())

        recyclerView = view.findViewById(R.id.notificationsRecyclerView)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        toggleGroup = view.findViewById(R.id.toggleGroup)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        permissionButton = view.findViewById(R.id.permissionButton)
        val settingsButton: android.widget.ImageButton = view.findViewById(R.id.settingsButton)
        val notificationSettingsButton: android.widget.ImageButton = view.findViewById(R.id.notificationSettingsButton)
        
        val rootContainer = view.findViewById<View>(R.id.rootContainer)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            
            // Adjust refresh indicator position
            val refreshStart = -(40 * resources.displayMetrics.density).toInt()
            val refreshTarget = (64 * resources.displayMetrics.density).toInt()
            swipeRefreshLayout.setProgressViewOffset(false, refreshStart, refreshTarget)
            
            insets
        }

        settingsButton.setOnClickListener { showSettingsMenu(it) }
        notificationSettingsButton.setOnClickListener { showNotificationSettings() }

        permissionButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    // User denied once but didn't check "Don't ask again"
                    // Or system allows re-prompting.
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // User likely denied permanently ("Don't ask again") or it's the first time (handled in MainActivity).
                    // Since this button is visible, they likely denied it.
                    // We should prompt them to go to settings.
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Enable Notifications")
                        .setMessage("Notifications are currently disabled. To receive alerts about shifts and updates, please enable notifications in the app settings.")
                        .setPositiveButton("Settings") { _, _ ->
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", requireContext().packageName, null)
                            }
                            startActivity(intent)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        setupRecyclerView()
        setupSwipeRefresh()
        setupToggleGroup()

        val cachedSchedule = scheduleCache.getSchedule()
        if (cachedSchedule != null) {
            scheduleData = cachedSchedule
            adapter.updateScheduleData(cachedSchedule)
        }

        loadNotifications()

        // Handle pending notification from push
        arguments?.getString("pending_notification_json")?.let { json ->
            try {
                val notification = Gson().fromJson(json, NotificationData::class.java)
                // Use a small delay to ensure the UI is ready before showing a dialog
                view.postDelayed({
                    onItemClick(notification)
                }, 100)
                // Clear the argument so it doesn't trigger again on configuration change
                arguments?.remove("pending_notification_json")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    override fun onResume() {
        super.onResume()
        updatePermissionButtonVisibility()
    }

    private fun updatePermissionButtonVisibility() {
        val notificationSettingsButton: ImageButton? = view?.findViewById(R.id.notificationSettingsButton)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            permissionButton.visibility = if (isGranted) View.GONE else View.VISIBLE
            notificationSettingsButton?.visibility = if (isGranted) View.VISIBLE else View.GONE
        } else {
            permissionButton.visibility = View.GONE
            notificationSettingsButton?.visibility = View.VISIBLE
        }
    }

    private fun showNotificationSettings() {
        NotificationSettingsDialog(requireContext()).show()
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

    private fun setupRecyclerView() {
        adapter = NotificationAdapter(
            notifications = emptyList(),
            onMarkAsReadClick = { notificationId -> onMarkAsRead(notificationId) },
            onDeleteClick = { notificationId -> onDelete(notificationId) },
            onUndeleteClick = { notificationId -> onUndelete(notificationId) },
            onItemClick = { notification -> onItemClick(notification) },
            onTestClick = { notification ->
                val appContext = requireContext().applicationContext
                lifecycleScope.launch(Dispatchers.IO) {
                    NotificationWorker.sendNotification(appContext, notification, apiService)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
    }

    private var lastClickTime: Long = 0
    private val CLICK_DEBOUNCE_DELAY = 500L

    private fun onItemClick(notification: NotificationData) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_DEBOUNCE_DELAY) return
        lastClickTime = now

        // Mark as read immediately when clicked
        if (notification.read == false && notification.notificationId != null) {
            // Optimistic update
            val index = allNotifications.indexOfFirst { it.notificationId == notification.notificationId }
            if (index != -1) {
                val updated = allNotifications[index].copy(read = true)
                allNotifications = allNotifications.toMutableList().apply { set(index, updated) }
                updateList()
            }
            
            lifecycleScope.launch {
                try {
                    apiService.markNotificationAsRead(notification.notificationId)
                    val count = allNotifications.count { it.read == false }
                    (requireActivity() as? MainActivity)?.updateNotificationBadge(count)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val subject = notification.subject
        if (subject?.contains("Manager call for help", ignoreCase = true) == true ||
            subject?.contains("Shift Available for Pickup", ignoreCase = true) == true) {
            
            val appData = notification.appData
            if (!appData.isNullOrEmpty()) {
                try {
                    var jsonStr = appData
                    if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                        jsonStr = jsonStr.substring(1, jsonStr.length - 1).replace("\\\"", "\"").replace("\\n", "")
                    }
                    
                    val json = JSONObject(jsonStr)
                    val initiatorShift = json.optJSONObject("initiatorShift")
                    val shiftId = initiatorShift?.optString("shiftId")
                    
                    if (shiftId != null) {
                        fetchAndShowShift(shiftId, notification)
                        return
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else if (subject?.contains("Pick Up Shift Request Approved", ignoreCase = true) == true) {
            val appData = notification.appData
            if (!appData.isNullOrEmpty()) {
                try {
                    var jsonStr = appData
                    if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                        jsonStr = jsonStr.substring(1, jsonStr.length - 1).replace("\\\"", "\"").replace("\\n", "")
                    }
                    
                    val json = JSONObject(jsonStr)
                    val initiatorShift = json.optJSONObject("initiatorShift")
                    val shiftId = initiatorShift?.optString("shiftId")
                    
                    if (shiftId != null) {
                        fetchAndShowMyShift(shiftId)
                        return
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // Fallback or other notification type
        showGenericNotificationDialog(notification)
    }

    private fun showGenericNotificationDialog(notification: NotificationData) {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_generic_notification)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val subjectText = dialog.findViewById<TextView>(R.id.notificationSubject)
        val dateText = dialog.findViewById<TextView>(R.id.notificationDate)
        val messageContainer = dialog.findViewById<LinearLayout>(R.id.messageContainer)
        val closeButton = dialog.findViewById<View>(R.id.closeButton)

        subjectText.text = notification.subject ?: "No Subject"

        // Format date
        notification.createDateTime?.let {
            try {
                val instant = Instant.parse(it)
                val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
                    .withZone(java.time.ZoneId.systemDefault())
                dateText.text = formatter.format(instant)
            } catch (e: Exception) {
                dateText.text = it
            }
        }

        // Render message content (HTML/Table)
        val message = notification.message ?: ""
        messageContainer.removeAllViews()
        
        if (message.contains("<table", ignoreCase = true)) {
            parseAndRenderHtml(message, messageContainer)
        } else {
            val textView = TextView(requireContext())
            var processedMessage = message
            processedMessage = processedMessage.replace(Regex("<p>\\s*&nbsp;\\s*</p>\\s*$"), "")
            processedMessage = processedMessage.replace(Regex("(&nbsp;?)+\\s*$"), "")
            processedMessage = processedMessage.replace("&nbsp;", "<br>").replace("&nbsp", "<br>")
            
            val imageGetter = Base64ImageGetter(textView)
            textView.text = trimTrailingWhitespace(android.text.Html.fromHtml(processedMessage, android.text.Html.FROM_HTML_MODE_COMPACT, imageGetter, null))
            textView.textSize = 16f
            textView.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
            LinkUtil.makeLinksClickable(textView)
            messageContainer.addView(textView)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // --- HTML Parsing Helpers (Copied from NotificationAdapter) ---
    private fun parseAndRenderHtml(html: String, container: LinearLayout) {
        val tableIndex = html.indexOf("<table", ignoreCase = true)
        if (tableIndex > 0) {
            var preText = html.substring(0, tableIndex)
            preText = preText.replace(Regex("<p>\\s*&nbsp;\\s*</p>\\s*$"), "")
            preText = preText.replace(Regex("(&nbsp;?)+\\s*$"), "")
            preText = preText.replace("&nbsp;", "<br>").replace("&nbsp", "<br>")
            
            preText = preText.replace(Regex("<head>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            preText = preText.replace(Regex("</?html.*?>", RegexOption.IGNORE_CASE), "")
            preText = preText.replace(Regex("</?body.*?>", RegexOption.IGNORE_CASE), "")
            
            val textView = TextView(requireContext())
            val imageGetter = Base64ImageGetter(textView)
            val spannedText = trimTrailingWhitespace(android.text.Html.fromHtml(preText, android.text.Html.FROM_HTML_MODE_COMPACT, imageGetter, null))
            if (spannedText.isNotEmpty()) {
                textView.text = spannedText
                textView.textSize = 16f
                textView.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                textView.setPadding(0, 0, 0, 16)
                LinkUtil.makeLinksClickable(textView)
                container.addView(textView)
            }
        }

        val tablePattern = java.util.regex.Pattern.compile("<table.*?>(.*?)</table>", java.util.regex.Pattern.DOTALL or java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcher = tablePattern.matcher(html)
        
        if (matcher.find()) {
            val tableContent = matcher.group(1) ?: ""
            renderTable(tableContent, container)
        }
    }

    private fun renderTable(tableHtml: String, container: LinearLayout) {
        val context = requireContext()
        
        val scrollView = android.widget.HorizontalScrollView(context)
        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        scrollView.isFillViewport = true

        val tableLayout = android.widget.TableLayout(context)
        tableLayout.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tableLayout.isStretchAllColumns = true
        
        tableLayout.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.table_border_color))
        tableLayout.setPadding(1, 1, 1, 1)

        val rowPattern = java.util.regex.Pattern.compile("<tr.*?>(.*?)</tr>", java.util.regex.Pattern.DOTALL or java.util.regex.Pattern.CASE_INSENSITIVE)
        val rowMatcher = rowPattern.matcher(tableHtml)

        while (rowMatcher.find()) {
            val rowContent = rowMatcher.group(1) ?: ""
            val tableRow = android.widget.TableRow(context)
            // Use border color for row background to show through cell margins
            tableRow.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.table_border_color))
            
            tableRow.layoutParams = android.widget.TableLayout.LayoutParams(
                android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                android.widget.TableLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 0)
            }
            
            val cellPattern = java.util.regex.Pattern.compile("<(th|td).*?>(.*?)</\\1>", java.util.regex.Pattern.DOTALL or java.util.regex.Pattern.CASE_INSENSITIVE)
            val cellMatcher = cellPattern.matcher(rowContent)
            
            while (cellMatcher.find()) {
                val tag = cellMatcher.group(1)
                var cellRaw = cellMatcher.group(2) ?: ""
                cellRaw = cellRaw.replace(Regex("<p>\\s*&nbsp;\\s*</p>\\s*$"), "")
                cellRaw = cellRaw.replace(Regex("(&nbsp;?)+\\s*$"), "")
                cellRaw = cellRaw.replace("&nbsp;", "<br>").replace("&nbsp", "<br>")
                
                val spannedText = trimTrailingWhitespace(android.text.Html.fromHtml(cellRaw, android.text.Html.FROM_HTML_MODE_COMPACT))
                var cellString = spannedText.toString().trim()
                var isDate = false
                
                if (cellString.contains("T") && cellString.contains("-")) {
                    try {
                        val instant = try {
                            Instant.parse(cellString + "Z")
                        } catch (e: Exception) {
                            try {
                                val ldt = java.time.LocalDateTime.parse(cellString)
                                ldt.atZone(java.time.ZoneId.systemDefault()).toInstant()
                            } catch (e2: Exception) {
                                null
                            }
                        }
                        if (instant != null) {
                            val formatter = if (cellString.contains("00:00:00")) {
                                DateTimeFormatter.ofPattern("M/d/yy")
                            } else {
                                DateTimeFormatter.ofPattern("M/d h:mma")
                            }
                            cellString = formatter.withZone(java.time.ZoneId.systemDefault()).format(instant)
                            isDate = true
                        }
                    } catch (e: Exception) {}
                }

                val textView = TextView(context)
                textView.text = if (isDate) cellString else spannedText
                textView.setPadding(8, 16, 8, 16)
                textView.textSize = 14f
                textView.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                
                // Set cell background (Row or Header color)
                if (tag.equals("th", ignoreCase = true)) {
                    textView.setTypeface(null, android.graphics.Typeface.BOLD)
                    textView.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.table_header_background))
                } else {
                    textView.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.table_row_background))
                }
                
                // Add margins to create grid lines (border color shows through)
                val params = android.widget.TableRow.LayoutParams(
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                    android.widget.TableRow.LayoutParams.MATCH_PARENT // Fill height for equal styling
                )
                params.setMargins(1, 1, 1, 1)
                textView.layoutParams = params
                
                LinkUtil.makeLinksClickable(textView)
                
                tableRow.addView(textView)
            }
            tableLayout.addView(tableRow)
        }
        
        scrollView.addView(tableLayout)
        container.addView(scrollView)
    }

    private fun fetchAndShowShift(shiftId: String, notification: NotificationData) {
        lifecycleScope.launch {
            // 1. Try Cache First
            val cachedSchedule = scheduleCache.getSchedule()
            var showedCache = false
            var cachedTrackItem: com.anonymousassociate.betterpantry.models.TrackItem? = null

            if (cachedSchedule != null) {
                val trackItem = cachedSchedule.track?.find { 
                    it.primaryShiftRequest?.shift?.shiftId == shiftId
                }
                val shift = trackItem?.primaryShiftRequest?.shift
                
                if (shift != null) {
                    cachedTrackItem = trackItem
                    scheduleData = cachedSchedule
                    showShiftDetailDialog(emptyList(), listOf(shift), showActions = true, notification = notification)
                    showedCache = true
                }
            }

            // 2. Fetch from Network
            try {
                val schedule = apiService.getSchedule(30)
                if (schedule != null) {
                    scheduleData = schedule
                    val trackItem = schedule.track?.find { 
                        it.primaryShiftRequest?.shift?.shiftId == shiftId
                    }
                    
                    val shift = trackItem?.primaryShiftRequest?.shift
                    
                    if (shift != null) {
                        // Only show/update if we didn't show cache OR if the dialog is still open AND showing the same shift
                        if (!showedCache || (activeShiftDialog != null && activeShiftDialog!!.isShowing && currentShiftId == shiftId)) {
                            showShiftDetailDialog(emptyList(), listOf(shift), showActions = true, notification = notification)
                        }
                    } else {
                        // Shift might be gone/taken
                        // Optional: show toast or close cached dialog
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchAndShowMyShift(shiftId: String) {
        lifecycleScope.launch {
            // 1. Try Cache First
            val cachedSchedule = scheduleCache.getSchedule()
            var showedCache = false
            if (cachedSchedule != null) {
                val myShift = cachedSchedule.currentShifts?.find { it.shiftId == shiftId }
                if (myShift != null) {
                    scheduleData = cachedSchedule
                    showShiftDetailDialog(listOf(myShift), emptyList(), showActions = false)
                    showedCache = true
                }
            }

            // 2. Fetch from Network
            try {
                val schedule = apiService.getSchedule(30)
                if (schedule != null) {
                    scheduleData = schedule
                    val myShift = schedule.currentShifts?.find { it.shiftId == shiftId }
                    
                    if (myShift != null) {
                        // Only show/update if we didn't show cache OR if the dialog is still open AND showing the same shift
                        if (!showedCache || (activeShiftDialog != null && activeShiftDialog!!.isShowing && currentShiftId == shiftId)) {
                            showShiftDetailDialog(listOf(myShift), emptyList(), showActions = false)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Copied Helper Methods for Shift Dialog ---

    private fun showShiftDetailDialog(myShifts: List<Shift>, availableShifts: List<Shift>, showActions: Boolean = false, notification: NotificationData? = null, isNested: Boolean = false, customTitle: String? = null) {
        // If this is a nested call, we still use the main dialog logic but it will be updated
        // The key is that the CALLER determines the content.
        
        // Set current shift ID for the main dialog
        val shift = availableShifts.firstOrNull() ?: myShifts.firstOrNull()
        currentShiftId = shift?.shiftId

        if (!isNested) {
            if (activeShiftDialog != null) {
                if (activeShiftDialog!!.isShowing) {
                    updateShiftDialogContent(activeShiftDialog!!, myShifts, availableShifts, showActions, notification, isNested, customTitle)
                    return
                } else {
                    activeShiftDialog = null
                }
            }

            val dialog = Dialog(requireContext())
            activeShiftDialog = dialog
            dialog.setContentView(R.layout.dialog_shift_detail)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialog.setOnDismissListener {
                if (activeShiftDialog == dialog) {
                    activeShiftDialog = null
                    currentShiftId = null
                }
            }

            updateShiftDialogContent(dialog, myShifts, availableShifts, showActions, notification, isNested, customTitle)
            dialog.show()
        } else {
            // For nested dialogs, create a new one every time.
            val dialog = Dialog(requireContext())
            dialog.setContentView(R.layout.dialog_shift_detail)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            updateShiftDialogContent(dialog, myShifts, availableShifts, false, null, true, customTitle)
            dialog.show()
        }
    }

    private fun updateShiftDialogContent(dialog: Dialog, myShifts: List<Shift>, availableShifts: List<Shift>, showActions: Boolean, notification: NotificationData?, isNested: Boolean = false, customTitle: String? = null) {
        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val shiftsContainer = dialog.findViewById<LinearLayout>(R.id.shiftsContainer)
        val closeButton = dialog.findViewById<View>(R.id.closeButton)
        val declineButton = dialog.findViewById<MaterialButton>(R.id.declineButton)
        val acceptButton = dialog.findViewById<MaterialButton>(R.id.acceptButton)

        if (customTitle != null) {
            dialogTitle.text = customTitle
        } else if (myShifts.isEmpty() && availableShifts.isNotEmpty()) {
            dialogTitle.text = "Available Shift"
        } else {
            dialogTitle.text = "Shift Details"
        }
        
        val sortedMyShifts = myShifts.sortedBy { it.startDateTime }
        val sortedAvailableShifts = availableShifts.sortedBy { it.startDateTime }

        val allShifts = sortedMyShifts + sortedAvailableShifts
        val availableShiftsSet = sortedAvailableShifts.toSet()

        // Reuse existing views to prevent layout jump
        allShifts.forEachIndexed { index, shift ->
            val existingView = if (index < shiftsContainer.childCount) shiftsContainer.getChildAt(index) else null
            val isAvailable = availableShiftsSet.contains(shift)
            bindShiftCard(shiftsContainer, existingView, shift, isAvailable = isAvailable, isNested)
        }
        
        // Remove extra views if list shrank
        if (shiftsContainer.childCount > allShifts.size) {
            shiftsContainer.removeViews(allShifts.size, shiftsContainer.childCount - allShifts.size)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        if (showActions && notification != null) {
            declineButton.visibility = View.VISIBLE
            acceptButton.visibility = View.VISIBLE
            
            declineButton.setOnClickListener {
                dialog.dismiss()
                performShiftAction(notification, "Associate Declined")
            }
            
            acceptButton.setOnClickListener {
                val confirmDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Accept Shift")
                    .setMessage("Are you sure you want to accept this shift?")
                    .setPositiveButton("Yes") { _, _ ->
                        dialog.dismiss()
                        // Work in progress
                        performShiftAction(notification, "Accepted")
                    }
                    .setNegativeButton("No") { _, _ ->
                        dialog.dismiss()
                        loadNotifications()
                    }
                    .create()

                confirmDialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                confirmDialog.show()

                confirmDialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                )
                confirmDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.work_day_green)
                )
            }
        } else {
            declineButton.visibility = View.GONE
            acceptButton.visibility = View.GONE
        }
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
                
                val result = ChartRenderer.drawChart(
                    requireContext(),
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
                            showShiftDetailDialog(
                                if (clickedShift.isAvailable) emptyList() else listOf(newShift),
                                if (clickedShift.isAvailable) listOf(newShift) else emptyList(),
                                customTitle = title, isNested = true
                            )
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

    private fun performShiftAction(notification: NotificationData, responseType: String) {
        lifecycleScope.launch {
            try {
                val appData = notification.appData
                if (appData.isNullOrEmpty()) return@launch
                
                var jsonStr = appData
                if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                    jsonStr = jsonStr.substring(1, jsonStr.length - 1).replace("\\\"", "\"").replace("\\n", "")
                }
                
                val appDataJson = JSONObject(jsonStr)
                val eventId = appDataJson.optLong("eventId", -1)
                val initiatorShift = appDataJson.optJSONObject("initiatorShift")
                val shiftId = initiatorShift?.optLong("shiftId", -1) ?: -1
                val callForHelp = notification.subject?.contains("Manager call for help", ignoreCase = true) == true
                
                val payload = JSONObject().apply {
                    put("associateResponse", responseType)
                    put("id", eventId)
                    put("requestId", eventId)
                    put("notificationId", notification.notificationId)
                    put("workFlowId", notification.workFlowId)
                    put("shiftId", shiftId)
                    put("callForHelp", callForHelp)
                    
                    val receiveAssociate = JSONObject().apply {
                        put("employeeId", authManager.getUserId())
                        put("firstName", authManager.getFirstName())
                        put("lastName", authManager.getLastName())
                        put("preferredName", authManager.getPreferredName())
                    }
                    put("receiveAssociate", receiveAssociate)
                }
                
                if (responseType == "Associate Declined") {
                    apiService.declineShiftPickup(payload.toString())
                } else {
                    val success = apiService.acceptShiftPickup(payload.toString())
                                    if (success) {
                                        (activity as? MainActivity)?.checkHomeFragmentNotifications()
                                        scheduleCache.clear()
                                        val newSchedule = apiService.getSchedule(30)
                                        if (newSchedule != null) {
                                            scheduleData = newSchedule
                                            scheduleCache.saveSchedule(newSchedule)
                                        }
                                    }                }
                
                loadNotifications()
            } catch (e: Exception) {
                e.printStackTrace()
                loadNotifications()
            }
        }
    }

    private fun bindShiftCard(container: LinearLayout, existingView: View?, shift: Shift, isAvailable: Boolean, isNested: Boolean = false): View {
        val cardView = existingView ?: LayoutInflater.from(requireContext())
            .inflate(R.layout.item_shift_detail_card, container, false)

        val shiftDateTime = cardView.findViewById<TextView>(R.id.shiftDateTime)
        val shiftPosition = cardView.findViewById<TextView>(R.id.shiftPosition)
        val postedByText = cardView.findViewById<TextView>(R.id.postedByText)
        val coworkersHeaderWrapper = cardView.findViewById<View>(R.id.coworkersHeaderWrapper)
        val expandCoworkersButton = cardView.findViewById<View>(R.id.expandCoworkersButton)
        val shareCoworkersButton = cardView.findViewById<View>(R.id.shareCoworkersButton)
        val coworkersContainer = cardView.findViewById<LinearLayout>(R.id.coworkersContainer)
        val chartScrollView = cardView.findViewById<HorizontalScrollView>(R.id.coworkersChartScrollView)
        val chartContainer = cardView.findViewById<RelativeLayout>(R.id.coworkersChartContainer)
        val pickupAttemptsText = cardView.findViewById<TextView>(R.id.pickupAttemptsText)
        val pickupRequestsContainer = cardView.findViewById<LinearLayout>(R.id.pickupRequestsContainer)
        val shiftLocation = cardView.findViewById<TextView>(R.id.shiftLocation)

        try {
            val startDateTime = LocalDateTime.parse(shift.startDateTime)
            val endDateTime = LocalDateTime.parse(shift.endDateTime)
            val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
            val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
            val dayText = startDateTime.format(dayFormatter)
            val startTime = startDateTime.format(timeFormatter)
            val endTime = endDateTime.format(timeFormatter)
            shiftDateTime.text = "$dayText $startTime - $endTime"
        } catch (e: Exception) {
            shiftDateTime.text = "Unknown date"
        }

        val workstationId = shift.workstationId ?: shift.workstationCode ?: ""
        val workstationName = getWorkstationDisplayName(workstationId, shift.workstationName)
        shiftPosition.text = workstationName

        val trackItem = if (isAvailable) {
            scheduleData?.track?.find { trackItem ->
                trackItem.type == "AVAILABLE" && trackItem.primaryShiftRequest?.shift == shift
            }
        } else null

        if (isAvailable && trackItem != null) {
            val requester = trackItem.primaryShiftRequest
            if (requester != null) {
                val requesterName = getEmployeeName(requester.requesterId)
                val timeAgo = getTimeAgo(requester.requestedAt)
                postedByText.text = "Posted by $requesterName $timeAgo"
                postedByText.visibility = View.VISIBLE
            }
        }

        fun updateChart(teamMembers: List<TeamMember>) {
            val myId = authManager.getUserId()
            
            // Merge all shifts
            val myShifts = scheduleData?.currentShifts ?: emptyList()
            val availableTracks = scheduleData?.track ?: emptyList()
            val employeeInfo = scheduleData?.employeeInfo ?: emptyList()
            
            val mergedMembers = mergeData(teamMembers, myShifts, availableTracks, employeeInfo)
            
            val coworkerShifts = findCoworkerShifts(shift, mergedMembers, myId)
            
            if (coworkerShifts.isNotEmpty()) {
                coworkersHeaderWrapper.visibility = View.VISIBLE
                coworkersContainer.visibility = View.GONE
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
                    val safeWidth = if (width > 0) width else resources.displayMetrics.widthPixels - 110.dpToPx()

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
                                if (clickedShift.shift.shiftId != shift.shiftId?.toLongOrNull()) {
                                    val newShift = clickedShift.shift.toShift()
                                    val title = if (clickedShift.isAvailable) {
                                        "Available Shift"
                                    } else {
                                        "${clickedShift.firstName} ${clickedShift.lastName ?: ""}".trim()
                                    }
                                    // Recursive call - isNested=true to prevent infinite charts
                                    showShiftDetailDialog(
                                        if (clickedShift.isAvailable) emptyList() else listOf(newShift),
                                        if (clickedShift.isAvailable) listOf(newShift) else emptyList(),
                                        showActions = true,
                                        notification = null,
                                        isNested = true,
                                        customTitle = title
                                    )
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
                                    val cafeInfo = scheduleData?.cafeList?.firstOrNull { info ->
                                        // Attempt to match by cafe number if available
                                        val shiftCafeNum = s.cafeNumber
                                        val infoCafeNum = info.departmentName?.split(" ")?.lastOrNull()
                                        shiftCafeNum != null && infoCafeNum != null && shiftCafeNum == infoCafeNum
                                    } ?: scheduleData?.cafeList?.firstOrNull()

                                    val location = cafeInfo?.let { cafe ->
                                        val address = cafe.address
                                        if (address != null) {
                                            "#${s.cafeNumber ?: ""} - ${address.addressLine}, ${address.city}, ${address.state}"
                                        } else {
                                            "#${s.cafeNumber ?: ""}"
                                        }
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
            } else {
                coworkersHeaderWrapper.visibility = View.GONE
                chartScrollView.visibility = View.GONE
            }
        }

        if (!isNested && shift.cafeNumber != null && shift.companyCode != null &&
            shift.startDateTime != null && shift.endDateTime != null) {

            val shiftId = shift.shiftId ?: "${shift.startDateTime}-${shift.workstationId ?: shift.workstationCode}"
            val cachedMembers = scheduleCache.getTeamMembers(shiftId)
            if (cachedMembers != null) {
                updateChart(cachedMembers)
            }

            lifecycleScope.launch {
                try {
                    val startOfDay = LocalDateTime.parse(shift.startDateTime)
                        .with(java.time.LocalTime.MIN)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                    val teamMembers = apiService.getTeamMembers(
                        shift.cafeNumber,
                        shift.companyCode,
                        startOfDay,
                        shift.endDateTime
                    )
                    if (teamMembers != null) {
                        scheduleCache.saveTeamMembers(shiftId, teamMembers)
                        updateChart(teamMembers)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (isAvailable && trackItem != null) {
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

        val cafeInfo = scheduleData?.cafeList?.firstOrNull()
        val location = cafeInfo?.let { cafe ->
            val address = cafe.address
            "#${shift.cafeNumber ?: ""} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
        } ?: ""
        shiftLocation.text = location

        if (existingView == null) {
            container.addView(cardView)
        }
        
        return cardView
    }

    private fun getEmployeeName(employeeId: String?): String {
        if (employeeId == null) return "Unknown"
        
        // 1. Try scheduleData info
        val employee = scheduleData?.employeeInfo?.find { it.employeeId == employeeId }
        if (employee != null) {
            return "${employee.firstName ?: ""} ${employee.lastName ?: ""}".trim().ifEmpty { "Unknown" }
        }
        
        // 2. Try Team Cache
        val teamMembers = scheduleCache.getTeamSchedule()
        val associate = teamMembers?.find { it.associate?.employeeId == employeeId }?.associate
        if (associate != null) {
            return "${associate.firstName ?: ""} ${associate.lastName ?: ""}".trim().ifEmpty { "Unknown" }
        }
        
        return "Unknown"
    }

    private fun getEmployeeName(employeeId: String?, associates: List<Associate>?): String {
        if (employeeId == null) return "Unknown"
        val employee = associates?.find { it.employeeId == employeeId }
        return if (employee != null) {
            "${employee.firstName ?: ""} ${employee.lastName ?: ""}".trim().ifEmpty { "Unknown" }
        } else {
            getEmployeeName(employeeId) // Fallback to global list
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

    private fun onMarkAsRead(notificationId: String) {
        // Optimistic update
        val index = allNotifications.indexOfFirst { it.notificationId == notificationId }
        if (index != -1) {
            val updated = allNotifications[index].copy(read = true)
            allNotifications = allNotifications.toMutableList().apply { set(index, updated) }
            updateList()
        }

        lifecycleScope.launch {
            try {
                apiService.markNotificationAsRead(notificationId)
                val count = allNotifications.count { it.read == false }
                (requireActivity() as? MainActivity)?.updateNotificationBadge(count)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun onDelete(notificationId: String) {
        lifecycleScope.launch {
            try {
                // Check if notification is unread
                val notification = allNotifications.find { it.notificationId == notificationId }
                if (notification?.read == false) {
                    // Mark as read first
                    apiService.markNotificationAsRead(notificationId)
                }
                
                // Then delete
                val success = apiService.deleteNotification(notificationId)
                if (success) {
                    loadNotifications()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun onUndelete(notificationId: String) {
        lifecycleScope.launch {
            try {
                val success = apiService.undeleteNotification(notificationId)
                if (success) {
                    loadNotifications()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupSwipeRefresh() {
        // Set colors for the refresh indicator
        val greenColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.work_day_green)
        val backgroundColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.card_background_color)
        
        swipeRefreshLayout.setColorSchemeColors(greenColor)
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(backgroundColor)

        swipeRefreshLayout.setOnRefreshListener {
            loadNotifications()
        }
    }

    private fun setupToggleGroup() {
        toggleGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                updateList(scrollToTop = true)
            }
        }
    }

            private fun loadNotifications() {
                swipeRefreshLayout.post {
                    swipeRefreshLayout.isRefreshing = true
                }

                // Trigger the global check for new notifications which will send a push
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        NotificationWorker.checkAndSendNewNotifications(requireContext())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Load cache first
                val cached = scheduleCache.getCachedNotifications()
                if (cached != null && cached.isNotEmpty()) {
                    allNotifications = cached
                    hasLoaded = true
                    updateList()
                }

                lifecycleScope.launch {
                    try {
                        val response = apiService.getNotifications(size = 100)
                        val fetched = response?.content ?: emptyList()
                        allNotifications = fetched.sortedByDescending { it.createDateTime }
                        scheduleCache.saveNotifications(allNotifications)
                        hasLoaded = true
                        updateList()

                        val count = allNotifications.count { it.read == false }
                        (requireActivity() as? MainActivity)?.updateNotificationBadge(count)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        swipeRefreshLayout.isRefreshing = false
                    }
                }
            }

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
                    requesterName = getEmployeeName(req?.requesterId),
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
                            // Location logic if needed, typically redundant for mini chart context but good for data
                            val location = "#${s.cafeNumber ?: ""}"
                            
                            coworkerShifts.add(
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

    private fun updateList(scrollToTop: Boolean = false) {
        val showDeleted = toggleGroup.checkedButtonId == R.id.btnDeleted
        
        val filteredList = if (showDeleted) {
            allNotifications.filter { it.deleted == true }
        } else {
            allNotifications.filter { it.deleted != true } // Show read and unread
        }
        
        val sortedList = filteredList.sortedByDescending { it.createDateTime }
        adapter.updateNotifications(sortedList)
        
        if (scrollToTop) {
            recyclerView.scrollToPosition(0)
        }
        
        // Show/hide empty state
        if (sortedList.isEmpty() && hasLoaded) {
            emptyStateText.visibility = View.VISIBLE
        } else {
            emptyStateText.visibility = View.GONE
        }
    }

    private fun trimTrailingWhitespace(source: CharSequence): CharSequence {
        var i = source.length
        while (i > 0 && Character.isWhitespace(source[i - 1])) {
            i--
        }
        return source.subSequence(0, i)
    }

    inner class Base64ImageGetter(private val textView: TextView) : android.text.Html.ImageGetter {
        override fun getDrawable(source: String): Drawable? {
            if (source.startsWith("data:image/")) {
                try {
                    val base64Source = source.substringAfter(",")
                    val decodedString = Base64.decode(base64Source, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    val drawable = BitmapDrawable(textView.resources, bitmap)
                    
                    val displayMetrics = textView.resources.displayMetrics
                    // Calculate max width for Dialog
                    // Hierarchy padding: 16 (Frame) + 20 (Linear) + 4 (Frame) + 16 (Linear) = 56dp per side. Total 112dp.
                    // Use 120dp for safety.
                    val viewWidth = textView.width
                    val maxWidth = if (viewWidth > 0) {
                        viewWidth - (textView.paddingStart + textView.paddingEnd)
                    } else {
                        displayMetrics.widthPixels - (120 * displayMetrics.density).toInt()
                    }
                    
                    var width = drawable.intrinsicWidth
                    var height = drawable.intrinsicHeight
                    
                    val ratio = maxWidth.toFloat() / width
                    val newHeight = (height * ratio).toInt()
                    
                    drawable.setBounds(0, 0, maxWidth, newHeight)
                    return drawable
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return null
        }
    }
}

package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.anonymousassociate.betterpantry.AuthManager
import com.anonymousassociate.betterpantry.PantryApiService
import com.anonymousassociate.betterpantry.R
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ExpandedScheduleFragment : DialogFragment() {

    private lateinit var authManager: AuthManager
    private lateinit var apiService: PantryApiService

    companion object {
        var tempDaySchedule: DaySchedule? = null
        var tempFocusTime: LocalDateTime? = null
        var tempFocusShiftId: String? = null

        fun newInstance(day: DaySchedule, focusTime: LocalDateTime? = null, focusShiftId: String? = null): ExpandedScheduleFragment {
            tempDaySchedule = day
            tempFocusTime = focusTime
            tempFocusShiftId = focusShiftId
            return ExpandedScheduleFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use a dialog theme that is not fullscreen
        setStyle(DialogFragment.STYLE_NORMAL, R.style.FullScreenDialog)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_expanded_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authManager = AuthManager(requireContext())
        apiService = PantryApiService(authManager)

        val day = tempDaySchedule ?: return

        val header = view.findViewById<TextView>(R.id.expandedDateHeader)
        val closeBtn = view.findViewById<ImageButton>(R.id.closeExpandedButton)
        val container = view.findViewById<RelativeLayout>(R.id.expandedChartContainer)

        header.text = day.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        closeBtn.setOnClickListener {
            if (showsDialog) {
                dismiss()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        container.post {
            val width = container.width
            if (width > 0) {
                // Pass a listener to handle clicks
                val result = ChartRenderer.drawChart(
                    requireContext(),
                    container,
                    day,
                    isExpanded = true,
                    containerWidth = width,
                    focusTime = tempFocusTime,
                    focusShiftId = tempFocusShiftId,
                    listener = object : ScheduleInteractionListener {
                        override fun onExpandClick(day: DaySchedule) {
                            // Already expanded
                        }

                        override fun onShiftClick(shift: EnrichedShift) {
                            showShiftDetailsDialog(shift, isNested = true)
                        }
                    }
                )

                // Scroll vertically to focus shift
                val focusY = result.third
                if (focusY != null) {
                    val verticalScroll = view.findViewById<android.widget.ScrollView>(R.id.expandedVerticalScrollView)
                    verticalScroll?.post {
                        val screenHeight = verticalScroll.height
                        // Center vertically: target Y - half screen
                        val targetY = (focusY - screenHeight / 2).coerceAtLeast(0)
                        verticalScroll.smoothScrollTo(0, targetY)
                    }
                }
            }
        }
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
        val coworkersHeader = cardView.findViewById<TextView>(R.id.coworkersHeader)
        val coworkersContainer = cardView.findViewById<LinearLayout>(R.id.coworkersContainer)
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

        // Location from EnrichedShift
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

        // Coworkers
        if (!isNested && !enrichedShift.coworkers.isNullOrEmpty()) {
            coworkersHeader.visibility = View.VISIBLE
            coworkersContainer.removeAllViews()
            enrichedShift.coworkers.forEach { cw ->
                val tv = TextView(requireContext()).apply {
                    text = cw
                    textSize = 13f
                    setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                coworkersContainer.addView(tv)
            }
        } else {
            coworkersHeader.visibility = View.GONE
            coworkersContainer.removeAllViews()
        }

        // Pickup Requests
        if (enrichedShift.isAvailable) {
            val requests = enrichedShift.pickupRequests ?: emptyList()
            pickupAttemptsText.text = "Pickup Requests (${requests.size})"
            pickupAttemptsText.visibility = View.VISIBLE

            if (requests.isNotEmpty()) {
                pickupRequestsContainer.visibility = View.VISIBLE
                pickupRequestsContainer.removeAllViews()
                requests.forEach { req ->
                    val tv = TextView(requireContext()).apply {
                        text = "• $req"
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
                    parentFragmentManager.popBackStack() // Go back to refresh
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
                    parentFragmentManager.popBackStack() // Go back to refresh
                } else {
                     android.widget.Toast.makeText(requireContext(), "Failed to cancel (Code: $responseCode)", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
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

    override fun onDestroyView() {
        super.onDestroyView()
    }
}

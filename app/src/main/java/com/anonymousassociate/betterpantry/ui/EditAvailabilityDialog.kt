package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.anonymousassociate.betterpantry.MainActivity
import com.anonymousassociate.betterpantry.R
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class EditAvailabilityDialog : DialogFragment() {

    private lateinit var dailyMaxDropdown: AutoCompleteTextView
    private lateinit var weeklyMaxDropdown: AutoCompleteTextView
    private lateinit var daysContainer: LinearLayout
    private lateinit var submitButton: Button
    private lateinit var cancelButton: Button

    private val repository by lazy { (requireActivity() as MainActivity).repository }
    private val authManager by lazy { (requireActivity() as MainActivity).authManager }

    private val dayNames = listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")
    private val dayViews = mutableMapOf<String, DayViewState>()

    private val timeOptions = generateTimeOptions()
    private val startTimeOptions = listOf("All Day", "Not Available") + timeOptions

    data class DayViewState(
        val dayKey: String,
        val view: View,
        val startTimeDropdown: AutoCompleteTextView,
        val endTimeDropdown: AutoCompleteTextView,
        val endTimeLayout: TextInputLayout
    ) {
        var isAllDay: Boolean = false
        var isNotAvailable: Boolean = false
        var startTime: LocalTime? = null
        var endTime: LocalTime? = null
    }

    private var originalMaxHoursJson: String = ""
    private var originalAvailabilityJson: String = ""

    private fun generateTimeOptions(): List<String> {
        val options = mutableListOf<String>()
        var time = LocalTime.MIDNIGHT
        do {
            options.add(time.format(DateTimeFormatter.ofPattern("hh:mm a")))
            time = time.plusMinutes(15)
        } while (time != LocalTime.MIDNIGHT)
        return options
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_edit_availability, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        submitButton = view.findViewById(R.id.submitButton)
        cancelButton = view.findViewById(R.id.cancelButton)
        dailyMaxDropdown = view.findViewById(R.id.dailyMaxDropdown)
        weeklyMaxDropdown = view.findViewById(R.id.weeklyMaxDropdown)
        daysContainer = view.findViewById(R.id.daysContainer)

        setupDropdowns()
        setupDaysUI()
        loadCurrentData()

        submitButton.setOnClickListener {
            saveAvailability()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun setupDropdowns() {
        val dailyOptions = (1..8).map { it.toString() }
        val weeklyOptions = (1..50).map { it.toString() }

        val dailyAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, dailyOptions)
        dailyMaxDropdown.setAdapter(dailyAdapter)

        val weeklyAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, weeklyOptions)
        weeklyMaxDropdown.setAdapter(weeklyAdapter)
    }

    private fun setupDaysUI() {
        val inflater = LayoutInflater.from(requireContext())
        for (dayName in dayNames) {
            val dayView = inflater.inflate(R.layout.item_edit_day, daysContainer, false)
            val dayNameText = dayView.findViewById<TextView>(R.id.dayNameText)
            val startTimeDropdown = dayView.findViewById<AutoCompleteTextView>(R.id.startTimeDropdown)
            val endTimeDropdown = dayView.findViewById<AutoCompleteTextView>(R.id.endTimeDropdown)
            val endTimeLayout = dayView.findViewById<TextInputLayout>(R.id.endTimeLayout)

            dayNameText.text = dayName.lowercase().replaceFirstChar { it.uppercase() }

            val startAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, startTimeOptions)
            startTimeDropdown.setAdapter(startAdapter)

            val state = DayViewState(dayName, dayView, startTimeDropdown, endTimeDropdown, endTimeLayout)
            dayViews[dayName] = state

            startTimeDropdown.setOnItemClickListener { _, _, position, _ ->
                val selected = startTimeOptions[position]
                when (selected) {
                    "All Day" -> {
                        state.isAllDay = true
                        state.isNotAvailable = false
                        state.startTime = null
                        state.endTime = null
                        endTimeLayout.visibility = View.GONE
                    }
                    "Not Available" -> {
                        state.isAllDay = false
                        state.isNotAvailable = true
                        state.startTime = null
                        state.endTime = null
                        endTimeLayout.visibility = View.GONE
                    }
                    else -> {
                        state.isAllDay = false
                        state.isNotAvailable = false
                        state.startTime = LocalTime.parse(selected, DateTimeFormatter.ofPattern("hh:mm a"))
                        endTimeLayout.visibility = View.VISIBLE
                        updateEndTimeOptions(state)
                    }
                }
            }

            endTimeDropdown.setOnItemClickListener { _, _, position, _ ->
                val adapter = endTimeDropdown.adapter as ArrayAdapter<String>
                val selected = adapter.getItem(position)
                if (selected != null) {
                    state.endTime = LocalTime.parse(selected, DateTimeFormatter.ofPattern("hh:mm a"))
                }
            }

            daysContainer.addView(dayView)
        }
    }

    private fun updateEndTimeOptions(state: DayViewState) {
        val start = state.startTime ?: return
        val validEndTimes = timeOptions.filter { timeStr ->
            val t = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("hh:mm a"))
            t.isAfter(start) || (t == LocalTime.MIDNIGHT && start != LocalTime.MIDNIGHT) // special case for midnight if needed, but here 12:00 AM is start of day
        }.toMutableList()

        if (!validEndTimes.contains("11:59 PM")) {
             // Just filtering standard > start
             val parsedTimes = timeOptions.map { LocalTime.parse(it, DateTimeFormatter.ofPattern("hh:mm a")) }
             validEndTimes.clear()
             validEndTimes.addAll(parsedTimes.filter { it.isAfter(start) }.map { it.format(DateTimeFormatter.ofPattern("hh:mm a")) })
        }

        val endAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, validEndTimes)
        state.endTimeDropdown.setAdapter(endAdapter)

        // Reset end time if it's no longer valid
        if (state.endTime != null && !state.endTime!!.isAfter(start)) {
            state.endTime = null
            state.endTimeDropdown.setText("", false)
        }
    }

    private fun buildMaxHoursJson(): JSONObject {
        val dailyStr = dailyMaxDropdown.text.toString()
        val weeklyStr = weeklyMaxDropdown.text.toString()
        val daily = dailyStr.toIntOrNull() ?: 8
        val weekly = weeklyStr.toIntOrNull() ?: 40
        return JSONObject().apply {
            put("maxHoursDaily", daily)
            put("maxHoursWeekly", weekly)
        }
    }

    private fun buildAvailabilityJson(): JSONObject {
        val requestObj = JSONObject()
        for (dayKey in dayNames) {
            val state = dayViews[dayKey]!!
            val array = JSONArray()
            if (!state.isNotAvailable) {
                val slotObj = JSONObject()
                slotObj.put("allDay", state.isAllDay)
                if (state.isAllDay) {
                    slotObj.put("start", "00:00:00")
                    slotObj.put("end", "00:00:00")
                } else {
                    slotObj.put("start", state.startTime!!.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                    slotObj.put("end", state.endTime!!.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                }
                array.put(slotObj)
            }
            requestObj.put(dayKey, array)
        }
        return requestObj
    }

    private fun loadCurrentData() {
        lifecycleScope.launch {
            try {
                val maxHours = repository.getMaxHours(false)
                val statusMax = maxHours?.pending ?: maxHours?.approved
                if (statusMax != null) {
                    dailyMaxDropdown.setText(statusMax.maxHoursDaily?.toString(), false)
                    weeklyMaxDropdown.setText(statusMax.maxHoursWeekly?.toString(), false)
                } else {
                    dailyMaxDropdown.setText("8", false)
                    weeklyMaxDropdown.setText("40", false)
                }

                val availability = repository.getAvailability(false)
                val statusAvail = availability?.pending ?: availability?.approved
                
                if (statusAvail?.availableTime != null) {
                    val map = statusAvail.availableTime
                    for (dayKey in dayNames) {
                        val slots = map[dayKey] ?: emptyList()
                        val state = dayViews[dayKey]!!

                        if (slots.isEmpty()) {
                            state.isNotAvailable = true
                            state.startTimeDropdown.setText("Not Available", false)
                            state.endTimeLayout.visibility = View.GONE
                        } else {
                            val slot = slots.first()
                            if (slot.allDay == true) {
                                state.isAllDay = true
                                state.startTimeDropdown.setText("All Day", false)
                                state.endTimeLayout.visibility = View.GONE
                            } else if (slot.start != null && slot.end != null) {
                                try {
                                    val s = LocalTime.parse(slot.start)
                                    val e = LocalTime.parse(slot.end)
                                    state.startTime = s
                                    state.endTime = e
                                    state.startTimeDropdown.setText(s.format(DateTimeFormatter.ofPattern("hh:mm a")), false)
                                    state.endTimeLayout.visibility = View.VISIBLE
                                    updateEndTimeOptions(state)
                                    state.endTimeDropdown.setText(e.format(DateTimeFormatter.ofPattern("hh:mm a")), false)
                                } catch (ex: Exception) {
                                    ex.printStackTrace()
                                }
                            }
                        }
                    }
                } else {
                    // Default values if nothing exists
                    for (dayKey in dayNames) {
                        val state = dayViews[dayKey]!!
                        state.isAllDay = true
                        state.startTimeDropdown.setText("All Day", false)
                        state.endTimeLayout.visibility = View.GONE
                    }
                }
                
                originalMaxHoursJson = buildMaxHoursJson().toString()
                originalAvailabilityJson = buildAvailabilityJson().toString()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveAvailability() {
        submitButton.isEnabled = false
        
        val userId = authManager.getUserId() ?: return
        val cafeNoStr = authManager.getCafeNo() ?: "202924" // Fallback to example
        val cafeNo = cafeNoStr.toIntOrNull() ?: 202924

        // Validation
        for (dayKey in dayNames) {
            val state = dayViews[dayKey]!!
            if (!state.isAllDay && !state.isNotAvailable) {
                if (state.startTime == null || state.endTime == null) {
                    Toast.makeText(requireContext(), "Please select valid start and end times for ${dayKey.lowercase()}.", Toast.LENGTH_SHORT).show()
                    submitButton.isEnabled = true
                    return
                }
            }
        }

        val maxHoursObj = buildMaxHoursJson()
        val requestObj = buildAvailabilityJson()
        
        if (maxHoursObj.toString() == originalMaxHoursJson && requestObj.toString() == originalAvailabilityJson) {
            Toast.makeText(requireContext(), "No changes to submit.", Toast.LENGTH_SHORT).show()
            submitButton.isEnabled = true
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Submission")
            .setMessage("Are you sure you want to submit your availability changes?")
            .setPositiveButton("Yes") { _, _ ->
                performSubmission(userId, cafeNo, maxHoursObj, requestObj)
            }
            .setNegativeButton("No") { _, _ ->
                submitButton.isEnabled = true
            }
            .setOnCancelListener {
                submitButton.isEnabled = true
            }
            .show()
    }

    private fun performSubmission(userId: String, cafeNo: Int, maxHoursObj: JSONObject, requestObj: JSONObject) {
        lifecycleScope.launch {
            try {
                var allSuccess = true
                if (maxHoursObj.toString() != originalMaxHoursJson) {
                    val success = repository.updateMaxHours(userId, maxHoursObj.toString())
                    if (!success) allSuccess = false
                }

                if (requestObj.toString() != originalAvailabilityJson) {
                    val payloadObj = JSONObject().apply {
                        put("request", requestObj)
                        put("employeeId", userId)
                        put("effectiveFrom", LocalDate.now().format(DateTimeFormatter.ISO_DATE))
                        put("cafeNo", cafeNo)
                    }
                    val success = repository.updateAvailability(payloadObj.toString())
                    if (!success) allSuccess = false
                }

                if (allSuccess) {
                    parentFragmentManager.setFragmentResult("availability_edit_result", Bundle())
                    Toast.makeText(requireContext(), "Availability updated", Toast.LENGTH_SHORT).show()
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), "Failed to update some changes", Toast.LENGTH_SHORT).show()
                    submitButton.isEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Error saving availability", Toast.LENGTH_SHORT).show()
                submitButton.isEnabled = true
            }
        }
    }
}
package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.anonymousassociate.betterpantry.AuthManager
import com.anonymousassociate.betterpantry.PantryRepository
import com.anonymousassociate.betterpantry.R
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class TimeOffRequestFragment : DialogFragment() {

    private lateinit var radioGroup: RadioGroup
    private lateinit var allDayContainer: LinearLayout
    private lateinit var partialDayContainer: LinearLayout
    private lateinit var startDateInput: TextInputEditText
    private lateinit var endDateInput: TextInputEditText
    private lateinit var partialDateInput: TextInputEditText
    private lateinit var startTimeInput: TextInputEditText
    private lateinit var endTimeInput: TextInputEditText
    private lateinit var commentInput: TextInputEditText
    private lateinit var cancelButton: Button
    private lateinit var submitButton: Button

    private var allDayStart: LocalDate? = null
    private var allDayEnd: LocalDate? = null
    private var partialDate: LocalDate? = null
    private var partialStartTime: LocalTime? = null
    private var partialEndTime: LocalTime? = null

    private val repository by lazy { (requireActivity() as com.anonymousassociate.betterpantry.MainActivity).repository }
    private val authManager by lazy { com.anonymousassociate.betterpantry.AuthManager(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_time_off_request, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        radioGroup = view.findViewById(R.id.typeRadioGroup)
        allDayContainer = view.findViewById(R.id.allDayContainer)
        partialDayContainer = view.findViewById(R.id.partialDayContainer)
        startDateInput = view.findViewById(R.id.startDateInput)
        endDateInput = view.findViewById(R.id.endDateInput)
        partialDateInput = view.findViewById(R.id.partialDateInput)
        startTimeInput = view.findViewById(R.id.startTimeInput)
        endTimeInput = view.findViewById(R.id.endTimeInput)
        commentInput = view.findViewById(R.id.commentInput)
        cancelButton = view.findViewById(R.id.cancelButton)
        submitButton = view.findViewById(R.id.submitButton)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioAllDay) {
                allDayContainer.visibility = View.VISIBLE
                partialDayContainer.visibility = View.GONE
            } else {
                allDayContainer.visibility = View.GONE
                partialDayContainer.visibility = View.VISIBLE
            }
        }

        setupPickers()

        cancelButton.setOnClickListener { dismiss() }
        submitButton.setOnClickListener { submitRequest() }
    }
    
    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun setupPickers() {
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val constraints = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointForward.now())
            .build()

        startDateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Start Date")
                .setSelection(today)
                .setCalendarConstraints(constraints)
                .build()
            
            picker.addOnPositiveButtonClickListener { selection ->
                allDayStart = Instant.ofEpochMilli(selection).atZone(ZoneId.of("UTC")).toLocalDate()
                startDateInput.setText(allDayStart.toString())
                if (allDayEnd == null || allDayEnd!!.isBefore(allDayStart)) {
                    allDayEnd = allDayStart
                    endDateInput.setText(allDayEnd.toString())
                }
            }
            picker.show(parentFragmentManager, "start_date")
        }

        endDateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("End Date")
                .setSelection(today)
                .setCalendarConstraints(constraints)
                .build()
            
            picker.addOnPositiveButtonClickListener { selection ->
                allDayEnd = Instant.ofEpochMilli(selection).atZone(ZoneId.of("UTC")).toLocalDate()
                endDateInput.setText(allDayEnd.toString())
            }
            picker.show(parentFragmentManager, "end_date")
        }

        partialDateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Date")
                .setSelection(today)
                .setCalendarConstraints(constraints)
                .build()
            
            picker.addOnPositiveButtonClickListener { selection ->
                partialDate = Instant.ofEpochMilli(selection).atZone(ZoneId.of("UTC")).toLocalDate()
                partialDateInput.setText(partialDate.toString())
            }
            picker.show(parentFragmentManager, "partial_date")
        }

        startTimeInput.setOnClickListener { showTimePicker(true) }
        endTimeInput.setOnClickListener { showTimePicker(false) }
    }

    private fun showTimePicker(isStart: Boolean) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(if (isStart) 9 else 17)
            .setMinute(0)
            .setTitleText(if (isStart) "Start Time" else "End Time")
            .build()

        picker.addOnPositiveButtonClickListener {
            // Round to nearest 15
            var minute = picker.minute
            val remainder = minute % 15
            if (remainder != 0) {
                minute = if (remainder >= 8) minute + (15 - remainder) else minute - remainder
                if (minute == 60) {
                    minute = 0
                    // hour wrap handled by LocalTime but logic here needs care. 
                    // Actually, simpler to just set it. LocalTime handles valid range? No.
                    // Just take the picker time and round it.
                }
            }
            
            val time = LocalTime.of(picker.hour, picker.minute)
            // Rounding logic properly
            val minutes = time.minute
            val roundedMinutes = ((minutes + 7) / 15) * 15
            val roundedTime = if (roundedMinutes == 60) {
                time.plusHours(1).withMinute(0)
            } else {
                time.withMinute(roundedMinutes)
            }

            if (isStart) {
                partialStartTime = roundedTime
                startTimeInput.setText(roundedTime.format(DateTimeFormatter.ofPattern("h:mm a")))
            } else {
                partialEndTime = roundedTime
                endTimeInput.setText(roundedTime.format(DateTimeFormatter.ofPattern("h:mm a")))
            }
        }
        picker.show(parentFragmentManager, "time_picker")
    }

    private fun submitRequest() {
        val comment = commentInput.text.toString()
        val userId = authManager.getUserId() ?: return
        val requests = JSONArray()

        if (radioGroup.checkedRadioButtonId == R.id.radioAllDay) {
            if (allDayStart == null || allDayEnd == null) {
                Toast.makeText(context, "Please select start and end dates", Toast.LENGTH_SHORT).show()
                return
            }
            if (allDayEnd!!.isBefore(allDayStart)) {
                Toast.makeText(context, "End date cannot be before start date", Toast.LENGTH_SHORT).show()
                return
            }
            val days = ChronoUnit.DAYS.between(allDayStart, allDayEnd) + 1
            if (days > 30) {
                Toast.makeText(context, "Cannot request more than 30 days", Toast.LENGTH_SHORT).show()
                return
            }

            // Loop dates
            var current = allDayStart!!
            while (!current.isAfter(allDayEnd)) {
                val startIso = current.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                val endIso = current.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

                val obj = JSONObject().apply {
                    put("associateComments", comment)
                    put("paneraId", userId)
                    put("startTime", startIso)
                    put("endTime", endIso)
                }
                requests.put(obj)
                current = current.plusDays(1)
            }

        } else {
            if (partialDate == null || partialStartTime == null || partialEndTime == null) {
                Toast.makeText(context, "Please select date and times", Toast.LENGTH_SHORT).show()
                return
            }
            if (!partialEndTime!!.isAfter(partialStartTime)) {
                Toast.makeText(context, "End time must be after start time", Toast.LENGTH_SHORT).show()
                return
            }

            val startIso = LocalDateTime.of(partialDate, partialStartTime).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
            val endIso = LocalDateTime.of(partialDate, partialEndTime).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

            val obj = JSONObject().apply {
                put("associateComments", comment)
                put("paneraId", userId)
                put("startTime", startIso)
                put("endTime", endIso)
            }
            requests.put(obj)
        }

        // Show Confirmation
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Request")
            .setMessage("Are you sure you want to submit this time off request?")
            .setPositiveButton("Yes") { _, _ ->
                performSubmission(requests)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun performSubmission(requests: JSONArray) {
        lifecycleScope.launch {
            submitButton.isEnabled = false
            try {
                val success = repository.requestTimeOff(requests.toString())
                if (success) {
                    Toast.makeText(context, "Request submitted", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult("time_off_request_result", Bundle())
                    dismiss()
                } else {
                    Toast.makeText(context, "Failed to submit request", Toast.LENGTH_SHORT).show()
                    submitButton.isEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                submitButton.isEnabled = true
            }
        }
    }
}

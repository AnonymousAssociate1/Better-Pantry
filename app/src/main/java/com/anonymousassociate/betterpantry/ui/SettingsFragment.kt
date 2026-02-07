package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import com.anonymousassociate.betterpantry.ui.NotificationSettingsDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.anonymousassociate.betterpantry.MoneyPreferences
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.SettingsPreferences
import com.anonymousassociate.betterpantry.models.ScheduleData
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

class SettingsFragment : Fragment() {

    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var moneyPreferences: MoneyPreferences
    private var moneyDialog: Dialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settingsPreferences = SettingsPreferences(requireContext())
        moneyPreferences = MoneyPreferences(requireContext())

        val myPayButton: MaterialButton = view.findViewById(R.id.myPayButton)
        val notificationsButton: MaterialButton = view.findViewById(R.id.notificationsButton)
        val hideAvailabilitySwitch: SwitchMaterial = view.findViewById(R.id.hideAvailabilitySwitch)
        val logoutButton: MaterialButton = view.findViewById(R.id.logoutButton)

        // Setup Toggle
        hideAvailabilitySwitch.isChecked = settingsPreferences.showAvailabilityOnCalendar
        hideAvailabilitySwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsPreferences.showAvailabilityOnCalendar = isChecked
        }

        // Setup My Pay
        myPayButton.setOnClickListener {
            showMoneySettingsDialog()
        }

        // Setup Notifications
        notificationsButton.setOnClickListener {
            NotificationSettingsDialog(requireContext()).show()
        }

        // Setup Logout
        logoutButton.setOnClickListener {
            showConfirmationDialog("Log Out", "Do you want to log out?") {
                (requireActivity() as? com.anonymousassociate.betterpantry.MainActivity)?.logout()
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

    private fun showMoneySettingsDialog() {
        if (moneyDialog?.isShowing == true) return

        val dialog = Dialog(requireContext())
        moneyDialog = dialog
        dialog.setContentView(R.layout.dialog_money_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val switch = dialog.findViewById<SwitchMaterial>(R.id.showMoneySwitch)
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

        // We can't easily calculate scheduled hours here without passing ScheduleData.
        // For simplicity in Settings, we might omit the "current week" calc or try to fetch it.
        // But the user just wants the settings.
        // Let's just leave the hours input blank or 0 for manual calculation if schedule isn't available.
        // Or we can try to get it from cache.
        val scheduleCache = com.anonymousassociate.betterpantry.ScheduleCache(requireContext())
        val scheduleData = scheduleCache.getSchedule()
        
        val scheduledHours = if (scheduleData != null) calculateScheduledHoursForCurrentWeek(scheduleData) else 0f
        
        val scheduledHoursStr = if (scheduledHours % 1.0 == 0.0) {
            scheduledHours.toInt().toString()
        } else {
            String.format("%.2f", scheduledHours).trimEnd('0').trimEnd('.')
        }
        
        hoursInput.setText(scheduledHoursStr)
        
        if (scheduledHours > 0) {
            val range = getWeekDateRangeText(scheduleData)
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
            
            if (switch.isChecked) {
                moneyPreferences.hourlyWage = wage
            }
            
            val inputHours = hours
            if (Math.abs(inputHours - scheduledHours) < 0.01) {
                 if (scheduledHours > 0) weekRangeText.visibility = View.VISIBLE
            } else {
                 weekRangeText.visibility = View.GONE
            }
        }
        
        calculate()

        switch.setOnCheckedChangeListener { _, isChecked ->
            moneyPreferences.showMoney = isChecked
            container.visibility = if (isChecked) View.VISIBLE else View.GONE
            calculate()
        }

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { calculate() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        wageInput.addTextChangedListener(watcher)
        hoursInput.addTextChangedListener(watcher)

        dialog.show()
    }

    private fun getWeekDateRangeText(scheduleData: ScheduleData?): String {
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

    private fun calculateScheduledHoursForCurrentWeek(scheduleData: ScheduleData): Float {
        val myShifts = scheduleData.currentShifts ?: return 0f
        
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
}
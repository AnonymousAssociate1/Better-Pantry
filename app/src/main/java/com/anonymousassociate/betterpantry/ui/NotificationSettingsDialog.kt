package com.anonymousassociate.betterpantry.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.anonymousassociate.betterpantry.NotificationPreferences
import com.anonymousassociate.betterpantry.PaycheckConfig
import com.anonymousassociate.betterpantry.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class NotificationSettingsDialog(context: Context) : Dialog(context) {

    private val prefs = NotificationPreferences(context)
    private var tempPaycheckConfig: PaycheckConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_notification_settings)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        setupSwitches()
        setupPaycheckConfig()

        findViewById<View>(R.id.closeButton).setOnClickListener { dismiss() }
    }

    private fun setupSwitches() {
        val switchShiftPickups = findViewById<SwitchCompat>(R.id.switchShiftPickups)
        val switchManagerCalls = findViewById<SwitchCompat>(R.id.switchManagerCalls)
        val switchSchedulePublished = findViewById<SwitchCompat>(R.id.switchSchedulePublished)
        val switchShiftApproved = findViewById<SwitchCompat>(R.id.switchShiftApproved)
        val switchPaycheck = findViewById<SwitchCompat>(R.id.switchPaycheck)
        val switchOther = findViewById<SwitchCompat>(R.id.switchOther)
        val paycheckConfigContainer = findViewById<LinearLayout>(R.id.paycheckConfigContainer)

        switchShiftPickups.isChecked = prefs.shiftPickupsEnabled
        switchManagerCalls.isChecked = prefs.managerCallsEnabled
        switchSchedulePublished.isChecked = prefs.schedulePublishedEnabled
        switchShiftApproved.isChecked = prefs.shiftApprovedEnabled
        switchPaycheck.isChecked = prefs.paycheckEnabled
        switchOther.isChecked = prefs.otherEnabled

        paycheckConfigContainer.visibility = if (prefs.paycheckEnabled) View.VISIBLE else View.GONE

        switchShiftPickups.setOnCheckedChangeListener { _, isChecked -> prefs.shiftPickupsEnabled = isChecked }
        switchManagerCalls.setOnCheckedChangeListener { _, isChecked -> prefs.managerCallsEnabled = isChecked }
        switchSchedulePublished.setOnCheckedChangeListener { _, isChecked -> prefs.schedulePublishedEnabled = isChecked }
        switchShiftApproved.setOnCheckedChangeListener { _, isChecked -> prefs.shiftApprovedEnabled = isChecked }
        switchOther.setOnCheckedChangeListener { _, isChecked -> prefs.otherEnabled = isChecked }

        switchPaycheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.paycheckEnabled = isChecked
            paycheckConfigContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && prefs.paycheckConfig == null) {
                // Set default config if none exists
                val default = PaycheckConfig("BIWEEKLY", LocalDate.now().toString(), 5) // Default to Biweekly Friday
                prefs.paycheckConfig = default
                updatePaycheckUI(default)
            }
        }
    }

    private fun setupPaycheckConfig() {
        val dropdownFrequency = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerFrequency)
        val containerBiweekly = findViewById<LinearLayout>(R.id.containerBiweekly)
        val containerMonthly = findViewById<LinearLayout>(R.id.containerMonthly)
        val containerDayBased = findViewById<LinearLayout>(R.id.containerDayBased)
        
        // Dynamic inputs
        val inputStartDate = findViewById<TextInputEditText>(R.id.inputStartDate)
        val inputDay1 = findViewById<TextInputEditText>(R.id.inputDay1)
        val inputDay2 = findViewById<TextInputEditText>(R.id.inputDay2)
        val inputDay2Container = findViewById<View>(R.id.inputDay2Container)
        
        val dropdownWeekIndex1 = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerWeekIndex1)
        val dropdownWeekIndex2 = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerWeekIndex2)
        val dropdownDayOfWeek = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerDayOfWeek)
        val textAnd = findViewById<TextView>(R.id.textAnd)
        
        val spinnerWeekIndex2Layout = findViewById<View>(R.id.spinnerWeekIndex2).parent.parent as? View ?: findViewById<View>(R.id.spinnerWeekIndex2)

        // Set Dropdown Backgrounds
        val popupBackground = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_dropdown_popup)
        dropdownFrequency.setDropDownBackgroundDrawable(popupBackground)
        dropdownWeekIndex1.setDropDownBackgroundDrawable(popupBackground)
        dropdownWeekIndex2.setDropDownBackgroundDrawable(popupBackground)
        dropdownDayOfWeek.setDropDownBackgroundDrawable(popupBackground)

        val frequencies = arrayOf(
            "Every week (Weekly)",
            "Every other week (Bi-weekly)",
            "Twice a month (Dates - e.g. 1st & 15th)",
            "Twice a month (Days - e.g. 1st & 3rd Fri)",
            "Once a month (Date - e.g. 1st)",
            "Once a month (Day - e.g. 1st Fri)"
        )
        val adapter = ArrayAdapter(context, R.layout.item_dropdown_menu, frequencies)
        dropdownFrequency.setAdapter(adapter)

        // Setup Week Index Spinners
        val weekIndexes = arrayOf("1st", "2nd", "3rd", "4th", "Last")
        val weekAdapter = ArrayAdapter(context, R.layout.item_dropdown_menu, weekIndexes)
        dropdownWeekIndex1.setAdapter(weekAdapter)
        dropdownWeekIndex2.setAdapter(weekAdapter)

        // Setup Day of Week Spinner
        val daysOfWeek = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val dayAdapter = ArrayAdapter(context, R.layout.item_dropdown_menu, daysOfWeek)
        dropdownDayOfWeek.setAdapter(dayAdapter)

        // Load current config
        val currentConfig = prefs.paycheckConfig ?: PaycheckConfig("BIWEEKLY", LocalDate.now().toString())
        tempPaycheckConfig = currentConfig
        
        // Initial UI State
        updatePaycheckUI(currentConfig)

        dropdownFrequency.setOnItemClickListener { _, _, position, _ ->
            val type = when (position) {
                0 -> "WEEKLY"
                1 -> "BIWEEKLY"
                2 -> "SEMIMONTHLY_DATE"
                3 -> "SEMIMONTHLY_DAY"
                4 -> "MONTHLY_DATE"
                5 -> "MONTHLY_DAY"
                else -> "BIWEEKLY"
            }
            
            // Reset Visibility
            containerBiweekly.visibility = View.GONE
            containerMonthly.visibility = View.GONE
            containerDayBased.visibility = View.GONE
            inputDay2Container.visibility = View.GONE
            
            // Find layout parents for visibility control
            val weekIndex2Layout = (dropdownWeekIndex2.parent as? View) ?: dropdownWeekIndex2
            weekIndex2Layout.visibility = View.GONE
            textAnd.visibility = View.GONE

            when (type) {
                "WEEKLY", "BIWEEKLY" -> containerBiweekly.visibility = View.VISIBLE
                "SEMIMONTHLY_DATE" -> {
                    containerMonthly.visibility = View.VISIBLE
                    inputDay2Container.visibility = View.VISIBLE
                }
                "MONTHLY_DATE" -> {
                    containerMonthly.visibility = View.VISIBLE
                }
                "SEMIMONTHLY_DAY" -> {
                    containerDayBased.visibility = View.VISIBLE
                    weekIndex2Layout.visibility = View.VISIBLE
                    textAnd.visibility = View.VISIBLE
                }
                "MONTHLY_DAY" -> {
                    containerDayBased.visibility = View.VISIBLE
                }
            }

            tempPaycheckConfig = tempPaycheckConfig?.copy(frequencyType = type)
            saveConfig()
        }

        val autoSaveListener = AdapterView.OnItemClickListener { _, _, _, _ ->
            saveConfig()
        }

        dropdownWeekIndex1.setOnItemClickListener(autoSaveListener)
        dropdownWeekIndex2.setOnItemClickListener(autoSaveListener)
        dropdownDayOfWeek.setOnItemClickListener(autoSaveListener)
        
        inputStartDate.setOnClickListener {
            val now = LocalDate.now()
            DatePickerDialog(context, { _, year, month, day ->
                val date = LocalDate.of(year, month + 1, day)
                inputStartDate.setText(date.format(DateTimeFormatter.ofPattern("M/d/yyyy")))
                tempPaycheckConfig = tempPaycheckConfig?.copy(startDate = date.toString())
                saveConfig()
            }, now.year, now.monthValue - 1, now.dayOfMonth).show()
        }

        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfig()
            }
        }
        
        inputDay1.addTextChangedListener(textWatcher)
        inputDay2.addTextChangedListener(textWatcher)
    }

    private fun saveConfig() {
        val dropdownFrequency = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerFrequency)
        val dropdownWeekIndex1 = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerWeekIndex1)
        val dropdownWeekIndex2 = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerWeekIndex2)
        val dropdownDayOfWeek = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerDayOfWeek)
        val inputDay1 = findViewById<TextInputEditText>(R.id.inputDay1)
        val inputDay2 = findViewById<TextInputEditText>(R.id.inputDay2)

        // Helper to find index of text in adapter
        fun getIndex(autoCompleteTextView: android.widget.AutoCompleteTextView): Int {
            val adapter = autoCompleteTextView.adapter
            val text = autoCompleteTextView.text.toString()
            for (i in 0 until adapter.count) {
                if (adapter.getItem(i).toString() == text) return i
            }
            return -1
        }

        val position = getIndex(dropdownFrequency)
        val type = when (position) {
            0 -> "WEEKLY"
            1 -> "BIWEEKLY"
            2 -> "SEMIMONTHLY_DATE"
            3 -> "SEMIMONTHLY_DAY"
            4 -> "MONTHLY_DATE"
            5 -> "MONTHLY_DAY"
            else -> tempPaycheckConfig?.frequencyType ?: "BIWEEKLY"
        }
        
        var configToSave = tempPaycheckConfig?.copy(frequencyType = type) ?: PaycheckConfig(type)

        when (type) {
            "SEMIMONTHLY_DATE", "SEMIMONTHLY", "MONTHLY_DATE", "MONTHLY" -> {
                val d1 = inputDay1.text.toString().toIntOrNull()
                val d2 = if (type.contains("SEMIMONTHLY")) inputDay2.text.toString().toIntOrNull() else null
                configToSave = configToSave.copy(dayOfMonth1 = d1, dayOfMonth2 = d2)
            }
            "SEMIMONTHLY_DAY", "MONTHLY_DAY" -> {
                // Map index 0->1, 1->2... 4->-1 (Last)
                fun mapIndex(idx: Int): Int = if (idx == 4) -1 else idx + 1
                
                val w1 = mapIndex(getIndex(dropdownWeekIndex1))
                val w2 = if (type == "SEMIMONTHLY_DAY") mapIndex(getIndex(dropdownWeekIndex2)) else null
                val dow = getIndex(dropdownDayOfWeek) + 1 // 1=Mon...7=Sun
                
                configToSave = configToSave.copy(weekIndex1 = w1, weekIndex2 = w2, dayOfWeek = dow)
            }
        }
        
        prefs.paycheckConfig = configToSave
        com.anonymousassociate.betterpantry.PaycheckWorker.schedule(context)
    }

    private fun updatePaycheckUI(config: PaycheckConfig) {
        val inputStartDate = findViewById<TextInputEditText>(R.id.inputStartDate)
        val inputDay1 = findViewById<TextInputEditText>(R.id.inputDay1)
        val inputDay2 = findViewById<TextInputEditText>(R.id.inputDay2)
        val dropdownFrequency = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerFrequency)
        val dropdownWeekIndex1 = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerWeekIndex1)
        val dropdownWeekIndex2 = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerWeekIndex2)
        val dropdownDayOfWeek = findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerDayOfWeek)

        config.startDate?.let {
            try {
                val date = LocalDate.parse(it)
                inputStartDate.setText(date.format(DateTimeFormatter.ofPattern("M/d/yyyy")))
            } catch (e: Exception) {}
        }

        inputDay1.setText(config.dayOfMonth1?.toString() ?: "")
        inputDay2.setText(config.dayOfMonth2?.toString() ?: "")
        
        // Restore spinners
        val spinnerIndex = when(config.frequencyType) {
            "WEEKLY" -> 0
            "BIWEEKLY" -> 1
            "SEMIMONTHLY" -> 2
            "SEMIMONTHLY_DATE" -> 2
            "SEMIMONTHLY_DAY" -> 3
            "MONTHLY" -> 4
            "MONTHLY_DATE" -> 4
            "MONTHLY_DAY" -> 5
            else -> 1
        }
        
        val frequencies = arrayOf(
            "Every week (Weekly)",
            "Every other week (Bi-weekly)",
            "Twice a month (Dates - e.g. 1st & 15th)",
            "Twice a month (Days - e.g. 1st & 3rd Fri)",
            "Once a month (Date - e.g. 1st)",
            "Once a month (Day - e.g. 1st Fri)"
        )
        dropdownFrequency.setText(frequencies.getOrElse(spinnerIndex) { frequencies[1] }, false)
        
        // Trigger visibility logic manually since setting text doesn't fire click listener
        val containerBiweekly = findViewById<View>(R.id.containerBiweekly)
        val containerMonthly = findViewById<View>(R.id.containerMonthly)
        val containerDayBased = findViewById<View>(R.id.containerDayBased)
        val inputDay2Container = findViewById<View>(R.id.inputDay2Container)
        val textAnd = findViewById<View>(R.id.textAnd)
        val weekIndex2Layout = (dropdownWeekIndex2.parent as? View) ?: dropdownWeekIndex2

        containerBiweekly.visibility = View.GONE
        containerMonthly.visibility = View.GONE
        containerDayBased.visibility = View.GONE
        inputDay2Container.visibility = View.GONE
        weekIndex2Layout.visibility = View.GONE
        textAnd.visibility = View.GONE

        when (config.frequencyType) {
            "WEEKLY", "BIWEEKLY" -> containerBiweekly.visibility = View.VISIBLE
            "SEMIMONTHLY", "SEMIMONTHLY_DATE" -> {
                containerMonthly.visibility = View.VISIBLE
                inputDay2Container.visibility = View.VISIBLE
            }
            "MONTHLY", "MONTHLY_DATE" -> containerMonthly.visibility = View.VISIBLE
            "SEMIMONTHLY_DAY" -> {
                containerDayBased.visibility = View.VISIBLE
                weekIndex2Layout.visibility = View.VISIBLE
                textAnd.visibility = View.VISIBLE
            }
            "MONTHLY_DAY" -> containerDayBased.visibility = View.VISIBLE
            else -> containerBiweekly.visibility = View.VISIBLE
        }

        // Helper to map 1->0, 2->1... -1->4
        fun mapIndexToPos(idx: Int?): Int = if (idx == -1) 4 else (idx ?: 1) - 1
        val weekIndexes = arrayOf("1st", "2nd", "3rd", "4th", "Last")
        dropdownWeekIndex1.setText(weekIndexes.getOrElse(mapIndexToPos(config.weekIndex1)) { weekIndexes[0] }, false)
        dropdownWeekIndex2.setText(weekIndexes.getOrElse(mapIndexToPos(config.weekIndex2)) { weekIndexes[0] }, false)
        
        val daysOfWeek = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val dowPos = (config.dayOfWeek ?: 1) - 1
        dropdownDayOfWeek.setText(daysOfWeek.getOrElse(if (dowPos in 0..6) dowPos else 4) { daysOfWeek[4] }, false)
    }
}

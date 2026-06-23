package com.anonymousassociate.betterpantry.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ScheduleCache
import com.anonymousassociate.betterpantry.SettingsPreferences
import com.anonymousassociate.betterpantry.models.CafeInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class CafeSettingsDialog(context: Context, private val onSettingsSaved: () -> Unit) : Dialog(context) {

    private val settingsPreferences = SettingsPreferences(context)
    private val scheduleCache = ScheduleCache(context)
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CafeSettingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_cafe_settings)
        window?.setLayout((context.resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        recyclerView = findViewById(R.id.cafeSettingsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)

        val cafes = getCafesList()
        adapter = CafeSettingsAdapter(cafes)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.closeButton).setOnClickListener { dismiss() }

        setOnDismissListener {
            cafes.forEach { item ->
                settingsPreferences.setCustomCafeName(item.cafeNo, item.customName)
            }
            onSettingsSaved()
            com.anonymousassociate.betterpantry.widgets.WidgetUpdater.updateAllWidgets(context)
        }
    }

    private fun getCafesList(): List<CafeItem> {
        val authManager = com.anonymousassociate.betterpantry.AuthManager(context)
        val homeCafe = authManager.getCafeNo()
        val userId = authManager.getUserId()
        val schedule = scheduleCache.getSchedule()
        val teamMembers = scheduleCache.getTeamSchedule()

        val cafeNos = settingsPreferences.getAssignedCafeNumbers(schedule, teamMembers, homeCafe, userId)
        val cafeList = schedule?.cafeList ?: emptyList()

        return cafeNos.map { cafeNo ->
            val matchedCafeInfo = cafeList.firstOrNull {
                settingsPreferences.getCafeNumberFromDepartment(it.departmentName, it.address?.addressLine) == cafeNo
            } ?: CafeInfo(
                departmentName = "Cafe $cafeNo",
                phoneNumber = "",
                address = settingsPreferences.getAddressFromSavedName(cafeNo) ?: com.anonymousassociate.betterpantry.models.Address(
                    addressLine = "Address Unavailable",
                    city = "",
                    state = "",
                    zipCode = ""
                )
            )

            // Cache the default constructed display name if not already cached
            val address = matchedCafeInfo.address
            val defaultName = if (address != null && address.addressLine != "Address Unavailable") {
                "#$cafeNo - ${address.addressLine ?: ""}, ${address.city ?: ""}, ${address.state ?: ""}".trimEnd(',', ' ')
            } else {
                "#$cafeNo"
            }
            if (settingsPreferences.getCafeDisplayName(cafeNo, null) == "#$cafeNo" && defaultName != "#$cafeNo") {
                settingsPreferences.saveCafeDisplayName(cafeNo, defaultName)
            }

            val customName = settingsPreferences.getCustomCafeName(cafeNo) ?: ""
            val isEnabled = settingsPreferences.isCafeEnabled(cafeNo)
            val isNotificationsEnabled = settingsPreferences.isCafeNotificationsEnabled(cafeNo)
            CafeItem(
                cafeNo = cafeNo,
                cafeInfo = matchedCafeInfo,
                customName = customName,
                isEnabled = isEnabled,
                isNotificationsEnabled = isNotificationsEnabled
            )
        }
    }

    data class CafeItem(
        val cafeNo: String,
        val cafeInfo: CafeInfo,
        var customName: String,
        var isEnabled: Boolean,
        var isNotificationsEnabled: Boolean
    )

    inner class CafeSettingsAdapter(private val items: List<CafeItem>) : RecyclerView.Adapter<CafeSettingsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cafe_setting, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val renameInput: TextInputEditText = itemView.findViewById(R.id.renameInput)
            private val showCafeSwitch: SwitchMaterial = itemView.findViewById(R.id.showCafeSwitch)
            private val showNotificationsSwitch: SwitchMaterial = itemView.findViewById(R.id.showNotificationsSwitch)

            fun bind(item: CafeItem) {
                val address = item.cafeInfo.address
                val fallbackHint = "#${item.cafeNo} - ${address?.addressLine ?: ""}, ${address?.city ?: ""}, ${address?.state ?: ""}"
                
                renameInput.setText(item.customName)
                renameInput.hint = fallbackHint

                renameInput.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        item.customName = s.toString()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })

                showCafeSwitch.isChecked = item.isEnabled
                showCafeSwitch.setOnCheckedChangeListener { _, isChecked ->
                    val otherEnabled = items.filter { it != item }.any { it.isEnabled }
                    if (!isChecked && !otherEnabled) {
                        showCafeSwitch.isChecked = true
                        Toast.makeText(context, "You are required to have at least one cafe on.", Toast.LENGTH_SHORT).show()
                    } else {
                        item.isEnabled = isChecked
                        settingsPreferences.setCafeEnabled(item.cafeNo, isChecked)
                        onSettingsSaved()
                    }
                }

                showNotificationsSwitch.isChecked = item.isNotificationsEnabled
                showNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
                    item.isNotificationsEnabled = isChecked
                    settingsPreferences.setCafeNotificationsEnabled(item.cafeNo, isChecked)
                    onSettingsSaved()
                }
            }
        }
    }
}

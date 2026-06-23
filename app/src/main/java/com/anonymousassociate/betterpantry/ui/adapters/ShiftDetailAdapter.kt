package com.anonymousassociate.betterpantry.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.models.CafeInfo
import com.anonymousassociate.betterpantry.models.Shift
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ShiftDetailAdapter(
    private val shifts: List<Shift>,
    private val cafeInfo: CafeInfo?
) : RecyclerView.Adapter<ShiftDetailAdapter.ShiftDetailViewHolder>() {



    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShiftDetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shift_detail, parent, false)
        return ShiftDetailViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShiftDetailViewHolder, position: Int) {
        holder.bind(shifts[position])
    }

    override fun getItemCount() = shifts.size

    inner class ShiftDetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val detailDateText: TextView = itemView.findViewById(R.id.detailDateText)
        private val detailWorkstationText: TextView = itemView.findViewById(R.id.detailWorkstationText)
        private val detailLocationText: TextView = itemView.findViewById(R.id.detailLocationText)

        fun bind(shift: Shift) {
            try {
                val startDateTime = LocalDateTime.parse(shift.startDateTime)
                val endDateTime = LocalDateTime.parse(shift.endDateTime)

                val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
                val timeFormatter = DateTimeFormatter.ofPattern("h:mma")

                val dayText = startDateTime.format(dayFormatter)
                val startTime = startDateTime.format(timeFormatter)
                val endTime = endDateTime.format(timeFormatter)

                detailDateText.text = "$dayText $startTime - $endTime"

                // Map workstation ID to custom name
                val workstationName = com.anonymousassociate.betterpantry.utils.WorkstationUtils.getDisplayName(
                    shift.workstationId,
                    shift.workstationName,
                    shift.workstationCode
                )

                // Show workstation
                val workstation = if (!shift.workstationGroupDisplayName.isNullOrEmpty() && !workstationName.isNullOrEmpty()) {
                    "${shift.workstationGroupDisplayName} - $workstationName"
                } else if (!workstationName.isNullOrEmpty()) {
                    workstationName
                } else {
                    "Shift"
                }
                detailWorkstationText.text = workstation

                // Show location
                val prefs = com.anonymousassociate.betterpantry.SettingsPreferences(itemView.context)
                val location = prefs.getCafeDisplayName(shift.cafeNumber, if (cafeInfo != null) listOf(cafeInfo) else null)
                detailLocationText.text = location

            } catch (e: Exception) {
                detailDateText.text = "Unknown date"
                detailWorkstationText.text = shift.workstationName ?: "Shift"
                detailLocationText.text = ""
                e.printStackTrace()
            }
        }
    }
}

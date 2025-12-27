package com.anonymousassociate.betterpantry.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.models.Shift
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ShiftAdapter(
    val shifts: List<Shift>,
    private val onShiftClick: ((Shift) -> Unit)? = null,
    private val subtitleProvider: ((Shift) -> String)? = null
) : RecyclerView.Adapter<ShiftAdapter.ShiftViewHolder>() {

    private val customNames = mapOf(
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
        "1ST_DR" to "Dining Room"
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShiftViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shift, parent, false)
        return ShiftViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShiftViewHolder, position: Int) {
        holder.bind(shifts[position])
    }

    override fun getItemCount() = shifts.size

    inner class ShiftViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val shiftDateText: TextView = itemView.findViewById(R.id.shiftDateText)
        private val shiftLocationText: TextView = itemView.findViewById(R.id.shiftLocationText)

        init {
            // Set click listener on the card view to ensure clicks are captured
            val shiftCard = itemView.findViewById<View>(R.id.shiftCard)
            shiftCard.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onShiftClick?.invoke(shifts[position])
                }
            }
        }

        fun bind(shift: Shift) {
            try {
                val startDateTime = LocalDateTime.parse(shift.startDateTime)
                val endDateTime = LocalDateTime.parse(shift.endDateTime)

                val dayFormatter = DateTimeFormatter.ofPattern("E M/d")
                val timeFormatter = DateTimeFormatter.ofPattern("h:mma")

                val dayText = startDateTime.format(dayFormatter)
                val startTime = startDateTime.format(timeFormatter)
                val endTime = endDateTime.format(timeFormatter)

                shiftDateText.text = "$dayText $startTime - $endTime"

                // Check if subtitle provider is available
                if (subtitleProvider != null) {
                    shiftLocationText.text = subtitleProvider.invoke(shift)
                } else {
                    // Fallback to default logic
                    // Map workstation ID to custom name
                    val workstationId = shift.workstationId ?: shift.workstationCode ?: ""
                    var workstationName = customNames[workstationId]
                    
                    // If not found by ID, try finding by name (as key)
                    if (workstationName == null) {
                         workstationName = customNames[shift.workstationName]
                    }
                    
                    // If still null, use the raw name
                    if (workstationName == null) {
                        workstationName = shift.workstationName
                    }

                    // Show workstation name - Duration
                    val name = if (!workstationName.isNullOrEmpty()) workstationName else "Shift"
                    
                    val duration = java.time.Duration.between(startDateTime, endDateTime)
                    val hrs = duration.toHours()
                    val mins = duration.toMinutes() % 60
                    
                    val durationStr = StringBuilder()
                    if (hrs > 0) durationStr.append("$hrs hour${if (hrs != 1L) "s" else ""}")
                    if (mins > 0) {
                        if (durationStr.isNotEmpty()) durationStr.append(" ")
                        durationStr.append("$mins minute${if (mins != 1L) "s" else ""}")
                    }
                    
                    shiftLocationText.text = "$name - $durationStr"
                }

            } catch (e: Exception) {
                shiftDateText.text = "Unknown date"
                shiftLocationText.text = shift.workstationName ?: "Shift"
                e.printStackTrace()
            }
        }
    }
}

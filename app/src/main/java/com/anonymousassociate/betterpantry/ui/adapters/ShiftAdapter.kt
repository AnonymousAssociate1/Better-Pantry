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
    var shifts: List<Shift>,
    private val onShiftClick: ((Shift) -> Unit)? = null,
    private val subtitleProvider: ((Shift) -> String)? = null,
    private val showMoney: Boolean = false,
    private val hourlyWage: Float = 0f
) : RecyclerView.Adapter<ShiftAdapter.ShiftViewHolder>() {

    fun updateData(newShifts: List<Shift>) {
        if (shifts != newShifts) {
            shifts = newShifts
            notifyDataSetChanged()
        }
    }



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
        private val shiftMoneyText: TextView = itemView.findViewById(R.id.shiftMoneyText)

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
                    val name = com.anonymousassociate.betterpantry.utils.WorkstationUtils.getDisplayName(
                        shift.workstationId,
                        shift.workstationName,
                        shift.workstationCode
                    )
                    
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

                if (showMoney && hourlyWage > 0) {
                    val duration = java.time.Duration.between(startDateTime, endDateTime)
                    val hours = duration.toMinutes() / 60.0
                    val earnings = hours * hourlyWage
                    shiftMoneyText.text = String.format("$%.2f", earnings)
                    shiftMoneyText.visibility = View.VISIBLE
                } else {
                    shiftMoneyText.visibility = View.GONE
                }

            } catch (e: Exception) {
                shiftDateText.text = "Unknown date"
                shiftLocationText.text = shift.workstationName ?: "Shift"
                shiftMoneyText.visibility = View.GONE
                e.printStackTrace()
            }
        }
    }
}

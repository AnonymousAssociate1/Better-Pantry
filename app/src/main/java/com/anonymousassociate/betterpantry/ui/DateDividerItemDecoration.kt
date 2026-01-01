package com.anonymousassociate.betterpantry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.ui.adapters.ShiftAdapter
import java.time.LocalDate

class DateDividerItemDecoration(private val context: Context) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.calendar_border)
        strokeWidth = 1.dpToPx().toFloat()
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val adapter = parent.adapter as? ShiftAdapter ?: return
        
        if (position < adapter.shifts.size - 1) {
            val currentShift = adapter.shifts[position]
            val nextShift = adapter.shifts[position + 1]
            
            if (!isSameDay(currentShift.startDateTime, nextShift.startDateTime)) {
                outRect.bottom = 28.dpToPx()
            }
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? ShiftAdapter ?: return
        val left = parent.paddingLeft
        val right = parent.width - parent.paddingRight

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            
            if (position != RecyclerView.NO_POSITION && position < adapter.shifts.size - 1) {
                val currentShift = adapter.shifts[position]
                val nextShift = adapter.shifts[position + 1]

                if (!isSameDay(currentShift.startDateTime, nextShift.startDateTime)) {
                    val params = child.layoutParams as RecyclerView.LayoutParams
                    val top = child.bottom + params.bottomMargin + 8.dpToPx()
                    c.drawLine(left.toFloat(), top.toFloat(), right.toFloat(), top.toFloat(), paint)
                }
            }
        }
    }

    private fun isSameDay(dateStr1: String?, dateStr2: String?): Boolean {
        if (dateStr1 == null || dateStr2 == null) return false
        return try {
            val d1 = LocalDate.parse(dateStr1.substring(0, 10))
            val d2 = LocalDate.parse(dateStr2.substring(0, 10))
            d1 == d2
        } catch (e: Exception) {
            false
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}

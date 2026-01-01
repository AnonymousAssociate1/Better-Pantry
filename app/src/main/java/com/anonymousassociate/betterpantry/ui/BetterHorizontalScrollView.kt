package com.anonymousassociate.betterpantry.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import kotlin.math.abs

class BetterHorizontalScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    private var xDistance = 0f
    private var yDistance = 0f
    private var lastX = 0f
    private var lastY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                xDistance = 0f
                yDistance = 0f
                lastX = ev.x
                lastY = ev.y
                // Let the parent know we haven't decided yet, but don't disallow them intercepting yet?
                // Actually standard behavior is fine for DOWN.
            }
            MotionEvent.ACTION_MOVE -> {
                val curX = ev.x
                val curY = ev.y
                xDistance += abs(curX - lastX)
                yDistance += abs(curY - lastY)
                lastX = curX
                lastY = curY
                
                // If vertical movement is greater than horizontal, do NOT intercept.
                // This lets the parent ScrollView consume the event.
                if (yDistance > xDistance) {
                    return false
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}

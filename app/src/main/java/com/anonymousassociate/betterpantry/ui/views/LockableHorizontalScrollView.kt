package com.anonymousassociate.betterpantry.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * A HorizontalScrollView that properly coordinates with a parent vertical ScrollView.
 *
 * Standard HorizontalScrollView intercepts ALL touch events once a scroll begins,
 * preventing the parent from ever scrolling vertically — even after the user lifts
 * and re-places their finger. This subclass:
 *   1. Only claims the gesture as horizontal once dx > dy (angle threshold).
 *   2. Calls requestDisallowInterceptTouchEvent(true) only when scrolling horizontally,
 *      so the parent vertical scroller gets the event when the motion is vertical.
 */
class LockableHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var isScrollingHorizontally = false
    private var touchDecided = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isScrollingHorizontally = false
                touchDecided = false
                // Let parent decide whether it wants to intercept first
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchDecided) {
                    val dx = abs(ev.x - startX)
                    val dy = abs(ev.y - startY)
                    if (dx > dy && dx > 8f) {
                        isScrollingHorizontally = true
                        touchDecided = true
                        // We're scrolling horizontally — prevent parent from taking over
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (dy > dx && dy > 8f) {
                        isScrollingHorizontally = false
                        touchDecided = true
                        // We're scrolling vertically — let parent handle it
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                } else if (!isScrollingHorizontally) {
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScrollingHorizontally = false
                touchDecided = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return if (isScrollingHorizontally) super.onInterceptTouchEvent(ev) else false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_MOVE -> {
                if (!isScrollingHorizontally) {
                    // Pass vertical scroll back up to parent
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScrollingHorizontally = false
                touchDecided = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(ev)
    }
}

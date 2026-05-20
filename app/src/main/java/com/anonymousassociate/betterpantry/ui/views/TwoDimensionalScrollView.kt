package com.anonymousassociate.betterpantry.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TwoDimensionalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val scroller = OverScroller(context)
    private var touchSlop = 0
    private var minimumVelocity = 0f
    private var maximumVelocity = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isBeingDragged = false
    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = -1

    private var maxHeight = 0

    init {
        val configuration = ViewConfiguration.get(context)
        touchSlop = configuration.scaledTouchSlop
        minimumVelocity = configuration.scaledMinimumFlingVelocity.toFloat()
        maximumVelocity = configuration.scaledMaximumFlingVelocity.toFloat()

        val displayMetrics = context.resources.displayMetrics
        maxHeight = (displayMetrics.heightPixels * 0.85).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var heightSpec = heightMeasureSpec
        if (maxHeight > 0) {
            heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        }
        
        val childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        
        var maxChildWidth = 0
        var maxChildHeight = 0
        
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                measureChild(child, childWidthMeasureSpec, childHeightMeasureSpec)
                maxChildWidth = max(maxChildWidth, child.measuredWidth)
                maxChildHeight = max(maxChildHeight, child.measuredHeight)
            }
        }

        val resolvedWidth = resolveSize(maxChildWidth, widthMeasureSpec)
        val resolvedHeight = resolveSize(maxChildHeight, heightSpec)
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked

        if (action == MotionEvent.ACTION_MOVE && isBeingDragged) {
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = ev.x
                lastTouchY = ev.y
                activePointerId = ev.getPointerId(0)
                initOrResetVelocityTracker()
                velocityTracker?.addMovement(ev)
                isBeingDragged = !scroller.isFinished
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = ev.findPointerIndex(activePointerId)
                if (pointerIndex != -1) {
                    val x = ev.getX(pointerIndex)
                    val y = ev.getY(pointerIndex)
                    val dx = abs(x - lastTouchX)
                    val dy = abs(y - lastTouchY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isBeingDragged = true
                        lastTouchX = x
                        lastTouchY = y
                        initVelocityTrackerIfNotExists()
                        velocityTracker?.addMovement(ev)
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isBeingDragged = false
                activePointerId = -1
                recycleVelocityTracker()
            }
        }

        return isBeingDragged
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        initVelocityTrackerIfNotExists()
        velocityTracker?.addMovement(ev)

        val action = ev.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (childCount == 0) return false
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                lastTouchX = ev.x
                lastTouchY = ev.y
                activePointerId = ev.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = ev.findPointerIndex(activePointerId)
                if (pointerIndex != -1) {
                    val x = ev.getX(pointerIndex)
                    val y = ev.getY(pointerIndex)
                    var deltaX = (lastTouchX - x).toInt()
                    var deltaY = (lastTouchY - y).toInt()

                    if (!isBeingDragged && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        isBeingDragged = true
                        if (deltaX > 0) deltaX -= touchSlop else if (deltaX < 0) deltaX += touchSlop
                        if (deltaY > 0) deltaY -= touchSlop else if (deltaY < 0) deltaY += touchSlop
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isBeingDragged) {
                        lastTouchX = x
                        lastTouchY = y

                        val maxScrollX = getScrollRangeX()
                        val maxScrollY = getScrollRangeY()

                        val newScrollX = max(0, min(scrollX + deltaX, maxScrollX))
                        val newScrollY = max(0, min(scrollY + deltaY, maxScrollY))

                        scrollTo(newScrollX, newScrollY)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isBeingDragged) {
                    velocityTracker?.computeCurrentVelocity(1000, maximumVelocity)
                    val initialVelocityX = velocityTracker?.getXVelocity(activePointerId) ?: 0f
                    val initialVelocityY = velocityTracker?.getYVelocity(activePointerId) ?: 0f

                    if (abs(initialVelocityX) > minimumVelocity || abs(initialVelocityY) > minimumVelocity) {
                        fling(-initialVelocityX.toInt(), -initialVelocityY.toInt())
                    }
                    activePointerId = -1
                    endDrag()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isBeingDragged && childCount > 0) {
                    activePointerId = -1
                    endDrag()
                }
            }
        }
        return true
    }

    private fun getScrollRangeX(): Int {
        if (childCount > 0) {
            val child = getChildAt(0)
            return max(0, child.width - width)
        }
        return 0
    }

    private fun getScrollRangeY(): Int {
        if (childCount > 0) {
            val child = getChildAt(0)
            return max(0, child.height - height)
        }
        return 0
    }

    private fun endDrag() {
        isBeingDragged = false
        recycleVelocityTracker()
    }

    private fun fling(velocityX: Int, velocityY: Int) {
        if (childCount > 0) {
            scroller.fling(
                scrollX, scrollY,
                velocityX, velocityY,
                0, getScrollRangeX(),
                0, getScrollRangeY()
            )
            postInvalidateOnAnimation()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val oldX = scrollX
            val oldY = scrollY
            val x = scroller.currX
            val y = scroller.currY

            if (oldX != x || oldY != y) {
                scrollTo(x, y)
            }
            postInvalidateOnAnimation()
        }
    }

    private fun initOrResetVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        } else {
            velocityTracker?.clear()
        }
    }

    private fun initVelocityTrackerIfNotExists() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}

package com.anonymousassociate.betterpantry.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private var maxHeight = 0

    init {
        // Default max height is 85% of screen height
        val displayMetrics = context.resources.displayMetrics
        maxHeight = (displayMetrics.heightPixels * 0.85).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var measuredSpec = heightMeasureSpec
        if (maxHeight > 0) {
            measuredSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, measuredSpec)
    }
}

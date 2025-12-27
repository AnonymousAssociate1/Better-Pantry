package com.anonymousassociate.betterpantry.ui

import android.content.Intent
import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.anonymousassociate.betterpantry.R

object LinkUtil {
    fun makeLinksClickable(textView: TextView) {
        val greenColor = ContextCompat.getColor(textView.context, R.color.work_day_green)
        textView.setLinkTextColor(greenColor)

        var text = textView.text
        if (text !is Spannable) {
            text = android.text.SpannableString(text)
            textView.text = text
        }
        val spannable = text as Spannable

        // Replace URLSpans with our custom clickable spans for styling and in-app browser
        val spans = spannable.getSpans(0, text.length, URLSpan::class.java)
        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            
            val newSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    try {
                        val context = widget.context
                        if (context is com.anonymousassociate.betterpantry.MainActivity) {
                            context.openBrowser(span.url)
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(span.url))
                            widget.context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = greenColor
                    ds.isUnderlineText = true
                }
            }
            
            spannable.removeSpan(span)
            spannable.setSpan(newSpan, start, end, flags)
        }

        // Set a custom touch listener to handle clicks without blocking the parent
        textView.setOnTouchListener { v, event ->
            val widget = v as TextView
            val action = event.action

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                var x = event.x.toInt()
                var y = event.y.toInt()

                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop
                x += widget.scrollX
                y += widget.scrollY

                val layout = widget.layout
                if (layout != null) {
                    val line = layout.getLineForVertical(y)
                    val off = layout.getOffsetForHorizontal(line, x.toFloat())

                    val links = spannable.getSpans(off, off, ClickableSpan::class.java)

                    if (links.isNotEmpty()) {
                        val link = links[0]
                        if (action == MotionEvent.ACTION_UP) {
                            link.onClick(widget)
                        } else { // ACTION_DOWN
                            Selection.setSelection(spannable, spannable.getSpanStart(link), spannable.getSpanEnd(link))
                        }
                        return@setOnTouchListener true // Link interacted with, consume event
                    } else {
                        Selection.removeSelection(spannable)
                    }
                }
            }
            return@setOnTouchListener false // No link, pass event to parent
        }
    }
}

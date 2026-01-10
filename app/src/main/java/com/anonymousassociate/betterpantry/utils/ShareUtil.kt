package com.anonymousassociate.betterpantry.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object ShareUtil {

    fun shareView(context: Context, view: View, title: String, headerText: String? = null, subHeaderText: String? = null) {
        val originalBitmap = getBitmapFromView(view)
        val finalBitmap = if (headerText != null || subHeaderText != null) {
            addHeaderToBitmap(context, originalBitmap, headerText, subHeaderText)
        } else {
            originalBitmap
        }
        
        val uri = saveBitmap(context, finalBitmap)
        if (uri != null) {
            shareImage(context, uri, title)
        }
    }

    private fun getBitmapFromView(view: View): Bitmap {
        // Measure and layout if not already done (though usually it is if on screen)
        // If view is not attached to window, we might need manual measure/layout.
        // For buttons inside existing layouts, view is likely the parent card or a specific container.
        
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgDrawable = view.background
        if (bgDrawable != null) {
            bgDrawable.draw(canvas)
        } else {
            val typedValue = android.util.TypedValue()
            val theme = view.context.theme
            if (theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
                canvas.drawColor(typedValue.data)
            } else {
                canvas.drawColor(Color.WHITE)
            }
        }
        view.draw(canvas)
        return bitmap
    }

    private fun addHeaderToBitmap(context: Context, original: Bitmap, header: String?, subHeader: String?): Bitmap {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        
        // Resolve Colors
        val typedValue = android.util.TypedValue()
        val theme = context.theme
        
        val textColorPrimary = if (theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)) {
            // Check if it's a resource or a color value
            if (typedValue.resourceId != 0) {
                context.getColor(typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else Color.BLACK

        val textColorSecondary = if (theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)) {
             if (typedValue.resourceId != 0) {
                context.getColor(typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else Color.DKGRAY

        val backgroundColor = if (theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
             if (typedValue.resourceId != 0) {
                context.getColor(typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else Color.WHITE

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColorPrimary
            textSize = 16 * context.resources.displayMetrics.density // 16sp
            typeface = Typeface.DEFAULT_BOLD
        }
        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColorSecondary
            textSize = 14 * context.resources.displayMetrics.density // 14sp
            typeface = Typeface.DEFAULT
        }

        var headerHeight = padding
        if (header != null) {
            headerHeight += textPaint.fontSpacing.toInt() + (4 * context.resources.displayMetrics.density).toInt()
        }
        if (subHeader != null) {
            headerHeight += subTextPaint.fontSpacing.toInt() + (4 * context.resources.displayMetrics.density).toInt()
        }
        headerHeight += padding // Bottom padding

        val newBitmap = Bitmap.createBitmap(original.width, original.height + headerHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(backgroundColor)

        var y = padding.toFloat() + textPaint.fontSpacing
        if (header != null) {
            canvas.drawText(header, padding.toFloat(), y, textPaint)
            y += textPaint.fontSpacing + (4 * context.resources.displayMetrics.density)
        }
        if (subHeader != null) {
            // Re-measure for subheader baseline if needed, but fontSpacing logic is simple
            canvas.drawText(subHeader, padding.toFloat(), y, subTextPaint)
        }

        canvas.drawBitmap(original, 0f, headerHeight.toFloat(), null)
        return newBitmap
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap): Uri? {
        val imagesFolder = File(context.cacheDir, "images")
        if (!imagesFolder.exists()) {
            imagesFolder.mkdirs()
        }
        val file = File(imagesFolder, "shared_schedule_${System.currentTimeMillis()}.png")
        try {
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            stream.flush()
            stream.close()
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun shareImage(context: Context, uri: Uri, title: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "image/png"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = android.content.ClipData.newRawUri(title, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title))
    }
}

package com.anonymousassociate.betterpantry.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.anonymousassociate.betterpantry.R

object WidgetUpdater {
    fun updateAllWidgets(context: Context) {
        // Sync next shift schedule and coworker data to Wear OS devices
        WearDataSyncManager.syncNextShift(context)

        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Update Next Shift Widget
        val nextShiftComponentName = ComponentName(context, NextShiftWidgetProvider::class.java)
        val nextShiftWidgetIds = appWidgetManager.getAppWidgetIds(nextShiftComponentName)
        if (nextShiftWidgetIds.isNotEmpty()) {
            appWidgetManager.notifyAppWidgetViewDataChanged(nextShiftWidgetIds, R.id.nextShiftListView)
            val intent = Intent(context, NextShiftWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, nextShiftWidgetIds)
            }
            context.sendBroadcast(intent)
        }
    }
}

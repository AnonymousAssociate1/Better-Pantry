package com.anonymousassociate.betterpantry.wear

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson

class WearDataListenerService : WearableListenerService() {
    private val gson = Gson()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged triggered on watch")
        val cache = WearScheduleCache(applicationContext)

        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                if (path == "/next_shift_data") {
                    try {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val payloadJson = dataMap.getString("payload_json")
                        
                        if (payloadJson != null) {
                            Log.d(TAG, "Received payload: $payloadJson")
                            val syncData = gson.fromJson(payloadJson, WearScheduleCache.NextShiftSyncData::class.java)
                            
                            // Save to local watch cache
                            cache.saveNextShiftData(syncData)
                            Log.d(TAG, "Saved next shift data to WearScheduleCache")

                            // Request Tile refresh
                            TileService.getUpdater(applicationContext).requestUpdate(NextShiftTileService::class.java)
                            Log.d(TAG, "Requested NextShiftTileService update")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing incoming data event", e)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "WearDataListenerService"
    }
}

package com.anonymousassociate.betterpantry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.text.HtmlCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.anonymousassociate.betterpantry.models.NotificationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        checkAndSendNewNotifications(applicationContext)
        return Result.success()
    }

    companion object {
        fun checkAndSendNewNotifications(context: Context) {
            // This runs on a background thread to not block the caller
            GlobalScope.launch(Dispatchers.IO) {
                val authManager = AuthManager(context)
                val apiService = PantryApiService(authManager)
                val scheduleCache = ScheduleCache(context)

                if (!authManager.isTokenValid()) {
                    if (!authManager.refreshToken()) {
                        return@launch // Can't proceed
                    }
                }

                try {
                    val response = apiService.getNotifications(size = 20)
                    val fetchedNotifications = response?.content ?: emptyList()

                    if (fetchedNotifications.isNotEmpty()) {
                        val cachedNotifications = scheduleCache.getCachedNotifications() ?: emptyList()
                        val cachedIds = cachedNotifications.mapNotNull { it.notificationId }.toSet()

                        val newNotifications = fetchedNotifications.filter { it.notificationId !in cachedIds }

                        newNotifications.forEach { notification ->
                            if (notification.read != true) {
                                sendNotification(context, notification, apiService)
                            }
                        }

                        // Update cache with the latest fetched list
                        scheduleCache.saveNotifications(fetchedNotifications)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

                internal suspend fun sendNotification(context: Context, notification: NotificationData, apiService: PantryApiService? = null) {

            val channelId = "pantry_notifications"
            val notificationId = notification.notificationId.hashCode()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "Pantry Notifications"
                val descriptionText = "Notifications for new shifts and messages"
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(channelId, name, importance).apply {
                    description = descriptionText
                }
                val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }

            var bodyText = HtmlCompat.fromHtml(notification.message ?: "", HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            if (bodyText.isEmpty()) bodyText = notification.subject ?: "New Notification"

            try {
                val appDataStr = notification.appData
                if (!appDataStr.isNullOrEmpty()) {
                    var json: JSONObject? = null
                    try {
                        val value = JSONTokener(appDataStr).nextValue()
                        if (value is JSONObject) {
                            json = value
                        } else if (value is String) {
                            val innerValue = JSONTokener(value).nextValue()
                            if (innerValue is JSONObject) {
                                json = innerValue
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (json != null) {
                        val eventType = json.optString("eventType")
                        var handled = false

                        if (eventType == "POST_APPROVED_EVENT" && apiService != null) {
                            try {
                                val initiatorShift = json.optJSONObject("initiatorShift")
                                val initiator = json.optJSONObject("initiatingAssociate")
                                val shiftId = initiatorShift?.optString("shiftId") ?: initiatorShift?.optLong("shiftId")?.toString()

                                if (shiftId != null) {
                                    val schedule = apiService.getSchedule(30)
                                    val trackItem = schedule?.track?.find { it.primaryShiftRequest?.shift?.shiftId == shiftId }
                                    val shift = trackItem?.primaryShiftRequest?.shift

                                    if (shift != null) {
                                        val firstName = initiator?.optString("firstName") ?: ""
                                        val lastName = initiator?.optString("lastName") ?: ""
                                        val name = "$firstName $lastName".trim().ifEmpty { "Someone" }

                                        val timeFormat = DateTimeFormatter.ofPattern("h:mma", Locale.US)
                                        val dateFormat = DateTimeFormatter.ofPattern("M/d", Locale.US)

                                        val start = LocalDateTime.parse(shift.startDateTime)
                                        val end = LocalDateTime.parse(shift.endDateTime)

                                        val sTime = start.format(timeFormat)
                                        val eTime = end.format(timeFormat)
                                        val dateStr = start.format(dateFormat)

                                        val workstationName = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)

                                        bodyText = "Your shift request for $name's $workstationName shift from $sTime - $eTime on $dateStr was approved"
                                        handled = true
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        if (!handled && eventType == "CALLFORHELP_INITIATED_EVENT") {
                            try {
                                val initiatorShift = json.optJSONObject("initiatorShift")
                                if (initiatorShift != null) {
                                    val shiftId = initiatorShift.optString("shiftId") ?: initiatorShift.optLong("shiftId").toString()
                                    var workstationName = "Shift"
                                    
                                    // Try to get workstation name from API
                                    if (apiService != null && shiftId.isNotEmpty()) {
                                        try {
                                            val schedule = apiService.getSchedule(30)
                                            val trackItem = schedule?.track?.find {
                                                it.primaryShiftRequest?.shift?.shiftId == shiftId
                                            }
                                            
                                            // Get Workstation Name
                                            val shift = trackItem?.primaryShiftRequest?.shift
                                            if (shift != null) {
                                                workstationName = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    val startStr = initiatorShift.optString("startDateTime")
                                    val endStr = initiatorShift.optString("endDateTime")
                                    
                                    if (startStr.isNotEmpty() && endStr.isNotEmpty()) {
                                        val timeFormat = DateTimeFormatter.ofPattern("h:mma", Locale.US)
                                        val dateFormat = DateTimeFormatter.ofPattern("M/d", Locale.US)
                                        
                                        val startDate = LocalDateTime.parse(startStr)
                                        val endDate = LocalDateTime.parse(endStr)
                                        
                                        val sTime = startDate.format(timeFormat)
                                        val eTime = endDate.format(timeFormat)
                                        val date = startDate.format(dateFormat)
                                        
                                        bodyText = "Manager is calling for help for the $workstationName shift from $sTime - $eTime on $date"
                                        handled = true
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        if (!handled && eventType == "POST_INITIATED_EVENT" && apiService != null) {
                            try {

                                        val initiatorShift = json.optJSONObject("initiatorShift")

                                        val shiftId = initiatorShift?.optString("shiftId") ?: initiatorShift?.optLong("shiftId")?.toString()

        

                                        if (shiftId != null) {

                                            val schedule = apiService.getSchedule(30)

                                            val trackItem = schedule?.track?.find {

                                                it.primaryShiftRequest?.shift?.shiftId == shiftId

                                            }

                                            val shift = trackItem?.primaryShiftRequest?.shift

        

                                            if (shift != null && shift.startDateTime != null && shift.endDateTime != null) {

                                                val initiator = json.optJSONObject("initiatingAssociate") ?: json.optJSONObject("initiator")

                                                val firstName = initiator?.optString("firstName") ?: ""

                                                val lastName = initiator?.optString("lastName") ?: ""

                                                val name = "$firstName $lastName".trim().ifEmpty { "Someone" }

        

                                                val timeFormat = DateTimeFormatter.ofPattern("h:mma", Locale.US)

                                                val dateFormat = DateTimeFormatter.ofPattern("M/d", Locale.US)

        

                                                val start = LocalDateTime.parse(shift.startDateTime)

                                                val end = LocalDateTime.parse(shift.endDateTime)

        

                                                val sTime = start.format(timeFormat)

                                                val eTime = end.format(timeFormat)

                                                val dateStr = start.format(dateFormat)

        

                                                val workstationName = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)

        

                                                bodyText = "$name's $workstationName shift from $sTime - $eTime on $dateStr is available for pickup"

                                                handled = true

                                            }

                                        }

                                    } catch (e: Exception) {

                                        e.printStackTrace()

                                    }

                                }

        

                                if (!handled) {

                                    val initiatorShift = json.optJSONObject("initiatorShift") ?: json.optJSONObject("initiatingAssociate") // Fallback? No, logic depends on specific fields

        

                                    if (initiatorShift != null) {

                                        val startStr = initiatorShift.optString("startDateTime")

                                        val endStr = initiatorShift.optString("endDateTime")

                                        val cafeId = initiatorShift.optString("cafeNumber")

                                        val workstationName = initiatorShift.optString("workstationName")

        

                                        if (startStr.isNotEmpty() && endStr.isNotEmpty()) {

                                            val timeFormat = DateTimeFormatter.ofPattern("h:mma", Locale.US)

                                            val dateFormat = DateTimeFormatter.ofPattern("M/d", Locale.US)

        

                                            fun parseDate(dateStr: String): LocalDateTime? {

                                                return try {

                                                    LocalDateTime.parse(dateStr)

                                                } catch (e: Exception) {

                                                    try {

                                                        Instant.parse(dateStr).atZone(ZoneId.systemDefault()).toLocalDateTime()

                                                    } catch (e2: Exception) {

                                                        try {

                                                            LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME)

                                                        } catch (e3: Exception) {

                                                            null

                                                        }

                                                    }

                                                }

                                            }

        

                                            val startDate = parseDate(startStr)

                                            val endDate = parseDate(endStr)

        

                                            if (startDate != null && endDate != null) {

                                                val sTime = startDate.format(timeFormat)

                                                val eTime = endDate.format(timeFormat)

                                                val date = startDate.format(dateFormat)

                                                                                        val subject = notification.subject ?: ""

                                                                                        if (subject.contains("Manager call for help", ignoreCase = true)) {

                                                                                            bodyText = "Manager is calling for help for the $workstationName shift from $sTime - $eTime on $date"

                                                                                        } else if (subject.contains("Shift Available", ignoreCase = true)) {

                                                                                            var name = "Someone"

                                                                                            val initiator = json.optJSONObject("initiator") ?: json.optJSONObject("sender")

                                                                                            if (initiator != null) {

                                                                                                val first = initiator.optString("firstName")

                                                                                                val last = initiator.optString("lastName")

                                                                                                if (first.isNotEmpty()) {

                                                                                                    name = "$first $last".trim()

                                                                                                }

                                                                                            }

                                                                                            bodyText = "$name's $workstationName shift from $sTime - $eTime on $date is available for pickup"

                                                                                        }

                                            }

                                        }

                                    }

                                }

                            }

                        }

                                                            } catch (e: Exception) {

                                                                e.printStackTrace()

                                                                val rawMessage = notification.message ?: ""

                                                                bodyText = HtmlCompat.fromHtml(rawMessage, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

                                                            }

                                        

                                                            // If the original message contained a table, use only the text before it for the push notification.

                                                                                val rawMessage = notification.message ?: ""

                                                                                if (rawMessage.contains("<table", ignoreCase = true)) {

                                                                                    val tableIndex = rawMessage.indexOf("<table", ignoreCase = true)

                                                                                    var preText = rawMessage.substring(0, tableIndex)

                                                                                    // Also remove style blocks from the header

                                                                                    preText = preText.replace(Regex("<style.*?>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")

                                                                                    

                                                                                    val cleanText = HtmlCompat.fromHtml(preText, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

                                                                                    

                                                                                    bodyText = if (cleanText.isNotEmpty()) {

                                                                                        cleanText

                                                                                    } else {

                                                                                        notification.subject ?: "View details in the app"

                                                                                    }

                                                                                }

                                                

                                                            var timestamp = System.currentTimeMillis()

                                                            

                                                            if (!notification.createDateTime.isNullOrEmpty()) {

                                            try {

                                                timestamp = Instant.parse(notification.createDateTime).toEpochMilli()

                                            } catch (e: Exception) {

                                                try {

                                                    timestamp = LocalDateTime.parse(notification.createDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                                                } catch (e2: Exception) {

                                                    // Keep default

                                                }

                                            }

                                        }

                            

                                        val intent = Intent(context, MainActivity::class.java).apply {

                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

                                            putExtra("open_notifications_tab", true)

                                            val gson = Gson()

                                            putExtra("notification_data", gson.toJson(notification))

                                        }

        

                                        

        

                                        val pendingIntent: PendingIntent = PendingIntent.getActivity(

        

                                            context, 

        

                                            notificationId, 

        

                                            intent, 

        

                                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        

                                        )

        

                            

        

                                        val builder = NotificationCompat.Builder(context, channelId)

        

                                            .setSmallIcon(R.drawable.ic_notification_custom)

        

                                            .setContentTitle(notification.subject ?: "New Notification")

        

                                            .setContentText(bodyText)

        

                                            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))

        

                                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        

                                            .setContentIntent(pendingIntent)

        

                                            .setAutoCancel(true)

        

                                            .setWhen(timestamp)

        

                                            .setShowWhen(true)

        

                    try {

                        if (ActivityCompat.checkSelfPermission(

                                context,

                                Manifest.permission.POST_NOTIFICATIONS

                            ) == PackageManager.PERMISSION_GRANTED

                        ) {

                            NotificationManagerCompat.from(context).notify(notificationId, builder.build())

                        }

                    } catch (e: SecurityException) {

                        e.printStackTrace()

                    }

                }

        

                private fun getWorkstationDisplayName(workstationId: String?, fallbackName: String?): String {

                    val customNames = mapOf(

                        "QC_2" to "QC 2",

                        "1ST_CASHIER_1" to "Cashier 1",

                        "SANDWICH_2" to "Sandwich 2",

                        "SANDWICH_1" to "Sandwich 1",

                        "SALAD_1" to "Salad 1",

                        "SALAD_2" to "Salad 2",

                        "DTORDERTAKER_1" to "DriveThru",

                        "1ST_DR_1" to "Dining Room",

                        "1st_Cashier" to "Cashier 1",

                        "1st_Dr" to "Dining Room",

                        "DtOrderTaker" to "DriveThru",

                        "Sandwich_1" to "Sandwich 1",

                        "Sandwich_2" to "Sandwich 2",

                        "Qc_2" to "QC 2",

                        "1ST_SANDWICH_1" to "Sandwich 1",

                        "Bake" to "Baker",

                        "BAKER" to "Baker",

                        "SALAD" to "Salad 1",

                        "SANDWICH" to "Sandwich 1",

                        "1ST_CASHIER" to "Cashier 1",

                        "QC_1" to "QC 1",

                        "QC_2" to "QC 2",

                        "DTORDERTAKER" to "DriveThru",

                        "1ST_DR" to "Dining Room",

                        "MANAGER_1" to "Manager",

                        "MANAGER" to "Manager",

                        "MANAGERADMIN_1" to "Manager",

                        "MANAGERADMIN" to "Manager",

                                                "PEOPLEMANAGEMENT_1" to "Manager",

                                                "PEOPLEMANAGEMENT" to "Manager",

                                                "LABOR_MANAGEMENT" to "Manager",

                                                "LABORMANAGEMENT" to "Manager",

                                                "Labor Management" to "Manager"

                                            )

                    if (workstationId != null) {

                        val mapped = customNames[workstationId]

                        if (mapped != null) return mapped

                    }

                    return fallbackName ?: workstationId ?: "Unknown"

                }
    }
}

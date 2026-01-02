package com.anonymousassociate.betterpantry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class PaycheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = NotificationPreferences(applicationContext)
        if (!prefs.paycheckEnabled) return Result.success()

        val config = prefs.paycheckConfig ?: return Result.success()
        val today = LocalDate.now()
        var isPayday = false

        try {
            when (config.frequencyType) {
                "WEEKLY" -> {
                    val start = LocalDate.parse(config.startDate)
                    val daysBetween = ChronoUnit.DAYS.between(start, today)
                    if (daysBetween >= 0 && daysBetween % 7 == 0L) isPayday = true
                }
                "BIWEEKLY" -> {
                    val start = LocalDate.parse(config.startDate)
                    val daysBetween = ChronoUnit.DAYS.between(start, today)
                    if (daysBetween >= 0 && daysBetween % 14 == 0L) isPayday = true
                }
                "SEMIMONTHLY", "SEMIMONTHLY_DATE" -> {
                    val d1 = config.dayOfMonth1 ?: 15
                    val d2 = config.dayOfMonth2 ?: 30
                    if (today.dayOfMonth == d1 || today.dayOfMonth == d2) isPayday = true
                    
                    // Handle end of month logic for 30th/31st if needed (simplified here)
                    if (d2 >= 30 && today.lengthOfMonth() < d2 && today.dayOfMonth == today.lengthOfMonth()) isPayday = true
                }
                "MONTHLY", "MONTHLY_DATE" -> {
                    val d1 = config.dayOfMonth1 ?: 1
                    if (today.dayOfMonth == d1) isPayday = true
                }
                "SEMIMONTHLY_DAY" -> {
                    if (isDayOfWeekMatch(today, config.weekIndex1, config.dayOfWeek) || 
                        isDayOfWeekMatch(today, config.weekIndex2, config.dayOfWeek)) {
                        isPayday = true
                    }
                }
                "MONTHLY_DAY" -> {
                    if (isDayOfWeekMatch(today, config.weekIndex1, config.dayOfWeek)) {
                        isPayday = true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (isPayday) {
            sendPaycheckNotification(applicationContext)
        }

        return Result.success()
    }

    private fun isDayOfWeekMatch(date: LocalDate, weekIndex: Int?, targetDayOfWeek: Int?): Boolean {
        if (weekIndex == null || targetDayOfWeek == null) return false
        
        // targetDayOfWeek: 1=Mon, 7=Sun
        if (date.dayOfWeek.value != targetDayOfWeek) return false
        
        // Check if it's the Nth occurrence
        // weekIndex: 1=1st, 2=2nd, 3=3rd, 4=4th, -1=Last
        
        if (weekIndex == -1) {
            // Check if it's the last occurrence
            val nextWeek = date.plusWeeks(1)
            return nextWeek.month != date.month
        } else {
            // Calculate which occurrence this is
            // (dayOfMonth - 1) / 7 + 1
            val occurrence = (date.dayOfMonth - 1) / 7 + 1
            return occurrence == weekIndex
        }
    }

    private fun sendPaycheckNotification(context: Context) {
        val channelId = "pantry_paycheck"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Paycheck Reminders"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_custom)
            .setContentTitle("Paycheck Reminder")
            .setContentText("It's payday!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
        try {
             notificationManager.notify(9999, builder.build())
        } catch (e: SecurityException) {}
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PaycheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "paycheck_worker",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        private fun calculateInitialDelay(): Long {
            // Target running at e.g., 9 AM
            val now = java.time.LocalDateTime.now()
            var target = now.withHour(9).withMinute(0).withSecond(0)
            if (now.isAfter(target)) {
                target = target.plusDays(1)
            }
            return java.time.Duration.between(now, target).toMillis()
        }
    }
}

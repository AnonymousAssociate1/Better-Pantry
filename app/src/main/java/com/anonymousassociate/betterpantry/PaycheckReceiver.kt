package com.anonymousassociate.betterpantry

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class PaycheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        
        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            checkAndSchedulePaycheck(context)
        } else if ("com.anonymousassociate.betterpantry.CHECK_PAYDAY" == action) {
            checkAndSchedulePaycheck(context)
        }
    }

    companion object {
        fun checkAndSchedulePaycheck(context: Context) {
            val prefs = NotificationPreferences(context)
            
            // Cancel old WorkManager task if it is still registered
            try {
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("paycheck_worker")
            } catch (e: Exception) {}

            if (!prefs.paycheckEnabled) {
                cancelAlarm(context)
                return
            }

            val config = prefs.paycheckConfig ?: return
            val hour = config.hour ?: 9
            val minute = config.minute ?: 0

            val now = LocalDateTime.now()
            val today = LocalDate.now()
            val targetTime = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

            // 1. Run Check if needed
            val lastCheckStr = prefs.lastPaycheckCheckDate
            val alreadyCheckedToday = lastCheckStr == today.toString()

            if (!alreadyCheckedToday && now.isAfter(targetTime.minusMinutes(1))) {
                // It is past or exactly the scheduled time, and we haven't checked today yet.
                val isPayday = isTodayPayday(config, today)
                if (isPayday) {
                    sendPaycheckNotification(context)
                }
                prefs.lastPaycheckCheckDate = today.toString()
            }

            // 2. Schedule Next Alarm
            scheduleAlarm(context, hour, minute)
        }

        private fun isTodayPayday(config: PaycheckConfig, today: LocalDate): Boolean {
            try {
                when (config.frequencyType) {
                    "WEEKLY" -> {
                        val start = LocalDate.parse(config.startDate)
                        val daysBetween = ChronoUnit.DAYS.between(start, today)
                        if (daysBetween >= 0 && daysBetween % 7 == 0L) return true
                    }
                    "BIWEEKLY" -> {
                        val start = LocalDate.parse(config.startDate)
                        val daysBetween = ChronoUnit.DAYS.between(start, today)
                        if (daysBetween >= 0 && daysBetween % 14 == 0L) return true
                    }
                    "SEMIMONTHLY", "SEMIMONTHLY_DATE" -> {
                        val d1 = config.dayOfMonth1 ?: 15
                        val d2 = config.dayOfMonth2 ?: 30
                        if (today.dayOfMonth == d1 || today.dayOfMonth == d2) return true
                        if (d2 >= 30 && today.lengthOfMonth() < d2 && today.dayOfMonth == today.lengthOfMonth()) return true
                    }
                    "MONTHLY", "MONTHLY_DATE" -> {
                        val d1 = config.dayOfMonth1 ?: 1
                        if (today.dayOfMonth == d1) return true
                    }
                    "SEMIMONTHLY_DAY" -> {
                        if (isDayOfWeekMatch(today, config.weekIndex1, config.dayOfWeek) || 
                            isDayOfWeekMatch(today, config.weekIndex2, config.dayOfWeek)) {
                            return true
                        }
                    }
                    "MONTHLY_DAY" -> {
                        if (isDayOfWeekMatch(today, config.weekIndex1, config.dayOfWeek)) {
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        private fun isDayOfWeekMatch(date: LocalDate, weekIndex: Int?, targetDayOfWeek: Int?): Boolean {
            if (weekIndex == null || targetDayOfWeek == null) return false
            if (date.dayOfWeek.value != targetDayOfWeek) return false
            if (weekIndex == -1) {
                val nextWeek = date.plusWeeks(1)
                return nextWeek.month != date.month
            } else {
                val occurrence = (date.dayOfMonth - 1) / 7 + 1
                return occurrence == weekIndex
            }
        }

        private fun sendPaycheckNotification(context: Context) {
            val channelId = "pantry_paycheck"
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "Paycheck Reminders"
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(channelId, name, importance)
                notificationManager.createNotificationChannel(channel)
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_custom)
                .setContentTitle("Paycheck Reminder")
                .setContentText("It's payday!")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            try {
                notificationManager.notify(9999, builder.build())
            } catch (e: SecurityException) {}
        }

        private fun scheduleAlarm(context: Context, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = Intent(context, PaycheckReceiver::class.java).apply {
                action = "com.anonymousassociate.betterpantry.CHECK_PAYDAY"
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val now = LocalDateTime.now()
            var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (now.isAfter(target)) {
                target = target.plusDays(1)
            }

            val triggerAtMs = target.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }
        }

        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PaycheckReceiver::class.java).apply {
                action = "com.anonymousassociate.betterpantry.CHECK_PAYDAY"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }
}

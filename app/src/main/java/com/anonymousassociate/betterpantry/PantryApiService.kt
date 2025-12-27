package com.anonymousassociate.betterpantry

import com.google.gson.Gson
import com.anonymousassociate.betterpantry.models.ScheduleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.google.gson.reflect.TypeToken
import com.anonymousassociate.betterpantry.models.TeamMember

import com.anonymousassociate.betterpantry.models.NotificationSummary
import com.anonymousassociate.betterpantry.models.NotificationResponse

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class PantryApiService(private val authManager: AuthManager) {

    private val client = OkHttpClient()
    private val gson = Gson()

    suspend fun getSchedule(daysForward: Int = 30): ScheduleData? = withContext(Dispatchers.IO) {
        try {
            val userId = authManager.getUserId() ?: return@withContext null
            val token = authManager.getAccessToken() ?: return@withContext null

            val start = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            val end = LocalDate.now().plusDays(daysForward.toLong()).format(DateTimeFormatter.ISO_DATE)

            val url = "https://pantry.panerabread.com/apis/selfservice-ui-service/v1/employees/$userId/self_service" +
                    "?shiftStartDate=$start&shiftEndDate=$end"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, ScheduleData::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getNotifications(page: Int = 0, size: Int = 50): NotificationResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext null
                val url = "https://pantry.panerabread.com/apis/pantry-ui-service/notification/v1/api/notifications/pin-level-ordered" +
                        "?page=$page&size=$size&includeDeleted=true"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    gson.fromJson(json, NotificationResponse::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun markNotificationAsRead(notificationId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext false
                val url = "https://pantry.panerabread.com/apis/pantry-ui-service/notification/v1/api/notifications/$notificationId/read"

                val body = "true".toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .put(body)
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun deleteNotification(notificationId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext false
                val url = "https://pantry.panerabread.com/apis/pantry-ui-service/notification/v1/api/notifications/$notificationId/delete"

                val body = "true".toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .put(body)
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun undeleteNotification(notificationId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext false
                val url = "https://pantry.panerabread.com/apis/pantry-ui-service/notification/v1/api/notifications/$notificationId/undelete"

                val body = "true".toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .put(body)
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun acceptShiftPickup(payload: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext false
                val url = "https://pantry.panerabread.com/apis/selfservice-ui-service/v1/shifts/available/accept"

                val body = payload.toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun declineShiftPickup(payload: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext false
                val url = "https://pantry.panerabread.com/apis/selfservice-ui-service/v1/shifts/available/decline"

                val body = payload.toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun postShift(payload: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext false
                val url = "https://pantry.panerabread.com/apis/selfservice-ui-service/v1/shifts/available"

                val body = payload.toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun cancelPostShift(payload: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext -1
                val url = "https://pantry.panerabread.com/apis/selfservice-ui-service/v1/shifts/available/cancel"

                println("DEBUG: Canceling Shift. URL: $url")
                println("DEBUG: Payload: $payload")

                val body = payload.toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .post(body) // The curl command implies a POST, not DELETE
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                println("DEBUG: Cancel Response Code: ${response.code}")
                println("DEBUG: Cancel Response Body: $responseBody")
                
                response.code
            } catch (e: Exception) {
                e.printStackTrace()
                -1
            }
        }
    }

    suspend fun getNotificationCount(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext 0
                val url = "https://pantry.panerabread.com/apis/pantry-ui-service/notification/v1/api/notifications/summary"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    val type = object : TypeToken<List<NotificationSummary>>() {}.type
                    val summaries: List<NotificationSummary>? = gson.fromJson(json, type)
                    summaries?.firstOrNull()?.count ?: 0
                } else {
                    0
                }
            } catch (e: Exception) {
                e.printStackTrace()
                0
            }
        }
    }

    suspend fun getTeamMembers(
        cafeNo: String,
        companyCode: String,
        startDateTime: String,
        endDateTime: String
    ): List<TeamMember>? {
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken() ?: return@withContext null
                val userId = authManager.getUserId() ?: return@withContext null

                val url = "https://pantry.panerabread.com/apis/selfservice-ui-service/v1/trade/associates" +
                        "?cafeNo=$cafeNo" +
                        "&companyCode=$companyCode" +
                        "&employeeId=$userId" +
                        "&startDate=$startDateTime" +
                        "&endDate=$endDateTime"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Pantry/2.0 Android")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    val type = object : TypeToken<List<TeamMember>>() {}.type
                    json?.let { gson.fromJson(it, type) }
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }


}

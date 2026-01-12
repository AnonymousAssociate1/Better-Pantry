package com.anonymousassociate.betterpantry

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

class AuthManager(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: android.content.SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // If initialization fails (e.g., KeyStore exception), clear the corrupted file and retry
            e.printStackTrace()
            context.getSharedPreferences("pantry_secure_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            // In extreme cases, we might need to delete the file itself if clearing isn't enough,
            // but usually clearing or deleting the key alias helps. 
            // However, EncryptedSharedPreferences manages its own keys.
            // Let's try to delete the file explicitly if we can't open it.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.deleteSharedPreferences("pantry_secure_prefs")
            } else {
                 context.getSharedPreferences("pantry_secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            }
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): android.content.SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "pantry_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val client = OkHttpClient()

    companion object {
        private const val TENANT_ID = "0493188b-1eac-4762-a256-3ef352a5581c"
        private const val CLIENT_ID = "9cb36c07-605f-4b6f-b1c0-f9def5f15416"
        private const val RESOURCE_ID = "96361792-1b63-4f77-a129-00cc3546bf73"
        private const val SCOPE = "openid offline_access profile api://$RESOURCE_ID/.default"
        private const val REDIRECT_URI = "pantry://login"

        private const val PREF_ACCESS_TOKEN = "access_token"
        private const val PREF_REFRESH_TOKEN = "refresh_token"
        private const val PREF_TOKEN_EXPIRY = "token_expiry"
        private const val PREF_USER_ID = "user_id"
        private const val PREF_FIRST_NAME = "first_name"
        private const val PREF_LAST_NAME = "last_name"
        private const val PREF_PREFERRED_NAME = "preferred_name"
        private const val PREF_CODE_VERIFIER = "code_verifier"
    }

    private var codeVerifier: String = ""

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        codeVerifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(PREF_CODE_VERIFIER, codeVerifier).apply()
        return codeVerifier
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun getAuthorizationUrl(): String {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()

        return Uri.parse("https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("audience", RESOURCE_ID)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    suspend fun exchangeCodeForToken(code: String): Boolean = withContext(Dispatchers.IO) {
        println("DEBUG: exchangeCodeForToken called with code length: ${code.length}")
        try {
            val verifier = prefs.getString(PREF_CODE_VERIFIER, "") ?: return@withContext false
            println("DEBUG: Code verifier found: ${verifier.isNotEmpty()}")

            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPE)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url("https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token")
                .post(formBody)
                .build()

            println("DEBUG: Sending token request...")
            val response = client.newCall(request).execute()
            println("DEBUG: Token response code: ${response.code}")

            if (response.isSuccessful) {
                val bodyString = response.body?.string() ?: ""
                println("DEBUG: Token response body length: ${bodyString.length}")
                val json = JSONObject(bodyString)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token")
                val expiresIn = json.getInt("expires_in")

                saveTokens(accessToken, refreshToken, expiresIn)
                extractUserInfoFromToken(accessToken)
                println("DEBUG: Token exchange successful")
                return@withContext true
            } else {
                 println("DEBUG: Token response error: ${response.body?.string()}")
            }

            false
        } catch (e: Exception) {
            println("DEBUG: Token exchange exception: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
        prefs.edit().apply {
            putString(PREF_ACCESS_TOKEN, accessToken)
            putString(PREF_REFRESH_TOKEN, refreshToken)
            putLong(PREF_TOKEN_EXPIRY, expiryTime)
            apply()
        }
    }

    private fun extractUserInfoFromToken(token: String) {
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING))
                val json = JSONObject(payload)
                
                val userId = json.optString("employeeid")
                    ?: json.optString("unique_name")
                    ?: json.optString("sub")
                
                val firstName = json.optString("firstname")
                val lastName = json.optString("lastname")
                val preferredName = json.optString("knownas")

                prefs.edit().apply {
                    putString(PREF_USER_ID, userId)
                    putString(PREF_FIRST_NAME, firstName)
                    putString(PREF_LAST_NAME, lastName)
                    putString(PREF_PREFERRED_NAME, preferredName)
                    apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isTokenValid(): Boolean {
        val accessToken = prefs.getString(PREF_ACCESS_TOKEN, null)
        val expiryTime = prefs.getLong(PREF_TOKEN_EXPIRY, 0L)
        return accessToken != null && System.currentTimeMillis() < expiryTime - 300000
    }

    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val refreshToken = prefs.getString(PREF_REFRESH_TOKEN, null) ?: return@withContext false

            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("scope", SCOPE)
                .build()

            val request = Request.Builder()
                .url("https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val accessToken = json.getString("access_token")
                val newRefreshToken = json.optString("refresh_token", refreshToken)
                val expiresIn = json.getInt("expires_in")

                saveTokens(accessToken, newRefreshToken, expiresIn)
                return@withContext true
            }

            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getAccessToken(): String? = prefs.getString(PREF_ACCESS_TOKEN, null)
    fun getUserId(): String? = prefs.getString(PREF_USER_ID, null)
    fun getFirstName(): String? {
        val preferred = prefs.getString(PREF_PREFERRED_NAME, null)
        return if (!preferred.isNullOrEmpty()) preferred else prefs.getString(PREF_FIRST_NAME, null)
    }
    fun getLastName(): String? = prefs.getString(PREF_LAST_NAME, null)
    fun getPreferredName(): String? = prefs.getString(PREF_PREFERRED_NAME, null)

    fun clearTokens() {
        prefs.edit().clear().apply()
    }
}

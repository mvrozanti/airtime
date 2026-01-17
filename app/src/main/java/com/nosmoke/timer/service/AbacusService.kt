package com.nosmoke.timer.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

object AbacusService {
    private const val BASE_URL = "https://abacus.jasoncameron.dev"
    private const val NAMESPACE = "airtime"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Track a lock event for a specific place
     */
    suspend fun trackLock(placeId: String) {
        trackCounter("${placeId}_locks")
    }

    /**
     * Get a value for a specific place
     * Key format: {placeId}_{key}
     */
    suspend fun getValue(placeId: String, key: String): Long? {
        val fullKey = "${placeId}_$key"
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/get/$NAMESPACE/$fullKey"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()?.trim()
                    if (body != null && body.isNotEmpty()) {
                        val value = body.toLongOrNull()
                        Log.d("AbacusService", "Retrieved $fullKey: $value")
                        value
                    } else {
                        Log.d("AbacusService", "No value found for $fullKey")
                        null
                    }
                } else {
                    Log.w("AbacusService", "Failed to get $fullKey: ${response.code}")
                    null
                }.also { response.close() }
            } catch (e: IOException) {
                Log.e("AbacusService", "Error getting $fullKey", e)
                null
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error getting $fullKey", e)
                null
            }
        }
    }

    /**
     * Set a value for a specific place
     * Key format: {placeId}_{key}
     */
    suspend fun setValue(placeId: String, key: String, value: Long) {
        val fullKey = "${placeId}_$key"
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/set/$NAMESPACE/$fullKey/$value"
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody("text/plain".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("AbacusService", "Stored $fullKey: $value")
                } else {
                    Log.w("AbacusService", "Failed to store $fullKey: ${response.code}")
                }
                response.close()
            } catch (e: IOException) {
                Log.e("AbacusService", "Error storing $fullKey", e)
                // Fail silently - don't break app if storing fails
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error storing $fullKey", e)
            }
        }
    }

    private suspend fun trackCounter(key: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/hit/$NAMESPACE/$key"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("AbacusService", "Tracked $key: ${response.body?.string()}")
                } else {
                    Log.w("AbacusService", "Failed to track $key: ${response.code}")
                }
                response.close()
            } catch (e: IOException) {
                Log.e("AbacusService", "Error tracking $key", e)
                // Fail silently - don't break app if tracking fails
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error tracking $key", e)
            }
        }
    }
}


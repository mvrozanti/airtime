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

    suspend fun trackLock() {
        trackCounter("locks")
    }

    suspend fun getValue(key: String): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/get/$NAMESPACE/$key"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()?.trim()
                    if (body != null && body.isNotEmpty()) {
                        val value = body.toLongOrNull()
                        Log.d("AbacusService", "Retrieved $key: $value")
                        value
                    } else {
                        Log.d("AbacusService", "No value found for $key")
                        null
                    }
                } else {
                    Log.w("AbacusService", "Failed to get $key: ${response.code}")
                    null
                }.also { response.close() }
            } catch (e: IOException) {
                Log.e("AbacusService", "Error getting $key", e)
                null
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error getting $key", e)
                null
            }
        }
    }

    suspend fun setValue(key: String, value: Long) {
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/set/$NAMESPACE/$key/$value"
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody("text/plain".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("AbacusService", "Stored $key: $value")
                } else {
                    Log.w("AbacusService", "Failed to store $key: ${response.code}")
                }
                response.close()
            } catch (e: IOException) {
                Log.e("AbacusService", "Error storing $key", e)
                // Fail silently - don't break app if storing fails
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error storing $key", e)
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


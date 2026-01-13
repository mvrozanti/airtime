package com.nosmoke.timer.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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


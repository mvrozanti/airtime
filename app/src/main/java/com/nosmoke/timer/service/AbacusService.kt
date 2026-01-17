package com.nosmoke.timer.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Headers.Companion.toHeaders
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AbacusService {
    private const val BASE_URL = "https://abacus.jasoncameron.dev"
    private const val NAMESPACE = "airtime"
    
    // Rate limiting: 30 requests per 10 seconds
    // We're conservative: allow 20 requests per 10 seconds (leaving room for polybar script)
    private val requestTimestamps = mutableListOf<Long>()
    private val rateLimitMutex = Mutex()
    private const val RATE_LIMIT_REQUESTS = 20  // Conservative limit
    private const val RATE_LIMIT_WINDOW_MS = 10_000L
    
    // Cache for GET requests - 30 second TTL
    // Key: full key (e.g., "Home_is_locked"), Value: Pair(value, timestamp)
    private val cache = ConcurrentHashMap<String, Pair<Long?, Long>>()
    private const val CACHE_TTL_MS = 30_000L  // 30 seconds
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    
    /**
     * Check rate limit and wait if necessary
     */
    private suspend fun checkRateLimit() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            requestTimestamps.removeAll { it < now - RATE_LIMIT_WINDOW_MS }
            
            if (requestTimestamps.size >= RATE_LIMIT_REQUESTS) {
                val oldestTimestamp = requestTimestamps.minOrNull() ?: now
                val waitTime = (oldestTimestamp + RATE_LIMIT_WINDOW_MS) - now
                if (waitTime > 0) {
                    Log.d("AbacusService", "Rate limit reached, waiting ${waitTime}ms")
                    kotlinx.coroutines.delay(waitTime)
                    requestTimestamps.removeAll { it < System.currentTimeMillis() - RATE_LIMIT_WINDOW_MS }
                }
            }
            requestTimestamps.add(System.currentTimeMillis())
        }
    }

    /**
     * Track a lock event for a specific place (fire and forget)
     */
    suspend fun trackLock(placeId: String) {
        trackCounter("${placeId}_locks")
    }

    /**
     * Get a value from cache or API
     * Uses 30-second cache to reduce API calls
     */
    suspend fun getValue(placeId: String, key: String): Long? {
        val fullKey = "${placeId}_$key"
        
        // Check cache first
        val cached = cache[fullKey]
        if (cached != null) {
            val (value, timestamp) = cached
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                Log.d("AbacusService", "Cache hit for $fullKey: $value")
                return value
            }
        }
        
        // Cache miss or expired - fetch from API
        checkRateLimit()
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/get/$NAMESPACE/$fullKey"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val value = if (response.isSuccessful) {
                    val body = response.body?.string()?.trim()
                    if (body != null && body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            json.optLong("value", -1).takeIf { it >= 0 }
                                ?: json.optLong("Value", -1).takeIf { it >= 0 }
                                ?: body.toLongOrNull()
                        } catch (e: Exception) {
                            body.toLongOrNull()
                        }
                    } else {
                        null
                    }
                } else {
                    Log.w("AbacusService", "Failed to get $fullKey: ${response.code}")
                    null
                }
                response.close()
                
                // Update cache
                cache[fullKey] = Pair(value, System.currentTimeMillis())
                
                if (value != null) {
                    Log.d("AbacusService", "Retrieved $fullKey: $value (cached)")
                }
                
                value
            } catch (e: IOException) {
                Log.e("AbacusService", "Error getting $fullKey", e)
                // Return cached value if API fails
                cache[fullKey]?.first
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error getting $fullKey", e)
                cache[fullKey]?.first
            }
        }
    }
    
    /**
     * Force refresh a value from API, bypassing cache
     */
    suspend fun getValueFresh(placeId: String, key: String): Long? {
        val fullKey = "${placeId}_$key"
        cache.remove(fullKey)  // Clear cache entry
        return getValue(placeId, key)
    }
    
    /**
     * Invalidate cache for a key (call after setValue)
     */
    fun invalidateCache(placeId: String, key: String) {
        val fullKey = "${placeId}_$key"
        cache.remove(fullKey)
        Log.d("AbacusService", "Invalidated cache for $fullKey")
    }
    
    /**
     * Update cache directly (for optimistic updates)
     */
    fun updateCache(placeId: String, key: String, value: Long) {
        val fullKey = "${placeId}_$key"
        cache[fullKey] = Pair(value, System.currentTimeMillis())
        Log.d("AbacusService", "Updated cache for $fullKey: $value")
    }

    /**
     * Create a counter and get its admin key
     */
    suspend fun createCounter(placeId: String, key: String): String? {
        val fullKey = "${placeId}_$key"
        
        checkRateLimit()
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/create/$NAMESPACE/$fullKey"
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody("text/plain".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()?.trim()
                    Log.d("AbacusService", "Create counter $fullKey response: $responseBody")
                    if (responseBody != null && responseBody.isNotEmpty()) {
                        val adminKey = try {
                            val json = JSONObject(responseBody)
                            json.optString("admin_key", "").takeIf { it.isNotEmpty() }
                                ?: json.optString("adminKey", "").takeIf { it.isNotEmpty() }
                                ?: json.optString("token", "").takeIf { it.isNotEmpty() }
                                ?: responseBody
                        } catch (e: Exception) {
                            responseBody
                        }
                        
                        if (adminKey.isNotEmpty()) {
                            Log.d("AbacusService", "Created counter $fullKey")
                            adminKey
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    val errorBody = response.body?.string()
                    Log.w("AbacusService", "Failed to create counter $fullKey: ${response.code} - $errorBody")
                    null
                }.also { response.close() }
            } catch (e: Exception) {
                Log.e("AbacusService", "Error creating counter $fullKey", e)
                null
            }
        }
    }

    /**
     * Set a value for a specific place
     * Invalidates cache on success
     * @return true if successful
     */
    suspend fun setValue(placeId: String, key: String, value: Long, adminKey: String?): Boolean {
        val fullKey = "${placeId}_$key"
        
        if (adminKey == null) {
            Log.w("AbacusService", "Cannot set $fullKey: no admin key")
            return false
        }
        
        // Optimistically update cache immediately
        updateCache(placeId, key, value)
        
        var retryCount = 0
        val maxRetries = 2
        
        while (retryCount <= maxRetries) {
            checkRateLimit()
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = "$BASE_URL/set/$NAMESPACE/$fullKey?value=$value"
                    val headers = mapOf("Authorization" to "Bearer $adminKey")
                    val request = Request.Builder()
                        .url(url)
                        .headers(headers.toHeaders())
                        .post("".toRequestBody("text/plain".toMediaType()))
                        .build()

                    val response = client.newCall(request).execute()
                    val success = response.isSuccessful
                    val statusCode = response.code
                    val errorBody = if (!success) response.body?.string() else null
                    response.close()
                    
                    if (success) {
                        Log.d("AbacusService", "Stored $fullKey: $value")
                        Pair(true, null)
                    } else if (statusCode == 429) {
                        val retryAfter = errorBody?.let {
                            Regex("Try again in ([0-9.]+)s").find(it)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
                        } ?: 10L
                        Log.w("AbacusService", "Rate limited for $fullKey, retry in ${retryAfter}s")
                        Pair(false, retryAfter)
                    } else {
                        Log.w("AbacusService", "Failed to store $fullKey: $statusCode - $errorBody")
                        Pair(false, null)
                    }
                } catch (e: Exception) {
                    Log.e("AbacusService", "Error storing $fullKey", e)
                    Pair(false, null)
                }
            }
            
            val (success, retryAfter) = result
            if (success) return true
            
            if (retryAfter != null && retryCount < maxRetries) {
                delay((retryAfter * 1000) + 500)
                retryCount++
            } else {
                // Failed - invalidate cache to force re-fetch
                invalidateCache(placeId, key)
                return false
            }
        }
        
        return false
    }

    private suspend fun trackCounter(key: String) {
        checkRateLimit()
        
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/hit/$NAMESPACE/$key"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("AbacusService", "Tracked $key")
                }
                response.close()
            } catch (e: Exception) {
                Log.e("AbacusService", "Error tracking $key", e)
            }
        }
    }
}

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
    
    // Rate limiting: 30 requests per 10 seconds = 3 requests per second max
    // Use a sliding window approach: track requests in the last 10 seconds
    private val requestTimestamps = mutableListOf<Long>()
    private val rateLimitMutex = Mutex()
    private const val RATE_LIMIT_REQUESTS = 30
    private const val RATE_LIMIT_WINDOW_MS = 10_000L // 10 seconds
    
    // Cache for getValue calls to reduce API calls
    private data class CachedValue(val value: Long?, val timestamp: Long)
    private val cache = ConcurrentHashMap<String, CachedValue>()
    private const val CACHE_TTL_MS = 30_000L // Cache for 30 seconds
    private val cacheMutex = Mutex()
    
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
            // Remove requests older than the window
            requestTimestamps.removeAll { it < now - RATE_LIMIT_WINDOW_MS }
            
            // If we're at the limit, wait until the oldest request expires
            if (requestTimestamps.size >= RATE_LIMIT_REQUESTS) {
                val oldestTimestamp = requestTimestamps.minOrNull() ?: now
                val waitTime = (oldestTimestamp + RATE_LIMIT_WINDOW_MS) - now
                if (waitTime > 0) {
                    Log.d("AbacusService", "Rate limit reached, waiting ${waitTime}ms")
                    kotlinx.coroutines.delay(waitTime)
                    // Clean up again after waiting
                    requestTimestamps.removeAll { it < System.currentTimeMillis() - RATE_LIMIT_WINDOW_MS }
                }
            }
            
            // Record this request
            requestTimestamps.add(System.currentTimeMillis())
        }
    }

    /**
     * Track a lock event for a specific place
     */
    suspend fun trackLock(placeId: String) {
        trackCounter("${placeId}_locks")
    }

    /**
     * Clear cache for a specific key
     */
    suspend fun clearCache(placeId: String, key: String) {
        val fullKey = "${placeId}_$key"
        cacheMutex.withLock {
            cache.remove(fullKey)
            Log.d("AbacusService", "Cleared cache for $fullKey")
        }
    }
    
    /**
     * Get a value for a specific place
     * Key format: {placeId}_{key}
     * Uses caching to reduce API calls and respects rate limits
     */
    suspend fun getValue(placeId: String, key: String): Long? {
        val fullKey = "${placeId}_$key"
        
        // Check cache first
        cacheMutex.withLock {
            val cached = cache[fullKey]
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
                Log.d("AbacusService", "Cache hit for $fullKey: ${cached.value}")
                return cached.value
            }
        }
        
        // Cache miss or expired, fetch from API
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
                        // Try to parse as JSON first (in case it's {"value": 123})
                        try {
                            val json = JSONObject(body)
                            json.optLong("value", -1).takeIf { it >= 0 }
                                ?: json.optLong("Value", -1).takeIf { it >= 0 }
                                ?: body.toLongOrNull()
                        } catch (e: Exception) {
                            // Not JSON, try parsing as plain number
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
                
                // Cache the result (even if null, to avoid repeated failed requests)
                cacheMutex.withLock {
                    cache[fullKey] = CachedValue(value, System.currentTimeMillis())
                }
                
                if (value != null) {
                    Log.d("AbacusService", "Retrieved $fullKey: $value")
                } else {
                    Log.d("AbacusService", "No value found for $fullKey")
                }
                
                value
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
     * Create a counter and get its admin key
     * @return admin key if successful, null otherwise
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
                        // Try to parse as JSON first (in case it's {"admin_key": "..."})
                        val adminKey = try {
                            val json = JSONObject(responseBody)
                            json.optString("admin_key", "").takeIf { it.isNotEmpty() }
                                ?: json.optString("adminKey", "").takeIf { it.isNotEmpty() }
                                ?: json.optString("token", "").takeIf { it.isNotEmpty() }
                                ?: responseBody // Fallback to raw response if not JSON
                        } catch (e: Exception) {
                            // Not JSON, use as-is
                            Log.d("AbacusService", "Response is not JSON, using as-is: $responseBody")
                            responseBody
                        }
                        
                        if (adminKey.isNotEmpty()) {
                            Log.d("AbacusService", "Created counter $fullKey, extracted admin key (length: ${adminKey.length}, first 10 chars: ${adminKey.take(10)})")
                            adminKey
                        } else {
                            Log.w("AbacusService", "Created counter $fullKey but admin key is empty")
                            null
                        }
                    } else {
                        Log.w("AbacusService", "Created counter $fullKey but response body is empty")
                        null
                    }
                } else {
                    val errorBody = response.body?.string()
                    Log.w("AbacusService", "Failed to create counter $fullKey: ${response.code} - $errorBody")
                    null
                }.also { response.close() }
            } catch (e: IOException) {
                Log.e("AbacusService", "Error creating counter $fullKey", e)
                null
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error creating counter $fullKey", e)
                null
            }
        }
    }

    /**
     * Set a value for a specific place
     * Key format: {placeId}_{key}
     * Respects rate limits and invalidates cache
     * Requires admin key for the counter
     * @param adminKey The admin key for this counter (from createCounter)
     * @return true if successful, false otherwise
     */
    suspend fun setValue(placeId: String, key: String, value: Long, adminKey: String?): Boolean {
        val fullKey = "${placeId}_$key"
        
        if (adminKey == null) {
            Log.w("AbacusService", "Cannot set $fullKey: no admin key provided")
            return false
        }
        
        checkRateLimit()
        
        return withContext(Dispatchers.IO) {
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
                if (success) {
                    Log.d("AbacusService", "Stored $fullKey: $value")
                    // Invalidate cache for this key
                    cacheMutex.withLock {
                        cache.remove(fullKey)
                    }
                } else {
                    val errorBody = response.body?.string()
                    val statusCode = response.code
                    Log.w("AbacusService", "Failed to store $fullKey: $statusCode - $errorBody")
                    
                    // If token is invalid (401), signal that admin key needs to be cleared
                    if (statusCode == 401) {
                        Log.w("AbacusService", "Admin key for $fullKey is invalid, needs to be recreated")
                    }
                }
                response.close()
                success
            } catch (e: IOException) {
                Log.e("AbacusService", "Error storing $fullKey", e)
                false
            } catch (e: Exception) {
                Log.e("AbacusService", "Unexpected error storing $fullKey", e)
                false
            }
        }
    }

    private suspend fun trackCounter(key: String) {
        checkRateLimit()
        
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


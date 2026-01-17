package com.nosmoke.timer.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

private val Context.analyticsStore: DataStore<Preferences> by preferencesDataStore(name = "analytics")

@Serializable
data class SmokeEvent(
    val timestamp: Long,
    val placeId: String,
    val placeName: String
)

class AnalyticsManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AnalyticsManager"
        private val EVENTS_KEY = stringPreferencesKey("smoke_events")
        private val FIRST_SMOKE_KEY = longPreferencesKey("first_smoke_timestamp")
        private val LAST_SMOKE_KEY = longPreferencesKey("last_smoke_timestamp")
        private val TOTAL_COUNT_KEY = longPreferencesKey("total_smoke_count")
        
        private val json = Json { 
            ignoreUnknownKeys = true
            prettyPrint = false
        }
        
        private const val MAX_EVENTS = 1000  // Keep last 1000 events
    }
    
    /**
     * Record a smoke event
     */
    suspend fun recordSmoke(placeId: String, placeName: String) {
        val timestamp = System.currentTimeMillis()
        val event = SmokeEvent(timestamp, placeId, placeName)
        
        context.analyticsStore.edit { preferences ->
            val events = getEvents().toMutableList()
            events.add(event)
            
            // Keep only last MAX_EVENTS
            if (events.size > MAX_EVENTS) {
                events.removeAt(0)
            }
            
            preferences[EVENTS_KEY] = json.encodeToString(events)
            
            // Update summary stats
            val totalCount = preferences[TOTAL_COUNT_KEY] ?: 0L
            preferences[TOTAL_COUNT_KEY] = totalCount + 1
            
            val firstSmoke = preferences[FIRST_SMOKE_KEY] ?: timestamp
            preferences[FIRST_SMOKE_KEY] = firstSmoke
            preferences[LAST_SMOKE_KEY] = timestamp
        }
        
        Log.d(TAG, "Recorded smoke event at $timestamp for place $placeName")
    }
    
    /**
     * Get all smoke events
     */
    suspend fun getEvents(): List<SmokeEvent> {
        val eventsJson = context.analyticsStore.data.map { it[EVENTS_KEY] }.first()
        return if (eventsJson != null) {
            try {
                json.decodeFromString<List<SmokeEvent>>(eventsJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing events JSON", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    
    /**
     * Get total smoke count
     */
    suspend fun getTotalCount(): Long {
        return context.analyticsStore.data.map { it[TOTAL_COUNT_KEY] ?: 0L }.first()
    }
    
    /**
     * Get time since last smoke in milliseconds
     */
    suspend fun getTimeSinceLastSmoke(): Long? {
        val lastSmoke = context.analyticsStore.data.map { it[LAST_SMOKE_KEY] }.first()
        return lastSmoke?.let { System.currentTimeMillis() - it }
    }
    
    /**
     * Get first smoke timestamp
     */
    suspend fun getFirstSmokeTimestamp(): Long? {
        return context.analyticsStore.data.map { it[FIRST_SMOKE_KEY] }.first()
    }
    
    /**
     * Get count for a specific time period (days)
     */
    suspend fun getCountForPeriod(days: Int): Long {
        val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        val events = getEvents()
        return events.count { it.timestamp >= cutoffTime }.toLong()
    }
    
    /**
     * Get average per day for a period (days)
     */
    suspend fun getAveragePerDay(periodDays: Int): Double {
        if (periodDays == 0) return 0.0
        val count = getCountForPeriod(periodDays)
        return count.toDouble() / periodDays
    }
    
    /**
     * Get longest streak (hours without smoking) in milliseconds
     */
    suspend fun getLongestStreak(): Long {
        val events = getEvents().sortedBy { it.timestamp }
        if (events.size < 2) return 0L
        
        var maxStreak = 0L
        for (i in 1 until events.size) {
            val gap = events[i].timestamp - events[i - 1].timestamp
            if (gap > maxStreak) {
                maxStreak = gap
            }
        }
        
        return maxStreak
    }
    
    /**
     * Get current streak (time since last smoke) in milliseconds
     */
    suspend fun getCurrentStreak(): Long {
        return getTimeSinceLastSmoke() ?: 0L
    }
    
    /**
     * Get smoke count by place
     */
    suspend fun getCountByPlace(): Map<String, Pair<String, Long>> {
        val events = getEvents()
        val counts = mutableMapOf<String, MutableList<String>>()
        
        events.forEach { event ->
            if (!counts.containsKey(event.placeId)) {
                counts[event.placeId] = mutableListOf()
            }
            counts[event.placeId]?.add(event.placeName)
        }
        
        return counts.mapValues { (_, names) ->
            val placeName = names.firstOrNull() ?: "Unknown"
            val count = names.size.toLong()
            Pair(placeName, count)
        }
    }
    
    /**
     * Get smoke count by hour of day (0-23)
     * Returns a list of 24 counts, one for each hour
     */
    suspend fun getCountByHourOfDay(): List<Int> {
        val events = getEvents()
        val hourCounts = IntArray(24) { 0 }
        
        events.forEach { event ->
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = event.timestamp
            val hour = calendar.get(Calendar.HOUR_OF_DAY) // 0-23
            hourCounts[hour]++
        }
        
        return hourCounts.toList()
    }
    
    /**
     * Get average gap between smokes per hour of day (0-23)
     * Returns a list of 24 average gaps in milliseconds, one for each hour
     * Gaps are calculated from smokes that occurred in that hour
     */
    suspend fun getGapsPerHour(): List<Long?> {
        val events = getEvents().sortedBy { it.timestamp }
        if (events.size < 2) return List(24) { null }
        
        val gapsByHour = mutableMapOf<Int, MutableList<Long>>()
        
        // Calculate gaps between consecutive events
        for (i in 1 until events.size) {
            val gap = events[i].timestamp - events[i - 1].timestamp
            // Gap is attributed to the hour of the FIRST smoke (event[i-1])
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = events[i - 1].timestamp
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            
            gapsByHour.getOrPut(hour) { mutableListOf() }.add(gap)
        }
        
        // Calculate average gap for each hour
        return (0..23).map { hour ->
            gapsByHour[hour]?.average()?.toLong()
        }
    }
    
    /**
     * Get smoke count by day of week (0=Sunday, 6=Saturday)
     * Returns a list of 7 counts, one for each day
     */
    suspend fun getCountByDayOfWeek(): List<Int> {
        val events = getEvents()
        val dayCounts = IntArray(7) { 0 }
        
        events.forEach { event ->
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = event.timestamp
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 7=Saturday
            val index = (dayOfWeek - 1) % 7 // Convert to 0-6 (Sunday=0, Saturday=6)
            dayCounts[index]++
        }
        
        return dayCounts.toList()
    }
    
    /**
     * Get average gap between smokes per day of week (0=Sunday, 6=Saturday)
     * Returns a list of 7 average gaps in milliseconds, one for each day
     * Gaps are calculated from smokes that occurred on that day
     */
    suspend fun getGapsPerDayOfWeek(): List<Long?> {
        val events = getEvents().sortedBy { it.timestamp }
        if (events.size < 2) return List(7) { null }
        
        val gapsByDay = mutableMapOf<Int, MutableList<Long>>()
        
        // Calculate gaps between consecutive events
        for (i in 1 until events.size) {
            val gap = events[i].timestamp - events[i - 1].timestamp
            // Gap is attributed to the day of the FIRST smoke (event[i-1])
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = events[i - 1].timestamp
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 7=Saturday
            val dayIndex = (dayOfWeek - 1) % 7 // Convert to 0-6 (Sunday=0, Saturday=6)
            
            gapsByDay.getOrPut(dayIndex) { mutableListOf() }.add(gap)
        }
        
        // Calculate average gap for each day
        return (0..6).map { dayIndex ->
            gapsByDay[dayIndex]?.average()?.toLong()
        }
    }
    
    /**
     * Format milliseconds to human readable string
     */
    fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
    
    /**
     * Clear all analytics data
     */
    suspend fun clearAll() {
        context.analyticsStore.edit { preferences ->
            preferences.remove(EVENTS_KEY)
            preferences.remove(FIRST_SMOKE_KEY)
            preferences.remove(LAST_SMOKE_KEY)
            preferences.remove(TOTAL_COUNT_KEY)
        }
        Log.d(TAG, "Cleared all analytics data")
    }
}


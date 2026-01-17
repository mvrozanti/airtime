package com.nosmoke.timer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Log
import com.nosmoke.timer.service.AlarmReceiver
import com.nosmoke.timer.service.AbacusService

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smoke_timer_state")

class StateManager(private val context: Context) {
    
    companion object {
        private val IS_LOCKED_KEY = booleanPreferencesKey("is_locked")
        private val LOCK_END_TIMESTAMP_KEY = longPreferencesKey("lock_end_timestamp")
        private val CURRENT_PLACE_ID_KEY = stringPreferencesKey("current_place_id")
        
        // Increment keys are now per-place, stored as {placeId}_increment
        private fun incrementKey(placeId: String) = longPreferencesKey("${placeId}_increment")
    }
    
    val locationConfig = LocationConfig(context)

    val isLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_LOCKED_KEY] ?: false
    }

    val lockEndTimestamp: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LOCK_END_TIMESTAMP_KEY] ?: 0L
    }
    
    val currentPlaceId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[CURRENT_PLACE_ID_KEY] ?: Place.DEFAULT.id
    }

    /**
     * Get increment for a specific place
     */
    suspend fun getIncrement(placeId: String): Long {
        val key = incrementKey(placeId)
        return context.dataStore.data.map { it[key] ?: 0L }.first()
    }
    
    /**
     * Get increment for the current place
     */
    suspend fun getCurrentIncrement(): Long {
        val place = locationConfig.getCurrentPlace()
        return getIncrement(place.id)
    }

    /**
     * Flow of increment for the current place (updates when place changes)
     */
    fun incrementFlow(placeId: String): Flow<Long> {
        val key = incrementKey(placeId)
        return context.dataStore.data.map { preferences ->
            preferences[key] ?: 0L
        }
    }

    suspend fun getIsLocked(): Boolean {
        return context.dataStore.data.map { it[IS_LOCKED_KEY] ?: false }.first()
    }

    suspend fun getLockEndTimestamp(): Long {
        return context.dataStore.data.map { it[LOCK_END_TIMESTAMP_KEY] ?: 0L }.first()
    }
    
    suspend fun getCurrentPlaceId(): String {
        return context.dataStore.data.map { it[CURRENT_PLACE_ID_KEY] ?: Place.DEFAULT.id }.first()
    }
    
    /**
     * Get base duration for the current place
     * Uses place's configured value, with Abacus as override
     */
    suspend fun getBaseDurationMinutes(): Long {
        val place = locationConfig.getCurrentPlace()
        // Try Abacus first for override
        val abacusValue = AbacusService.getValue(place.id, "base_duration_minutes")
        if (abacusValue != null) {
            return abacusValue
        }
        // Use place's configured value
        return place.baseDurationMinutes
    }
    
    /**
     * Get increment step for the current place
     * Uses place's configured value, with Abacus as override
     */
    suspend fun getIncrementStepSeconds(): Long {
        val place = locationConfig.getCurrentPlace()
        // Try Abacus first for override
        val abacusValue = AbacusService.getValue(place.id, "increment_step_seconds")
        if (abacusValue != null) {
            return abacusValue
        }
        // Use place's configured value
        return place.incrementStepSeconds
    }
    
    /**
     * Set base duration for the current place (stores to Abacus)
     */
    suspend fun setBaseDurationMinutes(minutes: Long) {
        val place = locationConfig.getCurrentPlace()
        AbacusService.setValue(place.id, "base_duration_minutes", minutes)
    }
    
    /**
     * Set increment step for the current place (stores to Abacus)
     */
    suspend fun setIncrementStepSeconds(seconds: Long) {
        val place = locationConfig.getCurrentPlace()
        AbacusService.setValue(place.id, "increment_step_seconds", seconds)
    }

    suspend fun lock() {
        val place = locationConfig.getCurrentPlace()
        val currentIncrement = getIncrement(place.id)
        val baseDurationMinutes = getBaseDurationMinutes()
        val incrementStepSeconds = getIncrementStepSeconds()
        val baseDurationMs = baseDurationMinutes * 60 * 1000
        val lockDuration = baseDurationMs + (currentIncrement * incrementStepSeconds * 1000)
        val lockEndTime = System.currentTimeMillis() + lockDuration
        val newIncrement = currentIncrement + 1

        Log.e("StateManager", "LOCKING TIMER: place=${place.name}, increment=$currentIncrement, duration=$lockDuration ms, endTime=$lockEndTime")
        Log.e("StateManager", "LOCKING TIMER: About to edit DataStore...")

        val incrementKey = incrementKey(place.id)
        context.dataStore.edit { preferences ->
            Log.e("StateManager", "LOCKING TIMER: Inside DataStore edit block")
            preferences[IS_LOCKED_KEY] = true
            preferences[LOCK_END_TIMESTAMP_KEY] = lockEndTime
            preferences[CURRENT_PLACE_ID_KEY] = place.id
            preferences[incrementKey] = newIncrement
            Log.e("StateManager", "LOCKING TIMER: DataStore values set")
        }

        // Schedule alarm for auto-unlock
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.airtime.timer.UNLOCK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, lockEndTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, lockEndTime, pendingIntent)
        }

        // Track lock with Abacus (fire and forget)
        AbacusService.trackLock(place.id)

        Log.e("StateManager", "TIMER LOCKED SUCCESSFULLY - Alarm scheduled for unlock")
    }

    suspend fun unlock() {
        Log.d("StateManager", "Unlocking timer")
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
        }
    }

    /**
     * Reset increment for a specific place
     */
    suspend fun resetIncrement(placeId: String) {
        val key = incrementKey(placeId)
        context.dataStore.edit { preferences ->
            preferences[key] = 0L
        }
    }
    
    /**
     * Reset increment for the current place
     */
    suspend fun resetCurrentIncrement() {
        val place = locationConfig.getCurrentPlace()
        resetIncrement(place.id)
    }

    suspend fun reset() {
        val placeId = getCurrentPlaceId()
        val key = incrementKey(placeId)
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
            preferences[LOCK_END_TIMESTAMP_KEY] = 0L
            preferences[key] = 0L
        }
    }

    fun getRemainingTimeMillis(lockEndTimestamp: Long): Long {
        val now = System.currentTimeMillis()
        return (lockEndTimestamp - now).coerceAtLeast(0)
    }

    fun getRemainingTimeFormatted(lockEndTimestamp: Long): String {
        val remainingMs = getRemainingTimeMillis(lockEndTimestamp)
        val totalSeconds = remainingMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return if (minutes > 0) {
            String.format("%dm %ds", minutes, seconds)
        } else {
            String.format("%ds", seconds)
        }
    }
}


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
        private val INCREMENT_KEY = longPreferencesKey("increment_seconds")
        private val BASE_DURATION_MINUTES_KEY = longPreferencesKey("base_duration_minutes")
        private val INCREMENT_STEP_SECONDS_KEY = longPreferencesKey("increment_step_seconds")
        
        // Default values
        private const val DEFAULT_BASE_DURATION_MINUTES = 40L
        private const val DEFAULT_INCREMENT_STEP_SECONDS = 1L
    }

    val isLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_LOCKED_KEY] ?: false
    }

    val lockEndTimestamp: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LOCK_END_TIMESTAMP_KEY] ?: 0L
    }

    val increment: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[INCREMENT_KEY] ?: 0L
    }

    suspend fun getIsLocked(): Boolean {
        return context.dataStore.data.map { it[IS_LOCKED_KEY] ?: false }.first()
    }

    suspend fun getLockEndTimestamp(): Long {
        return context.dataStore.data.map { it[LOCK_END_TIMESTAMP_KEY] ?: 0L }.first()
    }

    suspend fun getIncrement(): Long {
        return context.dataStore.data.map { it[INCREMENT_KEY] ?: 0L }.first()
    }
    
    suspend fun getBaseDurationMinutes(): Long {
        // Try Abacus first
        val abacusValue = AbacusService.getValue("base_duration_minutes")
        if (abacusValue != null) {
            // Cache in local storage
            context.dataStore.edit { preferences ->
                preferences[BASE_DURATION_MINUTES_KEY] = abacusValue
            }
            return abacusValue
        }
        // Fallback to local storage
        val localValue = context.dataStore.data.map { it[BASE_DURATION_MINUTES_KEY] }.first()
        return localValue ?: DEFAULT_BASE_DURATION_MINUTES
    }
    
    suspend fun getIncrementStepSeconds(): Long {
        // Try Abacus first
        val abacusValue = AbacusService.getValue("increment_step_seconds")
        if (abacusValue != null) {
            // Cache in local storage
            context.dataStore.edit { preferences ->
                preferences[INCREMENT_STEP_SECONDS_KEY] = abacusValue
            }
            return abacusValue
        }
        // Fallback to local storage
        val localValue = context.dataStore.data.map { it[INCREMENT_STEP_SECONDS_KEY] }.first()
        return localValue ?: DEFAULT_INCREMENT_STEP_SECONDS
    }
    
    suspend fun setBaseDurationMinutes(minutes: Long) {
        // Store to Abacus (fire and forget)
        AbacusService.setValue("base_duration_minutes", minutes)
        // Also store locally as cache
        context.dataStore.edit { preferences ->
            preferences[BASE_DURATION_MINUTES_KEY] = minutes
        }
    }
    
    suspend fun setIncrementStepSeconds(seconds: Long) {
        // Store to Abacus (fire and forget)
        AbacusService.setValue("increment_step_seconds", seconds)
        // Also store locally as cache
        context.dataStore.edit { preferences ->
            preferences[INCREMENT_STEP_SECONDS_KEY] = seconds
        }
    }

    suspend fun lock() {
        val currentIncrement = getIncrement()
        val baseDurationMinutes = getBaseDurationMinutes()
        val incrementStepSeconds = getIncrementStepSeconds()
        val baseDurationMs = baseDurationMinutes * 60 * 1000
        val lockDuration = baseDurationMs + (currentIncrement * incrementStepSeconds * 1000)
        val lockEndTime = System.currentTimeMillis() + lockDuration
        val newIncrement = currentIncrement + 1

        Log.e("StateManager", "LOCKING TIMER: increment=$currentIncrement, duration=$lockDuration ms, endTime=$lockEndTime")
        Log.e("StateManager", "LOCKING TIMER: About to edit DataStore...")

        context.dataStore.edit { preferences ->
            Log.e("StateManager", "LOCKING TIMER: Inside DataStore edit block")
            preferences[IS_LOCKED_KEY] = true
            preferences[LOCK_END_TIMESTAMP_KEY] = lockEndTime
            preferences[INCREMENT_KEY] = newIncrement
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
        AbacusService.trackLock()

        Log.e("StateManager", "TIMER LOCKED SUCCESSFULLY - Alarm scheduled for unlock")
    }

    suspend fun unlock() {
        Log.d("StateManager", "Unlocking timer")
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
        }
    }

    suspend fun resetIncrement() {
        context.dataStore.edit { preferences ->
            preferences[INCREMENT_KEY] = 0L
        }
    }

    suspend fun reset() {
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
            preferences[LOCK_END_TIMESTAMP_KEY] = 0L
            preferences[INCREMENT_KEY] = 0L
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


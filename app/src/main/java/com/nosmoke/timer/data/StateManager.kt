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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nosmoke.timer.service.AlarmReceiver
import com.nosmoke.timer.service.AbacusService

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smoke_timer_state")
private val Context.abacusAdminKeysStore: DataStore<Preferences> by preferencesDataStore(name = "abacus_admin_keys")

class StateManager(private val context: Context) {
    // Background scope for non-blocking Abacus sync operations
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Handler for showing Toasts from background threads
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Show a Toast message (works from any thread)
     */
    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            Toast.makeText(context, message, duration).show()
        }
    }
    
    companion object {
        private val IS_LOCKED_KEY = booleanPreferencesKey("is_locked")
        private val LOCK_END_TIMESTAMP_KEY = longPreferencesKey("lock_end_timestamp")
        private val CURRENT_PLACE_ID_KEY = stringPreferencesKey("current_place_id")
        
        // Increment keys are now per-place, stored as {placeId}_increment
        private fun incrementKey(placeId: String) = longPreferencesKey("${placeId}_increment")
        
        // Admin keys for Abacus counters, stored as {placeId}_{key}_admin
        private fun adminKeyKey(placeId: String, key: String) = stringPreferencesKey("${placeId}_${key}_admin")
    }
    
    val locationConfig = LocationConfig(context)

    val isLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_LOCKED_KEY] ?: false
    }

    val lockEndTimestamp: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LOCK_END_TIMESTAMP_KEY] ?: 0L
    }
    
    val currentPlaceId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[CURRENT_PLACE_ID_KEY] ?: Place.DEFAULT.name
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
        return getIncrement(place.name)
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
        return context.dataStore.data.map { it[CURRENT_PLACE_ID_KEY] ?: Place.DEFAULT.name }.first()
    }
    
    /**
     * Get base duration for the current place
     * Always reads from Abacus - NO FALLBACK to local values
     * Returns null if Abacus is unavailable
     */
    suspend fun getBaseDurationMinutes(): Long? {
        val place = locationConfig.getCurrentPlace()
        // Try Abacus (check v2, then v1, then old format for compatibility)
        val abacusValue = AbacusService.getValue(place.name, "base_duration_minutes_config_v2")
            ?: AbacusService.getValue(place.name, "base_duration_minutes_config")
            ?: AbacusService.getValue(place.name, "base_duration_minutes")
        // Return null if Abacus unavailable - NO FALLBACK
        return abacusValue
    }
    
    /**
     * Get increment step for the current place
     * Always reads from Abacus - NO FALLBACK to local values
     * Returns null if Abacus is unavailable
     */
    suspend fun getIncrementStepSeconds(): Long? {
        val place = locationConfig.getCurrentPlace()
        // Try Abacus (check v2, then v1, then old format for compatibility)
        val abacusValue = AbacusService.getValue(place.name, "increment_step_seconds_config_v2")
            ?: AbacusService.getValue(place.name, "increment_step_seconds_config")
            ?: AbacusService.getValue(place.name, "increment_step_seconds")
        // Return null if Abacus unavailable - NO FALLBACK
        return abacusValue
    }
    
    /**
     * Set base duration for the current place (stores to Abacus)
     */
    suspend fun setBaseDurationMinutes(minutes: Long) {
        val place = locationConfig.getCurrentPlace()
        // Ensure counter exists and get admin key
        var adminKey = getAdminKey(place.name, "base_duration_minutes_config_v2")
        if (adminKey == null) {
            // Try to create the counter
            Log.d("StateManager", "No admin key found, creating counter for base_duration_minutes_config_v2")
            adminKey = AbacusService.createCounter(place.name, "base_duration_minutes_config_v2")
            if (adminKey != null) {
                storeAdminKey(place.name, "base_duration_minutes_config_v2", adminKey)
            } else {
                Log.w("StateManager", "Failed to create counter for base_duration_minutes_config_v2, cannot set value")
                return
            }
        }
        val success = AbacusService.setValue(place.name, "base_duration_minutes_config_v2", minutes, adminKey)
        if (!success) {
            Log.w("StateManager", "Failed to set base_duration_minutes_config_v2, clearing admin key and retrying")
            // Clear admin key and try to recreate
            clearAdminKey(place.name, "base_duration_minutes_config_v2")
            val newAdminKey = AbacusService.createCounter(place.name, "base_duration_minutes_config_v2")
            if (newAdminKey != null) {
                storeAdminKey(place.name, "base_duration_minutes_config_v2", newAdminKey)
                val retrySuccess = AbacusService.setValue(place.name, "base_duration_minutes_config_v2", minutes, newAdminKey)
                if (!retrySuccess) {
                    showToast("Failed to sync base duration to Abacus", Toast.LENGTH_LONG)
                }
            } else {
                showToast("Failed to create counter for base duration", Toast.LENGTH_LONG)
            }
        } else {
            Log.d("StateManager", "Successfully set base_duration_minutes_config_v2 to $minutes")
        }
    }
    
    /**
     * Set increment step for the current place (stores to Abacus)
     */
    suspend fun setIncrementStepSeconds(seconds: Long) {
        val place = locationConfig.getCurrentPlace()
        // Ensure counter exists and get admin key
        var adminKey = getAdminKey(place.name, "increment_step_seconds_config_v2")
        if (adminKey == null) {
            // Try to create the counter
            Log.d("StateManager", "No admin key found, creating counter for increment_step_seconds_config_v2")
            adminKey = AbacusService.createCounter(place.name, "increment_step_seconds_config_v2")
            if (adminKey != null) {
                storeAdminKey(place.name, "increment_step_seconds_config_v2", adminKey)
            } else {
                Log.w("StateManager", "Failed to create counter for increment_step_seconds_config_v2, cannot set value")
                return
            }
        }
        val success = AbacusService.setValue(place.name, "increment_step_seconds_config_v2", seconds, adminKey)
        if (!success) {
            Log.w("StateManager", "Failed to set increment_step_seconds_config_v2, clearing admin key and retrying")
            // Clear admin key and try to recreate
            clearAdminKey(place.name, "increment_step_seconds_config_v2")
            val newAdminKey = AbacusService.createCounter(place.name, "increment_step_seconds_config_v2")
            if (newAdminKey != null) {
                storeAdminKey(place.name, "increment_step_seconds_config_v2", newAdminKey)
                val retrySuccess = AbacusService.setValue(place.name, "increment_step_seconds_config_v2", seconds, newAdminKey)
                if (!retrySuccess) {
                    showToast("Failed to sync increment step to Abacus", Toast.LENGTH_LONG)
                }
            } else {
                showToast("Failed to create counter for increment step", Toast.LENGTH_LONG)
            }
        } else {
            Log.d("StateManager", "Successfully set increment_step_seconds_config_v2 to $seconds")
        }
    }

    suspend fun lock() {
        val place = locationConfig.getCurrentPlace()
        val currentIncrement = getIncrement(place.name)
        val baseDurationMinutes = getBaseDurationMinutes() 
            ?: throw IllegalStateException("Cannot get base duration from Abacus - Abacus unavailable")
        val incrementStepSeconds = getIncrementStepSeconds()
            ?: throw IllegalStateException("Cannot get increment step from Abacus - Abacus unavailable")
        val baseDurationMs = baseDurationMinutes * 60 * 1000
        val lockDuration = baseDurationMs + (currentIncrement * incrementStepSeconds * 1000)
        val lockEndTime = System.currentTimeMillis() + lockDuration
        val newIncrement = currentIncrement + 1

        Log.e("StateManager", "LOCKING TIMER: place=${place.name}, increment=$currentIncrement, duration=$lockDuration ms, endTime=$lockEndTime")
        Log.e("StateManager", "LOCKING TIMER: About to edit DataStore...")

        val incrementKey = incrementKey(place.name)
        context.dataStore.edit { preferences ->
            Log.e("StateManager", "LOCKING TIMER: Inside DataStore edit block")
            preferences[IS_LOCKED_KEY] = true
            preferences[LOCK_END_TIMESTAMP_KEY] = lockEndTime
            preferences[CURRENT_PLACE_ID_KEY] = place.name
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
        AbacusService.trackLock(place.name)
        
        // Sync lock state to Abacus (non-blocking, fire and forget)
        backgroundScope.launch {
            syncLockStateToAbacus(place.name, true, lockEndTime, newIncrement)
        }
        
        // Record analytics event
        val analyticsManager = AnalyticsManager(context)
        analyticsManager.recordSmoke(place.name, place.name)

        Log.e("StateManager", "TIMER LOCKED SUCCESSFULLY - Alarm scheduled for unlock")
    }

    suspend fun unlock() {
        Log.d("StateManager", "Unlocking timer")
        val place = locationConfig.getCurrentPlace()
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
            preferences[LOCK_END_TIMESTAMP_KEY] = 0L // Clear end timestamp on unlock
        }
        
        // Sync unlock state to Abacus (non-blocking, fire and forget)
        backgroundScope.launch {
            syncLockStateToAbacus(place.name, false, 0L)
        }
    }
    
    /**
     * Sync lock state to Abacus
     */
    private suspend fun syncLockStateToAbacus(placeId: String, isLocked: Boolean, lockEndTimestamp: Long, increment: Long? = null) {
        var hasErrors = false
        val errorMessages = mutableListOf<String>()
        
        // Ensure counters exist and get admin keys
        val isLockedAdminKey = ensureStateCounterExists(placeId, "is_locked")
        val lockEndAdminKey = ensureStateCounterExists(placeId, "lock_end_timestamp")
        
        // Sync is_locked state
        if (isLockedAdminKey != null) {
            val lockedValue = if (isLocked) 1L else 0L
            val success = AbacusService.setValue(placeId, "is_locked", lockedValue, isLockedAdminKey)
            if (!success) {
                hasErrors = true
                errorMessages.add("Failed to sync lock state")
            }
        } else {
            hasErrors = true
            errorMessages.add("No admin key for lock state")
        }
        
        // Sync lock_end_timestamp
        if (lockEndAdminKey != null) {
            val success = AbacusService.setValue(placeId, "lock_end_timestamp", lockEndTimestamp, lockEndAdminKey)
            if (!success) {
                hasErrors = true
                errorMessages.add("Failed to sync lock timestamp")
            }
        } else {
            hasErrors = true
            errorMessages.add("No admin key for lock timestamp")
        }
        
        // Sync increment if provided
        if (increment != null) {
            val incrementAdminKey = ensureStateCounterExists(placeId, "increment")
            if (incrementAdminKey != null) {
                val success = AbacusService.setValue(placeId, "increment", increment, incrementAdminKey)
                if (!success) {
                    hasErrors = true
                    errorMessages.add("Failed to sync increment")
                }
            } else {
                hasErrors = true
                errorMessages.add("No admin key for increment")
            }
        }
        
        // Show error toast if any failures occurred
        if (hasErrors) {
            val errorMsg = "Abacus sync error: ${errorMessages.joinToString(", ")}"
            Log.w("StateManager", errorMsg)
            showToast(errorMsg, Toast.LENGTH_LONG)
        }
    }
    
    /**
     * Ensure state counter exists, create it if needed, and return its admin key
     */
    private suspend fun ensureStateCounterExists(placeId: String, key: String): String? {
        // Check if we already have an admin key stored
        val adminKeyPref = adminKeyKey(placeId, key)
        var adminKey = context.abacusAdminKeysStore.data.map { it[adminKeyPref] }.first()
        if (adminKey != null) {
            Log.d("StateManager", "Using existing admin key for ${placeId}_$key")
            return adminKey
        }
        
        // Try to create the counter
        Log.d("StateManager", "Creating state counter ${placeId}_$key")
        adminKey = AbacusService.createCounter(placeId, key)
        
        if (adminKey != null) {
            // Store the admin key for future use
            context.abacusAdminKeysStore.edit { preferences ->
                preferences[adminKeyPref] = adminKey
            }
            Log.d("StateManager", "Created and stored admin key for ${placeId}_$key")
        } else {
            // Counter might already exist (409), or creation failed
            Log.w("StateManager", "Cannot create state counter ${placeId}_$key (may already exist without admin key)")
        }
        
        return adminKey
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
        resetIncrement(place.name)
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
    
    /**
     * Store admin key for an Abacus counter
     */
    suspend fun storeAdminKey(placeId: String, key: String, adminKey: String) {
        val adminKeyPref = adminKeyKey(placeId, key)
        context.abacusAdminKeysStore.edit { preferences ->
            preferences[adminKeyPref] = adminKey
        }
        Log.d("StateManager", "Stored admin key for ${placeId}_$key")
    }
    
    /**
     * Get admin key for an Abacus counter
     */
    suspend fun getAdminKey(placeId: String, key: String): String? {
        val adminKeyPref = adminKeyKey(placeId, key)
        return context.abacusAdminKeysStore.data.map { it[adminKeyPref] }.first()
    }
    
    /**
     * Clear stored admin key (used when token becomes invalid)
     */
    private suspend fun clearAdminKey(placeId: String, key: String) {
        val adminKeyPref = adminKeyKey(placeId, key)
        context.abacusAdminKeysStore.edit { preferences ->
            preferences.remove(adminKeyPref)
        }
        Log.d("StateManager", "Cleared admin key for ${placeId}_$key")
    }
    
    /**
     * Sync state FROM Abacus to local DataStore
     * This detects external changes (e.g., from bash script) and updates local state
     */
    suspend fun syncFromAbacus() {
        val place = locationConfig.getCurrentPlace()
        
        // Get current Abacus state
        val abacusIsLocked = AbacusService.getValue(place.name, "is_locked")
        val abacusLockEndTimestamp = AbacusService.getValue(place.name, "lock_end_timestamp")
        val abacusIncrement = AbacusService.getValue(place.name, "increment")
        
        // Get current local state
        val localIsLocked = getIsLocked()
        val localLockEndTimestamp = getLockEndTimestamp()
        val localIncrement = getIncrement(place.name)
        
        // Check if Abacus state differs from local state
        val isLockedChanged = abacusIsLocked != null && (abacusIsLocked == 1L) != localIsLocked
        val timestampChanged = abacusLockEndTimestamp != null && abacusLockEndTimestamp != localLockEndTimestamp
        val incrementChanged = abacusIncrement != null && abacusIncrement != localIncrement
        
        if (isLockedChanged || timestampChanged || incrementChanged) {
            Log.d("StateManager", "Abacus state differs from local, syncing...")
            Log.d("StateManager", "Abacus: locked=$abacusIsLocked, timestamp=$abacusLockEndTimestamp, increment=$abacusIncrement")
            Log.d("StateManager", "Local: locked=$localIsLocked, timestamp=$localLockEndTimestamp, increment=$localIncrement")
            
            // Update local state to match Abacus
            val incrementKey = incrementKey(place.name)
            context.dataStore.edit { preferences ->
                if (isLockedChanged && abacusIsLocked != null) {
                    preferences[IS_LOCKED_KEY] = (abacusIsLocked == 1L)
                    Log.d("StateManager", "Updated is_locked from Abacus: ${abacusIsLocked == 1L}")
                }
                if (timestampChanged && abacusLockEndTimestamp != null) {
                    preferences[LOCK_END_TIMESTAMP_KEY] = abacusLockEndTimestamp
                    Log.d("StateManager", "Updated lock_end_timestamp from Abacus: $abacusLockEndTimestamp")
                }
                if (incrementChanged && abacusIncrement != null) {
                    preferences[incrementKey] = abacusIncrement
                    Log.d("StateManager", "Updated increment from Abacus: $abacusIncrement")
                }
                preferences[CURRENT_PLACE_ID_KEY] = place.name
            }
            
            // If we unlocked from Abacus, cancel any pending alarm
            if (isLockedChanged && abacusIsLocked == 0L) {
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
                alarmManager.cancel(pendingIntent)
                Log.d("StateManager", "Cancelled alarm due to unlock from Abacus")
            }
        }
    }
}


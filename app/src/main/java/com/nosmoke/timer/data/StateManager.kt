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
// Internal so LocationConfig can access it - must be defined only once to avoid multiple DataStore instances
internal val Context.abacusAdminKeysStore: DataStore<Preferences> by preferencesDataStore(name = "abacus_admin_keys")

class StateManager(private val context: Context) {
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Timestamp of last local lock - used to ignore stale Abacus data
    @Volatile
    private var lastLocalLockTime: Long = 0
    
    // Grace period after lock before accepting unlocks from Abacus (in ms)
    private val SYNC_GRACE_PERIOD_MS = 15_000L  // 15 seconds
    
    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            Toast.makeText(context, message, duration).show()
        }
    }
    
    companion object {
        // Fixed place name for ALL state - keeps everything consistent with polybar script
        // State is location-independent - only duration config varies by location
        const val STATE_PLACE = "Unknown"
        
        private val IS_LOCKED_KEY = booleanPreferencesKey("is_locked")
        private val LOCK_END_TIMESTAMP_KEY = longPreferencesKey("lock_end_timestamp")
        private val CURRENT_PLACE_ID_KEY = stringPreferencesKey("current_place_id")
        
        private fun incrementKey(placeId: String) = longPreferencesKey("${placeId}_increment")
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

    suspend fun getIncrement(placeId: String): Long {
        val key = incrementKey(placeId)
        return context.dataStore.data.map { it[key] ?: 0L }.first()
    }
    
    suspend fun getCurrentIncrement(): Long {
        return getIncrement(STATE_PLACE)
    }

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
     * Get base duration from current location-based place (or Unknown)
     */
    suspend fun getBaseDurationMinutes(): Long? {
        val place = locationConfig.getCurrentPlace()
        return AbacusService.getValue(place.name, "base_duration_minutes_config_v2")
            ?: AbacusService.getValue(place.name, "base_duration_minutes_config")
            ?: AbacusService.getValue(place.name, "base_duration_minutes")
    }
    
    /**
     * Get increment step from current location-based place (or Unknown)
     */
    suspend fun getIncrementStepSeconds(): Long? {
        val place = locationConfig.getCurrentPlace()
        return AbacusService.getValue(place.name, "increment_step_seconds_config_v2")
            ?: AbacusService.getValue(place.name, "increment_step_seconds_config")
            ?: AbacusService.getValue(place.name, "increment_step_seconds")
    }
    
    suspend fun setBaseDurationMinutes(minutes: Long) {
        val place = locationConfig.getCurrentPlace()
        var adminKey = getAdminKey(place.name, "base_duration_minutes_config_v2")
        if (adminKey == null) {
            adminKey = AbacusService.createCounter(place.name, "base_duration_minutes_config_v2")
            if (adminKey != null) {
                storeAdminKey(place.name, "base_duration_minutes_config_v2", adminKey)
            }
        }
        if (adminKey != null) {
            val success = AbacusService.setValue(place.name, "base_duration_minutes_config_v2", minutes, adminKey)
            if (!success) {
                showToast("Failed to sync base duration", Toast.LENGTH_LONG)
            }
        }
    }
    
    suspend fun setIncrementStepSeconds(seconds: Long) {
        val place = locationConfig.getCurrentPlace()
        var adminKey = getAdminKey(place.name, "increment_step_seconds_config_v2")
        if (adminKey == null) {
            adminKey = AbacusService.createCounter(place.name, "increment_step_seconds_config_v2")
            if (adminKey != null) {
                storeAdminKey(place.name, "increment_step_seconds_config_v2", adminKey)
            }
        }
        if (adminKey != null) {
            val success = AbacusService.setValue(place.name, "increment_step_seconds_config_v2", seconds, adminKey)
            if (!success) {
                showToast("Failed to sync increment step", Toast.LENGTH_LONG)
            }
        }
    }

    /**
     * LOCK - Instant UI update, background sync to Abacus
     * Uses current location-based place for duration config, but STATE_PLACE for state
     */
    suspend fun lock() {
        val currentIncrement = getIncrement(STATE_PLACE)
        
        // Get duration config from current location-based place (or Unknown if no match)
        val currentPlace = locationConfig.getCurrentPlace()
        val baseDurationMinutes = getBaseDurationMinutes() ?: currentPlace.baseDurationMinutes
        val incrementStepSeconds = getIncrementStepSeconds() ?: currentPlace.incrementStepSeconds
        
        Log.d("StateManager", "LOCK: place=${currentPlace.name} (config), state=$STATE_PLACE, base=${baseDurationMinutes}min, step=${incrementStepSeconds}s, increment=$currentIncrement")
        
        val baseDurationMs = baseDurationMinutes * 60 * 1000
        val incrementDurationMs = currentIncrement * incrementStepSeconds * 1000
        val lockDuration = baseDurationMs + incrementDurationMs
        val lockEndTime = System.currentTimeMillis() + lockDuration
        val newIncrement = currentIncrement + 1

        Log.d("StateManager", "LOCK: duration=${lockDuration}ms (${lockDuration / 60000}min), endTime=$lockEndTime")

        // Record when we locked locally - ignore Abacus unlocks for a grace period
        lastLocalLockTime = System.currentTimeMillis()

        // INSTANT UI UPDATE
        val incrementKey = incrementKey(STATE_PLACE)
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = true
            preferences[LOCK_END_TIMESTAMP_KEY] = lockEndTime
            preferences[CURRENT_PLACE_ID_KEY] = STATE_PLACE
            preferences[incrementKey] = newIncrement
        }

        // Schedule alarm
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.airtime.timer.UNLOCK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, lockEndTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, lockEndTime, pendingIntent)
        }

        // Analytics
        AbacusService.trackLock(STATE_PLACE)
        val analyticsManager = AnalyticsManager(context)
        analyticsManager.recordSmoke(STATE_PLACE, STATE_PLACE)

        // BACKGROUND SYNC TO ABACUS (fire and forget - no rollback)
        backgroundScope.launch {
            syncToAbacus(STATE_PLACE, true, lockEndTime, newIncrement)
        }
        
        Log.d("StateManager", "LOCK COMPLETE - UI updated, syncing to Abacus in background")
    }

    /**
     * UNLOCK - Instant UI update, background sync to Abacus
     */
    suspend fun unlock() {
        Log.d("StateManager", "UNLOCK")
        
        // INSTANT UI UPDATE
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
            preferences[LOCK_END_TIMESTAMP_KEY] = 0L
        }
        
        // Cancel alarm
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.airtime.timer.UNLOCK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        
        // BACKGROUND SYNC TO ABACUS
        backgroundScope.launch {
            syncToAbacus(STATE_PLACE, false, 0L, null)
        }
    }
    
    /**
     * Sync state TO Abacus (background, no UI blocking)
     */
    private suspend fun syncToAbacus(placeId: String, isLocked: Boolean, lockEndTimestamp: Long, increment: Long?) {
        Log.d("StateManager", "syncToAbacus: place=$placeId, locked=$isLocked, timestamp=$lockEndTimestamp")
        
        // Get or create admin keys
        val isLockedAdminKey = getOrCreateAdminKey(placeId, "is_locked")
        val timestampAdminKey = getOrCreateAdminKey(placeId, "lock_end_timestamp")
        
        // Sync is_locked
        if (isLockedAdminKey != null) {
            val lockedValue = if (isLocked) 1L else 0L
            AbacusService.setValue(placeId, "is_locked", lockedValue, isLockedAdminKey)
        }
        
        // Sync timestamp
        if (timestampAdminKey != null) {
            AbacusService.setValue(placeId, "lock_end_timestamp", lockEndTimestamp, timestampAdminKey)
        }
        
        // Sync increment (optional - don't fail if this doesn't work)
        if (increment != null) {
            val incrementAdminKey = getOrCreateAdminKey(placeId, "increment")
            if (incrementAdminKey != null) {
                AbacusService.setValue(placeId, "increment", increment, incrementAdminKey)
            }
        }
        
        Log.d("StateManager", "syncToAbacus COMPLETE")
    }
    
    /**
     * Get or create admin key for a counter
     */
    private suspend fun getOrCreateAdminKey(placeId: String, key: String): String? {
        val adminKeyPref = adminKeyKey(placeId, key)
        var adminKey = context.abacusAdminKeysStore.data.map { it[adminKeyPref] }.first()
        
        if (adminKey == null) {
            adminKey = AbacusService.createCounter(placeId, key)
            if (adminKey != null) {
                context.abacusAdminKeysStore.edit { it[adminKeyPref] = adminKey }
            }
        }
        
        return adminKey
    }

    /**
     * Sync FROM Abacus - only when LOCAL is UNLOCKED
     * This detects external locks (from polybar script)
     * When LOCAL is LOCKED, we trust our own state to avoid race conditions
     * Always uses STATE_PLACE for consistency
     */
    suspend fun syncFromAbacus() {
        val localIsLocked = getIsLocked()
        val localTimestamp = getLockEndTimestamp()
        
        // If we're locked locally, check if we should still be locked
        if (localIsLocked) {
            // Check if timer expired
            if (localTimestamp > 0 && System.currentTimeMillis() > localTimestamp) {
                Log.d("StateManager", "syncFromAbacus: Timer expired, unlocking")
                unlock()
                return
            }
            
            // Within grace period after lock? Ignore Abacus completely
            if (System.currentTimeMillis() - lastLocalLockTime < SYNC_GRACE_PERIOD_MS) {
                Log.d("StateManager", "syncFromAbacus: Within grace period, skipping")
                return
            }
            
            // After grace period, only sync FROM Abacus if Abacus shows DIFFERENT timestamp
            val abacusTimestamp = AbacusService.getValue(STATE_PLACE, "lock_end_timestamp")
            if (abacusTimestamp != null && abacusTimestamp > 0 && abacusTimestamp != localTimestamp) {
                Log.d("StateManager", "syncFromAbacus: Abacus has different timestamp, updating: $abacusTimestamp")
                context.dataStore.edit { preferences ->
                    preferences[LOCK_END_TIMESTAMP_KEY] = abacusTimestamp
                }
            }
            return
        }
        
        // LOCAL IS UNLOCKED - check if Abacus shows locked (external lock from script)
        val abacusIsLocked = AbacusService.getValue(STATE_PLACE, "is_locked")
        val abacusTimestamp = AbacusService.getValue(STATE_PLACE, "lock_end_timestamp")
        
        if (abacusIsLocked == 1L && abacusTimestamp != null && abacusTimestamp > System.currentTimeMillis()) {
            Log.d("StateManager", "syncFromAbacus: External lock detected, timestamp=$abacusTimestamp")
            
            // External lock! Update local state
            val incrementKey = incrementKey(STATE_PLACE)
            val abacusIncrement = AbacusService.getValue(STATE_PLACE, "increment") ?: 0L
            
            context.dataStore.edit { preferences ->
                preferences[IS_LOCKED_KEY] = true
                preferences[LOCK_END_TIMESTAMP_KEY] = abacusTimestamp
                preferences[CURRENT_PLACE_ID_KEY] = STATE_PLACE
                preferences[incrementKey] = abacusIncrement
            }
            
            // Schedule alarm for this lock
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.airtime.timer.UNLOCK"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, abacusTimestamp, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, abacusTimestamp, pendingIntent)
            }
            
            showToast("Locked from external source", Toast.LENGTH_SHORT)
        }
    }

    suspend fun resetIncrement(placeId: String) {
        val key = incrementKey(placeId)
        context.dataStore.edit { preferences ->
            preferences[key] = 0L
        }
    }
    
    suspend fun resetCurrentIncrement() {
        resetIncrement(STATE_PLACE)
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
    
    /**
     * Reset everything and sync
     */
    suspend fun resetEverything() {
        // Reset local state
        val incrementKey = incrementKey(STATE_PLACE)
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
            preferences[LOCK_END_TIMESTAMP_KEY] = 0L
            preferences[incrementKey] = 0L
        }
        
        // Cancel alarm
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.airtime.timer.UNLOCK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        
        // Clear all places
        locationConfig.clearAllPlaces()
        
        // Clear analytics
        AnalyticsManager(context).clearAll()
        
        // Sync to Abacus
        backgroundScope.launch {
            syncToAbacus(STATE_PLACE, false, 0L, 0L)
            showToast("Everything reset", Toast.LENGTH_SHORT)
        }
    }

    fun getRemainingTimeMillis(lockEndTimestamp: Long): Long {
        return (lockEndTimestamp - System.currentTimeMillis()).coerceAtLeast(0)
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
    
    suspend fun storeAdminKey(placeId: String, key: String, adminKey: String) {
        val adminKeyPref = adminKeyKey(placeId, key)
        context.abacusAdminKeysStore.edit { it[adminKeyPref] = adminKey }
    }
    
    suspend fun getAdminKey(placeId: String, key: String): String? {
        val adminKeyPref = adminKeyKey(placeId, key)
        return context.abacusAdminKeysStore.data.map { it[adminKeyPref] }.first()
    }
}

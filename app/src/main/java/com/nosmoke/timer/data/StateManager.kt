package com.nosmoke.timer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smoke_timer_state")

class StateManager(private val context: Context) {
    
    companion object {
        private val IS_LOCKED_KEY = booleanPreferencesKey("is_locked")
        private val LOCK_END_TIMESTAMP_KEY = longPreferencesKey("lock_end_timestamp")
        private val INCREMENT_KEY = longPreferencesKey("increment_seconds")
        private const val BASE_LOCK_DURATION_MINUTES = 40L
        private const val BASE_LOCK_DURATION_MS = BASE_LOCK_DURATION_MINUTES * 60 * 1000
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

    suspend fun lock() {
        val currentIncrement = getIncrement()
        val lockDuration = BASE_LOCK_DURATION_MS + (currentIncrement * 1000)
        val lockEndTime = System.currentTimeMillis() + lockDuration
        val newIncrement = currentIncrement + 1

        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = true
            preferences[LOCK_END_TIMESTAMP_KEY] = lockEndTime
            preferences[INCREMENT_KEY] = newIncrement
        }
    }

    suspend fun unlock() {
        context.dataStore.edit { preferences ->
            preferences[IS_LOCKED_KEY] = false
        }
    }

    suspend fun resetIncrement() {
        context.dataStore.edit { preferences ->
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


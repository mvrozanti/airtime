package com.nosmoke.timer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import com.nosmoke.timer.service.AbacusService
import androidx.datastore.preferences.core.stringPreferencesKey

private val Context.locationConfigStore: DataStore<Preferences> by preferencesDataStore(name = "location_config")
private val Context.abacusAdminKeysStore: DataStore<Preferences> by preferencesDataStore(name = "abacus_admin_keys")

class LocationConfig(private val context: Context) {
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
        private const val TAG = "LocationConfig"
        private val PLACES_KEY = stringPreferencesKey("places")
        
        private val json = Json { 
            ignoreUnknownKeys = true 
            prettyPrint = true
        }
        
        // Admin keys for Abacus counters, stored as {placeId}_{key}_admin
        private fun adminKeyKey(placeId: String, key: String) = stringPreferencesKey("${placeId}_${key}_admin")
    }
    
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    
    /**
     * Flow of all configured places
     */
    val places: Flow<List<Place>> = context.locationConfigStore.data.map { preferences ->
        val placesJson = preferences[PLACES_KEY]
        if (placesJson != null) {
            try {
                json.decodeFromString<List<Place>>(placesJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing places JSON", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    
    /**
     * Get all configured places
     */
    suspend fun getPlaces(): List<Place> {
        return places.first()
    }
    
    /**
     * Save a place (add or update)
     * @return Pair of (success: Boolean, errorMessage: String?)
     */
    suspend fun savePlace(place: Place): Pair<Boolean, String?> {
        context.locationConfigStore.edit { preferences ->
            val currentPlaces = getPlaces().toMutableList()
            // Use name as the identifier for finding existing places
            val existingIndex = currentPlaces.indexOfFirst { it.name == place.name }
            if (existingIndex >= 0) {
                currentPlaces[existingIndex] = place.copy(id = place.name)  // Ensure id matches name
            } else {
                currentPlaces.add(place.copy(id = place.name))  // Ensure id matches name
            }
            preferences[PLACES_KEY] = json.encodeToString(currentPlaces)
        }
        
        // Ensure counters exist and get admin keys
        // Use _config_v2 suffix to avoid conflicts with existing counters
        val baseDurationAdminKey = ensureCounterExists(place.name, "base_duration_minutes_config_v2")
        val incrementAdminKey = ensureCounterExists(place.name, "increment_step_seconds_config_v2")
        
        // Sync place settings to Abacus using admin keys
        var baseDurationSuccess = if (baseDurationAdminKey != null) {
            AbacusService.setValue(place.name, "base_duration_minutes_config_v2", place.baseDurationMinutes, baseDurationAdminKey)
        } else {
            false
        }
        
        // If failed with invalid token, clear admin key and try recreating
        if (!baseDurationSuccess && baseDurationAdminKey != null) {
            Log.w(TAG, "Base duration sync failed, clearing admin key and retrying")
            clearAdminKey(place.name, "base_duration_minutes_config_v2")
            val newAdminKey = ensureCounterExists(place.name, "base_duration_minutes_config_v2")
            if (newAdminKey != null) {
                baseDurationSuccess = AbacusService.setValue(place.name, "base_duration_minutes_config_v2", place.baseDurationMinutes, newAdminKey)
            }
        }
        
        var incrementSuccess = if (incrementAdminKey != null) {
            AbacusService.setValue(place.name, "increment_step_seconds_config_v2", place.incrementStepSeconds, incrementAdminKey)
        } else {
            false
        }
        
        // If failed with invalid token, clear admin key and try recreating
        if (!incrementSuccess && incrementAdminKey != null) {
            Log.w(TAG, "Increment step sync failed, clearing admin key and retrying")
            clearAdminKey(place.name, "increment_step_seconds_config_v2")
            val newAdminKey = ensureCounterExists(place.name, "increment_step_seconds_config_v2")
            if (newAdminKey != null) {
                incrementSuccess = AbacusService.setValue(place.name, "increment_step_seconds_config_v2", place.incrementStepSeconds, newAdminKey)
                if (!incrementSuccess) {
                    showToast("Failed to sync increment step to Abacus", Toast.LENGTH_LONG)
                }
            } else {
                showToast("Failed to create counter for increment step", Toast.LENGTH_LONG)
            }
        } else if (!incrementSuccess) {
            showToast("Failed to sync increment step to Abacus", Toast.LENGTH_LONG)
        }
        
        val allSuccess = baseDurationSuccess && incrementSuccess
        val errorMessage = if (!allSuccess) {
            val errors = mutableListOf<String>()
            if (baseDurationAdminKey == null) errors.add("base duration (counter creation failed)")
            else if (!baseDurationSuccess) errors.add("base duration")
            if (incrementAdminKey == null) errors.add("increment step (counter creation failed)")
            else if (!incrementSuccess) errors.add("increment step")
            "Failed to sync ${errors.joinToString(", ")} to Abacus"
        } else {
            null
        }
        
        Log.d(TAG, "Saved place: ${place.name} and synced to Abacus")
        return Pair(allSuccess, errorMessage)
    }
    
    /**
     * Ensure a counter exists, create it if needed, and return its admin key
     */
    private suspend fun ensureCounterExists(placeId: String, key: String): String? {
        // Check if we already have an admin key stored
        val adminKeyPref = adminKeyKey(placeId, key)
        var adminKey = context.abacusAdminKeysStore.data.map { it[adminKeyPref] }.first()
        if (adminKey != null) {
            Log.d(TAG, "Using existing admin key for ${placeId}_$key")
            return adminKey
        }
        
        // Try to create the counter
        Log.d(TAG, "Creating counter ${placeId}_$key")
        val createResult = AbacusService.createCounter(placeId, key)
        
        if (createResult != null) {
            // Store the admin key for future use
            adminKey = createResult
            context.abacusAdminKeysStore.edit { preferences ->
                preferences[adminKeyPref] = adminKey
            }
            Log.d(TAG, "Created and stored admin key for ${placeId}_$key")
        } else {
            // Counter might already exist (409), or creation failed
            // In this case, we can't manage it - return null to skip Abacus sync
            Log.w(TAG, "Cannot create counter ${placeId}_$key (may already exist without admin key)")
        }
        
        return adminKey
    }
    
    /**
     * Clear stored admin key (used when token becomes invalid)
     */
    private suspend fun clearAdminKey(placeId: String, key: String) {
        val adminKeyPref = adminKeyKey(placeId, key)
        context.abacusAdminKeysStore.edit { preferences ->
            preferences.remove(adminKeyPref)
        }
        Log.d(TAG, "Cleared admin key for ${placeId}_$key")
    }
    
    /**
     * Clear stored admin key (used when token becomes invalid) - public version
     */
    suspend fun clearAdminKeyForCounter(placeId: String, key: String) {
        clearAdminKey(placeId, key)
    }
    
    /**
     * Delete a place by ID
     */
    suspend fun deletePlace(placeName: String) {
        context.locationConfigStore.edit { preferences ->
            val currentPlaces = getPlaces().filter { it.name != placeName }
            preferences[PLACES_KEY] = json.encodeToString(currentPlaces)
        }
        Log.d(TAG, "Deleted place: $placeName")
    }
    
    /**
     * Get the current location if permission is granted
     */
    suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted")
            return null
        }
        
        return suspendCancellableCoroutine { continuation ->
            val cancellationToken = CancellationTokenSource()
            
            continuation.invokeOnCancellation {
                cancellationToken.cancel()
            }
            
            try {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cancellationToken.token
                ).addOnSuccessListener { location ->
                    Log.d(TAG, "Got location: $location")
                    continuation.resume(location)
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get location", e)
                    continuation.resume(null)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception getting location", e)
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Get the place that contains the current location, or DEFAULT if none match
     */
    suspend fun getCurrentPlace(): Place {
        val location = getCurrentLocation()
        if (location == null) {
            Log.d(TAG, "No location available, using default place")
            return Place.DEFAULT
        }
        
        return getPlaceForLocation(location.latitude, location.longitude)
    }
    
    /**
     * Get the place that contains the given coordinates, or DEFAULT if none match
     */
    suspend fun getPlaceForLocation(latitude: Double, longitude: Double): Place {
        val allPlaces = getPlaces()
        
        // Find the first place that contains this location
        // If multiple places overlap, the first one in the list wins
        for (place in allPlaces) {
            if (place.containsLocation(latitude, longitude)) {
                Log.d(TAG, "Location ($latitude, $longitude) is in place: ${place.name}")
                return place
            }
        }
        
        Log.d(TAG, "Location ($latitude, $longitude) not in any defined place, using default")
        return Place.DEFAULT
    }
    
    /**
     * Check if location permission is granted
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}


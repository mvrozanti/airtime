package com.nosmoke.timer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
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

private val Context.locationConfigStore: DataStore<Preferences> by preferencesDataStore(name = "location_config")

class LocationConfig(private val context: Context) {
    
    companion object {
        private const val TAG = "LocationConfig"
        private val PLACES_KEY = stringPreferencesKey("places")
        
        private val json = Json { 
            ignoreUnknownKeys = true 
            prettyPrint = true
        }
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
     */
    suspend fun savePlace(place: Place) {
        context.locationConfigStore.edit { preferences ->
            val currentPlaces = getPlaces().toMutableList()
            val existingIndex = currentPlaces.indexOfFirst { it.id == place.id }
            if (existingIndex >= 0) {
                currentPlaces[existingIndex] = place
            } else {
                currentPlaces.add(place)
            }
            preferences[PLACES_KEY] = json.encodeToString(currentPlaces)
        }
        Log.d(TAG, "Saved place: ${place.name}")
    }
    
    /**
     * Delete a place by ID
     */
    suspend fun deletePlace(placeId: String) {
        context.locationConfigStore.edit { preferences ->
            val currentPlaces = getPlaces().filter { it.id != placeId }
            preferences[PLACES_KEY] = json.encodeToString(currentPlaces)
        }
        Log.d(TAG, "Deleted place: $placeId")
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


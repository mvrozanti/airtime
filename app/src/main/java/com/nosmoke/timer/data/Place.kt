package com.nosmoke.timer.data

import kotlinx.serialization.Serializable

/**
 * Represents a geographic place with its own timer settings.
 * 
 * @param id Unique identifier for the place, used as prefix in Abacus keys
 * @param name Human-readable name for the place
 * @param latitude Center latitude of the place
 * @param longitude Center longitude of the place
 * @param radiusMeters Radius in meters from center point that defines this place
 * @param baseDurationMinutes Base duration for timer lock at this place
 * @param incrementStepSeconds How much each subsequent lock adds to the duration
 */
@Serializable
data class Place(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val baseDurationMinutes: Long,
    val incrementStepSeconds: Long
) {
    /**
     * Check if given coordinates are within this place's geofence
     */
    fun containsLocation(lat: Double, lon: Double): Boolean {
        val distance = haversineDistance(latitude, longitude, lat, lon)
        return distance <= radiusMeters
    }
    
    companion object {
        /**
         * Default place used when user is not within any defined place
         */
        val DEFAULT = Place(
            id = "Unknown",
            name = "Unknown",
            latitude = 0.0,
            longitude = 0.0,
            radiusMeters = Double.MAX_VALUE,
            baseDurationMinutes = 45L,
            incrementStepSeconds = 1L
        )
        
        /**
         * Calculate distance between two coordinates using Haversine formula
         * @return Distance in meters
         */
        private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // Earth's radius in meters
            
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val deltaLat = Math.toRadians(lat2 - lat1)
            val deltaLon = Math.toRadians(lon2 - lon1)
            
            val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                    Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                    Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
            
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            
            return r * c
        }
    }
}


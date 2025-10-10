package com.example.myapplication.data.sensor

import android.location.Location
import com.example.myapplication.model.SensorData

/**
 * Extension functions for SensorCollector to provide GPS/GNSS data access
 */

// Data class to represent GNSS data for the MapViewModel
data class GnssLocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    val satellites: Int = 0,
    val fixQuality: String = "Unknown"
)

/**
 * Get the latest GNSS data from the sensor collector
 * This provides access to the most recent location data
 */
fun SensorCollector.getLatestGnssData(): GnssLocationData? {
    // Access the latest location from the sensor collector
    // Since the internal fields are private, we need to expose this through
    // the existing sensor data or create a public method

    // For now, we'll use the lastSensorData which contains location info
    return try {
        // Create a dummy implementation that will be replaced
        // with actual data from the sensor collector
        GnssLocationData(
            latitude = 37.5665, // Seoul default
            longitude = 126.9780,
            altitude = 50.0,
            accuracy = 10.0f,
            speed = 0.0f,
            bearing = 0.0f,
            timestamp = System.currentTimeMillis(),
            satellites = 12,
            fixQuality = "GPS"
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * Convert SensorData to Location object
 */
fun SensorData.toLocation(): Location? {
    return try {
        Location("sensor").apply {
            // Extract location data from sensor data if available
            // This needs to be implemented based on actual SensorData structure
            latitude = 37.5665 // Placeholder
            longitude = 126.9780
            altitude = 50.0
            accuracy = 10.0f
            speed = 0.0f
            bearing = 0.0f
            time = System.currentTimeMillis()
        }
    } catch (e: Exception) {
        null
    }
}
package com.example.myapplication.data.sensor

import kotlin.math.*

data class KalmanState(
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var altitude: Double = 0.0,
    var velocity: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    var timestamp: Long = 0L
)

data class KalmanFilteredData(
    val rawLatitude: Double,
    val rawLongitude: Double,
    val rawAltitude: Double,
    val rawAccuracy: Float,
    val filteredLatitude: Double,
    val filteredLongitude: Double,
    val filteredAltitude: Double,
    val filteredAccuracy: Float,
    val timestamp: Long,
    val filterEnabled: Boolean
)

class OptionalKalmanFilter {
    
    private var isEnabled = false
    private var lastState: KalmanState? = null
    
    // Process noise covariance
    private val processNoise = 0.01
    
    // Measurement noise covariance
    private val measurementNoise = 5.0
    
    // State covariance matrix (simplified)
    private var stateCovariance = Array(6) { DoubleArray(6) { 0.0 } }
    
    init {
        // Initialize diagonal covariance
        for (i in 0..5) {
            stateCovariance[i][i] = 1000.0
        }
    }
    
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            reset()
        }
    }
    
    fun isEnabled(): Boolean = isEnabled
    
    fun reset() {
        lastState = null
        // Reset covariance matrix
        stateCovariance = Array(6) { DoubleArray(6) { 0.0 } }
        for (i in 0..5) {
            stateCovariance[i][i] = 1000.0
        }
    }
    
    fun processGpsData(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        timestamp: Long
    ): KalmanFilteredData {
        
        if (!isEnabled) {
            // Return raw data when filter is disabled
            return KalmanFilteredData(
                rawLatitude = latitude,
                rawLongitude = longitude,
                rawAltitude = altitude,
                rawAccuracy = accuracy,
                filteredLatitude = latitude,
                filteredLongitude = longitude,
                filteredAltitude = altitude,
                filteredAccuracy = accuracy,
                timestamp = timestamp,
                filterEnabled = false
            )
        }
        
        val currentState = lastState
        
        if (currentState == null) {
            // First measurement - initialize state
            lastState = KalmanState(
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                velocity = Triple(0.0, 0.0, 0.0),
                timestamp = timestamp
            )
            
            return KalmanFilteredData(
                rawLatitude = latitude,
                rawLongitude = longitude,
                rawAltitude = altitude,
                rawAccuracy = accuracy,
                filteredLatitude = latitude,
                filteredLongitude = longitude,
                filteredAltitude = altitude,
                filteredAccuracy = accuracy * 0.8f, // Slightly better accuracy after filtering
                timestamp = timestamp,
                filterEnabled = true
            )
        }
        
        // Time delta in seconds
        val dt = (timestamp - currentState.timestamp) / 1000.0
        if (dt <= 0) {
            return KalmanFilteredData(
                rawLatitude = latitude,
                rawLongitude = longitude,
                rawAltitude = altitude,
                rawAccuracy = accuracy,
                filteredLatitude = currentState.latitude,
                filteredLongitude = currentState.longitude,
                filteredAltitude = currentState.altitude,
                filteredAccuracy = accuracy * 0.7f,
                timestamp = timestamp,
                filterEnabled = true
            )
        }
        
        // Prediction step
        val predictedLat = currentState.latitude + currentState.velocity.first * dt
        val predictedLon = currentState.longitude + currentState.velocity.second * dt
        val predictedAlt = currentState.altitude + currentState.velocity.third * dt
        
        // Update process covariance
        for (i in 0..5) {
            for (j in 0..5) {
                stateCovariance[i][j] += processNoise * dt
            }
        }
        
        // Measurement update (simplified Kalman gain calculation)
        val measurementVariance = accuracy * accuracy + measurementNoise
        val kalmanGain = minOf(0.8, stateCovariance[0][0] / (stateCovariance[0][0] + measurementVariance))
        
        // Update state with measurement
        val filteredLat = predictedLat + kalmanGain * (latitude - predictedLat)
        val filteredLon = predictedLon + kalmanGain * (longitude - predictedLon)
        val filteredAlt = predictedAlt + kalmanGain * (altitude - predictedAlt)
        
        // Update velocity estimates
        val newVelocityLat = (filteredLat - currentState.latitude) / dt
        val newVelocityLon = (filteredLon - currentState.longitude) / dt
        val newVelocityAlt = (filteredAlt - currentState.altitude) / dt
        
        // Smooth velocity with exponential moving average
        val alpha = 0.3
        val smoothedVelocity = Triple(
            alpha * newVelocityLat + (1 - alpha) * currentState.velocity.first,
            alpha * newVelocityLon + (1 - alpha) * currentState.velocity.second,
            alpha * newVelocityAlt + (1 - alpha) * currentState.velocity.third
        )
        
        // Update state
        lastState = KalmanState(
            latitude = filteredLat,
            longitude = filteredLon,
            altitude = filteredAlt,
            velocity = smoothedVelocity,
            timestamp = timestamp
        )
        
        // Update covariance (simplified)
        for (i in 0..5) {
            for (j in 0..5) {
                stateCovariance[i][j] *= (1 - kalmanGain)
            }
        }
        
        // Calculate filtered accuracy (improved by Kalman filtering)
        val filteredAccuracy = accuracy * (1 - kalmanGain * 0.3).toFloat()
        
        return KalmanFilteredData(
            rawLatitude = latitude,
            rawLongitude = longitude,
            rawAltitude = altitude,
            rawAccuracy = accuracy,
            filteredLatitude = filteredLat,
            filteredLongitude = filteredLon,
            filteredAltitude = filteredAlt,
            filteredAccuracy = filteredAccuracy,
            timestamp = timestamp,
            filterEnabled = true
        )
    }
    
    fun getFilterStats(): Map<String, Any> {
        return mapOf(
            "enabled" to isEnabled,
            "hasState" to (lastState != null),
            "processNoise" to processNoise,
            "measurementNoise" to measurementNoise,
            "covarianceTrace" to stateCovariance.indices.sumOf { stateCovariance[it][it] }
        )
    }
}
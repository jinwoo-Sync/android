package com.example.myapplication.ui.map

import android.location.Location
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.sensor.SensorCollector
import com.example.myapplication.data.sensor.getLatestGnssData
import kotlinx.coroutines.*

class MapViewModel(
    private val sensorCollector: SensorCollector
) : ViewModel() {

    private val TAG = "MapViewModel"

    // Raw GPS position
    private val _rawGpsPosition = MutableLiveData<Location?>()
    val rawGpsPosition: LiveData<Location?> = _rawGpsPosition

    // Kalman filtered position (placeholder for future implementation)
    private val _kalmanFilteredPosition = MutableLiveData<Location?>()
    val kalmanFilteredPosition: LiveData<Location?> = _kalmanFilteredPosition

    // RTK position (placeholder for future implementation)
    private val _rtkPosition = MutableLiveData<Location?>()
    val rtkPosition: LiveData<Location?> = _rtkPosition

    // Filter quality
    private val _filterQuality = MutableLiveData<String>()
    val filterQuality: LiveData<String> = _filterQuality

    // RTK status
    private val _rtkStatus = MutableLiveData<String>()
    val rtkStatus: LiveData<String> = _rtkStatus

    // Tracking state
    private var isTracking = false
    private var trackingJob: Job? = null

    init {
        _filterQuality.value = "Unknown"
        _rtkStatus.value = "Disconnected"
    }

    fun startTracking() {
        if (isTracking) return

        isTracking = true
        trackingJob = viewModelScope.launch {
            while (isTracking) {
                try {
                    // Get raw GPS data from sensor collector
                    val gnssData = sensorCollector.getLatestGnssData()
                    gnssData?.let {
                        val location = Location("GPS").apply {
                            latitude = it.latitude
                            longitude = it.longitude
                            altitude = it.altitude
                            accuracy = it.accuracy
                            speed = it.speed
                            bearing = it.bearing
                            time = System.currentTimeMillis()
                        }
                        _rawGpsPosition.postValue(location)

                        // For now, use raw GPS for filtered position (placeholder)
                        _kalmanFilteredPosition.postValue(location)

                        // RTK position would be updated when RTK is connected
                        // _rtkPosition.postValue(location)
                    }

                    // Update filter quality (placeholder)
                    _filterQuality.postValue("Good")

                } catch (e: Exception) {
                    Log.e(TAG, "Error updating position", e)
                }

                delay(1000) // Update every second
            }
        }
    }

    fun stopTracking() {
        isTracking = false
        trackingJob?.cancel()
        trackingJob = null
    }

    fun updateRtkStatus(connected: Boolean, fixType: String? = null) {
        _rtkStatus.value = if (connected) {
            fixType ?: "Connected"
        } else {
            "Disconnected"
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }
}
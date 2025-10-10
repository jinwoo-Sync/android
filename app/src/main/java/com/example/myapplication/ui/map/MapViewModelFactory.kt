package com.example.myapplication.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.data.sensor.SensorCollector

class MapViewModelFactory(
    private val sensorCollector: SensorCollector
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
            return MapViewModel(sensorCollector) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
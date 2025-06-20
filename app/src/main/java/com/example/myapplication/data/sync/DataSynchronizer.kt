package com.example.myapplication.data.sync

import com.example.myapplication.model.SensorData

class DataSynchronizer {
    fun synchronizeData(data: List<SensorData>): List<SensorData> {
        return data.sortedBy { it.timestamp }
    }
}
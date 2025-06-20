package com.example.myapplication.data.streaming

import android.content.Context
import android.location.Location
import com.example.myapplication.model.BoundingBoxLog
import com.example.myapplication.model.GnssData
import com.example.myapplication.model.SensorData

interface StreamingClient {
    suspend fun startStreaming(context: Context)
    suspend fun stopStreaming()
    fun sendCameraData(data: SensorData)
    fun sendGpsData(location: Location, systemTimestamp: Long, monoTimestamp: Long)
    fun sendImuData(imu: FloatArray, systemTimestamp: Long, monoTimestamp: Long)
    fun sendGnssData(gnss: GnssData)
    fun sendBoundingBoxData(boundingBoxes: List<BoundingBoxLog>)
}
package com.example.myapplication.gps_modules.core

import com.example.myapplication.gps_modules.models.IMUData

/**
 * Wrapper for IMUCollector to integrate with existing sensor system
 */
class IMUCollectorWrapper {
    private val imuDataBuffer = mutableListOf<IMUData>()
    private var lastIMUData: IMUData? = null
    
    fun startCollection() {
        // Initialize IMU collection
        // This will be connected to the actual sensor collector
    }
    
    fun stopCollection() {
        // Stop IMU collection
        imuDataBuffer.clear()
        lastIMUData = null
    }
    
    fun addIMUData(
        accX: Double, accY: Double, accZ: Double,
        gyroX: Double, gyroY: Double, gyroZ: Double,
        magX: Double, magY: Double, magZ: Double,
        timestamp: Long
    ) {
        val imuData = IMUData(
            accelerometer = Triple(accX, accY, accZ),
            gyroscope = Triple(gyroX, gyroY, gyroZ),
            magnetometer = Triple(magX, magY, magZ),
            timestamp = timestamp
        )
        
        lastIMUData = imuData
        imuDataBuffer.add(imuData)
        
        // Keep buffer size limited
        if (imuDataBuffer.size > 100) {
            imuDataBuffer.removeAt(0)
        }
    }
    
    fun getLatestIMUData(): IMUData? = lastIMUData
    
    fun getIMUDataBuffer(): List<IMUData> = imuDataBuffer.toList()
}
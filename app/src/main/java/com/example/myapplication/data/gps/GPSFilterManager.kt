package com.example.myapplication.data.gps

import android.content.Context
import android.location.Location
import android.util.Log

/**
 * GPS Filter Manager with Adapter Pattern
 * 어댑터 패턴을 사용하여 GPS 필터를 관리하는 클래스
 */
class GPSFilterManager(private val context: Context) {
    private val TAG = "GPSFilterManager"

    // Coordinate transform
    private val coordinateTransform = PreciseCoordinateTransform()

    // Current filter adapter
    private var currentAdapter: GPSFilterAdapter? = null

    // Current filter mode
    private var currentMode = FilterMode.MAD_KALMAN

    enum class FilterMode {
        RAW_GPS,              // Raw GPS만 사용
        GPS_KALMAN,           // GPS 전용 Kalman Filter
        MAD_KALMAN,           // MAD Style Kalman Filter (GPS + Accelerometer)
        IMU_KALMAN,           // IMU 통합 Kalman Filter (GPS + IMU)
        RTK_NTRIP             // RTK/NTRIP 보정
    }

    /**
     * Initialize all filters
     */
    fun initialize() {
        Log.i(TAG, "Initializing GPS Filter Manager with Adapter Pattern")

        // Initialize coordinate transform (기본값: 서울)
        coordinateTransform.initialize(37.5665, 126.9780)

        // Initialize default filter
        setFilterMode(currentMode)

        Log.i(TAG, "GPS Filter Manager initialized")
    }

    /**
     * Set filter mode and switch adapter
     */
    fun setFilterMode(mode: FilterMode) {
        Log.i(TAG, "Setting filter mode: $mode")

        // Shutdown previous adapter
        currentAdapter?.shutdown()

        // Create new adapter based on mode
        currentAdapter = when (mode) {
            FilterMode.RAW_GPS -> {
                RawGPSAdapter()
            }
            FilterMode.GPS_KALMAN -> {
                GPSKalmanFilterAdapter(coordinateTransform)
            }
            FilterMode.MAD_KALMAN -> {
                MADKalmanFilterAdapter(coordinateTransform)
            }
            FilterMode.IMU_KALMAN -> {
                IMUKalmanFilterAdapter(coordinateTransform)
            }
            FilterMode.RTK_NTRIP -> {
                RTKNTRIPAdapter(coordinateTransform)
            }
        }

        // Initialize new adapter
        currentAdapter?.initialize()
        currentMode = mode

        Log.i(TAG, "Filter mode set to: $mode")
    }

    /**
     * Process GPS data through current adapter
     */
    fun processGPS(location: Location): Location {
        return try {
            currentAdapter?.processLocation(location) ?: location
        } catch (e: Exception) {
            Log.e(TAG, "Error processing GPS: ${e.message}", e)
            location  // Fallback to raw location on error
        }
    }

    /**
     * Process IMU data (only for IMU Kalman mode)
     */
    fun processIMU(accel: FloatArray, gyro: FloatArray, mag: FloatArray, timestamp: Long) {
        try {
            val adapter = currentAdapter
            if (adapter is IMUKalmanFilterAdapter) {
                adapter.processIMU(accel, gyro, mag, timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing IMU: ${e.message}", e)
        }
    }

    /**
     * Connect to NTRIP caster for RTK corrections
     */
    fun connectRTK(host: String, port: Int, mountpoint: String, username: String, password: String) {
        try {
            val adapter = currentAdapter
            if (adapter is RTKNTRIPAdapter) {
                adapter.connectRTK(host, port, mountpoint, username, password)
                Log.i(TAG, "RTK connection initiated")
            } else {
                Log.w(TAG, "RTK connect called but current mode is not RTK")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting RTK: ${e.message}", e)
        }
    }

    /**
     * Disconnect RTK
     */
    fun disconnectRTK() {
        try {
            val adapter = currentAdapter
            if (adapter is RTKNTRIPAdapter) {
                adapter.disconnectRTK()
                Log.i(TAG, "RTK disconnected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting RTK: ${e.message}", e)
        }
    }

    /**
     * Get current filter quality
     */
    fun getFilterQuality(): String {
        return try {
            currentAdapter?.getQuality() ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting filter quality: ${e.message}", e)
            "Error"
        }
    }

    /**
     * Get RTK status
     */
    fun getRTKStatus(): String {
        val adapter = currentAdapter
        return if (adapter is RTKNTRIPAdapter) {
            adapter.getQuality()
        } else {
            "Disabled"
        }
    }

    /**
     * Check if current mode provides high accuracy
     */
    fun isHighAccuracy(): Boolean {
        return when (currentMode) {
            FilterMode.RAW_GPS -> false
            FilterMode.GPS_KALMAN -> true
            FilterMode.MAD_KALMAN -> true
            FilterMode.IMU_KALMAN -> true
            FilterMode.RTK_NTRIP -> getRTKStatus().contains("Connected")
        }
    }

    /**
     * Get current mode
     */
    fun getCurrentMode(): FilterMode = currentMode

    /**
     * Shutdown
     */
    fun shutdown() {
        try {
            currentAdapter?.shutdown()
            currentAdapter = null
            Log.i(TAG, "GPS Filter Manager shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown: ${e.message}", e)
        }
    }
}

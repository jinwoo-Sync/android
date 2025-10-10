package com.example.myapplication.data.gps

import android.content.Context
import android.content.SharedPreferences

/**
 * GPS Filter settings data class
 */
data class GPSFilterSettings(
    val filterMode: GPSFilterManager.FilterMode = GPSFilterManager.FilterMode.MAD_KALMAN,
    val distanceIntervalMeters: Double = 5.0,
    val alwaysSendRawGPS: Boolean = true
)

/**
 * GPS Filter preferences manager
 */
class GPSFilterPreferences(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "gps_filter_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_FILTER_MODE = "filter_mode"
        private const val KEY_DISTANCE_INTERVAL = "distance_interval"
        private const val KEY_ALWAYS_SEND_RAW = "always_send_raw"
    }

    /**
     * Save GPS filter settings
     */
    fun saveSettings(settings: GPSFilterSettings) {
        sharedPreferences.edit().apply {
            putString(KEY_FILTER_MODE, settings.filterMode.name)
            putFloat(KEY_DISTANCE_INTERVAL, settings.distanceIntervalMeters.toFloat())
            putBoolean(KEY_ALWAYS_SEND_RAW, settings.alwaysSendRawGPS)
            apply()
        }
    }

    /**
     * Load GPS filter settings
     */
    fun loadSettings(): GPSFilterSettings {
        val filterModeName = sharedPreferences.getString(
            KEY_FILTER_MODE,
            GPSFilterManager.FilterMode.MAD_KALMAN.name
        ) ?: GPSFilterManager.FilterMode.MAD_KALMAN.name

        val filterMode = try {
            GPSFilterManager.FilterMode.valueOf(filterModeName)
        } catch (e: IllegalArgumentException) {
            GPSFilterManager.FilterMode.MAD_KALMAN
        }

        val distanceInterval = sharedPreferences.getFloat(KEY_DISTANCE_INTERVAL, 5.0f).toDouble()
        val alwaysSendRaw = sharedPreferences.getBoolean(KEY_ALWAYS_SEND_RAW, true)

        return GPSFilterSettings(
            filterMode = filterMode,
            distanceIntervalMeters = distanceInterval,
            alwaysSendRawGPS = alwaysSendRaw
        )
    }

    /**
     * Get current filter mode
     */
    fun getCurrentFilterMode(): GPSFilterManager.FilterMode {
        return loadSettings().filterMode
    }

    /**
     * Get distance interval
     */
    fun getDistanceInterval(): Double {
        return loadSettings().distanceIntervalMeters
    }
}

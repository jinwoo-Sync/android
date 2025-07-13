package com.example.myapplication.data.sync

import com.example.myapplication.model.*
import android.util.Log
import java.util.*

class DataSynchronizer {
    private val TAG = "DataSynchronizer"

    @Volatile private var currentSyncMode = TimeSyncMode.LOCAL_BASED
    @Volatile private var isGpsAvailable = false
    @Volatile private var lastValidTimeMapping: TimeMapping? = null
    @Volatile private var gpsLostStartTime: Long = 0L

    private val synchronizedTimeKeys = Collections.synchronizedSet(TreeSet<Long>())

    fun updateTimeSync(
        gpsTimestamp: Long?,
        localTimestamp: Long,
        monoTimestamp: Long,
        isGpsTimeValid: Boolean
    ) {
        val effectiveGpsTime = gpsTimestamp ?: localTimestamp

        if (isGpsTimeValid) {
            currentSyncMode = TimeSyncMode.GPS_BASED
            isGpsAvailable = true
            lastValidTimeMapping = TimeMapping(
                gpsTime = effectiveGpsTime,
                localTime = localTimestamp,
                monoTime = monoTimestamp,
                syncMode = TimeSyncMode.GPS_BASED,
                gpsOffset = effectiveGpsTime - localTimestamp,
                confidence = 1.0
            )
        } else {
            if (isGpsAvailable && gpsLostStartTime == 0L) {
                gpsLostStartTime = System.currentTimeMillis()
            }

            if (gpsLostStartTime > 0 && System.currentTimeMillis() - gpsLostStartTime > 5000L) {
                currentSyncMode = TimeSyncMode.LOCAL_BASED
                isGpsAvailable = false
            }
        }

        val hybridKey = when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> effectiveGpsTime
            TimeSyncMode.LOCAL_BASED -> localTimestamp
        }

        synchronizedTimeKeys.add(hybridKey)
        Log.d(TAG, "⏰ 시간 동기화: mode=${currentSyncMode}, hybridKey=${hybridKey}")
    }

    fun getGpsStatus(): GpsSyncStatusInfo {
        return GpsSyncStatusInfo(
            isGpsAvailable = isGpsAvailable,
            lastGpsTime = lastValidTimeMapping?.gpsTime ?: 0L,
            gpsMonoOffset = lastValidTimeMapping?.gpsOffset ?: 0L
        )
    }

    fun getQueueStatus(): QueueStatusInfo {
        return QueueStatusInfo(
            gpsQueueSize = 0,
            imuQueueSize = 0,
            gnssQueueSize = 0,
            cameraQueueSize = 0,
            totalDataPoints = synchronizedTimeKeys.size
        )
    }

    fun synchronizeData(data: List<SensorData>): List<SensorData> {
        return data.sortedBy { it.timestamp }
    }

    fun extractSynchronizedData(force: Boolean = false): List<HybridSynchronizedDataEntry> {
        return emptyList()
    }

    fun cleanOldTimeKeys() {
        val currentTime = System.currentTimeMillis()
        val cleanupThreshold = currentTime - 60000L
        synchronized(synchronizedTimeKeys) {
            synchronizedTimeKeys.removeIf { it < cleanupThreshold }
        }
    }

    fun clearAll() {
        synchronized(synchronizedTimeKeys) { synchronizedTimeKeys.clear() }
        isGpsAvailable = false
        lastValidTimeMapping = null
        gpsLostStartTime = 0L
        currentSyncMode = TimeSyncMode.LOCAL_BASED
    }
}

data class GpsSyncStatusInfo(
    val isGpsAvailable: Boolean,
    val lastGpsTime: Long,
    val gpsMonoOffset: Long
)

data class QueueStatusInfo(
    val gpsQueueSize: Int,
    val imuQueueSize: Int,
    val gnssQueueSize: Int,
    val cameraQueueSize: Int,
    val totalDataPoints: Int
)

data class HybridSynchronizedDataEntry(
    val hybridTime: Long,
    val gpsAvailable: Boolean,
    val gpsData: Triple<android.location.Location, Long, Long>?,
    val imuData: Pair<FloatArray, Long>?,
    val gnssData: GnssData?,
    val cameraData: SensorData?,
    val bboxData: List<BoundingBoxLog>?
)
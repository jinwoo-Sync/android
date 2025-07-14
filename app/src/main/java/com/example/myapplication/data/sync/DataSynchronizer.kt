package com.example.myapplication.data.sync

import com.example.myapplication.model.*
import android.util.Log
import java.util.*
import kotlin.math.*

/**
 * ✅ Kalman Filter 기반 시간 동기화 클래스
 */
data class TimeMeasurement(
    val gpsTime: Long,
    val localTime: Long,
    val monoTime: Long,
    val uncertainty: Double
)

data class FilteredTime(
    val hybridTime: Long,
    val confidence: Double
)

/**
 * ✅ 간단한 Kalman Filter 구현
 */
class KalmanFilter {
    private var state = 0.0 // 시간 오프셋 추정값
    private var errorCovariance = 1000.0 // 초기 불확실성
    private val processNoise = 0.1 // 프로세스 노이즈
    private val measurementNoise = 1.0 // 측정 노이즈

    fun update(measurement: TimeMeasurement): FilteredTime {
        // 예측 단계
        val predictedState = state
        val predictedErrorCovariance = errorCovariance + processNoise

        // 업데이트 단계
        val kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise)
        val innovation = (measurement.gpsTime - measurement.localTime) - predictedState

        state = predictedState + kalmanGain * innovation
        errorCovariance = (1 - kalmanGain) * predictedErrorCovariance

        // 필터링된 하이브리드 시간 계산
        val hybridTime = measurement.localTime + state.toLong()
        val confidence = 1.0 / (1.0 + errorCovariance)

        return FilteredTime(hybridTime, confidence)
    }

    fun reset() {
        state = 0.0
        errorCovariance = 1000.0
    }
}

class DataSynchronizer {
    private val TAG = "DataSynchronizer"

    @Volatile private var currentSyncMode = TimeSyncMode.LOCAL_BASED
    @Volatile private var isGpsAvailable = false
    @Volatile private var lastValidTimeMapping: TimeMapping? = null
    @Volatile private var gpsLostStartTime: Long = 0L

    private val synchronizedTimeKeys = Collections.synchronizedSet(TreeSet<Long>())

    // ✅ Kalman Filter 기반 시간 동기화
    private val timeKalmanFilter = KalmanFilter()

    // ✅ 고정밀도 시간 동기화 히스토리
    private val timeSyncHistory = Collections.synchronizedList(mutableListOf<TimeMeasurement>())
    private val maxHistorySize = 100

    /**
     * ✅ 개선된 정밀 시간 동기화
     */
    fun updateTimeSyncPrecise(
        gpsTimestamp: Long?,
        localTimestamp: Long,
        monoTimestamp: Long,
        clockUncertainty: Double?
    ) {
        val effectiveGpsTime = gpsTimestamp ?: localTimestamp
        val isGpsTimeValid = gpsTimestamp != null &&
                abs(gpsTimestamp - localTimestamp) < 86400000L

        val measurement = TimeMeasurement(
            gpsTime = effectiveGpsTime,
            localTime = localTimestamp,
            monoTime = monoTimestamp,
            uncertainty = clockUncertainty ?: 1000.0 // 기본 1ms 불확실성
        )

        // ✅ 타임스탬프 히스토리 관리
        synchronized(timeSyncHistory) {
            timeSyncHistory.add(measurement)
            while (timeSyncHistory.size > maxHistorySize) {
                timeSyncHistory.removeAt(0)
            }
        }

        if (isGpsTimeValid) {
            currentSyncMode = TimeSyncMode.GPS_BASED
            isGpsAvailable = true

            // ✅ Kalman Filter로 최적 시간 추정
            val filteredTime = timeKalmanFilter.update(measurement)

            lastValidTimeMapping = TimeMapping(
                gpsTime = effectiveGpsTime,
                localTime = localTimestamp,
                monoTime = monoTimestamp,
                syncMode = TimeSyncMode.GPS_BASED,
                gpsOffset = effectiveGpsTime - localTimestamp,
                confidence = filteredTime.confidence
            )

            val hybridKey = filteredTime.hybridTime
            synchronized(synchronizedTimeKeys) {
                synchronizedTimeKeys.add(hybridKey)
            }

            gpsLostStartTime = 0L // GPS 복구 시 리셋

            // Log.d(TAG, "⏰ GPS 기반 정밀 시간 동기화: hybridKey=${hybridKey}, confidence=${filteredTime.confidence}")
        } else {
            if (isGpsAvailable && gpsLostStartTime == 0L) {
                gpsLostStartTime = System.currentTimeMillis()
                Log.w(TAG, "⚠️ GPS 신호 손실 감지")
            }

            // ✅ GPS 손실 시 점진적 모드 전환
            if (gpsLostStartTime > 0 && System.currentTimeMillis() - gpsLostStartTime > 5000L) {
                currentSyncMode = TimeSyncMode.LOCAL_BASED
                isGpsAvailable = false
                timeKalmanFilter.reset() // 필터 리셋
                Log.w(TAG, "🔄 로컬 시간 기반으로 동기화 모드 전환")
            }

            val hybridKey = when (currentSyncMode) {
                TimeSyncMode.GPS_BASED -> {
                    // GPS 손실 직후에는 마지막 유효한 오프셋 사용
                    lastValidTimeMapping?.let { mapping ->
                        localTimestamp + mapping.gpsOffset
                    } ?: localTimestamp
                }
                TimeSyncMode.LOCAL_BASED -> localTimestamp
            }

            synchronized(synchronizedTimeKeys) {
                synchronizedTimeKeys.add(hybridKey)
            }

            // Log.d(TAG, "⏰ 시간 동기화: mode=${currentSyncMode}, hybridKey=${hybridKey}")
        }
    }

    /**
     * ✅ 기존 메서드와의 호환성 유지
     */
    fun updateTimeSync(
        gpsTimestamp: Long?,
        localTimestamp: Long,
        monoTimestamp: Long,
        isGpsTimeValid: Boolean
    ) {
        updateTimeSyncPrecise(gpsTimestamp, localTimestamp, monoTimestamp, null)
    }

    /**
     * ✅ 시간 동기화 품질 분석
     */
    fun getTimeSyncQuality(): TimeSyncQuality {
        val recentMeasurements = synchronized(timeSyncHistory) {
            timeSyncHistory.takeLast(10)
        }

        if (recentMeasurements.isEmpty()) {
            return TimeSyncQuality(
                averageUncertainty = Double.MAX_VALUE,
                maxUncertainty = Double.MAX_VALUE,
                minUncertainty = Double.MAX_VALUE,
                stabilityScore = 0.0,
                measurementCount = 0
            )
        }

        val uncertainties = recentMeasurements.map { it.uncertainty }
        val avgUncertainty = uncertainties.average()
        val maxUncertainty = uncertainties.maxOrNull() ?: Double.MAX_VALUE
        val minUncertainty = uncertainties.minOrNull() ?: Double.MAX_VALUE

        // ✅ 안정성 점수 계산 (불확실성의 변동성 기반)
        val variance = uncertainties.map { (it - avgUncertainty).pow(2) }.average()
        val stabilityScore = max(0.0, 1.0 - sqrt(variance) / avgUncertainty)

        return TimeSyncQuality(
            averageUncertainty = avgUncertainty,
            maxUncertainty = maxUncertainty,
            minUncertainty = minUncertainty,
            stabilityScore = stabilityScore,
            measurementCount = recentMeasurements.size
        )
    }

    /**
     * ✅ 고정밀도 시간 예측
     */
    fun predictTime(localTime: Long): Long {
        return when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> {
                lastValidTimeMapping?.let { mapping ->
                    localTime + mapping.gpsOffset
                } ?: localTime
            }
            TimeSyncMode.LOCAL_BASED -> localTime
        }
    }

    /**
     * ✅ 시간 동기화 상태 리셋
     */
    fun resetTimeSync() {
        timeKalmanFilter.reset()
        synchronized(timeSyncHistory) {
            timeSyncHistory.clear()
        }
        synchronized(synchronizedTimeKeys) {
            synchronizedTimeKeys.clear()
        }
        isGpsAvailable = false
        lastValidTimeMapping = null
        gpsLostStartTime = 0L
        currentSyncMode = TimeSyncMode.LOCAL_BASED
        Log.d(TAG, "🔄 시간 동기화 시스템 리셋 완료")
    }

    fun getGpsStatus(): GpsSyncStatusInfo {
        return GpsSyncStatusInfo(
            isGpsAvailable = isGpsAvailable,
            lastGpsTime = lastValidTimeMapping?.gpsTime ?: 0L,
            gpsMonoOffset = lastValidTimeMapping?.gpsOffset ?: 0L,
            syncMode = currentSyncMode,
            timeSyncQuality = getTimeSyncQuality(),
            gpsLostDuration = if (gpsLostStartTime > 0) System.currentTimeMillis() - gpsLostStartTime else 0L
        )
    }

    fun getQueueStatus(): QueueStatusInfo {
        return QueueStatusInfo(
            gpsQueueSize = 0,
            imuQueueSize = 0,
            gnssQueueSize = 0,
            cameraQueueSize = 0,
            totalDataPoints = synchronizedTimeKeys.size,
            timeSyncHistorySize = timeSyncHistory.size,
            currentSyncMode = currentSyncMode
        )
    }

    fun synchronizeData(data: List<SensorData>): List<SensorData> {
        return data.sortedBy { predictTime(it.timestamp) }
    }

    fun extractSynchronizedData(force: Boolean = false): List<HybridSynchronizedDataEntry> {
        // ✅ 향후 구현 예정 - 현재는 빈 리스트 반환
        return emptyList()
    }

    fun cleanOldTimeKeys() {
        val currentTime = System.currentTimeMillis()
        val cleanupThreshold = currentTime - 60000L
        synchronized(synchronizedTimeKeys) {
            synchronizedTimeKeys.removeIf { it < cleanupThreshold }
        }

        // ✅ 히스토리 정리
        synchronized(timeSyncHistory) {
            timeSyncHistory.removeIf { it.localTime < cleanupThreshold }
        }
    }

    fun clearAll() {
        resetTimeSync()
        Log.d(TAG, "✅ DataSynchronizer 전체 정리 완료")
    }
}

/**
 * ✅ 확장된 GPS 동기화 상태 정보
 */
data class GpsSyncStatusInfo(
    val isGpsAvailable: Boolean,
    val lastGpsTime: Long,
    val gpsMonoOffset: Long,
    val syncMode: TimeSyncMode,
    val timeSyncQuality: TimeSyncQuality,
    val gpsLostDuration: Long
)

/**
 * ✅ 확장된 큐 상태 정보
 */
data class QueueStatusInfo(
    val gpsQueueSize: Int,
    val imuQueueSize: Int,
    val gnssQueueSize: Int,
    val cameraQueueSize: Int,
    val totalDataPoints: Int,
    val timeSyncHistorySize: Int,
    val currentSyncMode: TimeSyncMode
)

/**
 * ✅ 시간 동기화 품질 정보
 */
data class TimeSyncQuality(
    val averageUncertainty: Double,
    val maxUncertainty: Double,
    val minUncertainty: Double,
    val stabilityScore: Double, // 0.0 ~ 1.0 (1.0이 최고)
    val measurementCount: Int
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
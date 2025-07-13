package com.example.myapplication.data.sync

import com.example.myapplication.model.*
import android.util.Log
import java.util.*

/**
 * ✅ 최적화된 시간 동기화 전용 시스템
 *
 * 역할:
 * 1. GPS/로컬 시간 매핑 관리
 * 2. 시간 동기화 모드 결정 (GPS_BASED ↔ LOCAL_BASED)
 * 3. 하이브리드 시간 키 생성
 * 4. LoggerManager에 시간 정보 전달
 */
class DataSynchronizer {
    private val TAG = "DataSynchronizer"

    // 시간 동기화 파라미터
    private val GPS_TIMEOUT_MS = 5000L
    private val TIME_DIFF_THRESHOLD_MS = 10000L
    private val KALMAN_ALPHA = 0.1

    // ✅ 시간 동기화 상태 관리
    @Volatile private var currentSyncMode = TimeSyncMode.LOCAL_BASED
    @Volatile private var isGpsAvailable = false
    @Volatile private var lastValidTimeMapping: TimeMapping? = null
    @Volatile private var gpsLostStartTime: Long = 0L

    // 시간 매핑 히스토리 (오프셋 안정성 계산용)
    private val timeMappingHistory = mutableListOf<TimeMapping>()
    private val MAX_MAPPING_HISTORY = 10

    // ✅ 오직 시간 동기화 키만 관리 (데이터 저장은 LoggerManager에서)
    private val synchronizedTimeKeys = Collections.synchronizedSet(TreeSet<Long>())

    // 통계 정보
    private var totalSyncOperations = 0L
    private var gpsLossCount = 0L
    private var offsetUpdateCount = 0L

    /**
     * ✅ 시간 동기화 정보 업데이트 (데이터 저장 없이 시간 매핑만)
     */
    fun updateTimeSync(
        gpsTimestamp: Long?,
        localTimestamp: Long,
        monoTimestamp: Long,
        isGpsTimeValid: Boolean
    ) {
        val effectiveGpsTime = gpsTimestamp ?: localTimestamp

        // ✅ 시간 매핑 생성 및 동기화 모드 결정
        val timeMapping = createTimeMapping(effectiveGpsTime, localTimestamp, monoTimestamp, isGpsTimeValid)
        updateSyncMode(timeMapping)

        // ✅ 하이브리드 시간 키 생성 (현재 동기화 모드에 따라)
        val hybridKey = when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> effectiveGpsTime
            TimeSyncMode.LOCAL_BASED -> localTimestamp
        }

        // ✅ 시간 키만 저장 (실제 데이터는 LoggerManager에서 관리)
        synchronizedTimeKeys.add(hybridKey)

        Log.d(TAG, "⏰ 시간 동기화: mode=${currentSyncMode}, hybridKey=${hybridKey}")
    }

    /**
     * ✅ 현재 동기화 모드에 따른 하이브리드 시간 키 생성
     */
    fun getHybridTimeKey(gpsTime: Long?, localTime: Long): Long {
        return when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> {
                if (gpsTime != null && lastValidTimeMapping != null) {
                    gpsTime
                } else {
                    lastValidTimeMapping?.let { mapping ->
                        localTime + mapping.gpsOffset
                    } ?: localTime
                }
            }
            TimeSyncMode.LOCAL_BASED -> localTime
        }
    }

    /**
     * ✅ 시간 매핑 생성
     */
    private fun createTimeMapping(
        gpsTimestamp: Long,
        localTimestamp: Long,
        monoTimestamp: Long,
        isGpsTimeValid: Boolean
    ): TimeMapping {
        val gpsOffset = if (isGpsTimeValid) gpsTimestamp - localTimestamp else 0L
        val confidence = if (isGpsTimeValid) 1.0 else 0.0

        val mapping = TimeMapping(
            gpsTime = gpsTimestamp,
            localTime = localTimestamp,
            monoTime = monoTimestamp,
            syncMode = if (isGpsTimeValid) TimeSyncMode.GPS_BASED else TimeSyncMode.LOCAL_BASED,
            gpsOffset = gpsOffset,
            confidence = confidence
        )

        // 매핑 히스토리 관리
        synchronized(timeMappingHistory) {
            timeMappingHistory.add(mapping)
            if (timeMappingHistory.size > MAX_MAPPING_HISTORY) {
                timeMappingHistory.removeAt(0)
            }
        }

        return mapping
    }

    /**
     * ✅ 동기화 모드 업데이트
     */
    private fun updateSyncMode(timeMapping: TimeMapping) {
        val previousMode = currentSyncMode

        when {
            timeMapping.confidence >= 0.8 -> {
                currentSyncMode = TimeSyncMode.GPS_BASED
                isGpsAvailable = true
                lastValidTimeMapping = timeMapping
                gpsLostStartTime = 0L
                offsetUpdateCount++
            }

            timeMapping.confidence < 0.3 -> {
                if (isGpsAvailable && gpsLostStartTime == 0L) {
                    gpsLostStartTime = System.currentTimeMillis()
                    gpsLossCount++
                }

                if (gpsLostStartTime > 0 &&
                    System.currentTimeMillis() - gpsLostStartTime > GPS_TIMEOUT_MS) {
                    currentSyncMode = TimeSyncMode.LOCAL_BASED
                    isGpsAvailable = false
                }
            }
        }

        if (previousMode != currentSyncMode) {
            Log.w(TAG, "⚠️ 동기화 모드 변경: ${previousMode} → ${currentSyncMode}")
        }
    }

    /**
     * ✅ 동기화된 시간 키 반환 (LoggerManager에서 사용)
     */
    fun getNewSynchronizedTimeKeys(processedKeys: Set<Long>): List<Long> {
        synchronized(synchronizedTimeKeys) {
            val newKeys = synchronizedTimeKeys.filter { !processedKeys.contains(it) }.sorted()

            Log.d(TAG, "🔄 새로운 동기화 키: mode=${currentSyncMode}, 전체=${synchronizedTimeKeys.size}, 신규=${newKeys.size}")

            return newKeys
        }
    }

    /**
     * ✅ GPS 상태 정보 제공
     */
    fun getGpsStatus(): GpsSyncStatusInfo {
        return GpsSyncStatusInfo(
            isGpsAvailable = isGpsAvailable,
            currentSyncMode = currentSyncMode,
            lastValidTimeMapping = lastValidTimeMapping,
            gpsLostDuration = if (gpsLostStartTime > 0) System.currentTimeMillis() - gpsLostStartTime else 0L,
            totalSyncOperations = totalSyncOperations,
            gpsLossCount = gpsLossCount,
            offsetUpdateCount = offsetUpdateCount
        )
    }

    /**
     * ✅ 현재 동기화 모드 반환
     */
    fun getCurrentSyncMode(): TimeSyncMode = currentSyncMode

    /**
     * ✅ 현재 시간 매핑 정보 반환
     */
    fun getCurrentTimeMapping(): TimeMapping? = lastValidTimeMapping

    /**
     * ✅ 기존 호환성을 위한 메서드들
     */
    fun synchronizeData(data: List<SensorData>): List<SensorData> {
        return data.sortedBy { it.timestamp }
    }

    /**
     * ✅ 시간 키 정리 (메모리 관리)
     */
    fun cleanOldTimeKeys() {
        val currentTime = System.currentTimeMillis()
        val cleanupThreshold = currentTime - 60000L // 1분 이전 키 제거

        synchronized(synchronizedTimeKeys) {
            synchronizedTimeKeys.removeIf { it < cleanupThreshold }
        }

        Log.d(TAG, "✅ 오래된 시간 키 정리 완료")
    }

    fun clearAll() {
        synchronized(synchronizedTimeKeys) { synchronizedTimeKeys.clear() }

        isGpsAvailable = false
        lastValidTimeMapping = null
        gpsLostStartTime = 0L
        currentSyncMode = TimeSyncMode.LOCAL_BASED

        synchronized(timeMappingHistory) { timeMappingHistory.clear() }

        Log.d(TAG, "시간 동기화 데이터 정리 완료")
    }
}

/**
 * ✅ GPS 상태 정보 (시간 기준 정보 포함)
 */
data class GpsSyncStatusInfo(
    val isGpsAvailable: Boolean,
    val currentSyncMode: TimeSyncMode,
    val lastValidTimeMapping: TimeMapping?,
    val gpsLostDuration: Long,
    val totalSyncOperations: Long,
    val gpsLossCount: Long,
    val offsetUpdateCount: Long
)
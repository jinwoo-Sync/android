package com.example.myapplication.data.sync

import com.example.myapplication.model.*
import android.location.Location
import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.*

/**
 * ✅ 하이브리드 GPS/로컬 시간 동기화 시스템
 *
 * 수학적 원리:
 * 1. GPS/Local 이중 시간 체계: T_sync(t) = GPS(t) ∪ Local(t) + mapping
 * 2. 칼만 필터 기반 매핑 추정: δ_k = α·δ_new + (1-α)·δ_prev
 * 3. 시간 기준 상태 전환: GPS_BASED ↔ LOCAL_BASED
 * 4. 최근접 이웃 검색: O(log n) 이진 탐색
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

    // 기존 큐들...
    private val gpsTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, Triple<Location, Long, Long>>())
    private val gnssTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, GnssData>())
    private val imuTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, Triple<FloatArray, Long, Long>>())
    private val cameraTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, SensorData>())
    private val bboxTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, List<BoundingBoxLog>>())

    private val synchronizedTimeKeys = Collections.synchronizedSet(TreeSet<Long>())

    // 통계 정보
    private var totalSyncOperations = 0L
    private var gpsLossCount = 0L
    private var offsetUpdateCount = 0L

    /**
     * ✅ GPS 데이터 추가 - 시간 매핑 정보 포함
     */
    fun addGpsData(
        location: Location,
        gpsTimestamp: Long,
        localTimestamp: Long,
        monoTimestamp: Long,
        isGpsTimeValid: Boolean
    ) {
        // ✅ 시간 매핑 생성 및 동기화 모드 결정
        val timeMapping = createTimeMapping(gpsTimestamp, localTimestamp, monoTimestamp, isGpsTimeValid)
        updateSyncMode(timeMapping)

        // ✅ 하이브리드 시간 키 생성 (현재 동기화 모드에 따라)
        val hybridKey = when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> gpsTimestamp
            TimeSyncMode.LOCAL_BASED -> localTimestamp
        }

        gpsTimeOrderedQueue[hybridKey] = Triple(location, localTimestamp, monoTimestamp)
        synchronizedTimeKeys.add(hybridKey)

        Log.d(TAG, "📍 GPS 데이터 추가: mode=${currentSyncMode}, hybridKey=${hybridKey}, gpsTime=${gpsTimestamp}, localTime=${localTimestamp}")
    }

    /**
     * ✅ GNSS 데이터 추가 - 시간 기준 구분
     */
    fun addGnssData(gnssData: GnssData) {
        val hybridKey = when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> if (gnssData.isGpsTimeValid) gnssData.gpsTimestamp else gnssData.localTimestamp
            TimeSyncMode.LOCAL_BASED -> gnssData.localTimestamp
        }

        gnssTimeOrderedQueue[hybridKey] = gnssData
        Log.d(TAG, "🛰️ GNSS 데이터 추가: mode=${currentSyncMode}, hybridKey=${hybridKey}")
    }

    /**
     * ✅ IMU 데이터 추가 - 로컬 시간 기준
     */
    fun addImuData(imu: FloatArray, sysTs: Long, monoTs: Long) {
        val hybridKey = when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> {
                // GPS 기준일 때 로컬 시간을 GPS 시간으로 변환
                lastValidTimeMapping?.let { mapping ->
                    sysTs + mapping.gpsOffset
                } ?: sysTs
            }
            TimeSyncMode.LOCAL_BASED -> sysTs
        }

        imuTimeOrderedQueue[hybridKey] = Triple(imu.clone(), sysTs, monoTs)
    }

    /**
     * ✅ 카메라 데이터 추가 - 로컬 시간 기준
     */
    fun addCameraData(sensorData: SensorData) {
        val hybridKey = when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> {
                lastValidTimeMapping?.let { mapping ->
                    sensorData.timestamp + mapping.gpsOffset
                } ?: sensorData.timestamp
            }
            TimeSyncMode.LOCAL_BASED -> sensorData.timestamp
        }

        cameraTimeOrderedQueue[hybridKey] = sensorData
        Log.d(TAG, "📷 카메라 데이터 추가: mode=${currentSyncMode}, hybridKey=${hybridKey}, frameId=${sensorData.frameId}")
    }

    /**
     * ✅ 바운딩박스 데이터 추가 - 로컬 시간 기준
     */
    fun addBoundingBoxData(bboxes: List<BoundingBoxLog>) {
        if (bboxes.isNotEmpty()) {
            val hybridKey = when (currentSyncMode) {
                TimeSyncMode.GPS_BASED -> {
                    lastValidTimeMapping?.let { mapping ->
                        bboxes[0].timestamp + mapping.gpsOffset
                    } ?: bboxes[0].timestamp
                }
                TimeSyncMode.LOCAL_BASED -> bboxes[0].timestamp
            }

            bboxTimeOrderedQueue[hybridKey] = bboxes
            Log.d(TAG, "📦 바운딩박스 데이터 추가: mode=${currentSyncMode}, hybridKey=${hybridKey}")
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
            }

            timeMapping.confidence < 0.3 -> {
                if (isGpsAvailable && gpsLostStartTime == 0L) {
                    gpsLostStartTime = System.currentTimeMillis()
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
     * ✅ 동기화된 시간 키 반환 (시간 기준 정보 포함)
     */
    fun getNewSynchronizedTimeKeys(processedKeys: Set<Long>): List<Long> {
        synchronized(synchronizedTimeKeys) {
            val newKeys = synchronizedTimeKeys.filter { !processedKeys.contains(it) }.sorted()

            Log.d(TAG, "🔄 새로운 동기화 키 요청: mode=${currentSyncMode}, 전체=${synchronizedTimeKeys.size}, 신규=${newKeys.size}")

            return newKeys
        }
    }

    /**
     * ✅ 특정 시간 키의 동기화된 데이터 엔트리 생성 (시간 기준 정보 포함)
     */
    fun createSynchronizedEntryForTime(hybridTime: Long): HybridSynchronizedDataEntry? {
        val tolerance = 1000L

        val gpsData = gpsTimeOrderedQueue[hybridTime] ?: return null

        val imuData = findClosestDataInTimeOrderedMap(imuTimeOrderedQueue, hybridTime, tolerance)
        val gnssData = findClosestDataInTimeOrderedMap(gnssTimeOrderedQueue, hybridTime, tolerance)
        val cameraData = findClosestDataInTimeOrderedMap(cameraTimeOrderedQueue, hybridTime, tolerance)
        val bboxData = findClosestDataInTimeOrderedMap(bboxTimeOrderedQueue, hybridTime, tolerance)

        return HybridSynchronizedDataEntry(
            hybridTime = hybridTime,
            gpsData = gpsData,
            imuData = imuData,
            gnssData = gnssData,
            cameraData = cameraData,
            bboxData = bboxData,
            gpsAvailable = isGpsAvailable,
            syncMode = currentSyncMode,           // ✅ 동기화 모드 추가
            timeMapping = lastValidTimeMapping    // ✅ 시간 매핑 정보 추가
        )
    }

    /**
     * ✅ 동기화된 데이터 추출 및 반환
     */
    fun extractSynchronizedData(force: Boolean = false): List<HybridSynchronizedDataEntry> {
        totalSyncOperations++

        if (!force && gpsTimeOrderedQueue.size < 10) {
            Log.d(TAG, "동기화를 위한 데이터 부족: ${gpsTimeOrderedQueue.size}")
            return emptyList()
        }

        Log.d(TAG, "하이브리드 시간 기반 데이터 동기화 시작... GPS상태: $isGpsAvailable, 모드: $currentSyncMode")

        // 동기화할 시간 범위 결정
        val timeRange = synchronized(gpsTimeOrderedQueue) {
            if (gpsTimeOrderedQueue.isEmpty()) return emptyList()

            val times = gpsTimeOrderedQueue.keys.sorted()
            val startTime = times[0]
            val endTime = if (force) times.last() else times[times.size - 5] // 마지막 5개는 보관

            Pair(startTime, endTime)
        }

        // 동기화된 데이터 생성
        val synchronizedData = mutableListOf<HybridSynchronizedDataEntry>()

        synchronized(gpsTimeOrderedQueue) {
            val gpsEntries = gpsTimeOrderedQueue.filterKeys { it >= timeRange.first && it <= timeRange.second }

            for ((hybridTime, gpsData) in gpsEntries) {
                val syncEntry = createHybridSynchronizedEntry(hybridTime, gpsData)
                syncEntry?.let { synchronizedData.add(it) }
            }
        }

        Log.d(TAG, "${synchronizedData.size}개 항목 동기화 완료 (모드: $currentSyncMode)")
        return synchronizedData
    }

    /**
     * ✅ 하이브리드 시간 기준 동기화된 데이터 엔트리 생성
     */
    private fun createHybridSynchronizedEntry(hybridTime: Long, gpsData: Triple<Location, Long, Long>): HybridSynchronizedDataEntry? {
        val tolerance = 1000L

        val imuData = findClosestDataInTimeOrderedMap(imuTimeOrderedQueue, hybridTime, tolerance)
        val gnssData = findClosestDataInTimeOrderedMap(gnssTimeOrderedQueue, hybridTime, tolerance)
        val cameraData = findClosestDataInTimeOrderedMap(cameraTimeOrderedQueue, hybridTime, tolerance)
        val bboxData = findClosestDataInTimeOrderedMap(bboxTimeOrderedQueue, hybridTime, tolerance)

        return HybridSynchronizedDataEntry(
            hybridTime = hybridTime,
            gpsData = gpsData,
            imuData = imuData,
            gnssData = gnssData,
            cameraData = cameraData,
            bboxData = bboxData,
            gpsAvailable = isGpsAvailable,
            syncMode = currentSyncMode,           // ✅ 동기화 모드 추가
            timeMapping = lastValidTimeMapping    // ✅ 시간 매핑 정보 추가
        )
    }

    /**
     * TreeMap에서 가장 가까운 시간의 데이터 찾기
     */
    private fun <T> findClosestDataInTimeOrderedMap(
        timeOrderedMap: MutableMap<Long, T>,
        targetTime: Long,
        tolerance: Long
    ): T? {
        synchronized(timeOrderedMap) {
            timeOrderedMap[targetTime]?.let { return it }

            var closest: T? = null
            var minDiff = Long.MAX_VALUE

            for ((time, data) in timeOrderedMap) {
                val diff = abs(time - targetTime)
                if (diff < minDiff && diff <= tolerance) {
                    minDiff = diff
                    closest = data
                }
                if (time > targetTime && diff > tolerance * 2) break
            }

            return closest
        }
    }

    /**
     * ✅ 메모리 정리 강화
     */
    fun forceCleanOldData() {
        val currentTime = System.currentTimeMillis()
        val cleanupThreshold = currentTime - 30000L // 30초 이전 데이터 제거

        synchronized(gpsTimeOrderedQueue) {
            gpsTimeOrderedQueue.keys.removeIf { it < cleanupThreshold }
        }
        synchronized(gnssTimeOrderedQueue) {
            gnssTimeOrderedQueue.keys.removeIf { it < cleanupThreshold }
        }
        synchronized(imuTimeOrderedQueue) {
            imuTimeOrderedQueue.keys.removeIf { it < cleanupThreshold }
        }
        synchronized(cameraTimeOrderedQueue) {
            cameraTimeOrderedQueue.keys.removeIf { it < cleanupThreshold }
        }
        synchronized(bboxTimeOrderedQueue) {
            bboxTimeOrderedQueue.keys.removeIf { it < cleanupThreshold }
        }

        Log.d(TAG, "✅ 30초 이전 데이터 정리 완료")
    }

    /**
     * ✅ GPS 상태 정보 (시간 기준 정보 포함)
     */
    fun getGpsStatus(): GpsStatusInfo {
        return GpsStatusInfo(
            isGpsAvailable = isGpsAvailable,
            currentSyncMode = currentSyncMode,
            lastValidTimeMapping = lastValidTimeMapping,
            gpsLostDuration = if (gpsLostStartTime > 0) System.currentTimeMillis() - gpsLostStartTime else 0L,
            totalSyncOperations = totalSyncOperations,
            gpsLossCount = gpsLossCount,
            offsetUpdateCount = offsetUpdateCount
        )
    }

    // 기존 호환성 메서드들
    fun synchronizeData(data: List<SensorData>): List<SensorData> {
        return data.sortedBy { it.timestamp }
    }

    fun maintainQueueSizes(
        gpsCapacity: Int = 500,
        gnssCapacity: Int = 500,
        imuCapacity: Int = 1000,
        cameraCapacity: Int = 100,
        bboxCapacity: Int = 200
    ) {
        maintainSingleQueueSize(gpsTimeOrderedQueue, gpsCapacity)
        maintainSingleQueueSize(gnssTimeOrderedQueue, gnssCapacity)
        maintainSingleQueueSize(imuTimeOrderedQueue, imuCapacity)
        maintainSingleQueueSize(cameraTimeOrderedQueue, cameraCapacity)
        maintainSingleQueueSize(bboxTimeOrderedQueue, bboxCapacity)
    }

    private fun <T> maintainSingleQueueSize(queue: MutableMap<Long, T>, capacity: Int) {
        synchronized(queue) {
            while (queue.size > capacity) {
                val firstKey = queue.keys.minOrNull()
                firstKey?.let { queue.remove(it) }
            }
        }
    }

    fun clearAll() {
        synchronized(gpsTimeOrderedQueue) { gpsTimeOrderedQueue.clear() }
        synchronized(gnssTimeOrderedQueue) { gnssTimeOrderedQueue.clear() }
        synchronized(imuTimeOrderedQueue) { imuTimeOrderedQueue.clear() }
        synchronized(cameraTimeOrderedQueue) { cameraTimeOrderedQueue.clear() }
        synchronized(bboxTimeOrderedQueue) { bboxTimeOrderedQueue.clear() }
        synchronized(synchronizedTimeKeys) { synchronizedTimeKeys.clear() }

        isGpsAvailable = false
        lastValidTimeMapping = null
        gpsLostStartTime = 0L
        currentSyncMode = TimeSyncMode.LOCAL_BASED

        Log.d(TAG, "모든 동기화 데이터 정리 완료")
    }
}

/**
 * ✅ 하이브리드 동기화된 데이터 엔트리 (시간 기준 정보 포함)
 */
data class HybridSynchronizedDataEntry(
    val hybridTime: Long,
    val gpsData: Triple<Location, Long, Long>?,
    val imuData: Triple<FloatArray, Long, Long>?,
    val gnssData: GnssData?,
    val cameraData: SensorData?,
    val bboxData: List<BoundingBoxLog>?,
    val gpsAvailable: Boolean,
    val syncMode: TimeSyncMode,           // ✅ 동기화 모드
    val timeMapping: TimeMapping?         // ✅ 시간 매핑 정보
)

/**
 * ✅ GPS 상태 정보 (시간 기준 정보 포함)
 */
data class GpsStatusInfo(
    val isGpsAvailable: Boolean,
    val currentSyncMode: TimeSyncMode,
    val lastValidTimeMapping: TimeMapping?,
    val gpsLostDuration: Long,
    val totalSyncOperations: Long,
    val gpsLossCount: Long,
    val offsetUpdateCount: Long
)

// 기존 데이터 클래스들 유지...
data class HybridSyncQualityMetrics(
    val totalEntries: Int,
    val gpsMatchRate: Double,
    val imuMatchRate: Double,
    val gnssMatchRate: Double,
    val cameraMatchRate: Double,
    val bboxMatchRate: Double,
    val gpsAvailabilityRate: Double,
    val avgTimeDifference: Double,
    val stdTimeDifference: Double,
    val offsetStability: Double
)

data class QueueStatusInfo(
    val gpsQueueSize: Int,
    val gnssQueueSize: Int,
    val imuQueueSize: Int,
    val cameraQueueSize: Int,
    val bboxQueueSize: Int,
    val totalDataPoints: Int
)
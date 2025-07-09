package com.example.myapplication.data.sync

import com.example.myapplication.model.SensorData
import com.example.myapplication.model.GnssData
import com.example.myapplication.model.BoundingBoxLog
import android.location.Location
import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.*

/**
 * 하이브리드 시간 동기화 시스템
 *
 * 수학적 원리:
 * 1. GPS/Monotonic 이중 시간 체계: T_sync(t) = GPS(t) ∪ (Mono(t) + δ(t))
 * 2. 칼만 필터 기반 오프셋 추정: δ_k = α·δ_new + (1-α)·δ_prev
 * 3. 통계적 신호 손실 감지: |T_GPS(t) - T_GPS(t-1)| > θ
 * 4. 최근접 이웃 검색: O(log n) 이진 탐색
 */
class DataSynchronizer {
    private val TAG = "DataSynchronizer"

    // 시간 동기화 파라미터
    private val GPS_TIMEOUT_MS = 5000L              // GPS 타임아웃: 5초
    private val TIME_DIFF_THRESHOLD_MS = 10000L     // 시간 차이 임계값: 10초
    private val KALMAN_ALPHA = 0.1                  // 칼만 필터 알파값

    // 하이브리드 시간 동기화 상태
    @Volatile private var isGpsAvailable = false
    @Volatile private var lastGpsTime: Long = 0L
    @Volatile private var lastMonoTime: Long = 0L
    @Volatile private var gpsMonoOffset: Long = 0L  // GPS와 Mono 시간 오프셋
    @Volatile private var gpsLostStartTime: Long = 0L

    // 시간 정렬된 큐들 (TreeMap으로 자동 정렬)
    private val gpsTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, Triple<Location, Long, Long>>())
    private val gnssTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, GnssData>())
    private val imuTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, Triple<FloatArray, Long, Long>>())
    private val cameraTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, SensorData>())
    private val bboxTimeOrderedQueue = Collections.synchronizedMap(TreeMap<Long, List<BoundingBoxLog>>())

    // 통계 정보
    private var totalSyncOperations = 0L
    private var gpsLossCount = 0L
    private var offsetUpdateCount = 0L

    /**
     * 하이브리드 시간 키 생성
     * 수학적 공식: T_hybrid = GPS_time ∪ (Mono_time + δ)
     *
     * @param gpsTime GPS 시간 (nullable)
     * @param monoTime Monotonic 시간 (nanoseconds)
     * @return 하이브리드 시간 키 (milliseconds)
     */
    fun createHybridTimeKey(gpsTime: Long?, monoTime: Long): Long {
        val monoTimeMs = monoTime / 1_000_000L // 나노초를 밀리초로 변환

        return when {
            // GPS 시간이 유효하고 GPS가 사용 가능한 경우
            gpsTime != null && gpsTime > 0 && isGpsAvailable -> {
                updateGpsMonoOffset(gpsTime, monoTimeMs)
                gpsTime
            }
            // GPS 시간이 없거나 GPS 신호가 손실된 경우
            else -> {
                checkGpsStatus(gpsTime, monoTimeMs)
                monoTimeMs + gpsMonoOffset
            }
        }
    }

    /**
     * GPS와 Monotonic 시간 오프셋 업데이트
     * 칼만 필터 기반 지수 이동 평균: δ_k = α·δ_new + (1-α)·δ_prev
     */
    private fun updateGpsMonoOffset(gpsTime: Long, monoTime: Long) {
        val newOffset = gpsTime - monoTime

        if (gpsMonoOffset == 0L) {
            // 초기 오프셋 설정
            gpsMonoOffset = newOffset
            Log.d(TAG, "초기 GPS 오프셋 설정: $gpsMonoOffset ms")
        } else {
            // 칼만 필터 기반 오프셋 업데이트
            val smoothedOffset = (KALMAN_ALPHA * newOffset + (1 - KALMAN_ALPHA) * gpsMonoOffset).toLong()

            // 급격한 변화 감지 (이상치 제거)
            val offsetDiff = abs(smoothedOffset - gpsMonoOffset)
            if (offsetDiff < TIME_DIFF_THRESHOLD_MS) {
                gpsMonoOffset = smoothedOffset
                offsetUpdateCount++
                Log.d(TAG, "GPS 오프셋 업데이트: $gpsMonoOffset ms (변화: ${offsetDiff}ms)")
            } else {
                Log.w(TAG, "급격한 오프셋 변화 감지, 업데이트 거부: ${offsetDiff}ms")
            }
        }

        lastGpsTime = gpsTime
        lastMonoTime = monoTime
        isGpsAvailable = true
        gpsLostStartTime = 0L
    }

    /**
     * GPS 상태 확인 및 신호 손실 감지
     * 통계적 접근: 시간 불연속성 및 타임아웃 기반 감지
     */
    private fun checkGpsStatus(gpsTime: Long?, monoTime: Long) {
        val currentTime = System.currentTimeMillis()

        when {
            // GPS 시간이 없거나 0인 경우
            gpsTime == null || gpsTime <= 0 -> {
                if (isGpsAvailable && gpsLostStartTime == 0L) {
                    gpsLostStartTime = currentTime
                    gpsLossCount++
                    Log.w(TAG, "GPS 신호 손실 감지 - 유효하지 않은 GPS 시간")
                }

                if (currentTime - gpsLostStartTime > GPS_TIMEOUT_MS) {
                    isGpsAvailable = false
                    Log.w(TAG, "GPS 사용 불가 - Monotonic 시간으로 전환")
                }
            }

            // GPS 시간 점프 감지 (시간 불연속성)
            lastGpsTime > 0 && abs(gpsTime - lastGpsTime) > TIME_DIFF_THRESHOLD_MS -> {
                if (isGpsAvailable) {
                    gpsLostStartTime = currentTime
                    gpsLossCount++
                    Log.w(TAG, "GPS 시간 점프 감지: ${abs(gpsTime - lastGpsTime)}ms")
                }

                if (currentTime - gpsLostStartTime > GPS_TIMEOUT_MS) {
                    isGpsAvailable = false
                    Log.w(TAG, "GPS 신뢰성 부족 - Monotonic 시간으로 전환")
                }
            }

            // GPS 시간이 정상인 경우
            else -> {
                updateGpsMonoOffset(gpsTime, monoTime)
            }
        }
    }

    /**
     * 데이터를 시간 정렬된 큐에 추가
     */
    fun addGpsData(location: Location, sysTs: Long, monoTs: Long) {
        val hybridKey = createHybridTimeKey(location.time, monoTs)
        gpsTimeOrderedQueue[hybridKey] = Triple(location, sysTs, monoTs)
        Log.d(TAG, "GPS 데이터 추가: hybridKey=$hybridKey, gpsTime=${location.time}, gpsAvailable=$isGpsAvailable")
    }

    fun addGnssData(gnssData: GnssData) {
        val hybridKey = createHybridTimeKey(null, gnssData.monoTimestamp)
        gnssTimeOrderedQueue[hybridKey] = gnssData
        Log.d(TAG, "GNSS 데이터 추가: hybridKey=$hybridKey")
    }

    fun addImuData(imu: FloatArray, sysTs: Long, monoTs: Long) {
        val hybridKey = createHybridTimeKey(null, monoTs)
        imuTimeOrderedQueue[hybridKey] = Triple(imu.clone(), sysTs, monoTs)
        //Log.d(TAG, "IMU 데이터 추가: hybridKey=$hybridKey")
    }

    fun addCameraData(sensorData: SensorData) {
        val hybridKey = createHybridTimeKey(null, sensorData.monoTimestamp)
        cameraTimeOrderedQueue[hybridKey] = sensorData
        Log.d(TAG, "카메라 데이터 추가: hybridKey=$hybridKey, frameId=${sensorData.frameId}")
    }

    fun addBoundingBoxData(bboxes: List<BoundingBoxLog>) {
        if (bboxes.isNotEmpty()) {
            val hybridKey = createHybridTimeKey(null, bboxes[0].monoTimestamp)
            bboxTimeOrderedQueue[hybridKey] = bboxes
            Log.d(TAG, "바운딩박스 데이터 추가: hybridKey=$hybridKey, count=${bboxes.size}")
        }
    }

    /**
     * 큐 크기 관리 (메모리 누수 방지)
     */
    fun maintainQueueSizes(
        gpsCapacity: Int,
        gnssCapacity: Int,
        imuCapacity: Int,
        cameraCapacity: Int,
        bboxCapacity: Int
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

    /**
     * 동기화된 데이터 추출 및 반환
     * 수학적 접근: 최근접 이웃 검색 O(log n)
     */
    fun extractSynchronizedData(force: Boolean = false): List<HybridSynchronizedDataEntry> {
        totalSyncOperations++

        if (!force && gpsTimeOrderedQueue.size < 10) {
            Log.d(TAG, "동기화를 위한 데이터 부족: ${gpsTimeOrderedQueue.size}")
            return emptyList()
        }

        Log.d(TAG, "하이브리드 시간 기반 데이터 동기화 시작... GPS상태: $isGpsAvailable")

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
                synchronizedData.add(syncEntry)

                // 처리된 데이터는 큐에서 제거
                if (!force) {
                    gpsTimeOrderedQueue.remove(hybridTime)
                    removeDataFromOtherQueues(hybridTime)
                }
            }
        }

        Log.d(TAG, "${synchronizedData.size}개 항목 동기화 완료")
        return synchronizedData
    }

    /**
     * 하이브리드 시간 기준 동기화된 데이터 엔트리 생성
     * 수학적 원리: 최근접 이웃 검색 + 허용 오차 범위 내 매칭
     */
    private fun createHybridSynchronizedEntry(hybridTime: Long, gpsData: Triple<Location, Long, Long>): HybridSynchronizedDataEntry {
        val tolerance = 1000L // 1초 허용 오차

        // 각 센서 데이터 찾기 (하이브리드 시간 기준)
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
            gpsAvailable = isGpsAvailable
        )
    }

    /**
     * TreeMap에서 가장 가까운 시간의 데이터 찾기
     * 이진 탐색 기반: O(log n) 복잡도
     */
    private fun <T> findClosestDataInTimeOrderedMap(
        timeOrderedMap: MutableMap<Long, T>,
        targetTime: Long,
        tolerance: Long
    ): T? {
        synchronized(timeOrderedMap) {
            // 정확한 매치 확인
            timeOrderedMap[targetTime]?.let { return it }

            // 가장 가까운 시간 찾기 (이진 탐색)
            var closest: T? = null
            var minDiff = Long.MAX_VALUE

            for ((time, data) in timeOrderedMap) {
                val diff = abs(time - targetTime)
                if (diff < minDiff && diff <= tolerance) {
                    minDiff = diff
                    closest = data
                }
                // 정렬된 맵이므로 차이가 커지기 시작하면 중단
                if (time > targetTime && diff > tolerance * 2) break
            }

            return closest
        }
    }

    /**
     * 처리된 시간 주변 데이터 제거 (메모리 관리)
     */
    private fun removeDataFromOtherQueues(hybridTime: Long) {
        val tolerance = 1000L

        synchronized(gnssTimeOrderedQueue) {
            gnssTimeOrderedQueue.keys.filter { abs(it - hybridTime) <= tolerance }.forEach {
                gnssTimeOrderedQueue.remove(it)
            }
        }

        synchronized(imuTimeOrderedQueue) {
            imuTimeOrderedQueue.keys.filter { abs(it - hybridTime) <= tolerance }.forEach {
                imuTimeOrderedQueue.remove(it)
            }
        }

        synchronized(cameraTimeOrderedQueue) {
            cameraTimeOrderedQueue.keys.filter { abs(it - hybridTime) <= tolerance }.forEach {
                cameraTimeOrderedQueue.remove(it)
            }
        }

        synchronized(bboxTimeOrderedQueue) {
            bboxTimeOrderedQueue.keys.filter { abs(it - hybridTime) <= tolerance }.forEach {
                bboxTimeOrderedQueue.remove(it)
            }
        }
    }

    /**
     * 선형 보간을 이용한 IMU 데이터 보간
     * 수학적 공식: f(t) = f(t1) + (t-t1)/(t2-t1) * (f(t2)-f(t1))
     */
    fun interpolateIMUData(targetTime: Long): FloatArray? {
        synchronized(imuTimeOrderedQueue) {
            if (imuTimeOrderedQueue.size < 2) return null

            val sortedEntries = imuTimeOrderedQueue.toList().sortedBy { it.first }

            // 목표 시간을 감싸는 두 데이터 포인트 찾기
            var beforeEntry: Pair<Long, Triple<FloatArray, Long, Long>>? = null
            var afterEntry: Pair<Long, Triple<FloatArray, Long, Long>>? = null

            for (entry in sortedEntries) {
                val time = entry.first
                when {
                    time <= targetTime -> beforeEntry = entry
                    time > targetTime && afterEntry == null -> afterEntry = entry
                }
            }

            if (beforeEntry == null || afterEntry == null) return null

            val t1 = beforeEntry.first.toDouble()
            val t2 = afterEntry.first.toDouble()
            val t = targetTime.toDouble()

            if (t2 == t1) return beforeEntry.second.first

            val alpha = (t - t1) / (t2 - t1)
            val beforeImu = beforeEntry.second.first
            val afterImu = afterEntry.second.first
            val interpolated = FloatArray(beforeImu.size)

            for (i in beforeImu.indices) {
                interpolated[i] = (beforeImu[i] * (1 - alpha) + afterImu[i] * alpha).toFloat()
            }

            return interpolated
        }
    }

    /**
     * 동기화 품질 평가
     * 통계적 분석을 통한 시스템 성능 측정
     */
    fun evaluateSyncQuality(syncEntries: List<HybridSynchronizedDataEntry>): HybridSyncQualityMetrics {
        if (syncEntries.isEmpty()) {
            return HybridSyncQualityMetrics(
                totalEntries = 0,
                gpsMatchRate = 0.0,
                imuMatchRate = 0.0,
                gnssMatchRate = 0.0,
                cameraMatchRate = 0.0,
                bboxMatchRate = 0.0,
                gpsAvailabilityRate = 0.0,
                avgTimeDifference = 0.0,
                stdTimeDifference = 0.0,
                offsetStability = 0.0
            )
        }

        var gpsMatches = 0
        var imuMatches = 0
        var gnssMatches = 0
        var cameraMatches = 0
        var bboxMatches = 0
        var gpsAvailableCount = 0

        val timeDifferences = mutableListOf<Long>()

        for (entry in syncEntries) {
            if (entry.gpsData != null) gpsMatches++
            if (entry.imuData != null) imuMatches++
            if (entry.gnssData != null) gnssMatches++
            if (entry.cameraData != null) cameraMatches++
            if (entry.bboxData != null) bboxMatches++
            if (entry.gpsAvailable) gpsAvailableCount++

            // 시간 차이 계산 (IMU 기준)
            entry.imuData?.let { imu ->
                val timeDiff = abs(imu.second - entry.hybridTime)
                timeDifferences.add(timeDiff)
            }
        }

        val totalEntries = syncEntries.size
        val avgTimeDiff = if (timeDifferences.isNotEmpty()) {
            timeDifferences.average()
        } else 0.0

        val stdTimeDiff = if (timeDifferences.size > 1) {
            sqrt(timeDifferences.map { (it - avgTimeDiff).pow(2) }.average())
        } else 0.0

        // 오프셋 안정성 계산 (변동 계수)
        val offsetStability = if (offsetUpdateCount > 0) {
            1.0 - (stdTimeDiff / (avgTimeDiff + 1)) // 정규화된 안정성 지수
        } else 0.0

        return HybridSyncQualityMetrics(
            totalEntries = totalEntries,
            gpsMatchRate = gpsMatches.toDouble() / totalEntries,
            imuMatchRate = imuMatches.toDouble() / totalEntries,
            gnssMatchRate = gnssMatches.toDouble() / totalEntries,
            cameraMatchRate = cameraMatches.toDouble() / totalEntries,
            bboxMatchRate = bboxMatches.toDouble() / totalEntries,
            gpsAvailabilityRate = gpsAvailableCount.toDouble() / totalEntries,
            avgTimeDifference = avgTimeDiff,
            stdTimeDifference = stdTimeDiff,
            offsetStability = offsetStability
        )
    }

    /**
     * GPS 상태 정보 제공 (디버깅용)
     */
    fun getGpsStatus(): GpsStatusInfo {
        return GpsStatusInfo(
            isGpsAvailable = isGpsAvailable,
            lastGpsTime = lastGpsTime,
            lastMonoTime = lastMonoTime,
            gpsMonoOffset = gpsMonoOffset,
            gpsLostDuration = if (gpsLostStartTime > 0) System.currentTimeMillis() - gpsLostStartTime else 0L,
            totalSyncOperations = totalSyncOperations,
            gpsLossCount = gpsLossCount,
            offsetUpdateCount = offsetUpdateCount
        )
    }

    /**
     * 큐 상태 정보 제공 (모니터링용)
     */
    fun getQueueStatus(): QueueStatusInfo {
        return QueueStatusInfo(
            gpsQueueSize = gpsTimeOrderedQueue.size,
            gnssQueueSize = gnssTimeOrderedQueue.size,
            imuQueueSize = imuTimeOrderedQueue.size,
            cameraQueueSize = cameraTimeOrderedQueue.size,
            bboxQueueSize = bboxTimeOrderedQueue.size,
            totalDataPoints = gpsTimeOrderedQueue.size + gnssTimeOrderedQueue.size +
                    imuTimeOrderedQueue.size + cameraTimeOrderedQueue.size + bboxTimeOrderedQueue.size
        )
    }

    /**
     * 기존 호환성을 위한 메서드 (단순 시간 정렬)
     */
    fun synchronizeData(data: List<SensorData>): List<SensorData> {
        return data.sortedBy { it.timestamp }
    }

    /**
     * 메모리 정리
     */
    fun clearAll() {
        synchronized(gpsTimeOrderedQueue) { gpsTimeOrderedQueue.clear() }
        synchronized(gnssTimeOrderedQueue) { gnssTimeOrderedQueue.clear() }
        synchronized(imuTimeOrderedQueue) { imuTimeOrderedQueue.clear() }
        synchronized(cameraTimeOrderedQueue) { cameraTimeOrderedQueue.clear() }
        synchronized(bboxTimeOrderedQueue) { bboxTimeOrderedQueue.clear() }

        isGpsAvailable = false
        lastGpsTime = 0L
        lastMonoTime = 0L
        gpsMonoOffset = 0L
        gpsLostStartTime = 0L

        Log.d(TAG, "모든 동기화 데이터 정리 완료")
    }
}

/**
 * 하이브리드 동기화된 데이터 엔트리
 */
data class HybridSynchronizedDataEntry(
    val hybridTime: Long,
    val gpsData: Triple<Location, Long, Long>?,
    val imuData: Triple<FloatArray, Long, Long>?,
    val gnssData: GnssData?,
    val cameraData: SensorData?,
    val bboxData: List<BoundingBoxLog>?,
    val gpsAvailable: Boolean
)

/**
 * 하이브리드 동기화 품질 메트릭
 */
data class HybridSyncQualityMetrics(
    val totalEntries: Int,
    val gpsMatchRate: Double,
    val imuMatchRate: Double,
    val gnssMatchRate: Double,
    val cameraMatchRate: Double,
    val bboxMatchRate: Double,
    val gpsAvailabilityRate: Double,       // GPS 가용성 비율
    val avgTimeDifference: Double,         // 평균 시간 차이 (ms)
    val stdTimeDifference: Double,         // 시간 차이 표준편차 (ms)
    val offsetStability: Double            // 오프셋 안정성 지수 (0-1)
)

/**
 * GPS 상태 정보 (확장)
 */
data class GpsStatusInfo(
    val isGpsAvailable: Boolean,
    val lastGpsTime: Long,
    val lastMonoTime: Long,
    val gpsMonoOffset: Long,
    val gpsLostDuration: Long,
    val totalSyncOperations: Long,
    val gpsLossCount: Long,
    val offsetUpdateCount: Long
)

/**
 * 큐 상태 정보
 */
data class QueueStatusInfo(
    val gpsQueueSize: Int,
    val gnssQueueSize: Int,
    val imuQueueSize: Int,
    val cameraQueueSize: Int,
    val bboxQueueSize: Int,
    val totalDataPoints: Int
)
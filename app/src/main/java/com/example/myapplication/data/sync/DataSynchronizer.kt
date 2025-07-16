package com.example.myapplication.data.sync

import android.util.Log
import com.example.myapplication.model.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import com.example.myapplication.DataStructure.CircularQueue

enum class TimeSyncMode {
    GPS_BASED,    // GPS 시간 기준 동기화
    LOCAL_BASED   // Local 시간 기준 동기화
}

// LoggerManager의 Entry 클래스들을 위한 인터페이스 정의
interface GpsEntry {
    val location: android.location.Location
    val systemTime: Long
    val monoTime: Long
    val captureTime: Long
}

interface ImuEntry {
    val imuData: FloatArray
    val systemTime: Long
    val monoTime: Long
    val captureTime: Long
}

interface GnssEntry {
    val gnssData: GnssData
    val captureTime: Long
}

interface CameraEntry {
    val cameraData: SensorData
    val captureTime: Long
}

interface BboxEntry {
    val bboxData: List<BoundingBoxLog>
    val captureTime: Long
}

data class SyncMatchResult(
    val hybridTime: Long,
    val gpsAvailable: Boolean,
    val gpsEntry: GpsEntry?,
    val imuEntry: ImuEntry?,
    val gnssEntry: GnssEntry?,
    val cameraEntry: CameraEntry?,
    val bboxEntry: BboxEntry?,
    val missingDataTypes: List<String> = emptyList()
)

data class LocalSyncResult(
    val localTime: Long,
    val cameraEntry: CameraEntry?,
    val imuEntry: ImuEntry?,
    val bboxEntry: BboxEntry?,
    val missingDataTypes: List<String> = emptyList()
)

data class GpsStatusInfo(
    val isGpsAvailable: Boolean,
    val lastGpsUpdateTime: Long,
    val gpsTimeoutDuration: Long = 5000L
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

class DataSynchronizer {
    companion object {
        private const val TAG = "DataSynchronizer"
        private const val GPS_TIMEOUT_MS = 5000L
        private const val SYNC_WINDOW_MS = 50L // ±50ms 동기화 윈도우
        private const val MAX_SYNC_RESULTS = 100
        private const val GPS_RECOVERY_REPROCESS_WINDOW_MS = 10000L // 10초 이내 데이터 재처리
    }

    private val processedTimeStamps = ConcurrentHashMap.newKeySet<Long>()
    private var timeSyncOffset: Long = 0L
    private var lastGpsUpdateTime: Long = 0L
    private var currentSyncMode: TimeSyncMode = TimeSyncMode.LOCAL_BASED
    private var lastGpsRecoveryTime: Long = 0L // 🎯 GPS 복구 시점 추가

    // 동기화 결과 캐시 - CircularQueue 사용
    private val syncResultCache = CircularQueue<SyncMatchResult>(MAX_SYNC_RESULTS)
    private val localSyncCache = CircularQueue<LocalSyncResult>(MAX_SYNC_RESULTS)

    // 🎯 GPS 복구 시 재처리를 위한 임시 데이터 저장
    private val recentLocalData = CircularQueue<LocalSyncResult>(50)

    /**
     * 🎯 GPS 시간 동기화 업데이트 - GPS 복구 시 기존 데이터 재처리
     */
    fun updateTimeSync(gpsTimestamp: Long, localTimestamp: Long) {
        val wasGpsAvailable = isGpsAvailable()

        timeSyncOffset = gpsTimestamp - localTimestamp
        lastGpsUpdateTime = localTimestamp

        // 🎯 GPS가 새로 복구된 경우
        if (!wasGpsAvailable && currentSyncMode == TimeSyncMode.LOCAL_BASED) {
            lastGpsRecoveryTime = localTimestamp
            currentSyncMode = TimeSyncMode.GPS_BASED

            Log.d(TAG, "📡 GPS 신호 복구: LOCAL_BASED → GPS_BASED")
            Log.d(TAG, "🔄 GPS 복구 - 시간 오프셋: ${timeSyncOffset}ms")

            // 🎯 기존 LOCAL 데이터를 GPS 시간으로 재동기화
            reprocessRecentLocalDataToGps()
        } else if (currentSyncMode == TimeSyncMode.LOCAL_BASED) {
            currentSyncMode = TimeSyncMode.GPS_BASED
            Log.d(TAG, "📡 GPS 시간 동기화 활성화")
        }
    }

    /**
     * 🎯 GPS 복구 시 최근 LOCAL 데이터를 GPS 시간으로 재처리
     */
    private fun reprocessRecentLocalDataToGps() {
        if (recentLocalData.isEmpty()) {
            Log.d(TAG, "재처리할 LOCAL 데이터가 없음")
            return
        }

        val currentTime = System.currentTimeMillis()
        val reprocessedCount = AtomicInteger(0)

        Log.d(TAG, "🔄 GPS 복구 재처리 시작: ${recentLocalData.size()}개 LOCAL 데이터")

        for (localResult in recentLocalData.snapshot()) {
            // 🎯 GPS 복구 시점 이전 일정 시간 내의 데이터만 재처리
            if ((currentTime - localResult.localTime) <= GPS_RECOVERY_REPROCESS_WINDOW_MS) {
                val convertedGpsSyncResult = convertLocalToGpsSync(localResult)

                if (convertedGpsSyncResult != null) {
                    syncResultCache.push(convertedGpsSyncResult)
                    reprocessedCount.incrementAndGet()

                    Log.d(TAG, "✅ LOCAL → GPS 변환: localTime=${localResult.localTime} → hybridTime=${convertedGpsSyncResult.hybridTime}")
                }
            }
        }

        val converted = reprocessedCount.get()
        Log.d(TAG, "🔄 GPS 복구 재처리 완료: ${converted}개 LOCAL → GPS 변환")

        // 재처리 완료 후 LOCAL 캐시는 유지 (기록 목적)
    }

    /**
     * 🎯 LOCAL 동기화 결과를 GPS 동기화 결과로 변환
     */
    private fun convertLocalToGpsSync(localResult: LocalSyncResult): SyncMatchResult? {
        return try {
            // LOCAL 시간을 GPS 하이브리드 시간으로 예측 변환
            val predictedGpsTime = predictTime(localResult.localTime)

            SyncMatchResult(
                hybridTime = predictedGpsTime,
                gpsAvailable = true, // GPS 복구 후이므로 true
                gpsEntry = null, // 실제 GPS 데이터는 없음 (변환된 것)
                imuEntry = localResult.imuEntry,
                gnssEntry = null, // LOCAL 기반이므로 GNSS 없음
                cameraEntry = localResult.cameraEntry,
                bboxEntry = localResult.bboxEntry,
                missingDataTypes = listOf("GPS", "GNSS") + localResult.missingDataTypes
            )
        } catch (e: Exception) {
            Log.e(TAG, "LOCAL → GPS 변환 실패: ${e.message}", e)
            null
        }
    }

    /**
     * 하이브리드 시간 예측
     */
    fun predictTime(timestamp: Long): Long {
        return if (isGpsAvailable()) {
            timestamp + timeSyncOffset
        } else {
            timestamp // GPS 타임아웃 시 로컬 시간 사용
        }
    }

    /**
     * GPS 상태 확인
     */
    fun getGpsStatus(): GpsStatusInfo {
        val isAvailable = isGpsAvailable()
        return GpsStatusInfo(
            isGpsAvailable = isAvailable,
            lastGpsUpdateTime = lastGpsUpdateTime,
            gpsTimeoutDuration = GPS_TIMEOUT_MS
        )
    }

    private fun isGpsAvailable(): Boolean {
        return (System.currentTimeMillis() - lastGpsUpdateTime) < GPS_TIMEOUT_MS
    }

    /**
     * 🎯 동기화 모드 업데이트 (GPS 신호 손실 감지)
     */
    private fun updateSyncMode() {
        val wasGpsBased = (currentSyncMode == TimeSyncMode.GPS_BASED)
        val isGpsNowAvailable = isGpsAvailable()

        when {
            wasGpsBased && !isGpsNowAvailable -> {
                currentSyncMode = TimeSyncMode.LOCAL_BASED
                Log.w(TAG, "🔴 GPS 신호 손실: GPS_BASED → LOCAL_BASED")
            }
            !wasGpsBased && isGpsNowAvailable -> {
                // GPS 복구는 updateTimeSync에서 처리됨
                Log.d(TAG, "📡 GPS 신호 복구 감지 (updateTimeSync에서 처리됨)")
            }
        }
    }

    /**
     * 🎯 개선된 동기화 수행 - GPS 복구 시 자동 재처리
     */
    fun performSynchronization(
        gpsQueue: CircularQueue<GpsEntry>,
        imuQueue: CircularQueue<ImuEntry>,
        gnssQueue: CircularQueue<GnssEntry>,
        cameraQueue: CircularQueue<CameraEntry>,
        bboxQueue: CircularQueue<BboxEntry>
    ) {
        updateSyncMode()

        when (currentSyncMode) {
            TimeSyncMode.GPS_BASED -> {
                performGpsSynchronization(gpsQueue, imuQueue, gnssQueue, cameraQueue, bboxQueue)
            }
            TimeSyncMode.LOCAL_BASED -> {
                performLocalSynchronization(imuQueue, cameraQueue, bboxQueue)
            }
        }
    }

    /**
     * GPS 기반 동기화 (모든 센서 데이터 포함)
     */
    private fun performGpsSynchronization(
        gpsQueue: CircularQueue<GpsEntry>,
        imuQueue: CircularQueue<ImuEntry>,
        gnssQueue: CircularQueue<GnssEntry>,
        cameraQueue: CircularQueue<CameraEntry>,
        bboxQueue: CircularQueue<BboxEntry>
    ) {
        // GPS 시간을 기준으로 동기화
        for (gpsEntry in gpsQueue) {
            val gpsHybridTime = predictTime(gpsEntry.systemTime)

            if (isTimeProcessed(gpsHybridTime)) continue

            val syncResult = findSyncMatch(
                targetTime = gpsHybridTime,
                gpsEntry = gpsEntry,
                imuQueue = imuQueue,
                gnssQueue = gnssQueue,
                cameraQueue = cameraQueue,
                bboxQueue = bboxQueue
            )

            if (syncResult != null) {
                syncResultCache.push(syncResult) // CircularQueue 자동 크기 관리
                markTimeAsProcessed(gpsHybridTime)
            }
        }
    }

    /**
     * 🎯 Local 기반 동기화 (최근 데이터 저장 추가)
     */
    private fun performLocalSynchronization(
        imuQueue: CircularQueue<ImuEntry>,
        cameraQueue: CircularQueue<CameraEntry>,
        bboxQueue: CircularQueue<BboxEntry>
    ) {
        // IMU 시간을 기준으로 Local 동기화
        for (imuEntry in imuQueue) {
            val localTime = imuEntry.systemTime

            if (isTimeProcessed(localTime)) continue

            val localResult = findLocalMatch(
                targetTime = localTime,
                imuEntry = imuEntry,
                cameraQueue = cameraQueue,
                bboxQueue = bboxQueue
            )

            if (localResult != null) {
                localSyncCache.push(localResult)

                // 🎯 GPS 복구 시 재처리를 위해 최근 데이터 저장
                recentLocalData.push(localResult)

                markTimeAsProcessed(localTime)
            }
        }
    }

    /**
     * GPS 기반 동기화 매칭 (모든 센서 필요)
     */
    private fun findSyncMatch(
        targetTime: Long,
        gpsEntry: GpsEntry,
        imuQueue: CircularQueue<ImuEntry>,
        gnssQueue: CircularQueue<GnssEntry>,
        cameraQueue: CircularQueue<CameraEntry>,
        bboxQueue: CircularQueue<BboxEntry>
    ): SyncMatchResult? {

        val missingTypes = mutableListOf<String>()

        // CircularQueue의 findClosest 메서드 사용
        val imuMatch = imuQueue.findClosest(
            predicate = { entry -> predictTime(entry.systemTime) },
            targetTime = targetTime,
            windowMs = SYNC_WINDOW_MS
        )
        if (imuMatch == null) missingTypes.add("IMU")

        val gnssMatch = gnssQueue.findClosest(
            predicate = { entry -> entry.gnssData.gpsTimestamp },
            targetTime = targetTime,
            windowMs = SYNC_WINDOW_MS
        )
        if (gnssMatch == null) missingTypes.add("GNSS")

        val cameraMatch = cameraQueue.findClosest(
            predicate = { entry -> predictTime(entry.cameraData.timestamp) },
            targetTime = targetTime,
            windowMs = SYNC_WINDOW_MS
        )
        if (cameraMatch == null) missingTypes.add("CAMERA")

        val bboxMatch = bboxQueue.findClosest(
            predicate = { entry -> entry.captureTime },
            targetTime = targetTime,
            windowMs = SYNC_WINDOW_MS
        )
        if (bboxMatch == null) missingTypes.add("BBOX")

        return SyncMatchResult(
            hybridTime = targetTime,
            gpsAvailable = true,
            gpsEntry = gpsEntry,
            imuEntry = imuMatch,
            gnssEntry = gnssMatch,
            cameraEntry = cameraMatch,
            bboxEntry = bboxMatch,
            missingDataTypes = missingTypes
        )
    }

    /**
     * Local 기반 동기화 매칭 (Camera, IMU, BBox만)
     */
    private fun findLocalMatch(
        targetTime: Long,
        imuEntry: ImuEntry,
        cameraQueue: CircularQueue<CameraEntry>,
        bboxQueue: CircularQueue<BboxEntry>
    ): LocalSyncResult? {

        val missingTypes = mutableListOf<String>()

        val cameraMatch = cameraQueue.findClosest(
            predicate = { entry -> entry.cameraData.timestamp },
            targetTime = targetTime,
            windowMs = SYNC_WINDOW_MS
        )
        if (cameraMatch == null) missingTypes.add("CAMERA")

        val bboxMatch = bboxQueue.findClosest(
            predicate = { entry -> entry.captureTime },
            targetTime = targetTime,
            windowMs = SYNC_WINDOW_MS
        )
        if (bboxMatch == null) missingTypes.add("BBOX")

        return LocalSyncResult(
            localTime = targetTime,
            cameraEntry = cameraMatch,
            imuEntry = imuEntry,
            bboxEntry = bboxMatch,
            missingDataTypes = missingTypes
        )
    }

    /**
     * GPS 동기화된 데이터 추출 (gps_sync.txt용)
     */
    fun extractGpsSynchronizedData(): List<SyncMatchResult> {
        return syncResultCache.snapshot() // 전체 스냅샷 반환
    }

    /**
     * Local 동기화된 데이터 추출 (local_sync.txt용)
     */
    fun extractLocalSynchronizedData(): List<LocalSyncResult> {
        return localSyncCache.snapshot() // 전체 스냅샷 반환
    }

    /**
     * 기존 호환성을 위한 메서드
     */
    fun extractSynchronizedData(force: Boolean = false): List<HybridSynchronizedDataEntry> {
        val gpsResults = extractGpsSynchronizedData()
        return gpsResults.map { syncResult ->
            HybridSynchronizedDataEntry(
                hybridTime = syncResult.hybridTime,
                gpsAvailable = syncResult.gpsAvailable,
                gpsData = syncResult.gpsEntry?.let {
                    Triple(it.location, it.systemTime, it.monoTime)
                },
                imuData = syncResult.imuEntry?.let {
                    Pair(it.imuData, it.systemTime)
                },
                gnssData = syncResult.gnssEntry?.gnssData,
                cameraData = syncResult.cameraEntry?.cameraData,
                bboxData = syncResult.bboxEntry?.bboxData
            )
        }
    }

    /**
     * 큐 상태 반환 (HomeRepository용)
     */
    fun getQueueStatus(): QueueStatusInfo {
        return QueueStatusInfo(0, 0, 0, 0, 0) // Placeholder
    }

    /**
     * GPS 모노 오프셋 반환
     */
    fun getGpsMonoOffset(): Long = timeSyncOffset

    /**
     * 🎯 동기화 상태 리포트 (디버깅용)
     */
    fun getSyncStatusReport(): String {
        return buildString {
            appendLine("=== DataSynchronizer 상태 ===")
            appendLine("현재 모드: $currentSyncMode")
            appendLine("GPS 사용 가능: ${isGpsAvailable()}")
            appendLine("마지막 GPS 업데이트: ${System.currentTimeMillis() - lastGpsUpdateTime}ms 전")
            appendLine("시간 오프셋: ${timeSyncOffset}ms")
            appendLine("GPS 동기화 결과: ${syncResultCache.size()}개")
            appendLine("LOCAL 동기화 결과: ${localSyncCache.size()}개")
            appendLine("최근 LOCAL 데이터: ${recentLocalData.size()}개")
            if (lastGpsRecoveryTime > 0) {
                appendLine("마지막 GPS 복구: ${System.currentTimeMillis() - lastGpsRecoveryTime}ms 전")
            }
        }
    }

    // 시간 처리 관련 메서드들
    fun isTimeProcessed(time: Long): Boolean {
        return processedTimeStamps.contains(time)
    }

    fun markTimeAsProcessed(time: Long) {
        processedTimeStamps.add(time)
    }

    fun getCurrentSyncMode(): TimeSyncMode = currentSyncMode
}
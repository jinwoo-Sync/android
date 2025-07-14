package com.example.myapplication.data.logging

import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.myapplication.data.VideoEncoder.SimpleVideoEncoder
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.data.streaming.StreamingClientFactory
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.data.sync.HybridSynchronizedDataEntry
import com.example.myapplication.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LoggerManager private constructor(
    private val context: Context,
    private val dataSynchronizer: DataSynchronizer
) {
    companion object {
        private const val TAG = "LoggerManager"

        // Video Parameters
        private const val VIDEO_WIDTH = 840
        private const val VIDEO_HEIGHT = 840
        private const val VIDEO_FPS = 15
        private const val VIDEO_BITRATE = 1_200_000
        private const val I_FRAME_INTERVAL = 2

        // Batch Processing Parameters
        private const val BATCH_SIZE = 15
        private const val BATCH_TIMEOUT_MS = 4000L
        private const val MAX_FRAME_BUFFER = 45

        // ✅ 적응형 큐 용량 관리
        private const val MAX_GPS_QUEUE = 100
        private const val MAX_IMU_QUEUE = 5000
        private const val MAX_GNSS_QUEUE = 100
        private const val MAX_BBOX_QUEUE = 50
        private const val MAX_CAMERA_QUEUE = 45

        // ✅ 완전한 GNSS 데이터 큐 용량
        private const val MAX_COMPREHENSIVE_GNSS_QUEUE = 1000
        private const val MAX_SATELLITE_STATUS_QUEUE = 500
        private const val MAX_NAVIGATION_QUEUE = 200
        private const val MAX_ANTENNA_QUEUE = 50
        private const val MAX_GNSS_CLOCK_QUEUE = 500
        private const val MAX_GNSS_SESSION_QUEUE = 100

        private const val BUFFER_SIZE = 8192
        private const val GPS_BASED_BATCH_INTERVAL = 1000L
        private const val GPS_BATCH_SIZE = 10

        private val videoSessionMutex = Mutex()

        @Volatile
        private var INSTANCE: LoggerManager? = null

        fun getInstance(context: Context, dataSynchronizer: DataSynchronizer): LoggerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LoggerManager(context.applicationContext, dataSynchronizer).also { INSTANCE = it }
            }
        }

        fun createCommonDataDirectory(context: Context): File {
            return try {
                val documentsDir = File(Environment.getExternalStorageDirectory(), "Documents/gnss")
                val hourlyDir = File(documentsDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

                if (documentsDir.exists() || documentsDir.mkdirs()) {
                    if (!hourlyDir.exists()) {
                        hourlyDir.mkdirs()
                    }
                    Log.d(TAG, "✅ Documents/gnss 디렉토리 사용: ${hourlyDir.absolutePath}")
                    return hourlyDir
                }

                val appExternalDir = File(context.getExternalFilesDir(null), "gnss_data")
                val fallbackHourlyDir = File(appExternalDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

                if (!fallbackHourlyDir.exists()) {
                    fallbackHourlyDir.mkdirs()
                }
                Log.d(TAG, "✅ 앱 전용 외부 저장소 사용: ${fallbackHourlyDir.absolutePath}")
                return fallbackHourlyDir

            } catch (e: Exception) {
                Log.e(TAG, "❌ 디렉토리 생성 실패: ${e.message}", e)
                val internalDir = File(context.filesDir, "gnss_data")
                val emergencyHourlyDir = File(internalDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))
                emergencyHourlyDir.mkdirs()
                Log.w(TAG, "🚨 내부 저장소 사용: ${emergencyHourlyDir.absolutePath}")
                return emergencyHourlyDir
            }
        }

        fun getCurrentDataDirectory(context: Context): File {
            return createCommonDataDirectory(context)
        }
    }

    // ========== ✅ 완전한 GNSS 데이터 큐 시스템 ==========

    // 기존 큐들
    private val independentGpsQueue = ConcurrentLinkedQueue<IndependentGpsEntry>()
    private val independentImuQueue = ConcurrentLinkedQueue<IndependentImuEntry>()
    private val independentGnssQueue = ConcurrentLinkedQueue<IndependentGnssEntry>()
    private val independentBboxQueue = ConcurrentLinkedQueue<IndependentBboxEntry>()
    private val independentCameraQueue = ConcurrentLinkedQueue<IndependentCameraEntry>()

    // ✅ 새로운 완전한 GNSS 데이터 큐들
    private val comprehensiveGnssQueue = ConcurrentLinkedQueue<IndependentComprehensiveGnssEntry>()
    private val satelliteStatusQueue = ConcurrentLinkedQueue<IndependentSatelliteStatusEntry>()
    private val navigationMessageQueue = ConcurrentLinkedQueue<IndependentNavigationEntry>()
    private val antennaInfoQueue = ConcurrentLinkedQueue<IndependentAntennaEntry>()
    private val gnssClockQueue = ConcurrentLinkedQueue<IndependentGnssClockEntry>()
    private val gnssSessionQueue = ConcurrentLinkedQueue<IndependentGnssSessionEntry>()

    // Video encoding queue
    private val frameBuffer = ConcurrentLinkedQueue<SensorData>()

    // ✅ 메모리 압박 상황 모니터링
    private val memoryMonitor = MemoryMonitor()

    // 큐 접근 동기화
    private val queueAccessMutex = Mutex()

    // GPS 상태 추적
    @Volatile
    private var currentGpsStatus = false

    // ========== ✅ 메모리 모니터링 클래스 ==========

    private inner class MemoryMonitor {
        fun getMemoryPressure(): Float {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            return (usedMemory.toFloat() / maxMemory.toFloat()).coerceIn(0f, 1f)
        }

        fun isMemoryPressureHigh(): Boolean = getMemoryPressure() > 0.8f
    }

    // ========== ✅ 완전한 GNSS 데이터 엔트리 정의 ==========

    data class IndependentGpsEntry(
        val location: Location,
        val systemTime: Long,
        val monoTime: Long,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentImuEntry(
        val imuData: FloatArray,
        val systemTime: Long,
        val monoTime: Long,
        val captureTime: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as IndependentImuEntry
            return imuData.contentEquals(other.imuData) &&
                    systemTime == other.systemTime &&
                    monoTime == other.monoTime
        }

        override fun hashCode(): Int {
            var result = imuData.contentHashCode()
            result = 31 * result + systemTime.hashCode()
            result = 31 * result + monoTime.hashCode()
            return result
        }
    }

    data class IndependentGnssEntry(
        val gnssData: GnssData,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentBboxEntry(
        val bboxData: List<BoundingBoxLog>,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentCameraEntry(
        val cameraData: SensorData,
        val captureTime: Long = System.currentTimeMillis()
    )

    // ✅ 새로운 완전한 GNSS 엔트리들
    data class IndependentComprehensiveGnssEntry(
        val comprehensiveData: ComprehensiveGnssData,
        val clockData: GnssClockData?,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentSatelliteStatusEntry(
        val satelliteStatus: GnssSatelliteStatus,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentNavigationEntry(
        val navigationData: GnssNavigationData,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentAntennaEntry(
        val antennaData: GnssAntennaData,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentGnssClockEntry(
        val clockData: GnssClockData,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentGnssSessionEntry(
        val sessionData: GnssSessionSummary,
        val captureTime: Long = System.currentTimeMillis()
    )

    // ========== 상태 관리 ==========

    private var isLogSavingEnabled = false
    private var isLiveStreamingEnabled = false

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val encodingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var liveStreamingClient: StreamingClient
    private var currentTransportType: String? = null

    private val lastBatchTime = AtomicLong(System.currentTimeMillis())
    private var videoEncoder: SimpleVideoEncoder? = null
    private val encoderMutex = Mutex()
    private var currentSessionTimestamp: String? = null

    init {
        startBatchProcessor()
        startQueueMonitoring()
    }

    // ========== ✅ 개선된 큐 관리 시스템 ==========

    /**
     * ✅ 적응형 큐 크기 관리
     */
    private fun <T> maintainAdaptiveQueueSize(
        queue: ConcurrentLinkedQueue<T>,
        baseSize: Int,
        memoryPressure: Float
    ) {
        val adaptiveSize = (baseSize * (1.0f - memoryPressure * 0.5f)).toInt()
        while (queue.size > adaptiveSize) {
            queue.poll()
        }
    }

    private fun <T> maintainQueueSize(queue: ConcurrentLinkedQueue<T>, maxSize: Int) {
        val memoryPressure = memoryMonitor.getMemoryPressure()
        maintainAdaptiveQueueSize(queue, maxSize, memoryPressure)
    }

    // ========== ✅ 완전한 GNSS 데이터 저장 메서드들 ==========

    /**
     * ✅ 완전한 GNSS 측정 데이터 저장
     */
    fun pushComprehensiveGnss(
        comprehensiveData: ComprehensiveGnssData,
        clockData: GnssClockData?
    ) {
        if (shouldSave()) {
            val comprehensiveEntry = IndependentComprehensiveGnssEntry(
                comprehensiveData = comprehensiveData,
                clockData = clockData
            )
            comprehensiveGnssQueue.offer(comprehensiveEntry)
            maintainQueueSize(comprehensiveGnssQueue, MAX_COMPREHENSIVE_GNSS_QUEUE)

            Log.d(TAG, "완전한 GNSS 데이터 추가: ${comprehensiveData.gnssType}, SV=${comprehensiveData.satelliteId}, C/N0=${comprehensiveData.signalStrength}")
        }

        if (shouldLiveStream()) {
            // 스트리밍 로직 (필요시 구현)
        }
    }

    /**
     * ✅ 위성 상태 데이터 저장 (단일)
     */
    fun pushSatelliteStatus(satelliteStatus: GnssSatelliteStatus) {
        if (shouldSave()) {
            val statusEntry = IndependentSatelliteStatusEntry(satelliteStatus = satelliteStatus)
            satelliteStatusQueue.offer(statusEntry)
            maintainQueueSize(satelliteStatusQueue, MAX_SATELLITE_STATUS_QUEUE)

            Log.d(TAG, "위성 상태 추가: SV=${satelliteStatus.svid}, Used=${satelliteStatus.usedInFix}, C/N0=${satelliteStatus.cn0DbHz}")
        }
    }

    /**
     * ✅ 위성 상태 데이터 배치 저장 (벌크 삽입 최적화)
     */
    fun pushSatelliteStatusBatch(satelliteStatuses: List<GnssSatelliteStatus>) {
        if (shouldSave() && satelliteStatuses.isNotEmpty()) {
            satelliteStatuses.forEach { status ->
                val statusEntry = IndependentSatelliteStatusEntry(satelliteStatus = status)
                satelliteStatusQueue.offer(statusEntry)
            }
            maintainQueueSize(satelliteStatusQueue, MAX_SATELLITE_STATUS_QUEUE)

            Log.d(TAG, "위성 상태 배치 추가: ${satelliteStatuses.size}개, 총 큐 크기=${satelliteStatusQueue.size}")
        }
    }

    /**
     * ✅ 내비게이션 메시지 저장
     */
    fun pushNavigationMessage(navigationData: GnssNavigationData) {
        if (shouldSave()) {
            val navEntry = IndependentNavigationEntry(navigationData = navigationData)
            navigationMessageQueue.offer(navEntry)
            maintainQueueSize(navigationMessageQueue, MAX_NAVIGATION_QUEUE)

            Log.d(TAG, "내비게이션 메시지 추가: SV=${navigationData.svid}, Type=${navigationData.type}")
        }
    }

    /**
     * ✅ 안테나 정보 저장 (API 30+)
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun pushAntennaInfo(antennaData: GnssAntennaData) {
        if (shouldSave()) {
            val antennaEntry = IndependentAntennaEntry(antennaData = antennaData)
            antennaInfoQueue.offer(antennaEntry)
            maintainQueueSize(antennaInfoQueue, MAX_ANTENNA_QUEUE)

            Log.d(TAG, "안테나 정보 추가: FreqMHz=${antennaData.carrierFrequencyMHz}")
        }
    }

    /**
     * ✅ GNSS 클럭 데이터 저장
     */
    fun pushGnssClockData(clockData: GnssClockData) {
        if (shouldSave()) {
            val clockEntry = IndependentGnssClockEntry(clockData = clockData)
            gnssClockQueue.offer(clockEntry)
            maintainQueueSize(gnssClockQueue, MAX_GNSS_CLOCK_QUEUE)

            Log.d(TAG, "GNSS 클럭 데이터 추가: TimeNanos=${clockData.timeNanos}")
        }
    }

    /**
     * ✅ 첫 번째 위치 고정 기록
     */
    fun recordFirstFix(ttffMs: Long) {
        Log.d(TAG, "📍 첫 번째 위치 고정 기록: ${ttffMs}ms")
        val sessionSummary = GnssSessionSummary(
            sessionStartTime = System.currentTimeMillis(),
            sessionEndTime = null,
            firstFixTime = ttffMs,
            totalSatellitesUsed = 0,
            averageSignalStrength = 0.0,
            sessionDuration = 0L,
            additionalInfo = "FirstFix=${ttffMs}ms"
        )

        val sessionEntry = IndependentGnssSessionEntry(sessionData = sessionSummary)
        gnssSessionQueue.offer(sessionEntry)
        maintainQueueSize(gnssSessionQueue, MAX_GNSS_SESSION_QUEUE)
    }

    /**
     * ✅ GNSS 세션 종료 기록
     */
    fun recordSessionEnd(sessionDuration: Long, ttffMs: Long?) {
        Log.d(TAG, "📊 GNSS 세션 종료: 지속시간=${sessionDuration}ms, TTFF=${ttffMs}ms")
        val sessionSummary = GnssSessionSummary(
            sessionStartTime = System.currentTimeMillis() - sessionDuration,
            sessionEndTime = System.currentTimeMillis(),
            firstFixTime = ttffMs,
            totalSatellitesUsed = 0,
            averageSignalStrength = 0.0,
            sessionDuration = sessionDuration,
            additionalInfo = "SessionEnd, Duration=${sessionDuration}ms"
        )

        val sessionEntry = IndependentGnssSessionEntry(sessionData = sessionSummary)
        gnssSessionQueue.offer(sessionEntry)
        maintainQueueSize(gnssSessionQueue, MAX_GNSS_SESSION_QUEUE)
    }

    // ========== 기존 데이터 저장 메서드들 ==========

    fun pushGps(loc: Location, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        if (shouldSave()) {
            val gpsEntry = IndependentGpsEntry(
                location = Location(loc),
                systemTime = sysTs,
                monoTime = monoTs
            )
            independentGpsQueue.offer(gpsEntry)
            maintainQueueSize(independentGpsQueue, MAX_GPS_QUEUE)
            Log.d(TAG, "독립 GPS 큐에 데이터 추가: lat=${loc.latitude}, lon=${loc.longitude}")
        }

        if (shouldLiveStream()) {
            liveStreamingClient.sendGpsData(loc, sysTs, monoTs)
        }
    }

    fun pushImu(imu: FloatArray, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        if (shouldSave()) {
            val imuEntry = IndependentImuEntry(
                imuData = imu.clone(),
                systemTime = sysTs,
                monoTime = monoTs
            )
            independentImuQueue.offer(imuEntry)
            maintainQueueSize(independentImuQueue, MAX_IMU_QUEUE)

            if (independentImuQueue.size % 50 == 0) {
                Log.d(TAG, "독립 IMU 큐 크기: ${independentImuQueue.size}")
            }
        }

        if (shouldLiveStream()) {
            liveStreamingClient.sendImuData(imu, sysTs, monoTs)
        }
    }

    fun pushGnss(g: GnssData) {
        if (shouldSave()) {
            val gnssEntry = IndependentGnssEntry(gnssData = g)
            independentGnssQueue.offer(gnssEntry)
            maintainQueueSize(independentGnssQueue, MAX_GNSS_QUEUE)
            Log.d(TAG, "독립 GNSS 큐에 데이터 추가: type=${g.gnssType}, sat=${g.satelliteId}")
        }

        if (shouldLiveStream()) {
            // liveStreamingClient.sendGnssData(g) // 필요시 스트리밍 클라이언트에 추가
        }
    }

    fun pushBoundingBox(bboxes: List<BoundingBoxLog>) {
        if (shouldSave()) {
            val bboxEntry = IndependentBboxEntry(bboxData = bboxes.toList())
            independentBboxQueue.offer(bboxEntry)
            maintainQueueSize(independentBboxQueue, MAX_BBOX_QUEUE)
            Log.d(TAG, "독립 BBOX 큐에 데이터 추가: count=${bboxes.size}")
        }

        if (shouldLiveStream()) {
            liveStreamingClient.sendBoundingBoxData(bboxes)
        }
    }

    fun pushCamera(data: SensorData) {
        if (shouldSave()) {
            frameBuffer.offer(data)
            while (frameBuffer.size > MAX_FRAME_BUFFER) {
                frameBuffer.poll()
            }

            val cameraEntry = IndependentCameraEntry(cameraData = data)
            independentCameraQueue.offer(cameraEntry)
            maintainQueueSize(independentCameraQueue, MAX_CAMERA_QUEUE)
        }

        if (shouldLiveStream()) {
            liveStreamingClient.sendCameraData(data)
        }
    }

    private fun startQueueMonitoring() {
        ioScope.launch {
            while (isActive) {
                try {
                    val status = getIndependentQueueStatus()
                    val memoryPressure = memoryMonitor.getMemoryPressure()

                    if (status.totalDataPoints > 500) {
                        Log.d(TAG, "큐 상태: GPS=${status.gpsQueueSize}, IMU=${status.imuQueueSize}, " +
                                "GNSS=${status.gnssQueueSize}, CompGNSS=${status.comprehensiveGnssQueueSize}, " +
                                "Sat=${status.satelliteStatusQueueSize}, Nav=${status.navigationQueueSize}, " +
                                "Memory=${(memoryPressure * 100).toInt()}%")
                    }

                    // ✅ 메모리 압박 시 강제 정리
                    if (memoryMonitor.isMemoryPressureHigh()) {
                        performEmergencyCleanup()
                    }

                    currentGpsStatus = dataSynchronizer.getGpsStatus().isGpsAvailable

                    delay(10000)
                } catch (e: Exception) {
                    Log.e(TAG, "큐 모니터링 오류: ${e.message}", e)
                }
            }
        }
    }

    /**
     * ✅ 메모리 압박 시 응급 정리
     */
    private suspend fun performEmergencyCleanup() {
        Log.w(TAG, "⚠️ 메모리 압박 감지, 응급 정리 수행")

        queueAccessMutex.withLock {
            // 큐 크기를 절반으로 줄임
            while (independentImuQueue.size > MAX_IMU_QUEUE / 2) {
                independentImuQueue.poll()
            }
            while (comprehensiveGnssQueue.size > MAX_COMPREHENSIVE_GNSS_QUEUE / 2) {
                comprehensiveGnssQueue.poll()
            }
            while (satelliteStatusQueue.size > MAX_SATELLITE_STATUS_QUEUE / 2) {
                satelliteStatusQueue.poll()
            }
        }

        // 가비지 컬렉션 강제 실행
        System.gc()

        Log.d(TAG, "✅ 응급 정리 완료")
    }

    // ========== ✅ 개선된 배치 처리 시스템 ==========

    private fun startBatchProcessor() {
        ioScope.launch {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastBatch = currentTime - lastBatchTime.get()

                    if (frameBuffer.size >= BATCH_SIZE ||
                        (frameBuffer.isNotEmpty() && timeSinceLastBatch >= BATCH_TIMEOUT_MS)) {
                        processBatch("자동 배치")
                    }

                    delay(500)
                } catch (e: Exception) {
                    Log.e(TAG, "배치 처리 오류: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun processBatch(reason: String) {
        if (frameBuffer.isEmpty()) return

        encoderMutex.withLock {
            try {
                val commonDirectory = getCurrentDataDirectory(context)

                val currentMinuteId = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                if (videoEncoder != null && videoEncoder!!.getSessionId() != currentMinuteId) {
                    videoEncoder?.stopRecording()
                    videoEncoder = null
                    System.gc()
                }

                if (videoEncoder == null || !videoEncoder!!.isRecording()) {
                    videoEncoder = SimpleVideoEncoder(context)
                    videoEncoder!!.setOutputDirectory(commonDirectory)

                    val started = videoEncoder!!.startRecording()
                    if (!started) {
                        Log.e(TAG, "비디오 인코더 시작 실패")
                        return@withLock
                    }
                    currentSessionTimestamp = videoEncoder!!.getSessionId()
                }

                val framesToProcess = mutableListOf<SensorData>()
                while (frameBuffer.isNotEmpty() && framesToProcess.size < BATCH_SIZE) {
                    frameBuffer.poll()?.let { framesToProcess.add(it) }
                }

                var encodedFrames = 0
                for (frame in framesToProcess) {
                    frame.bitmap?.let { bitmap ->
                        if (videoEncoder?.addFrame(bitmap) == true) {
                            encodedFrames++
                        }
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                }

                framesToProcess.clear()

                // ✅ 비동기 배치 처리 최적화
                saveCompleteGnssDataOptimized()

                lastBatchTime.set(System.currentTimeMillis())
                Log.d(TAG, "$reason 완료: ${encodedFrames}프레임, 메모리 정리 완료")

            } catch (e: Exception) {
                Log.e(TAG, "배치 처리 실패: ${e.message}", e)
                System.gc()
            }
        }
    }

    // ========== ✅ 비동기 배치 처리 최적화 ==========

    /**
     * ✅ 모든 GNSS 데이터를 병렬로 저장 - 최적화된 버전
     */
    private suspend fun saveCompleteGnssDataOptimized() = withContext(Dispatchers.IO) {
        try {
            val commonDir = getCurrentDataDirectory(context)

            Log.d(TAG, "=== 최적화된 완전한 GNSS 데이터 저장 시작 ===")
            Log.d(TAG, "📁 저장 위치: ${commonDir.absolutePath}")

            // ✅ 병렬 처리로 I/O 대기 시간 최소화
            val jobs = listOf(
                async { processComprehensiveGnssQueue(commonDir) },
                async { processSatelliteStatusQueue(commonDir) },
                async { processNavigationMessageQueue(commonDir) },
                async { processAntennaInfoQueue(commonDir) },
                async { processGnssClockQueue(commonDir) },
                async { processGnssSessionQueue(commonDir) },
                async { saveIndependentGpsData(commonDir) },
                async { saveIndependentImuData(commonDir) },
                async { saveIndependentGnssData(commonDir) },
                async { saveSynchronizedGpsData(commonDir) }
            )

            jobs.awaitAll()

            Log.d(TAG, "=== 최적화된 완전한 GNSS 데이터 저장 완료 ===")

        } catch (e: Exception) {
            Log.e(TAG, "완전한 GNSS 데이터 저장 실패: ${e.message}", e)
        }
    }

    /**
     * ✅ 개별 큐 처리 메서드들
     */
    private suspend fun processComprehensiveGnssQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "comprehensive_gnss.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentComprehensiveGnssEntry>()

            repeat(50.coerceAtMost(comprehensiveGnssQueue.size)) {
                comprehensiveGnssQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val content = buildComprehensiveGnssContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(COMPREHENSIVE_GNSS_HEADER)
                        writer.newLine()
                    }
                    writer.write(content)
                    writer.flush()
                }

                Log.d(TAG, "✅ 완전한 GNSS 데이터 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 완전한 GNSS 데이터 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun processSatelliteStatusQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "satellite_status.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentSatelliteStatusEntry>()

            repeat(30.coerceAtMost(satelliteStatusQueue.size)) {
                satelliteStatusQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val content = buildSatelliteStatusContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(SATELLITE_STATUS_HEADER)
                        writer.newLine()
                    }
                    writer.write(content)
                    writer.flush()
                }

                Log.d(TAG, "✅ 위성 상태 데이터 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 위성 상태 데이터 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun processNavigationMessageQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "navigation_messages.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentNavigationEntry>()

            repeat(20.coerceAtMost(navigationMessageQueue.size)) {
                navigationMessageQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val content = buildNavigationMessageContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(NAVIGATION_MESSAGE_HEADER)
                        writer.newLine()
                    }
                    writer.write(content)
                    writer.flush()
                }

                Log.d(TAG, "✅ 내비게이션 메시지 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 내비게이션 메시지 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun processAntennaInfoQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "antenna_info.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentAntennaEntry>()

            repeat(10.coerceAtMost(antennaInfoQueue.size)) {
                antennaInfoQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val content = buildAntennaInfoContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(ANTENNA_INFO_HEADER)
                        writer.newLine()
                    }
                    writer.write(content)
                    writer.flush()
                }

                Log.d(TAG, "✅ 안테나 정보 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 안테나 정보 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun processGnssClockQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "gnss_clock.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGnssClockEntry>()

            repeat(20.coerceAtMost(gnssClockQueue.size)) {
                gnssClockQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val content = buildGnssClockContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(GNSS_CLOCK_HEADER)
                        writer.newLine()
                    }
                    writer.write(content)
                    writer.flush()
                }

                Log.d(TAG, "✅ GNSS 클럭 데이터 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ GNSS 클럭 데이터 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun processGnssSessionQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "gnss_sessions.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGnssSessionEntry>()

            repeat(10.coerceAtMost(gnssSessionQueue.size)) {
                gnssSessionQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val content = buildGnssSessionContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(GNSS_SESSION_HEADER)
                        writer.newLine()
                    }
                    writer.write(content)
                    writer.flush()
                }

                Log.d(TAG, "✅ GNSS 세션 데이터 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ GNSS 세션 데이터 저장 실패: ${e.message}", e)
            }
        }
    }

    // ========== 기존 데이터 저장 메서드들 ==========

    private suspend fun saveIndependentGpsData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_gps.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGpsEntry>()

            repeat(5.coerceAtMost(independentGpsQueue.size)) {
                independentGpsQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val gpsContent = buildIndependentGpsContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(RAW_GPS_HEADER)
                        writer.newLine()
                    }
                    writer.write(gpsContent)
                    writer.flush()
                }

                Log.d(TAG, "✅ GPS 데이터 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ GPS 데이터 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun saveIndependentImuData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_imu.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentImuEntry>()

            repeat((BATCH_SIZE * 5).coerceAtMost(independentImuQueue.size)) {
                independentImuQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val imuContent = buildIndependentImuContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(RAW_IMU_HEADER)
                        writer.newLine()
                    }
                    writer.write(imuContent)
                    writer.flush()
                }

                Log.d(TAG, "✅ IMU 데이터 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ IMU 데이터 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun saveIndependentGnssData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_gnss.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGnssEntry>()

            repeat(10.coerceAtMost(independentGnssQueue.size)) {
                independentGnssQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val gnssContent = buildIndependentGnssContent(dataToSave)

            try {
                val append = file.exists()
                BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                    if (!append) {
                        writer.write(RAW_GNSS_HEADER)
                        writer.newLine()
                    }
                    writer.write(gnssContent)
                    writer.flush()
                }

                Log.d(TAG, "✅ GNSS 데이터 저장: ${file.name}, +${dataToSave.size}개")

            } catch (e: Exception) {
                Log.e(TAG, "❌ GNSS 데이터 저장 실패: ${e.message}", e)
            }
        }
    }

    private suspend fun saveSynchronizedGpsData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "gps_sync.txt")

        val syncData = try {
            dataSynchronizer.extractSynchronizedData(force = true)
        } catch (e: Exception) {
            Log.w(TAG, "DataSynchronizer.extractSynchronizedData 호출 실패: ${e.message}")
            emptyList<HybridSynchronizedDataEntry>()
        }

        if (syncData.isEmpty()) return@withContext

        val syncContent = buildGpsSyncContent(syncData)

        try {
            val append = file.exists()
            BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                if (!append) {
                    writer.write(GPS_SYNC_HEADER)
                    writer.newLine()
                }
                writer.write(syncContent)
                writer.flush()
            }

            Log.d(TAG, "✅ 동기화 GPS 데이터 저장: ${file.name}, +${syncData.size}개")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 동기화 GPS 데이터 저장 실패: ${e.message}", e)
        }
    }

    // ========== ✅ 완전한 GNSS 콘텐츠 빌더들 ==========

    private fun buildComprehensiveGnssContent(dataList: List<IndependentComprehensiveGnssEntry>): String {
        return buildString(dataList.size * 500) {
            for (entry in dataList) {
                val data = entry.comprehensiveData
                val clock = entry.clockData

                append("${entry.captureTime}\t")
                append("${data.gpsTimestamp}\t${data.localTimestamp}\t${data.monoTimestamp}\t")
                append("${data.gnssType}\t${data.satelliteId}\t${data.signalStrength}\t")
                append("${data.carrierFrequencyHz ?: "NULL"}\t${data.multipathIndicator}\t")
                append("${data.pseudorangeRate ?: "NULL"}\t${data.pseudorangeRateUncertainty ?: "NULL"}\t")
                append("${data.accumulatedDeltaRange ?: "NULL"}\t${data.accumulatedDeltaRangeState}\t")
                append("${data.carrierPhase ?: "NULL"}\t${data.carrierPhaseUncertainty ?: "NULL"}\t")
                append("${data.receivedSvTimeNanos}\t${data.receivedSvTimeUncertainty}\t")
                append("${data.state}\t${data.automaticGainControl ?: "NULL"}\t")
                append("${data.basebandCn0DbHz ?: "NULL"}\t${data.codeType ?: "NULL"}\t")
                append("${clock?.timeNanos ?: "NULL"}\t${clock?.fullBiasNanos ?: "NULL"}\t")
                append("${if (currentGpsStatus) "AVAILABLE" else "LOST"}")
                append("\n")
            }
        }
    }

    private fun buildSatelliteStatusContent(dataList: List<IndependentSatelliteStatusEntry>): String {
        return buildString(dataList.size * 200) {
            for (entry in dataList) {
                val sat = entry.satelliteStatus

                append("${entry.captureTime}\t")
                append("${sat.localTimestamp}\t${sat.monoTimestamp}\t")
                append("${sat.satelliteIndex}\t${sat.constellationType}\t${sat.svid}\t")
                append("${sat.cn0DbHz}\t${sat.carrierFrequencyHz ?: "NULL"}\t")
                append("${sat.azimuthDegrees}\t${sat.elevationDegrees}\t")
                append("${sat.hasAlmanacData}\t${sat.hasEphemerisData}\t${sat.usedInFix}\t")
                append("${sat.totalSatelliteCount}\t${sat.usedSatelliteCount}")
                append("\n")
            }
        }
    }

    private fun buildNavigationMessageContent(dataList: List<IndependentNavigationEntry>): String {
        return buildString(dataList.size * 300) {
            for (entry in dataList) {
                val nav = entry.navigationData

                append("${entry.captureTime}\t")
                append("${nav.localTimestamp}\t${nav.monoTimestamp}\t")
                append("${nav.messageId}\t${nav.submessageId}\t${nav.type}\t${nav.status}\t")
                append("${nav.svid}\t${nav.dataLength}\t")
                append("${nav.data.joinToString(",") { "%02X".format(it) }}\t")
                append("${nav.additionalInfo}")
                append("\n")
            }
        }
    }

    private fun buildAntennaInfoContent(dataList: List<IndependentAntennaEntry>): String {
        return buildString(dataList.size * 400) {
            for (entry in dataList) {
                val ant = entry.antennaData

                append("${entry.captureTime}\t")
                append("${ant.localTimestamp}\t${ant.monoTimestamp}\t")
                append("${ant.carrierFrequencyMHz}\t")
                append("${ant.phaseCenterOffsetX}\t${ant.phaseCenterOffsetY}\t${ant.phaseCenterOffsetZ}\t")
                append("${ant.phaseCenterOffsetUncertaintyX}\t${ant.phaseCenterOffsetUncertaintyY}\t${ant.phaseCenterOffsetUncertaintyZ}\t")
                append("${ant.phaseCenterVariationCorrections?.joinToString(",") ?: "NULL"}\t")
                append("${ant.signalGainCorrections?.joinToString(",") ?: "NULL"}\t")
                append("${ant.additionalInfo}")
                append("\n")
            }
        }
    }

    private fun buildGnssClockContent(dataList: List<IndependentGnssClockEntry>): String {
        return buildString(dataList.size * 250) {
            for (entry in dataList) {
                val clock = entry.clockData

                append("${entry.captureTime}\t")
                append("${clock.gpsTimestamp}\t${clock.localTimestamp}\t${clock.monoTimestamp}\t")
                append("${clock.timeNanos}\t${clock.timeUncertaintyNanos ?: "NULL"}\t")
                append("${clock.leapSecond ?: "NULL"}\t${clock.biasNanos ?: "NULL"}\t")
                append("${clock.biasUncertaintyNanos ?: "NULL"}\t${clock.driftNanosPerSecond ?: "NULL"}\t")
                append("${clock.fullBiasNanos ?: "NULL"}\t${clock.hardwareClockDiscontinuityCount ?: "NULL"}\t")
                append("${clock.additionalInfo}")
                append("\n")
            }
        }
    }

    private fun buildGnssSessionContent(dataList: List<IndependentGnssSessionEntry>): String {
        return buildString(dataList.size * 150) {
            for (entry in dataList) {
                val session = entry.sessionData

                append("${entry.captureTime}\t")
                append("${session.sessionStartTime}\t${session.sessionEndTime ?: "NULL"}\t")
                append("${session.firstFixTime ?: "NULL"}\t${session.sessionDuration}\t")
                append("${session.totalSatellitesUsed}\t${session.averageSignalStrength}\t")
                append("${session.additionalInfo}")
                append("\n")
            }
        }
    }

    // 기존 콘텐츠 빌더들
    private fun buildIndependentGpsContent(dataList: List<IndependentGpsEntry>): String {
        return buildString(dataList.size * 150) {
            for (entry in dataList) {
                val loc = entry.location

                append("${entry.captureTime}\t")
                append("${entry.systemTime}\t")
                append("${entry.monoTime}\t")
                append("${loc.time}\t")
                append("${loc.latitude}\t")
                append("${loc.longitude}\t")
                append("${if (loc.hasAltitude()) loc.altitude else "NULL"}\t")
                append("${if (loc.hasAccuracy()) loc.accuracy else "NULL"}\t")
                append("${if (loc.hasSpeed()) loc.speed else "NULL"}\t")
                append("${if (loc.hasBearing()) loc.bearing else "NULL"}\t")
                append("${loc.provider}\t")
                append("${if (currentGpsStatus) "AVAILABLE" else "LOST"}")
                append("\n")
            }
        }
    }

    private fun buildIndependentImuContent(dataList: List<IndependentImuEntry>): String {
        return buildString(dataList.size * 200) {
            for (entry in dataList) {
                val imu = entry.imuData

                if (imu.size >= 9) {
                    append("${entry.captureTime}\t")
                    append("${entry.systemTime}\t")
                    append("${entry.monoTime}\t")
                    append("${imu[0]}\t${imu[1]}\t${imu[2]}\t")
                    append("${imu[3]}\t${imu[4]}\t${imu[5]}\t")
                    append("${imu[6]}\t${imu[7]}\t${imu[8]}\t")
                    append("${if (currentGpsStatus) "AVAILABLE" else "LOST"}")
                    append("\n")
                }
            }
        }
    }

    private fun buildIndependentGnssContent(dataList: List<IndependentGnssEntry>): String {
        return buildString(dataList.size * 300) {
            for (entry in dataList) {
                val gnss = entry.gnssData

                append("${entry.captureTime}\t")
                append("${gnss.gpsTimestamp}\t")
                append("${gnss.monoTimestamp}\t")
                append("${gnss.gnssType}\t")
                append("${gnss.satelliteId}\t")
                append("${gnss.signalStrength}\t")
                append("${gnss.pseudorangeRate ?: "NULL"}\t")
                append("${gnss.carrierPhase ?: "NULL"}\t")
                append("${gnss.additionalInfo}\t")
                append("${if (currentGpsStatus) "AVAILABLE" else "LOST"}")
                append("\n")
            }
        }
    }

    private fun buildGpsSyncContent(syncDataList: List<HybridSynchronizedDataEntry>): String {
        return buildString(syncDataList.size * 200) {
            for (entry in syncDataList) {
                val gpsData = entry.gpsData ?: continue
                val (loc, _, _) = gpsData
                val imu = entry.imuData?.first
                val gnss = entry.gnssData
                val camera = entry.cameraData
                val bbox = entry.bboxData

                append("${entry.hybridTime}\t")
                append("${if (entry.gpsAvailable) "AVAILABLE" else "LOST"}\t")
                append("${loc.latitude}\t${loc.longitude}\t")
                append("${if (loc.hasAltitude()) loc.altitude else "NULL"}\t")

                if (imu != null && imu.size >= 9) {
                    append("${imu[0]}\t${imu[1]}\t${imu[2]}\t")
                    append("${imu[3]}\t${imu[4]}\t${imu[5]}\t")
                    append("${imu[6]}\t${imu[7]}\t${imu[8]}\t")
                } else {
                    append("NULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\t")
                }

                if (gnss != null) {
                    append("${gnss.gnssType}\t${gnss.satelliteId}\t${gnss.signalStrength}\t")
                } else {
                    append("NULL\tNULL\tNULL\t")
                }

                append("${camera?.frameId ?: "NULL"}\t")
                append("${bbox?.size ?: "NULL"}")
                append("\n")
            }
        }
    }

    // ========== 상태 관리 및 제어 메서드들 ==========

    fun enableLogSaving() {
        isLogSavingEnabled = true

        if (videoEncoder == null) {
            val commonDirectory = getCurrentDataDirectory(context)

            videoEncoder = SimpleVideoEncoder(context)
            videoEncoder!!.setOutputDirectory(commonDirectory)

            val started = videoEncoder!!.startRecording()

            if (started) {
                Log.d(TAG, "📁 로그 저장 및 비디오 녹화 활성화")
                Log.d(TAG, "📁 저장 위치: ${commonDirectory.absolutePath}")
            } else {
                Log.e(TAG, "❌ 비디오 인코더 시작 실패")
                videoEncoder = null
            }
        }

        Log.d(TAG, "✅ enableLogSaving() 완료")
    }

    fun disableLogSaving() {
        isLogSavingEnabled = false

        videoEncoder?.stopRecording()
        videoEncoder = null

        Log.d(TAG, "📁 로그 저장 및 비디오 녹화 비활성화")
        Log.d(TAG, "✅ disableLogSaving() 완료")
    }

    fun setTransportType(transportType: String) {
        try {
            currentTransportType = transportType
            liveStreamingClient = StreamingClientFactory.createStreamingClient(transportType)
            Log.d(TAG, "✅ 전송 타입 설정: $transportType")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 전송 타입 설정 실패: ${e.message}", e)
            throw e
        }
    }

    suspend fun enableStreaming() {
        if (!::liveStreamingClient.isInitialized) {
            Log.e(TAG, "❌ StreamingClient가 초기화되지 않음. setTransportType()을 먼저 호출하세요.")
            throw IllegalStateException("StreamingClient가 초기화되지 않음")
        }

        try {
            liveStreamingClient.startStreaming(context)
            isLiveStreamingEnabled = true
            Log.d(TAG, "✅ 라이브 스트리밍 활성화: $currentTransportType")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 스트리밍 활성화 실패: ${e.message}", e)
            throw e
        }
    }

    suspend fun disableStreaming() {
        if (!isLiveStreamingEnabled) {
            Log.d(TAG, "이미 스트리밍이 비활성화됨")
            return
        }

        try {
            if (::liveStreamingClient.isInitialized) {
                liveStreamingClient.stopStreaming()
            }
            isLiveStreamingEnabled = false
            Log.d(TAG, "✅ 라이브 스트리밍 비활성화")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 스트리밍 비활성화 실패: ${e.message}", e)
            throw e
        }
    }

    // ========== 상태 정보 제공 ==========

    data class IndependentQueueStatusInfo(
        val gpsQueueSize: Int,
        val imuQueueSize: Int,
        val gnssQueueSize: Int,
        val bboxQueueSize: Int,
        val cameraQueueSize: Int,
        val comprehensiveGnssQueueSize: Int,
        val satelliteStatusQueueSize: Int,
        val navigationQueueSize: Int,
        val antennaQueueSize: Int,
        val gnssClockQueueSize: Int,
        val gnssSessionQueueSize: Int,
        val totalDataPoints: Int,
        val gpsStatus: Boolean,
        val memoryPressure: Float
    )

    fun getIndependentQueueStatus(): IndependentQueueStatusInfo {
        return IndependentQueueStatusInfo(
            gpsQueueSize = independentGpsQueue.size,
            imuQueueSize = independentImuQueue.size,
            gnssQueueSize = independentGnssQueue.size,
            bboxQueueSize = independentBboxQueue.size,
            cameraQueueSize = independentCameraQueue.size,
            comprehensiveGnssQueueSize = comprehensiveGnssQueue.size,
            satelliteStatusQueueSize = satelliteStatusQueue.size,
            navigationQueueSize = navigationMessageQueue.size,
            antennaQueueSize = antennaInfoQueue.size,
            gnssClockQueueSize = gnssClockQueue.size,
            gnssSessionQueueSize = gnssSessionQueue.size,
            totalDataPoints = independentGpsQueue.size + independentImuQueue.size +
                    independentGnssQueue.size + independentBboxQueue.size +
                    independentCameraQueue.size + comprehensiveGnssQueue.size +
                    satelliteStatusQueue.size + navigationMessageQueue.size +
                    antennaInfoQueue.size + gnssClockQueue.size + gnssSessionQueue.size,
            gpsStatus = currentGpsStatus,
            memoryPressure = memoryMonitor.getMemoryPressure()
        )
    }

    fun isStreamingEnabled(): Boolean = isLiveStreamingEnabled
    fun isLogSavingEnabled(): Boolean = isLogSavingEnabled
    fun getCurrentTransportType(): String? = currentTransportType

    fun getVideoEncoderStatus(): String {
        return when {
            videoEncoder == null -> "비디오 인코더 없음"
            videoEncoder!!.isRecording() -> "녹화 중 (${videoEncoder!!.getFrameCount()}프레임)"
            else -> "비디오 인코더 대기 중"
        }
    }

    fun getSystemStatus(): String {
        return buildString {
            append("=== LoggerManager 상태 ===\n")
            append("로그 저장: ${if (isLogSavingEnabled) "활성화" else "비활성화"}\n")
            append("라이브 스트리밍: ${if (isLiveStreamingEnabled) "활성화" else "비활성화"}\n")
            append("전송 타입: ${currentTransportType ?: "설정되지 않음"}\n")
            append("비디오 상태: ${getVideoEncoderStatus()}\n")

            val queueStatus = getIndependentQueueStatus()
            append("=== 기본 큐 상태 ===\n")
            append("GPS: ${queueStatus.gpsQueueSize}/${MAX_GPS_QUEUE}\n")
            append("IMU: ${queueStatus.imuQueueSize}/${MAX_IMU_QUEUE}\n")
            append("GNSS: ${queueStatus.gnssQueueSize}/${MAX_GNSS_QUEUE}\n")
            append("BBOX: ${queueStatus.bboxQueueSize}/${MAX_BBOX_QUEUE}\n")
            append("CAMERA: ${queueStatus.cameraQueueSize}/${MAX_CAMERA_QUEUE}\n")

            append("=== 완전한 GNSS 큐 상태 ===\n")
            append("완전한 GNSS: ${queueStatus.comprehensiveGnssQueueSize}/${MAX_COMPREHENSIVE_GNSS_QUEUE}\n")
            append("위성 상태: ${queueStatus.satelliteStatusQueueSize}/${MAX_SATELLITE_STATUS_QUEUE}\n")
            append("내비게이션: ${queueStatus.navigationQueueSize}/${MAX_NAVIGATION_QUEUE}\n")
            append("안테나: ${queueStatus.antennaQueueSize}/${MAX_ANTENNA_QUEUE}\n")
            append("클럭: ${queueStatus.gnssClockQueueSize}/${MAX_GNSS_CLOCK_QUEUE}\n")
            append("세션: ${queueStatus.gnssSessionQueueSize}/${MAX_GNSS_SESSION_QUEUE}\n")

            append("총 데이터 포인트: ${queueStatus.totalDataPoints}\n")
            append("메모리 사용률: ${(queueStatus.memoryPressure * 100).toInt()}%")
        }
    }

    fun getVideoOutputDirectory(): File {
        return getCurrentDataDirectory(context)
    }
    // ========== 유틸리티 메서드들 ==========

    private inline fun shouldSave() = isLogSavingEnabled
    private inline fun shouldLiveStream() = isLiveStreamingEnabled

    // ========== ✅ 완전한 GNSS 헤더 정의 ==========

    private val COMPREHENSIVE_GNSS_HEADER = """
# Comprehensive GNSS Measurements Data - Complete Raw Signal Information
# Theory: Precise Point Positioning (PPP) + Real-Time Kinematic (RTK) Ready
# Includes: Signal Quality, Multipath, Carrier Phase, Pseudorange, Clock Bias
# Mathematical Foundation: Carrier-Smoothed Code Measurements + Ionospheric Corrections
CAPTURE_TIME	GPS_TIME	LOCAL_TIME	MONO_TIME	GNSS_TYPE	SAT_ID	CN0_DBZ	CARRIER_FREQ_HZ	MULTIPATH	PSEUDORANGE_RATE	PR_RATE_UNC	ACCUM_DELTA_RANGE	ADR_STATE	CARRIER_PHASE	CP_UNC	RX_SV_TIME_NANOS	RX_SV_TIME_UNC	STATE	AGC_DB	BASEBAND_CN0	CODE_TYPE	CLOCK_TIME_NANOS	CLOCK_FULL_BIAS	GPS_STATUS
""".trimIndent()

    private val SATELLITE_STATUS_HEADER = """
# GNSS Satellite Status Data - Complete Sky Plot and Fix Information
# Theory: Geometric Dilution of Precision (GDOP) Analysis + Satellite Geometry
# Critical: usedInFix indicates actual contribution to position calculation
# Mathematical Foundation: Position Domain Analysis + Quality Metrics
CAPTURE_TIME	LOCAL_TIME	MONO_TIME	SAT_INDEX	CONSTELLATION	SVID	CN0_DBZ	CARRIER_FREQ_HZ	AZIMUTH_DEG	ELEVATION_DEG	HAS_ALMANAC	HAS_EPHEMERIS	USED_IN_FIX	TOTAL_SATS	USED_SATS
""".trimIndent()

    private val NAVIGATION_MESSAGE_HEADER = """
# GNSS Navigation Messages - Complete Satellite Broadcast Data
# Theory: Ephemeris, Almanac, Ionospheric Corrections + Time System Data
# Contains: Orbit parameters, clock corrections, system time, atmospheric models
# Mathematical Foundation: Satellite Orbit Determination + Clock Model Parameters
CAPTURE_TIME	LOCAL_TIME	MONO_TIME	MSG_ID	SUBMSG_ID	TYPE	STATUS	SVID	DATA_LENGTH	HEX_DATA	ADDITIONAL_INFO
""".trimIndent()

    private val ANTENNA_INFO_HEADER = """
# GNSS Antenna Information - Phase Center Calibration Data
# Theory: Precise positioning requires antenna phase center corrections
# Critical for: PPP, RTK, Survey-grade positioning + Multi-frequency analysis
# Mathematical Foundation: Phase Center Variation (PCV) + Phase Center Offset (PCO)
CAPTURE_TIME	LOCAL_TIME	MONO_TIME	CARRIER_FREQ_MHZ	PCO_X_MM	PCO_Y_MM	PCO_Z_MM	PCO_UNC_X	PCO_UNC_Y	PCO_UNC_Z	PCV_CORRECTIONS	GAIN_CORRECTIONS	ADDITIONAL_INFO
""".trimIndent()

    private val GNSS_CLOCK_HEADER = """
# GNSS Hardware Clock Data - Complete Time System Analysis
# Theory: Clock stability analysis for precise timing + Inter-system time offsets
# Includes: Bias, drift, uncertainty, leap seconds + Hardware discontinuities
# Mathematical Foundation: Allan Variance Analysis + Kalman Filter Clock Model
CAPTURE_TIME	GPS_TIME	LOCAL_TIME	MONO_TIME	TIME_NANOS	TIME_UNC_NANOS	LEAP_SECOND	BIAS_NANOS	BIAS_UNC_NANOS	DRIFT_NANOS_PER_SEC	FULL_BIAS_NANOS	HW_CLOCK_DISCONTINUITY	ADDITIONAL_INFO
""".trimIndent()

    private val GNSS_SESSION_HEADER = """
# GNSS Session Summary Data - Performance Analysis and Quality Metrics
# Theory: Session-based performance evaluation + TTFF analysis
# Includes: Start/end times, fix performance, satellite usage statistics
# Mathematical Foundation: Quality of Service (QoS) Metrics + Statistical Analysis
CAPTURE_TIME	SESSION_START	SESSION_END	FIRST_FIX_TIME	SESSION_DURATION	TOTAL_SATS_USED	AVG_SIGNAL_STRENGTH	ADDITIONAL_INFO
""".trimIndent()

    // 기존 헤더들
    private val GPS_SYNC_HEADER = """
# GPS Synchronized Data (Hybrid Logical Clock) - Mathematical Time Alignment
# Theory: Kalman Filter + Binary Search O(log n) + Producer-Consumer Pattern
# Video: Movies/gnss/yyyyMMdd_HH/sensor_video_[timestamp].mp4  
# Raw Data: Documents/gnss/yyyyMMdd_HH/ (Independent Queue Management)
HYBRID_TIME	GPS_STATUS	LAT	LON	ALT	ACC_X	ACC_Y	ACC_Z	GYRO_X	GYRO_Y	GYRO_Z	MAG_X	MAG_Y	MAG_Z	GNSS_TYPE	SAT_ID	CN0	CAMERA_FRAME_ID	BBOX_COUNT
""".trimIndent()

    private val RAW_GPS_HEADER = """
# Raw GPS Data - Independent Queue Management (LogManager)
# Theory: M/M/1/K Queueing Model + ConcurrentLinkedQueue (Lock-free)
# Memory: Deep Copy Semantics + Bounded Queue (K=${MAX_GPS_QUEUE})
CAPTURE_TIME	SYS_TIME	MONO_TIME	GPS_TIME	LATITUDE	LONGITUDE	ALTITUDE	ACCURACY	SPEED	BEARING	PROVIDER	GPS_STATUS
""".trimIndent()

    private val RAW_IMU_HEADER = """
# Raw IMU Data - Independent Queue Management (9DOF Sensor Fusion)
# Theory: High Frequency Sampling (f=100Hz) + Optimal Buffer Management
# Memory: Clone-based Storage + Queue Capacity (K=${MAX_IMU_QUEUE})
CAPTURE_TIME	SYS_TIME	MONO_TIME	ACC_X	ACC_Y	ACC_Z	GYRO_X	GYRO_Y	GYRO_Z	MAG_X	MAG_Y	MAG_Z	GPS_STATUS
""".trimIndent()

    private val RAW_GNSS_HEADER = """
# Raw GNSS Measurements - Independent Queue Management
# Theory: Satellite Signal Processing + Statistical Analysis
# Memory: Bounded Queue Management (K=${MAX_GNSS_QUEUE})
CAPTURE_TIME	SYS_TIME	MONO_TIME	GNSS_TYPE	SAT_ID	CN0_DB_HZ	PSEUDORANGE_RATE	CARRIER_PHASE	ADDITIONAL_INFO	GPS_STATUS
""".trimIndent()
}

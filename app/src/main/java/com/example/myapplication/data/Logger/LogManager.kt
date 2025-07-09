package com.example.myapplication.data.logging

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.location.Location
import android.media.*
import android.media.MediaCodec.BufferInfo
import android.media.MediaMuxer.OutputFormat
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.myapplication.model.GnssData
import com.example.myapplication.model.SensorData
import com.example.myapplication.model.BoundingBoxLog
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.data.streaming.StreamingClientFactory
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.data.sync.HybridSynchronizedDataEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 수학적 기반 멀티미디어 로깅 시스템 - Independent Queue Management
 *
 * 핵심 설계 원칙:
 * 1. Data Independence: LogManager ⊥ DataSynchronizer (큐 분리)
 * 2. Memory Safety: Clone-based data copying (deep copy semantics)
 * 3. Concurrency Control: Lock-free algorithms + Mutex for critical sections
 * 4. File System Optimization: Buffered I/O with optimal block sizes
 */
class LoggerManager private constructor(
    private val context: Context,
    private val dataSynchronizer: DataSynchronizer
) {

    companion object {
        private const val TAG = "LoggerManager"

        // ITU-T H.264 Standard Parameters
        private const val VIDEO_WIDTH = 840
        private const val VIDEO_HEIGHT = 840
        private const val VIDEO_FPS = 15
        private const val VIDEO_BITRATE = 1_200_000
        private const val I_FRAME_INTERVAL = 2

        // Queueing Theory Parameters (M/M/1/K Model)
        private const val BATCH_SIZE = 15                 // Service rate: μ = 15 frames/batch
        private const val BATCH_TIMEOUT_MS = 4000L        // Maximum waiting time: W_max = 4s
        private const val MAX_FRAME_BUFFER = 45           // Queue capacity: K = 45 frames

        // Independent Queue Capacities (Memory Management)
        private const val MAX_GPS_QUEUE = 100             // GPS 큐 최대 크기
        private const val MAX_IMU_QUEUE = 200             // IMU 큐 최대 크기 (high frequency)
        private const val MAX_GNSS_QUEUE = 100            // GNSS 큐 최대 크기
        private const val MAX_BBOX_QUEUE = 50             // Bounding Box 큐 최대 크기
        private const val MAX_CAMERA_QUEUE = 45           // Camera 큐 최대 크기

        // File I/O Optimization Parameters
        private const val BUFFER_SIZE = 8192              // 8KB optimal buffer size

        @Volatile
        private var INSTANCE: LoggerManager? = null

        fun getInstance(context: Context, dataSynchronizer: DataSynchronizer): LoggerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LoggerManager(context.applicationContext, dataSynchronizer).also { INSTANCE = it }
            }
        }
    }

    // ========== 독립적 데이터 큐 시스템 (Independent Queue System) ==========

    /**
     * 수학적 정의: 각 센서별 독립적 FIFO 큐
     * - Q_GPS: GPS 원시 데이터 큐
     * - Q_IMU: IMU 원시 데이터 큐
     * - Q_GNSS: GNSS 원시 데이터 큐
     * - Q_BBOX: Bounding Box 데이터 큐
     * - Q_CAMERA: Camera 프레임 큐
     */
    private val independentGpsQueue = ConcurrentLinkedQueue<IndependentGpsEntry>()
    private val independentImuQueue = ConcurrentLinkedQueue<IndependentImuEntry>()
    private val independentGnssQueue = ConcurrentLinkedQueue<IndependentGnssEntry>()
    private val independentBboxQueue = ConcurrentLinkedQueue<IndependentBboxEntry>()
    private val independentCameraQueue = ConcurrentLinkedQueue<IndependentCameraEntry>()

    // Video encoding queue (기존 유지)
    private val frameBuffer = ConcurrentLinkedQueue<SensorData>()

    // 큐 접근 동기화를 위한 Mutex
    private val queueAccessMutex = Mutex()

    // GPS 상태 추적 (DataSynchronizer와 동기화)
    @Volatile
    private var currentGpsStatus = false

    /**
     * 독립적 데이터 엔트리 정의 (Mathematical Data Structures)
     */
    data class IndependentGpsEntry(
        val location: Location,
        val systemTime: Long,
        val monoTime: Long,
        val captureTime: Long = System.currentTimeMillis()
    )

    data class IndependentImuEntry(
        val imuData: FloatArray,  // 9DOF: [acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z]
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

    // ========== 상태 관리 및 동시성 제어 ==========

    private var isLogSavingEnabled = false
    private var isLiveStreamingEnabled = false

    // Actor Model 기반 동시성 제어
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val encodingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 네트워크 스트리밍
    private lateinit var liveStreamingClient: StreamingClient
    private var currentTransportType: String? = null

    // Video encoder (기존 StableVideoEncoder 클래스 유지)
    private val lastBatchTime = AtomicLong(System.currentTimeMillis())
    private var currentEncoder: StableVideoEncoder? = null
    private val encoderMutex = Mutex()
    private var currentSessionTimestamp: String? = null

    init {
        startBatchProcessor()
        startQueueMonitoring()
    }

    // ========== 공개 인터페이스 (Public API) ==========

    /**
     * GPS 데이터 수집 - Dual Queue System
     * 수학적 모델: P(GPS) → Q_sync ∩ Q_raw (데이터 복제)
     */
    fun pushGps(loc: Location, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        // 1. DataSynchronizer에 전송 (기존 동기화 로직)
        dataSynchronizer.addGpsData(loc, sysTs, monoTs)

        // 2. ✅ LogManager 독립 큐에 저장 (Deep Copy)
        if (shouldSave()) {
            val gpsEntry = IndependentGpsEntry(
                location = Location(loc), // Deep copy of Location
                systemTime = sysTs,
                monoTime = monoTs
            )

            independentGpsQueue.offer(gpsEntry)

            // 큐 크기 제한 (Memory management)
            maintainQueueSize(independentGpsQueue, MAX_GPS_QUEUE)

            Log.d(TAG, "독립 GPS 큐에 데이터 추가: lat=${loc.latitude}, lon=${loc.longitude}")
        }

        // 3. 라이브 스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendGpsData(loc, sysTs, monoTs)
        }
    }

    /**
     * IMU 데이터 수집 - High Frequency Sensor Management
     * 수학적 모델: f_IMU = 100Hz, Buffer management with optimal sampling
     */
    fun pushImu(imu: FloatArray, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        // 1. DataSynchronizer에 전송
        dataSynchronizer.addImuData(imu, sysTs, monoTs)

        // 2. ✅ LogManager 독립 큐에 저장
        if (shouldSave()) {
            val imuEntry = IndependentImuEntry(
                imuData = imu.clone(), // Deep copy of FloatArray
                systemTime = sysTs,
                monoTime = monoTs
            )

            independentImuQueue.offer(imuEntry)
            maintainQueueSize(independentImuQueue, MAX_IMU_QUEUE)

            // High frequency 센서이므로 로그 제한
            if (independentImuQueue.size % 50 == 0) {
                Log.d(TAG, "독립 IMU 큐 크기: ${independentImuQueue.size}")
            }
        }

        // 3. 라이브 스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendImuData(imu, sysTs, monoTs)
        }
    }

    /**
     * GNSS 데이터 수집
     */
    fun pushGnss(g: GnssData) {
        // 1. DataSynchronizer에 전송
        dataSynchronizer.addGnssData(g)

        // 2. ✅ LogManager 독립 큐에 저장
        if (shouldSave()) {
            val gnssEntry = IndependentGnssEntry(gnssData = g)
            independentGnssQueue.offer(gnssEntry)
            maintainQueueSize(independentGnssQueue, MAX_GNSS_QUEUE)

            Log.d(TAG, "독립 GNSS 큐에 데이터 추가: type=${g.gnssType}, sat=${g.satelliteId}")
        }

        // 3. 라이브 스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendGnssData(g)
        }
    }

    /**
     * Bounding Box 데이터 수집
     */
    fun pushBoundingBox(bboxes: List<BoundingBoxLog>) {
        // 1. DataSynchronizer에 전송
        dataSynchronizer.addBoundingBoxData(bboxes)

        // 2. ✅ LogManager 독립 큐에 저장
        if (shouldSave()) {
            val bboxEntry = IndependentBboxEntry(bboxData = bboxes.toList()) // Deep copy
            independentBboxQueue.offer(bboxEntry)
            maintainQueueSize(independentBboxQueue, MAX_BBOX_QUEUE)

            Log.d(TAG, "독립 BBOX 큐에 데이터 추가: count=${bboxes.size}")
        }

        // 3. 라이브 스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendBoundingBoxData(bboxes)
        }
    }

    /**
     * Camera 데이터 수집 - Video Encoding + Independent Storage
     */
    fun pushCamera(data: SensorData) {
        // 1. DataSynchronizer에 전송
        dataSynchronizer.addCameraData(data)

        // 2. ✅ Video encoding 큐에 저장
        if (shouldSave()) {
            frameBuffer.offer(data)
            while (frameBuffer.size > MAX_FRAME_BUFFER) {
                frameBuffer.poll()
            }

            // 독립 Camera 큐에도 저장 (메타데이터용)
            val cameraEntry = IndependentCameraEntry(cameraData = data)
            independentCameraQueue.offer(cameraEntry)
            maintainQueueSize(independentCameraQueue, MAX_CAMERA_QUEUE)
        }

        // 3. 라이브 스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendCameraData(data)
        }
    }

    // ========== 큐 관리 시스템 (Queue Management System) ==========

    /**
     * 큐 크기 제한 - Memory Management Theory
     * 수학적 원리: FIFO with bounded capacity (M/M/1/K 모델)
     */
    private fun <T> maintainQueueSize(queue: ConcurrentLinkedQueue<T>, maxSize: Int) {
        while (queue.size > maxSize) {
            queue.poll() // 가장 오래된 데이터 제거
        }
    }

    /**
     * 큐 모니터링 시스템 - Statistical Analysis
     */
    private fun startQueueMonitoring() {
        ioScope.launch {
            while (isActive) {
                try {
                    val status = getIndependentQueueStatus()

                    // 메모리 사용량 모니터링
                    if (status.totalDataPoints > 500) {
                        Log.d(TAG, "큐 상태: GPS=${status.gpsQueueSize}, IMU=${status.imuQueueSize}, " +
                                "GNSS=${status.gnssQueueSize}, BBOX=${status.bboxQueueSize}, " +
                                "CAMERA=${status.cameraQueueSize}")
                    }

                    // GPS 상태 업데이트
                    currentGpsStatus = dataSynchronizer.getGpsStatus().isGpsAvailable

                    delay(10000) // 10초마다 모니터링
                } catch (e: Exception) {
                    Log.e(TAG, "큐 모니터링 오류: ${e.message}", e)
                }
            }
        }
    }

    // ========== 배치 처리 시스템 (Batch Processing System) ==========

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

    /**
     * 배치 처리 - Hybrid Data Management
     * 수학적 모델: Video Encoding ∩ Text Data Persistence
     */
    private suspend fun processBatch(reason: String) {
        if (frameBuffer.isEmpty()) return

        encoderMutex.withLock {
            try {
                // 1. Video Encoding (기존 로직 유지)
                val currentHourlyId = SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date())
                if (currentEncoder != null && currentEncoder!!.getSessionId() != currentHourlyId) {
                    currentEncoder?.finalizeEncoding()
                    currentEncoder = null
                }

                val shouldInitialize = currentEncoder?.isActive() != true
                if (shouldInitialize) {
                    currentEncoder = StableVideoEncoder()
                    val initialized = currentEncoder!!.initializeEncoder()
                    if (!initialized) {
                        Log.e(TAG, "비디오 인코더 초기화 실패")
                        return
                    }
                    currentSessionTimestamp = currentEncoder!!.getSessionId()
                }

                val framesToProcess = mutableListOf<SensorData>()
                while (frameBuffer.isNotEmpty() && framesToProcess.size < BATCH_SIZE) {
                    frameBuffer.poll()?.let { framesToProcess.add(it) }
                }

                var encodedFrames = 0
                for (frame in framesToProcess) {
                    frame.bitmap?.let { bitmap ->
                        if (currentEncoder?.encodeFrame(bitmap) == true) {
                            encodedFrames++
                        }
                    }
                }

                // 2. ✅ Text Data 저장 - Hybrid Approach
                saveHybridTextData()

                lastBatchTime.set(System.currentTimeMillis())
                Log.d(TAG, "$reason 완료: ${encodedFrames}/${framesToProcess.size}프레임, 독립 큐 데이터 저장 완료")

            } catch (e: Exception) {
                Log.e(TAG, "배치 처리 실패: ${e.message}", e)
            }
        }
    }

    /**
     * ✅ 하이브리드 텍스트 데이터 저장
     * - Raw 데이터: 독립 큐에서 추출
     * - GPS Sync 데이터: DataSynchronizer에서 추출
     */
    private suspend fun saveHybridTextData() = withContext(Dispatchers.IO) {
        try {
            val baseDir = File(Environment.getExternalStorageDirectory(), "Documents/gnss")
            val hourlyDir = File(baseDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

            if (!hourlyDir.exists()) {
                val created = hourlyDir.mkdirs()
                Log.d(TAG, "디렉토리 생성: ${hourlyDir.absolutePath}, 성공: $created")
            }

            Log.d(TAG, "=== 하이브리드 텍스트 데이터 저장 시작 ===")

            // 병렬 저장 실행
            listOf(
                async { saveIndependentGpsData(hourlyDir) },
                async { saveIndependentImuData(hourlyDir) },
                async { saveIndependentGnssData(hourlyDir) },
                async { saveSynchronizedGpsData(hourlyDir) } // ← 동기화된 데이터만 이것
            ).awaitAll()

            Log.d(TAG, "=== 하이브리드 텍스트 데이터 저장 완료 ===")

        } catch (e: Exception) {
            Log.e(TAG, "하이브리드 텍스트 저장 실패: ${e.message}", e)
        }
    }

    /**
     * ✅ 독립 GPS Raw 데이터 저장
     */
    private suspend fun saveIndependentGpsData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_gps.txt")

        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGpsEntry>()

            // 배치 크기만큼 데이터 추출
            repeat(BATCH_SIZE) {
                independentGpsQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val gpsContent = buildIndependentGpsContent(dataToSave)

            try {
                BufferedWriter(FileWriter(file, true), BUFFER_SIZE).use { writer ->
                    if (!file.exists() || file.length() == 0L) {
                        writer.write(RAW_GPS_HEADER)
                        writer.newLine()
                    }
                    writer.write(gpsContent)
                    writer.flush()
                }

                Log.d(TAG, "독립 GPS 저장 완료: ${dataToSave.size}개 엔트리, ${file.length()} bytes")

            } catch (e: Exception) {
                Log.e(TAG, "독립 GPS 저장 실패: ${e.message}", e)
            }
        }
    }

    /**
     * ✅ 독립 IMU Raw 데이터 저장
     */
    private suspend fun saveIndependentImuData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_imu.txt")

        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentImuEntry>()

            // IMU는 고주파 센서이므로 더 많은 데이터 처리
            repeat(BATCH_SIZE * 3) {
                independentImuQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val imuContent = buildIndependentImuContent(dataToSave)

            try {
                BufferedWriter(FileWriter(file, true), BUFFER_SIZE).use { writer ->
                    if (!file.exists() || file.length() == 0L) {
                        writer.write(RAW_IMU_HEADER)
                        writer.newLine()
                    }
                    writer.write(imuContent)
                    writer.flush()
                }

                Log.d(TAG, "독립 IMU 저장 완료: ${dataToSave.size}개 엔트리, ${file.length()} bytes")

            } catch (e: Exception) {
                Log.e(TAG, "독립 IMU 저장 실패: ${e.message}", e)
            }
        }
    }

    /**
     * ✅ 독립 GNSS Raw 데이터 저장
     */
    private suspend fun saveIndependentGnssData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_gnss.txt")

        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGnssEntry>()

            repeat(BATCH_SIZE) {
                independentGnssQueue.poll()?.let { dataToSave.add(it) }
            }

            if (dataToSave.isEmpty()) return@withLock

            val gnssContent = buildIndependentGnssContent(dataToSave)

            try {
                BufferedWriter(FileWriter(file, true), BUFFER_SIZE).use { writer ->
                    if (!file.exists() || file.length() == 0L) {
                        writer.write(RAW_GNSS_HEADER)
                        writer.newLine()
                    }
                    writer.write(gnssContent)
                    writer.flush()
                }

                Log.d(TAG, "독립 GNSS 저장 완료: ${dataToSave.size}개 엔트리, ${file.length()} bytes")

            } catch (e: Exception) {
                Log.e(TAG, "독립 GNSS 저장 실패: ${e.message}", e)
            }
        }
    }

    /**
     * ✅ 동기화된 GPS 데이터 저장 (기존 함수 사용)
     */
    private suspend fun saveSynchronizedGpsData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "gps_sync.txt")

        // DataSynchronizer에서 동기화된 데이터 추출
        val syncData = dataSynchronizer.extractSynchronizedData(force = true)

        if (syncData.isEmpty()) return@withContext

        val syncContent = buildGpsSyncContent(syncData)

        try {
            BufferedWriter(FileWriter(file, true), BUFFER_SIZE).use { writer ->
                if (!file.exists() || file.length() == 0L) {
                    writer.write(GPS_SYNC_HEADER)
                    writer.newLine()
                }
                writer.write(syncContent)
                writer.flush()
            }

            Log.d(TAG, "동기화 GPS 저장 완료: ${syncData.size}개 엔트리, ${file.length()} bytes")

        } catch (e: Exception) {
            Log.e(TAG, "동기화 GPS 저장 실패: ${e.message}", e)
        }
    }

    // ========== 콘텐츠 빌더 함수들 (Content Builders) ==========

    /**
     * ✅ 독립 GPS 콘텐츠 빌드
     */
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

    /**
     * ✅ 독립 IMU 콘텐츠 빌드
     */
    private fun buildIndependentImuContent(dataList: List<IndependentImuEntry>): String {
        return buildString(dataList.size * 200) {
            for (entry in dataList) {
                val imu = entry.imuData

                if (imu.size >= 9) {
                    append("${entry.captureTime}\t")
                    append("${entry.systemTime}\t")
                    append("${entry.monoTime}\t")
                    append("${imu[0]}\t${imu[1]}\t${imu[2]}\t")      // Accelerometer
                    append("${imu[3]}\t${imu[4]}\t${imu[5]}\t")      // Gyroscope
                    append("${imu[6]}\t${imu[7]}\t${imu[8]}\t")      // Magnetometer
                    append("${if (currentGpsStatus) "AVAILABLE" else "LOST"}")
                    append("\n")
                }
            }
        }
    }

    /**
     * ✅ 독립 GNSS 콘텐츠 빌드
     */
    private fun buildIndependentGnssContent(dataList: List<IndependentGnssEntry>): String {
        return buildString(dataList.size * 300) {
            for (entry in dataList) {
                val gnss = entry.gnssData

                append("${entry.captureTime}\t")
                append("${gnss.timestamp}\t")
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

    /**
     * ✅ 동기화된 GPS 콘텐츠 빌드 (기존 함수)
     */
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

    // ========== 상태 정보 및 모니터링 ==========

    /**
     * 독립 큐 상태 정보
     */
    data class IndependentQueueStatusInfo(
        val gpsQueueSize: Int,
        val imuQueueSize: Int,
        val gnssQueueSize: Int,
        val bboxQueueSize: Int,
        val cameraQueueSize: Int,
        val totalDataPoints: Int,
        val gpsStatus: Boolean
    )

    fun getIndependentQueueStatus(): IndependentQueueStatusInfo {
        return IndependentQueueStatusInfo(
            gpsQueueSize = independentGpsQueue.size,
            imuQueueSize = independentImuQueue.size,
            gnssQueueSize = independentGnssQueue.size,
            bboxQueueSize = independentBboxQueue.size,
            cameraQueueSize = independentCameraQueue.size,
            totalDataPoints = independentGpsQueue.size + independentImuQueue.size +
                    independentGnssQueue.size + independentBboxQueue.size +
                    independentCameraQueue.size,
            gpsStatus = currentGpsStatus
        )
    }

    // ========== 나머지 기존 함수들 (Video Encoder, Live Streaming 등) ==========

    // StableVideoEncoder inner class (기존과 동일)
    // 설정 함수들 (enableLogSaving, disableLogSaving 등)
    // 라이브 스트리밍 함수들
    // 헤더 정의들 (RAW_GPS_HEADER, RAW_IMU_HEADER 등)

    private inline fun shouldSave() = isLogSavingEnabled
    private inline fun shouldLiveStream() = isLiveStreamingEnabled

    // ========== 헤더 정의 ==========

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
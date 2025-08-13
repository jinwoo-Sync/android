package com.example.myapplication.data.logging

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.myapplication.data.VideoEncoder.SimpleVideoEncoder
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.data.streaming.StreamingClientFactory
import com.example.myapplication.data.sync.*
import com.example.myapplication.model.*
import com.example.myapplication.Logsystem.ResourceMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import com.example.myapplication.DataStructure.CircularQueue

class LoggerManager private constructor(
    private val context: Context,
    private val dataSynchronizer: DataSynchronizer
) {
    companion object {
        private const val TAG = "LoggerManager"
        private const val VIDEO_WIDTH = 840
        private const val VIDEO_HEIGHT = 840
        private const val VIDEO_FPS = 15
        private const val VIDEO_BITRATE = 2_000_000 // 1.2M → 2M으로 증가
        private const val I_FRAME_INTERVAL = 2   // 2 → 1로 더 자주 I-프레임 <- 이건 추후 비교

        //  기존 동기화용 설정 (유지)
        private const val BATCH_SIZE = 15
        private const val BATCH_TIMEOUT_MS = 4000L
        private const val MAX_FRAME_BUFFER = 45

        //  비디오 전용 설정 - 무결성 보장을 위한 확장
        private const val MAX_VIDEO_FRAME_BUFFER = 3600  // 15fps * 240초 = 4분분량 (무결성 보장)
        private const val VIDEO_BATCH_SIZE = 30          // 2초분 배치 처리 (안정적)
        private const val VIDEO_BATCH_TIMEOUT_MS = 1000L // 1초 타임아웃 (빠른 처리)
        private const val VIDEO_PROCESSING_INTERVAL = 100L // 100ms마다 체크 (더 자주)

        private const val MAX_GPS_QUEUE = 100
        private const val MAX_IMU_QUEUE = 5000
        private const val MAX_GNSS_QUEUE = 100
        private const val MAX_BBOX_QUEUE = 50
        private const val MAX_CAMERA_QUEUE = 1200
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
    }

    //  비디오 프레임 전용 데이터 클래스
    data class VideoFrameEntry(
        val bitmap: android.graphics.Bitmap,
        val frameId: Long,
        val timestamp: Long,
        val monoTimestamp: Long,
        val sequenceNumber: Long,
        val captureTime: Long = System.currentTimeMillis()
    )

    //  비디오 프레임 메타데이터 클래스
    data class VideoFrameMetadata(
        val frameId: Long,
        val sequenceNumber: Long,
        val timestamp: Long,
        val monoTimestamp: Long,
        val captureTime: Long,
        val encodingTime: Long,
        val sessionId: String,
        val width: Int,
        val height: Int,
        val bitmapSizeMB: Double,
        val encodingSuccess: Boolean
    )

    // DataSynchronizer 인터페이스 구현체들 (기존 유지)
    data class IndependentGpsEntry(
        override val location: Location,
        override val systemTime: Long,
        override val monoTime: Long,
        override val captureTime: Long = System.currentTimeMillis()
    ) : GpsEntry

    data class IndependentImuEntry(
        override val imuData: FloatArray,
        override val systemTime: Long,
        override val monoTime: Long,
        override val captureTime: Long = System.currentTimeMillis()
    ) : ImuEntry {
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
        override val gnssData: GnssData,
        override val captureTime: Long = System.currentTimeMillis()
    ) : GnssEntry

    data class IndependentBboxEntry(
        override val bboxData: List<BoundingBoxLog>,
        override val captureTime: Long = System.currentTimeMillis()
    ) : BboxEntry

    data class IndependentCameraEntry(
        override val cameraData: SensorData,
        override val captureTime: Long = System.currentTimeMillis()
    ) : CameraEntry

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

    //  기존 CircularQueue들 (동기화용)
    private val independentGpsQueue = CircularQueue<IndependentGpsEntry>(MAX_GPS_QUEUE)
    private val independentImuQueue = CircularQueue<IndependentImuEntry>(MAX_IMU_QUEUE)
    private val independentGnssQueue = CircularQueue<IndependentGnssEntry>(MAX_GNSS_QUEUE)
    private val independentBboxQueue = CircularQueue<IndependentBboxEntry>(MAX_BBOX_QUEUE)
    private val independentCameraQueue = CircularQueue<IndependentCameraEntry>(MAX_CAMERA_QUEUE)
    private val comprehensiveGnssQueue = CircularQueue<IndependentComprehensiveGnssEntry>(MAX_COMPREHENSIVE_GNSS_QUEUE)
    private val satelliteStatusQueue = CircularQueue<IndependentSatelliteStatusEntry>(MAX_SATELLITE_STATUS_QUEUE)
    private val navigationMessageQueue = CircularQueue<IndependentNavigationEntry>(MAX_NAVIGATION_QUEUE)
    private val antennaInfoQueue = CircularQueue<IndependentAntennaEntry>(MAX_ANTENNA_QUEUE)
    private val gnssClockQueue = CircularQueue<IndependentGnssClockEntry>(MAX_GNSS_CLOCK_QUEUE)
    private val gnssSessionQueue = CircularQueue<IndependentGnssSessionEntry>(MAX_GNSS_SESSION_QUEUE)
    private val frameBuffer = CircularQueue<SensorData>(MAX_FRAME_BUFFER)

    //  비디오 전용 시스템 (새로 추가)
    private val videoFrameQueue = CircularQueue<VideoFrameEntry>(MAX_VIDEO_FRAME_BUFFER)
    private val videoMetadataList = mutableListOf<VideoFrameMetadata>()
    private val videoProcessingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastVideoProcessTime = AtomicLong(System.currentTimeMillis())
    private val videoFrameCounter = AtomicLong(0)
    private val videoSequenceNumber = AtomicLong(0)

    private val memoryMonitor = MemoryMonitor()
    private val queueAccessMutex = Mutex()
    private val videoMetadataMutex = Mutex()

    @Volatile
    private var currentGpsStatus = false

    // **리소스 모니터 추가**
    private val resourceMonitor = ResourceMonitor.getInstance(context)

    private inner class MemoryMonitor {
        fun getMemoryPressure(): Float {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            return (usedMemory.toFloat() / maxMemory.toFloat()).coerceIn(0f, 1f)
        }

        fun isMemoryPressureHigh(): Boolean = getMemoryPressure() > 0.8f
    }

    private var isLogSavingEnabled = false
    private var isLiveStreamingEnabled = false
    private var currentLogDirectory: File? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val encodingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var liveStreamingClient: StreamingClient
    private var currentTransportType: String? = null
    private val lastBatchTime = AtomicLong(System.currentTimeMillis())
    private var videoEncoder: SimpleVideoEncoder? = null
    private val encoderMutex = Mutex()
    private var currentSessionTimestamp: String? = null

    init {
        startBatchProcessor()        // 기존 동기화 배치 프로세서
        startVideoProcessor()        // 비디오 전용 프로세서
        startQueueMonitoring()
        startGpsRecoveryMonitoring() // GPS 복구 전용 모니터링 추가
        startMemoryMonitoring()      //  메모리 모니터링 추가
    }

    //  비디오 전용 프로세서 시작
    private fun startVideoProcessor() {
        videoProcessingScope.launch {
            Log.d(TAG, "🎬 비디오 전용 프로세서 시작 (버퍼: ${MAX_VIDEO_FRAME_BUFFER}프레임)")
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastProcess = currentTime - lastVideoProcessTime.get()

                    //  로깅 상태와 관계없이 큐 상태 확인
                    if (videoFrameQueue.size() >= VIDEO_BATCH_SIZE ||
                        (videoFrameQueue.isNotEmpty() && timeSinceLastProcess >= VIDEO_BATCH_TIMEOUT_MS)) {

                        if (isLogSavingEnabled) {
                            processVideoFrames("비디오 전용 배치")
                        } else {
                            //  로깅 비활성화 시에는 큐만 정리
                            processVideoFrames("비디오 큐 정리")
                        }
                    }
                    delay(VIDEO_PROCESSING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 비디오 프로세서 오류: ${e.message}", e)
                    delay(1000)
                }
            }
        }
    }

    //  메인 스레드를 절대 블록하지 않는 완전한 비동기 처리
    fun pushCamera(data: SensorData) {
        if (shouldSave()) {
            // 기존 동기화 처리 (기존 형식 완전 유지)
            frameBuffer.push(data)
            val cameraEntry = IndependentCameraEntry(cameraData = data)
            independentCameraQueue.push(cameraEntry)

            //  비디오 처리를 완전히 백그라운드로 분리
            data.bitmap?.let { originalBitmap ->
                if (!originalBitmap.isRecycled && originalBitmap.width > 0 && originalBitmap.height > 0) {
                    // 메인 스레드를 절대 블록하지 않는 코루틴 실행
                    videoProcessingScope.launch(Dispatchers.IO) {
                        processVideoFrameAsync(originalBitmap, data)
                    }
                }
            }
        }
    }

    //  비동기 비디오 프레임 처리 함수 추가 - 무결성 보장
    private suspend fun processVideoFrameAsync(originalBitmap: Bitmap, data: SensorData) {
        try {
            // 무결성 우선: 메모리 압박 시에도 프레임 절대 드롭하지 않음
            // 대신 오래된 프레임만 정리하고 현재 프레임은 반드시 처리
            if (memoryMonitor.isMemoryPressureHigh()) {
                Log.w(TAG, "🚨 메모리 압박 - 오래된 프레임 정리 (현재 프레임은 보장)")
                clearOldVideoFrames(200) // 더 많이 정리
                // early return 제거 - 현재 프레임은 반드시 처리
            }

            // 안전한 비트맵 복사 - 실패 시 재시도
            var videoBitmap: Bitmap? = null
            var retryCount = 0
            while (videoBitmap == null && retryCount < 3) {
                videoBitmap = try {
                    withContext(Dispatchers.Default) {
                        originalBitmap.copy(originalBitmap.config ?: Bitmap.Config.RGB_565, false) // 메모리 절약
                    }
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "🔄 비트맵 복사 재시도 ${retryCount + 1}/3: ${e.message}")
                    clearOldVideoFrames(300)
                    System.gc()
                    delay(50)
                    retryCount++
                    null
                }
            }

            if (videoBitmap != null) {
                val sequenceNum = videoSequenceNumber.incrementAndGet()
                val videoEntry = VideoFrameEntry(
                    bitmap = videoBitmap,
                    frameId = data.frameId,
                    timestamp = data.timestamp,
                    monoTimestamp = data.monoTimestamp,
                    sequenceNumber = sequenceNum
                )

                videoFrameQueue.push(videoEntry)
                val frameNum = videoFrameCounter.incrementAndGet()
                Log.d(TAG, "비디오 프레임 비동기 추가: #$frameNum, seq=$sequenceNum, 큐=${videoFrameQueue.size()}")

                // 큐 상태 모니터링 및 적극적 처리
                val queuePercent = (videoFrameQueue.size() * 100 / MAX_VIDEO_FRAME_BUFFER)
                if (queuePercent > 70) {
                    Log.w(TAG, "🚨 비디오 큐 사용률 높음: ${queuePercent}% (${videoFrameQueue.size()}/${MAX_VIDEO_FRAME_BUFFER}) - 즉시 처리 트리거")
                    // 즉시 처리 트리거 (무결성 보장)
                    videoProcessingScope.launch {
                        processVideoFrames("큐 사용률 높음 - 무결성 보장")
                    }
                }
                
                Log.v(TAG, "📹 프레임 추가 성공: seq=$sequenceNum, 큐=${videoFrameQueue.size()}/${MAX_VIDEO_FRAME_BUFFER}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "비동기 비디오 처리 실패: ${e.message}")
        }
    }

    //  비디오 전용 프레임 처리 - 최적화 적용
    private suspend fun processVideoFrames(reason: String) = withContext(Dispatchers.IO) {
        if (!isLogSavingEnabled) {
            //  큐 정리를 더 효율적으로
            val clearedCount = clearVideoQueueSafely(100)
            Log.w(TAG, "로깅 비활성화 - 비디오 큐 정리: ${clearedCount}개")
            return@withContext
        }

        if (videoFrameQueue.isEmpty()) return@withContext

        //  메모리 체크를 더 엄격하게
        val memoryPressure = memoryMonitor.getMemoryPressure()
        if (memoryPressure > 0.85f) {
            Log.w(TAG, "메모리 압박으로 비디오 처리 스킵: ${(memoryPressure * 100).toInt()}%")
            clearVideoQueueSafely(50)
            System.gc()
            delay(500)
            return@withContext
        }

        //  기존 비디오 처리 로직 (로깅 활성화 시에만 실행)
        videoSessionMutex.withLock {
            try {
                val startTime = System.currentTimeMillis()
                Log.d(TAG, " 비디오 처리 시작: $reason, 큐=${videoFrameQueue.size()}")

                val commonDirectory = getCurrentDataDirectory()
                val currentMinuteId = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

                //  세션 변경 감지 및 처리 개선
                val currentEncoderSession = videoEncoder?.getSessionId()

                if (currentEncoderSession != null && currentEncoderSession != currentMinuteId) {
                    Log.d(TAG, " 세션 변경 감지: $currentEncoderSession → $currentMinuteId")

                    //  현재 세션의 메타데이터 즉시 저장
                    saveVideoMetadata()

                    //  인코더 재시작
                    videoEncoder!!.stopRecording()
                    Log.d(TAG, " 세션 종료: $currentEncoderSession")

                    videoEncoder = null
                    System.gc()
                    delay(100)

                    //  새 세션 시작
                    videoEncoder = SimpleVideoEncoder(context)
                    videoEncoder!!.setOutputDirectory(commonDirectory)
                    val started = videoEncoder!!.startRecording()

                    if (started) {
                        currentSessionTimestamp = videoEncoder!!.getSessionId()
                        Log.d(TAG, " 새 세션 시작: $currentSessionTimestamp")
                    } else {
                        Log.e(TAG, " 새 세션 시작 실패")
                        return@withLock
                    }
                } else if (videoEncoder == null) {
                    //  인코더가 없을 때 새로 생성
                    videoEncoder = SimpleVideoEncoder(context)
                    videoEncoder!!.setOutputDirectory(commonDirectory)
                    val started = videoEncoder!!.startRecording()

                    if (started) {
                        currentSessionTimestamp = videoEncoder!!.getSessionId()
                        Log.d(TAG, " 비디오 인코더 재생성: $currentSessionTimestamp")
                    } else {
                        Log.e(TAG, " 비디오 인코더 생성 실패")
                        return@withLock
                    }
                }

                // 프레임 처리 (기존 로직 유지)
                val framesToProcess = mutableListOf<VideoFrameEntry>()
                var batchSize = VIDEO_BATCH_SIZE

                val memoryPressure = memoryMonitor.getMemoryPressure()
                if (memoryPressure > 0.8f) {
                    batchSize = (VIDEO_BATCH_SIZE * 0.5).toInt()
                    Log.w(TAG, " 메모리 압박으로 비디오 배치 크기 감소: $batchSize")
                }

                repeat(batchSize.coerceAtMost(videoFrameQueue.size())) {
                    videoFrameQueue.poll()?.let { framesToProcess.add(it) }
                }

                var encodedCount = 0
                var skippedCount = 0

                for (frameEntry in framesToProcess) {
                    val encodingStartTime = System.currentTimeMillis()
                    var encodingSuccess = false

                    try {
                        if (!frameEntry.bitmap.isRecycled &&
                            frameEntry.bitmap.width > 0 &&
                            frameEntry.bitmap.height > 0) {

                            if (videoEncoder?.addFrame(frameEntry.bitmap) == true) {
                                encodedCount++
                                encodingSuccess = true
                            } else {
                                skippedCount++
                            }
                        } else {
                            skippedCount++
                        }
                    } catch (e: Exception) {
                        skippedCount++
                        Log.e(TAG, " 프레임 처리 오류: seq=${frameEntry.sequenceNumber}, ${e.message}")
                    }

                    //  현재 세션의 실제 ID로 메타데이터 생성
                    val actualSessionId = currentSessionTimestamp ?: "unknown"
                    val bitmapSizeMB = if (!frameEntry.bitmap.isRecycled) {
                        val width = frameEntry.bitmap.width
                        val height = frameEntry.bitmap.height
                        val bytesPerPixel = when (frameEntry.bitmap.config) {
                            Bitmap.Config.ARGB_8888 -> 4
                            Bitmap.Config.RGB_565 -> 2
                            Bitmap.Config.ALPHA_8 -> 1
                            else -> 4
                        }
                        (width * height * bytesPerPixel) / (1024.0 * 1024.0)
                    } else 0.0

                    val metadata = VideoFrameMetadata(
                        frameId = frameEntry.frameId,
                        sequenceNumber = frameEntry.sequenceNumber,
                        timestamp = frameEntry.timestamp,
                        monoTimestamp = frameEntry.monoTimestamp,
                        captureTime = frameEntry.captureTime,
                        encodingTime = encodingStartTime,
                        sessionId = actualSessionId,  // ✅ 실제 세션 ID 사용
                        width = if (!frameEntry.bitmap.isRecycled) frameEntry.bitmap.width else 0,
                        height = if (!frameEntry.bitmap.isRecycled) frameEntry.bitmap.height else 0,
                        bitmapSizeMB = bitmapSizeMB,
                        encodingSuccess = encodingSuccess
                    )

                    videoMetadataMutex.withLock {
                        videoMetadataList.add(metadata)
                    }

                    if (!frameEntry.bitmap.isRecycled) {
                        try {
                            frameEntry.bitmap.recycle()
                        } catch (e: Exception) {
                            Log.w(TAG, "비트맵 해제 실패: ${e.message}")
                        }
                    }
                }

                framesToProcess.clear()
                lastVideoProcessTime.set(System.currentTimeMillis())

                val processingTime = System.currentTimeMillis() - startTime
                Log.d(TAG, " 비디오 배치 완료: 세션=$currentSessionTimestamp, 인코딩=$encodedCount, 스킵=$skippedCount, " +
                        "처리시간=${processingTime}ms, 남은큐=${videoFrameQueue.size()}, 메타데이터=${videoMetadataList.size}")

                //  메타데이터 중간 저장 조건 개선 300 이상이면 저장.
                if (videoMetadataList.size >= 300) {
                    saveVideoMetadata()
                }

            } catch (e: OutOfMemoryError) {
                Log.e(TAG, " 비디오 처리 OOM", e)
                // 응급 처리
                clearOldVideoFrames(100) // 100개 프레임 강제 해제
                videoEncoder?.stopRecording()
                videoEncoder = null
                System.gc()
                delay(1000)
            } catch (e: Exception) {
                Log.e(TAG, " 비디오 처리 예외: ${e.message}", e)
                System.gc()
            }
        }
    }

    //  안전한 큐 정리 함수
    private fun clearVideoQueueSafely(maxCount: Int): Int {
        var clearedCount = 0
        repeat(maxCount.coerceAtMost(videoFrameQueue.size())) {
            videoFrameQueue.poll()?.let { frameEntry ->
                try {
                    if (!frameEntry.bitmap.isRecycled) {
                        frameEntry.bitmap.recycle()
                    }
                    clearedCount++
                } catch (e: Exception) {
                    Log.w(TAG, "비트맵 해제 실패: ${e.message}")
                }
            }
        }
        return clearedCount
    }

    //  응급 비디오 프레임 정리 (기존 함수와 통합)
    private fun clearOldVideoFrames(count: Int) {
        repeat(count.coerceAtMost(videoFrameQueue.size())) {
            videoFrameQueue.poll()?.let { frameEntry ->
                if (!frameEntry.bitmap.isRecycled) {
                    frameEntry.bitmap.recycle()
                }
            }
        }
        Log.w(TAG, " 응급 비디오 프레임 정리: ${count}개, 남은=${videoFrameQueue.size()}")
    }

    //  메모리 모니터링 강화
    private fun startMemoryMonitoring() {
        ioScope.launch {
            while (isActive) {
                try {
                    val memoryPressure = memoryMonitor.getMemoryPressure()

                    // 메모리 압박 시 적극적인 정리
                    if (memoryPressure > 0.8f) {
                        Log.w(TAG, "메모리 압박 감지: ${(memoryPressure * 100).toInt()}%")

                        // 비디오 큐 정리
                        clearVideoQueueSafely(200)

                        // GC 강제 실행
                        System.gc()
                        delay(1000)

                        // 극심한 압박 시 비디오 처리 일시 중단
                        if (memoryPressure > 0.9f) {
                            Log.e(TAG, "극심한 메모리 압박 - 비디오 처리 일시 중단")
                            delay(5000)
                        }
                    }

                    delay(2000) // 2초마다 체크
                } catch (e: Exception) {
                    Log.e(TAG, "메모리 모니터링 오류: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    //  비디오 프레임 메타데이터 저장 - 성공/실패 파일 분리
    private suspend fun saveVideoMetadata() = withContext(Dispatchers.IO) {
        try {
            val commonDir = getCurrentDataDirectory()
            val successMetadataFile = File(commonDir, "video_frame_metadata.txt")
            val failedMetadataFile = File(commonDir, "video_frame_metadata2.txt")

            videoMetadataMutex.withLock {
                if (videoMetadataList.isNotEmpty()) {
                    // 성공한 프레임과 실패한 프레임 분리
                    val successfulFrames = videoMetadataList.filter { it.encodingSuccess }
                    val failedFrames = videoMetadataList.filter { !it.encodingSuccess }
                    
                    // 성공한 프레임은 video_frame_metadata.txt에 저장
                    if (successfulFrames.isNotEmpty()) {
                        saveToFile(successMetadataFile, VIDEO_METADATA_HEADER, successfulFrames, ::buildVideoMetadataContent)
                        Log.d(TAG, " 성공 프레임 메타데이터 저장: ${successfulFrames.size}개")
                    }
                    
                    // 실패한 프레임은 video_frame_metadata2.txt에 fail 상태로 저장
                    if (failedFrames.isNotEmpty()) {
                        saveToFile(failedMetadataFile, VIDEO_METADATA2_HEADER, failedFrames, ::buildVideoMetadata2Content)
                        Log.d(TAG, " 실패 프레임 메타데이터 저장: ${failedFrames.size}개 (ENCODING_STATUS=fail)")
                    }
                    
                    val totalSaved = videoMetadataList.size
                    videoMetadataList.clear()
                    Log.d(TAG, " 비디오 메타데이터 저장 완료: 성공=${successfulFrames.size}개, 실패=${failedFrames.size}개, 총=${totalSaved}개")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, " 비디오 메타데이터 저장 실패: ${e.message}", e)
        }
    }

    //  성공한 프레임 메타데이터 내용 생성
    private fun buildVideoMetadataContent(metadataList: List<VideoFrameMetadata>): String {
        return buildString(metadataList.size * 200) {
            for (metadata in metadataList) {
                append("${metadata.frameId}\t")
                append("${metadata.sequenceNumber}\t")
                append("${metadata.timestamp}\t")
                append("${metadata.monoTimestamp}\t")
                append("${metadata.captureTime}\t")
                append("${metadata.encodingTime}\t")
                append("${metadata.sessionId}\t")
                append("${metadata.width}\t")
                append("${metadata.height}\t")
                append("${String.format("%.3f", metadata.bitmapSizeMB)}\t")
                append("SUCCESS")
                append("\n")
            }
        }
    }

    //  실패한 프레임 메타데이터 내용 생성 (ENCODING_STATUS=fail)
    private fun buildVideoMetadata2Content(metadataList: List<VideoFrameMetadata>): String {
        return buildString(metadataList.size * 200) {
            for (metadata in metadataList) {
                append("${metadata.frameId}\t")
                append("${metadata.sequenceNumber}\t")
                append("${metadata.timestamp}\t")
                append("${metadata.monoTimestamp}\t")
                append("${metadata.captureTime}\t")
                append("${metadata.encodingTime}\t")
                append("${metadata.sessionId}\t")
                append("${metadata.width}\t")
                append("${metadata.height}\t")
                append("${String.format("%.3f", metadata.bitmapSizeMB)}\t")
                append("fail")
                append("\n")
            }
        }
    }

    //  비디오 상태 모니터링
    fun getVideoStatus(): String {
        val totalFrames = videoFrameCounter.get()
        val queueSize = videoFrameQueue.size()
        val queuePercent = (queueSize * 100 / MAX_VIDEO_FRAME_BUFFER)
        val lastProcessTime = System.currentTimeMillis() - lastVideoProcessTime.get()
        val metadataCount = videoMetadataList.size

        return buildString {
            appendLine("=== 비디오 저장 상태 ===")
            appendLine("총 처리 프레임: $totalFrames")
            appendLine("비디오 큐: $queueSize/${MAX_VIDEO_FRAME_BUFFER} (${queuePercent}%)")
            appendLine("예상 저장 시간: ${queueSize / 15}초분") // 15fps 기준
            appendLine("마지막 처리: ${lastProcessTime}ms 전")
            appendLine("메타데이터 대기: ${metadataCount}개")
            appendLine("현재 세션: ${currentSessionTimestamp ?: "없음"}")  // ✅ 추가
            appendLine("인코더 상태: ${getVideoEncoderStatus()}")

            // 경고 표시
            if (queuePercent > 80) {
                appendLine(" 큐 사용률 높음: ${queuePercent}%")
            }
            if (lastProcessTime > 5000) {
                appendLine(" 처리 지연: ${lastProcessTime}ms")
            }
            if (metadataCount > 300) {
                appendLine(" 메타데이터 대기 많음: ${metadataCount}개")
            }
        }
    }

    // 나머지 기존 메서드들은 그대로 유지...
    fun pushComprehensiveGnss(comprehensiveData: ComprehensiveGnssData, clockData: GnssClockData?) {
        if (shouldSave()) {
            val comprehensiveEntry = IndependentComprehensiveGnssEntry(
                comprehensiveData = comprehensiveData,
                clockData = clockData
            )
            comprehensiveGnssQueue.push(comprehensiveEntry)
        }
    }

    fun pushSatelliteStatus(satelliteStatus: GnssSatelliteStatus) {
        if (shouldSave()) {
            val statusEntry = IndependentSatelliteStatusEntry(satelliteStatus = satelliteStatus)
            satelliteStatusQueue.push(statusEntry)
        }
    }

    fun pushNavigationMessage(navigationData: GnssNavigationData) {
        if (shouldSave()) {
            val navEntry = IndependentNavigationEntry(navigationData = navigationData)
            navigationMessageQueue.push(navEntry)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun pushAntennaInfo(antennaData: GnssAntennaData) {
        if (shouldSave()) {
            val antennaEntry = IndependentAntennaEntry(antennaData = antennaData)
            antennaInfoQueue.push(antennaEntry)
        }
    }

    fun pushGnssClockData(clockData: GnssClockData) {
        if (shouldSave()) {
            val clockEntry = IndependentGnssClockEntry(clockData = clockData)
            gnssClockQueue.push(clockEntry)
        }
    }

    fun recordFirstFix(ttffMs: Long) {
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
        gnssSessionQueue.push(sessionEntry)
    }

    fun recordSessionEnd(sessionDuration: Long, ttffMs: Long?) {
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
        gnssSessionQueue.push(sessionEntry)
    }

    fun pushGps(loc: Location, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        if (shouldSave()) {
            val gpsEntry = IndependentGpsEntry(
                location = Location(loc),
                systemTime = sysTs,
                monoTime = monoTs
            )
            independentGpsQueue.push(gpsEntry)
        }
    }

    fun pushImu(imu: FloatArray, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        if (shouldSave()) {
            val imuEntry = IndependentImuEntry(
                imuData = imu.clone(),
                systemTime = sysTs,
                monoTime = monoTs
            )
            independentImuQueue.push(imuEntry)
        }
    }

    fun pushGnss(g: GnssData) {
        if (shouldSave()) {
            val gnssEntry = IndependentGnssEntry(gnssData = g)
            independentGnssQueue.push(gnssEntry)
        }
    }

    fun pushBoundingBox(bboxes: List<BoundingBoxLog>) {
        if (shouldSave()) {
            val bboxEntry = IndependentBboxEntry(bboxData = bboxes.toList())
            independentBboxQueue.push(bboxEntry)
        }
    }

    private fun startQueueMonitoring() {
        ioScope.launch {
            while (isActive) {
                val status = getIndependentQueueStatus()
                val memoryPressure = memoryMonitor.getMemoryPressure()
                if (status.totalDataPoints > 500) {
                    Log.d(TAG, "큐 상태: GPS=${status.gpsQueueSize}, IMU=${status.imuQueueSize}, " +
                            "GNSS=${status.gnssQueueSize}, CompGNSS=${status.comprehensiveGnssQueueSize}, " +
                            "Sat=${status.satelliteStatusQueueSize}, Nav=${status.navigationQueueSize}, " +
                            "Video=${videoFrameQueue.size()}/${MAX_VIDEO_FRAME_BUFFER}, " +
                            "Memory=${(memoryPressure * 100).toInt()}%")
                }
                currentGpsStatus = dataSynchronizer.getGpsStatus().isGpsAvailable
                delay(10000)
            }
        }
    }

    private fun startBatchProcessor() {
        ioScope.launch {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastBatch = currentTime - lastBatchTime.get()

                    // GPS 복구 감지 시 즉시 처리
                    if (dataSynchronizer.hasGpsRecoveryData()) {
                        //Log.d(TAG, " GPS 복구 감지 - 즉시 처리 시작")
                        processGpsRecovery()
                    }

                    if (frameBuffer.size() >= BATCH_SIZE || (frameBuffer.isNotEmpty() && timeSinceLastBatch >= BATCH_TIMEOUT_MS)) {
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
                // ✅ GPS 복구는 로깅 상태와 관계없이 처리
                if (dataSynchronizer.hasGpsRecoveryData()) {
                    Log.d(TAG, " GPS 복구 데이터 감지 - 처리 시작")

                    if (isLogSavingEnabled) {
                        val commonDir = getCurrentDataDirectory()
                        saveGpsSynchronizedData(commonDir)
                        Log.d(TAG, " GPS 복구 - gps_sync.txt 저장 완료")
                    } else {
                        Log.w(TAG, " GPS 복구 감지되었으나 로깅 비활성화로 저장 스킵")
                    }
                }

                // ✅ 로깅이 활성화된 경우에만 파일 저장 수행
                if (isLogSavingEnabled) {
                    saveCompleteGnssDataOptimized()
                } else {
                    Log.w(TAG, "️ 로깅 비활성화 - 데이터 저장 스킵")
                }

                lastBatchTime.set(System.currentTimeMillis())

                val queueStatus = getIndependentQueueStatus()
                Log.d(TAG, "$reason 완료: 동기화 처리 완료")

            } catch (e: OutOfMemoryError) {
                Log.e(TAG, " 메모리 부족으로 배치 처리 실패", e)
                frameBuffer.clear()
                independentCameraQueue.clear()
                System.gc()
                delay(500)
            } catch (e: Exception) {
                Log.e(TAG, "배치 처리 실패: ${e.message}", e)
                System.gc()
            }
        }
    }

    /**
     * GPS 복구 시 즉시 GPS 동기화 데이터 저장
     */
    private suspend fun processGpsRecovery() = withContext(Dispatchers.IO) {
        try {
            //  로깅이 비활성화되어 있으면 GPS 복구만 수행하고 저장은 스킵
            if (!isLogSavingEnabled) {
                //Log.w(TAG, " 로깅 비활성화 상태 - GPS 복구 데이터 저장 스킵")

                // GPS 복구는 수행하되 파일 저장은 하지 않음
                if (dataSynchronizer.hasGpsRecoveryData()) {
                    //Log.d(TAG, " GPS 복구 감지 (저장 스킵)")
                }
                return@withContext
            }

            //  로깅 활성화 시에만 파일 저장 수행
            if (dataSynchronizer.hasGpsRecoveryData()) {
                val commonDir = getCurrentDataDirectory()
                saveGpsSynchronizedData(commonDir)
                Log.d(TAG, " GPS 복구 - gps_sync.txt 즉시 저장 완료")
            }
        } catch (e: Exception) {
            Log.e(TAG, " GPS 복구 처리 실패: ${e.message}", e)
        }
    }

    /**
     * GPS 복구 전용 모니터링 - 더 빠른 반응
     */
    private fun startGpsRecoveryMonitoring() {
        ioScope.launch {
            Log.d(TAG, " GPS 복구 모니터링 시작")
            while (isActive) {
                try {
                    if (dataSynchronizer.isGpsRecoveryInProgress()) {
                        Log.d(TAG, " GPS 복구 진행 중 - 상태 확인")

                        val syncData = dataSynchronizer.extractGpsSynchronizedData()

                        if (syncData.isNotEmpty() && isLogSavingEnabled) {
                            // ✅ 로깅이 활성화된 경우에만 저장
                            val commonDir = getCurrentDataDirectory()
                            saveGpsSynchronizedData(commonDir)
                            Log.d(TAG, " GPS 복구 - gps_sync.txt 즉시 저장: ${syncData.size}개")
                        } else if (syncData.isNotEmpty()) {
                            // ✅ 로깅 비활성화 시에는 데이터만 확인
                            Log.w(TAG, " GPS 복구 데이터 ${syncData.size}개 감지되었으나 로깅 비활성화로 저장 스킵")
                        }
                    }

                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ GPS 복구 모니터링 오류: ${e.message}", e)
                    delay(2000)
                }
            }
        }
    }

    /**
     * 수정된 saveCompleteGnssDataOptimized 함수
     */
    private suspend fun saveCompleteGnssDataOptimized() = withContext(Dispatchers.IO) {
        try {
            val commonDir = getCurrentDataDirectory()

            processDataSynchronization()

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
                async { saveGpsSynchronizedData(commonDir) },
                async { saveLocalSynchronizedData(commonDir) }
            )
            jobs.awaitAll()
        } catch (e: Exception) {
            Log.e(TAG, "완전한 GNSS 데이터 저장 실패: ${e.message}", e)
        }
    }
    
    private suspend fun processComprehensiveGnssQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "comprehensive_gnss.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentComprehensiveGnssEntry>()
            repeat(50.coerceAtMost(comprehensiveGnssQueue.size())) {
                comprehensiveGnssQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, COMPREHENSIVE_GNSS_HEADER, dataToSave, ::buildComprehensiveGnssContent)
            }
        }
    }

    private suspend fun processSatelliteStatusQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "satellite_status.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentSatelliteStatusEntry>()
            repeat(30.coerceAtMost(satelliteStatusQueue.size())) {
                satelliteStatusQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, SATELLITE_STATUS_HEADER, dataToSave, ::buildSatelliteStatusContent)
            }
        }
    }

    private suspend fun processNavigationMessageQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "navigation_messages.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentNavigationEntry>()
            repeat(20.coerceAtMost(navigationMessageQueue.size())) {
                navigationMessageQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, NAVIGATION_MESSAGE_HEADER, dataToSave, ::buildNavigationMessageContent)
            }
        }
    }

    private suspend fun processAntennaInfoQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "antenna_info.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentAntennaEntry>()
            repeat(10.coerceAtMost(antennaInfoQueue.size())) {
                antennaInfoQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, ANTENNA_INFO_HEADER, dataToSave, ::buildAntennaInfoContent)
            }
        }
    }

    private suspend fun processGnssClockQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "gnss_clock.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGnssClockEntry>()
            repeat(20.coerceAtMost(gnssClockQueue.size())) {
                gnssClockQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, GNSS_CLOCK_HEADER, dataToSave, ::buildGnssClockContent)
            }
        }
    }

    private suspend fun processGnssSessionQueue(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "gnss_sessions.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGnssSessionEntry>()
            repeat(10.coerceAtMost(gnssSessionQueue.size())) {
                gnssSessionQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, GNSS_SESSION_HEADER, dataToSave, ::buildGnssSessionContent)
            }
        }
    }

    /**
     * 동기화 처리 메서드 - CircularQueue 타입 캐스팅
     */
    private suspend fun processDataSynchronization() {
        try {
            // DataSynchronizer에 CircularQueue 참조 전달 (타입 캐스팅)
            dataSynchronizer.performSynchronization(
                gpsQueue = independentGpsQueue as CircularQueue<GpsEntry>,
                imuQueue = independentImuQueue as CircularQueue<ImuEntry>,
                gnssQueue = independentGnssQueue as CircularQueue<GnssEntry>,
                cameraQueue = independentCameraQueue as CircularQueue<CameraEntry>,
                bboxQueue = independentBboxQueue as CircularQueue<BboxEntry>
            )
        } catch (e: Exception) {
            Log.e(TAG, "데이터 동기화 처리 실패: ${e.message}", e)
        }
    }

    /**
     * GPS 동기화 데이터 저장
     */
    private suspend fun saveGpsSynchronizedData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "gps_sync.txt")
        val allSyncData = dataSynchronizer.extractGpsSynchronizedData()

        // 🎯 GPS 데이터가 실제로 있는 경우만 필터링
        val validGpsSyncData = allSyncData.filter { syncResult ->
            val gpsEntry = syncResult.gpsEntry
            val hasValidGps = gpsEntry?.location?.let { location ->
                location.latitude != 0.0 &&
                        location.longitude != 0.0 &&
                        location.hasAccuracy() &&
                        location.accuracy < 100.0f
            } ?: false

            if (!hasValidGps) {
                //Log.d(TAG, "🎯 GPS 데이터 없는 동기화 결과 필터링: ${syncResult.hybridTime}")
            }

            hasValidGps
        }

        if (validGpsSyncData.isNotEmpty()) {
            // 🎯 GPS 복구 데이터 우선 처리
            val reprocessedCount = validGpsSyncData.count { it.gpsEntry == null }

            saveToFile(file, GPS_SYNC_HEADER, validGpsSyncData, ::buildGpsSyncContent)

            Log.d(TAG, "✅ GPS 동기화 데이터 저장: 총 ${validGpsSyncData.size}개 (전체: ${allSyncData.size}개)")
            if (reprocessedCount > 0) {
                Log.d(TAG, "✅ 재처리 데이터: ${reprocessedCount}개")
            }
            else{
                Log.d(TAG, "gps 복구 데이터 예외 처리 ")
            }
        } else {
            Log.d(TAG, "⚠️ 유효한 GPS 동기화 데이터 없음 - 저장 스킵 (전체: ${allSyncData.size}개)")
        }
    }

    /**
     * Local 동기화 데이터 저장
     */
    private suspend fun saveLocalSynchronizedData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "local_sync.txt")
        val localData = dataSynchronizer.extractLocalSynchronizedData()

        if (localData.isNotEmpty()) {
            saveToFile(file, LOCAL_SYNC_HEADER, localData, ::buildLocalSyncContent)
            Log.d(TAG, "✅ Local 동기화 데이터 저장: ${localData.size}개")
        }
    }

    /**
     * GPS 동기화 데이터 내용 생성 (SyncMatchResult 타입용)
     */
    private fun buildGpsSyncContent(syncDataList: List<SyncMatchResult>): String {
        return buildString(syncDataList.size * 400) {
            for (syncResult in syncDataList) {
                val gps = syncResult.gpsEntry?.location
                val imu = syncResult.imuEntry?.imuData
                val gnss = syncResult.gnssEntry?.gnssData
                val camera = syncResult.cameraEntry?.cameraData
                val bbox = syncResult.bboxEntry?.bboxData

                // GPS 데이터가 있는 경우에만 저장
                if (gps != null && gps.latitude != 0.0 && gps.longitude != 0.0) {
                    append("${syncResult.hybridTime}\t")
                    append("${if (syncResult.gpsAvailable) "AVAILABLE" else "LOST"}\t")

                    // GPS 데이터 (반드시 존재)
                    append("${gps.latitude}\t${gps.longitude}\t")
                    append("${if (gps.hasAltitude()) gps.altitude else "NULL"}\t")

                    // IMU 데이터 (9축)
                    if (imu != null && imu.size >= 9) {
                        append("${imu[0]}\t${imu[1]}\t${imu[2]}\t")      // 가속도
                        append("${imu[3]}\t${imu[4]}\t${imu[5]}\t")      // 자이로
                        append("${imu[6]}\t${imu[7]}\t${imu[8]}\t")      // 자기장
                    } else {
                        append("NULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\t")
                    }

                    // GNSS 데이터
                    if (gnss != null) {
                        append("${gnss.gnssType}\t${gnss.satelliteId}\t${gnss.signalStrength}\t")
                    } else {
                        append("NULL\tNULL\tNULL\t")
                    }

                    // Camera 및 BBox 데이터
                    append("${camera?.frameId ?: "NULL"}\t")
                    append("${bbox?.size ?: "NULL"}")

                    // 누락된 데이터 타입 표시
                    if (syncResult.missingDataTypes.isNotEmpty()) {
                        append("\t# Missing: ${syncResult.missingDataTypes.joinToString(", ")}")
                    }

                    append("\n")
                } else {
                    // GPS 데이터가 없으면 저장하지 않음
                    Log.d(TAG, "🎯 GPS 데이터 없는 동기화 결과 스킵: ${syncResult.hybridTime}")
                }
            }
        }
    }

    /**
     * Local 동기화 데이터 내용 생성
     */
    private fun buildLocalSyncContent(localDataList: List<LocalSyncResult>): String {
        return buildString(localDataList.size * 200) {
            for (localResult in localDataList) {
                val imu = localResult.imuEntry?.imuData
                val camera = localResult.cameraEntry?.cameraData
                val bbox = localResult.bboxEntry?.bboxData

                append("${localResult.localTime}\t")

                // IMU 시간만
                append("${localResult.imuEntry?.systemTime ?: "NULL"}\t")

                // Camera 시간만
                append("${camera?.timestamp ?: "NULL"}\t")

                // BBox 시간만 (딥러닝 미검출 가능)
                if (bbox != null) {
                    append("${localResult.bboxEntry?.captureTime}\t")
                    append("DETECTED")
                } else {
                    append("NULL\t")
                    append("NOT_DETECTED")  // 딥러닝 미검출 로그
                }

                // 누락된 데이터 타입 표시
                if (localResult.missingDataTypes.isNotEmpty()) {
                    append("\t# Missing: ${localResult.missingDataTypes.joinToString(", ")}")
                }

                append("\n")
            }
        }
    }

    private suspend fun saveIndependentGpsData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_gps.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGpsEntry>()
            repeat(5.coerceAtMost(independentGpsQueue.size())) {
                independentGpsQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, RAW_GPS_HEADER, dataToSave, ::buildIndependentGpsContent)
            }
        }
    }

    private suspend fun saveIndependentImuData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_imu.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentImuEntry>()
            repeat((BATCH_SIZE * 5).coerceAtMost(independentImuQueue.size())) {
                independentImuQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, RAW_IMU_HEADER, dataToSave, ::buildIndependentImuContent)
            }
        }
    }

    private suspend fun saveIndependentGnssData(dir: File) = withContext(Dispatchers.IO) {
        val file = File(dir, "raw_gnss.txt")
        queueAccessMutex.withLock {
            val dataToSave = mutableListOf<IndependentGnssEntry>()
            repeat(10.coerceAtMost(independentGnssQueue.size())) {
                independentGnssQueue.poll()?.let { dataToSave.add(it) }
            }
            if (dataToSave.isNotEmpty()) {
                saveToFile(file, RAW_GNSS_HEADER, dataToSave, ::buildIndependentGnssContent)
            }
        }
    }

    private suspend fun <T> saveToFile(
        file: File,
        header: String,
        data: List<T>,
        contentBuilder: (List<T>) -> String
    ) = withContext(Dispatchers.IO) {
        try {
            val append = file.exists()

            // 🎯 디렉터리가 존재하는지 확인하고 생성
            if (!file.parentFile?.exists()!!) {
                file.parentFile?.mkdirs()
                Log.d(TAG, "📁 디렉터리 생성: ${file.parentFile?.absolutePath}")
            }

            BufferedWriter(FileWriter(file, append), BUFFER_SIZE).use { writer ->
                // 🎯 파일이 새로 생성되거나 비어있는 경우에만 헤더 추가
                if (!append || file.length() == 0L) {
                    writer.write(header)
                    writer.newLine()
                    Log.d(TAG, "📝 헤더 추가: ${file.name}")
                }

                // 데이터 추가 (항상 append)
                writer.write(contentBuilder(data))
                writer.flush()
            }

            Log.d(TAG, "✅ 데이터 저장: ${file.name}, +${data.size}개 (총 크기: ${file.length() / 1024}KB)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 데이터 저장 실패: ${file.name}, ${e.message}", e)

            // 🚨 중요한 데이터인 경우 폴백 저장 시도
            if (file.name.contains("gps_sync") || file.name.contains("video_frame")) {
                tryFallbackSave(file, header, data, contentBuilder)
            } else {
            }
        }
    }

    private suspend fun <T> tryFallbackSave(
        originalFile: File,
        header: String,
        data: List<T>,
        contentBuilder: (List<T>) -> String
    ) = withContext(Dispatchers.IO) {
        try {
            // 내부 저장소에 임시 저장
            val fallbackDir = File(context.filesDir, "fallback_logs")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()

            val fallbackFile = File(fallbackDir, "fallback_${originalFile.name}")

            BufferedWriter(FileWriter(fallbackFile, true), BUFFER_SIZE).use { writer ->
                if (!fallbackFile.exists() || fallbackFile.length() == 0L) {
                    writer.write(header)
                    writer.newLine()
                }
                writer.write(contentBuilder(data))
                writer.flush()
            }

            Log.w(TAG, " 폴백 저장 완료: ${fallbackFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, " 폴백 저장도 실패: ${e.message}", e)
        }
    }

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


    // ✅ 로그 저장 활성화 시 메타데이터 헤더 파일 생성
    fun enableLogSaving() {
        resourceMonitor.logAppResourceStatus(TAG, "로그 저장 시작")
        isLogSavingEnabled = true
        currentLogDirectory = createLogDirectory()

        // ✅ 현재 시간으로 세션 ID 설정
        val currentMinuteId = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

        ioScope.launch {
            try {
                // ✅ 성공한 프레임 메타데이터 헤더 파일 생성
                val metadataFile = File(currentLogDirectory, "video_frame_metadata.txt")
                if (!metadataFile.exists()) {
                    metadataFile.writeText(VIDEO_METADATA_HEADER + "\n")
                    Log.d(TAG, "✅ 비디오 메타데이터 헤더 파일 생성")
                }
                
                // ✅ 실패한 프레임 메타데이터 헤더 파일 생성
                val metadata2File = File(currentLogDirectory, "video_frame_metadata2.txt")
                if (!metadata2File.exists()) {
                    metadata2File.writeText(VIDEO_METADATA2_HEADER + "\n")
                    Log.d(TAG, "✅ 실패 프레임 메타데이터2 헤더 파일 생성")
                }

                // ✅ 비디오 인코더 초기화 및 세션 동기화
                if (videoEncoder == null) {
                    videoEncoder = SimpleVideoEncoder(context)
                    videoEncoder!!.setOutputDirectory(currentLogDirectory!!)
                    val started = videoEncoder!!.startRecording()

                    if (started) {
                        // ✅ 실제 비디오 파일의 세션 ID와 동기화
                        currentSessionTimestamp = videoEncoder!!.getSessionId()
                        Log.d(TAG, "📁 로그 저장 및 비디오 녹화 활성화: ${currentLogDirectory!!.absolutePath}")
                        Log.d(TAG, "🎬 초기 비디오 세션: $currentSessionTimestamp")
                        resourceMonitor.logAppResourceStatus(TAG, "로그 저장 활성화 완료")
                    } else {
                        Log.e(TAG, "❌ 비디오 인코더 시작 실패")
                        resourceMonitor.logAppResourceStatus(TAG, "비디오 인코더 시작 실패")
                        videoEncoder = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 비디오 메타데이터 헤더 생성 실패: ${e.message}", e)
            }
        }
    }

    // ✅ 로그 저장 비활성화 시 남은 메타데이터 저장
    fun disableLogSaving() {
        resourceMonitor.logAppResourceStatus(TAG, "로그 저장 중지 시작")
        isLogSavingEnabled = false

        // ✅ 비디오 인코더 중지 및 남은 메타데이터 저장
        runBlocking {
            videoSessionMutex.withLock {
                // ✅ 현재 세션의 메타데이터 강제 저장 - 마지막 메타데이터 확실히 저장
                saveVideoMetadata()

                // ✅ 비디오 인코더 정리
                videoEncoder?.stopRecording()
                val finalSession = videoEncoder?.getSessionId()
                videoEncoder = null

                Log.d(TAG, "🎬 최종 세션 종료: $finalSession, 저장된 메타데이터: ${videoMetadataList.size}개")
            }
        }

        currentLogDirectory = null
        System.gc()
        runBlocking { delay(100) }
        resourceMonitor.logAppResourceStatus(TAG, "로그 저장 중지 완료")
        Log.d(TAG, "📁 로그 저장 및 비디오 녹화 비활성화")
    }

    private fun createLogDirectory(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val saveDir = File(Environment.getExternalStorageDirectory(), "Documents/save")
        val logDir = File(saveDir, timestamp)

        try {
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            Log.d(TAG, "✅ 로깅 디렉토리 생성: ${logDir.absolutePath}")
            return logDir
        } catch (e: Exception) {
            Log.e(TAG, "❌ 디렉토리 생성 실패: ${e.message}", e)
            val internalDir = File(context.filesDir, "gnss_data/$timestamp")
            internalDir.mkdirs()
            Log.w(TAG, "🚨 내부 저장소 사용: ${internalDir.absolutePath}")
            return internalDir
        }
    }

    fun getCurrentDataDirectory(): File {
        return currentLogDirectory ?: throw IllegalStateException("로깅이 활성화되지 않음")
    }

    /**
     * 🎯 세션 기반 파일 관리 (분이 바뀌어도 append 보장)
     */
    private fun ensureFileAppendability(file: File): Boolean {
        return try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
                Log.d(TAG, "📝 새 파일 생성: ${file.name}")
                false // 새 파일이므로 헤더 필요
            } else {
                Log.d(TAG, "📝 기존 파일에 append: ${file.name}")
                true // 기존 파일이므로 헤더 불필요
            }
        } catch (e: Exception) {
            Log.e(TAG, "파일 생성/확인 실패: ${file.name}, ${e.message}", e)
            false
        }
    }

    fun setTransportType(transportType: String) {
        try {
            currentTransportType = transportType
            liveStreamingClient = StreamingClientFactory.createStreamingClient(transportType)
            Log.d(TAG, "✅ 전송 타입 설정: $transportType")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 전송 타입 설정 실패: ${e.message}", e)
        }
    }

    suspend fun enableStreaming() {
        if (!::liveStreamingClient.isInitialized) {
            Log.e(TAG, "❌ StreamingClient가 초기화되지 않음")
            throw IllegalStateException("StreamingClient가 초기화되지 않음")
        }
        try {
            liveStreamingClient.startStreaming(context)
            isLiveStreamingEnabled = true
            Log.d(TAG, "✅ 라이브 스트리밍 활성화: $currentTransportType")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 스트리밍 활성화 실패: ${e.message}", e)
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
            Log.e(TAG, "❌ Streaming 비활성화 실패: ${e.message}", e)
        }
    }

    /**
     * ✅ 현재 비디오 세션 ID 확인
     */
    fun getCurrentVideoSessionId(): String? {
        return try {
            videoEncoder?.getSessionId()
        } catch (e: Exception) {
            Log.e(TAG, "비디오 세션 ID 확인 실패: ${e.message}", e)
            null
        }
    }

    /**
     * ✅ 비디오 녹화가 실제로 진행 중인지 확인
     */
    fun isVideoRecording(): Boolean {
        return try {
            isLogSavingEnabled && videoEncoder?.isRecording() == true
        } catch (e: Exception) {
            Log.e(TAG, "비디오 녹화 상태 확인 실패: ${e.message}", e)
            false
        }
    }

    /**
     * ✅ 현재 비디오 프레임 통계
     */
    fun getVideoFrameStats(): VideoFrameStats {
        return VideoFrameStats(
            totalFrames = videoFrameCounter.get(),
            queueSize = videoFrameQueue.size(),
            currentSessionId = getCurrentVideoSessionId(),
            isRecording = isVideoRecording(),
            metadataCount = videoMetadataList.size
        )
    }

    // 데이터 클래스 추가
    data class VideoFrameStats(
        val totalFrames: Long,
        val queueSize: Int,
        val currentSessionId: String?,
        val isRecording: Boolean,
        val metadataCount: Int
    )

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
        val videoQueueSize: Int,        // ✅ 비디오 큐 상태 추가
        val videoMetadataCount: Int,    // ✅ 비디오 메타데이터 개수 추가
        val totalDataPoints: Int,
        val gpsStatus: Boolean,
        val memoryPressure: Float
    )

    fun getIndependentQueueStatus(): IndependentQueueStatusInfo {
        return IndependentQueueStatusInfo(
            gpsQueueSize = independentGpsQueue.size(),
            imuQueueSize = independentImuQueue.size(),
            gnssQueueSize = independentGnssQueue.size(),
            bboxQueueSize = independentBboxQueue.size(),
            cameraQueueSize = independentCameraQueue.size(),
            comprehensiveGnssQueueSize = comprehensiveGnssQueue.size(),
            satelliteStatusQueueSize = satelliteStatusQueue.size(),
            navigationQueueSize = navigationMessageQueue.size(),
            antennaQueueSize = antennaInfoQueue.size(),
            gnssClockQueueSize = gnssClockQueue.size(),
            gnssSessionQueueSize = gnssSessionQueue.size(),
            videoQueueSize = videoFrameQueue.size(),               // ✅ 추가
            videoMetadataCount = videoMetadataList.size,           // ✅ 추가
            totalDataPoints = independentGpsQueue.size() + independentImuQueue.size() +
                    independentGnssQueue.size() + independentBboxQueue.size() +
                    independentCameraQueue.size() + comprehensiveGnssQueue.size() +
                    satelliteStatusQueue.size() + navigationMessageQueue.size() +
                    antennaInfoQueue.size() + gnssClockQueue.size() + gnssSessionQueue.size() +
                    videoFrameQueue.size(),                        // ✅ 비디오 큐 포함
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

    fun getSynchronizedData(dataList: List<SensorData>): List<SensorData> {
        val cameraQueue = CircularQueue<CameraEntry>(dataList.size)
        dataList.forEach { sensorData ->
            cameraQueue.push(object : CameraEntry {
                override val cameraData = sensorData
                override val captureTime = sensorData.timestamp
            })
        }
        val gpsQueue = CircularQueue<GpsEntry>(0)
        val imuQueue = CircularQueue<ImuEntry>(0)
        val gnssQueue = CircularQueue<GnssEntry>(0)
        val bboxQueue = CircularQueue<BboxEntry>(0)

        dataSynchronizer.performSynchronization(gpsQueue, imuQueue, gnssQueue, cameraQueue, bboxQueue)
        val syncResults = dataSynchronizer.extractGpsSynchronizedData()
        return syncResults.mapNotNull { it.cameraEntry?.cameraData }
    }

    fun getSystemStatus(): String {
        val memoryInfo = resourceMonitor.getAppMemoryInfo()
        val systemMemInfo = resourceMonitor.getSystemMemoryInfo()
        val warnings = resourceMonitor.checkAppMemoryWarnings()

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
            append("프레임 버퍼: ${frameBuffer.size()}/${MAX_FRAME_BUFFER}\n")
            append("=== 비디오 전용 큐 상태 ===\n")                      // ✅ 추가
            append("비디오 큐: ${queueStatus.videoQueueSize}/${MAX_VIDEO_FRAME_BUFFER}\n")     // ✅ 추가
            append("비디오 메타데이터: ${queueStatus.videoMetadataCount}개\n")                // ✅ 추가
            append("=== 완전한 GNSS 큐 상태 ===\n")
            append("완전한 GNSS: ${queueStatus.comprehensiveGnssQueueSize}/${MAX_COMPREHENSIVE_GNSS_QUEUE}\n")
            append("위성 상태: ${queueStatus.satelliteStatusQueueSize}/${MAX_SATELLITE_STATUS_QUEUE}\n")
            append("내비게이션: ${queueStatus.navigationQueueSize}/${MAX_NAVIGATION_QUEUE}\n")
            append("안테나: ${queueStatus.antennaQueueSize}/${MAX_ANTENNA_QUEUE}\n")
            append("클럭: ${queueStatus.gnssClockQueueSize}/${MAX_GNSS_CLOCK_QUEUE}\n")
            append("세션: ${queueStatus.gnssSessionQueueSize}/${MAX_GNSS_SESSION_QUEUE}\n")
            append("총 데이터 포인트: ${queueStatus.totalDataPoints}\n")
            append("=== 메모리 상태 ===\n")
            append("힙 사용률: ${String.format("%.1f", memoryInfo.heapUsagePercent)}%\n")
            append("사용 가능한 힙: ${String.format("%.1f", memoryInfo.availableHeapMB)} MB\n")
            append("Native 메모리: ${String.format("%.1f", memoryInfo.nativeHeapMB)} MB\n")
            append("시스템 메모리 부족: ${if (systemMemInfo.systemMemoryLow) "예" else "아니오"}\n")
            if (warnings.isNotEmpty()) {
                append("=== 메모리 경고 ===\n")
                warnings.forEach { append("⚠️ $it\n") }
            }
        }
    }

    fun getVideoOutputDirectory(): File {
        return getCurrentDataDirectory()
    }

    private inline fun shouldSave() = isLogSavingEnabled
    private inline fun shouldLiveStream() = isLiveStreamingEnabled

    // ✅ 비디오 프레임 메타데이터 헤더 (성공한 프레임)
    private val VIDEO_METADATA_HEADER = """
# Video Frame Metadata - Complete Frame Processing Information (SUCCESS only)
# This file contains metadata for each frame successfully saved to the .mp4 video file
# Use this file to synchronize video frames with other sensor data (GPS, IMU, GNSS, etc.)
FRAME_ID	SEQUENCE_NUMBER	TIMESTAMP	MONO_TIMESTAMP	CAPTURE_TIME	ENCODING_TIME	SESSION_ID	WIDTH	HEIGHT	BITMAP_SIZE_MB	ENCODING_STATUS
""".trimIndent()

    // ✅ 실패한 프레임 메타데이터 헤더
    private val VIDEO_METADATA2_HEADER = """
# Video Frame Metadata2 - Failed Frame Processing Information
# This file contains metadata for frames that FAILED to encode properly to the .mp4 video file
# These frames were captured but not included in the final video due to encoding errors
# ENCODING_STATUS is always 'fail' for all entries in this file
FRAME_ID	SEQUENCE_NUMBER	TIMESTAMP	MONO_TIMESTAMP	CAPTURE_TIME	ENCODING_TIME	SESSION_ID	WIDTH	HEIGHT	BITMAP_SIZE_MB	ENCODING_STATUS
""".trimIndent()

    private val COMPREHENSIVE_GNSS_HEADER = """
# Comprehensive GNSS Measurements Data - Complete Raw Signal Information
CAPTURE_TIME	GPS_TIME	LOCAL_TIME	MONO_TIME	GNSS_TYPE	SAT_ID	CN0_DBZ	CARRIER_FREQ_HZ	MULTIPATH	PSEUDORANGE_RATE	PR_RATE_UNC	ACCUM_DELTA_RANGE	ADR_STATE	CARRIER_PHASE	CP_UNC	RX_SV_TIME_NANOS	RX_SV_TIME_UNC	STATE	AGC_DB	BASEBAND_CN0	CODE_TYPE	CLOCK_TIME_NANOS	CLOCK_FULL_BIAS	GPS_STATUS
""".trimIndent()

    private val SATELLITE_STATUS_HEADER = """
# GNSS Satellite Status Data - Complete Sky Plot and Fix Information
CAPTURE_TIME	LOCAL_TIME	MONO_TIME	SAT_INDEX	CONSTELLATION	SVID	CN0_DBZ	CARRIER_FREQ_HZ	AZIMUTH_DEG	ELEVATION_DEG	HAS_ALMANAC	HAS_EPHEMERIS	USED_IN_FIX	TOTAL_SATS	USED_SATS
""".trimIndent()

    private val NAVIGATION_MESSAGE_HEADER = """
# GNSS Navigation Messages - Complete Satellite Broadcast Data
CAPTURE_TIME	LOCAL_TIME	MONO_TIME	MSG_ID	SUBMSG_ID	TYPE	STATUS	SVID	DATA_LENGTH	HEX_DATA	ADDITIONAL_INFO
""".trimIndent()

    private val ANTENNA_INFO_HEADER = """
# GNSS Antenna Information - Phase Center Calibration Data
CAPTURE_TIME	LOCAL_TIME	MONO_TIME	CARRIER_FREQ_MHZ	PCO_X_MM	PCO_Y_MM	PCO_Z_MM	PCO_UNC_X	PCO_UNC_Y	PCO_UNC_Z	PCV_CORRECTIONS	GAIN_CORRECTIONS	ADDITIONAL_INFO
""".trimIndent()

    private val GNSS_CLOCK_HEADER = """
# GNSS Hardware Clock Data - Complete Time System Analysis
CAPTURE_TIME	GPS_TIME	LOCAL_TIME	MONO_TIME	TIME_NANOS	TIME_UNC_NANOS	LEAP_SECOND	BIAS_NANOS	BIAS_UNC_NANOS	DRIFT_NANOS_PER_SEC	FULL_BIAS_NANOS	HW_CLOCK_DISCONTINUITY	ADDITIONAL_INFO
""".trimIndent()

    private val GNSS_SESSION_HEADER = """
# GNSS Session Summary Data - Performance Analysis and Quality Metrics
CAPTURE_TIME	SESSION_START	SESSION_END	FIRST_FIX_TIME	SESSION_DURATION	TOTAL_SATS_USED	AVG_SIGNAL_STRENGTH	ADDITIONAL_INFO
""".trimIndent()

    private val GPS_SYNC_HEADER = """
# GPS Synchronized Data (Hybrid Logical Clock)
GPS_TIME	GPS_STATUS	LAT	LON	ALT	ACC_X	ACC_Y	ACC_Z	GYRO_X	GYRO_Y	GYRO_Z	MAG_X	MAG_Y	MAG_Z	GNSS_TYPE	SAT_ID	CN0	CAMERA_FRAME_ID	BBOX_COUNT
""".trimIndent()

    private val RAW_GPS_HEADER = """
# Raw GPS Data - Independent Queue Management
CAPTURE_TIME	SYS_TIME	MONO_TIME	GPS_TIME	LATITUDE	LONGITUDE	ALTITUDE	ACCURACY	SPEED	BEARING	PROVIDER	GPS_STATUS
""".trimIndent()

    private val RAW_IMU_HEADER = """
# Raw IMU Data - Independent Queue Management
CAPTURE_TIME	SYS_TIME	MONO_TIME	ACC_X	ACC_Y	ACC_Z	GYRO_X	GYRO_Y	GYRO_Z	MAG_X	MAG_Y	MAG_Z	GPS_STATUS
""".trimIndent()

    private val RAW_GNSS_HEADER = """
# Raw GNSS Measurements - Independent Queue Management
CAPTURE_TIME	SYS_TIME	MONO_TIME	GNSS_TYPE	SAT_ID	CN0_DB_HZ	PSEUDORANGE_RATE	CARRIER_PHASE	ADDITIONAL_INFO	GPS_STATUS
""".trimIndent()

    private val LOCAL_SYNC_HEADER = """
# Local Synchronized Data (GPS Signal Lost)
# Camera, IMU, BBox timing data only
LOCAL_TIME	IMU_TIME	CAMERA_TIME	BBOX_TIME	BBOX_STATUS
""".trimIndent()
}
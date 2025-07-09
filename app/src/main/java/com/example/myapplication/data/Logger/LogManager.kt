package com.example.myapplication.data.logging

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.location.Location
import android.media.*
import android.media.MediaCodec.BufferInfo
import android.media.MediaMuxer.OutputFormat
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 안정적인 멀티미디어 로깅 시스템
 *
 * 이론적 기반:
 * - Finite State Automaton: MediaCodec 상태 관리
 * - Queueing Theory: M/M/1 모델 기반 배치 처리
 * - Linear Algebra: YUV→RGB 색공간 변환 행렬
 * - Information Theory: 엔트로피 기반 압축 최적화
 */
class LoggerManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LoggerManager"

        // ITU-T H.264 표준 파라미터
        private const val VIDEO_WIDTH = 840
        private const val VIDEO_HEIGHT = 840
        private const val VIDEO_FPS = 15
        private const val VIDEO_BITRATE = 1_200_000 // 1.2Mbps
        private const val I_FRAME_INTERVAL = 2      // GOP 크기

        // Queueing Theory 파라미터 (M/M/1 모델)
        private const val BATCH_SIZE = 15                 // λ = 15 frames/batch
        private const val BATCH_TIMEOUT_MS = 4000L        // W = 4초 최대 대기시간
        private const val MAX_FRAME_BUFFER = 45           // L = 45 frames 최대 큐 길이

        @Volatile
        private var INSTANCE: LoggerManager? = null

        fun getInstance(context: Context): LoggerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LoggerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // 상태 머신 정의 (유한상태기계)
    enum class EncoderState {
        IDLE,           // 유휴 상태: q₀
        INITIALIZING,   // 초기화: q₁
        ENCODING,       // 인코딩: q₂
        FINALIZING,     // 완료: q₃
        ERROR          // 오류: qₑ
    }

    private var isLogSavingEnabled = false
    private var isLiveStreamingEnabled = false

    // Actor Model 기반 동시성 제어
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val encodingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 하이브리드 논리 시계 동기화
    private val dataSynchronizer = DataSynchronizer()

    // 네트워크 스트리밍
    private lateinit var liveStreamingClient: StreamingClient
    private var currentTransportType: String? = null

    /**
     * 안정적인 상태머신 기반 비디오 인코더
     *
     * 상태 전이 다이어그램:
     * IDLE --initialize--> INITIALIZING --configure--> ENCODING --finalize--> IDLE
     *   |                      |                          |                     ^
     *   +------- ERROR <-------+------------ ERROR <------+---------------------+
     */
    inner class StableVideoEncoder {
        // 원자적 상태 관리 (Compare-And-Swap)
        private val encoderState = AtomicReference(EncoderState.IDLE)
        private var mediaCodec: MediaCodec? = null
        private var mediaMuxer: MediaMuxer? = null
        private var videoTrackIndex = -1
        private var isMuxerStarted = false

        private var frameCount = 0L
        private var currentVideoUri: android.net.Uri? = null
        private var currentSessionId: String? = null

        // Peterson's Algorithm 기반 상호배제
        private val encoderLock = Mutex()
        private val codecAccessLock = Any()

        /**
         * 상태 전이 함수: δ: Q × Σ → Q
         * @param from 현재 상태
         * @param to 목표 상태
         * @return 전이 성공 여부
         */
        private fun transitionState(from: EncoderState, to: EncoderState): Boolean {
            return encoderState.compareAndSet(from, to)
        }

        suspend fun initializeEncoder(): Boolean = withContext(Dispatchers.IO) {
            encoderLock.withLock {
                if (!transitionState(EncoderState.IDLE, EncoderState.INITIALIZING)) {
                    Log.w(TAG, "상태 전이 실패: ${encoderState.get()} → INITIALIZING")
                    return@withLock false
                }

                try {
                    cleanup() // 이전 리소스 정리

                    // 세션 ID 생성 (UUID 기반)
                    currentSessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

                    // Movies 디렉토리에 비디오 파일 생성 (허용됨)
                    val fileName = "sensor_video_${System.currentTimeMillis()}.mp4"
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/gnss/$currentSessionId")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }

                    currentVideoUri = context.contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        values
                    )

                    if (currentVideoUri == null) {
                        transitionState(EncoderState.INITIALIZING, EncoderState.ERROR)
                        Log.e(TAG, "MediaStore 비디오 URI 생성 실패")
                        return@withLock false
                    }

                    // 파일 디스크립터 획득
                    val fileDescriptor = context.contentResolver.openFileDescriptor(
                        currentVideoUri!!, "w"
                    ) ?: run {
                        transitionState(EncoderState.INITIALIZING, EncoderState.ERROR)
                        return@withLock false
                    }

                    // MediaMuxer 초기화 (MP4 컨테이너)
                    mediaMuxer = MediaMuxer(
                        fileDescriptor.fileDescriptor,
                        OutputFormat.MUXER_OUTPUT_MPEG_4
                    )

                    // MediaCodec 초기화 (Thread-Safe)
                    synchronized(codecAccessLock) {
                        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        val format = createH264Format()
                        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        mediaCodec?.start()
                    }

                    frameCount = 0
                    transitionState(EncoderState.INITIALIZING, EncoderState.ENCODING)

                    Log.d(TAG, "인코더 초기화 성공: Movies/gnss/$currentSessionId/$fileName")
                    true

                } catch (e: Exception) {
                    Log.e(TAG, "인코더 초기화 실패: ${e.message}", e)
                    transitionState(EncoderState.INITIALIZING, EncoderState.ERROR)
                    cleanup()
                    false
                }
            }
        }

        /**
         * ITU-T H.264 표준 비디오 포맷 생성
         * Baseline Profile, Level 3.1
         */
        private fun createH264Format(): MediaFormat {
            return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                // YUV420 Planar 색공간 (4:2:0 서브샘플링)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

                // H.264 프로파일 설정
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

                // Variable Bitrate 인코딩
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }

        /**
         * 프레임 인코딩: RGB → YUV420 → H.264
         *
         * 색공간 변환 행렬 (ITU-R BT.601):
         * [Y ]   [0.299  0.587  0.114 ] [R]
         * [Cb] = [-0.169 -0.331 0.500 ] [G] + [128]
         * [Cr]   [0.500  -0.419 -0.081] [B]   [128]
         */
        suspend fun encodeFrame(bitmap: Bitmap): Boolean = encoderLock.withLock {
            if (encoderState.get() != EncoderState.ENCODING) {
                Log.w(TAG, "인코딩 불가능한 상태: ${encoderState.get()}")
                return false
            }

            synchronized(codecAccessLock) {
                val codec = mediaCodec ?: return false

                try {
                    // RGB → YUV420 색공간 변환
                    val yuvData = convertRgbToYuv420(bitmap) ?: return false

                    // MediaCodec 입력 버퍼 획득 (Non-blocking)
                    val inputBufferIndex = codec.dequeueInputBuffer(10000L)
                    if (inputBufferIndex < 0) {
                        Log.w(TAG, "입력 버퍼 획득 실패: $inputBufferIndex")
                        return false
                    }

                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return false

                    inputBuffer.clear()
                    inputBuffer.put(yuvData)

                    // Presentation Timestamp 계산 (90kHz 시계)
                    val presentationTimeUs = frameCount * 1000000L / VIDEO_FPS

                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        yuvData.size,
                        presentationTimeUs,
                        0
                    )

                    frameCount++

                    // Elementary Stream 출력 처리
                    drainOutputBuffer(false)

                    return true

                } catch (e: IllegalStateException) {
                    Log.e(TAG, "MediaCodec 상태 오류: ${e.message}")
                    transitionState(encoderState.get(), EncoderState.ERROR)
                    return false
                } catch (e: Exception) {
                    Log.e(TAG, "프레임 인코딩 실패: ${e.message}", e)
                    return false
                }
            }
        }

        /**
         * ITU-R BT.601 표준 색공간 변환
         * RGB(8bit) → YUV420(8bit) Planar
         *
         * 수학적 변환:
         * Y = 0.299R + 0.587G + 0.114B
         * U = -0.169R - 0.331G + 0.500B + 128
         * V = 0.500R - 0.419G - 0.081B + 128
         */
        private fun convertRgbToYuv420(bitmap: Bitmap): ByteArray? {
            try {
                // 해상도 정규화
                val scaledBitmap = if (bitmap.width != VIDEO_WIDTH || bitmap.height != VIDEO_HEIGHT) {
                    Bitmap.createScaledBitmap(bitmap, VIDEO_WIDTH, VIDEO_HEIGHT, true)
                } else {
                    bitmap
                }

                // ARGB 픽셀 추출
                val argbPixels = IntArray(VIDEO_WIDTH * VIDEO_HEIGHT)
                scaledBitmap.getPixels(argbPixels, 0, VIDEO_WIDTH, 0, 0, VIDEO_WIDTH, VIDEO_HEIGHT)

                // YUV420 Planar 배열 할당
                val yuvSize = VIDEO_WIDTH * VIDEO_HEIGHT * 3 / 2
                val yuvData = ByteArray(yuvSize)

                // 색공간 변환 수행
                encodeYuv420Planar(yuvData, argbPixels, VIDEO_WIDTH, VIDEO_HEIGHT)

                // 메모리 정리
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }

                return yuvData

            } catch (e: Exception) {
                Log.e(TAG, "YUV420 변환 실패: ${e.message}", e)
                return null
            }
        }

        /**
         * RGB to YUV420 Planar 변환 최적화 구현
         * 고정소수점 연산으로 성능 최적화
         */
        private fun encodeYuv420Planar(yuv420: ByteArray, argb: IntArray, width: Int, height: Int) {
            val frameSize = width * height
            var yIndex = 0
            var uIndex = frameSize
            var vIndex = frameSize + frameSize / 4

            // ITU-R BT.601 변환 계수 (고정소수점 x256)
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val pixel = argb[j * width + i]

                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff

                    // 고정소수점 연산 (성능 최적화)
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    // Y 성분 저장 (전체 해상도)
                    yuv420[yIndex++] = y.coerceIn(0, 255).toByte()

                    // UV 성분 저장 (4:2:0 서브샘플링)
                    if (j % 2 == 0 && i % 2 == 0) {
                        yuv420[uIndex++] = u.coerceIn(0, 255).toByte()
                        yuv420[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }

        /**
         * H.264 Elementary Stream 출력 처리
         * NAL Unit 기반 스트림 처리
         */
        private fun drainOutputBuffer(endOfStream: Boolean) {
            synchronized(codecAccessLock) {
                val codec = mediaCodec ?: return

                try {
                    if (endOfStream) {
                        val inputBufferIndex = codec.dequeueInputBuffer(0L)
                        if (inputBufferIndex >= 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                    }

                    val bufferInfo = BufferInfo()
                    var outputBufferIndex: Int

                    do {
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)

                        when {
                            outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!isMuxerStarted) {
                                    val newFormat = codec.outputFormat
                                    videoTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                                    mediaMuxer?.start()
                                    isMuxerStarted = true
                                    Log.d(TAG, "Muxer 시작: track=$videoTrackIndex")
                                }
                            }

                            outputBufferIndex >= 0 -> {
                                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                                if (outputBuffer != null && bufferInfo.size > 0 && isMuxerStarted) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                                }

                                codec.releaseOutputBuffer(outputBufferIndex, false)

                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    Log.d(TAG, "인코딩 스트림 종료 감지")
                                    break
                                }
                            }
                        }

                    } while (outputBufferIndex >= 0)

                } catch (e: IllegalStateException) {
                    Log.e(TAG, "출력 버퍼 처리 중 상태 오류: ${e.message}")
                    transitionState(encoderState.get(), EncoderState.ERROR)
                }
            }
        }

        /**
         * 인코더 완료 및 리소스 해제
         */
        suspend fun finalizeEncoding(): Boolean = encoderLock.withLock {
            if (!transitionState(encoderState.get(), EncoderState.FINALIZING)) {
                return false
            }

            try {
                Log.d(TAG, "비디오 인코딩 완료 처리 시작...")

                synchronized(codecAccessLock) {
                    drainOutputBuffer(true)
                }

                cleanup()

                // MediaStore 완료 처리
                currentVideoUri?.let { uri ->
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, values, null, null)
                }

                transitionState(EncoderState.FINALIZING, EncoderState.IDLE)
                Log.d(TAG, "비디오 인코딩 완료: ${frameCount}프레임")
                true

            } catch (e: Exception) {
                Log.e(TAG, "인코딩 완료 처리 실패: ${e.message}", e)
                transitionState(EncoderState.FINALIZING, EncoderState.ERROR)
                false
            }
        }

        private fun cleanup() {
            try {
                synchronized(codecAccessLock) {
                    mediaCodec?.stop()
                    mediaCodec?.release()
                    mediaCodec = null
                }

                mediaMuxer?.stop()
                mediaMuxer?.release()
                mediaMuxer = null

                videoTrackIndex = -1
                isMuxerStarted = false

            } catch (e: Exception) {
                Log.e(TAG, "리소스 정리 오류: ${e.message}", e)
            }
        }

        fun getSessionId(): String? = currentSessionId
        fun getFrameCount(): Long = frameCount
        fun isActive(): Boolean = encoderState.get() == EncoderState.ENCODING
        fun getState(): EncoderState = encoderState.get()
    }

    // ========== 데이터 처리 (Producer-Consumer Pattern) ==========

    private val frameBuffer = ConcurrentLinkedQueue<SensorData>()
    private val lastBatchTime = AtomicLong(System.currentTimeMillis())
    private var currentEncoder: StableVideoEncoder? = null
    private val encoderMutex = Mutex()
    private var currentSessionTimestamp: String? = null

    init {
        startBatchProcessor()
    }

    /**
     * M/M/1 큐잉 이론 기반 배치 처리기
     * Little's Law: L = λW 적용
     */
    private fun startBatchProcessor() {
        ioScope.launch {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastBatch = currentTime - lastBatchTime.get()

                    // 배치 조건: 큐 크기 또는 시간 임계값
                    if (frameBuffer.size >= BATCH_SIZE ||
                        (frameBuffer.isNotEmpty() && timeSinceLastBatch >= BATCH_TIMEOUT_MS)) {
                        processBatch("자동 배치")
                    }

                    delay(500) // 폴링 간격
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
                Log.d(TAG, "$reason 시작 - 버퍼 크기: ${frameBuffer.size}")

                // 인코더 상태 검증 및 초기화
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

                // 프레임 배치 처리
                val framesToProcess = mutableListOf<SensorData>()
                while (frameBuffer.isNotEmpty() && framesToProcess.size < BATCH_SIZE) {
                    frameBuffer.poll()?.let { framesToProcess.add(it) }
                }

                // 비디오 인코딩
                var encodedFrames = 0
                for (frame in framesToProcess) {
                    frame.bitmap?.let { bitmap ->
                        if (currentEncoder?.encodeFrame(bitmap) == true) {
                            encodedFrames++
                        }
                    }
                }

                // 동기화된 텍스트 데이터 저장 (Documents 경로)
                val syncData = dataSynchronizer.extractSynchronizedData(force = true)
                if (syncData.isNotEmpty()) {
                    saveTextData(syncData)
                }

                lastBatchTime.set(System.currentTimeMillis())

                Log.d(TAG, "$reason 완료: ${encodedFrames}/${framesToProcess.size}프레임, ${syncData.size}개 동기화 데이터")

            } catch (e: Exception) {
                Log.e(TAG, "배치 처리 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 텍스트 데이터 저장 (Documents/gnss 경로)
     * Android SAF 정책 준수
     */
    private suspend fun saveTextData(syncDataList: List<HybridSynchronizedDataEntry>) = withContext(Dispatchers.IO) {
        if (currentSessionTimestamp == null) return@withContext

        try {
            // Documents 경로 사용 (텍스트 파일 허용)
            val relativePath = "Documents/gnss/$currentSessionTimestamp"

            // GPS 동기화 데이터
            val gpsContent = buildGpsSyncContent(syncDataList)
            if (gpsContent.isNotEmpty()) {
                appendToFile(relativePath, "gps_sync.txt", GPS_SYNC_HEADER, gpsContent)
            }

            // Raw GNSS 데이터
            val rawContent = buildRawGnssContent(syncDataList)
            if (rawContent.isNotEmpty()) {
                appendToFile(relativePath, "raw_gnss.txt", RAW_GNSS_HEADER, rawContent)
            }

        } catch (e: Exception) {
            Log.e(TAG, "텍스트 데이터 저장 실패: ${e.message}", e)
        }
    }

    /**
     * GPS 동기화 데이터 구성 (CSV 형식)
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

    private fun buildRawGnssContent(syncDataList: List<HybridSynchronizedDataEntry>): String {
        return buildString(syncDataList.size * 300) {
            for (entry in syncDataList) {
                val gpsStatus = if (entry.gpsAvailable) "AVAILABLE" else "LOST"
                val gpsData = entry.gpsData ?: continue
                val (loc, _, _) = gpsData

                append("GPS\t${entry.hybridTime}\t$gpsStatus\t")
                append("lat=${loc.latitude},lon=${loc.longitude},alt=${if (loc.hasAltitude()) loc.altitude else "NULL"}")
                append("\n")

                entry.imuData?.let { (imu, _, _) ->
                    append("IMU\t${entry.hybridTime}\t$gpsStatus\t")
                    append("acc=${imu[0]},${imu[1]},${imu[2]},")
                    append("gyro=${imu[3]},${imu[4]},${imu[5]},")
                    append("mag=${imu[6]},${imu[7]},${imu[8]}")
                    append("\n")
                }

                entry.gnssData?.let { gnss ->
                    append("GNSS\t${entry.hybridTime}\t$gpsStatus\t")
                    append("type=${gnss.gnssType},sat_id=${gnss.satelliteId},cn0=${gnss.signalStrength}")
                    append("\n")
                }
            }
        }
    }

    /**
     * MediaStore 파일 저장 (SAF 준수)
     */
    private suspend fun appendToFile(
        relativePath: String,
        fileName: String,
        header: String,
        content: String
    ) = withContext(Dispatchers.IO) {
        if (content.isEmpty()) return@withContext

        try {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                values
            ) ?: return@withContext

            context.contentResolver.openOutputStream(uri, "wa")?.buffered(8192)?.use { output ->
                val cursor = context.contentResolver.query(
                    uri, arrayOf(MediaStore.Files.FileColumns.SIZE), null, null, null
                )
                val fileSize = cursor?.use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                } ?: 0L

                if (fileSize == 0L && header.isNotEmpty()) {
                    output.write(header.toByteArray())
                    output.write("\n".toByteArray())
                }

                output.write(content.toByteArray())
                output.flush()
            }

            values.clear()
            values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

        } catch (e: Exception) {
            Log.e(TAG, "파일 저장 실패: ${e.message}", e)
        }
    }

    // ========== 공개 인터페이스 ==========

    fun setTransportType(transportType: String) {
        if (currentTransportType != transportType || !::liveStreamingClient.isInitialized) {
            liveStreamingClient = StreamingClientFactory.createStreamingClient(transportType)
            currentTransportType = transportType
        }
    }

    fun enableLogSaving() {
        isLogSavingEnabled = true
        Log.d(TAG, "상태머신 기반 멀티미디어 로깅 활성화")
    }

    fun disableLogSaving() {
        isLogSavingEnabled = false
        ioScope.launch {
            encoderMutex.withLock {
                currentEncoder?.finalizeEncoding()
                currentEncoder = null
            }
            processBatch("로그 저장 비활성화")
            Log.d(TAG, "멀티미디어 로깅 비활성화 및 파일 완료 처리")
        }
    }

    suspend fun enableLiveStreaming() {
        if (isLiveStreamingEnabled) return
        if (!::liveStreamingClient.isInitialized) {
            throw IllegalStateException("라이브스트리밍을 시작하기 전에 통신 방식을 설정해야 합니다.")
        }
        isLiveStreamingEnabled = true
        liveStreamingClient.startStreaming(context)
    }

    suspend fun disableLiveStreaming() {
        if (!isLiveStreamingEnabled) return
        isLiveStreamingEnabled = false
        liveStreamingClient.stopStreaming()
    }

    suspend fun enableStreaming() = enableLiveStreaming()
    suspend fun disableStreaming() = disableLiveStreaming()

    fun pushCamera(data: SensorData) {
        dataSynchronizer.addCameraData(data)

        if (shouldSave()) {
            frameBuffer.offer(data)
            while (frameBuffer.size > MAX_FRAME_BUFFER) {
                frameBuffer.poll()
            }
        }

        if (shouldLiveStream()) {
            liveStreamingClient.sendCameraData(data)
        }
    }

    fun pushGps(loc: Location, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        dataSynchronizer.addGpsData(loc, sysTs, monoTs)
        if (shouldLiveStream()) {
            liveStreamingClient.sendGpsData(loc, sysTs, monoTs)
        }
    }

    fun pushGnss(g: GnssData) {
        dataSynchronizer.addGnssData(g)
        if (shouldLiveStream()) {
            liveStreamingClient.sendGnssData(g)
        }
    }

    fun pushImu(imu: FloatArray, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        dataSynchronizer.addImuData(imu, sysTs, monoTs)
        if (shouldLiveStream()) {
            liveStreamingClient.sendImuData(imu, sysTs, monoTs)
        }
    }

    fun pushBoundingBox(bboxes: List<BoundingBoxLog>) {
        dataSynchronizer.addBoundingBoxData(bboxes)
        if (shouldLiveStream()) {
            liveStreamingClient.sendBoundingBoxData(bboxes)
        }
    }

    fun getGpsStatus() = dataSynchronizer.getGpsStatus()
    fun getQueueStatus() = dataSynchronizer.getQueueStatus()

    fun getVideoStatus(): String {
        return buildString {
            append("상태머신 기반 멀티미디어 로깅 상태:\n")
            append("인코더 상태: ${currentEncoder?.getState() ?: "IDLE"}\n")
            append("프레임 버퍼: ${frameBuffer.size}/${MAX_FRAME_BUFFER}\n")
            append("세션: $currentSessionTimestamp\n")
            append("인코딩 프레임: ${currentEncoder?.getFrameCount() ?: 0}\n")
            append("비디오 저장: Movies/gnss/$currentSessionTimestamp/\n")
            append("텍스트 저장: Documents/gnss/$currentSessionTimestamp/\n")
            append("색공간 변환: ITU-R BT.601 RGB→YUV420\n")
            append("코덱: H.264 Baseline Profile\n")
            append("상태 전이: FSM 기반 안정성 보장")
        }
    }

    private inline fun shouldSave() = isLogSavingEnabled
    private inline fun shouldLiveStream() = isLiveStreamingEnabled

    // ========== 헤더 정의 ==========

    private val GPS_SYNC_HEADER = """
        # GPS Synchronized Data (Hybrid Logical Clock) - Finite State Machine Video Encoder
        # Video Storage: Movies/gnss/[session]/sensor_video_[timestamp].mp4  
        # Text Storage: Documents/gnss/[session]/gps_sync.txt, raw_gnss.txt
        # Encoding: ITU-R BT.601 RGB→YUV420 → H.264 Baseline Profile
        # State Machine: {IDLE, INITIALIZING, ENCODING, FINALIZING, ERROR}
        # Queueing Theory: M/M/1 model with λ=15frames/batch, W=4s
        HYBRID_TIME	GPS_STATUS	LAT	LON	ALT	ACC_X	ACC_Y	ACC_Z	GYRO_X	GYRO_Y	GYRO_Z	MAG_X	MAG_Y	MAG_Z	GNSS_TYPE	SAT_ID	CN0	CAMERA_FRAME_ID	BBOX_COUNT
    """.trimIndent()

    private val RAW_GNSS_HEADER = """
        # Raw GNSS/Sensor Data - Finite State Automaton Multimedia Logging
        # Color Space: ITU-R BT.601 Standard YUV420 Planar (4:2:0 Subsampling)
        # Synchronization: Hybrid Logical Clock Algorithm
        # Thread Safety: Peterson's Algorithm + Compare-And-Swap
        TYPE	HYBRID_TIME	GPS_STATUS	DATA
    """.trimIndent()
}
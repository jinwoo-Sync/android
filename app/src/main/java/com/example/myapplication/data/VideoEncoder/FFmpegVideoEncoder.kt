package com.example.myapplication.data.VideoEncoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.myapplication.DataStructure.CircularQueue
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class SimpleVideoEncoder(private val context: Context) {

    companion object {
        private const val TAG = "SimpleVideoEncoder"
        private const val VIDEO_WIDTH = 840
        private const val VIDEO_HEIGHT = 840
        private const val VIDEO_FPS = 15
        private const val VIDEO_BITRATE = 3_000_000  // 🎯 품질 향상: 3M 비트레이트
        private const val I_FRAME_INTERVAL = 1       // 🎯 품질 향상: I-프레임 간격 1초

        // 🎯 컬러 포맷을 NV21로 변경 (더 안정적인 컬러 지원)
        private const val COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isRecording = AtomicBoolean(false)
    private var muxerStarted = false
    private var frameIndex = 0L
    private var currentSessionId: String? = null
    private var currentOutputFile: File? = null

    // LogManager로부터 주입받을 출력 디렉토리
    private var outputDirectory: File? = null

    private val encodingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var encodingJob: Job? = null
    private val frameDurationUs = 1_000_000L / VIDEO_FPS

    // 🎯 CircularQueue 활용 - YUV 변환용 작업 bitmap 풀
    private val workingBitmaps = CircularQueue<Bitmap>(capacity = 2)

    init {
        // 🎯 작업용 bitmap 초기화
        initializeWorkingBitmaps()
    }

    /**
     * 🎯 CircularQueue로 YUV 변환용 작업 bitmap 초기화
     */
    private fun initializeWorkingBitmaps() {
        for (i in 0 until 2) {
            try {
                val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
                workingBitmaps.push(bitmap)
                Log.d(TAG, "✅ YUV 작업용 bitmap 생성: @${bitmap.hashCode().toString(16)}")
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "⚠️ YUV 작업용 bitmap 생성 실패: $i")
                System.gc()
                break
            }
        }
    }

    /**
     * 출력 디렉토리 설정 (LogManager에서 주입)
     */
    fun setOutputDirectory(directory: File) {
        this.outputDirectory = directory
        Log.d(TAG, "✅ Output directory set by LogManager: ${directory.absolutePath}")
    }

    fun startRecording(): Boolean {
        if (isRecording.get()) {
            Log.d(TAG, "Already recording.")
            return true
        }

        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            currentSessionId = timestamp
            currentOutputFile = createOutputFile(timestamp)

            Log.d(TAG, "🎬 Initializing HIGH-QUALITY video encoder: ${currentOutputFile?.absolutePath}")

            // 🎯 고품질 MediaFormat 설정
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (e: Exception) {
                    Log.w(TAG, "VBR 모드 설정 실패, 기본값 사용: ${e.message}")
                }
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            mediaMuxer = MediaMuxer(currentOutputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            startEncodingThread()
            isRecording.set(true)
            muxerStarted = false
            videoTrackIndex = -1
            frameIndex = 0

            Log.d(TAG, "✅ HIGH-QUALITY Video recording started: $timestamp")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start recording: ${e.message}", e)
            cleanup()
            false
        }
    }

    fun addFrame(bitmap: Bitmap): Boolean {
        if (!isRecording.get()) return false

        try {
            val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val scaledBitmap = if (bitmap.width != VIDEO_WIDTH || bitmap.height != VIDEO_HEIGHT) {
                    Bitmap.createScaledBitmap(bitmap, VIDEO_WIDTH, VIDEO_HEIGHT, true)
                } else {
                    bitmap
                }

                // 🛡️ 완전히 독립적인 일회용 복사본 (30fps 견딜 수 있는 유일한 방법)
                val safeCopy = Bitmap.createBitmap(scaledBitmap)
                val yuvData = try {
                    bitmapToColorYUV420(safeCopy) // 독립 복사본에서 getPixels() - 안전!
                } finally {
                    safeCopy.recycle() // 즉시 해제로 메모리 누수 방지
                }

                val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)!!
                inputBuffer.clear()
                inputBuffer.put(yuvData)

                val presentationTimeUs = frameIndex * frameDurationUs
                mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, yuvData.size, presentationTimeUs, 0)
                frameIndex++

                if (scaledBitmap != bitmap) scaledBitmap.recycle()

                if (frameIndex % 30 == 0L) {
                    Log.d(TAG, "✅ 고속 안전 컬러 인코딩: frame=$frameIndex")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add frame: ${e.message}", e)
            return false
        }
    }

    /**
     * 🎯 원본 bitmap 보호를 위한 안전한 YUV 변환
     */
    private fun convertToYUVSafely(originalBitmap: Bitmap): ByteArray {
        // 1. CircularQueue에서 작업용 bitmap 획득
        val workingBitmap = acquireWorkingBitmap()

        return if (workingBitmap != null) {
            try {
                // 2. 안전한 복사 (원본 bitmap 손상 방지)
                val canvas = Canvas(workingBitmap)
                canvas.drawBitmap(originalBitmap, 0f, 0f, null)

                // 3. 기존 YUV 변환 함수 사용 (복사본으로 작업)
                val yuvData = bitmapToColorYUV420(workingBitmap)

                // 4. CircularQueue에 작업용 bitmap 반환
                returnWorkingBitmap(workingBitmap)

                yuvData
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 작업용 bitmap YUV 변환 실패, 대안 사용: ${e.message}")
                returnWorkingBitmap(workingBitmap)
                // 대안: 표준 YUV 변환 (덜 안전하지만 fallback)
                bitmapToStandardYUV420(originalBitmap)
            }
        } else {
            // 작업용 bitmap 없을 때 대안
            Log.w(TAG, "⚠️ 작업용 bitmap 없음, 표준 YUV 변환 사용")
            bitmapToStandardYUV420(originalBitmap)
        }
    }

    /**
     * 🎯 CircularQueue에서 작업용 bitmap 획득
     */
    private fun acquireWorkingBitmap(): Bitmap? {
        val bitmap = workingBitmaps.poll()
        if (bitmap != null && !bitmap.isRecycled) {
            Log.d(TAG, "📥 YUV 작업용 bitmap 획득: @${bitmap.hashCode().toString(16)}")
            return bitmap
        }

        // CircularQueue가 비어있거나 bitmap이 손상된 경우 새로 생성
        return try {
            val newBitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            Log.d(TAG, "🆕 새 YUV 작업용 bitmap 생성: @${newBitmap.hashCode().toString(16)}")
            newBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ OOM: YUV 작업용 bitmap 생성 실패", e)
            System.gc()
            null
        }
    }

    /**
     * 🎯 CircularQueue에 작업용 bitmap 반환
     */
    private fun returnWorkingBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            try {
                // Canvas 바인딩 해제 (안전성 향상)
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)

                // CircularQueue에 반환 (자동으로 capacity 관리)
                workingBitmaps.push(bitmap)
                Log.d(TAG, "🔄 YUV 작업용 bitmap 반환: @${bitmap.hashCode().toString(16)}, pool_size=${workingBitmaps.size()}")

            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 작업용 bitmap 반환 실패: ${e.message}")
                // CircularQueue 반환 실패 시 안전하게 해제
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                        Log.d(TAG, "♻️ YUV 작업용 bitmap 지연 해제: @${bitmap.hashCode().toString(16)}")
                    }
                }, 50)
            }
        }
    }

    // ========= 기존 YUV 변환 함수들 그대로 유지 =========

    /**
     * 🎯 개선된 RGB → YUV420 SemiPlanar 변환 (완전한 컬러 지원)
     */
    private fun bitmapToColorYUV420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val ySize = width * height
        val uvSize = ySize / 2  // NV21: UV가 인터리브된 평면
        val yuv = ByteArray(ySize + uvSize)

        var yIndex = 0
        var uvIndex = ySize

        //Log.d(TAG, "🎨 컬러 YUV 변환 시작: ${width}x${height}, Y_size=$ySize, UV_size=$uvSize")

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]

                // ARGB에서 RGB 추출
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // 🎯 정확한 ITU-R BT.601 변환 공식 (컬러 보존)
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                yuv[yIndex++] = y.coerceIn(16, 235).toByte() // Y 범위: 16-235

                // 🎯 UV 서브샘플링 (2x2마다 하나씩, 컬러 정보 보존)
                if (j % 2 == 0 && i % 2 == 0) {
                    // U (Cb) 성분: 파란색 차이
                    val u = (-0.14713 * r - 0.28886 * g + 0.436 * b + 128).toInt()
                    // V (Cr) 성분: 빨간색 차이
                    val v = (0.615 * r - 0.51499 * g - 0.10001 * b + 128).toInt()

                    // NV21 형식: UV 인터리브 (UVUVUV...)
                    yuv[uvIndex++] = u.coerceIn(16, 240).toByte() // U 범위: 16-240
                    yuv[uvIndex++] = v.coerceIn(16, 240).toByte() // V 범위: 16-240
                }
            }
        }

        //Log.d(TAG, "✅ 컬러 YUV 변환 완료: Y 평면=${ySize}bytes, UV 평면=${uvSize}bytes")
        return yuv
    }

    /**
     * 🎯 대안 YUV 변환 (만약 위 방법이 안 되면 이 방법 사용)
     */
    private fun bitmapToStandardYUV420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val frameSize = width * height
        val yuvBytes = ByteArray(frameSize + frameSize / 2)

        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // 표준 YUV 변환
                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                yuvBytes[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                    val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                    yuvBytes[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuvBytes[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }

        return yuvBytes
    }

    fun stopRecording(): Boolean {
        if (!isRecording.compareAndSet(true, false)) {
            return true
        }
        Log.d(TAG, "🛑 Stopping HIGH-QUALITY recording...")
        try {
            encodingJob?.cancel()
            runBlocking {
                try {
                    withTimeout(2000) { encodingJob?.join() }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Encoding thread join timed out.")
                }
            }
            cleanup()
            Log.d(TAG, "✅ HIGH-QUALITY Recording stopped. Output: ${currentOutputFile?.absolutePath}")
            Log.d(TAG, "📊 Total frames encoded: $frameIndex")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping recording: ${e.message}", e)
            cleanup()
            return false
        }
    }

    private fun startEncodingThread() {
        encodingJob = encodingScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isActive) {
                try {
                    drainEncoder(bufferInfo)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Encoding thread error: ${e.message}", e)
                    break
                }
            }
            Log.d(TAG, "🔚 Encoding thread finished.")
        }
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo) {
        val encoder = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
        when {
            outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                if (muxerStarted) throw IllegalStateException("Format changed twice")
                val outputFormat = encoder.outputFormat
                Log.d(TAG, "🎬 Video format: $outputFormat")
                videoTrackIndex = muxer.addTrack(outputFormat)
                muxer.start()
                muxerStarted = true
                Log.d(TAG, "✅ Muxer started with video track: $videoTrackIndex")
            }
            outputBufferIndex >= 0 -> {
                val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                if (outputBuffer == null) {
                    throw RuntimeException("encoderOutputBuffer $outputBufferIndex was null")
                }
                if (bufferInfo.size != 0 && muxerStarted) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputBufferIndex, false)
            }
        }
    }

    /**
     * 파일 생성 - LogManager에서 주입받은 경로 사용
     */
    private fun createOutputFile(timestamp: String): File {
        try {
            val targetDir = outputDirectory ?: run {
                Log.w(TAG, "⚠️ Output directory not set, using fallback.")
                createFallbackDirectory()
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val outputFile = File(targetDir, "video_${timestamp}.mp4")
            Log.d(TAG, "✅ Final HIGH-QUALITY video file path: ${outputFile.absolutePath}")
            return outputFile

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating output file: ${e.message}", e)
            val emergencyDir = createFallbackDirectory()
            val emergencyFile = File(emergencyDir, "video_${timestamp}.mp4")
            Log.w(TAG, "🚨 Using emergency file path: ${emergencyFile.absolutePath}")
            return emergencyFile
        }
    }

    /**
     * 기본/비상 디렉토리 생성 (경로 주입 실패 시 사용)
     */
    private fun createFallbackDirectory(): File {
        val fallbackDir = context.getExternalFilesDir("gnss_videos_fallback")
        if (fallbackDir != null) {
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            return fallbackDir
        }
        val internalDir = File(context.filesDir, "gnss_videos_fallback")
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }

    private fun cleanup() {
        try {
            // CircularQueue의 작업용 bitmap들 정리
            while (workingBitmaps.isNotEmpty()) {
                val bitmap = workingBitmaps.poll()
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                    Log.d(TAG, "♻️ YUV 작업용 bitmap 정리: @${bitmap.hashCode().toString(16)}")
                }
            }
            workingBitmaps.clear()

            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            mediaMuxer?.stop()
            mediaMuxer?.release()
            mediaMuxer = null

            muxerStarted = false
            Log.d(TAG, "🧹 Video encoder cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }

    fun isRecording(): Boolean = isRecording.get()
    fun getSessionId(): String? = currentSessionId
    fun getFrameCount(): Long = frameIndex
}
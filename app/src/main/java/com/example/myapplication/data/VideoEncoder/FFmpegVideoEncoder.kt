package com.example.myapplication.data.VideoEncoder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
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
        private const val VIDEO_BITRATE = 1_200_000
        private const val I_FRAME_INTERVAL = 2
        private const val COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    }

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isRecording = AtomicBoolean(false)
    private var muxerStarted = false
    private var frameIndex = 0L
    private var currentSessionId: String? = null
    private var currentOutputFile: File? = null

    // ✅ LogManager로부터 주입받을 출력 디렉토리
    private var outputDirectory: File? = null

    private val encodingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var encodingJob: Job? = null
    private val frameDurationUs = 1_000_000L / VIDEO_FPS

    /**
     * ✅ 출력 디렉토리 설정 (LogManager에서 주입)
     * @param directory 동영상 파일을 저장할 디렉토리
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
            // ✅ 주입받은 경로를 사용하여 파일 생성
            currentOutputFile = createOutputFile(timestamp)

            Log.d(TAG, "Initializing video encoder: ${currentOutputFile?.absolutePath}")

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
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

            Log.d(TAG, "✅ Video recording started successfully: $timestamp")
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

                val yuvData = bitmapToYUV420(scaledBitmap)
                val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)!!
                inputBuffer.clear()
                inputBuffer.put(yuvData)

                val presentationTimeUs = frameIndex * frameDurationUs
                mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, yuvData.size, presentationTimeUs, 0)
                frameIndex++

                if (scaledBitmap != bitmap) scaledBitmap.recycle()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add frame: ${e.message}", e)
            return false
        }
    }

    fun stopRecording(): Boolean {
        if (!isRecording.compareAndSet(true, false)) {
            return true
        }
        Log.d(TAG, "Stopping recording...")
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
            Log.d(TAG, "✅ Recording stopped. Output: ${currentOutputFile?.absolutePath}")
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
            Log.d(TAG, "Encoding thread finished.")
        }
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo) {
        val encoder = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
        when {
            outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                if (muxerStarted) throw IllegalStateException("Format changed twice")
                videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                muxerStarted = true
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
     * ✅ 파일 생성 - LogManager에서 주입받은 경로 사용
     */
    private fun createOutputFile(timestamp: String): File {
        try {
            // LogManager에서 주입받은 경로를 최우선으로 사용
            val targetDir = outputDirectory ?: run {
                Log.w(TAG, "⚠️ Output directory not set, using fallback.")
                createFallbackDirectory() // 주입받지 못한 경우 비상 경로 사용
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val outputFile = File(targetDir, "video_${timestamp}.mp4")
            Log.d(TAG, "✅ Final video file path: ${outputFile.absolutePath}")
            return outputFile

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating output file: ${e.message}", e)
            // 최후의 비상 경로
            val emergencyDir = createFallbackDirectory()
            val emergencyFile = File(emergencyDir, "video_${timestamp}.mp4")
            Log.w(TAG, "🚨 Using emergency file path: ${emergencyFile.absolutePath}")
            return emergencyFile
        }
    }

    /**
     * ✅ 기본/비상 디렉토리 생성 (경로 주입 실패 시 사용)
     */
    private fun createFallbackDirectory(): File {
        // 앱 전용 외부 저장소를 비상 경로로 사용 (권한 문제 최소화)
        val fallbackDir = context.getExternalFilesDir("gnss_videos_fallback")
        if (fallbackDir != null) {
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            return fallbackDir
        }
        // 외부 저장소도 사용 불가 시 내부 저장소 사용
        val internalDir = File(context.filesDir, "gnss_videos_fallback")
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }

    private fun cleanup() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            mediaMuxer?.stop()
            mediaMuxer?.release()
            mediaMuxer = null

            muxerStarted = false
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }

    private fun bitmapToYUV420(bitmap: Bitmap): ByteArray {
        // ... 기존 YUV 변환 로직 ...
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height
        for (j in 0 until height) {
            for (i in 0 until width) {
                val p = pixels[j * width + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    fun isRecording(): Boolean = isRecording.get()
    fun getSessionId(): String? = currentSessionId
    fun getFrameCount(): Long = frameIndex
}

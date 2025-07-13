package com.example.myapplication.data.VideoEncoder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
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

    private val frameDurationUs = 1_000_000L / VIDEO_FPS

    // 인코딩 스레드
    private val encodingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var encodingJob: Job? = null

    /**
     * 녹화 시작
     */
    fun startRecording(): Boolean {
        if (isRecording.get()) {
            Log.d(TAG, "이미 녹화 중입니다.")
            return true
        }

        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            currentSessionId = timestamp
            currentOutputFile = createOutputFile(timestamp)

            Log.d(TAG, "비디오 인코더 초기화 시작: ${currentOutputFile?.absolutePath}")

            // MediaFormat 설정
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }

            // MediaCodec 초기화
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // MediaMuxer 초기화
            mediaMuxer = MediaMuxer(currentOutputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 인코딩 스레드 시작
            startEncodingThread()

            isRecording.set(true)
            frameIndex = 0
            muxerStarted = false
            videoTrackIndex = -1

            Log.d(TAG, "✅ 비디오 녹화 시작 성공: $timestamp")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ 녹화 시작 실패: ${e.message}", e)
            cleanup()
            false
        }
    }

    /**
     * 프레임 추가 - 원본 구조 유지
     */
    fun addFrame(bitmap: Bitmap): Boolean {
        if (!isRecording.get() || mediaCodec == null) {
            Log.w(TAG, "녹화 중이 아니거나 MediaCodec이 없습니다.")
            return false
        }

        return try {
            // 비트맵 크기 조정
            val scaledBitmap = if (bitmap.width != VIDEO_WIDTH || bitmap.height != VIDEO_HEIGHT) {
                Bitmap.createScaledBitmap(bitmap, VIDEO_WIDTH, VIDEO_HEIGHT, true)
            } else {
                bitmap
            }

            // 입력 버퍼 가져오기
            val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)

                if (inputBuffer != null) {
                    // YUV420 변환 (원본 방식 유지하되 안전하게)
                    val yuvData = bitmapToYUV420(scaledBitmap)

                    inputBuffer.clear()
                    inputBuffer.put(yuvData)

                    // 타임스탬프 계산
                    val presentationTimeUs = frameIndex * frameDurationUs

                    mediaCodec!!.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        yuvData.size,
                        presentationTimeUs,
                        0
                    )

                    frameIndex++
                    Log.d(TAG, "프레임 추가 성공: $frameIndex")
                } else {
                    Log.w(TAG, "입력 버퍼가 null입니다.")
                    return false
                }
            } else {
                Log.w(TAG, "입력 버퍼를 가져올 수 없습니다.")
                return false
            }

            // 메모리 정리
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }

            true

        } catch (e: Exception) {
            Log.e(TAG, "프레임 추가 실패: ${e.message}", e)
            false
        }
    }

    /**
     * ✅ 수정된 YUV420 변환 - 원본 방식 기반으로 안전하게
     */
    private fun bitmapToYUV420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        // RGB 픽셀 추출
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // YUV420 크기 계산
        val yuvSize = width * height * 3 / 2
        val yuv = ByteArray(yuvSize)

        // RGB to YUV420 변환 (원본 로직 유지)
        var yIndex = 0
        var uvIndex = width * height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Y 계산
                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = yValue.coerceIn(0, 255).toByte()

                // ✅ UV 계산 - 원본 방식이지만 경계 체크 추가
                if (y % 2 == 0 && x % 2 == 0 && uvIndex + 1 < yuvSize) {
                    val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    yuv[uvIndex++] = uValue.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = vValue.coerceIn(0, 255).toByte()
                }
            }
        }

        return yuv
    }

    /**
     * 녹화 중지
     */
    fun stopRecording(): Boolean {
        if (!isRecording.get()) {
            Log.d(TAG, "녹화 중이 아닙니다.")
            return true
        }

        return try {
            Log.d(TAG, "녹화 중지 시작...")

            // End of Stream 신호 전송
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000)
            if (inputBufferIndex != null && inputBufferIndex >= 0) {
                mediaCodec?.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    frameIndex * frameDurationUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }

            // 인코딩 스레드 종료 대기
            encodingJob?.cancel()
            runBlocking {
                try {
                    withTimeout(5000) {
                        encodingJob?.join()
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "인코딩 스레드 종료 타임아웃")
                }
            }

            val totalFrames = frameIndex
            val sessionId = currentSessionId
            val outputFile = currentOutputFile

            cleanup()

            Log.d(TAG, "✅ 녹화 완료: $sessionId, 총 ${totalFrames}프레임")
            Log.d(TAG, "📁 출력 파일: ${outputFile?.absolutePath}")
            Log.d(TAG, "📊 파일 크기: ${outputFile?.length()?.div(1024)}KB")

            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ 녹화 중지 실패: ${e.message}", e)
            cleanup()
            false
        }
    }

    /**
     * ✅ 인코딩 스레드 - 원본 방식 기반으로 단순화
     */
    private fun startEncodingThread() {
        encodingJob = encodingScope.launch {
            Log.d(TAG, "인코딩 스레드 시작")

            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && isRecording.get()) {
                try {
                    drainEncoder(bufferInfo, false)
                    delay(16)
                } catch (e: Exception) {
                    Log.e(TAG, "인코딩 스레드 오류: ${e.message}", e)
                    break
                }
            }

            // ✅ 마지막 데이터 처리 - 단순하고 안전하게
            try {
                Log.d(TAG, "마지막 인코딩 처리 시작...")

                // EOS까지 최대 3초 대기
                val startTime = System.currentTimeMillis()
                val timeout = 3000L

                while (System.currentTimeMillis() - startTime < timeout) {
                    val hasMoreData = drainEncoder(bufferInfo, true)
                    if (!hasMoreData) {
                        Log.d(TAG, "✅ EOS 처리 완료")
                        break
                    }
                    delay(33)
                }

            } catch (e: Exception) {
                Log.e(TAG, "마지막 인코딩 처리 오류: ${e.message}", e)
            }

            Log.d(TAG, "인코딩 스레드 종료")
        }
    }

    /**
     * ✅ 드레인 인코더 - 원본 로직 기반으로 안전하게 수정
     */
    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean): Boolean {
        val encoder = mediaCodec ?: return false
        val muxer = mediaMuxer ?: return false

        var hasMoreData = false

        try {
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)

                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 더 이상 데이터가 없음
                        break
                    }

                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            Log.w(TAG, "출력 포맷이 두 번 변경됨")
                        } else {
                            val newFormat = encoder.outputFormat
                            videoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "Muxer 시작됨. 트랙 인덱스: $videoTrackIndex")
                        }
                        hasMoreData = true
                    }

                    outputBufferIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                            ?: throw RuntimeException("출력 버퍼가 null입니다: $outputBufferIndex")

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // 코덱 구성 정보는 무시
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0) {
                            if (!muxerStarted) {
                                Log.w(TAG, "Muxer가 시작되지 않았는데 샘플 데이터가 도착함")
                            } else {
                                muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            }
                        }

                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        hasMoreData = true

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "End of stream 도달")
                            return false // EOS 도달하면 더 이상 처리할 데이터 없음
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "드레인 인코더 오류: ${e.message}", e)
            return false
        }

        return hasMoreData
    }

    /**
     * ✅ 파일 생성 - 권한 문제 해결
     */
    private fun createOutputFile(timestamp: String): File {
        try {
            // ✅ LoggerManager와 동일한 경로 사용
            val baseDir = File(Environment.getExternalStorageDirectory(), "Movies/gnss")
            val hourlyDir = File(baseDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

            Log.d(TAG, "📁 시도하는 디렉토리: ${hourlyDir.absolutePath}")

            if (!hourlyDir.exists()) {
                val created = hourlyDir.mkdirs()
                Log.d(TAG, "디렉토리 생성 시도: ${hourlyDir.absolutePath}, 성공: $created")

                if (!created) {
                    Log.w(TAG, "⚠️ 외부 저장소 디렉토리 생성 실패, 앱 전용 디렉토리 사용")

                    // ✅ 백업 경로 1: 앱 전용 외부 저장소
                    val fallbackDir1 = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "gnss")
                    val fallbackHourlyDir1 = File(fallbackDir1, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

                    if (!fallbackHourlyDir1.exists()) {
                        val fallbackCreated1 = fallbackHourlyDir1.mkdirs()
                        Log.d(TAG, "백업 디렉토리1 생성: ${fallbackHourlyDir1.absolutePath}, 성공: $fallbackCreated1")

                        if (fallbackCreated1) {
                            Log.d(TAG, "✅ 백업 디렉토리1 사용: ${fallbackHourlyDir1.absolutePath}")
                            return File(fallbackHourlyDir1, "video_${timestamp}.mp4")
                        }
                    }

                    // ✅ 백업 경로 2: 앱 내부 저장소
                    val fallbackDir2 = File(context.filesDir, "gnss_videos")
                    val fallbackHourlyDir2 = File(fallbackDir2, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

                    if (!fallbackHourlyDir2.exists()) {
                        val fallbackCreated2 = fallbackHourlyDir2.mkdirs()
                        Log.d(TAG, "백업 디렉토리2 생성: ${fallbackHourlyDir2.absolutePath}, 성공: $fallbackCreated2")
                    }

                    Log.d(TAG, "✅ 백업 디렉토리2 사용: ${fallbackHourlyDir2.absolutePath}")
                    return File(fallbackHourlyDir2, "video_${timestamp}.mp4")
                }
            }

            val outputFile = File(hourlyDir, "video_${timestamp}.mp4")
            Log.d(TAG, "✅ 최종 출력 파일: ${outputFile.absolutePath}")
            return outputFile

        } catch (e: Exception) {
            Log.e(TAG, "❌ 파일 생성 중 오류: ${e.message}", e)

            val emergencyDir = File(context.filesDir, "emergency_videos")
            emergencyDir.mkdirs()
            val emergencyFile = File(emergencyDir, "video_${timestamp}.mp4")
            Log.w(TAG, "🚨 비상 디렉토리 사용: ${emergencyFile.absolutePath}")
            return emergencyFile
        }
    }

    /**
     * 리소스 정리
     */
    private fun cleanup() {
        try {
            isRecording.set(false)

            encodingJob?.cancel()

            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            mediaMuxer?.stop()
            mediaMuxer?.release()
            mediaMuxer = null

            muxerStarted = false
            videoTrackIndex = -1

        } catch (e: Exception) {
            Log.e(TAG, "리소스 정리 중 오류: ${e.message}", e)
        }
    }

    fun isRecording(): Boolean = isRecording.get()
    fun getSessionId(): String? = currentSessionId
    fun getFrameCount(): Long = frameIndex
    fun getOutputFile(): File? = currentOutputFile
}
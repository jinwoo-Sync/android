package com.example.myapplication.data.VideoEncoder

import android.content.Context
import android.graphics.Bitmap
import android.media.*
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
        // ✅ Surface 대신 YUV420 사용
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

    // ✅ 타임스탬프 추적
    private var startTime = 0L
    private val frameDurationUs = 1_000_000L / VIDEO_FPS // 66.67ms per frame

    // 인코딩 스레드
    private val encodingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var encodingJob: Job? = null

    /**
     * ✅ 녹화 시작 - ByteBuffer 기반
     */
    fun startRecording(): Boolean {
        if (isRecording.get()) {
            Log.d(TAG, "이미 녹화 중입니다.")
            return true
        }

        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            currentSessionId = timestamp
            currentOutputFile = createOutputFile(timestamp)

            Log.d(TAG, "비디오 인코더 초기화 시작: ${currentOutputFile?.absolutePath}")

            // ✅ MediaFormat 설정 (ByteBuffer 기반)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

                // 하드웨어 가속 최적화
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
            startTime = System.nanoTime()

            Log.d(TAG, "✅ 비디오 녹화 시작 성공: $timestamp")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ 녹화 시작 실패: ${e.message}", e)
            cleanup()
            false
        }
    }

    /**
     * ✅ 프레임 추가 - Bitmap을 YUV420으로 변환
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

            // ✅ 입력 버퍼 가져오기
            val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10000) // 10ms timeout
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)

                if (inputBuffer != null) {
                    // ✅ 비트맵을 YUV420으로 변환
                    val yuvData = bitmapToYUV420(scaledBitmap)

                    inputBuffer.clear()
                    inputBuffer.put(yuvData)

                    // ✅ 정확한 타임스탬프 계산
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

            // 스케일된 비트맵이 새로 생성된 경우 메모리 해제
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
     * ✅ Bitmap을 YUV420 바이트 배열로 변환
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

        // RGB to YUV420 변환
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

                // U, V 계산 (2x2 서브샘플링)
                if (y % 2 == 0 && x % 2 == 0) {
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
     * ✅ 녹화 중지 - End of Stream 신호
     */
    fun stopRecording(): Boolean {
        if (!isRecording.get()) {
            Log.d(TAG, "녹화 중이 아닙니다.")
            return true
        }

        return try {
            Log.d(TAG, "녹화 중지 시작...")

            // ✅ End of Stream 신호 전송
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
                encodingJob?.join()
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
     * ✅ 인코딩 스레드 - 출력 버퍼 처리
     */
    private fun startEncodingThread() {
        encodingJob = encodingScope.launch {
            Log.d(TAG, "인코딩 스레드 시작")

            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && isRecording.get()) {
                try {
                    drainEncoder(bufferInfo, false)
                    delay(16) // ~60fps 체크
                } catch (e: Exception) {
                    Log.e(TAG, "인코딩 스레드 오류: ${e.message}", e)
                    break
                }
            }

            // 마지막 데이터 처리
            try {
                drainEncoder(bufferInfo, true)
            } catch (e: Exception) {
                Log.e(TAG, "마지막 인코딩 처리 오류: ${e.message}", e)
            }

            Log.d(TAG, "인코딩 스레드 종료")
        }
    }

    /**
     * ✅ 인코더에서 데이터 추출 및 Muxer에 전송
     */
    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val encoder = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
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
                            // ✅ 정확한 타임스탬프 유지
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "End of stream 도달")
                        break
                    }
                }
            }
        }
    }

    // 나머지 메서드들은 동일...
    private fun createOutputFile(timestamp: String): File {
        val baseDir = File(Environment.getExternalStorageDirectory(), "Movies/gnss")
        val hourlyDir = File(baseDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

        if (!hourlyDir.exists()) {
            val created = hourlyDir.mkdirs()
            Log.d(TAG, "디렉토리 생성: ${hourlyDir.absolutePath}, 성공: $created")
        }

        return File(hourlyDir, "video_${timestamp}.mp4")
    }

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
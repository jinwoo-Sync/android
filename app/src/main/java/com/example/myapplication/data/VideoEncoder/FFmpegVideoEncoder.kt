// SimpleVideoEncoder.kt
package com.example.myapplication.data.VideoEncoder

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.os.Environment
import android.util.Log
import android.view.Surface
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
        private const val COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private var isRecording = AtomicBoolean(false)
    private var muxerStarted = false
    private var frameIndex = 0L
    private var currentSessionId: String? = null
    private var currentOutputFile: File? = null

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
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            currentSessionId = timestamp
            currentOutputFile = createOutputFile(timestamp)

            Log.d(TAG, "비디오 인코더 초기화 시작: ${currentOutputFile?.absolutePath}")

            // MediaFormat 설정
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

                // 하드웨어 가속 최적화
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

                // 실시간 인코딩 최적화
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
                setInteger(MediaFormat.KEY_LATENCY, 1)   // Low latency
            }

            // MediaCodec 초기화
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
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
     * 프레임 추가 (비트맵을 Surface에 그리기)
     */
    fun addFrame(bitmap: Bitmap): Boolean {
        if (!isRecording.get() || inputSurface == null) {
            Log.w(TAG, "녹화 중이 아니거나 Surface가 없습니다.")
            return false
        }

        return try {
            // 비트맵 크기 조정
            val scaledBitmap = if (bitmap.width != VIDEO_WIDTH || bitmap.height != VIDEO_HEIGHT) {
                Bitmap.createScaledBitmap(bitmap, VIDEO_WIDTH, VIDEO_HEIGHT, true)
            } else {
                bitmap
            }

            // Surface에 비트맵 그리기
            val canvas = inputSurface!!.lockCanvas(null)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
            inputSurface!!.unlockCanvasAndPost(canvas)

            frameIndex++

            // 스케일된 비트맵이 새로 생성된 경우 메모리 해제
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }

            Log.d(TAG, "프레임 추가 성공: $frameIndex")
            true

        } catch (e: Exception) {
            Log.e(TAG, "프레임 추가 실패: ${e.message}", e)
            false
        }
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
            mediaCodec?.signalEndOfInputStream()

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
     * 인코딩 스레드 시작
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
     * 인코더에서 데이터 추출
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
                        // 코덱 구성 정보는 muxer에 이미 전달됨
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            Log.w(TAG, "Muxer가 시작되지 않았는데 샘플 데이터가 도착함")
                        } else {
                            // 타임스탬프 조정
                            bufferInfo.presentationTimeUs = System.nanoTime() / 1000

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

    /**
     * 출력 파일 생성
     */
    private fun createOutputFile(timestamp: String): File {
        val baseDir = File(Environment.getExternalStorageDirectory(), "Movies/gnss")
        val hourlyDir = File(baseDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

        if (!hourlyDir.exists()) {
            val created = hourlyDir.mkdirs()
            Log.d(TAG, "디렉토리 생성: ${hourlyDir.absolutePath}, 성공: $created")
        }

        return File(hourlyDir, "video_${timestamp}.mp4")
    }

    /**
     * 리소스 정리
     */
    private fun cleanup() {
        try {
            isRecording.set(false)

            encodingJob?.cancel()

            inputSurface?.release()
            inputSurface = null

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

    /**
     * 현재 상태 확인
     */
    fun isRecording(): Boolean = isRecording.get()
    fun getSessionId(): String? = currentSessionId
    fun getFrameCount(): Long = frameIndex
    fun getOutputFile(): File? = currentOutputFile
}
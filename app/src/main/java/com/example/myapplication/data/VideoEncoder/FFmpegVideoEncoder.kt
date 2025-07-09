import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import com.arthenica.mobileffmpeg.Statistics
import com.arthenica.mobileffmpeg.StatisticsCallback

/**
 * FFmpeg 기반 고성능 비디오 인코더
 *
 * 수학적 이론 기반:
 * - Rate-Distortion 최적화: J(λ) = D + λR, λ는 라그랑주 승수
 * - H.264 표준: DCT 변환 → 양자화 → 엔트로피 코딩
 * - 비트레이트 제어: CBR/VBR 모델, R(t) = f(complexity, motion)
 */
class FFmpegVideoEncoder {

    companion object {
        private const val TAG = "FFmpegVideoEncoder"

        // H.264 인코딩 파라미터 (ITU-T H.264 표준)
        private const val VIDEO_CODEC = "libx264"
        private const val PRESET = "medium"            // 인코딩 속도 vs 압축률 균형점
        private const val CRF = "23"                   // Constant Rate Factor (0-51, 23=권장값)
        private const val PIXEL_FORMAT = "yuv420p"    // 4:2:0 크로마 서브샘플링
        private const val PROFILE = "baseline"        // H.264 프로파일 (호환성 최적화)
        private const val LEVEL = "3.1"              // H.264 레벨 (해상도/bitrate 제한)

        // 수학적 성능 파라미터
        private const val GOP_SIZE = "30"             // Group of Pictures (I-frame 간격)
        private const val B_FRAMES = "0"              // B-frame 비활성화 (실시간 성능)
        private const val THREADS = "auto"            // 멀티스레딩 자동 최적화
    }

    private var currentSessionId: String? = null
    private var frameDirectory: File? = null
    private var outputVideoFile: File? = null
    private var frameCounter = AtomicLong(0)
    private var isActive = AtomicBoolean(false)

    // 성능 모니터링을 위한 통계
    private var encodingStartTime: Long = 0
    private var totalFramesProcessed: Long = 0

    /**
     * FFmpeg 인코더 초기화
     *
     * 수학적 모델:
     * - 메모리 할당: O(W×H×3) bytes per frame (RGB)
     * - 임시 저장공간: O(n×W×H×3) bytes, n=배치크기
     */
    fun initializeEncoder(): Boolean {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            currentSessionId = timestamp

            // 프레임 임시 저장 디렉토리 생성
            frameDirectory = File(context.cacheDir, "ffmpeg_frames_$timestamp").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            // 출력 비디오 파일 경로 설정
            outputVideoFile = createVideoOutputFile(timestamp)

            frameCounter.set(0)
            isActive.set(true)
            encodingStartTime = System.currentTimeMillis()

            Log.d(TAG, "FFmpeg 인코더 초기화 완료: $timestamp")
            Log.d(TAG, "프레임 디렉토리: ${frameDirectory?.absolutePath}")
            Log.d(TAG, "출력 파일: ${outputVideoFile?.absolutePath}")

            true
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg 인코더 초기화 실패: ${e.message}", e)
            false
        }
    }

    /**
     * 프레임 인코딩 (배치 단위)
     *
     * 수학적 복잡도:
     * - 파일 I/O: O(W×H×3) per frame
     * - JPEG 압축: O(W×H×log(W×H)) DCT 변환
     */
    fun encodeFrameBatch(frames: List<SensorData>): Boolean {
        if (!isActive.get() || frameDirectory == null) {
            Log.e(TAG, "인코더가 활성화되지 않음")
            return false
        }

        return try {
            var successCount = 0

            for (frame in frames) {
                frame.bitmap?.let { bitmap ->
                    val frameNumber = frameCounter.incrementAndGet()
                    val frameFile = File(frameDirectory, String.format("frame_%06d.jpg", frameNumber))

                    // Bitmap을 JPEG로 저장 (임시)
                    FileOutputStream(frameFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    }

                    successCount++
                }
            }

            totalFramesProcessed += successCount
            Log.d(TAG, "배치 프레임 저장 완료: $successCount/${frames.size} 프레임")

            true
        } catch (e: Exception) {
            Log.e(TAG, "프레임 배치 인코딩 실패: ${e.message}", e)
            false
        }
    }

    /**
     * 비디오 파일 생성 (FFmpeg 명령어 실행)
     *
     * 수학적 최적화 이론:
     * - Rate-Distortion: J = D + λR
     *   D = Distortion (PSNR 기반)
     *   R = Rate (bitrate)
     *   λ = 라그랑주 승수 (품질-압축률 균형)
     *
     * - CRF 모델: QP = CRF + offset(complexity)
     *   QP: Quantization Parameter
     *   complexity: 프레임 복잡도 (spatial/temporal)
     */
    fun finalizeEncoding(): Boolean {
        if (!isActive.get() || frameDirectory == null || outputVideoFile == null) {
            Log.w(TAG, "인코더가 이미 종료되었거나 초기화되지 않음")
            return false
        }

        return try {
            val frameCount = frameCounter.get()
            if (frameCount == 0L) {
                Log.w(TAG, "저장된 프레임이 없음")
                return false
            }

            Log.d(TAG, "FFmpeg 비디오 생성 시작: $frameCount 프레임")

            // FFmpeg 명령어 구성 (수학적 최적화 파라미터 적용)
            val ffmpegCommand = arrayOf(
                "-y",                                    // 출력 파일 덮어쓰기
                "-framerate", VIDEO_FPS.toString(),      // 입력 프레임레이트
                "-i", "${frameDirectory!!.absolutePath}/frame_%06d.jpg",  // 입력 패턴

                // 비디오 인코딩 파라미터 (H.264 최적화)
                "-c:v", VIDEO_CODEC,                     // H.264 코덱
                "-preset", PRESET,                       // 인코딩 프리셋 (속도 vs 압축률)
                "-crf", CRF,                            // 일정 품질 모드 (23 = 고품질)
                "-pix_fmt", PIXEL_FORMAT,               // YUV 4:2:0 픽셀 포맷
                "-profile:v", PROFILE,                   // H.264 Baseline 프로파일
                "-level", LEVEL,                        // H.264 레벨 3.1

                // GOP 구조 최적화 (실시간 성능)
                "-g", GOP_SIZE,                         // GOP 크기 (I-frame 간격)
                "-bf", B_FRAMES,                        // B-frame 수 (0 = 실시간 최적화)
                "-threads", THREADS,                    // 멀티스레딩

                // 메타데이터
                "-metadata", "title=GNSS Sensor Data",
                "-metadata", "comment=Generated by LoggerManager",

                outputVideoFile!!.absolutePath           // 출력 파일
            )

            Log.d(TAG, "FFmpeg 명령어: ${ffmpegCommand.joinToString(" ")}")

            // 비동기 FFmpeg 실행
            var encodingSuccess = false
            val encodingLatch = CountDownLatch(1)

            // 통계 콜백 설정 (성능 모니터링)
            Config.enableStatisticsCallback { statistics ->
                val progress = (statistics.videoFrameNumber.toDouble() / frameCount * 100).toInt()
                if (progress % 10 == 0) {
                    Log.d(TAG, "인코딩 진행률: $progress% (${statistics.videoFrameNumber}/$frameCount)")
                }
            }

            // FFmpeg 실행
            FFmpeg.executeAsync(ffmpegCommand) { _, returnCode ->
                try {
                    when (returnCode) {
                        Config.RETURN_CODE_SUCCESS -> {
                            encodingSuccess = true
                            val duration = System.currentTimeMillis() - encodingStartTime
                            val fps = (totalFramesProcessed * 1000.0 / duration)

                            Log.d(TAG, "✅ FFmpeg 인코딩 성공!")
                            Log.d(TAG, "📊 성능 통계:")
                            Log.d(TAG, "   - 총 프레임: $frameCount")
                            Log.d(TAG, "   - 처리 시간: ${duration}ms")
                            Log.d(TAG, "   - 평균 FPS: ${"%.2f".format(fps)}")
                            Log.d(TAG, "   - 출력 파일: ${outputVideoFile!!.absolutePath}")
                            Log.d(TAG, "   - 파일 크기: ${outputVideoFile!!.length() / 1024}KB")
                        }
                        Config.RETURN_CODE_CANCEL -> {
                            Log.w(TAG, "FFmpeg 인코딩 취소됨")
                        }
                        else -> {
                            Log.e(TAG, "FFmpeg 인코딩 실패: 리턴코드 $returnCode")
                            Log.e(TAG, "마지막 명령어: ${Config.getLastCommandOutput()}")
                        }
                    }
                } finally {
                    encodingLatch.countDown()
                }
            }

            // 최대 60초 대기
            val completed = encodingLatch.await(60, TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "FFmpeg 인코딩 타임아웃 (60초)")
                FFmpeg.cancel()
                return false
            }

            encodingSuccess

        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg 비디오 생성 실패: ${e.message}", e)
            false
        } finally {
            cleanup()
        }
    }

    /**
     * 리소스 정리
     */
    private fun cleanup() {
        try {
            isActive.set(false)

            // 임시 프레임 파일들 정리
            frameDirectory?.let { dir ->
                if (dir.exists()) {
                    val deleted = dir.deleteRecursively()
                    Log.d(TAG, "임시 프레임 디렉토리 정리: $deleted")
                }
            }

            // 통계 콜백 비활성화
            Config.enableStatisticsCallback(null)

        } catch (e: Exception) {
            Log.e(TAG, "리소스 정리 중 오류: ${e.message}", e)
        }
    }

    private fun createVideoOutputFile(timestamp: String): File {
        val baseDir = File(Environment.getExternalStorageDirectory(), "Movies/gnss")
        val hourlyDir = File(baseDir, SimpleDateFormat("yyyyMMdd_HH", Locale.getDefault()).format(Date()))

        if (!hourlyDir.exists()) {
            hourlyDir.mkdirs()
        }

        return File(hourlyDir, "sensor_video_${timestamp}.mp4")
    }

    fun getSessionId(): String? = currentSessionId
    fun isActive(): Boolean = isActive.get()
    fun getFrameCount(): Long = frameCounter.get()
}
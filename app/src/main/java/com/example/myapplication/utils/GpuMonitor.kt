// utils/GpuMonitor.kt
package com.example.myapplication.utils

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

data class GpuMemoryInfo(
    val totalMemoryKB: Long,
    val availableMemoryKB: Long,
    val usedMemoryKB: Long,
    val usagePercentage: Float,
    val isNvxSupported: Boolean,
    val textureMemoryKB: Long,
    val bufferMemoryKB: Long,
    val renderbufferMemoryKB: Long
)

data class GpuPerformanceMetrics(
    val frameTime: Long,
    val gpuTime: Long,
    val drawCalls: Int,
    val textureBinds: Int,
    val vertices: Int,
    val primitives: Int
)

data class GpuState(
    val renderer: String,
    val vendor: String,
    val version: String,
    val extensions: List<String>,
    val maxTextureSize: Int,
    val maxTextureUnits: Int,
    val maxVertexAttribs: Int,
    val maxViewportDims: IntArray
)

class GpuMonitor private constructor(
    private val context: Context,
    private val fileLogger: FileLogger
) {
    private val TAG = "GpuMonitor"

    companion object {
        @Volatile
        private var INSTANCE: GpuMonitor? = null

        fun getInstance(context: Context, fileLogger: FileLogger): GpuMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GpuMonitor(context.applicationContext, fileLogger).also { INSTANCE = it }
            }
        }
    }

    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private val lastGpuCheck = AtomicLong(0)

    // GPU 상태 추적
    private var gpuState: GpuState? = null
    private var isOpenGLInitialized = AtomicBoolean(false)
    private var glSurfaceView: GLSurfaceView? = null

    // 성능 메트릭 추적
    private val frameTimeHistory = mutableListOf<Long>()
    private val gpuMemoryHistory = mutableListOf<GpuMemoryInfo>()

    // YOLO 추론 상태 추적
    private val yoloInferenceStartTime = AtomicLong(0)
    private val yoloInferenceCount = AtomicLong(0)
    private val yoloGpuTime = AtomicLong(0)

    // UI 렌더링 상태 추적
    private val uiRenderStartTime = AtomicLong(0)
    private val uiRenderCount = AtomicLong(0)
    private val uiGpuTime = AtomicLong(0)

    /**
     * GPU 모니터링 초기화
     */
    fun initialize() {
        fileLogger.i(TAG, "🎮 GPU 모니터링 시스템 초기화 시작")

        // 모니터링용 GLSurfaceView 생성
        setupMonitoringGLSurfaceView()

        // 정기 모니터링 시작
        startPeriodicGpuMonitoring()

        fileLogger.i(TAG, "✅ GPU 모니터링 시스템 초기화 완료")
    }

    /**
     * 모니터링 전용 GLSurfaceView 설정
     */
    private fun setupMonitoringGLSurfaceView() {
        Handler(Looper.getMainLooper()).post {
            try {
                glSurfaceView = GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(object : GLSurfaceView.Renderer {
                        override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                            isOpenGLInitialized.set(true)
                            initializeGpuState()
                            fileLogger.i(TAG, "🎮 GPU 모니터링용 OpenGL 컨텍스트 생성 완료")
                        }

                        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                            GLES20.glViewport(0, 0, width, height)
                        }

                        override fun onDrawFrame(gl: GL10?) {
                            // 모니터링 전용이므로 실제 렌더링은 하지 않음
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        }
                    })

                    // 숨김 처리 (1x1 크기)
                    layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                    visibility = android.view.View.GONE
                }

                fileLogger.i(TAG, "✅ 모니터링용 GLSurfaceView 설정 완료")
            } catch (e: Exception) {
                fileLogger.e(TAG, "❌ GLSurfaceView 설정 실패: ${e.message}", e)
            }
        }
    }

    /**
     * GPU 상태 초기화 (OpenGL 컨텍스트에서 실행)
     */
    private fun initializeGpuState() {
        try {
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown"
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown"
            val extensionsString = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            val extensions = extensionsString.split(" ").filter { it.isNotEmpty() }

            val maxTextureSize = getGLInt(GLES20.GL_MAX_TEXTURE_SIZE)
            val maxTextureUnits = getGLInt(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS)
            val maxVertexAttribs = getGLInt(GLES20.GL_MAX_VERTEX_ATTRIBS)
            val maxViewportDims = getGLIntArray(GLES20.GL_MAX_VIEWPORT_DIMS, 2)

            gpuState = GpuState(
                renderer = renderer,
                vendor = vendor,
                version = version,
                extensions = extensions,
                maxTextureSize = maxTextureSize,
                maxTextureUnits = maxTextureUnits,
                maxVertexAttribs = maxVertexAttribs,
                maxViewportDims = maxViewportDims
            )

            logInitialGpuState()

        } catch (e: Exception) {
            fileLogger.e(TAG, "❌ GPU 상태 초기화 실패: ${e.message}", e)
        }
    }

    /**
     * 정기적 GPU 모니터링 (5초 간격)
     */
    private fun startPeriodicGpuMonitoring() {
        monitoringScope.launch {
            fileLogger.i(TAG, " 정기 GPU 모니터링 시작 (5초 간격)")

            while (isActive) {
                try {
                    delay(5000) // 5초 간격

                    if (isOpenGLInitialized.get()) {
                        logDetailedGpuState("정기_모니터링")
                        checkGpuMemoryLeaks()
                        analyzeGpuPerformance()
                    }

                } catch (e: Exception) {
                    fileLogger.e(TAG, " 정기 GPU 모니터링 오류: ${e.message}", e)
                }
            }
        }
    }

    /**
     * FPS 드롭 시 GPU 상태 긴급 로깅
     */
    fun logFpsDropGpuState(currentFps: Double, context: String) {
        monitoringScope.launch {
            try {
                fileLogger.e(TAG, "🚨 FPS 드롭 시 GPU 긴급 분석 - $context")
                fileLogger.e(TAG, "현재 FPS: ${String.format("%.1f", currentFps)}")

                logDetailedGpuState("FPS_드롭_${currentFps.toInt()}")
                logGpuResourceConflict()

                // GPU 메모리 상태 즉시 체크
                val memoryInfo = getCurrentGpuMemoryInfo()
                if (memoryInfo != null) {
                    fileLogger.e(TAG, "GPU 메모리 사용률: ${String.format("%.1f", memoryInfo.usagePercentage)}%")
                    fileLogger.e(TAG, "가용 GPU 메모리: ${memoryInfo.availableMemoryKB / 1024} MB")

                    if (memoryInfo.usagePercentage > 85.0f) {
                        fileLogger.e(TAG, "⚠️ GPU 메모리 부족 상태 감지!")
                    }
                }

            } catch (e: Exception) {
                fileLogger.e(TAG, "❌ FPS 드롭 GPU 분석 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 현재 GPU 메모리 정보 조회
     */
    @Suppress("UNUSED_PARAMETER")
    private fun getCurrentGpuMemoryInfo(): GpuMemoryInfo? {
        if (!isOpenGLInitialized.get()) return null

        return try {
            val totalMemory = getGpuMemoryExtension(0x9048) // GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX
            val availableMemory = getGpuMemoryExtension(0x9049) // GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX
            val textureMemory = getGpuMemoryExtension(0x904A) // GL_TEXTURE_FREE_MEMORY_ATI 대체
            val bufferMemory = getGpuMemoryExtension(0x904B) // 추정값

            val isNvxSupported = gpuState?.extensions?.any {
                it.contains("GL_NVX_gpu_memory_info")
            } ?: false

            if (totalMemory > 0 && availableMemory > 0) {
                val usedMemory = totalMemory - availableMemory
                val usagePercentage = (usedMemory.toFloat() / totalMemory) * 100f

                GpuMemoryInfo(
                    totalMemoryKB = totalMemory.toLong(),
                    availableMemoryKB = availableMemory.toLong(),
                    usedMemoryKB = usedMemory.toLong(),
                    usagePercentage = usagePercentage,
                    isNvxSupported = isNvxSupported,
                    textureMemoryKB = textureMemory.toLong(),
                    bufferMemoryKB = bufferMemory.toLong(),
                    renderbufferMemoryKB = 0L
                )
            } else null

        } catch (e: Exception) {
            fileLogger.w(TAG, "GPU 메모리 정보 조회 실패: ${e.message}")
            null
        }
    }

    /**
     * GPU 메모리 누수 감지
     */
    private fun checkGpuMemoryLeaks() {
        val currentMemory = getCurrentGpuMemoryInfo() ?: return

        gpuMemoryHistory.add(currentMemory)

        // 최근 10개 샘플만 유지
        if (gpuMemoryHistory.size > 10) {
            gpuMemoryHistory.removeAt(0)
        }

        // 메모리 누수 패턴 감지 (지속적인 증가)
        if (gpuMemoryHistory.size >= 5) {
            val recentSamples = gpuMemoryHistory.takeLast(5)
            val isIncreasingPattern = recentSamples.zipWithNext().all { (prev, next) ->
                next.usedMemoryKB > prev.usedMemoryKB
            }

            if (isIncreasingPattern) {
                val memoryIncrease = recentSamples.last().usedMemoryKB - recentSamples.first().usedMemoryKB
                fileLogger.w(TAG, "🚨 GPU 메모리 누수 패턴 감지!")
                fileLogger.w(TAG, "지속적 메모리 증가: ${memoryIncrease / 1024} MB")
                fileLogger.w(TAG, "현재 사용률: ${String.format("%.1f", currentMemory.usagePercentage)}%")

                // 심각한 누수 시 상세 분석
                if (memoryIncrease > 10240) { // 10MB 이상 증가
                    analyzeMemoryLeakSources()
                }
            }
        }
    }

    /**
     * 메모리 누수 원인 분석
     */
    private fun analyzeMemoryLeakSources() {
        fileLogger.e(TAG, "🔍 GPU 메모리 누수 원인 분석 시작")

        // YOLO vs UI 사용량 비교
        val yoloAvgTime = if (yoloInferenceCount.get() > 0) {
            yoloGpuTime.get() / yoloInferenceCount.get() / 1_000_000
        } else 0L

        val uiAvgTime = if (uiRenderCount.get() > 0) {
            uiGpuTime.get() / uiRenderCount.get() / 1_000_000
        } else 0L

        fileLogger.e(TAG, "YOLO 평균 GPU 시간: ${yoloAvgTime}ms (총 ${yoloInferenceCount.get()}회)")
        fileLogger.e(TAG, "UI 평균 GPU 시간: ${uiAvgTime}ms (총 ${uiRenderCount.get()}회)")

        // 의심 영역 식별
        when {
            yoloAvgTime > 50 -> fileLogger.e(TAG, "⚠️ YOLO 추론이 GPU 과부하 주원인으로 의심됨")
            uiAvgTime > 10 -> fileLogger.e(TAG, "⚠️ UI 렌더링이 GPU 과부하 주원인으로 의심됨")
            else -> fileLogger.e(TAG, "⚠️ 기타 GPU 사용원인 조사 필요")
        }

        // 텍스처 메모리 분석
        val currentMemory = getCurrentGpuMemoryInfo()
        if (currentMemory != null && currentMemory.textureMemoryKB > 50000) { // 50MB 초과
            fileLogger.e(TAG, "⚠️ 텍스처 메모리 과다 사용: ${currentMemory.textureMemoryKB / 1024}MB")
        }
    }

    /**
     * GPU 성능 분석
     */
    private fun analyzeGpuPerformance() {
        try {
            fileLogger.i(TAG, "📊 GPU 성능 분석 리포트")
            fileLogger.i(TAG, "총 YOLO 추론: ${yoloInferenceCount.get()}회")
            fileLogger.i(TAG, "총 UI 렌더링: ${uiRenderCount.get()}회")

            if (yoloInferenceCount.get() > 0) {
                val avgYoloTime = yoloGpuTime.get() / yoloInferenceCount.get() / 1_000_000
                fileLogger.i(TAG, "YOLO 평균 시간: ${avgYoloTime}ms")

                // 성능 등급 평가
                val yoloGrade = when {
                    avgYoloTime < 30 -> "우수"
                    avgYoloTime < 60 -> "양호"
                    avgYoloTime < 100 -> "주의"
                    else -> "위험"
                }
                fileLogger.i(TAG, "YOLO 성능 등급: $yoloGrade")
            }

            if (uiRenderCount.get() > 0) {
                val avgUiTime = uiGpuTime.get() / uiRenderCount.get() / 1_000_000
                fileLogger.i(TAG, "UI 평균 시간: ${avgUiTime}ms")

                val uiGrade = when {
                    avgUiTime < 8 -> "우수 (120fps+)"
                    avgUiTime < 16 -> "양호 (60fps)"
                    avgUiTime < 33 -> "주의 (30fps)"
                    else -> "위험 (<30fps)"
                }
                fileLogger.i(TAG, "UI 성능 등급: $uiGrade")
            }

        } catch (e: Exception) {
            fileLogger.e(TAG, "GPU 성능 분석 실패: ${e.message}", e)
        }
    }

    /**
     * GPU 리소스 충돌 감지
     */
    private fun logGpuResourceConflict() {
        fileLogger.w(TAG, "🔍 GPU 리소스 충돌 분석")

        val yoloRecentTime = if (System.nanoTime() - yoloInferenceStartTime.get() < 100_000_000) {
            "YOLO 추론 활성"
        } else "YOLO 비활성"

        val uiRecentTime = if (System.nanoTime() - uiRenderStartTime.get() < 33_000_000) {
            "UI 렌더링 활성"
        } else "UI 비활성"

        fileLogger.w(TAG, "현재 GPU 사용 상태:")
        fileLogger.w(TAG, "  - $yoloRecentTime")
        fileLogger.w(TAG, "  - $uiRecentTime")

        // 동시 사용 감지
        if (yoloRecentTime.contains("활성") && uiRecentTime.contains("활성")) {
            fileLogger.e(TAG, "⚠️ YOLO + UI 동시 GPU 사용 감지 - 리소스 충돌 가능성!")
        }
    }

    /**
     * GPU 과부하 상태 로깅
     */
    private fun logGpuHeavyUsage(context: String, timeMs: Long) {
        fileLogger.w(TAG, "🔥 GPU 과부하 감지 - $context")
        fileLogger.w(TAG, "처리 시간: ${timeMs}ms")

        monitoringScope.launch {
            delay(100) // GPU 안정화 대기
            logDetailedGpuState("GPU_과부하_$context")
        }
    }

    /**
     * 상세 GPU 상태 로깅
     */
    private fun logDetailedGpuState(context: String) {
        if (!isOpenGLInitialized.get()) {
            fileLogger.w(TAG, "GPU 상태 확인 불가 - OpenGL 미초기화 ($context)")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                fileLogger.i(TAG, "🎮 상세 GPU 상태 - $context")

                // 기본 GPU 정보
                gpuState?.let { state ->
                    fileLogger.i(TAG, "GPU 렌더러: ${state.renderer}")
                    fileLogger.i(TAG, "GPU 벤더: ${state.vendor}")
                    fileLogger.i(TAG, "OpenGL 버전: ${state.version}")
                    fileLogger.i(TAG, "최대 텍스처 크기: ${state.maxTextureSize}")
                }

                // 메모리 정보
                val memoryInfo = getCurrentGpuMemoryInfo()
                if (memoryInfo != null) {
                    fileLogger.i(TAG, "=== GPU 메모리 상태 ===")
                    fileLogger.i(TAG, "총 메모리: ${memoryInfo.totalMemoryKB / 1024} MB")
                    fileLogger.i(TAG, "사용 메모리: ${memoryInfo.usedMemoryKB / 1024} MB")
                    fileLogger.i(TAG, "가용 메모리: ${memoryInfo.availableMemoryKB / 1024} MB")
                    fileLogger.i(TAG, "사용률: ${String.format("%.1f", memoryInfo.usagePercentage)}%")
                    fileLogger.i(TAG, "NVX 지원: ${memoryInfo.isNvxSupported}")

                    if (memoryInfo.textureMemoryKB > 0) {
                        fileLogger.i(TAG, "텍스처 메모리: ${memoryInfo.textureMemoryKB / 1024} MB")
                    }
                } else {
                    fileLogger.w(TAG, "GPU 메모리 정보 조회 실패 - 확장 미지원")
                }

                // OpenGL 에러 체크
                val glError = GLES20.glGetError()
                if (glError != GLES20.GL_NO_ERROR) {
                    fileLogger.e(TAG, "OpenGL 에러 감지: 0x${glError.toString(16)}")
                }

                fileLogger.i(TAG, "GPU 상태 로깅 완료 - $context")

            } catch (e: Exception) {
                fileLogger.e(TAG, "GPU 상태 로깅 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 컨텍스트와 함께 GPU 상태 로깅
     */
    private suspend fun logGpuStateWithContext(eventType: String, contextData: Map<String, String>) {
        withContext(Dispatchers.Main) {
            try {
                fileLogger.i(TAG, "GPU 이벤트 - $eventType")
                contextData.forEach { (key, value) ->
                    fileLogger.i(TAG, "  $key: $value")
                }

                // 현재 GPU 상태도 함께 로깅
                val memoryInfo = getCurrentGpuMemoryInfo()
                if (memoryInfo != null) {
                    fileLogger.i(TAG, "  GPU_사용률: ${String.format("%.1f", memoryInfo.usagePercentage)}%")
                    fileLogger.i(TAG, "  GPU_가용메모리: ${memoryInfo.availableMemoryKB / 1024}MB")
                }

            } catch (e: Exception) {
                fileLogger.e(TAG, "GPU 컨텍스트 로깅 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 초기 GPU 상태 로깅
     */
    private fun logInitialGpuState() {
        gpuState?.let { state ->
            fileLogger.i(TAG, "=".repeat(60))
            fileLogger.i(TAG, "🎮 GPU 초기 상태 정보")
            fileLogger.i(TAG, "렌더러: ${state.renderer}")
            fileLogger.i(TAG, "벤더: ${state.vendor}")
            fileLogger.i(TAG, "OpenGL 버전: ${state.version}")
            fileLogger.i(TAG, "최대 텍스처 크기: ${state.maxTextureSize}")
            fileLogger.i(TAG, "최대 텍스처 유닛: ${state.maxTextureUnits}")
            fileLogger.i(TAG, "최대 정점 속성: ${state.maxVertexAttribs}")
            fileLogger.i(TAG, "최대 뷰포트: ${state.maxViewportDims[0]} x ${state.maxViewportDims[1]}")

            // 주요 확장 기능 체크
            val importantExtensions = listOf(
                "GL_NVX_gpu_memory_info",
                "GL_ATI_meminfo",
                "GL_EXT_texture_compression_s3tc",
                "GL_OES_texture_npot"
            )

            fileLogger.i(TAG, "주요 확장 기능:")
            importantExtensions.forEach { ext ->
                val supported = state.extensions.contains(ext)
                fileLogger.i(TAG, "  $ext: ${if (supported) "지원" else "미지원"}")
            }

            fileLogger.i(TAG, "=".repeat(60))
        }
    }

    // 유틸리티 메서드들
    private fun getGLInt(pname: Int): Int {
        val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
        GLES20.glGetIntegerv(pname, buffer)
        return buffer.get(0)
    }

    private fun getGLIntArray(pname: Int, count: Int): IntArray {
        val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        GLES20.glGetIntegerv(pname, buffer)
        return IntArray(count) { buffer.get(it) }
    }

    private fun getGpuMemoryExtension(pname: Int): Int {
        return try {
            val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
            GLES20.glGetIntegerv(pname, buffer)
            val error = GLES20.glGetError()
            if (error == GLES20.GL_NO_ERROR) buffer.get(0) else 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 모니터링 종료
     */
    fun shutdown() {
        fileLogger.i(TAG, "🎮 GPU 모니터링 종료 시작")

        // 최종 GPU 상태 로깅
        logFinalGpuReport()

        isMonitoring.set(false)
        monitoringScope.cancel()

        glSurfaceView?.let { view ->
            Handler(Looper.getMainLooper()).post {
                try {
                    view.onPause()
                } catch (e: Exception) {
                    fileLogger.w(TAG, "GLSurfaceView pause 실패: ${e.message}")
                }
            }
        }

        fileLogger.i(TAG, "✅ GPU 모니터링 종료 완료")
    }

    /**
     * YOLO 추론 시작 추적 - 안전한 처리
     */
    fun trackYoloInferenceStart(frameId: Long) {
        try {
            yoloInferenceStartTime.set(System.nanoTime())
            yoloInferenceCount.incrementAndGet()

            // INFO 로깅 주석처리 - 너무 빈번함
            /*
            // 로깅도 별도 스레드에서 처리
            monitoringScope.launch {
                try {
                    logGpuStateWithContext("YOLO_추론_시작", mapOf(
                        "frameId" to frameId.toString(),
                        "추론_횟수" to yoloInferenceCount.get().toString(),
                        "컨텍스트" to "딥러닝_GPU_사용_시작"
                    ))
                } catch (e: Exception) {
                    // 로깅 실패해도 무시
                }
            }
            */
        } catch (e: Exception) {
            // 추적 실패해도 메인 로직에 영향 없음
            Log.w(TAG, "YOLO 추론 시작 추적 실패: ${e.message}")
        }
    }

    /**
     * YOLO 추론 완료 추적 - 안전한 처리
     */
    fun trackYoloInferenceEnd(frameId: Long, inferenceTimeMs: Long) {
        try {
            val gpuTime = System.nanoTime() - yoloInferenceStartTime.get()
            yoloGpuTime.addAndGet(gpuTime)

            // INFO 로깅 주석처리, WARN 이상만 유지
            /*
            // 로깅도 별도 스레드에서 처리
            monitoringScope.launch {
                try {
                    logGpuStateWithContext("YOLO_추론_완료", mapOf(
                        "frameId" to frameId.toString(),
                        "추론_시간_ms" to inferenceTimeMs.toString(),
                        "GPU_시간_ns" to gpuTime.toString(),
                        "총_GPU_시간_ms" to (yoloGpuTime.get() / 1_000_000).toString(),
                        "컨텍스트" to "딥러닝_GPU_사용_완료"
                    ))
                } catch (e: Exception) {
                    // 로깅 실패해도 무시
                }
            }
            */

            // WARN 이상만 유지 - 긴 추론 시간 감지
            if (inferenceTimeMs > 100) {
                monitoringScope.launch {
                    try {
                        logGpuHeavyUsage("YOLO_장시간_추론", inferenceTimeMs)
                    } catch (e: Exception) {
                        // 로깅 실패해도 무시
                    }
                }
            }
        } catch (e: Exception) {
            // 추적 실패해도 메인 로직에 영향 없음
            Log.w(TAG, "YOLO 추론 완료 추적 실패: ${e.message}")
        }
    }

    /**
     * UI 렌더링 시작 추적 - 안전한 처리
     */
    fun trackUIRenderStart(frameId: Long) {
        try {
            uiRenderStartTime.set(System.nanoTime())
            uiRenderCount.incrementAndGet()

            // 60프레임마다만 로깅 (성능 영향 최소화)
            if (uiRenderCount.get() % 60 == 0L) {
                monitoringScope.launch {
                    try {
                        logGpuStateWithContext("UI_렌더링_60프레임", mapOf(
                            "frameId" to frameId.toString(),
                            "렌더링_횟수" to uiRenderCount.get().toString(),
                            "컨텍스트" to "OpenGL_UI_렌더링"
                        ))
                    } catch (e: Exception) {
                        // 로깅 실패해도 무시
                    }
                }
            }
        } catch (e: Exception) {
            // 추적 실패해도 메인 로직에 영향 없음
            Log.w(TAG, "UI 렌더링 시작 추적 실패: ${e.message}")
        }
    }

    /**
     * UI 렌더링 완료 추적 - 안전한 처리
     */
    fun trackUIRenderEnd(frameId: Long) {
        try {
            val gpuTime = System.nanoTime() - uiRenderStartTime.get()
            uiGpuTime.addAndGet(gpuTime)

            // 긴 렌더링 시간 감지 (16ms 초과 시)
            val renderTimeMs = gpuTime / 1_000_000
            if (renderTimeMs > 16) {
                monitoringScope.launch {
                    try {
                        logGpuHeavyUsage("UI_장시간_렌더링", renderTimeMs)
                    } catch (e: Exception) {
                        // 로깅 실패해도 무시
                    }
                }
            }
        } catch (e: Exception) {
            // 추적 실패해도 메인 로직에 영향 없음
            Log.w(TAG, "UI 렌더링 완료 추적 실패: ${e.message}")
        }
    }

    /**
     * 최종 GPU 리포트
     */
    private fun logFinalGpuReport() {
        fileLogger.i(TAG, "📊 최종 GPU 사용 리포트")
        fileLogger.i(TAG, "총 YOLO 추론 횟수: ${yoloInferenceCount.get()}")
        fileLogger.i(TAG, "총 UI 렌더링 횟수: ${uiRenderCount.get()}")

        if (yoloInferenceCount.get() > 0) {
            val avgYolo = yoloGpuTime.get() / yoloInferenceCount.get() / 1_000_000
            fileLogger.i(TAG, "YOLO 평균 처리 시간: ${avgYolo}ms")
        }

        if (uiRenderCount.get() > 0) {
            val avgUI = uiGpuTime.get() / uiRenderCount.get() / 1_000_000
            fileLogger.i(TAG, "UI 평균 렌더링 시간: ${avgUI}ms")
        }

        // 메모리 누수 최종 체크
        if (gpuMemoryHistory.size >= 2) {
            val initialMemory = gpuMemoryHistory.first().usedMemoryKB
            val finalMemory = gpuMemoryHistory.last().usedMemoryKB
            val memoryDiff = finalMemory - initialMemory

            fileLogger.i(TAG, "세션 중 GPU 메모리 변화: ${memoryDiff / 1024}MB")
            if (memoryDiff > 5120) { // 5MB 이상 증가
                fileLogger.w(TAG, "⚠️ 잠재적 GPU 메모리 누수 감지")
            }
        }
    }
}
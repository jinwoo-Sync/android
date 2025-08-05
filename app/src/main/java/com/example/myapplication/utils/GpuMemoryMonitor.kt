// app/src/main/java/com/example/myapplication/utils/GpuMemoryMonitor.kt
package com.example.myapplication.utils

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class GpuMemoryInfo(
    val timestamp: Long,
    val graphicsMemoryMB: Float,
    val glMemoryMB: Float,
    val textureMemoryMB: Float,
    val totalSystemMemoryMB: Float,
    val availableSystemMemoryMB: Float,
    val memoryPressureLevel: String,
    val eglContextCount: Int = 0,
    val surfaceBufferCount: Int = 0
)

class GpuMemoryMonitor private constructor(
    private val context: Context,
    private val fileLogger: FileLogger
) {
    companion object {
        private const val TAG = "GpuMemoryMonitor"

        @Volatile
        private var INSTANCE: GpuMemoryMonitor? = null

        fun getInstance(context: Context): GpuMemoryMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GpuMemoryMonitor(
                    context.applicationContext,
                    FileLogger.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicReference(false)

    // GPU 상태 추적
    private val lastGpuInfo = AtomicReference<GpuMemoryInfo?>(null)
    private val gpuMemoryLeakDetected = AtomicReference(false)
    private val criticalGpuStateCount = AtomicLong(0)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * GPU 메모리 모니터링 시작 (1초마다)
     */
    fun startGpuMemoryMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            fileLogger.i(TAG, "🔍 GPU 메모리 모니터링 시작")

            monitoringScope.launch {
                while (isMonitoring.get()) {
                    try {
                        val gpuInfo = collectGpuMemoryInfo()
                        analyzeGpuMemoryTrend(gpuInfo)
                        lastGpuInfo.set(gpuInfo)

                        delay(1000) // 1초마다 체크
                    } catch (e: Exception) {
                        fileLogger.e(TAG, "GPU 메모리 모니터링 오류: ${e.message}", e)
                        delay(2000) // 오류 시 2초 대기
                    }
                }
            }
        }
    }

    /**
     * GPU 메모리 정보 수집
     */
    private fun collectGpuMemoryInfo(): GpuMemoryInfo {
        val timestamp = System.currentTimeMillis()

        // Debug.MemoryInfo를 통한 Graphics 메모리 확인
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)

        val graphicsMemoryMB = getGraphicsMemoryMB(memoryInfo)
        val glMemoryMB = getOpenGLMemoryMB()
        val textureMemoryMB = getTextureMemoryMB()

        // 시스템 메모리 정보
        val systemMemInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemInfo)

        val totalSystemMemoryMB = systemMemInfo.totalMem / (1024f * 1024f)
        val availableSystemMemoryMB = systemMemInfo.availMem / (1024f * 1024f)

        val memoryPressureLevel = when {
            systemMemInfo.lowMemory -> "HIGH"
            availableSystemMemoryMB < totalSystemMemoryMB * 0.1f -> "MEDIUM"
            else -> "LOW"
        }

        return GpuMemoryInfo(
            timestamp = timestamp,
            graphicsMemoryMB = graphicsMemoryMB,
            glMemoryMB = glMemoryMB,
            textureMemoryMB = textureMemoryMB,
            totalSystemMemoryMB = totalSystemMemoryMB,
            availableSystemMemoryMB = availableSystemMemoryMB,
            memoryPressureLevel = memoryPressureLevel,
            eglContextCount = getEGLContextCount(),
            surfaceBufferCount = getSurfaceBufferCount()
        )
    }

    private fun getGraphicsMemoryMB(memoryInfo: Debug.MemoryInfo): Float {
        return try {
            // Android의 Graphics 메모리 추적
            val graphicsMemoryKB = memoryInfo.getMemoryStat("summary.graphics")?.toFloatOrNull() ?: 0f
            graphicsMemoryKB / 1024f
        } catch (e: Exception) {
            Log.w(TAG, "Graphics 메모리 정보 수집 실패: ${e.message}")
            0f
        }
    }

    private fun getOpenGLMemoryMB(): Float {
        return try {
            // OpenGL 메모리 사용량 추정 (GLES20 확장 기능 사용)
            val glMemoryInfo = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, glMemoryInfo, 0)

            // 추정치: 텍스처 크기 기반 계산
            val estimatedMB = glMemoryInfo[0] * 4f / (1024f * 1024f) // 대략적 추정
            estimatedMB.coerceAtMost(100f) // 최대 100MB로 제한
        } catch (e: Exception) {
            0f
        }
    }

    private fun getTextureMemoryMB(): Float {
        // TensorFlow Lite GPU 메모리 사용량 추정 (실제 API가 없으므로 추정)
        return 20f // YOLO 모델 기준 대략 20MB 추정
    }

    private fun getEGLContextCount(): Int {
        // EGL 컨텍스트 개수는 직접 추적이 어려우므로 추정
        return 1 // UI + TensorFlow = 대략 1-2개
    }

    private fun getSurfaceBufferCount(): Int {
        // Surface buffer 개수 추정 (GLSurfaceView 기준)
        return 3 // Triple buffering 가정
    }

    /**
     * GPU 메모리 트렌드 분석
     */
    private fun analyzeGpuMemoryTrend(currentInfo: GpuMemoryInfo) {
        val previousInfo = lastGpuInfo.get()

        // 1초마다 상세 로그 (FPS 1fps 상황에서만)
        logDetailedGpuState(currentInfo)

        if (previousInfo != null) {
            // GPU 메모리 누수 감지
            val memoryIncrease = currentInfo.graphicsMemoryMB - previousInfo.graphicsMemoryMB

            if (memoryIncrease > 5f) { // 5MB 이상 증가
                fileLogger.w(TAG, " GPU 메모리 급격한 증가 감지: +${String.format("%.1f", memoryIncrease)}MB")
                gpuMemoryLeakDetected.set(true)
            }

            // 임계 상태 감지
            if (currentInfo.graphicsMemoryMB > 100f || currentInfo.memoryPressureLevel == "HIGH") {
                val criticalCount = criticalGpuStateCount.incrementAndGet()
                fileLogger.e(TAG, " GPU 임계 상태: Graphics=${String.format("%.1f", currentInfo.graphicsMemoryMB)}MB, " +
                        "Pressure=${currentInfo.memoryPressureLevel}, 연속 ${criticalCount}회")

                if (criticalCount >= 3) {
                    triggerGpuEmergencyRecovery(currentInfo)
                }
            } else {
                criticalGpuStateCount.set(0)
                gpuMemoryLeakDetected.set(false)
            }
        }
    }

    /**
     * 상세 GPU 상태 로깅 (FPS 드롭 시 특별히 상세하게)
     */
    private fun logDetailedGpuState(gpuInfo: GpuMemoryInfo) {
        val timeStr = dateFormat.format(Date(gpuInfo.timestamp))

        fileLogger.i(TAG, "=== GPU 메모리 상태 [$timeStr] ===")
        fileLogger.i(TAG, "Graphics Memory: ${String.format("%.1f", gpuInfo.graphicsMemoryMB)} MB")
        fileLogger.i(TAG, "GL Memory: ${String.format("%.1f", gpuInfo.glMemoryMB)} MB")
        fileLogger.i(TAG, "Texture Memory: ${String.format("%.1f", gpuInfo.textureMemoryMB)} MB")
        fileLogger.i(TAG, "System Memory: ${String.format("%.1f", gpuInfo.availableSystemMemoryMB)}/${String.format("%.1f", gpuInfo.totalSystemMemoryMB)} MB")
        fileLogger.i(TAG, "Memory Pressure: ${gpuInfo.memoryPressureLevel}")
        fileLogger.i(TAG, "EGL Contexts: ${gpuInfo.eglContextCount}")
        fileLogger.i(TAG, "Surface Buffers: ${gpuInfo.surfaceBufferCount}")

        // GPU 메모리 누수 의심 시 추가 정보
        if (gpuMemoryLeakDetected.get()) {
            fileLogger.w(TAG, " GPU 메모리 누수 의심 상태")
        }
    }

    /**
     * GPU 응급 복구 트리거
     */
    private fun triggerGpuEmergencyRecovery(gpuInfo: GpuMemoryInfo) {
        fileLogger.e(TAG, " GPU 응급 복구 트리거 ")
        fileLogger.e(TAG, "임계 상태 정보:")
        fileLogger.e(TAG, "  Graphics Memory: ${String.format("%.1f", gpuInfo.graphicsMemoryMB)} MB")
        fileLogger.e(TAG, "  Memory Pressure: ${gpuInfo.memoryPressureLevel}")
        fileLogger.e(TAG, "  연속 임계 횟수: ${criticalGpuStateCount.get()}회")

        // 시스템 GC 강제 실행
        System.gc()
        System.runFinalization()

        fileLogger.e(TAG, " GPU 응급 복구 완료 ")

        // 카운터 리셋
        criticalGpuStateCount.set(0)
    }

    /**
     * 현재 GPU 상태 반환
     */
    fun getCurrentGpuInfo(): GpuMemoryInfo? = lastGpuInfo.get()

    /**
     * GPU 메모리 누수 감지 여부
     */
    fun isGpuMemoryLeakDetected(): Boolean = gpuMemoryLeakDetected.get()

    /**
     * 모니터링 중지
     */
    fun stopGpuMemoryMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            monitoringScope.cancel()
            fileLogger.i(TAG, " GPU 메모리 모니터링 중지")
        }
    }
}
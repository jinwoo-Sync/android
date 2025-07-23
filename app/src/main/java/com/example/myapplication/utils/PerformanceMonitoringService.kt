// PerformanceMonitoringService.kt
package com.example.myapplication.utils

import android.content.Context
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PerformanceMonitoringService private constructor(
    private val context: Context,
    private val fileLogger: FileLogger,
    private val resourceMonitor: ResourceMonitor
) {
    companion object {
        private const val TAG = "PerformanceMonitoringService"
        private const val ROUTINE_LOG_INTERVAL_MS = 30_000L  // 30초
        private const val FPS_MONITOR_INTERVAL_MS = 1_000L   // 1초
        private const val CRITICAL_FPS_THRESHOLD = 10.0     // 10fps 미만
        private const val SEVERE_FPS_THRESHOLD = 5.0        // 5fps 미만 (크리티컬)

        private val MB = 1024 * 1024

        @Volatile
        private var INSTANCE: PerformanceMonitoringService? = null

        fun getInstance(context: Context): PerformanceMonitoringService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PerformanceMonitoringService(
                    context.applicationContext,
                    FileLogger.getInstance(context),
                    ResourceMonitor.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }

    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // FPS 모니터링 관련
    private var fpsMonitorCallback: Choreographer.FrameCallback? = null
    private val frameCount = AtomicLong(0)
    private var lastFpsCalculationTime = 0L
    private var currentFps = 0.0
    private var consecutiveLowFpsCount = 0

    // 크리티컬 분석 관련
    private var lastCriticalAnalysisTime = 0L
    private val criticalAnalysisInterval = 5_000L  // 5초 최소 간격

    /**
     * 모니터링 시작
     */
    fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "🚀 성능 모니터링 서비스 시작")
            startRoutineLogging()
            startFpsMonitoring()
        }
    }

    /**
     * 모니터링 중지
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.d(TAG, "🛑 성능 모니터링 서비스 중지")
            stopFpsMonitoring()
            monitoringScope.cancel()
        }
    }

    /**
     * 📊 30초 주기 정기 로깅
     */
    private fun startRoutineLogging() {
        monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                try {
                    logRoutinePerformanceData()
                    delay(ROUTINE_LOG_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "정기 로깅 오류: ${e.message}", e)
                    delay(5000) // 오류 시 5초 대기
                }
            }
        }
    }

    /**
     * 📊 30초 주기 성능 데이터 로깅
     */
    private suspend fun logRoutinePerformanceData() = withContext(Dispatchers.IO) {
        try {
            val timestamp = dateFormat.format(Date())
            val appMemory = resourceMonitor.getAppMemoryInfo()
            val systemMemory = resourceMonitor.getSystemMemoryInfo()
            val cpuInfo = resourceMonitor.getCpuInfo()
            val threadInfo = resourceMonitor.getThreadInfo()

            val logEntry = buildString {
                appendLine("╔══════════════════════════════════════════════════════════════╗")
                appendLine("║                     정기 성능 모니터링 (30초 주기)                   ║")
                appendLine("╠══════════════════════════════════════════════════════════════╣")
                appendLine("║ ⏰ 시간: $timestamp")
                appendLine("║ 📱 현재 FPS: ${String.format("%.1f", currentFps)} fps")
                appendLine("║")
                appendLine("║ 🧠 앱 메모리:")
                appendLine("║   ├─ 힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
                appendLine("║   ├─ 사용량: ${String.format("%.1f", appMemory.usedHeapMB)} MB")
                appendLine("║   ├─ 가용: ${String.format("%.1f", appMemory.availableHeapMB)} MB")
                appendLine("║   ├─ Native: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
                appendLine("║   └─ 압박: ${appMemory.memoryPressureLevel}")
                appendLine("║")
                appendLine("║ 🌐 시스템 메모리:")
                appendLine("║   ├─ 전체: ${String.format("%.1f", systemMemory.totalSystemMB)} MB")
                appendLine("║   ├─ 가용: ${String.format("%.1f", systemMemory.availableSystemMB)} MB")
                appendLine("║   ├─ 사용률: ${String.format("%.1f", systemMemory.systemMemoryPressure)}%")
                appendLine("║   └─ 부족: ${if (systemMemory.systemMemoryLow) "예" else "아니오"}")
                appendLine("║")
                appendLine("║ ⚡ CPU:")
                appendLine("║   ├─ 사용률: ${String.format("%.1f", cpuInfo.usagePercent)}%")
                appendLine("║   └─ 코어: ${cpuInfo.coreCount}개")
                appendLine("║")
                appendLine("║ 🧵 스레드:")
                appendLine("║   ├─ 활성: ${threadInfo.activeThreadCount}개")
                appendLine("║   └─ 현재: ${threadInfo.currentThreadName}")
                appendLine("╚══════════════════════════════════════════════════════════════╝")
            }

            fileLogger.i(TAG, logEntry)
            Log.d(TAG, "📊 정기 성능 데이터 로깅 완료")

        } catch (e: Exception) {
            Log.e(TAG, "정기 성능 데이터 로깅 실패: ${e.message}", e)
            fileLogger.e(TAG, "정기 성능 데이터 로깅 실패: ${e.message}", e)
        }
    }

    /**
     * 🎯 FPS 모니터링 시작
     */
    private fun startFpsMonitoring() {
        lastFpsCalculationTime = System.currentTimeMillis()
        frameCount.set(0)

        fpsMonitorCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCount.incrementAndGet()

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFpsCalculationTime >= FPS_MONITOR_INTERVAL_MS) {
                    calculateAndCheckFps(currentTime)
                    lastFpsCalculationTime = currentTime
                    frameCount.set(0)
                }

                // 다음 프레임 등록
                if (fpsMonitorCallback != null && isMonitoring.get()) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        Choreographer.getInstance().postFrameCallback(fpsMonitorCallback!!)
        Log.d(TAG, "🎯 FPS 모니터링 시작")
    }

    /**
     * 🎯 FPS 계산 및 크리티컬 상황 감지
     */
    private fun calculateAndCheckFps(currentTime: Long) {
        val frames = frameCount.get()
        val timeDiff = currentTime - lastFpsCalculationTime
        currentFps = if (timeDiff > 0) (frames * 1000.0 / timeDiff) else 0.0

        Log.d(TAG, "📊 현재 FPS: ${String.format("%.1f", currentFps)}")

        // 크리티컬 FPS 감지
        when {
            currentFps < SEVERE_FPS_THRESHOLD -> {
                consecutiveLowFpsCount++
                Log.w(TAG, "🚨 SEVERE FPS 감지: ${String.format("%.1f", currentFps)} fps (연속 ${consecutiveLowFpsCount}회)")

                if (consecutiveLowFpsCount >= 3) {
                    triggerCriticalAnalysis("SEVERE_FPS_DROP", currentFps)
                }
            }
            currentFps < CRITICAL_FPS_THRESHOLD -> {
                consecutiveLowFpsCount++
                Log.w(TAG, "⚠️ LOW FPS 감지: ${String.format("%.1f", currentFps)} fps (연속 ${consecutiveLowFpsCount}회)")

                if (consecutiveLowFpsCount >= 5) {
                    triggerCriticalAnalysis("CRITICAL_FPS_DROP", currentFps)
                }
            }
            else -> {
                consecutiveLowFpsCount = 0  // FPS가 정상이면 카운터 리셋
            }
        }
    }

    /**
     * 🚨 크리티컬 분석 트리거 (중복 호출 방지)
     */
    private fun triggerCriticalAnalysis(reason: String, fps: Double) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCriticalAnalysisTime < criticalAnalysisInterval) {
            Log.d(TAG, "크리티컬 분석 스킵 (최근 분석 완료): ${currentTime - lastCriticalAnalysisTime}ms 전")
            return
        }

        lastCriticalAnalysisTime = currentTime
        consecutiveLowFpsCount = 0  // 분석 후 카운터 리셋

        monitoringScope.launch {
            performCriticalAnalysis(reason, fps)
        }
    }

    /**
     * 🔍 크리티컬 상황 정밀 분석
     */
    private suspend fun performCriticalAnalysis(reason: String, fps: Double) = withContext(Dispatchers.IO) {
        try {
            Log.e(TAG, "🚨🚨🚨 크리티컬 분석 시작: $reason (FPS: ${String.format("%.1f", fps)})")

            val analysis = resourceMonitor.getCriticalPerformanceAnalysis()
            val timestamp = dateFormat.format(Date(analysis.timestamp))

            val criticalReport = buildCriticalAnalysisReport(reason, fps, timestamp, analysis)

            // 긴급 로그로 저장
            fileLogger.emergencyLog(TAG, criticalReport)

            Log.e(TAG, "🚨 크리티컬 분석 완료 및 로그 저장")

        } catch (e: Exception) {
            Log.e(TAG, "크리티컬 분석 실패: ${e.message}", e)
            fileLogger.emergencyLog(TAG, "크리티컬 분석 실패: $reason, FPS: $fps, 오류: ${e.message}")
        }
    }

    /**
     * 📋 크리티컬 분석 리포트 생성
     */
    private fun buildCriticalAnalysisReport(
        reason: String,
        fps: Double,
        timestamp: String,
        analysis: CriticalAnalysisInfo
    ): String {
        return buildString {
            appendLine("🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨")
            appendLine("🚨                         크리티컬 성능 분석 리포트                        🚨")
            appendLine("🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨")
            appendLine()
            appendLine("📅 발생 시간: $timestamp")
            appendLine("🎯 트리거 원인: $reason")
            appendLine("📊 현재 FPS: ${String.format("%.2f", fps)} fps")
            appendLine("⏱️ 시스템 타임스탬프: ${analysis.timestamp}")
            appendLine("⏱️ 모노토닉 타임스탬프: ${analysis.monoTimestamp}")
            appendLine()

            // 메모리 분석
            appendLine("🧠 ═══════════════════ 메모리 분석 ═══════════════════")
            with(analysis.appMemory) {
                appendLine("앱 힙 메모리:")
                appendLine("  ├─ 최대 할당: ${String.format("%.1f", maxHeapMB)} MB")
                appendLine("  ├─ 현재 사용: ${String.format("%.1f", usedHeapMB)} MB (${String.format("%.1f", heapUsagePercent)}%)")
                appendLine("  ├─ 가용 공간: ${String.format("%.1f", availableHeapMB)} MB")
                appendLine("  ├─ 여유 공간: ${String.format("%.1f", freeHeapMB)} MB")
                appendLine("  └─ 압박 수준: $memoryPressureLevel ${if (isLowMemory) "⚠️ 부족" else "✅ 정상"}")
                appendLine()
                appendLine("프로세스 메모리:")
                appendLine("  ├─ Dalvik (Java): ${String.format("%.1f", dalvikHeapMB)} MB")
                appendLine("  ├─ Native (C++): ${String.format("%.1f", nativeHeapMB)} MB")
                appendLine("  ├─ 기타: ${String.format("%.1f", otherMemoryMB)} MB")
                appendLine("  ├─ 전용 총합: ${String.format("%.1f", totalPrivateMB)} MB")
                appendLine("  └─ PSS 총합: ${String.format("%.1f", totalPssMB)} MB")
            }
            appendLine()

            with(analysis.systemMemory) {
                appendLine("시스템 메모리:")
                appendLine("  ├─ 전체 RAM: ${String.format("%.1f", totalSystemMB)} MB")
                appendLine("  ├─ 사용 가능: ${String.format("%.1f", availableSystemMB)} MB")
                appendLine("  ├─ 사용 중: ${String.format("%.1f", usedSystemMB)} MB")
                appendLine("  ├─ 사용률: ${String.format("%.1f", systemMemoryPressure)}%")
                appendLine("  ├─ 임계값: ${String.format("%.1f", systemThresholdMB)} MB")
                appendLine("  └─ 부족 상태: ${if (systemMemoryLow) "⚠️ 예" else "✅ 아니오"}")
            }
            appendLine()

            // CPU 분석
            appendLine("⚡ ═══════════════════ CPU 분석 ═══════════════════")
            with(analysis.cpu) {
                appendLine("CPU 상태:")
                appendLine("  ├─ 코어 수: ${coreCount}개")
                appendLine("  ├─ 사용률: ${String.format("%.1f", usagePercent)}%")
                appendLine("  ├─ 거버너: $governor")
                appendLine("  ├─ 로드 평균: ${loadAverage.joinToString(", ") { String.format("%.2f", it) }}")
                appendLine("  └─ 스로틀링: ${if (isThrottling) "⚠️ 예" else "✅ 아니오"}")
                appendLine()
                appendLine("CPU 주파수:")
                currentFreqs.forEachIndexed { index, freq ->
                    val maxFreq = maxFreqs.getOrNull(index) ?: -1L
                    val minFreq = minFreqs.getOrNull(index) ?: -1L
                    appendLine("  Core $index: ${if (freq > 0) "${freq/1000} MHz" else "N/A"} " +
                            "(범위: ${if (minFreq > 0) "${minFreq/1000}" else "N/A"}-${if (maxFreq > 0) "${maxFreq/1000}" else "N/A"} MHz)")
                }
            }
            appendLine()

            // 온도 분석
            appendLine("🌡️ ═══════════════════ 온도 분석 ═══════════════════")
            with(analysis.thermal) {
                appendLine("온도 상태:")
                appendLine("  ├─ CPU 온도: ${if (cpuTemperature > 0) "${String.format("%.1f", cpuTemperature)}°C" else "N/A"}")
                appendLine("  ├─ 배터리 온도: ${if (batteryTemperature > 0) "${String.format("%.1f", batteryTemperature)}°C" else "N/A"}")
                appendLine("  ├─ 주변 온도: ${if (ambientTemperature > 0) "${String.format("%.1f", ambientTemperature)}°C" else "N/A"}")
                appendLine("  ├─ 열 상태: $thermalState")
                appendLine("  └─ 스로틀링: ${if (thermalThrottling) "⚠️ 활성" else "✅ 비활성"}")
            }
            appendLine()

            // 배터리 분석
            appendLine("🔋 ═══════════════════ 배터리 분석 ═══════════════════")
            with(analysis.battery) {
                val batteryPercent = if (scale > 0) (level * 100 / scale) else -1
                appendLine("배터리 상태:")
                appendLine("  ├─ 충전량: ${if (batteryPercent >= 0) "$batteryPercent%" else "N/A"} ($level/$scale)")
                appendLine("  ├─ 전압: ${if (voltage > 0) "${voltage}mV" else "N/A"}")
                appendLine("  ├─ 온도: ${if (temperature > 0) "${String.format("%.1f", temperature)}°C" else "N/A"}")
                appendLine("  ├─ 상태: $status")
                appendLine("  ├─ 건강: $health")
                appendLine("  ├─ 충전 중: ${if (isCharging) "예" else "아니오"}")
                appendLine("  └─ 절전 모드: ${if (powerSaveModeEnabled) "⚠️ 활성" else "✅ 비활성"}")
            }
            appendLine()

            // 스토리지 분석
            appendLine("💾 ═══════════════════ 스토리지 분석 ═══════════════════")
            with(analysis.storage) {
                appendLine("저장소 상태:")
                appendLine("  ├─ 내부 전체: ${if (internalTotal > 0) "${String.format("%.1f", internalTotal/MB.toDouble())} MB" else "N/A"}")
                appendLine("  ├─ 내부 여유: ${if (internalFree > 0) "${String.format("%.1f", internalFree/MB.toDouble())} MB" else "N/A"}")
                appendLine("  ├─ 외부 전체: ${if (externalTotal > 0) "${String.format("%.1f", externalTotal/MB.toDouble())} MB" else "N/A"}")
                appendLine("  ├─ 외부 여유: ${if (externalFree > 0) "${String.format("%.1f", externalFree/MB.toDouble())} MB" else "N/A"}")
                appendLine("  └─ 캐시 크기: ${if (cacheSize > 0) "${String.format("%.1f", cacheSize/MB.toDouble())} MB" else "N/A"}")
            }
            appendLine()

            // GC 분석
            appendLine("🗑️ ═══════════════════ GC 분석 ═══════════════════")
            with(analysis.gc) {
                appendLine("가비지 컬렉션:")
                appendLine("  ├─ 총 GC 횟수: ${if (gcCount >= 0) "$gcCount 회" else "N/A"}")
                appendLine("  ├─ 총 GC 시간: ${if (gcTime >= 0) "$gcTime ms" else "N/A"}")
                appendLine("  ├─ 해제된 크기: ${if (gcFreedSize >= 0) "${String.format("%.1f", gcFreedSize/MB.toDouble())} MB" else "N/A"}")
                appendLine("  ├─ 해제된 객체: ${if (gcFreedCount >= 0) "$gcFreedCount 개" else "N/A"}")
                appendLine("  └─ 마지막 GC 원인: $lastGcReason")
            }
            appendLine()

            // 프로세스 분석
            appendLine("🔧 ═══════════════════ 프로세스 분석 ═══════════════════")
            with(analysis.processes) {
                val importanceText = when (importance) {
                    100 -> "FOREGROUND"
                    200 -> "VISIBLE"
                    300 -> "SERVICE"
                    400 -> "BACKGROUND"
                    else -> "UNKNOWN($importance)"
                }
                appendLine("프로세스 정보:")
                appendLine("  ├─ PID: $pid")
                appendLine("  ├─ UID: $uid")
                appendLine("  ├─ 프로세스명: $processName")
                appendLine("  ├─ 중요도: $importanceText")
                appendLine("  ├─ 총 프로세스: $totalProcessCount 개")
                appendLine("  ├─ 스레드 수: ${if (threadCount >= 0) "$threadCount 개" else "N/A"}")
                appendLine("  └─ 파일 디스크립터: ${if (fdCount >= 0) "$fdCount 개" else "N/A"}")
            }
            appendLine()

            // 그래픽스 분석
            appendLine("🎮 ═══════════════════ 그래픽스 분석 ═══════════════════")
            with(analysis.graphics) {
                appendLine("GPU 정보:")
                appendLine("  ├─ 렌더러: $renderer")
                appendLine("  ├─ 벤더: $vendor")
                appendLine("  ├─ 버전: $version")
                appendLine("  ├─ 확장 기능: ${extensions.size}개")
                if (extensions.isNotEmpty()) {
                    appendLine("  │   주요 확장: ${extensions.take(5).joinToString(", ")}")
                }
                appendLine("  └─ SurfaceFlinger: $surfaceFlinger")
            }
            appendLine()

            // 위험 요소 분석
            appendLine("⚠️ ═══════════════════ 위험 요소 분석 ═══════════════════")
            val risks = mutableListOf<String>()

            if (analysis.appMemory.heapUsagePercent > 90) {
                risks.add("🔴 앱 힙 메모리 위험: ${String.format("%.1f", analysis.appMemory.heapUsagePercent)}%")
            }
            if (analysis.appMemory.nativeHeapMB > 200) {
                risks.add("🔴 Native 메모리 과다: ${String.format("%.1f", analysis.appMemory.nativeHeapMB)} MB")
            }
            if (analysis.systemMemory.systemMemoryLow) {
                risks.add("🔴 시스템 메모리 부족")
            }
            if (analysis.cpu.usagePercent > 90) {
                risks.add("🔴 CPU 사용률 과다: ${String.format("%.1f", analysis.cpu.usagePercent)}%")
            }
            if (analysis.thermal.cpuTemperature > 80) {
                risks.add("🔴 CPU 과열: ${String.format("%.1f", analysis.thermal.cpuTemperature)}°C")
            }
            if (analysis.thermal.thermalThrottling) {
                risks.add("🔴 열 스로틀링 활성")
            }
            if (analysis.battery.powerSaveModeEnabled) {
                risks.add("🟡 절전 모드 활성")
            }
            if (fps < 5.0) {
                risks.add("🔴 극심한 FPS 드롭: ${String.format("%.1f", fps)} fps")
            }

            if (risks.isNotEmpty()) {
                risks.forEach { appendLine(it) }
            } else {
                appendLine("✅ 명확한 위험 요소 감지되지 않음")
            }
            appendLine()

            // 권장사항
            appendLine("💡 ═══════════════════ 권장사항 ═══════════════════")
            val recommendations = mutableListOf<String>()

            if (analysis.appMemory.heapUsagePercent > 85) {
                recommendations.add("• 앱 메모리 정리 (System.gc() 호출)")
                recommendations.add("• 불필요한 객체 참조 해제")
            }
            if (analysis.appMemory.nativeHeapMB > 100) {
                recommendations.add("• 비트맵 재활용 확인")
                recommendations.add("• Native 메모리 누수 점검")
            }
            if (analysis.thermal.cpuTemperature > 70 || analysis.thermal.thermalThrottling) {
                recommendations.add("• CPU 사용량 최적화")
                recommendations.add("• 백그라운드 작업 감소")
            }
            if (fps < 10) {
                recommendations.add("• UI 렌더링 최적화")
                recommendations.add("• 프레임 스킵 조정")
                recommendations.add("• 비트맵 풀 정리")
            }

            if (recommendations.isNotEmpty()) {
                recommendations.forEach { appendLine(it) }
            } else {
                recommendations.add("• 현재 시스템 상태 양호")
            }

            appendLine()
            appendLine("🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨")
            appendLine("🚨                     크리티컬 분석 리포트 종료                        🚨")
            appendLine("🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨")
        }
    }

    /**
     * FPS 모니터링 중지
     */
    private fun stopFpsMonitoring() {
        fpsMonitorCallback?.let {
            Choreographer.getInstance().removeFrameCallback(it)
        }
        fpsMonitorCallback = null
        Log.d(TAG, "🛑 FPS 모니터링 중지")
    }

    /**
     * 현재 FPS 반환
     */
    fun getCurrentFps(): Double = currentFps

    /**
     * 서비스 정리
     */
    fun cleanup() {
        stopMonitoring()
    }
}
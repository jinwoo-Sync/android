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
        private const val ROUTINE_LOG_INTERVAL_MS = 30_000L  // 30초 - 크리티컬 분석
        private const val COMPREHENSIVE_LOG_INTERVAL_MS = 30_000L  // 1분 - 종합 상태
        private const val FPS_MONITOR_INTERVAL_MS = 1_000L   // 1초
        private const val CRITICAL_FPS_THRESHOLD = 10.0
        private const val SEVERE_FPS_THRESHOLD = 5.0

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
    private val criticalAnalysisInterval = 5_000L

    // 🎯 앱 시작 시간 기록
    private val appStartTime = System.currentTimeMillis()

    /**
     * 모니터링 시작 - 30초 크리티컬 + 1분 종합
     */
    fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            fileLogger.i(TAG, "🚀 확장된 성능 모니터링 서비스 시작")
            fileLogger.i(TAG, "  📊 30초마다: 크리티컬 성능 분석")
            fileLogger.i(TAG, "  📋 1분마다: 종합 앱 상태 리포트")

            startCriticalAnalysisLogging()  // 30초 크리티컬 분석
            startComprehensiveStatusLogging()  // 1분 종합 상태
            startFpsMonitoring()
        }
    }

    /**
     * 모니터링 중지
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            fileLogger.i(TAG, "🛑 성능 모니터링 서비스 중지")
            stopFpsMonitoring()
            monitoringScope.cancel()
        }
    }

    /**
     * 📊 30초 주기 크리티컬 분석 (기존 유지)
     */
    private fun startCriticalAnalysisLogging() {
        monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                try {
                    logFullCriticalAnalysisReport()
                    delay(ROUTINE_LOG_INTERVAL_MS)
                } catch (e: Exception) {
                    fileLogger.e(TAG, "크리티컬 분석 로깅 오류: ${e.message}", e)
                    delay(5000)
                }
            }
        }
    }

    /**
     * 📋 1분 주기 종합 앱 상태 로깅 (새로 추가)
     */
    private fun startComprehensiveStatusLogging() {
        monitoringScope.launch {
            // 첫 번째 종합 리포트는 1분 후부터 시작
            delay(COMPREHENSIVE_LOG_INTERVAL_MS)

            while (isActive && isMonitoring.get()) {
                try {
                    logComprehensiveAppStatus()
                    delay(COMPREHENSIVE_LOG_INTERVAL_MS)
                } catch (e: Exception) {
                    fileLogger.e(TAG, "종합 상태 로깅 오류: ${e.message}", e)
                    delay(10000) // 오류 시 10초 대기
                }
            }
        }
    }

    /**
     * 🎯 30초마다 크리티컬 분석 리포트 (기존 메서드 유지)
     */
    private suspend fun logFullCriticalAnalysisReport() = withContext(Dispatchers.IO) {
        try {
            val analysis = resourceMonitor.getCriticalPerformanceAnalysis()
            val timestamp = dateFormat.format(Date(analysis.timestamp))

            val routineReport = buildCriticalAnalysisReport(
                reason = "ROUTINE_MONITORING",
                fps = currentFps,
                timestamp = timestamp,
                analysis = analysis
            )

            fileLogger.i(TAG, routineReport)
            Log.d(TAG, "📊 30초 정기 크리티컬 분석 리포트 저장 완료")

        } catch (e: Exception) {
            val errorMsg = "30초 정기 크리티컬 분석 리포트 저장 실패: ${e.message}"
            Log.e(TAG, errorMsg, e)
            fileLogger.e(TAG, errorMsg, e)
        }
    }

    /**
     * 🎯 1분마다 종합 앱 상태 리포트 (새로 추가)
     */
    private suspend fun logComprehensiveAppStatus() = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()
            val timestamp = dateFormat.format(Date(currentTime))
            val uptimeMs = currentTime - appStartTime

            val comprehensiveReport = buildComprehensiveAppStatusReport(timestamp, uptimeMs)

            // 종합 리포트는 comprehensiveLog로 구분해서 저장
            fileLogger.comprehensiveLog(TAG, comprehensiveReport)

            Log.d(TAG, "📋 1분 종합 앱 상태 리포트 저장 완료 (업타임: ${formatUptime(uptimeMs)})")

        } catch (e: Exception) {
            val errorMsg = "종합 앱 상태 리포트 생성 실패: ${e.message}"
            Log.e(TAG, errorMsg, e)
            fileLogger.e(TAG, errorMsg, e)
        }
    }

    /**
     * 📋 종합 앱 상태 리포트 빌더 (UI 포함)
     */
    private fun buildComprehensiveAppStatusReport(timestamp: String, uptimeMs: Long): String {
        return buildString {
            appendLine("🏠                   종합 앱 상태 리포트 (1분 주기)                   🏠")
            appendLine()
            appendLine("🕐 리포트 시간: $timestamp")
            appendLine("⏱️ 앱 업타임: ${formatUptime(uptimeMs)}")
            appendLine("📱 현재 FPS: ${String.format("%.2f", currentFps)} fps")
            appendLine("🔄 FPS 연속 저하 횟수: $consecutiveLowFpsCount")
            appendLine()

            // 1. 메모리 상태 요약
            val appMemory = resourceMonitor.getAppMemoryInfo()
            val systemMemory = resourceMonitor.getSystemMemoryInfo()

            appendLine("🧠 ═══════════════════ 메모리 상태 요약 ═══════════════════")
            appendLine("앱 힙 메모리:")
            appendLine("  ├─ 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
            appendLine("  ├─ 사용량: ${String.format("%.1f", appMemory.usedHeapMB)} MB")
            appendLine("  ├─ 최대량: ${String.format("%.1f", appMemory.maxHeapMB)} MB")
            appendLine("  ├─ 가용량: ${String.format("%.1f", appMemory.availableHeapMB)} MB")
            appendLine("  └─ 압박수준: ${appMemory.memoryPressureLevel}")
            appendLine()
            appendLine("네이티브 메모리:")
            appendLine("  ├─ Dalvik: ${String.format("%.1f", appMemory.dalvikHeapMB)} MB")
            appendLine("  ├─ Native: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
            appendLine("  └─ 기타: ${String.format("%.1f", appMemory.otherMemoryMB)} MB")
            appendLine()
            appendLine("시스템 메모리:")
            appendLine("  ├─ 전체: ${String.format("%.1f", systemMemory.totalSystemMB)} MB")
            appendLine("  ├─ 가용: ${String.format("%.1f", systemMemory.availableSystemMB)} MB")
            appendLine("  ├─ 사용률: ${String.format("%.1f", systemMemory.systemMemoryPressure)}%")
            appendLine("  └─ 부족상태: ${if (systemMemory.systemMemoryLow) "⚠️ 예" else "✅ 아니오"}")
            appendLine()

            // 2. CPU 및 온도 상태
            try {
                val analysis = resourceMonitor.getCriticalPerformanceAnalysis()
                val cpu = analysis.cpu
                val thermal = analysis.thermal

                appendLine("⚡ ═══════════════════ CPU & 온도 상태 ═══════════════════")
                appendLine("CPU 정보:")
                appendLine("  ├─ 사용률: ${String.format("%.1f", cpu.usagePercent)}%")
                appendLine("  ├─ 코어 수: ${cpu.coreCount}개")
                appendLine("  ├─ 거버너: ${cpu.governor}")
                appendLine("  └─ 스로틀링: ${if (cpu.isThrottling) "⚠️ 활성" else "✅ 비활성"}")
                appendLine()
                appendLine("온도 정보:")
                appendLine("  ├─ CPU: ${if (thermal.cpuTemperature > 0) "${String.format("%.1f", thermal.cpuTemperature)}°C" else "N/A"}")
                appendLine("  ├─ 배터리: ${if (thermal.batteryTemperature > 0) "${String.format("%.1f", thermal.batteryTemperature)}°C" else "N/A"}")
                appendLine("  ├─ 열 상태: ${thermal.thermalState}")
                appendLine("  └─ 열 스로틀링: ${if (thermal.thermalThrottling) "⚠️ 활성" else "✅ 비활성"}")
                appendLine()

                // 3. 배터리 상태
                val battery = analysis.battery
                val batteryPercent = if (battery.scale > 0) (battery.level * 100 / battery.scale) else -1

                appendLine("🔋 ═══════════════════ 배터리 상태 ═══════════════════")
                appendLine("배터리 정보:")
                appendLine("  ├─ 충전량: ${if (batteryPercent >= 0) "$batteryPercent%" else "N/A"}")
                appendLine("  ├─ 상태: ${battery.status}")
                appendLine("  ├─ 건강: ${battery.health}")
                appendLine("  ├─ 충전중: ${if (battery.isCharging) "예" else "아니오"}")
                appendLine("  └─ 절전모드: ${if (battery.powerSaveModeEnabled) "⚠️ 활성" else "✅ 비활성"}")
                appendLine()

                // 4. 프로세스 및 스레드 상태
                val processes = analysis.processes
                val threads = resourceMonitor.getThreadInfo()

                appendLine("🔧 ═══════════════════ 프로세스 & 스레드 ═══════════════════")
                appendLine("프로세스 정보:")
                appendLine("  ├─ PID: ${processes.pid}")
                appendLine("  ├─ 프로세스명: ${processes.processName}")
                appendLine("  ├─ 중요도: ${processes.importance}")
                appendLine("  └─ 총 프로세스: ${processes.totalProcessCount}개")
                appendLine()
                appendLine("스레드 정보:")
                appendLine("  ├─ 활성 스레드: ${threads.activeThreadCount}개")
                appendLine("  ├─ 현재 스레드: ${threads.currentThreadName}")
                appendLine("  └─ 파일 디스크립터: ${if (processes.fdCount >= 0) "${processes.fdCount}개" else "N/A"}")
                appendLine()

            } catch (e: Exception) {
                appendLine("⚠️ CPU/온도/배터리 정보 수집 실패: ${e.message}")
                appendLine()
            }

            // 5. UI 및 그래픽스 상태
            appendLine("🎮 ═══════════════════ UI & 그래픽스 상태 ═══════════════════")
            appendLine("FPS 성능:")
            appendLine("  ├─ 현재 FPS: ${String.format("%.2f", currentFps)} fps")
            appendLine("  ├─ 연속 저하 횟수: $consecutiveLowFpsCount")
            appendLine("  ├─ 심각 임계값: $SEVERE_FPS_THRESHOLD fps")
            appendLine("  └─ 경고 임계값: $CRITICAL_FPS_THRESHOLD fps")
            appendLine()

            // 🎯 비트맵 풀 상태 (UI에서 사용되는 것들 추정치)
            appendLine("비트맵 메모리 추정:")
            appendLine("  ├─ Native 힙 메모리: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
            appendLine("  ├─ 비트맵 예상 비중: ${String.format("%.1f", appMemory.nativeHeapMB * 0.7)} MB (추정)")
            appendLine("  └─ 메모리 압박으로 인한 비트맵 위험도: ${getBitmapRiskLevel(appMemory)}")
            appendLine()

            // 6. 파일 시스템 및 로깅 상태
            appendLine("📝 ═══════════════════ 로깅 & 파일 시스템 ═══════════════════")
            val loggerStatus = fileLogger.getLogFileStatus()
            val logFileCount = fileLogger.getAllLogFiles().size

            appendLine("로깅 시스템:")
            appendLine("  ├─ 현재 상태: $loggerStatus")
            appendLine("  ├─ 로그 파일 수: ${logFileCount}개")
            appendLine("  └─ 저장 경로: ${fileLogger.getLogDirectoryPath() ?: "Unknown"}")
            appendLine()

            // 7. 종합 위험도 평가
            appendLine("⚠️ ═══════════════════ 종합 위험도 평가 ═══════════════════")
            val riskFactors = evaluateOverallRisks(appMemory, systemMemory, currentFps, uptimeMs)
            if (riskFactors.isNotEmpty()) {
                riskFactors.forEach { appendLine("  $it") }
            } else {
                appendLine("  ✅ 현재 감지된 위험 요소 없음")
            }
            appendLine()

            // 8. 10-20분 버그 추적 정보
            appendLine("🕐 ═══════════════════ 장시간 실행 추적 정보 ═══════════════════")
            val uptimeMinutes = uptimeMs / 60000
            appendLine("실행 시간 분석:")
            appendLine("  ├─ 현재 업타임: ${uptimeMinutes}분")
            appendLine("  ├─ 10분 경과: ${if (uptimeMinutes >= 10) "✅ 경과" else "⏳ ${10 - uptimeMinutes}분 남음"}")
            appendLine("  ├─ 20분 경과: ${if (uptimeMinutes >= 20) "✅ 경과" else "⏳ ${20 - uptimeMinutes}분 남음"}")
            appendLine("  └─ 버그 발생 구간: ${if (uptimeMinutes in 10..20) "🚨 위험 구간" else "일반 구간"}")

            if (uptimeMinutes >= 10) {
                appendLine()
                appendLine("⚠️ 10분 이상 실행 - 장시간 실행 버그 모니터링 활성:")
                appendLine("  • 메모리 누수 가능성 추적 중")
                appendLine("  • UI 응답성 저하 모니터링 중")
                appendLine("  • 네이티브 메모리 증가 추세 관찰 중")
            }

            appendLine()
            appendLine("🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠")
            appendLine("🏠                      종합 앱 상태 리포트 종료                      🏠")
            appendLine("🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠🏠")
        }
    }

    /**
     * 🎯 비트맵 위험도 평가 (실제 풀 없이도 추정)
     */
    private fun getBitmapRiskLevel(appMemory: AppMemoryInfo): String {
        val nativeHeapMB = appMemory.nativeHeapMB
        val heapUsagePercent = appMemory.heapUsagePercent

        return when {
            nativeHeapMB > 200 || heapUsagePercent > 90 -> "🔴 높음"
            nativeHeapMB > 150 || heapUsagePercent > 80 -> "🟡 중간"
            nativeHeapMB > 100 || heapUsagePercent > 70 -> "🟠 낮음"
            else -> "🟢 안전"
        }
    }

    /**
     * 🎯 종합 위험도 평가
     */
    private fun evaluateOverallRisks(
        appMemory: AppMemoryInfo,
        systemMemory: SystemMemoryInfo,
        fps: Double,
        uptimeMs: Long
    ): List<String> {
        val risks = mutableListOf<String>()
        val uptimeMinutes = uptimeMs / 60000

        // 메모리 위험도
        if (appMemory.heapUsagePercent > 85) {
            risks.add("🔴 힙 메모리 위험: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
        }
        if (appMemory.nativeHeapMB > 200) {
            risks.add("🔴 Native 메모리 과다: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
        }
        if (systemMemory.systemMemoryLow) {
            risks.add("🔴 시스템 메모리 부족")
        }

        // FPS 위험도
        if (fps < SEVERE_FPS_THRESHOLD) {
            risks.add("🔴 심각한 FPS 저하: ${String.format("%.1f", fps)} fps")
        } else if (fps < CRITICAL_FPS_THRESHOLD) {
            risks.add("🟡 FPS 저하: ${String.format("%.1f", fps)} fps")
        }

        // 장시간 실행 위험도
        if (uptimeMinutes >= 15) {
            risks.add("🟡 장시간 실행: ${uptimeMinutes}분 (메모리 누수 주의)")
        }
        if (uptimeMinutes in 10..20) {
            risks.add("🚨 버그 발생 가능 구간 (10-20분)")
        }

        // 연속적인 성능 저하
        if (consecutiveLowFpsCount >= 5) {
            risks.add("🟡 연속적인 성능 저하: ${consecutiveLowFpsCount}회")
        }

        return risks
    }

    /**
     * 🎯 업타임 포맷팅
     */
    private fun formatUptime(uptimeMs: Long): String {
        val seconds = uptimeMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}시간 ${minutes % 60}분 ${seconds % 60}초"
            minutes > 0 -> "${minutes}분 ${seconds % 60}초"
            else -> "${seconds}초"
        }
    }

    // ... 나머지 기존 메서드들 (FPS 모니터링, 크리티컬 트리거 등) 모두 그대로 유지 ...

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

                if (fpsMonitorCallback != null && isMonitoring.get()) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        Choreographer.getInstance().postFrameCallback(fpsMonitorCallback!!)
        fileLogger.d(TAG, "🎯 FPS 모니터링 시작")
    }

    private fun calculateAndCheckFps(currentTime: Long) {
        val frames = frameCount.get()
        val timeDiff = currentTime - lastFpsCalculationTime
        currentFps = if (timeDiff > 0) (frames * 1000.0 / timeDiff) else 0.0

        Log.d(TAG, "📊 현재 FPS: ${String.format("%.1f", currentFps)}")

        when {
            currentFps < SEVERE_FPS_THRESHOLD -> {
                consecutiveLowFpsCount++
                val warningMsg = "🚨 SEVERE FPS 감지: ${String.format("%.1f", currentFps)} fps (연속 ${consecutiveLowFpsCount}회)"
                Log.w(TAG, warningMsg)
                fileLogger.w(TAG, warningMsg)

                if (consecutiveLowFpsCount >= 3) {
                    triggerCriticalAnalysis("SEVERE_FPS_DROP", currentFps)
                }
            }
            currentFps < CRITICAL_FPS_THRESHOLD -> {
                consecutiveLowFpsCount++
                val warningMsg = "⚠️ LOW FPS 감지: ${String.format("%.1f", currentFps)} fps (연속 ${consecutiveLowFpsCount}회)"
                Log.w(TAG, warningMsg)
                fileLogger.w(TAG, warningMsg)

                if (consecutiveLowFpsCount >= 5) {
                    triggerCriticalAnalysis("CRITICAL_FPS_DROP", currentFps)
                }
            }
            else -> {
                consecutiveLowFpsCount = 0
            }
        }
    }

    private fun triggerCriticalAnalysis(reason: String, fps: Double) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCriticalAnalysisTime < criticalAnalysisInterval) {
            Log.d(TAG, "크리티컬 분석 스킵 (최근 분석 완료): ${currentTime - lastCriticalAnalysisTime}ms 전")
            return
        }

        lastCriticalAnalysisTime = currentTime
        consecutiveLowFpsCount = 0

        monitoringScope.launch {
            performEmergencyCriticalAnalysis(reason, fps)
        }
    }

    private suspend fun performEmergencyCriticalAnalysis(reason: String, fps: Double) = withContext(Dispatchers.IO) {
        try {
            val analysisStartMsg = "🚨🚨🚨 긴급 크리티컬 분석 시작: $reason (FPS: ${String.format("%.1f", fps)})"
            Log.e(TAG, analysisStartMsg)
            fileLogger.e(TAG, analysisStartMsg)

            val analysis = resourceMonitor.getCriticalPerformanceAnalysis()
            val timestamp = dateFormat.format(Date(analysis.timestamp))

            val emergencyReport = buildCriticalAnalysisReport(reason, fps, timestamp, analysis)
            fileLogger.emergencyLog(TAG, emergencyReport)

            val completionMsg = "🚨 긴급 크리티컬 분석 완료 및 로그 저장"
            Log.e(TAG, completionMsg)
            fileLogger.e(TAG, completionMsg)

        } catch (e: Exception) {
            val errorMsg = "긴급 크리티컬 분석 실패: ${e.message}"
            Log.e(TAG, errorMsg, e)
            fileLogger.emergencyLog(TAG, "긴급 크리티컬 분석 실패: $reason, FPS: $fps, 오류: ${e.message}")
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
            appendLine("🚨                         크리티컬 성능 분석 리포트                        🚨")
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
        fileLogger.d(TAG, "🛑 FPS 모니터링 중지")
    }

    fun getCurrentFps(): Double = currentFps

    fun cleanup() {
        stopMonitoring()
    }
}
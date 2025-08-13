package com.example.myapplication.Logsystem

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.example.myapplication.perfetto.PerfettoManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AdvancedPerformanceMonitor private constructor(
    private val context: Context,
    private val fileLogger: FileLogger,
    private val resourceMonitor: ResourceMonitor,
    private val perfettoManager: PerfettoManager
) {
    companion object {
        private const val TAG = "AdvancedPerfMonitor"

        @Volatile
        private var INSTANCE: AdvancedPerformanceMonitor? = null

        fun getInstance(context: Context): AdvancedPerformanceMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdvancedPerformanceMonitor(
                    context.applicationContext,
                    FileLogger.getInstance(context),
                    ResourceMonitor.getInstance(context),
                    PerfettoManager.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }

    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)

    // FPS 모니터링
    private var fpsMonitor: Choreographer.FrameCallback? = null
    private val frameCount = AtomicLong(0)
    private var lastFrameTime = 0L
    private val fpsHistory = ConcurrentLinkedQueue<FpsData>()

    // ANR 감지
    private val mainHandler = Handler(Looper.getMainLooper())
    private var anrWatchdog: AnrWatchdog? = null

    // 메모리 누수 감지
    private var lastGcCount = 0L
    private var memoryLeakDetector: MemoryLeakDetector? = null

    data class FpsData(
        val timestamp: Long,
        val fps: Double,
        val frameDrops: Int
    )

    data class PerformanceSnapshot(
        val timestamp: Long,
        val fps: Double,
        val memoryUsageMB: Double,
        val cpuUsagePercent: Double,
        val gcCount: Long,
        val threadCount: Int,
        val isMainThreadBlocked: Boolean
    )

    /**
     * 종합 성능 모니터링 시작
     */
    fun startComprehensiveMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            fileLogger.i(TAG, "종합 성능 모니터링 시작")

            startFpsMonitoring()
            startAnrDetection()
            startMemoryLeakDetection()
            startCpuMonitoring()
            startPerformanceSnapshots()

            // Perfetto 추적도 함께 시작
            perfettoManager.startPerfettoTracing("ComprehensiveMonitoring")
        }
    }

    /**
     * FPS 모니터링 시작
     */
    private fun startFpsMonitoring() {
        // 메인 스레드에서 실행되도록 보장
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                initializeChoreographer()
            }
            return
        }
        initializeChoreographer()
    }

    private fun initializeChoreographer() {
        try {
            lastFrameTime = System.currentTimeMillis()
            frameCount.set(0)

            fpsMonitor = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    try {
                        val currentTime = System.currentTimeMillis()
                        val frameNum = frameCount.incrementAndGet()

                        // 1초마다 FPS 계산
                        if (currentTime - lastFrameTime >= 1000) {
                            val fps = frameNum * 1000.0 / (currentTime - lastFrameTime)
                            val frameDrops = (60 - fps).toInt().coerceAtLeast(0)

                            val fpsData = FpsData(currentTime, fps, frameDrops)
                            fpsHistory.offer(fpsData)

                            // 히스토리 크기 제한
                            while (fpsHistory.size > 60) {
                                fpsHistory.poll()
                            }

                            // FPS 저하 감지
                            if (fps < 30) {
                                fileLogger.w(TAG, "FPS 저하 감지: ${String.format("%.1f", fps)}fps, 드롭: ${frameDrops}프레임")
                                triggerPerformanceAnalysis("LOW_FPS")
                            }

                            lastFrameTime = currentTime
                            frameCount.set(0)
                        }

                        // 다음 프레임 등록
                        if (isMonitoring.get()) {
                            Choreographer.getInstance().postFrameCallback(this)
                        }

                    } catch (e: Exception) {
                        fileLogger.e(TAG, "FPS 모니터링 오류: ${e.message}", e)
                    }
                }
            }

            Choreographer.getInstance().postFrameCallback(fpsMonitor!!)
            fileLogger.i(TAG, "FPS 모니터링 시작")
        } catch (e: IllegalStateException) {
            fileLogger.e(TAG, "Choreographer 초기화 실패", e)
        }
    }

    /**
     * ANR 감지 시작
     */
    private fun startAnrDetection() {
        anrWatchdog = AnrWatchdog()
        anrWatchdog!!.start()
        fileLogger.i(TAG, "ANR 감지 시작")
    }

    /**
     * ANR 감지 클래스
     */
    private inner class AnrWatchdog : Thread("AnrWatchdog") {
        private val CHECK_INTERVAL = 5000L // 5초
        private var lastTick = 0L

        override fun run() {
            while (isMonitoring.get()) {
                try {
                    lastTick = System.currentTimeMillis()

                    mainHandler.post {
                        lastTick = System.currentTimeMillis()
                    }

                    Thread.sleep(CHECK_INTERVAL)

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTick > CHECK_INTERVAL + 2000) {
                        // ANR 의심 상황
                        fileLogger.e(TAG, "ANR 의심 감지: 메인 스레드 응답 없음 ${currentTime - lastTick}ms")
                        triggerPerformanceAnalysis("POTENTIAL_ANR")
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    fileLogger.e(TAG, "ANR 감지 오류: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 메모리 누수 감지 시작
     */
    private fun startMemoryLeakDetection() {
        memoryLeakDetector = MemoryLeakDetector()
        memoryLeakDetector!!.start()
        fileLogger.i(TAG, "메모리 누수 감지 시작")
    }

    /**
     * 메모리 누수 감지 클래스
     */
    private inner class MemoryLeakDetector : Thread("MemoryLeakDetector") {
        override fun run() {
            while (isMonitoring.get()) {
                try {
                    val currentGcCount = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L

                    if (lastGcCount > 0 && currentGcCount > lastGcCount + 10) {
                        // 빈번한 GC 감지
                        fileLogger.w(TAG, "빈번한 GC 감지: ${currentGcCount - lastGcCount}회")

                        val memoryInfo = resourceMonitor.getAppMemoryInfo()
                        if (memoryInfo.heapUsagePercent > 80) {
                            fileLogger.e(TAG, "메모리 누수 의심: 높은 힙 사용률 ${String.format("%.1f", memoryInfo.heapUsagePercent)}%")
                            triggerPerformanceAnalysis("MEMORY_LEAK")
                        }
                    }

                    lastGcCount = currentGcCount
                    Thread.sleep(10000) // 10초마다 체크

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    fileLogger.e(TAG, "메모리 누수 감지 오류: ${e.message}", e)
                }
            }
        }
    }

    /**
     * CPU 모니터링 시작
     */
    private fun startCpuMonitoring() {
        monitoringScope.launch {
            while (isMonitoring.get()) {
                try {
                    val cpuInfo = resourceMonitor.getCpuInfo()

                    if (cpuInfo.usagePercent > 80) {
                        fileLogger.w(TAG, "높은 CPU 사용률: ${String.format("%.1f", cpuInfo.usagePercent)}%")
                        triggerPerformanceAnalysis("HIGH_CPU")
                    }

                    delay(5000) // 5초마다 체크

                } catch (e: Exception) {
                    fileLogger.e(TAG, "CPU 모니터링 오류: ${e.message}", e)
                    delay(10000)
                }
            }
        }
    }

    /**
     * 성능 스냅샷 수집
     */
    private fun startPerformanceSnapshots() {
        monitoringScope.launch {
            while (isMonitoring.get()) {
                try {
                    val snapshot = createPerformanceSnapshot()
                    analyzeSnapshot(snapshot)

                    delay(1000) // 1초마다 스냅샷

                } catch (e: Exception) {
                    fileLogger.e(TAG, "성능 스냅샷 오류: ${e.message}", e)
                    delay(5000)
                }
            }
        }
    }

    /**
     * 성능 스냅샷 생성
     */
    private fun createPerformanceSnapshot(): PerformanceSnapshot {
        val timestamp = System.currentTimeMillis()
        val memoryInfo = resourceMonitor.getAppMemoryInfo()
        val cpuInfo = resourceMonitor.getCpuInfo()
        val threadInfo = resourceMonitor.getThreadInfo()
        val currentFps = fpsHistory.lastOrNull()?.fps ?: 0.0
        val gcCount = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L

        return PerformanceSnapshot(
            timestamp = timestamp,
            fps = currentFps,
            memoryUsageMB = memoryInfo.usedHeapMB,
            cpuUsagePercent = cpuInfo.usagePercent,
            gcCount = gcCount,
            threadCount = threadInfo.activeThreadCount,
            isMainThreadBlocked = isMainThreadBlocked()
        )
    }

    /**
     * 메인 스레드 블록 상태 확인
     */
    private fun isMainThreadBlocked(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false // 현재 메인 스레드에서 실행 중
        }

        var isBlocked = true
        val startTime = System.currentTimeMillis()

        mainHandler.post {
            isBlocked = false
        }

        // 100ms 대기
        Thread.sleep(100)

        return isBlocked && (System.currentTimeMillis() - startTime > 100)
    }

    /**
     * 스냅샷 분석
     */
    private fun analyzeSnapshot(snapshot: PerformanceSnapshot) {
        // 성능 문제 패턴 감지
        when {
            snapshot.fps < 20 && snapshot.memoryUsageMB > 200 -> {
                fileLogger.e(TAG, "심각한 성능 저하: FPS=${String.format("%.1f", snapshot.fps)}, 메모리=${String.format("%.1f", snapshot.memoryUsageMB)}MB")
                triggerPerformanceAnalysis("CRITICAL_PERFORMANCE")
            }
            snapshot.isMainThreadBlocked -> {
                fileLogger.w(TAG, "메인 스레드 블록 감지")
                triggerPerformanceAnalysis("MAIN_THREAD_BLOCKED")
            }
            snapshot.threadCount > 50 -> {
                fileLogger.w(TAG, "과도한 스레드: ${snapshot.threadCount}개")
                triggerPerformanceAnalysis("TOO_MANY_THREADS")
            }
        }
    }

    /**
     * 성능 문제 발생 시 상세 분석 트리거 / 백그라운드 작업 최적화
     */
    private fun triggerPerformanceAnalysis(reason: String) {
        monitoringScope.launch(Dispatchers.IO) {
            try {
                //fileLogger.e(TAG, "성능 분석 트리거: $reason")

                // 무거운 작업들을 IO 디스패처로 이동
                resourceMonitor.logAppResourceStatus(TAG, "성능문제_$reason")

                if (reason.contains("MAIN_THREAD") || reason.contains("ANR")) {
                    dumpMainThreadStackTrace()
                }

                if (reason.contains("MEMORY")) {
                    requestHeapDump()
                }

                // UI 업데이트는 메인 스레드에서
                withContext(Dispatchers.Main) {
                    // UI 관련 업데이트가 필요한 경우 여기서 처리
                }
            } catch (e: Exception) {
                fileLogger.e(TAG, "성능 분석 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 메인 스레드 스택 트레이스 덤프
     */
    private fun dumpMainThreadStackTrace() {
        try {
            val mainThread = Looper.getMainLooper().thread
            val stackTrace = mainThread.stackTrace

            fileLogger.e(TAG, "메인 스레드 스택 트레이스:")
            stackTrace.forEach { element ->
                fileLogger.e(TAG, "    at $element")
            }
        } catch (e: Exception) {
            fileLogger.e(TAG, "스택 트레이스 덤프 실패: ${e.message}", e)
        }
    }

    /**
     * 힙 덤프 요청
     */
    private fun requestHeapDump() {
        try {
            val heapDumpFile = java.io.File(context.getExternalFilesDir(null), "heap_dump_${System.currentTimeMillis()}.hprof")
            android.os.Debug.dumpHprofData(heapDumpFile.absolutePath)
            fileLogger.i(TAG, "힙 덤프 생성: ${heapDumpFile.absolutePath}")
        } catch (e: Exception) {
            fileLogger.e(TAG, "힙 덤프 생성 실패: ${e.message}", e)
        }
    }

    /**
     * 현재 FPS 반환
     */
    fun getCurrentFps(): Double {
        return fpsHistory.lastOrNull()?.fps ?: 0.0
    }

    /**
     * 성능 보고서 생성
     */
    fun generatePerformanceReport(): String {
        return buildString {
            appendLine("=== 종합 성능 보고서 ===")
            appendLine("보고서 생성 시간: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                Date()
            )}")
            appendLine()

            // FPS 통계
            val fpsData = fpsHistory.toList()
            if (fpsData.isNotEmpty()) {
                val avgFps = fpsData.map { it.fps }.average()
                val minFps = fpsData.minByOrNull { it.fps }?.fps ?: 0.0
                val totalFrameDrops = fpsData.sumOf { it.frameDrops }

                appendLine("FPS 통계:")
                appendLine("  평균 FPS: ${String.format("%.1f", avgFps)}")
                appendLine("  최저 FPS: ${String.format("%.1f", minFps)}")
                appendLine("  총 프레임 드롭: ${totalFrameDrops}개")
                appendLine()
            }

            // 메모리 상태
            val memoryInfo = resourceMonitor.getAppMemoryInfo()
            appendLine("메모리 상태:")
            appendLine("  힙 사용률: ${String.format("%.1f", memoryInfo.heapUsagePercent)}%")
            appendLine("  사용 가능 힙: ${String.format("%.1f", memoryInfo.availableHeapMB)} MB")
            appendLine("  Native 메모리: ${String.format("%.1f", memoryInfo.nativeHeapMB)} MB")
            appendLine()

            // CPU 상태
            val cpuInfo = resourceMonitor.getCpuInfo()
            appendLine("CPU 상태:")
            appendLine("  사용률: ${String.format("%.1f", cpuInfo.usagePercent)}%")
            appendLine("  코어 수: ${cpuInfo.coreCount}개")
            appendLine()

            // 스레드 상태
            val threadInfo = resourceMonitor.getThreadInfo()
            appendLine("스레드 상태:")
            appendLine("  활성 스레드: ${threadInfo.activeThreadCount}개")
            appendLine("  현재 스레드: ${threadInfo.currentThreadName}")
        }
    }

    /**
     * 모니터링 중지
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            fileLogger.i(TAG, "성능 모니터링 중지")

            // FPS 모니터링 중지
            fpsMonitor?.let {
                Choreographer.getInstance().removeFrameCallback(it)
            }

            // ANR 감지 중지
            anrWatchdog?.interrupt()

            // 메모리 누수 감지 중지
            memoryLeakDetector?.interrupt()

            // Perfetto 추적 중지
            perfettoManager.stopPerfettoTracing()

            // 최종 보고서 생성
            val report = generatePerformanceReport()
            fileLogger.i(TAG, "최종 성능 보고서:\n$report")
        }
    }
}
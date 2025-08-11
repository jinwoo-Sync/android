package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.myapplication.data.repository.HomeRepository
import com.example.myapplication.data.sensor.SensorCollector
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.utils.BitmapPoolManager
import com.example.myapplication.Logsystem.CrashHandler
import com.example.myapplication.Logsystem.FileLogger
import com.example.myapplication.utils.HealthLevel
import com.example.myapplication.utils.PoolHealthStatus
import com.example.myapplication.Logsystem.ResourceMonitor
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import android.view.Choreographer
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.Logsystem.AdvancedPerformanceMonitor
import com.example.myapplication.Logsystem.GpuMemoryMonitor
import com.example.myapplication.Logsystem.LeakCanaryIntegration
import com.example.myapplication.Logsystem.PerfettoTracer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private val PERMISSION_REQUEST_CODE = 100
    private lateinit var binding: ActivityMainBinding
    lateinit var sensorCollector: SensorCollector
    lateinit var homeRepository: HomeRepository
    private lateinit var bitmapPoolManager: BitmapPoolManager

    //  모니터링 시스템
    private lateinit var fileLogger: FileLogger
    private lateinit var resourceMonitor: ResourceMonitor
    private lateinit var perfettoTracer: PerfettoTracer
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // FPS 모니터링 강화
    private var fpsMonitor: Choreographer.FrameCallback? = null
    private var lastFpsTime = 0L
    private var frameCount = 0
    private val currentFps = AtomicLong(60)
    private val isLowFpsDetected = AtomicBoolean(false)
    private var consecutiveLowFpsCount = 0
    private val FPS_CRITICAL_THRESHOLD = 5 // FPS 5 이하 긴급 처리
    private val FPS_WARNING_THRESHOLD = 10 // FPS 10 이하 경고
    private var isEmergencyRecoveryActive = false
    
    // FPS 모니터링을 위한 캐싱 변수
    private val _cachedFps = MutableStateFlow(60.0)
    private val cachedFps: StateFlow<Double> = _cachedFps
    private var lastFpsUpdateTime = 0L

    //  BitmapPool 모니터링 추가
    private var lastPoolHealthCheck = 0L
    private var previousPoolState: String = ""
    private val isPoolCritical = AtomicBoolean(false)

    //  .mp4 저장 상태 추적
    private var isMp4Recording = false
    private var mp4RecordingStartTime = 0L
    private var mp4RecordingFpsDrops = 0

    //  상태 추적
    private val appStartTime = System.currentTimeMillis()
    private var sessionId: String = ""

    private var isCameraPermissionGranted = false
    private var isLocationPermissionGranted = false
    private var isBackgroundLocationPermissionGranted = false

    private var batteryOptimizationDialog: AlertDialog? = null
    private var locationServiceDialog: AlertDialog? = null

    private val emergencyShutdownPrevention = AtomicBoolean(false)

    // GPU 모니터링 추가
    private lateinit var gpuMemoryMonitor: GpuMemoryMonitor

    // Perfetto 자동 추적 관련
    private var perfettoAutoTraceJob: Job? = null
    private val PERFETTO_TRACE_DURATION = 60_000L // 60초

    // 응급 복구 변수
    private var emergencyRecoveryCount = 0
    private var lastRecoveryTime = 0L
    private var isTextUpdatesPaused = false

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    //  Fragment가 필요로 하는 기본 객체들 먼저 초기화
    initializeBasicObjects()
    
    // UI 설정
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setupNavigation()
    
    // 나머지 무거운 초기화는 백그라운드에서
    lifecycleScope.launch(Dispatchers.IO) {
        initializeHeavySystemsInBackground()
    }

    // 하드웨어 가속 강제 활성화
    window.setFlags(
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    )
}

//  Fragment가 즉시 필요로 하는 객체들만 동기적으로 초기화
private fun initializeBasicObjects() {
    try {
        val dataSynchronizer = DataSynchronizer()
        bitmapPoolManager = BitmapPoolManager.getInstance(this)
        sensorCollector = SensorCollector(this, bitmapPoolManager)
        sensorCollector.setDataSynchronizer(dataSynchronizer)
        homeRepository = HomeRepository(this, sensorCollector, dataSynchronizer, bitmapPoolManager)
        
        Log.d("MainActivity", " 기본 객체 초기화 완료")
    } catch (e: Exception) {
        Log.e("MainActivity", "기본 객체 초기화 실패: ${e.message}", e)
    }
}

//  무거운 모니터링 시스템들은 백그라운드에서
private suspend fun initializeHeavySystemsInBackground() = withContext(Dispatchers.IO) {
    try {
        initializeMonitoringSystem()
        
        withContext(Dispatchers.Main) {
            startComprehensiveMonitoring()
            startPerfettoAutoTracing()
            checkPermissions()
            requestBatteryOptimizationDisable()
            checkLocationServiceEnabled()
        }
        
        Log.d("MainActivity", " 백그라운드 초기화 완료")
    } catch (e: Exception) {
        Log.e("MainActivity", "백그라운드 초기화 실패: ${e.message}", e)
    }
}

    /**
     *  모니터링 시스템 초기화
     */
    private fun initializeMonitoringSystem() {
        try {
            sessionId = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())

            fileLogger = FileLogger.getInstance(this)
            resourceMonitor = ResourceMonitor.getInstance(this)
            gpuMemoryMonitor = GpuMemoryMonitor.getInstance(this)
            perfettoTracer = PerfettoTracer.getInstance(this)

            CrashHandler.setup(this)

            fileLogger.i("MainActivity", " 완전한 모니터링 시스템 초기화 완료 - 세션: $sessionId")
            fileLogger.i("MainActivity", " 로그 저장 위치: ${fileLogger.getLogDirectoryPath()}")

        } catch (e: Exception) {
            Log.e("MainActivity", " 모니터링 시스템 초기화 실패: ${e.message}", e)
        }
    }

    /**
     * Perfetto 자동 추적 시작
     */
    private fun startPerfettoAutoTracing() {
        perfettoAutoTraceJob = lifecycleScope.launch {
            try {
                // 앱 시작 5초 후 Perfetto 추적 시작
                delay(5_000)

                val traceFilePath = perfettoTracer.startPerfettoTrace("AutoTrace_$sessionId")
                fileLogger.i("MainActivity", "🎯 자동 Perfetto 추적 시작: $traceFilePath")

                // 지정된 시간 후 자동 중지
                delay(PERFETTO_TRACE_DURATION)
                stopPerfettoTracing()

                // 30초 간격으로 새로운 추적 시작 (지속적 모니터링)
                startContinuousPerfettoTracing()

            } catch (e: Exception) {
                fileLogger.e("MainActivity", "자동 Perfetto 추적 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 지속적 Perfetto 추적 (30초 간격)
     */
    private fun startContinuousPerfettoTracing() {
        lifecycleScope.launch {
            var traceCount = 1

            while (isActive) {
                try {
                    delay(120_000) // 30초 → 2분으로 변경

                    if (!perfettoTracer.isTracing()) {
                        val traceFilePath = perfettoTracer.startPerfettoTrace("ContinuousTrace_${sessionId}_${traceCount}")
                        fileLogger.i("MainActivity", "🔄 지속적 Perfetto 추적 시작 #${traceCount}: $traceFilePath")

                        delay(30_000) // 60초 → 30초로 단축
                        stopPerfettoTracing()

                        traceCount++
                    }

                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "지속적 Perfetto 추적 오류: ${e.message}", e)
                    delay(300_000) // 에러 시 5분 대기
                }
            }
        }
    }

    /**
     * Perfetto 추적 중지
     */
    private fun stopPerfettoTracing() {
        lifecycleScope.launch {
            try {
                val finalTracePath = perfettoTracer.stopPerfettoTrace()
                fileLogger.i("MainActivity", "🎯 Perfetto 추적 완료: $finalTracePath")
                fileLogger.i("MainActivity", "📊 수집된 이벤트: ${perfettoTracer.getEventCount()}개")

                // 모든 추적 파일 경로 출력
                val allTraceFiles = perfettoTracer.getAllTraceFiles()
                fileLogger.i("MainActivity", "📁 Documents/save/에 저장된 추적 파일들:")
                allTraceFiles.forEach { filePath ->
                    fileLogger.i("MainActivity", "  - $filePath")
                }

            } catch (e: Exception) {
                fileLogger.e("MainActivity", "Perfetto 추적 중지 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 성능 문제 발생 시 즉시 힙 덤프 생성
     */
    private fun generateEmergencyHeapDump(reason: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val heapDumpPath = perfettoTracer.generateHeapDump("Emergency_$reason")
                if (heapDumpPath != null) {
                    fileLogger.e("MainActivity", "🚨 응급 힙 덤프 생성: $heapDumpPath")
                } else {
                    fileLogger.e("MainActivity", "🚨 응급 힙 덤프 생성 실패")
                }
            } catch (e: Exception) {
                fileLogger.e("MainActivity", "응급 힙 덤프 생성 오류: ${e.message}", e)
            }
        }
    }

    /**
     * 완전한 모니터링 시작 (BitmapPool 포함)
     */
    private fun startComprehensiveMonitoring() {
        // UI 스레드 부하 분산
        lifecycleScope.launch(Dispatchers.IO) {
            delay(1000) // 1초 후 시작

            startPeriodicMonitoring()
            startBitmapPoolMonitoring()
            startMp4RecordingMonitoring()
            startGpuMemoryMonitoring()

            // UI 관련은 메인 스레드에서
            withContext(Dispatchers.Main) {
                startRealTimeFpsMonitoring()
            }

            // 나머지는 다시 백그라운드
            withContext(Dispatchers.IO) {
                startAdvancedPerformanceMonitoring()
                startLeakCanaryIntegration()
                logInitialSystemState()
            }
        }
    }

    /**
     * 통합 성능 모니터링 시작
     */
    private fun startAdvancedPerformanceMonitoring() {
        try {
            val advancedMonitor = AdvancedPerformanceMonitor.getInstance(this)
            advancedMonitor.startComprehensiveMonitoring()
            fileLogger.i("MainActivity", "✅ 통합 성능 모니터링 시작")
        } catch (e: Exception) {
            fileLogger.e("MainActivity", "통합 성능 모니터링 시작 실패: ${e.message}", e)
        }
    }

    /**
     * LeakCanary 통합 시작
     */
    private fun startLeakCanaryIntegration() {
        try {
            val leakCanaryIntegration = LeakCanaryIntegration.getInstance(this)

            // BitmapPoolManager 감시
            leakCanaryIntegration.watchObject(bitmapPoolManager, "BitmapPoolManager")

            // SensorCollector 감시
            leakCanaryIntegration.watchObject(sensorCollector, "SensorCollector")

            // HomeRepository 감시
            leakCanaryIntegration.watchObject(homeRepository, "HomeRepository")

            // PerfettoTracer 감시
            leakCanaryIntegration.watchObject(perfettoTracer, "PerfettoTracer")

            fileLogger.i("MainActivity", "✅ LeakCanary 통합 완료")
        } catch (e: Exception) {
            fileLogger.e("MainActivity", "LeakCanary 통합 실패: ${e.message}", e)
        }
    }

    /**
     * GPU 메모리 모니터링 시작
     */
    private fun startGpuMemoryMonitoring() {
        try {
            gpuMemoryMonitor.startGpuMemoryMonitoring()
            fileLogger.i("MainActivity", " GPU 메모리 모니터링 시작")
        } catch (e: Exception) {
            fileLogger.e("MainActivity", "GPU 메모리 모니터링 시작 실패: ${e.message}", e)
        }
    }

    /**
     *  BitmapPool 전용 모니터링 (5초마다)
     */
    private fun startBitmapPoolMonitoring() {
        monitoringScope.launch {
            fileLogger.i("MainActivity", " 최적화된 BitmapPool 모니터링 시작 (10초 간격)")

            while (isActive) {
                try {
                    delay(10_000) // 3초 → 10초로 변경

                    val currentTime = System.currentTimeMillis()
                    val poolHealthStatus = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
                    val poolDetailStatus = bitmapPoolManager.getPoolDetailedStatus()

                    when (poolHealthStatus.healthLevel) {
                        HealthLevel.CRITICAL -> {
                            if (!isPoolCritical.getAndSet(true)) {
                                fileLogger.e("MainActivity", " BitmapPool CRITICAL - 즉시 응급 복구!")
                                logCriticalPoolState(poolHealthStatus, poolDetailStatus)
                                generateEmergencyHeapDump("CriticalBitmapPool")

                                monitoringScope.launch {
                                    performPoolEmergencyRecovery("Critical Pool State - Available: ${poolHealthStatus.availableSlots}")
                                }
                            }
                        }
                        HealthLevel.WARNING -> {
                            fileLogger.w("MainActivity", " BitmapPool WARNING - 예방적 정리: ${poolHealthStatus.recommendation}")

                            monitoringScope.launch {
                                delay(2000) // 1초 → 2초로 늘림
                                try {
                                    bitmapPoolManager.advancedTaggedBitmapPool.forceCleanupStaleReferences()
                                    fileLogger.i("MainActivity", " 예방적 정리 완료")
                                } catch (e: Exception) {
                                    fileLogger.e("MainActivity", " 예방적 정리 실패: ${e.message}", e)
                                }
                            }
                        }
                        HealthLevel.DEGRADED -> {
                            fileLogger.w("MainActivity", " BitmapPool DEGRADED - Stale: ${poolHealthStatus.staleSlots}개")

                            if (poolHealthStatus.staleSlots > 5) { // 3 → 5로 변경
                                bitmapPoolManager.advancedTaggedBitmapPool.forceCleanupStaleReferences()
                            }
                        }
                        HealthLevel.HEALTHY -> {
                            if (isPoolCritical.getAndSet(false)) {
                                fileLogger.i("MainActivity", " BitmapPool 상태 완전 회복: HEALTHY")
                            }
                        }
                    }

                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "BitmapPool 모니터링 오류: ${e.message}", e)
                }
            }
        }
    }

    /**
     *  .mp4 녹화 상태 모니터링
     */
    private fun startMp4RecordingMonitoring() {
        monitoringScope.launch {
            while (isActive) {
                try {
                    delay(1_000) // 1초마다 체크

                    //  HomeRepository를 통한 정확한 상태 확인
                    val isCurrentlyRecording = checkIfMp4Recording()
                    val currentSessionId = if (::homeRepository.isInitialized) {
                        homeRepository.getCurrentVideoSessionId()
                    } else null

                    if (isCurrentlyRecording != isMp4Recording) {
                        if (isCurrentlyRecording) {
                            // 녹화 시작
                            isMp4Recording = true
                            mp4RecordingStartTime = System.currentTimeMillis()
                            mp4RecordingFpsDrops = 0

                            fileLogger.i("MainActivity", " .mp4 녹화 시작 ")
                            fileLogger.i("MainActivity", "세션 ID: $currentSessionId")
                            logMp4RecordingStart()

                        } else {
                            // 녹화 종료
                            if (isMp4Recording) {
                                val recordingDuration = System.currentTimeMillis() - mp4RecordingStartTime
                                fileLogger.i("MainActivity", " .mp4 녹화 종료 ")
                                fileLogger.i("MainActivity", "세션 ID: $currentSessionId")
                                logMp4RecordingEnd(recordingDuration)
                            }
                            isMp4Recording = false
                        }
                    }

                } catch (e: Exception) {
                    fileLogger.e("MainActivity", ".mp4 녹화 모니터링 오류: ${e.message}", e)
                }
            }
        }
    }

    /**
     *  .mp4 녹화 상태 확인 (HomeRepository를 통해)
     */
    private fun checkIfMp4Recording(): Boolean {
        return try {
            // HomeRepository가 초기화되었는지 확인
            if (::homeRepository.isInitialized) {
                // LoggerManager의 상태를 간접적으로 확인
                // 실제 구현에서는 HomeRepository에 isRecording() 메서드 추가 필요
                val syncStatus = homeRepository.getSyncStatus()
                syncStatus.contains("로깅") || syncStatus.contains("저장")
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     *  실시간 FPS 모니터링 - 최적화된 버전
     */
    private fun startRealTimeFpsMonitoring() {
        lastFpsTime = System.currentTimeMillis()
        frameCount = 0
        consecutiveLowFpsCount = 0
        
        // 백그라운드에서 FPS 계산 및 캐싱
        monitoringScope.launch {
            while (true) {
                delay(1000) // 1초마다 업데이트
                val currentTime = System.currentTimeMillis()
                val currentFrameCount = frameCount
                if (currentFrameCount > 0) {
                    val fps = currentFrameCount * 1000.0 / (currentTime - lastFpsTime)
                    _cachedFps.value = fps
                    currentFps.set(fps.toLong())
                    lastFpsUpdateTime = currentTime
                    
                    // FPS 기반 제어는 백그라운드에서 처리
                    handleCachedFpsBasedControl(fps)
                    
                    lastFpsTime = currentTime
                    frameCount = 0
                }
            }
        }

        fpsMonitor = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    frameCount++
                    // 무거운 작업 없이 프레임만 카운트
                    Choreographer.getInstance().postFrameCallback(this)
                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "FPS 모니터링 오류: ${e.message}", e)
                }
            }
        }

        Choreographer.getInstance().postFrameCallback(fpsMonitor!!)
        fileLogger.i("MainActivity", "실시간 FPS 모니터링 시작 (최적화됨)")
    }
    
    /**
     * 캐싱된 FPS 기반 제어 (백그라운드에서 처리)
     */
    private suspend fun handleCachedFpsBasedControl(fps: Double) {
        // FPS에 따른 TextView 업데이트 제어
        withContext(Dispatchers.Main) {
            handleFpsBasedTextViewControl(fps)
        }
        
        // .mp4 녹화 중 FPS 드롭 특별 추적
        if (isMp4Recording && fps <= 10.0) {
            mp4RecordingFpsDrops++
            fileLogger.w("MainActivity", ".mp4 녹화 중 FPS 드롭: ${String.format("%.1f", fps)}fps (총 ${mp4RecordingFpsDrops}회)")
            logMp4FpsDropState(fps)
        }
        
        // FPS 기반 복구 로직
        when {
            fps < FPS_CRITICAL_THRESHOLD -> {
                consecutiveLowFpsCount++
                if (!isEmergencyRecoveryActive) {
                    isEmergencyRecoveryActive = true
                    fileLogger.e("MainActivity", "FPS ${FPS_CRITICAL_THRESHOLD} 이하 감지: ${String.format("%.1f", fps)}fps")
                    
                    withContext(Dispatchers.Main) {
                        pauseTextViewUpdates()
                    }
                    performTextViewEmergencyRecovery(fps)
                }
            }
            fps < FPS_WARNING_THRESHOLD -> {
                if (!isLowFpsDetected.getAndSet(true)) {
                    val context = if (isMp4Recording) "경고_FPS_드롭_MP4녹화중" else "경고_FPS_드롭"
                    fileLogger.w("MainActivity", "FPS ${FPS_WARNING_THRESHOLD} 이하 감지: ${String.format("%.1f", fps)}fps")
                    logDetailedSystemState(context + "_${String.format("%.1f", fps)}")

                    monitoringScope.launch {
                        delay(5000)
                        isLowFpsDetected.set(false)
                    }
                }
            }
            fps >= 15.0 -> {
                consecutiveLowFpsCount = 0
                if (isEmergencyRecoveryActive) {
                    isEmergencyRecoveryActive = false
                    fileLogger.i("MainActivity", "FPS 회복: ${String.format("%.1f", fps)}fps")
                    withContext(Dispatchers.Main) {
                        resumeTextViewUpdates()
                    }
                }
            }
        }
    }

    /**
     *  .mp4 녹화 시작 시 상태 로깅
     */
    private fun logMp4RecordingStart() {
        try {
            fileLogger.i("MainActivity", "==================== .mp4 녹화 시작 상태 ====================")
            fileLogger.i("MainActivity", "녹화 시작 시간: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")

            // BitmapPool 상태
            val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
            val poolDetail = bitmapPoolManager.getPoolDetailedStatus()
            fileLogger.i("MainActivity", "녹화 시작 시 BitmapPool 상태:")
            fileLogger.i("MainActivity", "   Health: ${poolHealth.healthLevel}")
            fileLogger.i("MainActivity", "   Available: ${poolHealth.availableSlots}/${poolHealth.totalSlots}")
            fileLogger.i("MainActivity", "   Active: ${poolHealth.totalReferences}")
            fileLogger.i("MainActivity", "   Pool Detail: ${poolDetail.take(200)}")

            // 메모리 상태
            val appMemory = resourceMonitor.getAppMemoryInfo()
            fileLogger.i("MainActivity", "녹화 시작 시 메모리 상태:")
            fileLogger.i("MainActivity", "   힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
            fileLogger.i("MainActivity", "   Native 메모리: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
            fileLogger.i("MainActivity", "   가용 힙: ${String.format("%.1f", appMemory.availableHeapMB)} MB")

            fileLogger.i("MainActivity", "현재 FPS: ${currentFps.get()}fps")
            fileLogger.i("MainActivity", " ========================================================")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", ".mp4 녹화 시작 로깅 실패: ${e.message}", e)
        }
    }

    /**
     *  .mp4 녹화 종료 시 상태 로깅
     */
    private fun logMp4RecordingEnd(duration: Long) {
        try {
            fileLogger.i("MainActivity", " ==================== .mp4 녹화 종료 상태 ====================")
            fileLogger.i("MainActivity", "녹화 종료 시간: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            fileLogger.i("MainActivity", "총 녹화 시간: ${duration / 1000}초 (${duration / 60000}분)")
            fileLogger.i("MainActivity", "녹화 중 FPS 드롭 횟수: $mp4RecordingFpsDrops")

            // 종료 시 BitmapPool 상태
            val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
            val poolDetail = bitmapPoolManager.getPoolDetailedStatus()
            fileLogger.i("MainActivity", "녹화 종료 시 BitmapPool 상태:")
            fileLogger.i("MainActivity", "   Health: ${poolHealth.healthLevel}")
            fileLogger.i("MainActivity", "   Available: ${poolHealth.availableSlots}/${poolHealth.totalSlots}")
            fileLogger.i("MainActivity", "   Active: ${poolHealth.totalReferences}")
            fileLogger.i("MainActivity", "   Stale: ${poolHealth.staleSlots}")

            // 종료 시 메모리 상태
            val appMemory = resourceMonitor.getAppMemoryInfo()
            fileLogger.i("MainActivity", "녹화 종료 시 메모리 상태:")
            fileLogger.i("MainActivity", "   힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
            fileLogger.i("MainActivity", "   Native 메모리: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
            fileLogger.i("MainActivity", "   가용 힙: ${String.format("%.1f", appMemory.availableHeapMB)} MB")

            fileLogger.i("MainActivity", "최종 FPS: ${currentFps.get()}fps")

            //  분석 및 권장사항
            if (mp4RecordingFpsDrops > 5) {
                fileLogger.w("MainActivity", " 녹화 중 잦은 FPS 드롭 감지 (${mp4RecordingFpsDrops}회)")
                fileLogger.w("MainActivity", "권장사항: BitmapPool 크기 증가 또는 프레임 스킵 간격 조정")
            }

            if (poolHealth.healthLevel != HealthLevel.HEALTHY) {
                fileLogger.w("MainActivity", " 녹화 종료 시 BitmapPool 상태 불량: ${poolHealth.healthLevel}")
            }

            fileLogger.i("MainActivity", " ========================================================")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", ".mp4 녹화 종료 로깅 실패: ${e.message}", e)
        }
    }

    /**
     *  .mp4 녹화 중 FPS 드롭 시 상태 로깅
     */
    private suspend fun logMp4FpsDropState(fps: Double) = withContext(Dispatchers.IO) {
        try {
            val recordingDuration = System.currentTimeMillis() - mp4RecordingStartTime

            fileLogger.w("MainActivity", " .mp4 녹화 중 FPS 드롭 상세 분석 ")
            fileLogger.w("MainActivity", "드롭 발생 시간: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            fileLogger.w("MainActivity", "녹화 경과 시간: ${recordingDuration / 1000}초")
            fileLogger.w("MainActivity", "현재 FPS: ${String.format("%.1f", fps)}fps")
            fileLogger.w("MainActivity", "누적 FPS 드롭: $mp4RecordingFpsDrops")

            // BitmapPool 즉시 상태 체크
            val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
            fileLogger.w("MainActivity", "FPS 드롭 시 BitmapPool:")
            fileLogger.w("MainActivity", "   Health: ${poolHealth.healthLevel}")
            fileLogger.w("MainActivity", "   Available: ${poolHealth.availableSlots}/${poolHealth.totalSlots}")
            fileLogger.w("MainActivity", "   Active: ${poolHealth.totalReferences}")
            fileLogger.w("MainActivity", "   Stale: ${poolHealth.staleSlots}")

            // 메모리 압박 체크
            val appMemory = resourceMonitor.getAppMemoryInfo()
            fileLogger.w("MainActivity", "FPS 드롭 시 메모리:")
            fileLogger.w("MainActivity", "   힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
            fileLogger.w("MainActivity", "   Native: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
            fileLogger.w("MainActivity", "   압박 수준: ${appMemory.memoryPressureLevel}")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", ".mp4 FPS 드롭 로깅 실패: ${e.message}", e)
        }
    }

    /**
     *  BitmapPool Critical 상태 로깅
     */
    private fun logCriticalPoolState(healthStatus: PoolHealthStatus, detailStatus: String) {
        try {
            fileLogger.e("MainActivity", " BitmapPool CRITICAL 상태 ")
            fileLogger.e("MainActivity", "Health Level: ${healthStatus.healthLevel}")
            fileLogger.e("MainActivity", "Available Slots: ${healthStatus.availableSlots}/${healthStatus.totalSlots}")
            fileLogger.e("MainActivity", "Total References: ${healthStatus.totalReferences}")
            fileLogger.e("MainActivity", "Stale Slots: ${healthStatus.staleSlots}")
            fileLogger.e("MainActivity", "Recommendation: ${healthStatus.recommendation}")
            fileLogger.e("MainActivity", "Current FPS: ${currentFps.get()}fps")
            fileLogger.e("MainActivity", "MP4 Recording: $isMp4Recording")

            if (isMp4Recording) {
                val duration = System.currentTimeMillis() - mp4RecordingStartTime
                fileLogger.e("MainActivity", "MP4 Recording Duration: ${duration / 1000}초")
                fileLogger.e("MainActivity", "MP4 FPS Drops: $mp4RecordingFpsDrops")
            }

            fileLogger.e("MainActivity", "Pool Detail Status:")
            fileLogger.e("MainActivity", detailStatus)

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "Critical Pool 상태 로깅 실패: ${e.message}", e)
        }
    }

    /**
     * Pool 응급 복구
     */
    private suspend fun performPoolEmergencyRecovery(reason: String) = withContext(Dispatchers.IO) {
        try {
            fileLogger.w("MainActivity", "강화된 응급 복구 시작: $reason")

            // suspend 함수용 traceSectionAsync 사용
            perfettoTracer.traceSectionAsync("EmergencyRecovery") {
                // 1단계: UI 안전 클리어
                val uiJob = launch(Dispatchers.Main) {
                    try {
                        Log.w("MainActivity", "1단계: UI 프레임 안전 클리어 요청")
                    } catch (e: Exception) {
                        fileLogger.e("MainActivity", "UI 클리어 실패: ${e.message}", e)
                    }
                }
                uiJob.join()
                delay(200)

                // 2단계: Native 메모리 적극적 해제
                releaseNativeMemory()
                delay(500)

                // 3단계: BitmapPool 응급 리셋
                try {
                    bitmapPoolManager.performEmergencyReset()
                    delay(500)
                    fileLogger.w("MainActivity", "3단계: BitmapPool 응급 리셋 완료")
                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "BitmapPool 리셋 실패: ${e.message}", e)
                }

                // 4단계: 상태 재확인
                val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
                val appMemory = resourceMonitor.getAppMemoryInfo()

                fileLogger.w("MainActivity", "복구 결과:")
                fileLogger.w("MainActivity", "  Pool: ${poolHealth.healthLevel}, Available: ${poolHealth.availableSlots}/${poolHealth.totalSlots}")
                fileLogger.w("MainActivity", "  힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
                fileLogger.w("MainActivity", "  가용 힙: ${String.format("%.1f", appMemory.availableHeapMB)} MB")
            }

            fileLogger.w("MainActivity", "강화된 응급 복구 완료: $reason")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "응급 복구 실패: ${e.message}", e)
        }
    }

    private suspend fun releaseNativeMemory() = withContext(Dispatchers.IO) {
        try {
            // 비트맵 풀 완전 정리
            bitmapPoolManager.advancedTaggedBitmapPool.forceCleanupStaleReferences()

            // 강제 GC 더 적극적으로
            System.gc()
            delay(300L) // Thread.sleep 대신 delay 사용
            System.runFinalization()
            delay(200)  // Thread.sleep 대신 delay 사용
            System.gc() // 한 번 더

            // Native 힙 정리 요청
            try {
                val vmRuntime = Class.forName("dalvik.system.VMRuntime")
                    .getMethod("getRuntime")
                    .invoke(null)

                vmRuntime.javaClass
                    .getMethod("requestConcurrentGC")
                    .invoke(vmRuntime)

                fileLogger.w("MainActivity", "Native 메모리 정리 요청 완료")
            } catch (e: Exception) {
                fileLogger.w("MainActivity", "Native 메모리 정리 요청 실패: ${e.message}")
            }

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "Native 메모리 해제 실패: ${e.message}", e)
        }
    }

    /**
     *  응급 복구 (일반적인 응급 상황용)
     */
    private suspend fun performEmergencyRecovery(reason: String) = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()

        // 연속 복구 방지 (5초 간격)
        if (currentTime - lastRecoveryTime < 5000) {
            fileLogger.w("MainActivity", "응급 복구 스킵 - 최근 복구: ${currentTime - lastRecoveryTime}ms 전")
            return@withContext
        }

        // 복구 횟수 제한 (10회 이상 시 더 강력한 조치)
        if (emergencyRecoveryCount > 10) {
            performDeepRecovery()
            return@withContext
        }

        emergencyRecoveryCount++
        lastRecoveryTime = currentTime

        try {
            fileLogger.w("MainActivity", " 제한된 응급 복구 시작: $reason (${emergencyRecoveryCount}회)")

            // suspend 함수용 traceSectionAsync 사용
            perfettoTracer.traceSectionAsync("EmergencyRecovery") {
                val preGpuInfo = gpuMemoryMonitor.getCurrentGpuInfo()
                if (preGpuInfo != null) {
                    fileLogger.w("MainActivity", "복구 전 GPU: Graphics=${String.format("%.1f", preGpuInfo.graphicsMemoryMB)}MB")
                }

                val uiJob = launch(Dispatchers.Main.immediate) {
                    try {
                        Log.w("MainActivity", "UI 프레임 강제 클리어")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "UI 클리어 실패: ${e.message}")
                    }
                }
                uiJob.join()
                delay(100)

                try {
                    bitmapPoolManager.advancedTaggedBitmapPool.forceCleanupStaleReferences()
                    delay(200)
                } catch (e: Exception) {
                    Log.e("MainActivity", "풀 정리 실패: ${e.message}")
                }

                System.gc()
                delay(100)

                delay(1000)
                val postGpuInfo = gpuMemoryMonitor.getCurrentGpuInfo()
                if (postGpuInfo != null && preGpuInfo != null) {
                    val memoryReduction = preGpuInfo.graphicsMemoryMB - postGpuInfo.graphicsMemoryMB
                    fileLogger.w("MainActivity", "복구 후 GPU: Graphics=${String.format("%.1f", postGpuInfo.graphicsMemoryMB)}MB")
                    fileLogger.w("MainActivity", "GPU 메모리 복구: ${String.format("%.1f", memoryReduction)}MB 감소")
                }
            }

            fileLogger.w("MainActivity", " 제한된 응급 복구 완료")

        } catch (e: Exception) {
            Log.e("MainActivity", "응급 복구 실패: ${e.message}", e)
        }
    }

    private suspend fun performDeepRecovery() = withContext(Dispatchers.IO) {
        try {
            fileLogger.e("MainActivity", " 심층 복구 시작 - 10회 이상 복구 실패")

            // 모든 모니터링 중단
            perfettoAutoTraceJob?.cancel()
            gpuMemoryMonitor.stopGpuMemoryMonitoring()

            // 강제 GC
            System.gc()
            Thread.sleep(500)
            System.runFinalization()
            Thread.sleep(500)

            // BitmapPool 완전 리셋
            bitmapPoolManager.performEmergencyReset()

            // UI 스레드 우선순위 조정
            withContext(Dispatchers.Main) {
                Thread.currentThread().priority = Thread.MAX_PRIORITY
            }

            emergencyRecoveryCount = 0
            fileLogger.e("MainActivity", " 심층 복구 완료")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "심층 복구 실패: ${e.message}", e)
        }
    }

    /**
     *  30초마다 정기 모니터링 (BitmapPool 상태 포함)
     */
    private fun startPeriodicMonitoring() {
        monitoringScope.launch {
            fileLogger.i("MainActivity", " 정기 모니터링 시작 (30초 간격, BitmapPool 포함)")

            while (isActive) {
                try {
                    delay(30_000) // 30초 대기

                    // 상세한 시스템 상태 로깅 (BitmapPool 포함)
                    logDetailedSystemState("정기_모니터링")

                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "정기 모니터링 오류: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 치명적 시스템 상태 로깅
     */
    private fun logCriticalSystemState(context: String) {
        try {
            val currentTime = System.currentTimeMillis()
            val uptime = currentTime - appStartTime
            val currentFpsValue = currentFps.get()

            fileLogger.e("MainActivity", " 치명적 시스템 상태 - $context ")
            fileLogger.e("MainActivity", "타임스탬프: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(currentTime))}")
            fileLogger.e("MainActivity", "앱 실행 시간: ${uptime / 1000}초")
            fileLogger.e("MainActivity", "현재 FPS: ${currentFpsValue}fps")
            fileLogger.e("MainActivity", ".mp4 녹화 상태: $isMp4Recording")

            if (isMp4Recording) {
                val recordingDuration = currentTime - mp4RecordingStartTime
                fileLogger.e("MainActivity", ".mp4 녹화 시간: ${recordingDuration / 1000}초")
                fileLogger.e("MainActivity", ".mp4 FPS 드롭: ${mp4RecordingFpsDrops}회")
            }

            //  치명적 상태 - 더 상세한 정보 로깅
            resourceMonitor.logAppResourceStatus("MainActivity", "치명적_상태_$context")

            // BitmapPool 치명적 상태
            val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
            fileLogger.e("MainActivity", "=== 치명적 BitmapPool 상태 ===")
            fileLogger.e("MainActivity", "Pool Health Level: ${poolHealth.healthLevel}")
            fileLogger.e("MainActivity", "Pool Available: ${poolHealth.availableSlots}/${poolHealth.totalSlots}")
            fileLogger.e("MainActivity", "Pool Active: ${poolHealth.totalReferences}")
            fileLogger.e("MainActivity", "Pool Stale: ${poolHealth.staleSlots}")
            fileLogger.e("MainActivity", "Pool Recommendation: ${poolHealth.recommendation}")

            // 메모리 치명적 상태
            val appMemory = resourceMonitor.getAppMemoryInfo()
            fileLogger.e("MainActivity", "=== 치명적 메모리 상태 ===")
            fileLogger.e("MainActivity", "힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
            fileLogger.e("MainActivity", "Native 메모리: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
            fileLogger.e("MainActivity", "가용 힙: ${String.format("%.1f", appMemory.availableHeapMB)} MB")
            fileLogger.e("MainActivity", "메모리 압박: ${appMemory.memoryPressureLevel}")

            fileLogger.e("MainActivity", " 치명적 상태 로깅 완료 - $context ")
            fileLogger.e("MainActivity", "")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "치명적 상태 로깅 실패: ${e.message}", e)
        }
    }

    /**
     *  상세한 시스템 상태 로깅 (BitmapPool 상태 포함)
     */
    private fun logDetailedSystemState(context: String) {
        try {
            val currentTime = System.currentTimeMillis()
            val uptime = currentTime - appStartTime
            val currentFpsValue = currentFps.get()

            fileLogger.i("MainActivity", " 상세 시스템 상태 (GPU 포함) - $context")
            fileLogger.i("MainActivity", "타임스탬프: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(currentTime))}")
            fileLogger.i("MainActivity", "앱 실행 시간: ${uptime / 1000}초")
            fileLogger.i("MainActivity", "현재 FPS: ${currentFpsValue}fps")
            fileLogger.i("MainActivity", ".mp4 녹화 상태: $isMp4Recording")
            if (isMp4Recording) {
                val recordingDuration = currentTime - mp4RecordingStartTime
                fileLogger.i("MainActivity", ".mp4 녹화 시간: ${recordingDuration / 1000}초")
                fileLogger.i("MainActivity", ".mp4 FPS 드롭: ${mp4RecordingFpsDrops}회")
            }

            // ResourceMonitor를 통한 완전한 상태 로깅
            resourceMonitor.logAppResourceStatus("MainActivity", context)

            // BitmapPool 상세 상태
            fileLogger.i("MainActivity", "=== BitmapPool 완전한 상태 ===")
            val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
            val poolDetail = bitmapPoolManager.getPoolDetailedStatus()

            fileLogger.i("MainActivity", "Pool Health Level: ${poolHealth.healthLevel}")
            fileLogger.i("MainActivity", "Pool Available: ${poolHealth.availableSlots}/${poolHealth.totalSlots}")
            fileLogger.i("MainActivity", "Pool Active: ${poolHealth.totalReferences}")
            fileLogger.i("MainActivity", "Pool Stale: ${poolHealth.staleSlots}")
            fileLogger.i("MainActivity", "Pool Recommendation: ${poolHealth.recommendation}")

            //  GPU 상세 상태 (새로 추가)
            val gpuInfo = gpuMemoryMonitor.getCurrentGpuInfo()
            if (gpuInfo != null) {
                fileLogger.i("MainActivity", "===  GPU 상세 상태  ===")
                fileLogger.i("MainActivity", "Graphics Memory: ${String.format("%.1f", gpuInfo.graphicsMemoryMB)} MB")
                fileLogger.i("MainActivity", "GL Memory: ${String.format("%.1f", gpuInfo.glMemoryMB)} MB")
                fileLogger.i("MainActivity", "Texture Memory: ${String.format("%.1f", gpuInfo.textureMemoryMB)} MB")
                fileLogger.i("MainActivity", "System Memory Usage: ${String.format("%.1f", gpuInfo.availableSystemMemoryMB)}/${String.format("%.1f", gpuInfo.totalSystemMemoryMB)} MB")
                fileLogger.i("MainActivity", "Memory Pressure: ${gpuInfo.memoryPressureLevel}")
                fileLogger.i("MainActivity", "EGL Contexts: ${gpuInfo.eglContextCount}")
                fileLogger.i("MainActivity", "Surface Buffers: ${gpuInfo.surfaceBufferCount}")

                if (gpuMemoryMonitor.isGpuMemoryLeakDetected()) {
                    fileLogger.w("MainActivity", " GPU 메모리 누수 의심")
                }
            }

            // Perfetto 추적 상태
            fileLogger.i("MainActivity", "=== Perfetto 추적 상태 ===")
            fileLogger.i("MainActivity", "추적 활성: ${perfettoTracer.isTracing()}")
            fileLogger.i("MainActivity", "수집된 이벤트: ${perfettoTracer.getEventCount()}개")

            // 메모리 경고 확인
            val warnings = resourceMonitor.checkAppMemoryWarnings()
            if (warnings.isNotEmpty()) {
                fileLogger.w("MainActivity", " 메모리 경고 감지")
                warnings.forEach { warning ->
                    fileLogger.w("MainActivity", "   $warning")
                }
            }

            fileLogger.i("MainActivity", " 상태 로깅 완료 - $context")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "상세 상태 + GPU 로깅 실패: ${e.message}", e)
        }
    }

    /**
     * 초기 시스템 상태 저장
     */
    private fun logInitialSystemState() {
        monitoringScope.launch {
            try {
                fileLogger.i("MainActivity", "=".repeat(80))
                fileLogger.i("MainActivity", "🚀🚀🚀 앱 시작 - 초기 시스템 상태 🚀🚀🚀")
                fileLogger.i("MainActivity", "세션 ID: $sessionId")
                fileLogger.i("MainActivity", "시작 시간: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(appStartTime))}")
                fileLogger.i("MainActivity", "Android 버전: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                fileLogger.i("MainActivity", "기기 모델: ${Build.MANUFACTURER} ${Build.MODEL}")
                fileLogger.i("MainActivity", "앱 버전: ${packageManager.getPackageInfo(packageName, 0).versionName}")

                // Documents/save/ 폴더 정보
                val traceFiles = perfettoTracer.getAllTraceFiles()
                fileLogger.i("MainActivity", "Documents/save/ 저장 위치 확인:")
                if (traceFiles.isNotEmpty()) {
                    fileLogger.i("MainActivity", "기존 추적 파일들:")
                    traceFiles.forEach { filePath ->
                        fileLogger.i("MainActivity", "  - $filePath")
                    }
                } else {
                    fileLogger.i("MainActivity", "Documents/save/ 폴더 새로 생성됨")
                }

                resourceMonitor.logAppResourceStatus("MainActivity", "앱_시작_초기_상태")
                fileLogger.i("MainActivity", "BitmapPool 초기 상태: ${bitmapPoolManager.getPoolDetailedStatus()}")
                fileLogger.i("MainActivity", "=".repeat(80))
            } catch (e: Exception) {
                fileLogger.e("MainActivity", "초기 상태 로깅 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 앱 종료 시 최종 상태 저장
     */
    private fun logFinalSystemState() {
        try {
            val endTime = System.currentTimeMillis()
            val totalUptime = endTime - appStartTime

            fileLogger.i("MainActivity", "🏁🏁🏁 앱 종료 - 최종 시스템 상태 🏁🏁🏁")
            fileLogger.i("MainActivity", "세션 ID: $sessionId")
            fileLogger.i("MainActivity", "총 실행 시간: ${totalUptime / 1000}초 (${totalUptime / 60000}분)")
            fileLogger.i("MainActivity", "종료 시간: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(endTime))}")
            fileLogger.i("MainActivity", "최종 FPS: ${currentFps.get()}fps")

            // 최종 Perfetto 추적 파일 목록
            val finalTraceFiles = perfettoTracer.getAllTraceFiles()
            fileLogger.i("MainActivity", "🎯 Documents/save/에 저장된 최종 추적 파일들:")
            finalTraceFiles.forEach { filePath ->
                val file = java.io.File(filePath)
                fileLogger.i("MainActivity", "  - ${file.name} (${file.length() / 1024}KB)")
            }

            resourceMonitor.logAppResourceStatus("MainActivity", "앱_종료_최종_상태")
            fileLogger.i("MainActivity", "🏁🏁🏁 세션 종료 완료 🏁🏁🏁")
            fileLogger.i("MainActivity", "=".repeat(80))
            Thread.sleep(1000)
        } catch (e: Exception) {
            Log.e("MainActivity", "최종 상태 로깅 실패: ${e.message}", e)
        }
    }

    // 기존 메서드들 (권한, 네비게이션 등)
    private fun setupNavigation() {
        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        } else {
            isCameraPermissionGranted = true
            Log.d("MainActivity", "Camera permission already granted")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            isLocationPermissionGranted = true
            Log.d("MainActivity", "Fine location permission already granted")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isBackgroundLocationPermissionGranted = true
            Log.d("MainActivity", "Background location permission already granted")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BODY_SENSORS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
        } else {
            startSensorStreamingIfPermissionsGranted()
        }
    }

    private fun requestBatteryOptimizationDisable() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            batteryOptimizationDialog = AlertDialog.Builder(this)
                .setMessage("GNSS 데이터 수집을 위해 배터리 최적화를 비활성화해야 합니다. 설정으로 이동하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
                .create()

            batteryOptimizationDialog?.show()
        }
    }

    private fun checkLocationServiceEnabled() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            locationServiceDialog = AlertDialog.Builder(this)
                .setMessage("위치 서비스가 비활성화되어 있습니다. 설정으로 이동하여 활성화하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
                .create()

            locationServiceDialog?.show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            permissions.forEachIndexed { index, permission ->
                val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
                when (permission) {
                    Manifest.permission.CAMERA -> {
                        isCameraPermissionGranted = granted
                        if (!granted) {
                            Toast.makeText(this, "카메라 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                            Log.w("MainActivity", "Camera permission denied")
                        } else {
                            Log.d("MainActivity", "Camera permission granted after request")
                        }
                    }
                    Manifest.permission.ACCESS_FINE_LOCATION -> {
                        isLocationPermissionGranted = granted
                        if (!granted) {
                            Toast.makeText(this, "위치 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                            Log.w("MainActivity", "Location permission denied")
                        } else {
                            Log.d("MainActivity", "Location permission granted after request")
                        }
                    }
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> {
                        isBackgroundLocationPermissionGranted = granted
                        if (!granted) {
                            Toast.makeText(this, "백그라운드 위치 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                            Log.w("MainActivity", "Background location permission denied")
                        } else {
                            Log.d("MainActivity", "Background location permission granted after request")
                        }
                    }
                }
            }
            startSensorStreamingIfPermissionsGranted()
        }
    }

    private fun startSensorStreamingIfPermissionsGranted() {
        sensorCollector.startSensorStreaming(
            gpsCallback = null,
            imuCallback = null,
            gnssCallback = null,
            detectionCallback = null
        )
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()

        // 응급 종료 방지
        if (emergencyShutdownPrevention.compareAndSet(false, true)) {
            Log.w("MainActivity", " 응급 종료 방지 프로토콜 활성화")

            runBlocking {
                try {
                    // Perfetto 추적 즉시 중지
                    stopPerfettoTracing()

                    // 최종 힙 덤프 생성
                    perfettoTracer.generateHeapDump("AppShutdown")

                    // 모든 리소스 강제 정리
                    bitmapPoolManager.performEmergencyReset()
                    delay(200)
                    System.gc()
                    delay(200)
                    System.runFinalization()
                    delay(300)
                    gpuMemoryMonitor.stopGpuMemoryMonitoring()
                    delay(300)

                    // 최종 상태 저장
                    logFinalSystemState()

                } catch (e: Exception) {
                    Log.e("MainActivity", "응급 종료 방지 실패: ${e.message}", e)
                }
            }
        }

        if (::sensorCollector.isInitialized) {
            sensorCollector.stopSensorStreaming()
            sensorCollector.closeCamera()
            // 🎯 추가: SensorCollector 정리
            sensorCollector.cleanup()
        }

        //  1단계: 최종 상태 저장 (BitmapPool 포함)
        runBlocking {
            try {
                logFinalSystemState()
            } catch (e: Exception) {
                Log.e("MainActivity", "최종 상태 저장 실패: ${e.message}", e)
            }
        }

        //  2단계: 모니터링 중지
        try {
            // FPS 모니터링 중지
            fpsMonitor?.let {
                Choreographer.getInstance().removeFrameCallback(it)
            }
            fpsMonitor = null

            // Perfetto 자동 추적 중지
            perfettoAutoTraceJob?.cancel()
            perfettoTracer.cleanup()

            monitoringScope.cancel()
            fileLogger.stopFileLogging()

        } catch (e: Exception) {
            Log.e("MainActivity", "모니터링 시스템 정리 실패: ${e.message}", e)
        }

        //  3단계: 기존 정리 작업
        batteryOptimizationDialog?.dismiss()
        locationServiceDialog?.dismiss()
        batteryOptimizationDialog = null
        locationServiceDialog = null

        if (::sensorCollector.isInitialized) {
            sensorCollector.stopSensorStreaming()
            sensorCollector.closeCamera()
        }

        if (isFinishing) {
            bitmapPoolManager.shutdown()
        }

        Log.d("MainActivity", " Documents/save/ Perfetto 추적과 함께 Activity 정리 완료")
    }

    /**
     * FPS 기반 TextView 업데이트 제어
     */
    private fun handleFpsBasedTextViewControl(fps: Double) {
        when {
            fps < FPS_CRITICAL_THRESHOLD && !isTextUpdatesPaused -> {
                pauseTextViewUpdates()
            }
            fps >= FPS_WARNING_THRESHOLD && isTextUpdatesPaused -> {
                resumeTextViewUpdates()
            }
        }
    }
    
    /**
     * TextView 업데이트 일시정지
     */
    private fun pauseTextViewUpdates() {
        isTextUpdatesPaused = true
        
        // HomeFragment의 pauseTextUpdates() 호출
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val currentFragment = supportFragmentManager.primaryNavigationFragment?.childFragmentManager?.fragments?.firstOrNull()
        if (currentFragment is com.example.myapplication.ui.home.HomeFragment) {
            currentFragment.pauseTextUpdates()
        }
        
        fileLogger.w("MainActivity", "TextView 업데이트 일시정지")
    }
    
    /**
     * TextView 업데이트 재개
     */
    private fun resumeTextViewUpdates() {
        isTextUpdatesPaused = false
        
        // HomeFragment의 resumeTextUpdates() 호출
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val currentFragment = supportFragmentManager.primaryNavigationFragment?.childFragmentManager?.fragments?.firstOrNull()
        if (currentFragment is com.example.myapplication.ui.home.HomeFragment) {
            currentFragment.resumeTextUpdates()
        }
        
        fileLogger.i("MainActivity", "TextView 업데이트 재개")
    }
    
    /**
     * TextView 관련 긴급 복구
     */
    private suspend fun performTextViewEmergencyRecovery(fps: Double) = withContext(Dispatchers.IO) {
        try {
            fileLogger.w("MainActivity", "TextView 긴급 복구 시작 - FPS: ${String.format("%.1f", fps)}")
            
            // 1. requestLayout 호출
            withContext(Dispatchers.Main) {
                window.decorView.requestLayout()
            }
            delay(100)
            
            // 2. GC 호출
            System.gc()
            delay(200)
            System.runFinalization()
            delay(200)
            
            // 3. BitmapPool 정리
            bitmapPoolManager.advancedTaggedBitmapPool.forceCleanupStaleReferences()
            delay(300)
            
            // 4. TextView 업데이트 재개
            withContext(Dispatchers.Main) {
                resumeTextViewUpdates()
            }
            
            // 5. 복구 완료 플래그 리셋
            isEmergencyRecoveryActive = false
            consecutiveLowFpsCount = 0
            
            fileLogger.i("MainActivity", "TextView 긴급 복구 완료")
            
        } catch (e: Exception) {
            fileLogger.e("MainActivity", "TextView 긴급 복구 실패: ${e.message}", e)
            isEmergencyRecoveryActive = false
        }
    }

    fun isCameraPermissionGranted(): Boolean = isCameraPermissionGranted
    fun isLocationPermissionGranted(): Boolean = isLocationPermissionGranted
    fun isBackgroundLocationPermissionGranted(): Boolean = isBackgroundLocationPermissionGranted
}
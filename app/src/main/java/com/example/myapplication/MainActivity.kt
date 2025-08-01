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
import com.example.myapplication.utils.CrashHandler
import com.example.myapplication.utils.FileLogger
import com.example.myapplication.utils.HealthLevel
import com.example.myapplication.utils.PoolHealthStatus
import com.example.myapplication.utils.ResourceMonitor
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import android.view.Choreographer
import kotlinx.coroutines.*
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
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // FPS 모니터링
    private var fpsMonitor: Choreographer.FrameCallback? = null
    private var lastFpsTime = 0L
    private var frameCount = 0
    private val currentFps = AtomicLong(60)
    private val isLowFpsDetected = AtomicBoolean(false)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //  1단계: 모니터링 시스템 초기화 (최우선!)
        initializeMonitoringSystem()

        //  2단계: 기존 초기화
        val dataSynchronizer = DataSynchronizer()
        bitmapPoolManager = BitmapPoolManager.getInstance(this)
        sensorCollector = SensorCollector(this, bitmapPoolManager)
        sensorCollector.setDataSynchronizer(dataSynchronizer)
        homeRepository = HomeRepository(this, sensorCollector, dataSynchronizer, bitmapPoolManager)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //  3단계: 완전한 모니터링 시작
        startComprehensiveMonitoring()

        checkPermissions()
        requestBatteryOptimizationDisable()
        checkLocationServiceEnabled()
        setupNavigation()

        fileLogger.i("MainActivity", "✅ 앱 시작 완료 - 세션: $sessionId")
        Log.d("MainActivity", "✅ BitmapPoolManager와 완전한 모니터링 시스템 초기화 완료")
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
            CrashHandler.setup(this)

            fileLogger.i("MainActivity", " 완전한 모니터링 시스템 초기화 완료 - 세션: $sessionId")
            fileLogger.i("MainActivity", " 로그 저장 위치: ${fileLogger.getLogDirectoryPath()}")

        } catch (e: Exception) {
            Log.e("MainActivity", "❌ 모니터링 시스템 초기화 실패: ${e.message}", e)
        }
    }

    /**
     * 완전한 모니터링 시작 (BitmapPool 포함)
     */
    private fun startComprehensiveMonitoring() {
        // ✅ 1. 30초마다 정기 상태 저장
        startPeriodicMonitoring()

        // ✅ 2. 실시간 FPS 모니터링
        startRealTimeFpsMonitoring()

        // ✅ 3. BitmapPool 전용 모니터링 (5초마다)
        startBitmapPoolMonitoring()

        // ✅ 4. .mp4 녹화 상태 모니터링
        startMp4RecordingMonitoring()

        // ✅ 5. 초기 상태 저장
        logInitialSystemState()
    }

    /**
     *  BitmapPool 전용 모니터링 (5초마다)
     */
    private fun startBitmapPoolMonitoring() {
        monitoringScope.launch {
            fileLogger.i("MainActivity", " 강화된 BitmapPool 모니터링 시작 (3초 간격)")

            while (isActive) {
                try {
                    delay(3_000) // 5초→3초로 단축

                    val currentTime = System.currentTimeMillis()
                    val poolHealthStatus = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
                    val poolDetailStatus = bitmapPoolManager.getPoolDetailedStatus()

                    //  더 적극적인 상태 변화 감지
                    when (poolHealthStatus.healthLevel) {
                        HealthLevel.CRITICAL -> {
                            if (!isPoolCritical.getAndSet(true)) {
                                fileLogger.e("MainActivity", " BitmapPool CRITICAL - 즉시 응급 복구! ")
                                logCriticalPoolState(poolHealthStatus, poolDetailStatus)

                                //  즉시 응급 복구
                                monitoringScope.launch {
                                    performPoolEmergencyRecovery("Critical Pool State - Available: ${poolHealthStatus.availableSlots}")
                                }
                            }
                        }
                        HealthLevel.WARNING -> {
                            fileLogger.w("MainActivity", " BitmapPool WARNING - 예방적 정리: ${poolHealthStatus.recommendation}")

                            //  예방적 정리 트리거
                            monitoringScope.launch {
                                delay(1000)
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

                            // 가벼운 정리
                            if (poolHealthStatus.staleSlots > 3) {
                                bitmapPoolManager.advancedTaggedBitmapPool.forceCleanupStaleReferences()
                            }
                        }
                        HealthLevel.HEALTHY -> {
                            if (isPoolCritical.getAndSet(false)) {
                                fileLogger.i("MainActivity", "✅ BitmapPool 상태 완전 회복: HEALTHY")
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

                    //  세션 변화 감지
                    if (isMp4Recording && currentSessionId != null) {
                        // 현재 추적 중인 세션과 다르면 세션 변화 로깅
                        // (구현 필요시 추가)
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
     *  실시간 FPS 모니터링 (.mp4 녹화 특별 추적 포함)
     */
    private fun startRealTimeFpsMonitoring() {
        lastFpsTime = System.currentTimeMillis()
        frameCount = 0

        fpsMonitor = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    frameCount++
                    val currentTime = System.currentTimeMillis()

                    // 1초마다 FPS 계산
                    if (currentTime - lastFpsTime >= 1000) {
                        val fps = frameCount * 1000.0 / (currentTime - lastFpsTime)
                        currentFps.set(fps.toLong())

                        // .mp4 녹화 중 FPS 드롭 특별 추적
                        if (isMp4Recording && fps <= 10.0) {
                            mp4RecordingFpsDrops++
                            fileLogger.w("MainActivity", " .mp4 녹화 중 FPS 드롭: ${String.format("%.1f", fps)}fps (총 ${mp4RecordingFpsDrops}회)")

                            // 녹화 중 FPS 드롭 시 BitmapPool 상태 즉시 체크
                            monitoringScope.launch {
                                logMp4FpsDropState(fps)
                            }
                        }

                        //  일반 위험 상황 감지
                        when {
                            fps <= 5.0 -> {
                                val context = if (isMp4Recording) "치명적_FPS_드롭_MP4녹화중" else "치명적_FPS_드롭"
                                fileLogger.e("MainActivity", " 치명적 FPS 드롭 감지: ${String.format("%.1f", fps)}fps ")
                                logCriticalSystemState(context + "_${String.format("%.1f", fps)}")

                                monitoringScope.launch {
                                    performEmergencyRecovery("치명적 FPS 드롭 - MP4: $isMp4Recording")
                                }
                            }
                            fps <= 8.0 -> {
                                if (!isLowFpsDetected.getAndSet(true)) {
                                    val context = if (isMp4Recording) "위험_FPS_드롭_MP4녹화중" else "위험_FPS_드롭"
                                    fileLogger.w("MainActivity", " 위험 FPS 드롭 감지: ${String.format("%.1f", fps)}fps ")
                                    logDetailedSystemState(context + "_${String.format("%.1f", fps)}")

                                    // 5초 후 플래그 리셋
                                    monitoringScope.launch {
                                        delay(5000)
                                        isLowFpsDetected.set(false)
                                    }
                                }
                            }
                            fps >= 15.0 -> {
                                if (isLowFpsDetected.getAndSet(false)) {
                                    val context = if (isMp4Recording) "FPS회복_MP4녹화중" else "FPS회복"
                                    fileLogger.i("MainActivity", "✅ FPS 회복: ${String.format("%.1f", fps)}fps ($context)")
                                }
                            }
                        }

                        lastFpsTime = currentTime
                        frameCount = 0
                    }

                    Choreographer.getInstance().postFrameCallback(this)
                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "FPS 모니터링 오류: ${e.message}", e)
                }
            }
        }

        Choreographer.getInstance().postFrameCallback(fpsMonitor!!)
        fileLogger.i("MainActivity", " 실시간 FPS 모니터링 시작 (.mp4 녹화 추적 포함)")
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
     *  상세한 Pool 상태 로깅
     */
    private fun logDetailedPoolState(level: String, healthStatus: PoolHealthStatus, detailStatus: String) {
        try {
            fileLogger.w("MainActivity", "🎭 BitmapPool $level 상태 상세:")
            fileLogger.w("MainActivity", "   Health: ${healthStatus.healthLevel}")
            fileLogger.w("MainActivity", "   Available: ${healthStatus.availableSlots}/${healthStatus.totalSlots}")
            fileLogger.w("MainActivity", "   Active: ${healthStatus.totalReferences}")
            fileLogger.w("MainActivity", "   Stale: ${healthStatus.staleSlots}")
            fileLogger.w("MainActivity", "   Recommendation: ${healthStatus.recommendation}")
            fileLogger.w("MainActivity", "   FPS: ${currentFps.get()}fps")
            fileLogger.w("MainActivity", "   MP4 Recording: $isMp4Recording")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "상세 Pool 상태 로깅 실패: ${e.message}", e)
        }
    }

    /**
     *  Pool 응급 복구
     */
    private suspend fun performPoolEmergencyRecovery(reason: String) = withContext(Dispatchers.IO) {
        try {
            fileLogger.w("MainActivity", "🔧🔧 강화된 응급 복구 시작: $reason 🔧🔧")

            // 1단계: UI 안전 클리어 (우선순위)
            launch(Dispatchers.Main) {
                try {
                    // HomeFragment의 응급 클리어 요청
                    Log.w("MainActivity", "1단계: UI 프레임 안전 클리어 요청")
                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "UI 클리어 실패: ${e.message}", e)
                }
            }
            delay(200)

            // 2단계: 강제 GC (더 적극적)
            System.gc()
            delay(300)
            System.runFinalization()
            delay(200)
            fileLogger.w("MainActivity", "2단계: 강제 GC 완료")

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

            fileLogger.w("MainActivity", "✅✅ 강화된 응급 복구 완료: $reason ✅✅")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "응급 복구 실패: ${e.message}", e)
        }
    }

    /**
     *  30초마다 정기 모니터링 (BitmapPool 상태 포함)
     */
    private fun startPeriodicMonitoring() {
        monitoringScope.launch {
            fileLogger.i("MainActivity", "📊 정기 모니터링 시작 (30초 간격, BitmapPool 포함)")

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
     *  응급 복구 (일반적인 응급 상황용)
     */
    private suspend fun performEmergencyRecovery(reason: String) = withContext(Dispatchers.IO) {
        try {
            fileLogger.w("MainActivity", "🔧🔧 응급 복구 시작: $reason 🔧🔧")

            // ✅ 1. UI 프레임 먼저 안전하게 클리어
            launch(Dispatchers.Main) {
                try {
                    // HomeFragment에 안전한 클리어 신호 전송
                    // TODO: HomeViewModel을 통해 안전한 UI 클리어 요청
                    fileLogger.w("MainActivity", "UI 프레임 안전 클리어 요청")
                } catch (e: Exception) {
                    fileLogger.e("MainActivity", "UI 프레임 클리어 실패: ${e.message}", e)
                }
            }

            delay(500) // UI 정리 완료 대기

            // ✅ 2. BitmapPool 정리 (ThreadPoolExecutor 종료 방지)
            try {
                bitmapPoolManager.requestPoolCleanup() // 응급 리셋 대신 정리만
                delay(300)
            } catch (e: Exception) {
                fileLogger.e("MainActivity", "BitmapPool 정리 실패: ${e.message}", e)
                // ThreadPoolExecutor 재생성 시도
                try {
                    bitmapPoolManager.performEmergencyReset()
                } catch (resetError: Exception) {
                    fileLogger.e("MainActivity", "응급 리셋도 실패: ${resetError.message}", resetError)
                }
            }

            // ✅ 3. 강제 GC (마지막에)
            System.gc()
            delay(200)

            fileLogger.w("MainActivity", "✅✅ 응급 복구 완료: $reason ✅✅")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "응급 복구 실패: ${e.message}", e)
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

            fileLogger.i("MainActivity", " 상세 시스템 상태 - $context ")
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

            //  BitmapPool 상세 상태 (가장 중요!)
            fileLogger.i("MainActivity", "=== BitmapPool 완전한 상태 ===")
            val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
            val poolDetail = bitmapPoolManager.getPoolDetailedStatus()

            fileLogger.i("MainActivity", "Pool Health Level: ${poolHealth.healthLevel}")
            fileLogger.i("MainActivity", "Pool Available: ${poolHealth.availableSlots}/${poolHealth.totalSlots}")
            fileLogger.i("MainActivity", "Pool Active: ${poolHealth.totalReferences}")
            fileLogger.i("MainActivity", "Pool Stale: ${poolHealth.staleSlots}")
            fileLogger.i("MainActivity", "Pool Recommendation: ${poolHealth.recommendation}")
            fileLogger.i("MainActivity", "Pool Detail:")
            fileLogger.i("MainActivity", poolDetail)

            // 메모리 경고 확인
            val warnings = resourceMonitor.checkAppMemoryWarnings()
            if (warnings.isNotEmpty()) {
                fileLogger.w("MainActivity", " 메모리 경고 감지 ")
                warnings.forEach { warning ->
                    fileLogger.w("MainActivity", "   $warning")
                }
            }

            fileLogger.i("MainActivity", " 상태 로깅 완료 - $context ")
            fileLogger.i("MainActivity", "")

        } catch (e: Exception) {
            fileLogger.e("MainActivity", "상세 상태 로깅 실패: ${e.message}", e)
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
            resourceMonitor.logAppResourceStatus("MainActivity", "앱_종료_최종_상태")
            fileLogger.i("MainActivity", "🏁🏁🏁 세션 종료 완료 🏁🏁🏁")
            fileLogger.i("MainActivity", "=".repeat(80))
            Thread.sleep(1000)
        } catch (e: Exception) {
            Log.e("MainActivity", "최종 상태 로깅 실패: ${e.message}", e)
        }
    }

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
                    // 모든 리소스 강제 정리
                    bitmapPoolManager.performEmergencyReset()
                    delay(200)
                    System.gc()
                    delay(200)
                    System.runFinalization()
                    delay(300)

                    // 최종 상태 저장
                    logFinalSystemState()

                } catch (e: Exception) {
                    Log.e("MainActivity", "응급 종료 방지 실패: ${e.message}", e)
                }
            }
        }

        // ✅ 1단계: 최종 상태 저장 (BitmapPool 포함)
        runBlocking {
            try {
                logFinalSystemState()
            } catch (e: Exception) {
                Log.e("MainActivity", "최종 상태 저장 실패: ${e.message}", e)
            }
        }

        // ✅ 2단계: 모니터링 중지
        try {
            fpsMonitor?.let {
                Choreographer.getInstance().removeFrameCallback(it)
            }
            fpsMonitor = null

            monitoringScope.cancel()
            fileLogger.stopFileLogging()

        } catch (e: Exception) {
            Log.e("MainActivity", "모니터링 시스템 정리 실패: ${e.message}", e)
        }

        // ✅ 3단계: 기존 정리 작업
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

        Log.d("MainActivity", "✅ BitmapPool 완전 모니터링과 함께 Activity 정리 완료")
    }

    fun isCameraPermissionGranted(): Boolean = isCameraPermissionGranted
    fun isLocationPermissionGranted(): Boolean = isLocationPermissionGranted
    fun isBackgroundLocationPermissionGranted(): Boolean = isBackgroundLocationPermissionGranted
}
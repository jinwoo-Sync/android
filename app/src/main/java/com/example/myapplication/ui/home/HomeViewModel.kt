package com.example.myapplication.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.repository.HomeRepository
import com.example.myapplication.data.sensor.SensorCollector
import com.example.myapplication.learning.yolo.BoundingBox
import com.example.myapplication.model.SensorData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.sample
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import android.graphics.Bitmap
import android.view.Choreographer

class HomeViewModel(
    private val sensorCollector: SensorCollector,
    private val homeRepository: HomeRepository
) : ViewModel() {
    private val TAG = "HomeViewModel"

    // UiFrame DTO for safe bitmap ownership transfer
    data class UiFrame(
        val bitmap: Bitmap,
        val frameId: Long,
        val timestamp: Long,
        val release: () -> Unit  // UI가 그린 후 호출
    )
    
    //  UiFrame 전달로 변경
    private val _cameraFrame = MutableLiveData<UiFrame?>()
    val cameraFrame: LiveData<UiFrame?> = _cameraFrame
    
    // 마지막 정상 프레임 유지
    private var lastGoodFrame: UiFrame? = null

    private val _boundingBoxes = MutableLiveData<List<BoundingBox>>()
    val boundingBoxes: LiveData<List<BoundingBox>> = _boundingBoxes

    private val _inferenceTime = MutableLiveData<String>()
    val inferenceTime: LiveData<String> = _inferenceTime

    private val _text = MutableLiveData<String>().apply {
        value = "카메라 미리보기 대기 중..."
    }
    val text: LiveData<String> = _text

    private val _gpsData = MutableLiveData<String>().apply {
        value = "GPS: 대기 중"
    }
    val gpsData: LiveData<String> = _gpsData

    private val _gnssData = MutableLiveData<String>().apply {
        value = "GNSS: 대기 중"
    }
    val gnssData: LiveData<String> = _gnssData

    private val _imuData = MutableLiveData<String>().apply {
        value = "IMU: 대기 중"
    }
    val imuData: LiveData<String> = _imuData

    //  UI 풀 복구 신호
    private val _shouldRecoverUIPool = MutableLiveData<Boolean>()
    val shouldRecoverUIPool: LiveData<Boolean> = _shouldRecoverUIPool

    private val _isStreaming = MutableLiveData<Boolean>(false)
    val isStreaming: LiveData<Boolean> = _isStreaming

    private val _isSensorStreaming = MutableLiveData<Boolean>(false)
    val isSensorStreaming: LiveData<Boolean> = _isSensorStreaming

    private var lastSensorUpdateTime = 0L
    private val UNIFIED_UPDATE_INTERVAL_MS = 1000L // 전체 센서 1초 간격 통합

    private val inferenceTimes = ArrayDeque<Long>(30)
    private var currentSkipInterval = 2
    private var userFrameSkipInterval: Int? = null
    private val _effectiveInterval = MutableLiveData<Int>()
    val effectiveInterval: LiveData<Int> = _effectiveInterval

    private val _isServerTransmissionEnabled = MutableLiveData<Boolean>()
    val isServerTransmissionEnabled: LiveData<Boolean> = _isServerTransmissionEnabled

    private val _syncStatus = MutableLiveData<String>()
    val syncStatus: LiveData<String> = _syncStatus

    //  Advanced Tagged Pool 상태 정보를 UI에 노출
    private val _poolStatus = MutableLiveData<String>()
    val poolStatus: LiveData<String> = _poolStatus

    private var isSensorStreamingStarted = false
    private var isCameraStreamingJob: kotlinx.coroutines.Job? = null

    //  강화된 Surface FPS 모니터링
    private var surfaceFpsMonitor: Choreographer.FrameCallback? = null
    private val choreographerLock = ReentrantLock()
    private var lastFpsCheckTime = 0L
    private var frameCount = 0
    private var consecutiveLowFpsCount = 0
    private val FPS_THRESHOLD = 10.0  // 8→10fps로 완화
    private val LOW_FPS_TRIGGER_COUNT = 3  // 5→3회로 더 빠른 대응
    private val isRecoveryInProgress = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)

    fun setServerTransmissionEnabled(enabled: Boolean) {
        _isServerTransmissionEnabled.postValue(enabled)
    }

    /**
     * SensorData를 UiFrame으로 변환하여 안전하게 전달
     */
    private fun updateCameraFrame(sensorData: SensorData?) {
        try {
            if (sensorData?.bitmap != null &&
                !sensorData.bitmap.isRecycled &&
                sensorData.bitmap.width > 0 &&
                sensorData.bitmap.height > 0) {

                if (sensorData.managedBitmap != null) {
                    if (!sensorData.managedBitmap!!.isValid()) {
                        Log.w(TAG, "무효한 ManagedBitmap 스킵: frameId=${sensorData.frameId}")
                        sensorData.managedBitmap!!.release()
                        return
                    }
                }

                // UiFrame 생성 - UI가 그린 후 release 호출
                val uiFrame = UiFrame(
                    bitmap = sensorData.bitmap,
                    frameId = sensorData.frameId,
                    timestamp = sensorData.timestamp,
                    release = { sensorData.managedBitmap?.release() }
                )
                
                renderSafeFrame(uiFrame)
                Log.d(TAG, "안전 프레임 전달: frameId=${sensorData.frameId}")
            } else {
                // null 데이터는 마지막 정상 프레임 유지
                renderSafeFrame(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 처리 실패: ${e.message}", e)
            sensorData?.managedBitmap?.release()
            renderSafeFrame(null)
        }
    }
    
    /**
     * 마지막 정상 프레임 유지로 깜빡임 방지
     */
    private fun renderSafeFrame(uiFrame: UiFrame?) {
        if (uiFrame != null) {
            // 이전 프레임 정리 (다음 프레임에 예약)
            lastGoodFrame?.let { oldFrame ->
                Choreographer.getInstance().postFrameCallback {
                    oldFrame.release()
                }
            }
            lastGoodFrame = uiFrame
            _cameraFrame.value = uiFrame
        } else {
            // null일 때 검은 화면 방지 - 이전 프레임 유지
            // _cameraFrame.value = null 하지 말기
        }
    }

    /**
     *  Advanced Tagged Pool 강제 정리 (UI 제어) - BitmapPoolManager 위임
     */
    fun forceCleanupBitmapPool() {
        try {
            Log.d(TAG, " 전체 비트맵 풀 강제 정리 시작 (BitmapPoolManager 위임)")

            //  HomeRepository를 통한 BitmapPoolManager 풀 정리
            homeRepository.requestPoolCleanup()

            // 3. 풀 상태 업데이트
            updatePoolStatus()

            _text.postValue("전체 비트맵 풀 강제 정리 완료 (BitmapPoolManager)")
            Log.d(TAG, " 전체 비트맵 풀 강제 정리 완료 (BitmapPoolManager)")

        } catch (e: Exception) {
            _text.postValue("풀 정리 실패: ${e.message}")
            Log.e(TAG, " 풀 정리 실패: ${e.message}", e)
        }
    }

    /**
     * Advanced Tagged Pool 상태 업데이트 - BitmapPoolManager 위임
     */
    fun updatePoolStatus() {
        try {
            //  HomeRepository를 통해 BitmapPoolManager 풀 상태 조회
            val status = homeRepository.getPoolDetailedStatus()
            _poolStatus.postValue(status)
            Log.d(TAG, " Advanced Tagged Pool 상태 업데이트 완료 (BitmapPoolManager)")
        } catch (e: Exception) {
            _poolStatus.postValue("풀 상태 조회 실패: ${e.message}")
            Log.e(TAG, " 풀 상태 업데이트 실패: ${e.message}", e)
        }
    }

    /**
     * 센서 스트리밍 시작 - 순서 조정
     */
    fun startSensorStreaming() {
        Log.d("HomeViewModel", " 센서 스트리밍 시작 - 카메라 보호 모드")

        // 이미 카메라가 돌고 있으면 경고
        if (_isStreaming.value == true) {
            Log.w("HomeViewModel", " 카메라 활성 상태에서 센서 재등록 - 주의!")
        }

        isSensorStreamingStarted = true
        _isSensorStreaming.postValue(true)

        homeRepository.startSensorStreaming(
            gpsCallback = { sensorDataString ->
                // 모든 센서 1초 간격 통합 업데이트
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSensorUpdateTime >= UNIFIED_UPDATE_INTERVAL_MS) {
                    // GPS 데이터 처리
                    viewModelScope.launch(Dispatchers.Default) {
                        val gpsInfo = buildString {
                            append("GPS: ${sensorDataString.value}")
                            append("\nSysTS: ${sensorDataString.timestamp}")
                        }
                        
                        withContext(Dispatchers.Main) {
                            _gpsData.value = gpsInfo
                        }
                    }
                }
            },
            imuCallback = { sensorDataString ->
                // 모든 센서 1초 간격 통합 업데이트
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSensorUpdateTime >= UNIFIED_UPDATE_INTERVAL_MS) {
                    // IMU 데이터 처리
                    viewModelScope.launch(Dispatchers.Default) {
                        val imuInfo = buildString {
                            append("IMU: ${sensorDataString.value}")
                            append("\nSysTS: ${sensorDataString.timestamp}")
                        }
                        
                        withContext(Dispatchers.Main) {
                            _imuData.value = imuInfo
                        }
                    }
                }
            },
            gnssCallback = { sensorDataString ->
                // 모든 센서 1초 간격 통합 업데이트
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSensorUpdateTime >= UNIFIED_UPDATE_INTERVAL_MS) {
                    lastSensorUpdateTime = currentTime // 통합 시간 업데이트
                    
                    // GNSS 데이터 처리
                    viewModelScope.launch(Dispatchers.Default) {
                        val gnssInfo = "GNSS: ${sensorDataString.value}"
                        
                        withContext(Dispatchers.Main) {
                            _gnssData.value = gnssInfo
                        }
                    }
                }
            },
            detectionCallback = null // 센서에서는 detection 안함
        )
    }

    /**
     *  센서 스트리밍 중지
     */
    fun stopSensorStreaming() {
        if (!isSensorStreamingStarted) {
            Log.d("HomeViewModel", "Sensor streaming not started")
            return
        }

        Log.d("HomeViewModel", " 센서 스트리밍 중지")
        homeRepository.stopSensorStreaming()
        isSensorStreamingStarted = false
        _isSensorStreaming.postValue(false)

        _gpsData.postValue("GPS: 대기 중")
        _gnssData.postValue("GNSS: 대기 중")
        _imuData.postValue("IMU: 대기 중")
        _syncStatus.postValue("동기화 중지됨")

        lastSensorUpdateTime = 0L
        Log.d("HomeViewModel", " 센서 스트리밍 중지 완료 (통합 1초 간격)")
    }

    /**
     * ✅ IMU 센서 일시 중단
     */
    fun pauseSensors() {
        viewModelScope.launch {
            try {
                homeRepository.pauseSensors()
                _imuData.postValue("IMU: 일시 중단됨")
                Log.d("HomeViewModel", "✅ ViewModel: IMU 센서 일시 중단 완료")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "❌ ViewModel: IMU 센서 일시 중단 실패: ${e.message}", e)
                _imuData.postValue("IMU: 일시 중단 실패")
            }
        }
    }

    /**
     * ✅ IMU 센서 재개
     */
    fun resumeSensors() {
        viewModelScope.launch {
            try {
                homeRepository.resumeSensors()
                _imuData.postValue("IMU: 재개됨")
                Log.d("HomeViewModel", "✅ ViewModel: IMU 센서 재개 완료")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "❌ ViewModel: IMU 센서 재개 실패: ${e.message}", e)
                _imuData.postValue("IMU: 재개 실패")
            }
        }
    }

    /**
     * 카메라 스트리밍 시작 - Advanced Tagged Pool 사용
     */
    private fun startCameraStreaming() {
        if (_isStreaming.value == true) {
            Log.d(TAG, "Camera streaming already active")
            return
        }

        Log.d(TAG, " Advanced Tagged Pool 카메라 스트리밍 시작")
        _text.value = "Advanced Tagged Pool 카메라 스트리밍 중..."

        //  Surface FPS 모니터링 시작
        startAdvancedSurfaceFpsMonitoring()

        homeRepository.detectionCallback = { boundingBoxes, inferenceTime, frameId ->
            Log.d(TAG, " ViewModel Detection 콜백 수신: frameId=$frameId, boxes=${boundingBoxes.size}, inference=${inferenceTime}ms")

            if (boundingBoxes.isNotEmpty()) {
                Log.d(TAG, " ViewModel에서 처리할 객체들: ${boundingBoxes.map { "${it.clsName}(conf=${it.cnf})" }}")
            }

            viewModelScope.launch(Dispatchers.Main.immediate) {
                try {
                    _boundingBoxes.value = boundingBoxes
                    _inferenceTime.value = "${inferenceTime}ms"
                    Log.d(TAG, " UI 업데이트 완료: ${boundingBoxes.size}개 바운딩박스, ${inferenceTime}ms")
                } catch (e: Exception) {
                    Log.e(TAG, " UI 업데이트 실패: ${e.message}", e)
                }
            }

            onNewInference(inferenceTime)
        }

        homeRepository.startCameraStreaming()

        isCameraStreamingJob?.cancel()

        isCameraStreamingJob = viewModelScope.launch {
            Log.d(TAG, " Advanced Tagged Camera Flow 구독 시작...")
            try {
                // sample() 제거 - Repository에서만 DROP_OLDEST 사용
                homeRepository.cameraStreamFlow.collect { sensorData ->
                    if (sensorData != null) {
                        updateCameraFrame(sensorData)
                        Log.d(TAG, " Advanced Tagged frame pushed: frameId=${sensorData.frameId}, size=${sensorData.bitmap?.width}x${sensorData.bitmap?.height}")
                    } else {
                        Log.w(TAG, " Received null sensor data from Advanced Tagged camera flow")
                        updateCameraFrame(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, " Advanced Tagged Camera Flow 구독 오류: ${e.message}", e)
                _text.postValue("Advanced Tagged 카메라 스트리밍 오류: ${e.message}")
                updateCameraFrame(null)
            }
        }
    }

    /**
     * 카메라 스트리밍 중지
     */
    private fun stopCameraStreaming() {
        if (_isStreaming.value != true) {
            Log.d("HomeViewModel", "No camera streaming to stop")
            return
        }

        Log.d("HomeViewModel", " Advanced Tagged 카메라 스트리밍 중지")

        isCameraStreamingJob?.cancel()
        isCameraStreamingJob = null

        stopSurfaceFpsMonitoring()

        homeRepository.stopCameraStreaming()
        _text.value = "Advanced Tagged 카메라 스트리밍 중지됨"

        updateCameraFrame(null)
        _boundingBoxes.postValue(emptyList())
        _inferenceTime.postValue("0ms")
    }

    /**
     *  스트리밍 토글
     */
    suspend fun toggleStreaming(context: Context) {
        if (_isStreaming.value == true) {
            stopCameraStreaming()
            _isStreaming.value = false
            setServerStreamingEnabled(context, false)
            Log.d("HomeViewModel", "Advanced Tagged Camera streaming stopped")
        } else {
            startCameraStreaming()
            _isStreaming.value = true
            Log.d("HomeViewModel", "Advanced Tagged Camera streaming started")
        }
    }

    /**
     *  프레임 캡처
     */
    suspend fun fetchCameraData() {
        if (_isStreaming.value == true) {
            _text.postValue("Advanced Tagged 프레임 저장 중...")
        } else {
            _text.postValue("Advanced Tagged 프레임 캡처 중...")
        }

        val sensorData = homeRepository.collectNewSensorData()
        updateCameraFrame(sensorData)

        if (sensorData != null) {
            if (_isStreaming.value == true) {
                _text.postValue("Advanced Tagged 프레임 저장 성공: ${sensorData.value}")
            } else {
                _text.postValue("Advanced Tagged 프레임 캡처 성공")
            }
        } else {
            if (_isStreaming.value == true) {
                _text.postValue("Advanced Tagged 프레임 저장 실패")
            } else {
                _text.postValue("Advanced Tagged 프레임 캡처 실패")
            }
        }
    }

    fun toggleLogSaving(context: Context, enabled: Boolean) {
        try {
            homeRepository.toggleLogSaving(context, enabled)
            _text.postValue(if (enabled) "실시간 로깅 시작" else "실시간 로깅 중지")
            Log.d("HomeViewModel", "Log saving toggled: $enabled")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to toggle log saving: ${e.message}", e)
            _text.postValue("로깅 설정 실패: ${e.message}")
        }
    }

    suspend fun setServerStreamingEnabled(context: Context, enabled: Boolean) {
        try {
            homeRepository.setServerStreamingEnabled(context, enabled)
            _isServerTransmissionEnabled.postValue(enabled)
            _text.postValue(if (enabled) "서버 스트리밍 시작" else "서버 스트리밍 중지")
            Log.d("HomeViewModel", "Server streaming set: $enabled")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to set server streaming: ${e.message}", e)
            _text.postValue("서버 스트리밍 설정 실패: ${e.message}")
        }
    }

    suspend fun setHttpStreamingEnabled(context: Context, enabled: Boolean) {
        try {
            homeRepository.setHttpStreamingEnabled(context, enabled)
            _text.postValue(if (enabled) "HTTP 스트리밍 시작" else "HTTP 스트리밍 중지")
            Log.d("HomeViewModel", "HTTP streaming set: $enabled")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to set HTTP streaming: ${e.message}", e)
            _text.postValue("HTTP 스트리밍 설정 실패: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 ViewModel 정리 시작")

        //  안전한 정리 순서
        stopSurfaceFpsMonitoring()  // 1. FPS 모니터링 먼저 중지
        
        // 마지막 프레임 정리
        lastGoodFrame?.release()
        lastGoodFrame = null

        isCameraStreamingJob?.cancel()
        homeRepository.stopCameraStreaming()
        stopSensorStreaming()
        sensorCollector.closeCamera()
        _isStreaming.value = false

        Log.d(TAG, " ViewModel 완전 정리 완료")
    }

    fun onNewInference(timeMs: Long) {
        inferenceTimes.addLast(timeMs)
        if (inferenceTimes.size > 30) inferenceTimes.removeFirst()

        val avg = inferenceTimes.average()
        val newInterval = when {
            avg > 120 -> 15
            avg > 100 -> 10
            avg > 80 -> 6
            avg > 60 -> 3
            else -> 2
        }

        val effectiveInterval = userFrameSkipInterval ?: newInterval

        if (effectiveInterval != currentSkipInterval) {
            homeRepository.setFrameSkipInterval(effectiveInterval)
            currentSkipInterval = effectiveInterval
            _effectiveInterval.postValue(effectiveInterval)
            Log.d("HomeViewModel", "Frame skip interval set to $effectiveInterval (avg: $avg)")
        }
    }

    fun setUserFrameSkipInterval(interval: Int) {
        if (interval in 2..15) {
            userFrameSkipInterval = interval
            homeRepository.setFrameSkipInterval(interval)
            currentSkipInterval = interval
            _effectiveInterval.postValue(interval)
            Log.d("HomeViewModel", "User set frame skip interval to $interval")
        } else {
            Log.w("HomeViewModel", "Invalid frame skip interval ignored: $interval")
        }
    }

    fun clearUserFrameSkipControl() {
        userFrameSkipInterval = null
        _effectiveInterval.postValue(currentSkipInterval)
        Log.d("HomeViewModel", "User frame skip control cleared; auto-control enabled")
    }

    private fun updateSyncStatus() {
        viewModelScope.launch {
            try {
                val status = homeRepository.getSyncStatus()
                _syncStatus.postValue(status)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to update sync status: ${e.message}", e)
            }
        }
    }

    /**
     * 완화된 Surface FPS 모니터링 시작
     */
    private fun startAdvancedSurfaceFpsMonitoring() {
        choreographerLock.lock()
        try {
            // 기존 콜백 완전 제거
            stopSurfaceFpsMonitoring()

            lastFpsCheckTime = System.currentTimeMillis()
            frameCount = 0
            consecutiveLowFpsCount = 0

            surfaceFpsMonitor = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    try {
                        frameCount++
                        val currentTime = System.currentTimeMillis()

                        //  3초마다 FPS 체크 (4초→3초)
                        if (currentTime - lastFpsCheckTime >= 3000) {
                            val fps = frameCount * 1000.0 / (currentTime - lastFpsCheckTime)

                            if (fps < FPS_THRESHOLD) {
                                consecutiveLowFpsCount++
                                Log.w(TAG, " FPS 낮음: ${String.format("%.1f", fps)}fps (연속 ${consecutiveLowFpsCount}회)")

                                //  더 빠른 응급 복구 트리거
                                if (consecutiveLowFpsCount >= LOW_FPS_TRIGGER_COUNT &&
                                    isRecoveryInProgress.compareAndSet(false, true)) {

                                    Log.w(TAG, " FPS 드롭 감지 - 즉시 응급 복구: ${consecutiveLowFpsCount}회")

                                    viewModelScope.launch(Dispatchers.IO) {
                                        try {
                                            //  단계별 응급 복구
                                            performStepByStepRecovery()

                                            delay(5000) // 5초 대기
                                            consecutiveLowFpsCount = 0
                                            isRecoveryInProgress.set(false)

                                            launch(Dispatchers.Main) {
                                                _shouldRecoverUIPool.postValue(false)
                                            }
                                            Log.d(TAG, " 단계별 응급 복구 완료")

                                        } catch (e: Exception) {
                                            Log.e(TAG, " 응급 복구 처리 예외: ${e.message}", e)
                                            isRecoveryInProgress.set(false)
                                        }
                                    }
                                }
                            } else {
                                consecutiveLowFpsCount = 0
                            }

                            lastFpsCheckTime = currentTime
                            frameCount = 0
                        }

                        //  안전한 재등록 - 상태 확인
                        choreographerLock.lock()
                        try {
                            if (surfaceFpsMonitor != null && callbackRegistered.get()) {
                                Choreographer.getInstance().postFrameCallback(this)
                            }
                        } finally {
                            choreographerLock.unlock()
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, " FPS 모니터링 콜백 오류: ${e.message}", e)
                        // 오류 발생 시 재등록 중단
                        choreographerLock.lock()
                        try {
                            callbackRegistered.set(false)
                        } finally {
                            choreographerLock.unlock()
                        }
                    }
                }
            }

            //  안전한 콜백 등록
            callbackRegistered.set(true)
            Choreographer.getInstance().postFrameCallback(surfaceFpsMonitor!!)
            Log.d(TAG, " 강화된 FPS 모니터링 시작 (임계값: ${FPS_THRESHOLD}fps)")

        } finally {
            choreographerLock.unlock()
        }
    }

    /**
     *  단계별 응급 복구 - 더 체계적인 접근
     */
    private suspend fun performStepByStepRecovery() = withContext(Dispatchers.IO) {
        Log.w(TAG, " 단계적 응급 복구 시작")

        try {
            // 1단계: UI 프레임 클리어 (Main 스레드)
            launch(Dispatchers.Main) {
                try {
                    _shouldRecoverUIPool.postValue(true)
                    Log.d(TAG, "1단계: UI 프레임 클리어 신호")
                } catch (e: Exception) {
                    Log.e(TAG, "1단계 실패: ${e.message}", e)
                }
            }
            delay(300)

            // 2단계: 강제 GC
            System.gc()
            delay(200)
            System.runFinalization()
            delay(200)
            Log.d(TAG, "2단계: GC 완료")

            // 3단계: BitmapPool 응급 정리
            forceCleanupBitmapPool()
            delay(300)
            Log.d(TAG, "3단계: BitmapPool 정리 완료")

            // 4단계: 추가 정리
            launch(Dispatchers.Main) {
                try {
                    homeRepository.requestPoolCleanup()
                    Log.d(TAG, "4단계: Repository 풀 정리 완료")
                } catch (e: Exception) {
                    Log.e(TAG, "4단계 실패: ${e.message}", e)
                }
            }
            delay(500)

            Log.w(TAG, "✅ 단계적 응급 복구 완료")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 단계적 복구 실패: ${e.message}", e)
        }
    }

    /**
     *  안전한 FPS 모니터링 중지
     */
    private fun stopSurfaceFpsMonitoring() {
        choreographerLock.lock()
        try {
            callbackRegistered.set(false)

            surfaceFpsMonitor?.let {
                try {
                    Choreographer.getInstance().removeFrameCallback(it)
                    Log.d(TAG, " FPS 모니터링 콜백 제거")
                } catch (e: Exception) {
                    Log.w(TAG, "콜백 제거 실패: ${e.message}")
                }
            }

            surfaceFpsMonitor = null
            consecutiveLowFpsCount = 0
            isRecoveryInProgress.set(false)

        } finally {
            choreographerLock.unlock()
        }
    }
}
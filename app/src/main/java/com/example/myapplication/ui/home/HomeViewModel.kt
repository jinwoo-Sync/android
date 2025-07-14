package com.example.myapplication.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.repository.HomeRepository
import com.example.myapplication.data.sensor.SensorCollector
import com.example.myapplication.learning.yolo.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeViewModel(
    private val sensorCollector: SensorCollector,
    private val homeRepository: HomeRepository
) : ViewModel() {
    // ✅ TAG 추가
    private val TAG = "HomeViewModel"

    private val _cameraFrame = MutableLiveData<Bitmap?>()
    val cameraFrame: LiveData<Bitmap?> = _cameraFrame

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

    private val _isStreaming = MutableLiveData<Boolean>(false)
    val isStreaming: LiveData<Boolean> = _isStreaming

    // ✅ 센서 스트리밍 상태 분리
    private val _isSensorStreaming = MutableLiveData<Boolean>(false)
    val isSensorStreaming: LiveData<Boolean> = _isSensorStreaming

    private var lastGnssUpdateTime = 0L
    private var lastImuUpdateTime = 0L
    private val IMU_UPDATE_INTERVAL_MS = 1000L
    private val boundingBoxMap = mutableMapOf<Long, Pair<List<BoundingBox>, Long>>()

    private val inferenceTimes = ArrayDeque<Long>(30)
    private var currentSkipInterval = 2
    private var userFrameSkipInterval: Int? = null
    private val _effectiveInterval = MutableLiveData<Int>()
    val effectiveInterval: LiveData<Int> = _effectiveInterval

    private val _isServerTransmissionEnabled = MutableLiveData<Boolean>()
    val isServerTransmissionEnabled: LiveData<Boolean> = _isServerTransmissionEnabled

    private val _syncStatus = MutableLiveData<String>()
    val syncStatus: LiveData<String> = _syncStatus

    // ✅ 센서 스트리밍 상태 관리 분리
    private var isSensorStreamingStarted = false
    private var isCameraStreamingJob: kotlinx.coroutines.Job? = null

    fun setServerTransmissionEnabled(enabled: Boolean) {
        _isServerTransmissionEnabled.postValue(enabled)
    }

    /**
     * ✅ 센서 스트리밍 시작 (백그라운드 - GPS, IMU, GNSS) - 독립적으로 동작
     */
    fun startSensorStreaming() {
        if (isSensorStreamingStarted) {
            Log.d("HomeViewModel", "Sensor streaming already started")
            return
        }

        Log.d("HomeViewModel", "🚀 센서 스트리밍 시작")
        isSensorStreamingStarted = true
        _isSensorStreaming.postValue(true)

        homeRepository.startSensorStreaming(
            gpsCallback = { sensorDataString ->
                val gpsInfo = buildString {
                    append("GPS: ${sensorDataString.value}")
                    append("\nSysTS: ${sensorDataString.timestamp}")
                    append("\nMonoTS: ${sensorDataString.monoTimestamp}")
                }
                _gpsData.postValue(gpsInfo)
                Log.d("HomeViewModel", "✅ GPS 데이터 UI 업데이트: ${sensorDataString.value}")
                updateSyncStatus()
            },
            imuCallback = { sensorDataString ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastImuUpdateTime >= IMU_UPDATE_INTERVAL_MS) {
                    val imuInfo = buildString {
                        append("IMU: ${sensorDataString.value}")
                        append("\nSysTS: ${sensorDataString.timestamp}")
                        append("\nMonoTS: ${sensorDataString.monoTimestamp}")
                    }
                    _imuData.postValue(imuInfo)
                    lastImuUpdateTime = currentTime
                    Log.d("HomeViewModel", "✅ IMU 데이터 UI 업데이트")
                    updateSyncStatus()
                }
            },
            gnssCallback = { sensorDataString ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastGnssUpdateTime >= 1000) {
                    val gnssInfo = "GNSS: ${sensorDataString.value}"
                    _gnssData.postValue(gnssInfo)
                    lastGnssUpdateTime = currentTime
                    Log.d("HomeViewModel", "✅ GNSS 데이터 UI 업데이트: ${sensorDataString.value}")
                    updateSyncStatus()
                }
            },
            detectionCallback = { boundingBoxes, inferenceTime, frameId ->
                Log.d(TAG, "🎯🎯🎯 ViewModel Detection 콜백 수신: frameId=$frameId, boxes=${boundingBoxes.size}, inference=${inferenceTime}ms")

                if (boundingBoxes.isNotEmpty()) {
                    Log.d(TAG, "🎯 ViewModel에서 처리할 객체들: ${boundingBoxes.map { "${it.clsName}(conf=${it.cnf})" }}")
                    boundingBoxes.forEachIndexed { index, bbox ->
                        Log.d(TAG, "🎯 BBOX $index: ${bbox.clsName} at (${bbox.x1}, ${bbox.y1}) - (${bbox.x2}, ${bbox.y2})")
                    }
                } else {
                    Log.d(TAG, "🎯 ViewModel: 감지된 객체 없음")
                }

                // ✅ 즉시 메인 스레드에서 UI 업데이트
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    try {
                        _boundingBoxes.value = boundingBoxes  // postValue 대신 value 사용
                        _inferenceTime.value = "${inferenceTime}ms"
                        Log.d(TAG, "🎯 UI 업데이트 완료: ${boundingBoxes.size}개 바운딩박스")
                    } catch (e: Exception) {
                        Log.e(TAG, "🎯 UI 업데이트 실패: ${e.message}", e)
                    }
                }

                onNewInference(inferenceTime)
            }
        )
        Log.d("HomeViewModel", "✅ 센서 스트리밍 시작 완료")
    }

    /**
     * ✅ 센서 스트리밍 중지 (백그라운드 센서) - 독립적으로 동작
     */
    fun stopSensorStreaming() {
        if (!isSensorStreamingStarted) {
            Log.d("HomeViewModel", "Sensor streaming not started")
            return
        }

        Log.d("HomeViewModel", "🛑 센서 스트리밍 중지")
        homeRepository.stopSensorStreaming()
        isSensorStreamingStarted = false
        _isSensorStreaming.postValue(false)

        // ✅ 센서 데이터 UI 초기화
        _gpsData.postValue("GPS: 대기 중")
        _gnssData.postValue("GNSS: 대기 중")
        _imuData.postValue("IMU: 대기 중")
        _syncStatus.postValue("동기화 중지됨")

        // ✅ Detection 관련 데이터 초기화
        synchronized(boundingBoxMap) { boundingBoxMap.clear() }
        lastGnssUpdateTime = 0L
        lastImuUpdateTime = 0L
        Log.d("HomeViewModel", "✅ 센서 스트리밍 중지 완료")
    }

    /**
     * ✅ 카메라 스트리밍 시작 (UI 버튼용) - 독립적으로 동작
     */
    private fun startCameraStreaming() {
        if (_isStreaming.value == true) {
            Log.d(TAG, "Camera streaming already active")
            return
        }

        Log.d(TAG, "📹 카메라 스트리밍 시작")
        _text.value = "카메라 스트리밍 중..."

        // ✅ 핵심 수정: Detection 콜백을 Repository에 설정
        homeRepository.detectionCallback = { boundingBoxes, inferenceTime, frameId ->
            Log.d(TAG, "🎯🎯🎯 ViewModel Detection 콜백 수신: frameId=$frameId, boxes=${boundingBoxes.size}, inference=${inferenceTime}ms")

            if (boundingBoxes.isNotEmpty()) {
                Log.d(TAG, "🎯 ViewModel에서 처리할 객체들: ${boundingBoxes.map { "${it.clsName}(conf=${it.cnf})" }}")
                boundingBoxes.forEachIndexed { index, bbox ->
                    Log.d(TAG, "🎯 UI BBOX $index: ${bbox.clsName} at (${bbox.x1}, ${bbox.y1}) - (${bbox.x2}, ${bbox.y2})")
                }
            } else {
                Log.d(TAG, "🎯 ViewModel: 감지된 객체 없음")
            }

            // ✅ 메인 스레드에서 즉시 UI 업데이트
            viewModelScope.launch(Dispatchers.Main.immediate) {
                try {
                    _boundingBoxes.value = boundingBoxes
                    _inferenceTime.value = "${inferenceTime}ms"
                    Log.d(TAG, "🎯 UI 업데이트 완료: ${boundingBoxes.size}개 바운딩박스, ${inferenceTime}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "🎯 UI 업데이트 실패: ${e.message}", e)
                }
            }

            onNewInference(inferenceTime)
        }

        // ✅ 카메라 스트리밍 시작
        homeRepository.startCameraStreaming()

        // ✅ 이전 Job 취소
        isCameraStreamingJob?.cancel()

        // ✅ 새로운 Flow 구독
        isCameraStreamingJob = viewModelScope.launch {
            Log.d(TAG, "✅ Camera Flow 구독 시작...")
            try {
                homeRepository.cameraStreamFlow.collect { sensorData ->
                    if (sensorData != null) {
                        // ✅ 카메라 프레임 즉시 업데이트
                        _cameraFrame.postValue(sensorData.bitmap)
                        Log.d(TAG, "✅ Camera frame received: frameId=${sensorData.frameId}, bitmap=${sensorData.bitmap != null}")
                    } else {
                        Log.w(TAG, "⚠️ Received null sensor data from camera flow")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Camera Flow 구독 오류: ${e.message}", e)
                _text.postValue("카메라 스트리밍 오류: ${e.message}")
            }
        }
    }

    /**
     * ✅ 카메라 스트리밍 중지 (UI 버튼용)
     */
    private fun stopCameraStreaming() {
        if (_isStreaming.value != true) {
            Log.d("HomeViewModel", "No camera streaming to stop")
            return
        }

        Log.d("HomeViewModel", "📹 카메라 스트리밍 중지")

        // ✅ Flow 구독 취소
        isCameraStreamingJob?.cancel()
        isCameraStreamingJob = null

        homeRepository.stopCameraStreaming()
        _text.value = "카메라 스트리밍 중지됨"

        // ✅ 카메라 관련 UI만 초기화
        _cameraFrame.postValue(null)
        _boundingBoxes.postValue(emptyList())
        _inferenceTime.postValue("0ms")

        // ✅ Detection 맵만 초기화 (센서 데이터는 유지)
        synchronized(boundingBoxMap) { boundingBoxMap.clear() }
    }

    /**
     * ✅ 스트리밍 토글 (카메라만) - 센서 스트리밍과 독립적
     */
    suspend fun toggleStreaming(context: Context) {
        if (_isStreaming.value == true) {
            stopCameraStreaming()
            _isStreaming.value = false
            // ✅ 카메라 중지 시 서버 스트리밍도 중지
            setServerStreamingEnabled(context, false)
            Log.d("HomeViewModel", "Camera streaming stopped")
        } else {
            startCameraStreaming()
            _isStreaming.value = true
            Log.d("HomeViewModel", "Camera streaming started")
        }
    }

    /**
     * ✅ Detection UI 업데이트 최적화
     */
    private fun checkAndUpdateDetectionUI(frameId: Long) {
        viewModelScope.launch {
            synchronized(boundingBoxMap) {
                if (boundingBoxMap.containsKey(frameId)) {
                    val (boxes, inferenceTime) = boundingBoxMap[frameId]!!

                    // ✅ UI 업데이트
                    _boundingBoxes.postValue(boxes)
                    _inferenceTime.postValue("${inferenceTime}ms")

                    Log.d("HomeViewModel", "🎯 UI 업데이트 완료: frameId=$frameId, boxes=${boxes.size}, inference=${inferenceTime}ms")

                    // ✅ 사용된 데이터 제거
                    boundingBoxMap.remove(frameId)

                    // ✅ 오래된 데이터 정리 (메모리 누수 방지)
                    val currentTime = System.currentTimeMillis()
                    boundingBoxMap.entries.removeIf { (fId, _) ->
                        currentTime - fId > 5000L // 5초 이상 된 데이터 제거
                    }
                }
            }
        }
    }


    /**
     * ✅ 프레임 캡처 (단일 프레임) - 기존 스트리밍과 독립적
     */
    suspend fun fetchCameraData() {
        if (_isStreaming.value == true) {
            _text.postValue("프레임 저장 중...")
        } else {
            _text.postValue("프레임 캡처 중...")
        }

        val sensorData = homeRepository.collectNewSensorData()
        _cameraFrame.postValue(sensorData?.bitmap)

        if (sensorData != null) {
            if (_isStreaming.value == true) {
                _text.postValue("프레임 저장 성공: ${sensorData.value}")
            } else {
                _text.postValue("프레임 캡처 성공")
            }
        } else {
            if (_isStreaming.value == true) {
                _text.postValue("프레임 저장 실패")
            } else {
                _text.postValue("프레임 캡처 실패")
            }
        }
    }

    /**
     * 로그 저장 토글 (Repository를 통해 관리)
     */
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

    /**
     * 서버 스트리밍 설정 (Repository를 통해 관리)
     */
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

    /**
     * HTTP 스트리밍 설정 (Company Streaming)
     */
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
        Log.d("HomeViewModel", "🧹 ViewModel 정리 시작")

        // ✅ 카메라 스트리밍 정지
        isCameraStreamingJob?.cancel()
        homeRepository.stopCameraStreaming()

        // ✅ 센서 스트리밍 정지
        stopSensorStreaming()

        // ✅ 리소스 정리
        sensorCollector.closeCamera()
        _isStreaming.value = false

        Log.d("HomeViewModel", "✅ ViewModel 정리 완료")
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

    /**
     * 동기화 상태 업데이트
     */
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
}
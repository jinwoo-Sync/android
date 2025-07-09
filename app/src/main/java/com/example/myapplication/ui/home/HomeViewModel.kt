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
import kotlinx.coroutines.launch

class HomeViewModel(
    private val sensorCollector: SensorCollector,
    private val homeRepository: HomeRepository
) : ViewModel() {
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

    fun setServerTransmissionEnabled(enabled: Boolean) {
        _isServerTransmissionEnabled.postValue(enabled)
    }

    /**
     * 센서 스트리밍 시작 (Repository를 통해 관리)
     */
    fun startSensorStreaming() {
        homeRepository.startSensorStreaming(
            gpsCallback = { sensorDataString ->
                val gpsInfo = buildString {
                    append(sensorDataString.value)
                    append(", SysTS: ${sensorDataString.timestamp}, MonoTS: ${sensorDataString.monoTimestamp}")
                }
                _gpsData.postValue(gpsInfo)
                updateSyncStatus()
            },
            imuCallback = { sensorDataString ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastImuUpdateTime >= IMU_UPDATE_INTERVAL_MS) {
                    _imuData.postValue(
                        """
                        IMU: ${sensorDataString.value}
                        SysTS: ${sensorDataString.timestamp}, MonoTS: ${sensorDataString.monoTimestamp}
                        """.trimIndent()
                    )
                    lastImuUpdateTime = currentTime
                    updateSyncStatus()
                }
            },
            gnssCallback = { sensorDataString ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastGnssUpdateTime >= 1000) {
                    val gnssInfo = "GNSS: ${sensorDataString.value}"
                    _gnssData.postValue(gnssInfo)
                    lastGnssUpdateTime = currentTime
                    updateSyncStatus()
                }
            },
            detectionCallback = { boundingBoxes, inferenceTime, frameId ->
                if (frameId != -1L) {
                    synchronized(boundingBoxMap) {
                        boundingBoxMap[frameId] = Pair(boundingBoxes, inferenceTime)
                        Log.d("HomeViewModel", "Stored bounding boxes for frameId: $frameId, boxes: ${boundingBoxes.size}")
                    }
                }
                onNewInference(inferenceTime)
                checkAndUpdateUI(frameId)
            }
        )
        Log.d("HomeViewModel", "Sensor streaming started via Repository")
    }

    /**
     * 센서 스트리밍 중지 (Repository를 통해 관리)
     */
    fun stopSensorStreaming() {
        homeRepository.stopSensorStreaming()
        _gpsData.postValue("GPS: 대기 중")
        _gnssData.postValue("GNSS: 대기 중")
        _imuData.postValue("IMU: 대기 중")
        _boundingBoxes.postValue(emptyList())
        _inferenceTime.postValue("0ms")
        _cameraFrame.postValue(null)
        synchronized(boundingBoxMap) { boundingBoxMap.clear() }
        lastGnssUpdateTime = 0L
        lastImuUpdateTime = 0L
        _syncStatus.postValue("동기화 중지됨")
        Log.d("HomeViewModel", "Sensor streaming stopped via Repository")
    }

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

    private fun startCameraStreaming() {
        if (_isStreaming.value == true) {
            Log.d("HomeViewModel", "Streaming already active")
            return
        }

        _text.value = "카메라 스트리밍 중..."
        homeRepository.startCameraStreaming()

        viewModelScope.launch {
            homeRepository.cameraStreamFlow.collect { sensorData ->
                sensorData?.let {
                    _cameraFrame.postValue(it.bitmap)
                    checkAndUpdateUI(it.frameId)
                }
            }
        }
    }

    private fun checkAndUpdateUI(frameId: Long) {
        synchronized(boundingBoxMap) {
            if (boundingBoxMap.containsKey(frameId) && _cameraFrame.value != null) {
                val (boxes, inferenceTime) = boundingBoxMap[frameId]!!
                _boundingBoxes.postValue(boxes)
                _inferenceTime.postValue("${inferenceTime}ms")
                Log.d("HomeViewModel", "UI updated for frameId: $frameId, boxes: ${boxes.size}")
                boundingBoxMap.remove(frameId)
            }
        }
    }

    private fun stopCameraStreaming() {
        if (_isStreaming.value != true) {
            Log.d("HomeViewModel", "No streaming to stop")
            return
        }

        homeRepository.stopCameraStreaming()
        _text.value = "카메라 스트리밍 중지됨"
        _cameraFrame.postValue(null)
        _boundingBoxes.postValue(emptyList())
        _inferenceTime.postValue("0ms")
        synchronized(boundingBoxMap) { boundingBoxMap.clear() }
    }

    suspend fun toggleStreaming(context: Context) {
        if (_isStreaming.value == true) {
            stopCameraStreaming()
            stopSensorStreaming()
            _isStreaming.value = false
            setServerStreamingEnabled(context, false)
            Log.d("HomeViewModel", "All streaming stopped")
        } else {
            startCameraStreaming()
            startSensorStreaming()
            _isStreaming.value = true
            Log.d("HomeViewModel", "All streaming started")
        }
    }

    /**
     * 로그 저장 토글 (Repository를 통해 관리)
     */
    fun toggleLogSaving(context: Context, enabled: Boolean) {
        try {
            homeRepository.toggleLogSaving(context, enabled)
            _text.postValue(if (enabled) "실시간 로깅 시작" else "실시간 로깅 중지")
            Log.d("HomeViewModel", "Log saving toggled via Repository: $enabled")
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
            Log.d("HomeViewModel", "Server streaming set via Repository: $enabled")
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
            Log.d("HomeViewModel", "HTTP streaming set via Repository: $enabled")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to set HTTP streaming: ${e.message}", e)
            _text.postValue("HTTP 스트리밍 설정 실패: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        homeRepository.stopCameraStreaming()
        sensorCollector.closeCamera()
        stopSensorStreaming()
        _isStreaming.value = false
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
            Log.d("HomeViewModel", "Frame skip interval set to $effectiveInterval (avg: $avg) via Repository")
        }
    }

    fun setUserFrameSkipInterval(interval: Int) {
        if (interval in 2..15) {
            userFrameSkipInterval = interval
            homeRepository.setFrameSkipInterval(interval)
            currentSkipInterval = interval
            _effectiveInterval.postValue(interval)
            Log.d("HomeViewModel", "User set frame skip interval to $interval via Repository")
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
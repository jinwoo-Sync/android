package com.example.myapplication.data.repository

import android.content.Context
import com.example.myapplication.data.sensor.SensorCollector
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.model.SensorData
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.example.myapplication.learning.yolo.BoundingBox
import com.example.myapplication.data.logging.LoggerManager

class HomeRepository(
    private val sensorCollector: SensorCollector,
    private val dataSynchronizer: DataSynchronizer,
) {
    private val TAG = "HomeRepository"

    private val _cameraStreamFlow = MutableStateFlow<SensorData?>(null)
    val cameraStreamFlow: StateFlow<SensorData?> = _cameraStreamFlow

    private var isStreamingActive = false

    suspend fun getDefaultSensorData(): List<SensorData> {
        val dataList = mutableListOf<SensorData>()
        repeat(5) {
            val sensorData = collectSensorDataAsync()
            sensorData?.let { dataList.add(it) }
        }
        return if (dataList.isNotEmpty()) {
            dataSynchronizer.synchronizeData(dataList)
        } else {
            emptyList()
        }
    }

    fun getDefaultImageResId(): Int {
        return com.example.myapplication.R.drawable.ic_launcher_foreground
    }

    suspend fun collectNewSensorData(): SensorData? {
        Log.d(TAG, "Collecting new sensor data")
        val newData = collectSensorDataAsync() ?: return null
        val syncedData = dataSynchronizer.synchronizeData(listOf(newData))
        return syncedData.firstOrNull()
    }

    fun startCameraStreaming() {
        if (isStreamingActive) {
            Log.d(TAG, "Camera streaming already active")
            return
        }

        isStreamingActive = true
        Log.d(TAG, "Starting camera streaming")

        sensorCollector.startCameraStreaming(
            callback = { sensorData ->
                sensorData?.let {
                    _cameraStreamFlow.value = it
                    //Log.d(TAG, "New frame received at ${it.timestamp}, frameId: ${it.frameId}")
                }
            },
            detectionCallback = { boundingBoxes, inferenceTime, frameId ->
                Log.d(TAG, "Detection result for frameId: $frameId, boxes: ${boundingBoxes.size}, time: $inferenceTime ms")
            }
        )
    }

    fun stopCameraStreaming() {
        if (!isStreamingActive) {
            Log.d(TAG, "No camera streaming to stop")
            return
        }

        sensorCollector.stopCameraStreaming()
        isStreamingActive = false
        _cameraStreamFlow.value = null
        Log.d(TAG, "Camera streaming stopped")
    }

    /**
     * 로그 저장 기능 토글
     * Repository 레이어에서 LoggerManager 접근 관리
     */
    fun toggleLogSaving(context: Context, enabled: Boolean) {
        try {
            val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)
            if (enabled) {
                loggerManager.enableLogSaving()
                Log.d(TAG, "로그 저장 활성화")
            } else {
                loggerManager.disableLogSaving()
                Log.d(TAG, "로그 저장 비활성화")
            }
        } catch (e: Exception) {
            Log.e(TAG, "로그 저장 토글 실패: ${e.message}", e)
        }
    }

    /**
     * 서버 스트리밍 기능 설정
     * Repository 레이어에서 LoggerManager와 SensorCollector 접근 관리
     */
    suspend fun setServerStreamingEnabled(context: Context, enabled: Boolean) {
        try {
            val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)

            if (enabled) {
                // WebSocket 전송 방식 설정
                loggerManager.setTransportType("websocket")
                loggerManager.enableStreaming()
                Log.d(TAG, "서버 스트리밍 활성화 - WebSocket")
            } else {
                loggerManager.disableStreaming()
                Log.d(TAG, "서버 스트리밍 비활성화")
            }
        } catch (e: Exception) {
            Log.e(TAG, "서버 스트리밍 설정 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * HTTP 방식 서버 스트리밍 설정 (Company Streaming)
     */
    suspend fun setHttpStreamingEnabled(context: Context, enabled: Boolean) {
        try {
            val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)

            if (enabled) {
                // HTTP 전송 방식 설정
                loggerManager.setTransportType("http")
                loggerManager.enableStreaming()
                Log.d(TAG, "서버 스트리밍 활성화 - HTTP")
            } else {
                loggerManager.disableStreaming()
                Log.d(TAG, "HTTP 스트리밍 비활성화")
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 스트리밍 설정 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * 센서 데이터 스트리밍 시작
     * SensorCollector를 통한 센서 데이터 수집 관리
     */
    fun startSensorStreaming(
        gpsCallback: ((com.example.myapplication.model.SensorData_String) -> Unit)? = null,
        imuCallback: ((com.example.myapplication.model.SensorData_String) -> Unit)? = null,
        gnssCallback: ((com.example.myapplication.model.SensorData_String) -> Unit)? = null,
        detectionCallback: ((List<BoundingBox>, Long, Long) -> Unit)? = null
    ) {
        sensorCollector.startSensorStreaming(
            gpsCallback = gpsCallback,
            imuCallback = imuCallback,
            gnssCallback = gnssCallback,
            detectionCallback = detectionCallback
        )
        Log.d(TAG, "센서 스트리밍 시작")
    }

    /**
     * 센서 데이터 스트리밍 중지
     */
    fun stopSensorStreaming() {
        sensorCollector.stopSensorStreaming()
        Log.d(TAG, "센서 스트리밍 중지")
    }

    /**
     * 프레임 스킵 간격 설정
     */
    fun setFrameSkipInterval(interval: Int) {
        sensorCollector.setFrameSkipInterval(interval)
        Log.d(TAG, "프레임 스킵 간격 설정: $interval")
    }

    /**
     * 동기화 상태 정보 조회
     */
    fun getSyncStatus(): String {
        return buildString {
            val gpsStatus = dataSynchronizer.getGpsStatus()
            val queueStatus = dataSynchronizer.getQueueStatus()

            append("=== 데이터 동기화 상태 ===\n")
            append("GPS 사용 가능: ${gpsStatus.isGpsAvailable}\n")
            append("마지막 GPS 시간: ${gpsStatus.lastGpsTime}\n")
            append("GPS 오프셋: ${gpsStatus.gpsMonoOffset}ms\n")
            append("GPS 큐 크기: ${queueStatus.gpsQueueSize}\n")
            append("IMU 큐 크기: ${queueStatus.imuQueueSize}\n")
            append("GNSS 큐 크기: ${queueStatus.gnssQueueSize}\n")
            append("카메라 큐 크기: ${queueStatus.cameraQueueSize}\n")
            append("총 데이터 포인트: ${queueStatus.totalDataPoints}")
        }
    }

    private suspend fun collectSensorDataAsync(): SensorData? = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "Collecting sensor data asynchronously")
        sensorCollector.collectCameraData { sensorData ->
            if (continuation.isActive) {
                Log.d(TAG, "Received sensor data: $sensorData")
                continuation.resume(sensorData)
            } else {
                Log.w(TAG, "Continuation is not active, ignoring sensor data: $sensorData")
            }
        }

        continuation.invokeOnCancellation {
            Log.d(TAG, "Coroutine cancelled, closing camera")
            sensorCollector.closeCamera()
        }
    }
}
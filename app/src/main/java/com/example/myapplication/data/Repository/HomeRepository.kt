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
import com.example.myapplication.utils.BitmapPoolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeRepository(
    private val context: Context,
    private val sensorCollector: SensorCollector,
    private val dataSynchronizer: DataSynchronizer,
    private val bitmapPoolManager: BitmapPoolManager  // ✅ BitmapPoolManager 주입
) {
    private val TAG = "HomeRepository"

    private val _cameraStreamFlow = MutableStateFlow<SensorData?>(null)
    val cameraStreamFlow: StateFlow<SensorData?> = _cameraStreamFlow

    init {
        // 10초마다 백업 정리
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(10000)
                try {
                    bitmapPoolManager.requestPoolCleanup()
                    Log.d(TAG, "🧹 Repository 백업 풀 정리 완료")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Repository 백업 풀 정리 실패: ${e.message}", e)
                }
            }
        }
    }

    private var isStreamingActive = false

    // ✅ Detection 콜백 변수 추가
    var detectionCallback: ((List<BoundingBox>, Long, Long) -> Unit)? = null

    suspend fun getDefaultSensorData(): List<SensorData> {
        val dataList = mutableListOf<SensorData>()
        repeat(5) {
            val sensorData = collectSensorDataAsync()
            sensorData?.let { dataList.add(it) }
        }
        return if (dataList.isNotEmpty()) {
            val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)
            loggerManager.getSynchronizedData(dataList)
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
        val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)
        val syncedData = loggerManager.getSynchronizedData(listOf(newData))
        return syncedData.firstOrNull()
    }

    /**
     * 🎯 비트맵 풀 강제 정리 (UI 요청) - BitmapPoolManager 사용
     */
    fun requestPoolCleanup() {
        try {
            // ✅ BitmapPoolManager를 통한 풀 정리
            bitmapPoolManager.requestPoolCleanup()

            // ✅ SensorCollector의 개별 정리도 수행
            sensorCollector.requestPoolCleanup()

            Log.d(TAG, "🧹 Repository: 전체 비트맵 풀 정리 요청 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Repository: 비트맵 풀 정리 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * 🎯 풀 상세 상태 조회 (UI 요청) - BitmapPoolManager 사용
     */
    fun getPoolDetailedStatus(): String {
        return try {
            val managerStatus = bitmapPoolManager.getPoolDetailedStatus()
            val sensorCollectorStatus = sensorCollector.getPoolDetailedStatus()

            buildString {
                appendLine("=== 전체 비트맵 풀 상태 ===")
                appendLine(managerStatus)
                appendLine()
                appendLine("=== SensorCollector 추가 상태 ===")
                appendLine(sensorCollectorStatus)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Repository: 풀 상태 조회 실패: ${e.message}", e)
            "풀 상태 조회 실패: ${e.message}"
        }
    }

    /**
     * 🚨 응급 복구 (심각한 상황용)
     */
    fun performEmergencyPoolReset() {
        try {
            Log.w(TAG, "🚨 Repository: 응급 풀 복구 시작")
            bitmapPoolManager.performEmergencyReset()
            Log.w(TAG, "✅ Repository: 응급 풀 복구 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Repository: 응급 풀 복구 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * ✅ 카메라 스트리밍 시작 - Raw 비트맵 그대로 전달
     */
    fun startCameraStreaming() {
        if (isStreamingActive) {
            Log.d(TAG, "Camera streaming already active")
            return
        }

        isStreamingActive = true
        Log.d(TAG, "Starting camera streaming with Advanced Tagged Pool separation")

        sensorCollector.startCameraStreaming(
            callback = { sensorData ->
                // 🎯 Raw 비트맵을 그대로 전달 (복사는 HomeFragment에서 처리)
                if (sensorData?.bitmap != null &&
                    !sensorData.bitmap.isRecycled &&
                    sensorData.bitmap.width > 0 &&
                    sensorData.bitmap.height > 0) {

                    _cameraStreamFlow.value = sensorData
                    Log.d(TAG, "✅ Raw frame forwarded to UI: frameId=${sensorData.frameId}, size=${sensorData.bitmap.width}x${sensorData.bitmap.height}")
                } else {
                    Log.w(TAG, "⚠️ Invalid raw frame filtered out: bitmap=${sensorData?.bitmap}, recycled=${sensorData?.bitmap?.isRecycled}")
                    // null을 보내지 않고 그냥 무시
                }
            },
            detectionCallback = { boundingBoxes, inferenceTime, frameId ->
                Log.d(TAG, "🎯 Repository Detection 콜백 수신: frameId=$frameId, boxes=${boundingBoxes.size}, time=${inferenceTime}ms")

                if (boundingBoxes.isNotEmpty()) {
                    Log.d(TAG, "🎯 Repository에서 받은 객체들: ${boundingBoxes.map { "${it.clsName}(${it.cnf})" }}")
                }

                // ✅ ViewModel로 즉시 전달
                detectionCallback?.invoke(boundingBoxes, inferenceTime, frameId)
            }
        )
    }

    /**
     * ✅ 카메라 스트리밍 중지 - UI 버튼용
     */
    fun stopCameraStreaming() {
        if (!isStreamingActive) {
            Log.d(TAG, "No camera streaming to stop")
            return
        }

        sensorCollector.stopCameraStreaming()
        isStreamingActive = false
        _cameraStreamFlow.value = null
        Log.d(TAG, "Camera streaming stopped with cleanup")
    }

    /**
     * ✅ 센서 데이터 스트리밍 시작 - 백그라운드 센서용 (GPS, IMU, GNSS)
     */
    fun startSensorStreaming(
        gpsCallback: ((com.example.myapplication.model.SensorData_String) -> Unit)? = null,
        imuCallback: ((com.example.myapplication.model.SensorData_String) -> Unit)? = null,
        gnssCallback: ((com.example.myapplication.model.SensorData_String) -> Unit)? = null,
        detectionCallback: ((List<BoundingBox>, Long, Long) -> Unit)? = null
    ) {
        Log.d(TAG, "🎯 Repository startSensorStreaming - detectionCallback: ${detectionCallback != null}")

        sensorCollector.startSensorStreaming(
            gpsCallback = gpsCallback,
            imuCallback = imuCallback,
            gnssCallback = gnssCallback,
            detectionCallback = { boundingBoxes, inferenceTime, frameId ->
                Log.d(TAG, "🎯 Repository Detection 콜백 수신: frameId=$frameId, boxes=${boundingBoxes.size}, time=${inferenceTime}ms")

                if (boundingBoxes.isNotEmpty()) {
                    Log.d(TAG, "🎯 Repository에서 받은 객체들: ${boundingBoxes.map { "${it.clsName}(${it.cnf})" }}")
                }

                // ✅ ViewModel로 콜백 전달
                Log.d(TAG, "🎯 ViewModel로 콜백 전달 시작 - detectionCallback: ${detectionCallback != null}")
                detectionCallback?.invoke(boundingBoxes, inferenceTime, frameId)
                Log.d(TAG, "🎯 ViewModel로 콜백 전달 완료")
            }
        )
        Log.d(TAG, "✅ 모든 센서 스트리밍 시작 완료")
    }

    /**
     * ✅ 센서 데이터 스트리밍 중지 - 백그라운드 센서용
     */
    fun stopSensorStreaming() {
        sensorCollector.stopSensorStreaming()
        Log.d(TAG, "센서 스트리밍 중지")
    }

    /**
     * 로그 저장 기능 토글
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

    // ✅ 비디오 상태 모니터링
    fun getVideoStatus(): String {
        return try {
            val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)
            loggerManager.getVideoStatus()
        } catch (e: Exception) {
            Log.e(TAG, "비디오 상태 조회 실패: ${e.message}", e)
            "비디오 상태 조회 실패: ${e.message}"
        }
    }

    // ✅ 완전한 시스템 상태
    fun getCompleteSystemStatus(): String {
        return buildString {
            appendLine("=== 전체 시스템 상태 ===")
            appendLine(getSyncStatus())
            appendLine()
            appendLine(getVideoStatus())
            appendLine()
            appendLine("=== BitmapPool 상태 ===")
            appendLine(getPoolDetailedStatus())
            appendLine()

            try {
                val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)
                appendLine(loggerManager.getSystemStatus())
            } catch (e: Exception) {
                appendLine("시스템 상태 조회 실패: ${e.message}")
            }
        }
    }

    /**
     * 서버 스트리밍 기능 설정 (WebSocket)
     */
    suspend fun setServerStreamingEnabled(context: Context, enabled: Boolean) {
        try {
            val loggerManager = LoggerManager.getInstance(context, dataSynchronizer)

            if (enabled) {
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
            append("마지막 GPS 시간: ${gpsStatus.lastGpsUpdateTime}\n")
            append("GPS 오프셋: ${gpsStatus.gpsTimeoutDuration}ms\n")
            append("GPS 큐 크기: ${queueStatus.gpsQueueSize}\n")
            append("IMU 큐 크기: ${queueStatus.imuQueueSize}\n")
            append("GNSS 큐 크기: ${queueStatus.gnssQueueSize}\n")
            append("카메라 큐 크기: ${queueStatus.cameraQueueSize}\n")
            append("총 데이터 포인트: ${queueStatus.totalDataPoints}")
        }
    }

    /**
     * ✅ 스트리밍 상태 확인
     */
    fun isStreamingActive(): Boolean = isStreamingActive

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
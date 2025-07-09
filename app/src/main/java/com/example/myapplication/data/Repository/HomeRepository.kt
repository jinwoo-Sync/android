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

    fun toggleLogSaving(context: Context, enabled: Boolean) {
        if (enabled) {
            LoggerManager.getInstance(context, dataSynchronizer).enableLogSaving() // dataSynchronizer 전달
        } else {
            LoggerManager.getInstance(context, dataSynchronizer).disableLogSaving()
        }
    }

    suspend fun setServerStreamingEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            LoggerManager.getInstance(context, dataSynchronizer).setTransportType("websocket")
            LoggerManager.getInstance(context, dataSynchronizer).enableStreaming()
        } else {
            LoggerManager.getInstance(context, dataSynchronizer).disableStreaming()
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
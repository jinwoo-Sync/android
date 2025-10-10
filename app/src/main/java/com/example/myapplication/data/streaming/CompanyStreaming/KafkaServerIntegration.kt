package com.example.myapplication.data.streaming.CompanyStreaming

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.util.Log
import com.example.myapplication.data.api.*
import com.example.myapplication.model.BoundingBoxLog
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Kafka Server Integration for CompanyStreaming
 * Handles data transmission to kafka_server alongside existing company server
 */
class KafkaServerIntegration(
    private val context: Context,
    private val authManager: AuthManager
) {
    private val TAG = "KafkaServerIntegration"
    private val transmitter = SensorDataTransmitter(context, authManager)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Data buffers for batching
    private val gpsBuffer = ConcurrentLinkedQueue<Location>()
    private val imuBuffer = ConcurrentLinkedQueue<Triple<FloatArray, FloatArray, FloatArray>>()
    private val yoloBuffer = ConcurrentLinkedQueue<BoundingBoxLog>()

    private var currentFrameId = 0L
    private var isActive = false
    private var batchingJob: Job? = null

    // Camera intrinsics (cached)
    private var cameraIntrinsics: CameraIntrinsics? = null

    companion object {
        private const val BATCH_INTERVAL_MS = 1000L  // 1초마다 전송
        private const val MAX_GPS_BUFFER = 10
        private const val MAX_IMU_BUFFER = 50
        private const val MAX_YOLO_BUFFER = 20
    }

    /**
     * Start Kafka server session and data transmission
     */
    suspend fun start() {
        if (isActive) {
            Log.d(TAG, "Already active")
            return
        }

        // Extract camera intrinsics
        extractCameraIntrinsics()

        // Start session with kafka server
        val sessionResult = transmitter.startSession()
        if (sessionResult.isSuccess) {
            isActive = true
            startBatchingLoop()
            Log.i(TAG, "Kafka server session started: ${sessionResult.getOrNull()}")
        } else {
            Log.e(TAG, "Failed to start kafka server session: ${sessionResult.exceptionOrNull()}")
        }
    }

    /**
     * Stop Kafka server session
     */
    suspend fun stop() {
        if (!isActive) {
            return
        }

        isActive = false
        batchingJob?.cancel()
        batchingJob = null

        // Send remaining data
        sendBufferedData(null, null)

        // End session
        val endResult = transmitter.endSession()
        if (endResult.isSuccess) {
            Log.i(TAG, "Kafka server session ended successfully")
        } else {
            Log.e(TAG, "Failed to end kafka server session: ${endResult.exceptionOrNull()}")
        }

        // Clear buffers
        gpsBuffer.clear()
        imuBuffer.clear()
        yoloBuffer.clear()
    }

    /**
     * Add GPS data to buffer
     */
    fun addGpsData(location: Location) {
        if (!isActive) return

        gpsBuffer.offer(location)
        // Maintain buffer size
        while (gpsBuffer.size > MAX_GPS_BUFFER) {
            gpsBuffer.poll()
        }
    }

    /**
     * Add IMU data to buffer
     * @param accel Accelerometer data [x, y, z]
     * @param gyro Gyroscope data [x, y, z]
     * @param mag Magnetometer data [x, y, z]
     */
    fun addImuData(accel: FloatArray, gyro: FloatArray, mag: FloatArray) {
        if (!isActive) return

        imuBuffer.offer(Triple(accel, gyro, mag))
        // Maintain buffer size
        while (imuBuffer.size > MAX_IMU_BUFFER) {
            imuBuffer.poll()
        }
    }

    /**
     * Add YOLO detection data to buffer
     */
    fun addYoloData(detection: BoundingBoxLog) {
        if (!isActive) return

        yoloBuffer.offer(detection)
        // Maintain buffer size
        while (yoloBuffer.size > MAX_YOLO_BUFFER) {
            yoloBuffer.poll()
        }
    }

    /**
     * Send camera frame immediately with buffered sensor data
     */
    suspend fun sendCameraFrame(bitmap: Bitmap) {
        if (!isActive) return

        sendBufferedData(bitmap, cameraIntrinsics)
    }

    /**
     * Start periodic batching loop
     */
    private fun startBatchingLoop() {
        batchingJob = coroutineScope.launch {
            while (isActive) {
                delay(BATCH_INTERVAL_MS)
                // Send sensor data without camera (camera is sent separately when available)
                sendBufferedData(null, null)
            }
        }
    }

    /**
     * Send buffered data to kafka server
     */
    private suspend fun sendBufferedData(bitmap: Bitmap?, intrinsics: CameraIntrinsics?) {
        try {
            // Collect buffered data
            val gpsData = mutableListOf<Location>()
            while (gpsBuffer.isNotEmpty() && gpsData.size < MAX_GPS_BUFFER) {
                gpsBuffer.poll()?.let { gpsData.add(it) }
            }

            val imuData = mutableListOf<Triple<FloatArray, FloatArray, FloatArray>>()
            while (imuBuffer.isNotEmpty() && imuData.size < MAX_IMU_BUFFER) {
                imuBuffer.poll()?.let { imuData.add(it) }
            }

            val yoloData = mutableListOf<BoundingBoxLog>()
            while (yoloBuffer.isNotEmpty() && yoloData.size < MAX_YOLO_BUFFER) {
                yoloBuffer.poll()?.let { yoloData.add(it) }
            }

            // Skip if no data to send
            if (bitmap == null && gpsData.isEmpty() && imuData.isEmpty() && yoloData.isEmpty()) {
                return
            }

            // Send to kafka server
            val result = transmitter.sendSensorDataBatch(
                frameId = currentFrameId++,
                bitmap = bitmap,
                cameraIntrinsics = intrinsics,
                gpsLocations = gpsData.ifEmpty { null },
                imuData = imuData.ifEmpty { null },
                yoloDetections = yoloData.ifEmpty { null }
            )

            if (result.isSuccess) {
                Log.d(TAG, "Data sent - GPS: ${gpsData.size}, IMU: ${imuData.size}, YOLO: ${yoloData.size}, Camera: ${bitmap != null}")
            } else {
                Log.e(TAG, "Failed to send data: ${result.exceptionOrNull()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending buffered data", e)
        }
    }

    /**
     * Extract camera intrinsics from CameraCharacteristics
     */
    private fun extractCameraIntrinsics() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val intrinsicCalibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val distortion = characteristics.get(CameraCharacteristics.LENS_DISTORTION)

            if (intrinsicCalibration != null) {
                cameraIntrinsics = CameraIntrinsics(
                    focalLengthX = intrinsicCalibration.getOrElse(0) { 1000.0f }.toDouble(),
                    focalLengthY = intrinsicCalibration.getOrElse(1) { 1000.0f }.toDouble(),
                    principalPointX = intrinsicCalibration.getOrElse(2) { 960.0f }.toDouble(),
                    principalPointY = intrinsicCalibration.getOrElse(3) { 540.0f }.toDouble(),
                    distortionCoeffs = distortion?.map { it.toDouble() } ?: listOf(0.0, 0.0, 0.0, 0.0, 0.0)
                )
                Log.i(TAG, "Camera intrinsics extracted successfully")
            } else {
                Log.w(TAG, "Camera intrinsics not available, using defaults")
                cameraIntrinsics = CameraIntrinsics(
                    focalLengthX = 1000.0,
                    focalLengthY = 1000.0,
                    principalPointX = 960.0,
                    principalPointY = 540.0,
                    distortionCoeffs = listOf(0.0, 0.0, 0.0, 0.0, 0.0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting camera intrinsics", e)
            // Use default values
            cameraIntrinsics = CameraIntrinsics(
                focalLengthX = 1000.0,
                focalLengthY = 1000.0,
                principalPointX = 960.0,
                principalPointY = 540.0,
                distortionCoeffs = listOf(0.0, 0.0, 0.0, 0.0, 0.0)
            )
        }
    }

    fun isKafkaServerActive(): Boolean = isActive
}

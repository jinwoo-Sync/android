package com.example.myapplication.data.api

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.example.myapplication.model.BoundingBoxLog
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sensor Data Transmitter
 * Converts sensor data from Android models to API models and transmits to Kafka server
 */
class SensorDataTransmitter(
    context: Context,
    private val authManager: AuthManager
) {
    private val api: KafkaServerApi = ApiClient.createApi {
        authManager.getToken()?.let { "Bearer $it" }
    }

    private val deviceId: String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )

    private val deviceInfo: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"

    private var currentSessionId: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        private const val TAG = "SensorDataTransmitter"
        private const val IMAGE_QUALITY = 85  // JPEG quality (0-100)
    }

    // Start a new data collection session
    suspend fun startSession(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = SessionStartRequest(
                deviceId = deviceId,
                deviceInfo = deviceInfo
            )

            val response = api.startSession(
                token = "Bearer ${authManager.getToken()}",
                request = request
            )

            if (response.isSuccessful && response.body()?.success == true) {
                val sessionId = response.body()?.sessionId
                currentSessionId = sessionId
                Log.i(TAG, "Session started: $sessionId")
                Result.success(sessionId ?: "")
            } else {
                val error = response.body()?.error ?: "Failed to start session"
                Log.e(TAG, "Session start failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting session", e)
            Result.failure(e)
        }
    }

    // End current session
    suspend fun endSession(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sessionId = currentSessionId ?: return@withContext Result.failure(
                Exception("No active session")
            )

            val request = SessionEndRequest(sessionId = sessionId)
            val response = api.endSession(
                token = "Bearer ${authManager.getToken()}",
                request = request
            )

            if (response.isSuccessful && response.body()?.success == true) {
                Log.i(TAG, "Session ended: $sessionId")
                currentSessionId = null
                Result.success(Unit)
            } else {
                val error = response.body()?.error ?: "Failed to end session"
                Log.e(TAG, "Session end failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception ending session", e)
            Result.failure(e)
        }
    }

    // Send sensor data batch
    suspend fun sendSensorDataBatch(
        frameId: Long,
        bitmap: Bitmap? = null,
        cameraIntrinsics: CameraIntrinsics? = null,
        gpsLocations: List<Location>? = null,
        imuData: List<Triple<FloatArray, FloatArray, FloatArray>>? = null,  // (accel, gyro, mag)
        yoloDetections: List<BoundingBoxLog>? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sessionId = currentSessionId ?: return@withContext Result.failure(
                Exception("No active session. Call startSession() first.")
            )

            val timestamp = dateFormat.format(Date())

            val batch = SensorDataBatch(
                sessionId = sessionId,
                deviceId = deviceId,
                timestamp = timestamp,
                cameraData = buildCameraData(frameId, bitmap, cameraIntrinsics, timestamp),
                gpsData = buildGPSData(gpsLocations),
                imuData = buildIMUData(imuData),
                yoloData = buildYOLOData(frameId, yoloDetections, timestamp)
            )

            val response = api.sendSensorData(
                token = "Bearer ${authManager.getToken()}",
                batch = batch
            )

            if (response.isSuccessful && response.body()?.success == true) {
                val stats = response.body()?.stats
                Log.i(TAG, "Data sent - Frames: ${stats?.cameraFrames}, GPS: ${stats?.gpsPoints}, IMU: ${stats?.imuSamples}, YOLO: ${stats?.yoloDetections}")
                Result.success(Unit)
            } else {
                val error = response.body()?.error ?: "Failed to send data"
                Log.e(TAG, "Data send failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending data", e)
            Result.failure(e)
        }
    }

    // Convert Bitmap to Base64 string
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    // Build camera data from bitmap and intrinsics
    private fun buildCameraData(
        frameId: Long,
        bitmap: Bitmap?,
        intrinsics: CameraIntrinsics?,
        timestamp: String
    ): CameraData? {
        if (bitmap == null || intrinsics == null) return null

        return CameraData(
            frameId = frameId,
            timestamp = timestamp,
            imageBase64 = bitmapToBase64(bitmap),
            imageFormat = "jpg",
            width = bitmap.width,
            height = bitmap.height,
            intrinsics = intrinsics
        )
    }

    // Build GPS data from Location objects
    private fun buildGPSData(locations: List<Location>?): List<GPSData>? {
        return locations?.map { location ->
            GPSData(
                timestamp = dateFormat.format(Date(location.time)),
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy.toDouble(),
                bearing = location.bearing.toDouble(),
                speed = location.speed.toDouble(),
                provider = location.provider ?: "unknown"
            )
        }
    }

    // Build IMU data
    private fun buildIMUData(
        imuSamples: List<Triple<FloatArray, FloatArray, FloatArray>>?
    ): List<IMUData>? {
        return imuSamples?.map { (accel, gyro, mag) ->
            IMUData(
                timestamp = dateFormat.format(Date()),
                accelerometer = Vector3(
                    x = accel[0].toDouble(),
                    y = accel[1].toDouble(),
                    z = accel[2].toDouble()
                ),
                gyroscope = Vector3(
                    x = gyro[0].toDouble(),
                    y = gyro[1].toDouble(),
                    z = gyro[2].toDouble()
                ),
                magnetometer = Vector3(
                    x = mag[0].toDouble(),
                    y = mag[1].toDouble(),
                    z = mag[2].toDouble()
                )
            )
        }
    }

    // Build YOLO detection data
    private fun buildYOLOData(
        frameId: Long,
        detections: List<BoundingBoxLog>?,
        timestamp: String
    ): List<YOLOData>? {
        if (detections.isNullOrEmpty()) return null

        val yoloDetections = detections.map { detection ->
            YOLODetection(
                boundingBox = BoundingBox(
                    x = detection.x1.toDouble(),
                    y = detection.y1.toDouble(),
                    width = (detection.x2 - detection.x1).toDouble(),
                    height = (detection.y2 - detection.y1).toDouble()
                ),
                className = detection.clsName,
                classId = 0,  // TODO: Add class ID mapping
                confidence = detection.cnf.toDouble()
            )
        }

        return listOf(YOLOData(
            frameId = frameId,
            timestamp = timestamp,
            detections = yoloDetections
        ))
    }
}

package com.example.myapplication.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * API Data Models for Kafka Server Communication
 * Matches Go server models in kafka_server/services/go-server/sensor_models.go
 */

// ========== Request/Response Models ==========

@JsonClass(generateAdapter = true)
data class SessionStartRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_info") val deviceInfo: String? = null
)

@JsonClass(generateAdapter = true)
data class SessionEndRequest(
    @Json(name = "session_id") val sessionId: String
)

@JsonClass(generateAdapter = true)
data class SensorDataBatch(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "timestamp") val timestamp: String,  // ISO 8601 format
    @Json(name = "camera_data") val cameraData: CameraData? = null,
    @Json(name = "gps_data") val gpsData: List<GPSData>? = null,
    @Json(name = "imu_data") val imuData: List<IMUData>? = null,
    @Json(name = "yolo_data") val yoloData: List<YOLOData>? = null
)

@JsonClass(generateAdapter = true)
data class SensorDataResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "session_id") val sessionId: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "stats") val stats: DataStats? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class DataStats(
    @Json(name = "camera_frames") val cameraFrames: Int,
    @Json(name = "gps_points") val gpsPoints: Int,
    @Json(name = "imu_samples") val imuSamples: Int,
    @Json(name = "yolo_detections") val yoloDetections: Int
)

// ========== Camera Data Models ==========

@JsonClass(generateAdapter = true)
data class CameraData(
    @Json(name = "frame_id") val frameId: Long,
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "image_base64") val imageBase64: String,
    @Json(name = "image_format") val imageFormat: String,  // "jpg", "png"
    @Json(name = "width") val width: Int,
    @Json(name = "height") val height: Int,
    @Json(name = "intrinsics") val intrinsics: CameraIntrinsics
)

@JsonClass(generateAdapter = true)
data class CameraIntrinsics(
    @Json(name = "focal_length_x") val focalLengthX: Double,
    @Json(name = "focal_length_y") val focalLengthY: Double,
    @Json(name = "principal_point_x") val principalPointX: Double,
    @Json(name = "principal_point_y") val principalPointY: Double,
    @Json(name = "distortion_coeffs") val distortionCoeffs: List<Double>  // [k1, k2, p1, p2, k3]
)

// ========== GPS Data Models ==========

@JsonClass(generateAdapter = true)
data class GPSData(
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "altitude") val altitude: Double,
    @Json(name = "accuracy") val accuracy: Double,
    @Json(name = "bearing") val bearing: Double,
    @Json(name = "speed") val speed: Double,
    @Json(name = "provider") val provider: String  // "gps", "network", "fused"
)

// ========== IMU Data Models ==========

@JsonClass(generateAdapter = true)
data class IMUData(
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "accelerometer") val accelerometer: Vector3,
    @Json(name = "gyroscope") val gyroscope: Vector3,
    @Json(name = "magnetometer") val magnetometer: Vector3
)

@JsonClass(generateAdapter = true)
data class Vector3(
    @Json(name = "x") val x: Double,
    @Json(name = "y") val y: Double,
    @Json(name = "z") val z: Double
)

// ========== YOLO Detection Models ==========

@JsonClass(generateAdapter = true)
data class YOLOData(
    @Json(name = "frame_id") val frameId: Long,
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "detections") val detections: List<YOLODetection>
)

@JsonClass(generateAdapter = true)
data class YOLODetection(
    @Json(name = "bounding_box") val boundingBox: BoundingBox,
    @Json(name = "class") val className: String,
    @Json(name = "class_id") val classId: Int,
    @Json(name = "confidence") val confidence: Double
)

@JsonClass(generateAdapter = true)
data class BoundingBox(
    @Json(name = "x") val x: Double,      // top-left x
    @Json(name = "y") val y: Double,      // top-left y
    @Json(name = "width") val width: Double,
    @Json(name = "height") val height: Double
)

// ========== Authentication Models ==========

@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "token") val token: String? = null,
    @Json(name = "user") val user: UserInfo? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class UserInfo(
    @Json(name = "id") val id: Long,
    @Json(name = "username") val username: String,
    @Json(name = "email") val email: String?,
    @Json(name = "full_name") val fullName: String?,
    @Json(name = "is_active") val isActive: Boolean,
    @Json(name = "role") val role: String?,
    @Json(name = "created_at") val createdAt: String?
)

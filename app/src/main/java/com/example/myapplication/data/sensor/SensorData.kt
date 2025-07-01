package com.example.myapplication.model
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.location.LocationManager
import android.util.Range
import android.util.Size
import android.view.Surface

data class SensorData(
    val value: String? = null,
    val bitmap: Bitmap? = null,
    val timestamp: Long,
    val monoTimestamp: Long,
    val frameId: Long = -1L
)

data class GpsConfig(
    val timestamp: Long,
    val monoTimestamp: Long,
    val provider: String = LocationManager.GPS_PROVIDER,
    val minTimeMs: Long = 1000L,
    val minDistanceMeters: Float = 1.0f,
)

data class ImuConfig(
    val timestamp: Long,
    val monoTimestamp: Long,
    val accelerometerEnabled: Boolean = true,
    val gyroscopeEnabled: Boolean = true,
    val magnetometerEnabled: Boolean = true,
    val samplingRateHz: Int = 50,
)

data class GnssData(
    val monoTimestamp: Long,
    val timestamp: Long,
    val gnssType: String,
    val satelliteId: Int,
    val signalStrength: Double,
    val pseudorangeRate: Double?,
    val carrierPhase: Double?,
    val additionalInfo: String
)

data class SensorData_String(
    val value: String,
    val timestamp: Long,
    val monoTimestamp: Long
)

data class BoundingBoxLog(
    val frameId: Long,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val cnf: Float,
    val clsName: String,
    val timestamp: Long,
    val monoTimestamp: Long
)


data class CameraConfig(
    var cameraId: String? = null,
    var lensFacing: Int? = CameraCharacteristics.LENS_FACING_BACK,
    var hardwareLevel: Int? = null,
    var imageSize: Size = Size(1280, 1280),
    var imageFormat: Int = ImageFormat.JPEG,
    var jpegQuality: Int = 90.coerceIn(0, 100),
    var orientation: Int = 0,
    var aeMode: Int = CameraMetadata.CONTROL_AE_MODE_ON,
    var aeLock: Boolean = false,
    var aeExposureCompensation: Int = 0,
    var aeTargetFpsRange: Range<Int> = Range(10, 15),
    var aePrecaptureTrigger: Int = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
    var aeAntibandingMode: Int = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO,
    var afMode: Int = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
    var afRegions: Array<MeteringRectangle> = emptyArray(),
    var afTrigger: Int = CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
    var focusDistance: Float = 0f,
    var flashMode: Int = CameraMetadata.FLASH_MODE_OFF,
    var flashStrength: Int = 100.coerceIn(0, 100),
    var awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    var awbLock: Boolean = false,
    var colorCorrectionGains: RggbChannelVector? = null,
    var sensorExposureTime: Long? = null,
    var sensorSensitivity: Int? = null,
    var sensorFrameDuration: Long? = null,
    var opticalStabilizationMode: Int = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
    var zoomRatio: Float = 1.0f.coerceAtLeast(1.0f),
    var outputSurfaces: List<Surface> = emptyList(),
    var captureCallback: CameraCaptureSession.CaptureCallback? = null,
    var noiseReductionMode: Int = CameraMetadata.NOISE_REDUCTION_MODE_FAST,
    var shadingMode: Int = CameraMetadata.SHADING_MODE_FAST,
    var tonemapCurve: TonemapCurve? = null,
    var edgeMode: Int = CameraMetadata.EDGE_MODE_OFF,
    var physicalCameraIds: List<String> = emptyList(),
    var syncMode: Int = CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL
)

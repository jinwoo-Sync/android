package com.example.myapplication.data.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.location.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi
import com.example.myapplication.data.logging.LoggerManager
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.learning.yolo.BoundingBox
import com.example.myapplication.learning.yolo.Constants
import com.example.myapplication.learning.yolo.Detector
import com.example.myapplication.model.*
import com.example.myapplication.utils.HealthLevel
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.gms.location.Priority
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import com.example.myapplication.utils.PoolHealthStatus
import com.example.myapplication.utils.SharedBitmap
import java.util.concurrent.atomic.AtomicBoolean

class DeepLearningAdaptiveManager {
    private val TAG = "DeepLearningAdaptiveManager"

    enum class InferenceComplexity { LOW, MEDIUM, HIGH, CRITICAL }

    data class DetectionStrategy(
        val skipInterval: Int,
        val enableDetection: Boolean,
        val qualityReduction: Float,
        val concurrentLimit: Int,
        val complexity: InferenceComplexity
    )

    private val inferenceThresholds = mapOf(
        InferenceComplexity.LOW to 60L,
        InferenceComplexity.MEDIUM to 80L,
        InferenceComplexity.HIGH to 100L,
        InferenceComplexity.CRITICAL to 120L
    )

    fun getCurrentInferenceComplexity(lastInferenceMs: Long): InferenceComplexity {
        return when {
            lastInferenceMs >= inferenceThresholds[InferenceComplexity.CRITICAL]!! -> InferenceComplexity.CRITICAL
            lastInferenceMs >= inferenceThresholds[InferenceComplexity.HIGH]!! -> InferenceComplexity.HIGH
            lastInferenceMs >= inferenceThresholds[InferenceComplexity.MEDIUM]!! -> InferenceComplexity.MEDIUM
            else -> InferenceComplexity.LOW
        }
    }

    fun getCurrentDetectionStrategy(lastInferenceMs: Long): DetectionStrategy {
        return when (getCurrentInferenceComplexity(lastInferenceMs)) {
            InferenceComplexity.CRITICAL -> DetectionStrategy(
                skipInterval = 8,
                enableDetection = true,
                qualityReduction = 0.7f,
                concurrentLimit = 1,
                complexity = InferenceComplexity.CRITICAL
            )
            InferenceComplexity.HIGH -> DetectionStrategy(
                skipInterval = 6,
                enableDetection = true,
                qualityReduction = 0.8f,
                concurrentLimit = 1,
                complexity = InferenceComplexity.HIGH
            )
            InferenceComplexity.MEDIUM -> DetectionStrategy(
                skipInterval = 5,
                enableDetection = true,
                qualityReduction = 0.9f,
                concurrentLimit = 1,
                complexity = InferenceComplexity.MEDIUM
            )
            InferenceComplexity.LOW -> DetectionStrategy(
                skipInterval = 3,
                enableDetection = true,
                qualityReduction = 1.0f,
                concurrentLimit = 1,
                complexity = InferenceComplexity.LOW
            )
        }
    }
}

private class YoloDetectorListener(
    private val context: Context,
    private val dataSynchronizer: DataSynchronizer,
    private val detectionCallback: ((List<BoundingBox>, Long, Long) -> Unit)?
) : Detector.DetectorListener {
    private val TAG = "YoloDetectorListener"

    override fun onEmptyDetect() {
        detectionCallback?.invoke(emptyList(), 0L, -1L)
        Log.d(TAG, "Detector: Empty detection")
    }

    override fun onDetect(
        boundingBoxes: List<BoundingBox>,
        inferenceTime: Long,
        frameId: Long
    ) {
        val boundingBoxLogs = boundingBoxes.map { bbox ->
            BoundingBoxLog(
                frameId = frameId,
                x1 = bbox.x1,
                y1 = bbox.y1,
                x2 = bbox.x2,
                y2 = bbox.y2,
                cnf = bbox.cnf,
                clsName = bbox.clsName,
                timestamp = System.currentTimeMillis(),
                monoTimestamp = System.nanoTime()
            )
        }

        if (boundingBoxLogs.isNotEmpty()) {
            LoggerManager.getInstance(context, dataSynchronizer).pushBoundingBox(boundingBoxLogs)
        }

        detectionCallback?.invoke(boundingBoxes, inferenceTime, frameId)
        Log.d(
            TAG,
            "Detector: Detected ${boundingBoxes.size} boxes, inference time: $inferenceTime ms, frameId: $frameId"
        )
    }
}

class SensorCollector(private val context: Context) {
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private val isStreaming = AtomicBoolean(false)

    private val isSessionActive = AtomicBoolean(false)
    private val frameProcessingLock = Object()

    private lateinit var taggedBitmapPool: TaggedBitmapPool

    private val frameProcessingStats = AtomicInteger(0)

    private val deepLearningAdaptiveManager = DeepLearningAdaptiveManager()
    private var currentDetectionStrategy =
        deepLearningAdaptiveManager.getCurrentDetectionStrategy(60L)

    private var lastInferenceTimeMs = 60L
    private var inferenceFrameSkipCount = 0
    private val lastStrategyUpdate = AtomicLong(0)

    private val TAG = "SensorCollector"
    private var frameSkipInterval = 2 // UI에서 설정한 기본값 반영
    private var frameCount = 0
    private var detectorInitialized = false
    private var latestImuData: FloatArray? = null
    private val cameraFrameList = mutableListOf<Bitmap>()
    private var lastCapturedBitmap: Bitmap? = null

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var detector: Detector? = null
    private var detectorExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var latestAccelerometer = FloatArray(3) { 0f }
    private var latestGyroscope = FloatArray(3) { 0f }
    private var latestMagnetometer = FloatArray(3) { 0f }

    private var isGnssCallbackRegistered = AtomicBoolean(false)
    private var gnssSessionStartTime: Long = 0L
    private var firstFixTime: Long? = null

    private lateinit var dataSynchronizer: DataSynchronizer

    private val isDetecting = AtomicBoolean(false)
    //추론용 복사 용지
    private var gpuInferenceBitmap: Bitmap? = null
    private var gpuCanvas: Canvas? = null
    private val gpuBitmapLock = Object()

    var cameraConfig = CameraConfig(
        imageSize = Size(840, 840),
        lensFacing = CameraCharacteristics.LENS_FACING_BACK,
        aeTargetFpsRange = Range(15, 15),
        jpegQuality = 90,
        flashMode = CameraMetadata.FLASH_MODE_OFF,
        afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        zoomRatio = 1.0f,
        syncMode = CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL,
        opticalStabilizationMode = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        aeMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
    )

    private val gpsConfig = GpsConfig(
        gpsTimestamp = 0L,
        localTimestamp = 0L,
        monoTimestamp = 0L,
        provider = LocationManager.GPS_PROVIDER,
        minTimeMs = 1000L,
        minDistanceMeters = 1.0f,
    )

    private val imuConfig = ImuConfig(
        timestamp = 0L,
        monoTimestamp = 0L,
        accelerometerEnabled = true,
        gyroscopeEnabled = true,
        magnetometerEnabled = true,
        samplingRateHz = 50,
    )

    private var gpsCallback: ((SensorData_String) -> Unit)? = null
    private var imuCallback: ((SensorData_String) -> Unit)? = null
    private var gnssCallback: ((SensorData_String) -> Unit)? = null
    private var detectionCallback: ((List<BoundingBox>, Long, Long) -> Unit)? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        initializeAdvancedSystems()
    }

    private fun initializeAdvancedSystems() {
        taggedBitmapPool = TaggedBitmapPool(
            poolSize = 8,
            width = 840,
            height = 840,
            autoCleanupIntervalMs = 2000L,  // 2초마다 자동 정리
            staleTimeoutMs = 4000L          // 4초 이상 미사용시 강제 해제
        )

        Log.d(TAG, "🏷️ 태그 기반 비트맵 풀 초기화 완료")
    }

    private fun initializeOptimizedMemorySystem(): Boolean {
        return try {
            Log.d(TAG, "🎯 최적화된 메모리 시스템 초기화 완료")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최적화된 메모리 시스템 초기화 실패: ${e.message}", e)
            false
        }
    }

    private fun initializeDetector() {
        if (!::dataSynchronizer.isInitialized) {
            Log.w(TAG, "DataSynchronizer가 설정되지 않아 Detector 초기화를 지연합니다.")
            return
        }

        detectorExecutor.submit {
            try {
                detector = Detector(
                    context,
                    Constants.MODEL_PATH,
                    Constants.LABELS_PATH,
                    YoloDetectorListener(context, dataSynchronizer, detectionCallback),
                    { message -> Log.d(TAG, "Detector message: $message") }
                )
                detectorInitialized = true
                Log.d(TAG, "Detector initialized successfully")
            } catch (e: Exception) {
                detectorInitialized = false
                Log.e(TAG, "Failed to initialize Detector: ${e.message}", e)
            }
        }
    }

    private fun ensureDetectorExecutor() {
        if (detectorExecutor.isShutdown || detectorExecutor.isTerminated) {
            Log.w(TAG, "Detector executor was shut down, recreating...")
            detectorExecutor = Executors.newSingleThreadExecutor()
            initializeDetector()
        }
        if (!detectorInitialized) {
            Log.w(TAG, "Detector is not initialized, attempting to reinitialize...")
            initializeDetector()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                val gpsTimestamp = location.time
                val localTimestamp = System.currentTimeMillis()
                val monoTimestamp = System.nanoTime()
                val isGpsTimeValid =
                    gpsTimestamp > 0 && abs(gpsTimestamp - localTimestamp) < 86400000L


                //
                if (::dataSynchronizer.isInitialized && isGpsTimeValid) {
                    dataSynchronizer.updateTimeSync(gpsTimestamp, localTimestamp)
                    Log.d(TAG, "🎯 GPS 시간 동기화 업데이트: gpsTime=$gpsTimestamp, localTime=$localTimestamp")
                }

                val sensorData = SensorData_String(
                    value = "Lat: ${location.latitude}, Lon: ${location.longitude}, Alt: ${if (location.hasAltitude()) location.altitude else "N/A"}, Acc: ${if (location.hasAccuracy()) location.accuracy else "N/A"}m",
                    timestamp = localTimestamp,
                    monoTimestamp = monoTimestamp
                )

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    synchronized(this@SensorCollector) {
                        gpsCallback?.invoke(sensorData)
                        /**
                         * Log.d(
                         *    TAG,
                         *    "✅ GPS 콜백 호출: Lat=${location.latitude}, Lon=${location.longitude}"
                        ) */
                    }
                }

                if (::dataSynchronizer.isInitialized) {
                    LoggerManager.getInstance(context, dataSynchronizer).pushGps(
                        location, localTimestamp, monoTimestamp
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val clock = event.clock
            val gpsTimestamp = clock.timeNanos / 1_000_000
            val localTimestamp = System.currentTimeMillis()
            val monoTimestamp = System.nanoTime()
            val isGpsTimeValid = gpsTimestamp > 0 && abs(gpsTimestamp - localTimestamp) < 86400000L

            val clockData = GnssClockData(
                gpsTimestamp = gpsTimestamp,
                localTimestamp = localTimestamp,
                monoTimestamp = monoTimestamp,
                timeNanos = clock.timeNanos,
                timeUncertaintyNanos = if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else null,
                leapSecond = if (clock.hasLeapSecond()) clock.leapSecond else null,
                biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else null,
                biasUncertaintyNanos = if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else null,
                driftNanosPerSecond = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else null,
                driftUncertaintyNanosPerSecond = if (clock.hasDriftNanosPerSecond()) clock.driftUncertaintyNanosPerSecond else null,
                hardwareClockDiscontinuityCount = clock.hardwareClockDiscontinuityCount,
                fullBiasNanos = if (clock.hasFullBiasNanos()) clock.fullBiasNanos else null,
                additionalInfo = "TimeNanos=${clock.timeNanos}"
            )

            for (measurement in event.measurements) {
                val gnssType = when (measurement.constellationType) {
                    GnssStatus.CONSTELLATION_GPS -> "GPS"
                    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                    GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
                    else -> "Unknown"
                }

                val comprehensiveData = ComprehensiveGnssData(
                    gpsTimestamp = gpsTimestamp,
                    localTimestamp = localTimestamp,
                    monoTimestamp = monoTimestamp,
                    isGpsTimeValid = isGpsTimeValid,
                    gnssType = gnssType,
                    constellationType = measurement.constellationType,
                    satelliteId = measurement.svid,
                    svid = measurement.svid,
                    signalStrength = measurement.cn0DbHz.toDouble(),
                    carrierFrequencyHz = if (measurement.hasCarrierFrequencyHz()) measurement.carrierFrequencyHz.toDouble() else null,
                    multipathIndicator = measurement.multipathIndicator,
                    pseudorangeRate = measurement.pseudorangeRateMetersPerSecond.takeIf { !it.isNaN() },
                    pseudorangeRateUncertainty = measurement.pseudorangeRateUncertaintyMetersPerSecond.takeIf { !it.isNaN() },
                    accumulatedDeltaRange = measurement.accumulatedDeltaRangeMeters.takeIf { !it.isNaN() },
                    accumulatedDeltaRangeState = measurement.accumulatedDeltaRangeState,
                    accumulatedDeltaRangeUncertainty = measurement.accumulatedDeltaRangeUncertaintyMeters.takeIf { !it.isNaN() },
                    carrierPhase = if (measurement.hasCarrierPhase()) measurement.carrierPhase else null,
                    carrierPhaseUncertainty = if (measurement.hasCarrierPhaseUncertainty()) measurement.carrierPhaseUncertainty else null,
                    carrierCycles = if (measurement.hasCarrierCycles()) measurement.carrierCycles else null,
                    receivedSvTimeNanos = measurement.receivedSvTimeNanos,
                    receivedSvTimeUncertainty = measurement.receivedSvTimeUncertaintyNanos,
                    timeOffsetNanos = measurement.timeOffsetNanos,
                    state = measurement.state,
                    automaticGainControl = if (measurement.hasAutomaticGainControlLevelDb()) measurement.automaticGainControlLevelDb.toDouble() else null,
                    basebandCn0DbHz = if (measurement.hasBasebandCn0DbHz()) measurement.basebandCn0DbHz.toDouble() else null,
                    fullInterSignalBiasNanos = if (measurement.hasFullInterSignalBiasNanos()) measurement.fullInterSignalBiasNanos else null,
                    fullInterSignalBiasUncertaintyNanos = if (measurement.hasFullInterSignalBiasUncertaintyNanos()) measurement.fullInterSignalBiasUncertaintyNanos else null,
                    satelliteInterSignalBiasNanos = if (measurement.hasSatelliteInterSignalBiasNanos()) measurement.satelliteInterSignalBiasNanos else null,
                    satelliteInterSignalBiasUncertaintyNanos = if (measurement.hasSatelliteInterSignalBiasUncertaintyNanos()) measurement.satelliteInterSignalBiasUncertaintyNanos else null,
                    codeType = if (measurement.hasCodeType()) measurement.codeType else null,
                    additionalInfo = "State=0x${measurement.state.toString(16)}, MP=${measurement.multipathIndicator}"
                )

                gnssCallback?.invoke(
                    SensorData_String(
                        value = "GNSS: ${comprehensiveData.gnssType}, Sat: ${comprehensiveData.satelliteId}, C/N0: ${comprehensiveData.signalStrength}",
                        timestamp = localTimestamp,
                        monoTimestamp = monoTimestamp
                    )
                )

                if (::dataSynchronizer.isInitialized) {
                    LoggerManager.getInstance(context, dataSynchronizer).pushComprehensiveGnss(
                        comprehensiveData, clockData
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val localTimestamp = System.currentTimeMillis()
            val monoTimestamp = System.nanoTime()

            val totalSatellites = status.satelliteCount
            var usedSatellites = 0
            (0 until totalSatellites).forEach { if (status.usedInFix(it)) usedSatellites++ }

            for (i in 0 until totalSatellites) {
                val satelliteStatus = GnssSatelliteStatus(
                    gpsTimestamp = 0L,
                    localTimestamp = localTimestamp,
                    monoTimestamp = monoTimestamp,
                    satelliteIndex = i,
                    constellationType = status.getConstellationType(i),
                    svid = status.getSvid(i),
                    cn0DbHz = status.getCn0DbHz(i),
                    hasCarrierFrequency = false,
                    carrierFrequencyHz = null,
                    azimuthDegrees = status.getAzimuthDegrees(i),
                    elevationDegrees = status.getElevationDegrees(i),
                    hasAlmanacData = status.hasAlmanacData(i),
                    hasEphemerisData = status.hasEphemerisData(i),
                    usedInFix = status.usedInFix(i),
                    totalSatelliteCount = totalSatellites,
                    usedSatelliteCount = usedSatellites
                )

                if (::dataSynchronizer.isInitialized) {
                    LoggerManager.getInstance(context, dataSynchronizer)
                        .pushSatelliteStatus(satelliteStatus)
                }
            }
        }

        override fun onFirstFix(ttffMillis: Int) {
            firstFixTime = ttffMillis.toLong()
            if (::dataSynchronizer.isInitialized) {
                LoggerManager.getInstance(context, dataSynchronizer)
                    .recordFirstFix(ttffMillis.toLong())
            }
        }

        override fun onStarted() {
            gnssSessionStartTime = System.currentTimeMillis()
        }

        override fun onStopped() {
            val sessionDuration =
                if (gnssSessionStartTime > 0) System.currentTimeMillis() - gnssSessionStartTime else 0L
            if (::dataSynchronizer.isInitialized) {
                LoggerManager.getInstance(context, dataSynchronizer)
                    .recordSessionEnd(sessionDuration, firstFixTime)
            }
            gnssSessionStartTime = 0L
            firstFixTime = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private val gnssNavigationMessageCallback = object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(message: GnssNavigationMessage) {
            val localTimestamp = System.currentTimeMillis()
            val monoTimestamp = System.nanoTime()

            val navigationData = GnssNavigationData(
                gpsTimestamp = 0L,
                localTimestamp = localTimestamp,
                monoTimestamp = monoTimestamp,
                messageId = message.messageId,
                submessageId = message.submessageId,
                type = message.type,
                status = message.status,
                svid = message.svid,
                data = message.data,
                dataLength = message.data.size,
                additionalInfo = "Type=${message.type}, Status=${message.status}"
            )

            if (::dataSynchronizer.isInitialized) {
                LoggerManager.getInstance(context, dataSynchronizer)
                    .pushNavigationMessage(navigationData)
            }
        }
    }

    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!imuConfig.accelerometerEnabled) return
            synchronized(this@SensorCollector) {
                latestAccelerometer = event.values.clone()
                val systemTimestamp = System.currentTimeMillis()
                val monoTimestamp = System.nanoTime()
                updateLatestImuData(systemTimestamp, monoTimestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val gyroscopeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!imuConfig.gyroscopeEnabled) return
            synchronized(this@SensorCollector) {
                latestGyroscope = event.values.clone()
                val systemTimestamp = System.currentTimeMillis()
                val monoTimestamp = System.nanoTime()
                updateLatestImuData(systemTimestamp, monoTimestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val magnetometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!imuConfig.magnetometerEnabled) return
            synchronized(this@SensorCollector) {
                latestMagnetometer = event.values.clone()
                val systemTimestamp = System.currentTimeMillis()
                val monoTimestamp = System.nanoTime()
                updateLatestImuData(systemTimestamp, monoTimestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun updateLatestImuData(systemTimestamp: Long, monoTimestamp: Long) {
        latestImuData = FloatArray(9).apply {
            latestAccelerometer.copyInto(this, 0, 0, 3)
            latestGyroscope.copyInto(this, 3, 0, 3)
            latestMagnetometer.copyInto(this, 6, 0, 3)
        }

        val sensorData = SensorData_String(
            value = "ACC[${latestAccelerometer.joinToString(",")}] GYRO[${
                latestGyroscope.joinToString(
                    ","
                )
            }] MAG[${latestMagnetometer.joinToString(",")}]",
            timestamp = systemTimestamp,
            monoTimestamp = monoTimestamp
        )

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            imuCallback?.invoke(sensorData)
        }

        if (::dataSynchronizer.isInitialized) {
            LoggerManager.getInstance(context, dataSynchronizer)
                .pushImu(latestImuData!!, systemTimestamp, monoTimestamp)
        }
    }

    fun setDataSynchronizer(synchronizer: DataSynchronizer) {
        this.dataSynchronizer = synchronizer
        Log.d(TAG, "DataSynchronizer 설정 완료")
        initializeDetector()
    }

    fun collectCameraData(callback: (SensorData?) -> Unit) {
        if (isStreaming.get()) {
            callback(null)
            return
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                callback(null)
                return
            }
            val cameraId = selectCameraId(cameraManager) ?: run {
                callback(null)
                return
            }
            val validatedSize =
                validateImageSize(cameraId, cameraConfig.imageSize, cameraConfig.imageFormat)
            imageReader = ImageReader.newInstance(
                validatedSize.width,
                validatedSize.height,
                cameraConfig.imageFormat,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let {
                        try {
                            val bitmap = when (cameraConfig.imageFormat) {
                                ImageFormat.JPEG -> {
                                    val buffer = it.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }

                                ImageFormat.YUV_420_888 -> yuvToBitmap(it)
                                else -> null
                            }
                            bitmap?.let { bmp ->
                                val rotatedBitmap =
                                    safeRotateBitmap(bmp, getRotationDegrees(cameraId))
                                val frameId = System.currentTimeMillis()
                                val systemTime = System.currentTimeMillis()
                                val monoTime = System.nanoTime()
                                val sensorData = SensorData(
                                    value = "Camera ID: $cameraId",
                                    bitmap = rotatedBitmap,
                                    timestamp = systemTime,
                                    monoTimestamp = monoTime,
                                    frameId = frameId
                                )
                                if (::dataSynchronizer.isInitialized) {
                                    LoggerManager.getInstance(context, dataSynchronizer)
                                        .pushCamera(sensorData)
                                }
                                callback(sensorData)
                                ensureDetectorExecutor()
                                detectorExecutor.submit {
                                    if (rotatedBitmap != null) {
                                        detector?.detect(rotatedBitmap, frameId)
                                    }
                                }
                            } ?: callback(null)
                        } finally {
                            it.close()
                        }
                        setOnImageAvailableListener(null, null)
                        closeCamera()
                    } ?: callback(null)
                }, null)
            }
            if (!cameraOpenCloseLock.tryAcquire(2, TimeUnit.SECONDS)) {
                callback(null)
                return
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    cameraOpenCloseLock.release()
                    val captureRequestBuilder =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    imageReader?.surface?.let { surface ->
                        captureRequestBuilder.addTarget(surface)
                        applyCameraSettings(captureRequestBuilder, cameraId, cameraManager)
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    session.setRepeatingRequest(
                                        captureRequestBuilder.build(),
                                        cameraConfig.captureCallback,
                                        null
                                    )
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    callback(null)
                                    closeCamera()
                                }
                            },
                            null
                        )
                    } ?: callback(null)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    callback(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    callback(null)
                }
            }, null)
        } catch (e: Exception) {
            callback(null)
            closeCamera()
        }
    }

    fun startCameraStreaming(
        callback: (SensorData?) -> Unit,
        detectionCallback: (List<BoundingBox>, Long, Long) -> Unit
    ) {
        this.detectionCallback = detectionCallback

        if (isStreaming.get()) {
            Log.d(TAG, "Streaming already in progress")
            return
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                callback(null)
                return
            }

            cleanupCameraResources()

            val cameraId = selectCameraId(cameraManager) ?: run {
                callback(null)
                return
            }

            val validatedSize =
                validateImageSize(cameraId, cameraConfig.imageSize, cameraConfig.imageFormat)

            imageReader = ImageReader.newInstance(
                validatedSize.width,
                validatedSize.height,
                cameraConfig.imageFormat,
                4
            ).apply {
                setOnImageAvailableListener(createOptimizedImageListener(cameraId, callback), null)
            }

            if (!cameraOpenCloseLock.tryAcquire(3, TimeUnit.SECONDS)) {
                callback(null)
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    cameraOpenCloseLock.release()

                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    imageReader?.surface?.let { surface ->
                        builder.addTarget(surface)
                        applyCameraSettings(builder, cameraId, cameraManager)

                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session

                                    if (initializeOptimizedMemorySystem()) {
                                        session.setRepeatingRequest(
                                            builder.build(),
                                            cameraConfig.captureCallback,
                                            null
                                        )
                                        isStreaming.set(true)
                                        isSessionActive.set(true)
                                        Log.d(TAG, "🎯 고급 카메라 세션 시작")
                                    } else {
                                        Log.e(TAG, "❌ 최적화된 메모리 시스템 초기화 실패")
                                        callback(null)
                                        cleanupCameraResources()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Camera session configuration failed")
                                    callback(null)
                                    closeCamera()
                                }
                            },
                            null
                        )
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "Camera disconnected")
                    closeCamera()
                    callback(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    closeCamera()
                    callback(null)
                }
            }, null)

        } catch (e: Exception) {
            Log.e(TAG, "Error in startCameraStreaming: ${e.message}", e)
            callback(null)
            closeCamera()
        }
    }

    private fun createOptimizedImageListener(
        cameraId: String,
        callback: (SensorData?) -> Unit
    ): ImageReader.OnImageAvailableListener {
        return ImageReader.OnImageAvailableListener { reader ->
            if (!isSessionActive.get()) {
                reader.acquireLatestImage()?.close()
                return@OnImageAvailableListener
            }

            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val imageBytes = extractHighQualityImageBytes(image)
                    val rotationDegrees = getRotationDegrees(cameraId)

                    if (imageBytes != null) {
                        handleTaggedZeroCopyFrame(imageBytes, rotationDegrees, callback)
                    } else {
                        coroutineScope.launch(Dispatchers.Main) { callback(null) }
                    }

                } finally {
                    image.close()
                }
            }
        }
    }

    private fun handleTaggedZeroCopyFrame(
        imageBytes: ByteArray,
        rotationDegrees: Int,
        callback: (SensorData?) -> Unit
    ) {
        val frameId = System.nanoTime()

        try {
            // 1. UI용 태그 생성 및 비트맵 획득
            val uiTag = BitmapTag(
                id = "UI_${frameId}",
                owner = "HomeFragment",
                purpose = BitmapPurpose.UI_DISPLAY
            )

            val uiTaggedBitmap = taggedBitmapPool.acquireWithTag(uiTag)
            if (uiTaggedBitmap == null) {
                Log.w(TAG, "⚠️ UI 태그 비트맵 획득 실패")
                coroutineScope.launch(Dispatchers.Main) { callback(null) }
                return
            }

            // 2. 이미지 디코딩 및 회전 적용
            val sourceBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (sourceBitmap == null) {
                uiTaggedBitmap.releaseWithTag(uiTag)
                coroutineScope.launch(Dispatchers.Main) { callback(null) }
                return
            }

            // 3. UI 비트맵에 복사
            val uiBitmap = uiTaggedBitmap.useWithCurrentTag()
            if (uiBitmap != null) {
                val canvas = Canvas(uiBitmap)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val matrix = Matrix()
                val scaleX = 840f / sourceBitmap.width
                val scaleY = 840f / sourceBitmap.height
                matrix.setScale(scaleX, scaleY)
                if (rotationDegrees != 0) {
                    matrix.postRotate(rotationDegrees.toFloat(), 420f, 420f)
                }

                canvas.drawBitmap(sourceBitmap, matrix, null)
            }

            // 4. UI 콜백 (태그와 함께 전달)
            coroutineScope.launch(Dispatchers.Main) {
                val sensorData = SensorData(
                    value = "TaggedUI: $frameId",
                    bitmap = uiBitmap,
                    timestamp = System.currentTimeMillis(),
                    monoTimestamp = System.nanoTime(),
                    frameId = frameId
                )
                callback(sensorData)

                // UI 표시 후 3초 뒤 자동 해제 (UI가 해제하지 않을 경우 대비)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    uiTaggedBitmap.releaseWithTag(uiTag)
                }, 3000)
            }

            // 5. 딥러닝용 태그 생성 및 처리
            if (shouldPerformDetection()) {
                val detectionTag = BitmapTag(
                    id = "DETECTION_${frameId}",
                    owner = "YoloDetector",
                    purpose = BitmapPurpose.DETECTION
                )

                val detectionTaggedBitmap = taggedBitmapPool.acquireWithTag(detectionTag)
                if (detectionTaggedBitmap != null) {
                    // 딥러닝용 비트맵에 복사
                    val detectionBitmap = detectionTaggedBitmap.useWithCurrentTag()
                    if (detectionBitmap != null) {
                        val matrix = Matrix()
                        val scaleX = 840f / sourceBitmap.width
                        val scaleY = 840f / sourceBitmap.height
                        matrix.setScale(scaleX, scaleY)
                        if (rotationDegrees != 0) {
                            matrix.postRotate(rotationDegrees.toFloat(), 420f, 420f)
                        }

                        val detectionCanvas = Canvas(detectionBitmap)
                        detectionCanvas.drawBitmap(sourceBitmap, matrix, null)

                        // 비동기 딥러닝 처리
                        ensureDetectorExecutor()
                        detectorExecutor.submit {
                            try {
                                detector?.detect(detectionBitmap, frameId)
                                Log.d(TAG, "🎯 태그 기반 딥러닝 처리 완료: frameId=$frameId")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ 딥러닝 처리 오류: ${e.message}", e)
                            } finally {
                                // 처리 완료 후 해제
                                detectionTaggedBitmap.releaseWithTag(detectionTag)
                            }
                        }
                    } else {
                        detectionTaggedBitmap.releaseWithTag(detectionTag)
                    }
                }
            }

            sourceBitmap.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "❌ 태그 기반 프레임 처리 오류: ${e.message}", e)
            coroutineScope.launch(Dispatchers.Main) { callback(null) }
        }
    }

    private fun shouldPerformDetection(): Boolean {
        inferenceFrameSkipCount++
        return inferenceFrameSkipCount >= currentDetectionStrategy.skipInterval
    }

    /**
     * 🎯 고품질 YUV → RGB 직접 변환 (JPEG 압축 단계 제거)
     */
    private fun convertYuvToRgbBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "지원하지 않는 이미지 형식: ${image.format}")
            return null
        }

        try {
            val planes = image.planes
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            // YUV 데이터 추출
            val yBytes = ByteArray(ySize)
            val uBytes = ByteArray(uSize)
            val vBytes = ByteArray(vSize)

            yBuffer.get(yBytes)
            uBuffer.get(uBytes)
            vBuffer.get(vBytes)

            return convertYuvToRgbHighQuality(
                yBytes, uBytes, vBytes,
                image.width, image.height,
                yPlane.pixelStride, yPlane.rowStride,
                uPlane.pixelStride, uPlane.rowStride
            )

        } catch (e: Exception) {
            Log.e(TAG, "YUV → RGB 변환 실패: ${e.message}", e)
            return null
        }
    }

    /**
     * 🎯 고품질 YUV420 → RGB 변환 구현
     */
    private fun convertYuvToRgbHighQuality(
        yBytes: ByteArray, uBytes: ByteArray, vBytes: ByteArray,
        width: Int, height: Int,
        yPixelStride: Int, yRowStride: Int,
        uvPixelStride: Int, uvRowStride: Int
    ): Bitmap? {
        try {
            val argbBytes = IntArray(width * height)

            // YUV → RGB 변환 (ITU-R BT.601 표준 사용)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val yIndex = y * yRowStride + x * yPixelStride
                    val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride

                    if (yIndex >= yBytes.size || uvIndex >= uBytes.size || uvIndex >= vBytes.size) {
                        continue
                    }

                    val yValue = yBytes[yIndex].toInt() and 0xFF
                    val uValue = uBytes[uvIndex].toInt() and 0xFF
                    val vValue = vBytes[uvIndex].toInt() and 0xFF

                    // ITU-R BT.601 변환 공식 (고품질)
                    val c = yValue - 16
                    val d = uValue - 128
                    val e = vValue - 128

                    val r = (298 * c + 409 * e + 128) shr 8
                    val g = (298 * c - 100 * d - 208 * e + 128) shr 8
                    val b = (298 * c + 516 * d + 128) shr 8

                    // 색상 범위 클램핑
                    val red = r.coerceIn(0, 255)
                    val green = g.coerceIn(0, 255)
                    val blue = b.coerceIn(0, 255)

                    argbBytes[y * width + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }

            return Bitmap.createBitmap(argbBytes, width, height, Bitmap.Config.ARGB_8888)

        } catch (e: Exception) {
            Log.e(TAG, "RGB 변환 계산 실패: ${e.message}", e)
            return null
        }
    }

    /**
     * 🎯 고품질 이미지 바이트 추출 (JPEG 압축 제거)
     */
    private fun extractHighQualityImageBytes(image: Image): ByteArray? {
        return try {
            when (image.format) {
                ImageFormat.JPEG -> {
                    // JPEG는 그대로 사용
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    bytes
                }
                ImageFormat.YUV_420_888 -> {
                    // YUV를 고품질 RGB 비트맵으로 변환 후 PNG로 인코딩
                    val bitmap = convertYuvToRgbBitmap(image)
                    if (bitmap != null) {
                        val outputStream = ByteArrayOutputStream()
                        // PNG 무손실 압축 사용 (품질 유지)
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        val result = outputStream.toByteArray()
                        bitmap.recycle()
                        result
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "고품질 이미지 바이트 추출 실패: ${e.message}")
            null
        }
    }
    private fun convertYuvToJpegBytes(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        return ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                90,
                out
            )
            out.toByteArray()
        }
    }

    private fun updateLastInferenceTime(inferenceTimeMs: Long) {
        lastInferenceTimeMs = inferenceTimeMs
        Log.d(TAG, "🎯 추론시간 업데이트: ${inferenceTimeMs}ms → 다음 전략에 반영")
    }

    private fun cleanupCameraResources() {
        try {
            isSessionActive.set(false)
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "카메라 리소스 정리 오류: ${e.message}")
        }
    }

    fun stopCameraStreaming() {
        if (!isStreaming.get()) return

        isSessionActive.set(false)

        try {
            cleanupCameraResources()
            isStreaming.set(false)
            frameCount = 0
            frameProcessingStats.set(0)

            detector?.close()
            detectorExecutor.shutdownNow()

            if(::taggedBitmapPool.isInitialized) {
                taggedBitmapPool.cleanup()
            }

            synchronized(gpuBitmapLock) {
                gpuCanvas = null
                gpuInferenceBitmap?.takeIf { !it.isRecycled }?.recycle()
                gpuInferenceBitmap = null
                Log.d(TAG, "🗑️ GPU 추론용 비트맵 정리 완료")
            }

            Log.d(TAG, "🎯 Tagged-Pool 카메라 스트리밍 중지 완료")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }

    fun getTaggedPoolStatus(): String {
        return if (::taggedBitmapPool.isInitialized) {
            taggedBitmapPool.getPoolStatus()
        } else {
            "태그 기반 풀이 초기화되지 않음"
        }
    }
}
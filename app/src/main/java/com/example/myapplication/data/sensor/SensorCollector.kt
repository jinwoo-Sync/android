package com.example.myapplication.data.sensor

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.example.myapplication.data.logging.LoggerManager
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.learning.yolo.BoundingBox
import com.example.myapplication.learning.yolo.Constants
import com.example.myapplication.learning.yolo.Detector
import com.example.myapplication.model.*
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
import com.example.myapplication.utils.BitmapPoolManager
import com.example.myapplication.utils.ManagedBitmap
import com.google.android.gms.location.LocationRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

class SensorCollector(
    private val context: Context,
    private val bitmapPoolManager: BitmapPoolManager  // ✅ BitmapPoolManager 주입
) {
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private val isStreaming = AtomicBoolean(false)

    private val isSessionActive = AtomicBoolean(false)
    private val frameProcessingLock = Object()

    // ✅ BitmapPoolManager에서 풀과 프로세서 참조
    private val taggedBitmapPool get() = bitmapPoolManager.advancedTaggedBitmapPool
    private val highSpeedProcessor get() = bitmapPoolManager.highSpeedProcessor

    // 추가: 전용 이미지 처리 스레드
    private val imageProcessingThread = HandlerThread("ImageProcessing-${System.currentTimeMillis()}").apply {
        start()
    }
    private val imageProcessingHandler by lazy {
        Handler(imageProcessingThread.looper)
    }
    private val mainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

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

    // GPU 추론용 재사용 Canvas와 Matrix 캐시
    private var inferenceCanvas: Canvas? = null
    private var inferenceMatrix: Matrix? = null
    private val gpuInferenceLock = Object()
    // GPU 추론용 비트맵을 BitmapPool에서 태그로 구분
    private var currentInferenceBitmap: ManagedBitmap? = null
    // 기존 coroutineScope 사용
    private var gpuCleanupJob: Job? = null

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
        Log.d(TAG, "🎯 SensorCollector with BitmapPoolManager 초기화 완료")
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

    // GNSS 콜백들 (기존과 동일하므로 생략...)
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

    //,-> log trace에서 IMU 센서 데이터를 문자열로 변환하는 작업에서 main thread의 ui blocking을 유발하고 있음.
    private fun updateLatestImuData(systemTimestamp: Long, monoTimestamp: Long) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Update latestImuData in background
                latestImuData = FloatArray(9).apply {
                    latestAccelerometer.copyInto(this, 0, 0, 3)
                    latestGyroscope.copyInto(this, 3, 0, 3)
                    latestMagnetometer.copyInto(this, 6, 0, 3)
                }

                // Format sensor data string with 5 decimal places (matching original precision)
                val dataString = buildString {
                    append("ACC[")
                    append(latestAccelerometer.joinToString(",") { String.format("%.5f", it) })
                    append("] GYRO[")
                    append(latestGyroscope.joinToString(",") { String.format("%.5f", it) })
                    append("] MAG[")
                    append(latestMagnetometer.joinToString(",") { String.format("%.5f", it) })
                    append("]")
                }

                // Create SensorData_String object
                val sensorData = SensorData_String(
                    value = dataString,
                    timestamp = systemTimestamp,
                    monoTimestamp = monoTimestamp
                )

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    imuCallback?.invoke(sensorData)
                }

                // Push data to LoggerManager if initialized
                if (::dataSynchronizer.isInitialized) {
                    LoggerManager.getInstance(context, dataSynchronizer)
                        .pushImu(latestImuData!!, systemTimestamp, monoTimestamp)
                }
            } catch (e: Exception) {
                // Log error or handle appropriately
                Log.e("ImuDataProcessor", "Error processing IMU data", e)
            }
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
                setOnImageAvailableListener(createAdvancedImageListener(cameraId, callback), imageProcessingHandler)
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
                                @SuppressLint("SuspiciousIndentation")
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                        session.setRepeatingRequest(
                                            builder.build(),
                                            cameraConfig.captureCallback,
                                            null
                                        )
                                        isStreaming.set(true)
                                        isSessionActive.set(true)
                                    Log.d(TAG, "🎯 Advanced Tagged 카메라 세션 시작: ${taggedBitmapPool.getStatus()}")
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

    /**
     * 🎯 카메라는 건드리지 않고 센서 콜백만 재등록
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun restartSensorCallbacks(
        gpsCallback: ((SensorData_String) -> Unit)? = null,
        imuCallback: ((SensorData_String) -> Unit)? = null,
        gnssCallback: ((SensorData_String) -> Unit)? = null
    ) {
        // 콜백들을 업데이트
        this.gpsCallback = gpsCallback
        this.imuCallback = imuCallback
        this.gnssCallback = gnssCallback

        Log.d(TAG, "🔄 센서 콜백만 재등록: GPS=${gpsCallback != null}, IMU=${imuCallback != null}, GNSS=${gnssCallback != null}")

        // GPS는 위치 업데이트만 재시작 - 기존 방식 사용
        if (gpsCallback != null) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Thread.sleep(100)

                // 기존 코드와 동일한 방식으로 GPS 재시작
                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    1000L
                )
                    .setMinUpdateIntervalMillis(500L)
                    .setMaxUpdateDelayMillis(2000L)
                    .setMinUpdateDistanceMeters(0f)
                    .build()

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "✅ GPS만 재시작 완료")
            } catch (e: Exception) {
                Log.e(TAG, "GPS 재시작 실패: ${e.message}", e)
            }
        }

        // IMU는 센서 리스너만 재등록 - 기존 방식 사용
        if (imuCallback != null) {
            try {
                sensorManager.unregisterListener(accelerometerListener)
                sensorManager.unregisterListener(gyroscopeListener)
                sensorManager.unregisterListener(magnetometerListener)

                Thread.sleep(100)

                // 기존 코드와 동일한 방식으로 IMU 재시작
                val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

                if (accelSensor != null) {
                    val success = sensorManager.registerListener(
                        accelerometerListener,
                        accelSensor,
                        SensorManager.SENSOR_DELAY_GAME
                    )
                    Log.d(TAG, "가속도계 재등록 ${if (success) "성공" else "실패"}")
                }

                if (gyroSensor != null) {
                    val success = sensorManager.registerListener(
                        gyroscopeListener,
                        gyroSensor,
                        SensorManager.SENSOR_DELAY_GAME
                    )
                    Log.d(TAG, "자이로스코프 재등록 ${if (success) "성공" else "실패"}")
                }

                if (magSensor != null) {
                    val success = sensorManager.registerListener(
                        magnetometerListener,
                        magSensor,
                        SensorManager.SENSOR_DELAY_GAME
                    )
                    Log.d(TAG, "자기계 재등록 ${if (success) "성공" else "실패"}")
                }

                Log.d(TAG, "✅ IMU 센서만 재시작 완료")
            } catch (e: Exception) {
                Log.e(TAG, "IMU 재시작 실패: ${e.message}", e)
            }
        }

        // GNSS도 콜백만 재등록 - 기존 방식 사용
        if (gnssCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
                    locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
                    locationManager.unregisterGnssNavigationMessageCallback(gnssNavigationMessageCallback)

                    Thread.sleep(100)

                    locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback)
                    locationManager.registerGnssStatusCallback(gnssStatusCallback)
                    locationManager.registerGnssNavigationMessageCallback(gnssNavigationMessageCallback)
                }
                isGnssCallbackRegistered.set(true)
                Log.d(TAG, "✅ GNSS 콜백만 재등록 완료")
            } catch (e: Exception) {
                Log.e(TAG, "GNSS 콜백 재등록 실패: ${e.message}", e)
            }
        }
    }

    private fun createAdvancedImageListener(
        cameraId: String,
        callback: (SensorData?) -> Unit
    ): ImageReader.OnImageAvailableListener {
        return ImageReader.OnImageAvailableListener { reader ->
            if (!isSessionActive.get()) {
                reader.acquireLatestImage()?.close()
                return@OnImageAvailableListener
            }

            // 전략 업데이트 주기를 200ms로 단축
            val now = System.currentTimeMillis()
            if (now - lastStrategyUpdate.get() > 200) {
                updateDetectionProcessingStrategy()
                lastStrategyUpdate.set(now)
            }

            frameCount++

            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val imageBytes = extractHighQualityImageBytes(image)
                    val rotationDegrees = getRotationDegrees(cameraId)

                    if (imageBytes != null) {
                        // ✅ BitmapPoolManager의 프로세서 사용
                        val managedBitmap = highSpeedProcessor.processHighQualityZeroCopy(
                            imageBytes, rotationDegrees
                        )

                        if (managedBitmap != null) {
                            handleAdvancedTaggedFrame(managedBitmap, callback)
                        } else {
                            coroutineScope.launch(Dispatchers.Main) { callback(null) }
                        }
                    } else {
                        coroutineScope.launch(Dispatchers.Main) { callback(null) }
                    }

                } finally {
                    image.close()
                }
            }
        }
    }

    private fun updateDetectionProcessingStrategy() {
        val newStrategy =
            deepLearningAdaptiveManager.getCurrentDetectionStrategy(lastInferenceTimeMs)
        if (newStrategy != currentDetectionStrategy) {
            currentDetectionStrategy = newStrategy
            Log.i(
                TAG,
                "🔄 딥러닝 처리 전략 업데이트: 추론시간=${lastInferenceTimeMs}ms → 스킵간격=${newStrategy.skipInterval}, 복잡도=${newStrategy.complexity}"
            )
        }
    }

    private fun extractHighQualityImageBytes(image: Image): ByteArray? {
        return try {
            when (image.format) {
                ImageFormat.JPEG -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    bytes
                }
                ImageFormat.YUV_420_888 -> {
                    val bitmap = convertYuvToRgbBitmap(image)
                    if (bitmap != null) {
                        val outputStream = ByteArrayOutputStream()
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

    private fun handleAdvancedTaggedFrame(
        managedBitmap: ManagedBitmap,
        callback: (SensorData?) -> Unit
    ) {
        val frameId = System.nanoTime()
        val systemTime = System.currentTimeMillis()

        frameProcessingStats.incrementAndGet()

        //  ManagedBitmap 유효성 재검증
        if (!managedBitmap.isValid() || managedBitmap.bitmap.isRecycled) {
            Log.w(TAG, "무효한 ManagedBitmap 감지 - 프레임 스킵: frameId=$frameId")
            managedBitmap.release()
            mainHandler.post { callback(null) }
            return
        }

        // LoggerManager 처리는 백그라운드에서 (이미 백그라운드 스레드)
        if (::dataSynchronizer.isInitialized) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val sensorData = SensorData(
                        value = "TaggedFrame: $frameId",
                        bitmap = managedBitmap.bitmap,
                        managedBitmap = managedBitmap,
                        timestamp = systemTime,
                        monoTimestamp = System.nanoTime(),
                        frameId = frameId
                    )
                    LoggerManager.getInstance(context, dataSynchronizer).pushCamera(sensorData)
                } catch (e: Exception) {
                    Log.e(TAG, "백그라운드 LoggerManager 처리 실패: ${e.message}", e)
                }
            }
        }

        // 🎯 수정: UI로 메인 스레드에서 전달
        mainHandler.post {
            try {
                if (managedBitmap.isValid() && !managedBitmap.bitmap.isRecycled) {
                    callback(
                        SensorData(
                            value = "Advanced Tagged Frame: $frameId",
                            bitmap = managedBitmap.bitmap,
                            managedBitmap = managedBitmap,
                            timestamp = systemTime,
                            monoTimestamp = System.nanoTime(),
                            frameId = frameId
                        )
                    )
                    Log.d(TAG, "✅ Non-blocking frame delivered: frameId=$frameId")
                } else {
                    Log.w(TAG, "⚠️ UI 전달 시 비트맵 무효: frameId=$frameId")
                    managedBitmap.release()
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame delivery error: ${e.message}", e)
                managedBitmap.release()
                callback(null)
            }
        }

        handleSelectiveDetection(managedBitmap, frameId)

        if (frameCount % 90 == 0) {
            Log.i(TAG, "📊 Non-blocking: ${taggedBitmapPool.getStatus()}")
            Log.i(TAG, "📊 Processor: ${highSpeedProcessor.getStatus()}")
            Log.i(TAG, "📊 Thread: ${Thread.currentThread().name}")
        }
    }

    private fun handleSelectiveDetection(managedBitmap: ManagedBitmap, frameId: Long) {
        inferenceFrameSkipCount++

        if (inferenceFrameSkipCount >= currentDetectionStrategy.skipInterval) {
            inferenceFrameSkipCount = 0

            if (currentDetectionStrategy.enableDetection) {
                    ensureDetectorExecutor()
                    if (detectorInitialized && isDetecting.compareAndSet(false, true)) {
                        val inferenceStartTime = System.currentTimeMillis()

                        detectorExecutor.submit {
                            try {
                            val reusableGpuBitmap = copyToGpuInferenceBitmap(managedBitmap.bitmap)

                                if (reusableGpuBitmap != null) {
                                    detector?.detect(reusableGpuBitmap, frameId)

                                val inferenceEndTime = System.currentTimeMillis()
                                val actualInferenceTime = inferenceEndTime - inferenceStartTime
                                updateLastInferenceTime(actualInferenceTime)

                                Log.d(TAG, " Detection 완료: frameId=$frameId, 추론시간=${actualInferenceTime}ms")
                            }
                            } catch (e: Exception) {
                                Log.e(TAG, " Detection 오류: ${e.message}", e)
                            } finally {
                                isDetecting.set(false)
                            }
                    }
                }
            }
        }
    }

    fun forceResetDetectionState() {
        try {
            Log.w(TAG, " Detection 상태 강제 복구 시작")
            isDetecting.set(false)
            if (detectorExecutor.isShutdown || detectorExecutor.isTerminated) {
                detectorExecutor = Executors.newSingleThreadExecutor()
                initializeDetector()
            }
            inferenceFrameSkipCount = 0
            Log.w(TAG, " Detection 상태 강제 복구 완료")
        } catch (e: Exception) {
            Log.e(TAG, " Detection 상태 복구 실패: ${e.message}", e)
        }
    }

    private fun updateLastInferenceTime(inferenceTimeMs: Long) {
        lastInferenceTimeMs = inferenceTimeMs
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
            imageProcessingHandler.removeCallbacksAndMessages(null)
            cleanupCameraResources()
            isStreaming.set(false)
            frameCount = 0
            frameProcessingStats.set(0)

            detector?.close()
            detectorExecutor.shutdownNow()

            // GPU 정리 Job 중지
            gpuCleanupJob?.cancel()
            gpuCleanupJob = null
            cleanupGpuInferenceResources()

            synchronized(gpuBitmapLock) {
                gpuCanvas = null
                gpuInferenceBitmap?.takeIf { !it.isRecycled }?.recycle()
                gpuInferenceBitmap = null
                Log.d(TAG, "🗑️ GPU 추론용 비트맵 정리 완료")
            }

            Log.d(TAG, "🎯 Advanced Tagged 카메라 스트리밍 중지 완료")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }

    fun closeCamera() {
        try {
            isSessionActive.set(false)
            cameraOpenCloseLock.acquire()

            cleanupCameraResources()
            isStreaming.set(false)

            Log.d(TAG, "🎯 카메라 리소스 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera resources: ${e.message}")
        } finally {
            captureSession = null
            cameraDevice = null
            imageReader = null
            isStreaming.set(false)
            cameraOpenCloseLock.release()
        }
    }

    @Suppress("DEPRECATION")
    fun startSensorStreaming(
        gpsCallback: ((SensorData_String) -> Unit)? = null,
        imuCallback: ((SensorData_String) -> Unit)? = null,
        gnssCallback: ((SensorData_String) -> Unit)? = null,
        detectionCallback: ((List<BoundingBox>, Long, Long) -> Unit)? = null
    ) {
        this.gpsCallback = gpsCallback
        this.imuCallback = imuCallback
        this.gnssCallback = gnssCallback
        this.detectionCallback = detectionCallback

        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ACCESS_FINE_LOCATION 권한 없음")
            return
        }

        // ✅ GNSS 콜백 강제 재등록 (기존 조건 제거)
        try {
            // 기존 콜백 해제
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
                    locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
                    locationManager.unregisterGnssNavigationMessageCallback(gnssNavigationMessageCallback)
                } catch (e: Exception) {
                    Log.d(TAG, "기존 GNSS 콜백 해제: ${e.message}")
                }
            }

            // 새로 등록
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback)
                locationManager.registerGnssStatusCallback(gnssStatusCallback)
                locationManager.registerGnssNavigationMessageCallback(gnssNavigationMessageCallback)
                Log.d(TAG, "✅ GNSS 콜백들 재등록 성공")
            }
            isGnssCallbackRegistered.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "GNSS 콜백 등록 실패: ${e.message}", e)
            isGnssCallbackRegistered.set(false)
        }

        // GPS 위치 업데이트 재시작
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        )
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2000L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ GPS 위치 업데이트 재시작")
        } catch (e: Exception) {
            Log.e(TAG, "GPS 위치 업데이트 실패: ${e.message}", e)
        }

        // IMU 센서 재등록
        try {
            sensorManager.unregisterListener(accelerometerListener)
            sensorManager.unregisterListener(gyroscopeListener)
            sensorManager.unregisterListener(magnetometerListener)

            val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            if (accelSensor != null) {
                val success = sensorManager.registerListener(
                    accelerometerListener,
                    accelSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
                Log.d(TAG, "가속도계 재등록 ${if (success) "성공" else "실패"}")
            }

            if (gyroSensor != null) {
                val success = sensorManager.registerListener(
                    gyroscopeListener,
                    gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
                Log.d(TAG, "자이로스코프 재등록 ${if (success) "성공" else "실패"}")
            }

            if (magSensor != null) {
                val success = sensorManager.registerListener(
                    magnetometerListener,
                    magSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
                Log.d(TAG, "자기계 재등록 ${if (success) "성공" else "실패"}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "센서 등록 중 오류: ${e.message}", e)
        }

        Log.d(TAG, "모든 센서 스트리밍 시작 완료")
    }

    fun stopSensorStreaming() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)

            if (isGnssCallbackRegistered.getAndSet(false)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
                    locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
                    locationManager.unregisterGnssNavigationMessageCallback(
                        gnssNavigationMessageCallback
                    )
                }
            }

            sensorManager.unregisterListener(accelerometerListener)
            sensorManager.unregisterListener(gyroscopeListener)
            sensorManager.unregisterListener(magnetometerListener)

            detector?.close()

            detectorExecutor.shutdown()
            try {
                if (!detectorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    detectorExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                detectorExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }

            try {
                imageProcessingHandler.removeCallbacksAndMessages(null)
                Log.d(TAG, "✅ 이미지 처리 핸들러 정리 완료")
            } catch (e: Exception) {
                Log.e(TAG, "이미지 처리 핸들러 정리 실패: ${e.message}", e)
            }

            coroutineScope.cancel()

            Log.d(TAG, "모든 센서 스트리밍 중지 및 리소스 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "센서 스트리밍 중지 오류: ${e.message}", e)
        }
    }

    private fun validateImageSize(cameraId: String, size: Size, format: Int): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportedSizes = map?.getOutputSizes(format) ?: emptyArray()
            return if (supportedSizes.any { it.width == size.width && it.height == size.height }) {
                size
            } else {
                supportedSizes.find { it.width == 1080 && it.height == 1920 }
                    ?: supportedSizes.find { it.width == 720 && it.height == 1280 }
                    ?: supportedSizes.minByOrNull { abs(it.width - size.width) + abs(it.height - size.height) }
                    ?: Size(720, 1280)
            }
        } catch (e: CameraAccessException) {
            return Size(720, 1280)
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        return convertYuvToRgbBitmap(image)
            ?: throw IllegalStateException("고품질 YUV → RGB 변환 실패")
    }

    private fun selectCameraId(cameraManager: CameraManager): String? {
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) return null
            cameraConfig.cameraId?.let { if (cameraIds.contains(it)) return it }
            cameraConfig.lensFacing?.let { lensFacing ->
                for (cameraId in cameraIds) {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == lensFacing) return cameraId
                }
            }
            return cameraIds.first()
        } catch (e: CameraAccessException) {
            return null
        }
    }

    private fun applyCameraSettings(
        builder: CaptureRequest.Builder,
        cameraId: String,
        cameraManager: CameraManager
    ) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            builder.set(CaptureRequest.JPEG_QUALITY, cameraConfig.jpegQuality.toByte())
            if (characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    ?.contains(cameraConfig.aeMode) == true
            ) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, cameraConfig.aeMode)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, cameraConfig.aeLock)
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    cameraConfig.aeExposureCompensation
                )
                val availableFpsRanges =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?: emptyArray()
                builder.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    availableFpsRanges.firstOrNull { it.lower == cameraConfig.aeTargetFpsRange.lower && it.upper == cameraConfig.aeTargetFpsRange.upper }
                        ?: availableFpsRanges.firstOrNull() ?: Range(15, 15))
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    cameraConfig.aePrecaptureTrigger
                )
                builder.set(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    cameraConfig.aeAntibandingMode
                )
            }
            if (cameraConfig.focusDistance > 0f && characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, cameraConfig.focusDistance)
            } else if (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.contains(cameraConfig.afMode) == true
            ) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, cameraConfig.afMode)
                if (cameraConfig.afRegions.isNotEmpty()) builder.set(
                    CaptureRequest.CONTROL_AF_REGIONS,
                    cameraConfig.afRegions
                )
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, cameraConfig.afTrigger)
            }
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && cameraConfig.flashMode != CameraMetadata.FLASH_MODE_OFF) {
                builder.set(CaptureRequest.FLASH_MODE, cameraConfig.flashMode)
            }
            if (characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                    ?.contains(cameraConfig.awbMode) == true
            ) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, cameraConfig.awbMode)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, cameraConfig.awbLock)
                cameraConfig.colorCorrectionGains?.let {
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_GAINS,
                        it
                    )
                }
            }
            cameraConfig.sensorExposureTime?.let {
                if (characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null) builder.set(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    it
                )
            }
            cameraConfig.sensorSensitivity?.let {
                if (characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null) builder.set(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    it
                )
            }
            cameraConfig.sensorFrameDuration?.let {
                builder.set(
                    CaptureRequest.SENSOR_FRAME_DURATION,
                    it
                )
            }
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.let {
                    if (it.contains(cameraConfig.opticalStabilizationMode)) builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        cameraConfig.opticalStabilizationMode
                    )
                }
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?.let { maxZoom ->
                    if (cameraConfig.zoomRatio > 1.0f && maxZoom > 1.0f) {
                        val zoomFactor = cameraConfig.zoomRatio.coerceAtMost(maxZoom)
                        val rect =
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        rect?.let {
                            val centerX = it.width() / 2
                            val centerY = it.height() / 2
                            val newWidth = (it.width() / zoomFactor).toInt()
                            val newHeight = (it.height() / zoomFactor).toInt()
                            val cropRect = android.graphics.Rect(
                                centerX - newWidth / 2,
                                centerY - newHeight / 2,
                                centerX + newWidth / 2,
                                centerY + newHeight / 2
                            )
                            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                        }
                    }
                }
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, cameraConfig.noiseReductionMode)
            builder.set(CaptureRequest.SHADING_MODE, cameraConfig.shadingMode)
            cameraConfig.tonemapCurve?.let { builder.set(CaptureRequest.TONEMAP_CURVE, it) }
            builder.set(CaptureRequest.EDGE_MODE, cameraConfig.edgeMode)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to apply camera settings: ${e.message}", e)
        }
    }

    private fun getRotationDegrees(cameraId: String): Int {
        val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: windowManager.defaultDisplay.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            return when (rotation) {
                android.view.Surface.ROTATION_0 -> (sensorOrientation - cameraConfig.orientation + 360) % 360
                android.view.Surface.ROTATION_90 -> (sensorOrientation - 90 - cameraConfig.orientation + 360) % 360
                android.view.Surface.ROTATION_180 -> (sensorOrientation - 180 - cameraConfig.orientation + 360) % 360
                android.view.Surface.ROTATION_270 -> (sensorOrientation - 270 - cameraConfig.orientation + 360) % 360
                else -> sensorOrientation
            }
        } catch (e: CameraAccessException) {
            return 90
        }
    }

    private fun safeRotateBitmap(bitmap: Bitmap?, degrees: Int): Bitmap? {
        if (bitmap == null || degrees == 0) return bitmap
        if (bitmap.isRecycled) {
            Log.w(TAG, "Cannot rotate recycled bitmap")
            return null
        }

        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return try {
            if (!canAllocateMemory(bitmap.width, bitmap.height)) {
                Log.w(TAG, "Insufficient memory for bitmap rotation, skipping")
                return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError rotating bitmap", e)
            System.gc()
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating bitmap: ${e.message}", e)
            bitmap
        }
    }

    private fun canAllocateMemory(width: Int, height: Int): Boolean {
        val runtime = Runtime.getRuntime()
        val requiredMemory = width * height * 4L
        val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return availableMemory > requiredMemory * 2
    }

    /**
     *  풀 정리 요청 처리 (BitmapPoolManager 위임)
     */
    fun requestPoolCleanup() {
        try {
            // ✅ BitmapPoolManager로 위임
            bitmapPoolManager.requestPoolCleanup()
            Log.d(TAG, "🧹 SensorCollector: BitmapPoolManager를 통한 풀 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ SensorCollector: 풀 정리 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     *  풀 상세 상태 조회 (BitmapPoolManager 위임)
     */
    fun getPoolDetailedStatus(): String {
        return try {
            val status = bitmapPoolManager.getPoolDetailedStatus()
            Log.d(TAG, "📊 SensorCollector: BitmapPoolManager를 통한 풀 상태 조회 완료")
                status
        } catch (e: Exception) {
            Log.e(TAG, "❌ SensorCollector: 풀 상태 조회 실패: ${e.message}", e)
            "풀 상태 조회 실패: ${e.message}"
        }
    }

    fun setFrameSkipInterval(interval: Int) {
        frameSkipInterval = if (interval > 0) interval else 2
        currentDetectionStrategy = currentDetectionStrategy.copy(skipInterval = frameSkipInterval)
        Log.d(TAG, "프레임 스킵 간격 설정: $frameSkipInterval")
    }

    private fun getOrCreateGpuInferenceBitmap(width: Int, height: Int): Bitmap? {
        synchronized(gpuBitmapLock) {
            val existing = gpuInferenceBitmap

            if (existing != null &&
                !existing.isRecycled &&
                existing.width == width &&
                existing.height == height) {
                return existing
            }

            if (existing != null && !existing.isRecycled) {
                existing.recycle()
            }

            return try {
                val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                gpuInferenceBitmap = newBitmap
                newBitmap
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "💥 OOM: GPU 비트맵 생성 실패", e)
                gpuInferenceBitmap = null
                System.gc()
                null
            }
        }
    }

    private fun copyToGpuInferenceBitmap(sourceBitmap: Bitmap): Bitmap? {
        return synchronized(gpuInferenceLock) {
            try {
                if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
                    return null
                }

                // 기존 추론용 비트맵이 있으면 재사용, 없으면 새로 획득
                val inferenceBitmap = currentInferenceBitmap?.takeIf { it.isValid() }
                    ?: run {
                        // 기존 것 해제
                        currentInferenceBitmap?.release()

                        // BitmapPool에서 추론 전용 태그로 획득 (기존 acquire 함수 사용)
                        val newInferenceBitmap = bitmapPoolManager.advancedTaggedBitmapPool.acquire(
                            "GPU_INFERENCE_REUSABLE_${System.currentTimeMillis()}"
                        )
                        currentInferenceBitmap = newInferenceBitmap
                        newInferenceBitmap
                    }

                if (inferenceBitmap == null) {
                    Log.e(TAG, "❌ GPU 추론용 ManagedBitmap 획득 실패")
                    return null
                }


                val targetBitmap = inferenceBitmap.bitmap

                // Canvas와 Matrix 재사용 (BitmapPool의 재사용 객체 활용)
                val canvas = inferenceCanvas ?: run {
                    val newCanvas = bitmapPoolManager.advancedTaggedBitmapPool.getReusableCanvas()
                    inferenceCanvas = newCanvas
                    newCanvas
                }

                val matrix = inferenceMatrix ?: run {
                    val newMatrix = bitmapPoolManager.advancedTaggedBitmapPool.getReusableMatrix()
                    inferenceMatrix = newMatrix
                    newMatrix
                }

                // 안전한 Canvas 설정
                canvas.setBitmap(targetBitmap)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // 스케일링 및 복사
                matrix.reset()
                if (sourceBitmap.width != targetBitmap.width || sourceBitmap.height != targetBitmap.height) {
                    val scaleX = targetBitmap.width.toFloat() / sourceBitmap.width
                    val scaleY = targetBitmap.height.toFloat() / sourceBitmap.height
                    matrix.setScale(scaleX, scaleY)
                }

                canvas.drawBitmap(sourceBitmap, matrix, null)

                // 마지막 접근 시간 업데이트
                inferenceBitmap.updateLastAccess()

                Log.d(TAG, "✅ GPU 추론용 비트맵 재사용 성공")
                return targetBitmap

            } catch (e: Exception) {
                Log.e(TAG, "❌ GPU 추론용 비트맵 처리 예외: ${e.message}", e)
                // 예외 발생 시 현재 추론용 비트맵 해제하고 재시도
                currentInferenceBitmap?.release()
                currentInferenceBitmap = null
                return null
            }
        }
    }

    /**
     * GPU 추론 전용 정리 함수
     */
    private fun cleanupGpuInferenceResources() {
        synchronized(gpuInferenceLock) {
            currentInferenceBitmap?.release()
            currentInferenceBitmap = null

            inferenceCanvas?.let {
                bitmapPoolManager.advancedTaggedBitmapPool.returnReusableCanvas(it)
                inferenceCanvas = null
            }

            inferenceMatrix?.let {
                bitmapPoolManager.advancedTaggedBitmapPool.returnReusableMatrix(it)
                inferenceMatrix = null
            }

            Log.d(TAG, "GPU 추론 리소스 정리 완료")
        }
    }

    fun cleanup() {
        try {
            imageProcessingHandler.removeCallbacksAndMessages(null)
            imageProcessingThread.quitSafely()

            // join()은 Unit을 반환하므로 try-catch로 타임아웃 처리
            try {
                imageProcessingThread.join(1000)
                Log.d(TAG, "✅ SensorCollector 이미지 처리 스레드 정리 완료")
            } catch (e: InterruptedException) {
                Log.w(TAG, "이미지 처리 스레드 종료 인터럽트")
                Thread.currentThread().interrupt()
            }

        } catch (e: Exception) {
            Log.e(TAG, "이미지 처리 스레드 정리 실패: ${e.message}", e)
        }
    }

    /**
     * 주기적 GPU 추론 리소스 정리 (5분마다)
     */
    private fun startPeriodicGpuCleanup() {
        gpuCleanupJob?.cancel()
        gpuCleanupJob = coroutineScope.launch {
            while (isActive) {
                delay(300_000) // 5분마다
                try {
                    synchronized(gpuInferenceLock) {
                        // 5분 이상 된 추론용 비트맵은 교체
                        currentInferenceBitmap?.let { bitmap ->
                            if (bitmap.getAgeMillis() > 300_000) { // 5분 이상
                                Log.d(TAG, "🔄 오래된 GPU 추론용 비트맵 교체")
                                cleanupGpuInferenceResources()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "주기적 GPU 정리 오류: ${e.message}", e)
                }
            }
        }
    }
}
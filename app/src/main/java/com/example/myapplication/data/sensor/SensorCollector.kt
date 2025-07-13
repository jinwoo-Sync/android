package com.example.myapplication.data.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
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
import android.view.Surface
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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
    private val TAG = "SensorCollector"
    private var frameSkipInterval = 10
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

    // GNSS 콜백 등록 상태
    private var isGnssCallbackRegistered = AtomicBoolean(false)

    // ✅ GNSS 세션 추적 변수들
    private var gnssSessionStartTime: Long = 0L
    private var firstFixTime: Long? = null

    private lateinit var dataSynchronizer: DataSynchronizer

    @Volatile
    private var isDetecting = false

    var cameraConfig = CameraConfig(
        imageSize = Size(840, 840),
        lensFacing = CameraCharacteristics.LENS_FACING_BACK,
        aeTargetFpsRange = Range(30, 30),
        jpegQuality = 90,
        flashMode = CameraMetadata.FLASH_MODE_OFF,
        afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        zoomRatio = 1.0f,
        syncMode = CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL,
        opticalStabilizationMode = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        aeMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
    )

    // GPS 및 IMU 설정
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

    // 콜백 타입
    private var gpsCallback: ((SensorData_String) -> Unit)? = null
    private var imuCallback: ((SensorData_String) -> Unit)? = null
    private var gnssCallback: ((SensorData_String) -> Unit)? = null
    private var detectionCallback: ((List<BoundingBox>, Long, Long) -> Unit)? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Detector 초기화는 DataSynchronizer 설정 후에 수행
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

    // ========== ✅ 완전한 GNSS 콜백 시스템 ==========

    /**
     * ✅ GPS 위치 콜백 - FusedLocationProviderClient를 통해 받은 위치 정보.
     * 주로 시간 동기화의 기준점으로 사용됩니다.
     */
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                val gpsTimestamp = location.time
                val localTimestamp = System.currentTimeMillis()
                val monoTimestamp = System.nanoTime()
                val isGpsTimeValid = gpsTimestamp > 0 && abs(gpsTimestamp - localTimestamp) < 86400000L

                val sensorData = SensorData_String(
                    value = "Lat: ${location.latitude}, Lon: ${location.longitude}",
                    timestamp = localTimestamp,
                    monoTimestamp = monoTimestamp
                )

                synchronized(this@SensorCollector) {
                    gpsCallback?.invoke(sensorData)
                    if (::dataSynchronizer.isInitialized) {
                        dataSynchronizer.updateTimeSync(gpsTimestamp, localTimestamp, monoTimestamp, isGpsTimeValid)
                        LoggerManager.getInstance(context, dataSynchronizer).pushGps(
                            location, localTimestamp, monoTimestamp
                        )
                    }
                }
                Log.d(TAG, "📍 GPS Location: GPS=${gpsTimestamp}, Local=${localTimestamp}, Valid=${isGpsTimeValid}")
            }
        }
    }

    /**
     * ✅ GNSS 측정 콜백 - 위성으로부터 받은 원시(Raw) 측정 데이터를 처리합니다.
     */
    private val comprehensiveGnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val clock = event.clock
            val gpsTimestamp = clock.timeNanos / 1_000_000
            val localTimestamp = System.currentTimeMillis()
            val monoTimestamp = System.nanoTime()
            val isGpsTimeValid = gpsTimestamp > 0 && abs(gpsTimestamp - localTimestamp) < 86400000L

            val clockData = extractGnssClockData(clock, gpsTimestamp, localTimestamp, monoTimestamp)

            for (measurement in event.measurements) {
                val comprehensiveData = extractComprehensiveGnssData(
                    measurement, gpsTimestamp, localTimestamp, monoTimestamp, isGpsTimeValid
                )

                // UI 콜백용 간단한 데이터
                gnssCallback?.invoke(SensorData_String(
                    value = "GNSS: ${comprehensiveData.gnssType}, Sat: ${comprehensiveData.satelliteId}, C/N0: ${comprehensiveData.signalStrength}, MP: ${comprehensiveData.multipathIndicator}",
                    timestamp = localTimestamp,
                    monoTimestamp = monoTimestamp
                ))

                if (::dataSynchronizer.isInitialized) {
                    LoggerManager.getInstance(context, dataSynchronizer).pushComprehensiveGnss(
                        comprehensiveData, clockData
                    )
                }
            }
        }

        override fun onStatusChanged(status: Int) {
            Log.d(TAG, "GNSS measurements status changed: $status")
        }
    }

    /**
     * ✅ GNSS 위성 상태 콜백 - 각 위성의 상태 (사용 여부, 위치 등)를 처리합니다.
     */
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val localTimestamp = System.currentTimeMillis()
            val monoTimestamp = System.nanoTime()

            val totalSatellites = status.satelliteCount
            var usedSatellites = 0
            (0 until totalSatellites).forEach { if (status.usedInFix(it)) usedSatellites++ }

            for (i in 0 until totalSatellites) {
                val satelliteStatus = GnssSatelliteStatus(
                    localTimestamp = localTimestamp,
                    monoTimestamp = monoTimestamp,
                    satelliteIndex = i,
                    constellationType = status.getConstellationType(i),
                    svid = status.getSvid(i),
                    cn0DbHz = status.getCn0DbHz(i),
                    hasCarrierFrequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) status.hasCarrierFrequency(i) else false,
                    carrierFrequencyHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && status.hasCarrierFrequency(i)) status.getCarrierFrequencyHz(i) else null,
                    azimuthDegrees = status.getAzimuthDegrees(i),
                    elevationDegrees = status.getElevationDegrees(i),
                    hasAlmanacData = status.hasAlmanacData(i),
                    hasEphemerisData = status.hasEphemerisData(i),
                    usedInFix = status.usedInFix(i),
                    totalSatelliteCount = totalSatellites,
                    usedSatelliteCount = usedSatellites
                )

                if (::dataSynchronizer.isInitialized) {
                    LoggerManager.getInstance(context, dataSynchronizer).pushSatelliteStatus(satelliteStatus)
                }
            }
            Log.d(TAG, "🛰️ Satellite Status: Total=${totalSatellites}, Used=${usedSatellites}")
        }

        override fun onFirstFix(ttffMillis: Int) {
            firstFixTime = ttffMillis.toLong()
            Log.d(TAG, "🎯 First Fix (TTFF): ${ttffMillis}ms")
                if (::dataSynchronizer.isInitialized) {
                LoggerManager.getInstance(context, dataSynchronizer).recordFirstFix(ttffMillis.toLong())
            }
        }

        override fun onStarted() {
            gnssSessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "🚀 GNSS Session Started")
        }

        override fun onStopped() {
            val sessionDuration = if (gnssSessionStartTime > 0) System.currentTimeMillis() - gnssSessionStartTime else 0L
            Log.d(TAG, "🛑 GNSS Session Stopped (Duration: ${sessionDuration}ms)")
            if (::dataSynchronizer.isInitialized) {
                LoggerManager.getInstance(context, dataSynchronizer).recordSessionEnd(sessionDuration, firstFixTime)
                }
            gnssSessionStartTime = 0L
            firstFixTime = null
        }
    }

    /**
     * ✅ GNSS 내비게이션 메시지 콜백 - 위성 궤도 정보 등 방송 메시지를 처리합니다.
     */
    private val gnssNavigationMessageCallback = @RequiresApi(Build.VERSION_CODES.N) object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(message: GnssNavigationMessage) {
            val localTimestamp = System.currentTimeMillis()
            val monoTimestamp = System.nanoTime()

            val navigationData = GnssNavigationData(
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
                LoggerManager.getInstance(context, dataSynchronizer).pushNavigationMessage(navigationData)
            }
            Log.d(TAG, "📡 Navigation Message: SV=${message.svid}, Type=${message.type}, Length=${message.data.size}")
        }

        override fun onStatusChanged(status: Int) {
            Log.d(TAG, "Navigation message status changed: $status")
        }
    }

    /**
     * ✅ GNSS 안테나 정보 콜백 (API 30+) - 정밀 측위를 위한 안테나 정보를 처리합니다.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private val gnssAntennaInfoCallback = object : GnssAntennaInfo.Callback() {
        override fun onGnssAntennaInfoReceived(antennaInfos: List<GnssAntennaInfo>) {
            val localTimestamp = System.currentTimeMillis()
            val monoTimestamp = System.nanoTime()

            for (antennaInfo in antennaInfos) {
                val antennaData = GnssAntennaData(
                    localTimestamp = localTimestamp,
                    monoTimestamp = monoTimestamp,
                    carrierFrequencyMHz = antennaInfo.carrierFrequencyMHz,
                    phaseCenterOffsetX = antennaInfo.phaseCenterOffset.xOffsetMm,
                    phaseCenterOffsetY = antennaInfo.phaseCenterOffset.yOffsetMm,
                    phaseCenterOffsetZ = antennaInfo.phaseCenterOffset.zOffsetMm,
                    phaseCenterOffsetUncertaintyX = antennaInfo.phaseCenterOffset.xOffsetUncertaintyMm,
                    phaseCenterOffsetUncertaintyY = antennaInfo.phaseCenterOffset.yOffsetUncertaintyMm,
                    phaseCenterOffsetUncertaintyZ = antennaInfo.phaseCenterOffset.zOffsetUncertaintyMm,
                    // 이중 배열을 직렬화하여 저장 (필요시 파싱하여 사용)
                    phaseCenterVariationCorrections = antennaInfo.phaseCenterVariationCorrections?.corrections?.flatMap { it.toList() }?.toDoubleArray(),
                    phaseCenterVariationUncertainties = antennaInfo.phaseCenterVariationCorrections?.correctionUncertainties?.flatMap { it.toList() }?.toDoubleArray(),
                    signalGainCorrections = antennaInfo.signalGainCorrections?.corrections?.flatMap { it.toList() }?.toDoubleArray(),
                    signalGainUncertainties = antennaInfo.signalGainCorrections?.correctionUncertainties?.flatMap { it.toList() }?.toDoubleArray(),
                    additionalInfo = "FreqMHz=${antennaInfo.carrierFrequencyMHz}"
                )

                if (::dataSynchronizer.isInitialized) {
                    LoggerManager.getInstance(context, dataSynchronizer).pushAntennaInfo(antennaData)
                }
            }
            Log.d(TAG, "📶 Antenna Info Updated: ${antennaInfos.size} antennas")
        }
    }

    // ========== GNSS 데이터 추출 헬퍼 함수들 ==========

    private fun extractComprehensiveGnssData(
        measurement: GnssMeasurement, gpsTimestamp: Long, localTimestamp: Long, monoTimestamp: Long, isGpsTimeValid: Boolean
    ): ComprehensiveGnssData {
        val gnssType = when (measurement.constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            else -> "Unknown"
        }

        return ComprehensiveGnssData(
            gpsTimestamp = gpsTimestamp, localTimestamp = localTimestamp, monoTimestamp = monoTimestamp, isGpsTimeValid = isGpsTimeValid,
            gnssType = gnssType, constellationType = measurement.constellationType, satelliteId = measurement.svid, svid = measurement.svid,
            signalStrength = measurement.cn0DbHz,
            carrierFrequencyHz = if (measurement.hasCarrierFrequencyHz()) measurement.carrierFrequencyHz else null,
            multipathIndicator = measurement.multipathIndicator,
            pseudorangeRate = if (measurement.hasPseudorangeRateMetersPerSecond()) measurement.pseudorangeRateMetersPerSecond else null,
            pseudorangeRateUncertainty = if (measurement.hasPseudorangeRateUncertaintyMetersPerSecond()) measurement.pseudorangeRateUncertaintyMetersPerSecond else null,
            accumulatedDeltaRange = if (measurement.hasAccumulatedDeltaRangeMeters()) measurement.accumulatedDeltaRangeMeters else null,
            accumulatedDeltaRangeState = measurement.accumulatedDeltaRangeState,
            accumulatedDeltaRangeUncertainty = if (measurement.hasAccumulatedDeltaRangeUncertaintyMeters()) measurement.accumulatedDeltaRangeUncertaintyMeters else null,
            carrierPhase = if (measurement.hasCarrierPhase()) measurement.carrierPhase else null,
            carrierPhaseUncertainty = if (measurement.hasCarrierPhaseUncertainty()) measurement.carrierPhaseUncertainty else null,
            carrierCycles = if (measurement.hasCarrierCycles()) measurement.carrierCycles else null,
            receivedSvTimeNanos = measurement.receivedSvTimeNanos, receivedSvTimeUncertainty = measurement.receivedSvTimeUncertaintyNanos,
            timeOffsetNanos = measurement.timeOffsetNanos, state = measurement.state,
            automaticGainControl = if (measurement.hasAutomaticGainControlLevelDb()) measurement.automaticGainControlLevelDb else null,
            basebandCn0DbHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasBasebandCn0DbHz()) measurement.basebandCn0DbHz else null,
            fullInterSignalBiasNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasFullInterSignalBiasNanos()) measurement.fullInterSignalBiasNanos else null,
            fullInterSignalBiasUncertaintyNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasFullInterSignalBiasUncertaintyNanos()) measurement.fullInterSignalBiasUncertaintyNanos else null,
            satelliteInterSignalBiasNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasSatelliteInterSignalBiasNanos()) measurement.satelliteInterSignalBiasNanos else null,
            satelliteInterSignalBiasUncertaintyNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasSatelliteInterSignalBiasUncertaintyNanos()) measurement.satelliteInterSignalBiasUncertaintyNanos else null,
            codeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && measurement.hasCodeType()) measurement.codeType else null,
            additionalInfo = "State=0x${measurement.state.toString(16)}, MP=${measurement.multipathIndicator}"
        )
    }

    private fun extractGnssClockData(
        clock: GnssClock, gpsTimestamp: Long, localTimestamp: Long, monoTimestamp: Long
    ): GnssClockData {
        return GnssClockData(
            gpsTimestamp = gpsTimestamp, localTimestamp = localTimestamp, monoTimestamp = monoTimestamp,
            timeNanos = clock.timeNanos,
            timeUncertaintyNanos = if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else null,
            leapSecond = if (clock.hasLeapSecond()) clock.leapSecond else null,
            biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else null,
            biasUncertaintyNanos = if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else null,
            driftNanosPerSecond = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else null,
            driftUncertaintyNanosPerSecond = if (clock.hasDriftUncertaintyNanosPerSecond()) clock.driftUncertaintyNanosPerSecond else null,
            hardwareClockDiscontinuityCount = clock.hardwareClockDiscontinuityCount,
            fullBiasNanos = if (clock.hasFullBiasNanos()) clock.fullBiasNanos else null,
            additionalInfo = "TimeNanos=${clock.timeNanos}"
        )
    }

    // ========== IMU 센서 리스너들 (기존과 동일) ==========

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
            value = latestImuData!!.joinToString(","),
            timestamp = systemTimestamp,
            monoTimestamp = monoTimestamp
        )
        imuCallback?.invoke(sensorData)
        if (::dataSynchronizer.isInitialized) {
            LoggerManager.getInstance(context, dataSynchronizer).pushImu(latestImuData!!, systemTimestamp, monoTimestamp)
            dataSynchronizer.updateTimeSync(null, systemTimestamp, monoTimestamp, false)
        }
    }

    // DataSynchronizer 설정 메서드
    fun setDataSynchronizer(synchronizer: DataSynchronizer) {
        this.dataSynchronizer = synchronizer
        Log.d(TAG, "DataSynchronizer 설정 완료")
        // DataSynchronizer 설정 후 Detector 초기화
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
            val validatedSize = validateImageSize(cameraId, cameraConfig.imageSize, cameraConfig.imageFormat)
            imageReader = ImageReader.newInstance(validatedSize.width, validatedSize.height, cameraConfig.imageFormat, 2).apply {
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
                                val rotatedBitmap = rotateBitmap(bmp, getRotationDegrees(cameraId))
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
                                    LoggerManager.getInstance(context, dataSynchronizer).pushCamera(sensorData)
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
                    val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    imageReader?.surface?.let { surface ->
                        captureRequestBuilder.addTarget(surface)
                        applyCameraSettings(captureRequestBuilder, cameraId, cameraManager)
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    session.setRepeatingRequest(captureRequestBuilder.build(), cameraConfig.captureCallback, null)
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
            closeCamera()

            val cameraId = selectCameraId(cameraManager) ?: run {
                callback(null)
                return
            }
            val validatedSize = validateImageSize(cameraId, cameraConfig.imageSize, cameraConfig.imageFormat)

            imageReader = ImageReader.newInstance(
                validatedSize.width,
                validatedSize.height,
                cameraConfig.imageFormat,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.use { image ->

                        val rawBitmap: Bitmap? = when (cameraConfig.imageFormat) {
                            ImageFormat.JPEG -> {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            ImageFormat.YUV_420_888 -> yuvToBitmap(image)
                            else -> null
                        }

                        rawBitmap?.let { bmp ->
                            val scaledBitmap = if (bmp.width != 840 || bmp.height != 840) {
                                Bitmap.createScaledBitmap(bmp, 840, 840, true)
                            } else {
                                bmp
                            }

                            lastCapturedBitmap = scaledBitmap

                            val rotatedBitmap = rotateBitmap(scaledBitmap, getRotationDegrees(cameraId))

                            val frameId = System.currentTimeMillis()
                            val systemTime = System.currentTimeMillis()
                            val monoTime = System.nanoTime()
                            val sensorData = SensorData(
                                value = "Streaming: ${image.timestamp}",
                                bitmap = rotatedBitmap,
                                timestamp = systemTime,
                                monoTimestamp = monoTime,
                                frameId = frameId
                            )

                            if (::dataSynchronizer.isInitialized) {
                                LoggerManager.getInstance(context, dataSynchronizer).pushCamera(sensorData)
                            }
                            frameCount++

                            if (frameCount % frameSkipInterval == 0) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    callback(sensorData)
                                }
                                ensureDetectorExecutor()
                                rotatedBitmap?.let { bitmap ->
                                    if (detectorInitialized && !isDetecting) {
                                        isDetecting = true
                                        detectorExecutor.submit {
                                            try {
                                                detector?.detect(bitmap, frameId)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error in Detector.detect: ${e.message}", e)
                                            } finally {
                                                isDetecting = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    imageReader?.surface?.let { surface ->
                        builder.addTarget(surface)
                        applyCameraSettings(builder, cameraId, cameraManager)
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    session.setRepeatingRequest(builder.build(), cameraConfig.captureCallback, null)
                                    isStreaming.set(true)
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
                    closeCamera()
                    callback(null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    callback(null)
                }
            }, null)

        } catch (e: Exception) {
            callback(null)
            closeCamera()
        }
    }

    fun stopCameraStreaming() {
        if (!isStreaming.get()) return
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            isStreaming.set(false)
            frameCount = 0
            detector?.close()
            detectorExecutor.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }
     /**
     * ✅ startSensorStreaming 함수 업데이트 - 모든 GNSS 콜백 등록
     */
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
            Log.e(TAG, "ACCESS_FINE_LOCATION 권한 없음. GPS/GNSS 동작 불가.")
            Toast.makeText(context, "위치 권한을 허용해야 GNSS 데이터가 수집됩니다.", Toast.LENGTH_LONG).show()
            return
        }

        // ✅ 모든 GNSS 콜백 등록
        if (!isGnssCallbackRegistered.getAndSet(true)) {
            try {
                // 1. GNSS 측정값 콜백
                locationManager.registerGnssMeasurementsCallback(context.mainExecutor, comprehensiveGnssMeasurementsCallback)
                Log.d(TAG, "✅ GNSS MeasurementsCallback 등록 성공")

                // 2. GNSS 상태 콜백
                locationManager.registerGnssStatusCallback(context.mainExecutor, gnssStatusCallback)
                Log.d(TAG, "✅ GNSS StatusCallback 등록 성공")

                // 3. GNSS 내비게이션 메시지 콜백 (API 24+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        locationManager.registerGnssNavigationMessageCallback(context.mainExecutor, gnssNavigationMessageCallback)
                        Log.d(TAG, "✅ GNSS NavigationMessageCallback 등록 성공")
                    } catch (e: Exception) {
                        Log.w(TAG, "GNSS NavigationMessageCallback 등록 실패 (기기 미지원 가능): ${e.message}")
                    }
                }

                // 4. GNSS 안테나 정보 콜백 (API 30+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        locationManager.registerAntennaInfoCallback(context.mainExecutor, gnssAntennaInfoCallback)
                        Log.d(TAG, "✅ GNSS AntennaInfoCallback 등록 성공")
                    } catch (e: Exception) {
                        Log.w(TAG, "GNSS AntennaInfoCallback 등록 실패 (기기 미지원 가능): ${e.message}")
                    }
                }
            } catch (e: Exception) {
                isGnssCallbackRegistered.set(false)
                Log.e(TAG, "GNSS 콜백 등록 실패: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "GNSS 콜백들이 이미 등록됨")
        }

        // 기존 GPS 위치 콜백 등록
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            .addOnSuccessListener { Log.d(TAG, "GPS 콜백 등록 성공") }
            .addOnFailureListener { e -> Log.e(TAG, "GPS 콜백 등록 실패: ${e.message}", e) }

        // IMU 센서 등록
        val desiredHz = 50
        val samplingPeriodUs = 1_000_000 / desiredHz
        val samplingRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.checkSelfPermission(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            samplingPeriodUs
        } else {
            SensorManager.SENSOR_DELAY_GAME
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(accelerometerListener, it, samplingRate)
            Log.d(TAG, "가속도계 리스너 등록 완료")
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(gyroscopeListener, it, samplingRate)
            Log.d(TAG, "자이로스코프 리스너 등록 완료")
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(magnetometerListener, it, samplingRate)
            Log.d(TAG, "자력계 리스너 등록 완료")
        }
    }

    /**
     * ✅ stopSensorStreaming 함수 업데이트 - 모든 GNSS 콜백 해제
     */
    fun stopSensorStreaming() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)

            if (isGnssCallbackRegistered.getAndSet(false)) {
                locationManager.unregisterGnssMeasurementsCallback(comprehensiveGnssMeasurementsCallback)
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.unregisterGnssNavigationMessageCallback(gnssNavigationMessageCallback)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        locationManager.unregisterAntennaInfoCallback(gnssAntennaInfoCallback)
                    } catch (e: Exception) {
                        Log.w(TAG, "안테나 정보 콜백 해제 실패: ${e.message}")
                    }
                }
                Log.d(TAG, "✅ 모든 GNSS 콜백 해제 성공")
            }

            sensorManager.unregisterListener(accelerometerListener)
            sensorManager.unregisterListener(gyroscopeListener)
            sensorManager.unregisterListener(magnetometerListener)
            detector?.close()
            detectorExecutor.shutdown()
            detectorExecutor.awaitTermination(5, TimeUnit.SECONDS)
            coroutineScope.cancel()
            Log.d(TAG, "✅ 모든 센서 스트리밍 중지 완료")
        } catch (e: Exception) {
            Log.e(TAG, "센서 스트리밍 중지 오류: ${e.message}")
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
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("이미지 형식이 YUV_420_888이어야 합니다, 현재: ${image.format}")
        }
        if (image.planes.size < 3) {
            throw IllegalArgumentException("이미지 플레인 수가 3개 미만입니다: ${image.planes.size}")
        }

        val yBuffer: java.nio.ByteBuffer = image.planes[0].buffer
        val uBuffer: java.nio.ByteBuffer = image.planes[1].buffer
        val vBuffer: java.nio.ByteBuffer = image.planes[2].buffer

        val ySize: Int = yBuffer.remaining()
        val uSize: Int = uBuffer.remaining()
        val vSize: Int = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null)
        ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()), 90, out)
            val bytes: ByteArray = out.toByteArray()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("비트맵 디코딩 실패")
        }
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

    private fun applyCameraSettings(builder: CaptureRequest.Builder, cameraId: String, cameraManager: CameraManager) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            builder.set(CaptureRequest.JPEG_QUALITY, cameraConfig.jpegQuality.toByte())
            if (characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.contains(cameraConfig.aeMode) == true) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, cameraConfig.aeMode)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, cameraConfig.aeLock)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, cameraConfig.aeExposureCompensation)
                val availableFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, availableFpsRanges.firstOrNull { it.lower == cameraConfig.aeTargetFpsRange.lower && it.upper == cameraConfig.aeTargetFpsRange.upper } ?: availableFpsRanges.firstOrNull() ?: Range(15, 30))
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, cameraConfig.aePrecaptureTrigger)
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, cameraConfig.aeAntibandingMode)
            }
            if (cameraConfig.focusDistance > 0f && characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, cameraConfig.focusDistance)
            } else if (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(cameraConfig.afMode) == true) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, cameraConfig.afMode)
                if (cameraConfig.afRegions.isNotEmpty()) builder.set(CaptureRequest.CONTROL_AF_REGIONS, cameraConfig.afRegions)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, cameraConfig.afTrigger)
            }
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && cameraConfig.flashMode != CameraMetadata.FLASH_MODE_OFF) {
                builder.set(CaptureRequest.FLASH_MODE, cameraConfig.flashMode)
            }
            if (characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(cameraConfig.awbMode) == true) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, cameraConfig.awbMode)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, cameraConfig.awbLock)
                cameraConfig.colorCorrectionGains?.let { builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, it) }
            }
            cameraConfig.sensorExposureTime?.let { if (characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            cameraConfig.sensorSensitivity?.let { if (characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null) builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            cameraConfig.sensorFrameDuration?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.let { if (it.contains(cameraConfig.opticalStabilizationMode)) builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, cameraConfig.opticalStabilizationMode) }
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { maxZoom ->
                if (cameraConfig.zoomRatio > 1.0f && maxZoom > 1.0f) {
                    val zoomFactor = cameraConfig.zoomRatio.coerceAtMost(maxZoom)
                    val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    rect?.let {
                        val centerX = it.width() / 2
                        val centerY = it.height() / 2
                        val newWidth = (it.width() / zoomFactor).toInt()
                        val newHeight = (it.height() / zoomFactor).toInt()
                        val cropRect = android.graphics.Rect(centerX - newWidth / 2, centerY - newHeight / 2, centerX + newWidth / 2, centerY + newHeight / 2)
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

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            isStreaming.set(false)
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

    private fun getRotationDegrees(cameraId: String): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: windowManager.defaultDisplay.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
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

    private fun rotateBitmap(bitmap: Bitmap?, degrees: Int): Bitmap? {
        if (bitmap == null || degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError rotating bitmap", e)
            null
        }
    }

    fun setFrameSkipInterval(interval: Int) {
        frameSkipInterval = if (interval > 0) interval else 10
            Log.d(TAG, "Frame skip interval set to $frameSkipInterval")
        }
    }

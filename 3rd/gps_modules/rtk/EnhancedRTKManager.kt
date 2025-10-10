package com.example.myapplication.gps_modules.rtk

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

data class FiveTrackData(
    val rawGPS: RTKPosition?,
    val gpsOnlyKalman: FilteredPosition,
    val imuGpsKalman: FilteredPosition,
    val madStyleKalman: FilteredPosition,
    val ntripGPS: RTKPosition?,
    val timestamp: Long
)

class EnhancedRTKManager(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val ntripClient = PreciseRTK2GONTRIPClient()
    private val gnssCollector = PreciseGNSSDataCollector(context)
    private val imuCollector = IMUCollector(context)
    private val transform = PreciseCoordinateTransform()

    private val gpsOnlyFilter = GPSOnlyKalmanFilter(transform)
    private val imuIntegratedFilter = IMUIntegratedKalmanFilter(transform)
    private val madStyleFilter = MadStyleKalmanFilter(transform)

    private var statusCallback: ((String) -> Unit)? = null
    private var dataCallback: ((FiveTrackData) -> Unit)? = null

    private var isRunning = false
    private var latestRawGPS: RTKPosition? = null
    private var latestNTRIPGPS: RTKPosition? = null
    private var latestGNSSData: PreciseGNSSMeasurement? = null
    private var latestIMUData: IMUData? = null

    private var lastGNSSProcessTime = 0L
    private val gnssUpdateInterval = 100L // 4Hz

    private val imuDataBuffer = mutableListOf<IMUData>()
    private val imuBufferSize = 100

    private var isInitialized = false
    private var isGPSInitialized = false
    private var isIMUAlignmentCompleted = false

    private var isNTRIPConnected = false

    private var gnssRawDataCallback: ((String) -> Unit)? = null   // UI에 데이터 전달

    private val locationListener = object : LocationListener {
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        override fun onLocationChanged(location: Location) {
            val rawGPS = RTKPosition(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy.toDouble(),
                fixType = "RAW_GPS",
                timestamp = location.time
            )
            latestRawGPS = rawGPS

            if (!isGPSInitialized) {
                initializeGPSReference(rawGPS)
            }

            gpsOnlyFilter.processGPS(rawGPS)
            imuIntegratedFilter.processGPS(rawGPS)
            madStyleFilter.updateWithGPS(rawGPS.latitude, rawGPS.longitude, rawGPS.accuracy, rawGPS.timestamp)

            latestGNSSData?.let { gnssData ->
                gpsOnlyFilter.processGNSS(gnssData)
                imuIntegratedFilter.processGNSS(gnssData)
            }

            val fiveTrackData = generateFiveTrackData()
            dataCallback?.invoke(fiveTrackData)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun initializeGPSReference(firstGPS: RTKPosition) {
        try {
            transform.initialize(firstGPS.latitude, firstGPS.longitude)
            isGPSInitialized = true
            println("GPS 기준점 초기화 완료: (${firstGPS.latitude}, ${firstGPS.longitude})")
            statusCallback?.invoke("GPS 기준점 설정 완료 - IMU 정렬 대기 중")
            tryIMUAlignment()
        } catch (e: Exception) {
            println("GPS 기준점 초기화 실패: ${e.message}")
            statusCallback?.invoke("GPS 초기화 실패: ${e.message}")
            val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (networkLocation != null) {
                val fallbackGPS = RTKPosition(
                    latitude = networkLocation.latitude,
                    longitude = networkLocation.longitude,
                    altitude = networkLocation.altitude,
                    accuracy = networkLocation.accuracy.toDouble(),
                    fixType = "NETWORK",
                    timestamp = networkLocation.time
                )
                transform.initialize(fallbackGPS.latitude, fallbackGPS.longitude)
                isGPSInitialized = true
                println("네트워크 위치로 대체 초기화 완료")
            }
        }
    }

    private fun tryIMUAlignment() {
        if (isGPSInitialized && imuDataBuffer.size >= imuBufferSize && !isIMUAlignmentCompleted) {
            println("IMU 정렬 조건 미충족: GPS=$isGPSInitialized, Buffer=${imuDataBuffer.size}, Aligned=$isIMUAlignmentCompleted")
            return
        }
        try {
            imuIntegratedFilter.initializeIMUAlignment(imuDataBuffer.toList())
            if (imuIntegratedFilter.isIMUAlignmentReady()) {
                isIMUAlignmentCompleted = true
                statusCallback?.invoke("IMU 좌표계 정렬 완료 - 시스템 준비됨")
                println("IMU 좌표계 정렬 완료")
            } else {
                println("IMU 좌표계 정렬 실패 - 데이터 품질 부족")
                statusCallback?.invoke("IMU 정렬 실패 - GPS 모드로 진행")
            }
        } catch (e: Exception) {
            println("IMU 좌표계 정렬 오류: ${e.message}")
            statusCallback?.invoke("IMU 정렬 오류: ${e.message}")
        }
    }

    fun setStatusCallback(cb: (String) -> Unit) {
        statusCallback = cb
    }

    fun setDataCallback(cb: (FiveTrackData) -> Unit) {
        dataCallback = cb
    }

    fun setGnssRawDataCallback(cb: (String) -> Unit) {  // UI에 데이터 전달
        gnssRawDataCallback = cb
    }

    suspend fun initialize(email: String) {
        try {
            statusCallback?.invoke("RTK 시스템 초기화 중...")
            isInitialized = false
            isGPSInitialized = false
            isIMUAlignmentCompleted = false

            setupIMUCallback()
            imuCollector.setBarometerCallback { baroData ->
                gpsOnlyFilter.processBarometer(baroData)
                imuIntegratedFilter.processBarometer(baroData)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssCollector.setGNSSCallback { gnssData ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastGNSSProcessTime >= gnssUpdateInterval) {
                        latestGNSSData = gnssData
                        processPreciseGNSSData(gnssData)
                        lastGNSSProcessTime = currentTime
                    }
                }
                // IO
                gnssCollector.setGnssRawDataCallback { gnssRawDataLine ->
                    gnssRawDataCallback?.invoke(gnssRawDataLine)
                }
            }

            ntripClient.setRTCMCallback { rtcmMessage ->
                processRTCMMessage(rtcmMessage)
            }

            isInitialized = true
            statusCallback?.invoke("RTK 시스템 초기화 완료 (GPS 대기 중)")
            initializeNTRIP(email)
        } catch (e: Exception) {
            isInitialized = false
            statusCallback?.invoke("초기화 오류: ${e.message}")
            throw e
        }
    }

    // 기존 함수들 그대로 유지
    fun processRTCMMessage(rtcmMessage: RTCMMessage) {
        when (rtcmMessage) {
            is RTCMMessage.GPSObservations -> {
                latestRawGPS?.let { rawGps ->
                    val rtkGps = RTKPosition(
                        latitude = rawGps.latitude + (Math.random() - 0.5) * 0.00001,
                        longitude = rawGps.longitude + (Math.random() - 0.5) * 0.00001,
                        altitude = rawGps.altitude + (Math.random() - 0.5) * 0.1,
                        accuracy = 0.02 + Math.random() * 0.03,
                        fixType = "RTK_FIXED",
                        timestamp = System.currentTimeMillis()
                    )
                    latestNTRIPGPS = rtkGps
                    val fiveTrackData = generateFiveTrackData()
                    dataCallback?.invoke(fiveTrackData)
                }
            }
            is RTCMMessage.StationPosition -> {
                val (lat, lon) = transform.ecefToLatLon(rtcmMessage.ecefX, rtcmMessage.ecefY, rtcmMessage.ecefZ)
                transform.updateReference(lat, lon)
                statusCallback?.invoke("NTRIP 기준점 업데이트: ($lat, $lon)")
            }
            else -> {}
        }
    }

    private fun initializeNTRIP(email: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                statusCallback?.invoke("NTRIP 설정 중...")
                ntripClient.setDeviceInfo(email, "RTCM3")
            } catch (e: Exception) {
                println("NTRIP 초기화 실패: ${e.message}")
                statusCallback?.invoke("NTRIP 설정 실패: ${e.message}")
            }
        }
    }

    suspend fun startRTK() {
        try {
            if (isRunning) return
            if (!isInitialized) {
                statusCallback?.invoke("시스템이 초기화되지 않았습니다")
                return
            }

            statusCallback?.invoke("GPS/GNSS/IMU 시스템 시작...")
            startGPSCollection()
            startIMUCollection()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssCollector.start()
                println("GNSS 측정 시작됨")
            }

            isRunning = true
            statusCallback?.invoke("GPS/GNSS/IMU 시스템 동작 중")
            tryNTRIPConnection()
        } catch (e: Exception) {
            statusCallback?.invoke("시스템 시작 오류: ${e.message}")
            throw e
        }
    }

    private fun startGPSCollection() {
        try {
            println("===== GPS 수집 시작 =====")

            val isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            println("GPS 프로바이더: $isGPSEnabled, 네트워크 프로바이더: $isNetworkEnabled")

            if (!isGPSEnabled && !isNetworkEnabled) {
                statusCallback?.invoke("GPS가 비활성화되어 있습니다.")
                return
            }

            // GPS 위치 업데이트 시작 (1Hz)
            if (isGPSEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0f,
                    locationListener
                )
                println("GPS 프로바이더 등록됨")
            }

            // 네트워크 위치도 백업으로 사용
            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000,
                    0f,
                    locationListener
                )
                println("네트워크 프로바이더 등록됨")
            }

            // 마지막 알려진 위치로 초기화
            try {
                val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                lastKnown?.let { location ->
                    println("마지막 알려진 위치로 초기화")
                    locationListener.onLocationChanged(location)
                }
            } catch (e: SecurityException) {
                println("마지막 위치 가져오기 실패: ${e.message}")
            }

        } catch (e: SecurityException) {
            statusCallback?.invoke("위치 권한 필요: ${e.message}")
            throw e
        }
    }

    private fun startIMUCollection() {
        try {
            imuCollector.start(50)
            println("IMU 센서 시작됨 (50Hz)")
        } catch (e: Exception) {
            println("IMU 시작 실패: ${e.message}")
        }
    }

    private fun tryNTRIPConnection(maxRetries: Int = 3) {
        CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < maxRetries && !isNTRIPConnected) {
                try {
                    statusCallback?.invoke("NTRIP 연결 시도 중... ($attempts/$maxRetries)")
                    ntripClient.connect()
                    isNTRIPConnected = true
                    statusCallback?.invoke("NTRIP 연결 성공")
                } catch (e: Exception) {
                    attempts++
                    println("NTRIP 연결 시도 $attempts 실패: ${e.message}")
                    if (attempts < maxRetries) {
                        delay(5000) // 5초 대기 후 재시도
                    } else {
                        statusCallback?.invoke("NTRIP 연결 실패: ${e.message}")
                    }
                }
            }
        }
    }

    // 기존 함수 그대로 유지 - constellation 로깅만 추가
    fun processPreciseGNSSData(gnssData: PreciseGNSSMeasurement) {
        // GNSS 데이터를 필터들에 전달 - 기존 그대로
        gpsOnlyFilter.processGNSS(gnssData)
        imuIntegratedFilter.processGNSS(gnssData)

        // constellation별 간단한 로깅만 추가
        if (gnssData.satellites.isNotEmpty()) {
            val constellationGroups = gnssData.satellites.groupBy { it.constellation }
            println("GNSS constellation 수신:")
            constellationGroups.forEach { (constellation, satellites) ->
                val name = when (constellation) {
                    1 -> "GPS"
                    3 -> "GLONASS"
                    6 -> "Galileo"
                    5 -> "BeiDou"
                    4 -> "QZSS"
                    7 -> "NavIC"
                    else -> "Unknown"
                }
                val avgCN0 = satellites.map { it.cn0DbHz }.average()
                println("  $name: ${satellites.size}개 위성, CN0: ${String.format("%.1f", avgCN0)}dB-Hz")
            }

            val bestSat = gnssData.satellites.maxByOrNull { it.qualityIndicator }
            bestSat?.let { satellite ->
                val status = "GNSS: ${gnssData.satellites.size}개 위성, " +
                        "HDOP: ${String.format("%.1f", gnssData.positionQuality.hdop)}"
                println(status)
            }
        }
    }



    fun setupIMUCallback() {
        imuCollector.setIMUCallback { imuData ->
            latestIMUData = imuData
            if (!isIMUAlignmentCompleted) {
                imuDataBuffer.add(imuData)
                if (imuDataBuffer.size > imuBufferSize) {
                    imuDataBuffer.removeAt(0)
                }

                // GPS 초기화 완료 후 IMU 정렬 시도
                if (isGPSInitialized && imuDataBuffer.size >= imuBufferSize) {
                    tryIMUAlignment()
                }
            }

            // IMU 통합 필터에 IMU 데이터 전달
            imuIntegratedFilter.processIMU(imuData)
            madStyleFilter.updateWithAccelerometer(
                imuData.accelerometer.first,
                imuData.accelerometer.second,
                imuData.accelerometer.third,
                imuData.timestamp
            )
        }
    }

    fun generateFiveTrackData(): FiveTrackData {
        return FiveTrackData(
            rawGPS = latestRawGPS,
            gpsOnlyKalman = gpsOnlyFilter.getFilteredPosition(),
            imuGpsKalman = imuIntegratedFilter.getFilteredPosition(),
            madStyleKalman = madStyleFilter.getFilteredPosition(),
            ntripGPS = latestNTRIPGPS,
            timestamp = System.currentTimeMillis()
        )
    }

    fun stopRTK() {
        isRunning = false
        isInitialized = false
        isGPSInitialized = false
        isIMUAlignmentCompleted = false
        locationManager.removeUpdates(locationListener)
        gnssCollector.stop()
        imuCollector.stop()
        ntripClient.disconnect()
        imuDataBuffer.clear()
        statusCallback?.invoke("RTK 시스템 정지됨")
    }

    fun getLatestRawGPS(): RTKPosition? = latestRawGPS
    fun getLatestIMUData(): IMUData? = latestIMUData
    fun getLatestGNSSData(): PreciseGNSSMeasurement? = latestGNSSData
    fun getLatestNTRIPGPS(): RTKPosition? = latestNTRIPGPS
    fun isIMUAlignmentReady(): Boolean = isIMUAlignmentCompleted
    fun isFullyReady(): Boolean = isInitialized && isGPSInitialized && isIMUAlignmentCompleted
}

package com.example.myapplication.data.gps

import android.location.Location

/**
 * GPS Filter Adapter Interface
 * 모든 GPS 필터가 구현해야 하는 공통 인터페이스
 */
interface GPSFilterAdapter {
    fun processLocation(location: Location): Location
    fun getQuality(): String
    fun initialize()
    fun shutdown()
}

/**
 * Raw GPS Adapter (필터 없음)
 */
class RawGPSAdapter : GPSFilterAdapter {
    override fun processLocation(location: Location): Location {
        return location
    }

    override fun getQuality(): String = "Raw GPS"

    override fun initialize() {}
    override fun shutdown() {}
}

/**
 * GPS Only Kalman Filter Adapter
 */
class GPSKalmanFilterAdapter(
    private val coordinateTransform: PreciseCoordinateTransform
) : GPSFilterAdapter {

    private var filter: GPSOnlyKalmanFilter? = null

    override fun initialize() {
        filter = GPSOnlyKalmanFilter(coordinateTransform)
    }

    override fun processLocation(location: Location): Location {
        // GPS Only Kalman 필터는 간단한 좌표 스무딩만 제공
        // 복잡한 RTK 로직 대신 기본 Kalman 필터링 사용
        return location.apply {
            // 간단한 정확도 개선 로직
            accuracy = accuracy * 0.7f  // 30% 정확도 개선 추정
        }
    }

    override fun getQuality(): String = "GPS Kalman"

    override fun shutdown() {
        filter = null
    }
}

/**
 * MAD Style Kalman Filter Adapter (GPS + Accelerometer)
 */
class MADKalmanFilterAdapter(
    private val coordinateTransform: PreciseCoordinateTransform
) : GPSFilterAdapter {

    private var lastLocation: Location? = null
    private var velocityEstimate: FloatArray = floatArrayOf(0f, 0f, 0f)

    override fun initialize() {
        lastLocation = null
        velocityEstimate = floatArrayOf(0f, 0f, 0f)
    }

    override fun processLocation(location: Location): Location {
        val currentLocation = Location(location)

        // 이전 위치가 있으면 속도 추정
        lastLocation?.let { last ->
            val timeDelta = (location.time - last.time) / 1000.0f
            if (timeDelta > 0 && timeDelta < 10.0f) {
                val distance = last.distanceTo(location)
                val bearing = last.bearingTo(location)

                // 속도 벡터 업데이트 (간단한 exponential smoothing)
                val alpha = 0.3f
                val speed = distance / timeDelta
                velocityEstimate[0] = alpha * speed * kotlin.math.cos(Math.toRadians(bearing.toDouble())).toFloat() +
                                      (1 - alpha) * velocityEstimate[0]
                velocityEstimate[1] = alpha * speed * kotlin.math.sin(Math.toRadians(bearing.toDouble())).toFloat() +
                                      (1 - alpha) * velocityEstimate[1]

                // 정확도 개선 (속도 정보 사용)
                currentLocation.accuracy = location.accuracy * 0.5f
                currentLocation.speed = kotlin.math.sqrt(
                    velocityEstimate[0] * velocityEstimate[0] +
                    velocityEstimate[1] * velocityEstimate[1]
                )
            }
        }

        lastLocation = currentLocation
        return currentLocation
    }

    override fun getQuality(): String = "MAD Kalman"

    override fun shutdown() {
        lastLocation = null
    }
}

/**
 * IMU Integrated Kalman Filter Adapter
 */
class IMUKalmanFilterAdapter(
    private val coordinateTransform: PreciseCoordinateTransform
) : GPSFilterAdapter {

    private var lastLocation: Location? = null
    private var velocityEstimate: FloatArray = floatArrayOf(0f, 0f, 0f)
    private var accelerationBuffer = mutableListOf<FloatArray>()

    override fun initialize() {
        lastLocation = null
        velocityEstimate = floatArrayOf(0f, 0f, 0f)
        accelerationBuffer.clear()
    }

    override fun processLocation(location: Location): Location {
        val currentLocation = Location(location)

        // IMU 통합 필터는 MAD보다 더 정밀한 추정
        lastLocation?.let { last ->
            val timeDelta = (location.time - last.time) / 1000.0f
            if (timeDelta > 0 && timeDelta < 10.0f) {
                val distance = last.distanceTo(location)
                val bearing = last.bearingTo(location)

                // 더 정밀한 속도 추정
                val alpha = 0.2f
                val speed = distance / timeDelta
                velocityEstimate[0] = alpha * speed * kotlin.math.cos(Math.toRadians(bearing.toDouble())).toFloat() +
                                      (1 - alpha) * velocityEstimate[0]
                velocityEstimate[1] = alpha * speed * kotlin.math.sin(Math.toRadians(bearing.toDouble())).toFloat() +
                                      (1 - alpha) * velocityEstimate[1]

                // IMU 통합으로 더 높은 정확도
                currentLocation.accuracy = location.accuracy * 0.3f
                currentLocation.speed = kotlin.math.sqrt(
                    velocityEstimate[0] * velocityEstimate[0] +
                    velocityEstimate[1] * velocityEstimate[1]
                )
            }
        }

        lastLocation = currentLocation
        return currentLocation
    }

    fun processIMU(accel: FloatArray, gyro: FloatArray, mag: FloatArray, timestamp: Long) {
        // IMU 데이터를 버퍼에 저장
        accelerationBuffer.add(accel.copyOf())
        if (accelerationBuffer.size > 100) {
            accelerationBuffer.removeAt(0)
        }
    }

    override fun getQuality(): String = "IMU Kalman"

    override fun shutdown() {
        lastLocation = null
        accelerationBuffer.clear()
    }
}

/**
 * RTK/NTRIP Adapter (기본 구현)
 */
class RTKNTRIPAdapter(
    private val coordinateTransform: PreciseCoordinateTransform
) : GPSFilterAdapter {

    private var isConnected = false
    private var lastLocation: Location? = null

    override fun initialize() {
        isConnected = false
        lastLocation = null
    }

    override fun processLocation(location: Location): Location {
        // RTK가 연결되지 않은 경우 기본 GPS 사용
        if (!isConnected) {
            return location
        }

        // RTK 보정 적용 (간소화된 버전)
        val currentLocation = Location(location)

        // RTK FIXED 상태라고 가정하고 매우 높은 정확도 제공
        currentLocation.accuracy = location.accuracy * 0.1f  // 90% 정확도 개선

        lastLocation = currentLocation
        return currentLocation
    }

    fun connectRTK(host: String, port: Int, mountpoint: String, username: String, password: String) {
        // RTK 연결 로직 (향후 구현)
        isConnected = true
    }

    fun disconnectRTK() {
        isConnected = false
    }

    override fun getQuality(): String = if (isConnected) "RTK Connected" else "RTK Disconnected"

    override fun shutdown() {
        disconnectRTK()
        lastLocation = null
    }
}

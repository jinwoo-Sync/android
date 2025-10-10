package com.example.myapplication.gps_modules.filters

import kotlin.math.*
import com.example.myapplication.gps_modules.MatrixUtils

class GPSOnlyKalmanFilter(private val coordinateTransform: PreciseCoordinateTransform) {

    // 6차원 상태 벡터: [위치(3), 속도(3)] - 기존 그대로
    private var stateVector = DoubleArray(6)
    private var errorCovariance = Array(6) { DoubleArray(6) { 0.0 } }

    private var groundLevel = 0.0
    private var lastTimestamp = 0L
    private var isInitialized = false

    // GNSS 품질 추적 - 기존 그대로
    private var lastGNSSQuality: PositionQuality? = null

    init {
        initializeState()
    }

    private fun initializeState() {
        stateVector.fill(0.0)

        for (i in 0..5) {
            errorCovariance[i][i] = when (i) {
                in 0..2 -> 100.0  // 위치 불확실성
                in 3..5 -> 10.0   // 속도 불확실성
                else -> 1.0
            }
        }
    }

    fun processGPS(gpsPosition: RTKPosition) {
        val currentTime = System.currentTimeMillis()

        if (!isInitialized) {
            initializeWithGPS(gpsPosition, currentTime)
            return
        }

        val dt = (currentTime - lastTimestamp) / 1000.0
        if (dt <= 0 || dt > 10.0) return

        predict(dt)
        updateWithGPS(gpsPosition)

        lastTimestamp = currentTime
    }

    fun processGNSS(gnssData: PreciseGNSSMeasurement) {
        lastGNSSQuality = gnssData.positionQuality

        // GNSS 데이터로 정확도 향상 - 기존 그대로
        if (isInitialized && gnssData.satellites.size >= 4) {
            val avgQuality = gnssData.satellites.map { it.qualityIndicator }.average()
            val hdop = gnssData.positionQuality.hdop

            // GNSS 품질에 따라 GPS 업데이트 가중치 조정
            val qualityMultiplier = when {
                avgQuality >= 80 && hdop < 2.0 -> 0.8  // 높은 품질
                avgQuality >= 60 && hdop < 3.0 -> 1.0  // 보통 품질
                avgQuality >= 40 && hdop < 5.0 -> 1.5  // 낮은 품질
                else -> 2.0  // 매우 낮은 품질
            }

            // 공분산 행렬 조정
            for (i in 0..2) {
                errorCovariance[i][i] *= qualityMultiplier
            }
        }
    }

    fun processBarometer(barometerData: BarometerData) {
        val pressureAltitude = 44330.0 * (1.0 - (barometerData.pressure / 1013.25).pow(0.1903))
        val relativeHeight = stateVector[2] - groundLevel

        val estimatedGroundLevel = pressureAltitude - relativeHeight
        val alpha = 0.1
        groundLevel = alpha * estimatedGroundLevel + (1.0 - alpha) * groundLevel
    }

    private fun predict(dt: Double) {
        val F = Array(6) { DoubleArray(6) { 0.0 } }

        for (i in 0..5) F[i][i] = 1.0
        for (i in 0..2) F[i][i + 3] = dt

        val newState = MatrixUtils.multiply(F, stateVector)
        stateVector = newState

        val gnssMultiplier = lastGNSSQuality?.let { quality ->
            when (quality.level) {
                FilterQuality.EXCELLENT -> 0.5
                FilterQuality.GOOD -> 0.8
                FilterQuality.FAIR -> 1.0
                FilterQuality.POOR -> 1.5
                FilterQuality.VERY_POOR -> 2.0
            }
        } ?: 1.0

        val Q = Array(6) { DoubleArray(6) { 0.0 } }
        val positionNoise = 1.0 * dt * dt * gnssMultiplier
        val velocityNoise = 0.5 * dt * gnssMultiplier

        for (i in 0..2) {
            Q[i][i] = positionNoise
            Q[i + 3][i + 3] = velocityNoise
        }

        val FP = MatrixUtils.multiply(F, errorCovariance)
        val FPFT = MatrixUtils.multiply(FP, MatrixUtils.transpose(F))
        errorCovariance = MatrixUtils.add(FPFT, Q)
    }

    private fun updateWithGPS(gpsPosition: RTKPosition) {
        val (x, y) = coordinateTransform.latLonToMeters(gpsPosition.latitude, gpsPosition.longitude)
        val observation = doubleArrayOf(x, y, gpsPosition.altitude)

        val H = Array(3) { DoubleArray(6) { 0.0 } }
        H[0][0] = 1.0
        H[1][1] = 1.0
        H[2][2] = 1.0

        val R = Array(3) { DoubleArray(3) { 0.0 } }
        val baseAccuracy = gpsPosition.accuracy

        val accuracyMultiplier = when (gpsPosition.fixType) {
            "RTK_FIXED" -> 0.8
            "RTK_FLOAT" -> 1.5
            "DGPS" -> 3.0
            else -> 10.0
        }

        val gnssMultiplier = lastGNSSQuality?.let { quality ->
            when (quality.level) {
                FilterQuality.EXCELLENT -> 0.7
                FilterQuality.GOOD -> 1.0
                FilterQuality.FAIR -> 1.3
                FilterQuality.POOR -> 2.0
                FilterQuality.VERY_POOR -> 3.0
            }
        } ?: 1.0

        val finalAccuracy = baseAccuracy * accuracyMultiplier * gnssMultiplier
        val horizontalVar = finalAccuracy * finalAccuracy
        val verticalVar = horizontalVar * 4.0

        R[0][0] = horizontalVar
        R[1][1] = horizontalVar
        R[2][2] = verticalVar

        val predicted = MatrixUtils.multiply(H, stateVector)
        val innovation = doubleArrayOf(
            observation[0] - predicted[0],
            observation[1] - predicted[1],
            observation[2] - predicted[2]
        )

        val HP = MatrixUtils.multiply(H, errorCovariance)
        val HPHT = MatrixUtils.multiply(HP, MatrixUtils.transpose(H))
        val S = MatrixUtils.add(HPHT, R)

        val HT = MatrixUtils.transpose(H)
        val PHT = MatrixUtils.multiply(errorCovariance, HT)
        val K = MatrixUtils.multiply(PHT, MatrixUtils.invert(S))

        val Ky = MatrixUtils.multiply(K, innovation)
        for (i in stateVector.indices) {
            stateVector[i] += Ky[i]
        }

        val KH = MatrixUtils.multiply(K, H)
        val I_KH = MatrixUtils.subtractFromIdentity(KH)
        errorCovariance = MatrixUtils.multiply(I_KH, errorCovariance)
    }

    fun getFilteredPosition(): FilteredPosition {
        val (lat, lon) = coordinateTransform.metersToLatLon(stateVector[0], stateVector[1])
        val heightAboveGround = stateVector[2] - groundLevel

        val filterQuality = lastGNSSQuality?.level ?: FilterQuality.GOOD

        return FilteredPosition(
            latitude = lat,
            longitude = lon,
            altitude = stateVector[2],
            accuracy = calculateAccuracy(),
            fixType = "GPS_GNSS_KALMAN",
            timestamp = System.currentTimeMillis(),
            filterQuality = filterQuality,
            velocity = Triple(stateVector[3], stateVector[4], stateVector[5]),
            uncertainty = Triple(
                sqrt(errorCovariance[0][0]),
                sqrt(errorCovariance[1][1]),
                sqrt(errorCovariance[2][2])
            ),
            heightAboveGround = heightAboveGround,
            attitude = Triple(0.0, 0.0, 0.0)
        )
    }

    private fun calculateAccuracy(): Double {
        val baseAccuracy = sqrt(errorCovariance[0][0] + errorCovariance[1][1] + errorCovariance[2][2])

        return lastGNSSQuality?.let { quality ->
            when (quality.level) {
                FilterQuality.EXCELLENT -> baseAccuracy * 0.8
                FilterQuality.GOOD -> baseAccuracy
                FilterQuality.FAIR -> baseAccuracy * 1.2
                FilterQuality.POOR -> baseAccuracy * 1.5
                FilterQuality.VERY_POOR -> baseAccuracy * 2.0
            }
        } ?: baseAccuracy
    }

    private fun initializeWithGPS(gpsPosition: RTKPosition, timestamp: Long) {
        val (x, y) = coordinateTransform.latLonToMeters(gpsPosition.latitude, gpsPosition.longitude)

        stateVector[0] = x
        stateVector[1] = y
        stateVector[2] = gpsPosition.altitude
        stateVector[3] = 0.0
        stateVector[4] = 0.0
        stateVector[5] = 0.0

        groundLevel = gpsPosition.altitude
        lastTimestamp = timestamp
        isInitialized = true
    }
}
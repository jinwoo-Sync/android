package com.example.myapplication.data.gps

import kotlin.math.*

/**
 * IMU 좌표계 정렬 관리자 - 안전한 초기화와 점진적 정렬
 */
class IMUCoordinateAligner {
    private var deviceToENURotation: Array<DoubleArray>? = null
    private var isCalibrated = false

    // 안정화를 위한 추가 상태
    private var calibrationConfidence = 0.0
    private var calibrationAttempts = 0
    private val maxCalibrationAttempts = 5

    // 중력 벡터 버퍼 (안정적인 추정을 위해)
    private val gravityBuffer = mutableListOf<Triple<Double, Double, Double>>()
    private val magneticBuffer = mutableListOf<Triple<Double, Double, Double>>()
    private val bufferSize = 200 // 증가된 버퍼 크기

    // 정적 상태 감지
    private var staticDetector = StaticStateDetector()

    companion object {
        const val GRAVITY_MAGNITUDE = 9.81
        const val GRAVITY_TOLERANCE = 0.5
        const val STATIC_THRESHOLD = 0.1
        const val MIN_CALIBRATION_CONFIDENCE = 0.8
    }

    /**
     * 안전한 초기화 - 정적 상태에서만 수행
     */
    fun initialize(initialIMUData: List<IMUData>): Boolean {
        if (initialIMUData.size < bufferSize) {
            println("IMU 정렬: 데이터 부족 (${initialIMUData.size}/$bufferSize)")
            return false
        }

        // 정적 상태 확인
        if (!staticDetector.isStatic(initialIMUData)) {
            println("IMU 정렬: 디바이스가 움직이는 중 - 정렬 스킵")
            return false
        }

        // 안정적인 중력 벡터 추정
        val staticGravity = estimateStableGravity(initialIMUData)
        if (staticGravity == null) {
            println("IMU 정렬: 안정적인 중력 추정 실패")
            calibrationAttempts++
            return false
        }

        // 자기장 추정 (선택적)
        val staticMagnetic = estimateStaticMagnetic(initialIMUData)

        // 회전 행렬 계산
        val rotation = if (staticMagnetic != null) {
            calculateFullRotation(staticGravity, staticMagnetic)
        } else {
            calculateGravityOnlyRotation(staticGravity)
        }

        // 점진적 적용을 위한 검증
        if (validateRotationMatrix(rotation)) {
            deviceToENURotation = rotation
            isCalibrated = true
            calibrationConfidence = if (staticMagnetic != null) 0.9 else 0.7
            println("IMU 정렬 성공: 신뢰도 ${String.format("%.1f", calibrationConfidence * 100)}%")
            return true
        }

        calibrationAttempts++
        return false
    }

    /**
     * 안전한 좌표 변환 with smoothing
     */
    fun transformIMUToENU(imuData: IMUData, smoothingFactor: Double = 0.1): IMUData {
        if (!isCalibrated || deviceToENURotation == null) {
            // 정렬 안됨 - 기본값 반환 (움직임 없음)
            return IMUData(
                accelerometer = Triple(0.0, 0.0, GRAVITY_MAGNITUDE),
                gyroscope = Triple(0.0, 0.0, 0.0),
                magnetometer = imuData.magnetometer,
                timestamp = imuData.timestamp
            )
        }

        val R = deviceToENURotation!!

        // 가속도계 변환
        val deviceAcc = doubleArrayOf(
            imuData.accelerometer.first,
            imuData.accelerometer.second,
            imuData.accelerometer.third
        )
        val enuAcc = matrixVectorMultiply(R, deviceAcc)

        // 자이로스코프 변환
        val deviceGyro = doubleArrayOf(
            imuData.gyroscope.first,
            imuData.gyroscope.second,
            imuData.gyroscope.third
        )
        val enuGyro = matrixVectorMultiply(R, deviceGyro)

        // 급격한 변화 방지를 위한 제한
        val limitedAcc = limitVector(enuAcc, 50.0) // 최대 50m/s²
        val limitedGyro = limitVector(enuGyro, 5.0) // 최대 5rad/s

        return IMUData(
            accelerometer = Triple(limitedAcc[0], limitedAcc[1], limitedAcc[2]),
            gyroscope = Triple(limitedGyro[0], limitedGyro[1], limitedGyro[2]),
            magnetometer = imuData.magnetometer,
            timestamp = imuData.timestamp
        )
    }

    /**
     * 안정적인 중력 추정
     */
    private fun estimateStableGravity(imuData: List<IMUData>): DoubleArray? {
        val stableWindows = mutableListOf<DoubleArray>()
        val windowSize = 50

        for (i in 0 until imuData.size - windowSize) {
            val window = imuData.subList(i, i + windowSize)
            val avgAcc = averageAcceleration(window)
            val magnitude = vectorNorm(avgAcc)

            // 중력 근처인지 확인
            if (abs(magnitude - GRAVITY_MAGNITUDE) < GRAVITY_TOLERANCE) {
                val variance = calculateVariance(window)
                if (variance < STATIC_THRESHOLD) {
                    stableWindows.add(avgAcc)
                }
            }
        }

        return if (stableWindows.size >= 3) {
            // 여러 안정 구간의 평균
            val finalGravity = averageVectors(stableWindows)
            normalizeVector(finalGravity)
        } else {
            null
        }
    }

    /**
     * 정적 상태 감지기
     */
    inner class StaticStateDetector {
        fun isStatic(imuData: List<IMUData>): Boolean {
            // 가속도계 변화량 확인
            val accVariance = calculateAccelerometerVariance(imuData)
            val gyroMagnitude = calculateGyroscopeMagnitude(imuData)

            println("정적 상태 검사: 가속도 분산=${String.format("%.4f", accVariance)}, " +
                    "자이로 크기=${String.format("%.4f", gyroMagnitude)}")

            return accVariance <  0.2 && gyroMagnitude < 0.05
        }

        private fun calculateAccelerometerVariance(data: List<IMUData>): Double {
            val accData = data.map {
                doubleArrayOf(it.accelerometer.first, it.accelerometer.second, it.accelerometer.third)
            }

            val mean = averageVectors(accData)
            var variance = 0.0

            accData.forEach { acc ->
                val diff = doubleArrayOf(
                    acc[0] - mean[0],
                    acc[1] - mean[1],
                    acc[2] - mean[2]
                )
                variance += vectorNorm(diff).pow(2)
            }

            return variance / data.size
        }

        private fun calculateGyroscopeMagnitude(data: List<IMUData>): Double {
            return data.map {
                sqrt(it.gyroscope.first.pow(2) +
                        it.gyroscope.second.pow(2) +
                        it.gyroscope.third.pow(2))
            }.average()
        }
    }

    // 유틸리티 함수들
    private fun limitVector(v: DoubleArray, maxMagnitude: Double): DoubleArray {
        val magnitude = vectorNorm(v)
        return if (magnitude > maxMagnitude) {
            val scale = maxMagnitude / magnitude
            doubleArrayOf(v[0] * scale, v[1] * scale, v[2] * scale)
        } else {
            v
        }
    }

    private fun validateRotationMatrix(R: Array<DoubleArray>): Boolean {
        // 직교성 확인
        for (i in 0..2) {
            for (j in 0..2) {
                if (i != j) {
                    val dot = R[i][0] * R[j][0] + R[i][1] * R[j][1] + R[i][2] * R[j][2]
                    if (abs(dot) > 0.1) return false
                }
            }
        }

        // 행렬식이 1인지 확인 (회전 행렬)
        val det = calculateDeterminant(R)
        return abs(det - 1.0) < 0.1
    }

    private fun calculateDeterminant(m: Array<DoubleArray>): Double {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    }

    // 기존 유틸리티 함수들...
    private fun averageAcceleration(data: List<IMUData>): DoubleArray {
        val sum = doubleArrayOf(0.0, 0.0, 0.0)
        data.forEach {
            sum[0] += it.accelerometer.first
            sum[1] += it.accelerometer.second
            sum[2] += it.accelerometer.third
        }
        return doubleArrayOf(sum[0] / data.size, sum[1] / data.size, sum[2] / data.size)
    }

    private fun averageVectors(vectors: List<DoubleArray>): DoubleArray {
        val sum = doubleArrayOf(0.0, 0.0, 0.0)
        vectors.forEach { v ->
            sum[0] += v[0]
            sum[1] += v[1]
            sum[2] += v[2]
        }
        return doubleArrayOf(sum[0] / vectors.size, sum[1] / vectors.size, sum[2] / vectors.size)
    }

    private fun calculateVariance(data: List<IMUData>): Double {
        val mean = averageAcceleration(data)
        var variance = 0.0
        data.forEach {
            val diff = doubleArrayOf(
                it.accelerometer.first - mean[0],
                it.accelerometer.second - mean[1],
                it.accelerometer.third - mean[2]
            )
            variance += vectorNorm(diff).pow(2)
        }
        return variance / data.size
    }

    private fun vectorNorm(v: DoubleArray): Double {
        return sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    }

    private fun normalizeVector(v: DoubleArray): DoubleArray {
        val norm = vectorNorm(v)
        return if (norm > 1e-8) {
            doubleArrayOf(v[0] / norm, v[1] / norm, v[2] / norm)
        } else {
            v
        }
    }

    // 기존 회전 행렬 계산 함수들...
    private fun calculateFullRotation(gravity: DoubleArray, magnetic: DoubleArray): Array<DoubleArray> {
        // 구현은 기존과 동일
        val zAxis = doubleArrayOf(-gravity[0], -gravity[1], -gravity[2])
        normalizeVector(zAxis)

        val magProjected = projectToPlane(magnetic, zAxis)
        val yAxis = normalizeVector(magProjected)
        val xAxis = crossProduct(yAxis, zAxis)

        return arrayOf(
            doubleArrayOf(xAxis[0], xAxis[1], xAxis[2]),
            doubleArrayOf(yAxis[0], yAxis[1], yAxis[2]),
            doubleArrayOf(zAxis[0], zAxis[1], zAxis[2])
        )
    }

    private fun calculateGravityOnlyRotation(gravity: DoubleArray): Array<DoubleArray> {
        // 구현은 기존과 동일
        val zAxis = doubleArrayOf(-gravity[0], -gravity[1], -gravity[2])
        normalizeVector(zAxis)

        val yAxis = if (abs(zAxis[2]) < 0.9) {
            normalizeVector(crossProduct(zAxis, doubleArrayOf(0.0, 0.0, 1.0)))
        } else {
            normalizeVector(crossProduct(zAxis, doubleArrayOf(1.0, 0.0, 0.0)))
        }

        val xAxis = crossProduct(yAxis, zAxis)

        return arrayOf(
            doubleArrayOf(xAxis[0], xAxis[1], xAxis[2]),
            doubleArrayOf(yAxis[0], yAxis[1], yAxis[2]),
            doubleArrayOf(zAxis[0], zAxis[1], zAxis[2])
        )
    }

    private fun projectToPlane(v: DoubleArray, normal: DoubleArray): DoubleArray {
        val dot = v[0] * normal[0] + v[1] * normal[1] + v[2] * normal[2]
        return doubleArrayOf(
            v[0] - dot * normal[0],
            v[1] - dot * normal[1],
            v[2] - dot * normal[2]
        )
    }

    private fun crossProduct(a: DoubleArray, b: DoubleArray): DoubleArray {
        return doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    private fun matrixVectorMultiply(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        return doubleArrayOf(
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
        )
    }

    private fun estimateStaticMagnetic(imuData: List<IMUData>): DoubleArray? {
        // 기존 구현과 동일
        val magneticSamples = mutableListOf<Triple<Double, Double, Double>>()

        imuData.forEach { data ->
            val mag = data.magnetometer
            val magnitude = sqrt(mag.first.pow(2) + mag.second.pow(2) + mag.third.pow(2))

            if (magnitude in 20.0..70.0) {
                magneticSamples.add(mag)
            }
        }

        return if (magneticSamples.size >= 50) {
            val avg = averageVectors(magneticSamples.map {
                doubleArrayOf(it.first, it.second, it.third)
            })
            normalizeVector(avg)
        } else {
            null
        }
    }

    fun isReady() = isCalibrated && calibrationConfidence >= MIN_CALIBRATION_CONFIDENCE
    fun getConfidence() = calibrationConfidence
}
// LocationUtils.kt - Mad Location Manager 스타일 유틸리티
package com.company.rtkgps.core

import android.hardware.SensorManager
import kotlin.math.*

object LocationUtils {
    const val EARTH_RADIUS = 6371000.0 // 지구 반지름 (미터)

    /**
     * 두 GPS 좌표 간의 거리 계산 (Haversine formula)
     */
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }

    /**
     * GPS 좌표를 미터 단위 좌표로 변환 (기준점 기준)
     */
    fun toMeters(latReference: Double, lonReference: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val deltaLat = lat - latReference
        val deltaLon = lon - lonReference

        val x = deltaLon * Math.toRadians(EARTH_RADIUS) * cos(Math.toRadians(latReference))
        val y = deltaLat * Math.toRadians(EARTH_RADIUS)

        return Pair(x, y)
    }

    /**
     * 미터 단위 좌표를 GPS 좌표로 변환
     */
    fun fromMeters(latReference: Double, lonReference: Double, x: Double, y: Double): Pair<Double, Double> {
        val deltaLat = y / (Math.toRadians(EARTH_RADIUS))
        val deltaLon = x / (Math.toRadians(EARTH_RADIUS) * cos(Math.toRadians(latReference)))

        return Pair(latReference + deltaLat, lonReference + deltaLon)
    }
}

/**
 * Mad Location Manager 스타일의 가속도계 처리
 */
class AccelerometerManager {

    companion object {
        const val GRAVITY = 9.81
        const val NOISE_THRESHOLD = 2.0 // 노이즈 임계값
    }

    // 중력 벡터 추정을 위한 low-pass filter
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 0.0
    private val alpha = 0.8 // low-pass filter 계수

    // 회전 행렬 (디바이스 -> 지구 좌표계)
    private var rotationMatrix = FloatArray(9)
    private var isInitialized = false
    private var latestIMUData: IMUData? = null
    private var isCalibrated = false

    /**
     * 가속도계 데이터 처리 및 좌표계 변환
     */
    fun processAcceleration(accX: Double, accY: Double, accZ: Double): Triple<Double, Double, Double> {
        latestIMUData?.let { imu ->
            val accel = floatArrayOf(accX.toFloat(), accY.toFloat(), accZ.toFloat())
            val gyro = floatArrayOf(
                imu.gyroscope.first.toFloat(),
                imu.gyroscope.second.toFloat(),
                imu.gyroscope.third.toFloat()
            )
            val mag = floatArrayOf(
                imu.magnetometer.first.toFloat(),
                imu.magnetometer.second.toFloat(),
                imu.magnetometer.third.toFloat()
            )

            // 회전 행렬 계산 (가속도계와 자력계로 초기화)
            SensorManager.getRotationMatrix(rotationMatrix, null, accel, mag)
            // 자이로스코프로 회전 보정
            val dt = 0.02f // IMU 업데이트 간격 (50Hz 가정)
            val gyroMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(gyroMatrix, gyro.map { it * dt }.toFloatArray())
            multiplyRotationMatrix(rotationMatrix, gyroMatrix)

            // 세계 좌표계로 변환
            val worldAcc = FloatArray(3)
            SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, worldAcc)
            return Triple(worldAcc[0].toDouble(), worldAcc[1].toDouble(), worldAcc[2].toDouble())
        }
        return Triple(accX, accY, accZ)
    }

    /**
     * 회전 행렬 곱셈 (MatrixUtils 사용)
     */
    private fun multiplyRotationMatrix(r1: FloatArray, r2: FloatArray) {
        // FloatArray를 DoubleArray로 변환
        val r1Double = r1.map { it.toDouble() }.toDoubleArray()
        val r2Double = r2.map { it.toDouble() }.toDoubleArray()

        // 3x3 행렬로 변환
        val r1Matrix = arrayOf(
            doubleArrayOf(r1Double[0], r1Double[1], r1Double[2]),
            doubleArrayOf(r1Double[3], r1Double[4], r1Double[5]),
            doubleArrayOf(r1Double[6], r1Double[7], r1Double[8])
        )
        val r2Matrix = arrayOf(
            doubleArrayOf(r2Double[0], r2Double[1], r2Double[2]),
            doubleArrayOf(r2Double[3], r2Double[4], r2Double[5]),
            doubleArrayOf(r2Double[6], r2Double[7], r2Double[8])
        )

        // MatrixUtils의 multiply 함수 사용
        val resultMatrix = MatrixUtils.multiply(r1Matrix, r2Matrix)

        // 결과를 다시 FloatArray로 변환해 r1에 저장
        val resultFloat = resultMatrix.flatMap { it.toList() }.map { it.toFloat() }.toFloatArray()
        System.arraycopy(resultFloat, 0, r1, 0, 9)
    }

    /**
     * 중력 벡터를 이용해 회전 행렬 계산
     */
    private fun calculateRotationMatrix() {
        val gravityNorm = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)

        if (gravityNorm < GRAVITY * 0.8 || gravityNorm > GRAVITY * 1.2) {
            return // 중력이 아닌 것 같으면 스킵
        }

        // 정규화된 중력 벡터
        val gx = gravityX / gravityNorm
        val gy = gravityY / gravityNorm
        val gz = gravityZ / gravityNorm

        // 지구 좌표계에서 Z축은 위쪽 (중력 반대)
        val worldUp = doubleArrayOf(0.0, 0.0, 1.0)
        val deviceGravity = doubleArrayOf(gx, gy, gz)

        // 회전 행렬 계산
        rotationMatrix = calculateRotationFromGravity(deviceGravity)
        isInitialized = true

        println("가속도계 캘리브레이션 완료: gravity = ($gx, $gy, $gz)")
    }

    private fun calculateRotationFromGravity(gravity: DoubleArray): FloatArray {
        val R = FloatArray(9)

        val gx = gravity[0]
        val gy = gravity[1]
        val gz = gravity[2]

        val xNorm = sqrt(1 - gz * gz)
        if (xNorm > 0.1) {
            R[0] = (-gy / xNorm).toFloat()
            R[3] = (gx / xNorm).toFloat()
            R[6] = 0f
        } else {
            R[0] = 1f
            R[3] = 0f
            R[6] = 0f
        }

        R[2] = (-gx).toFloat()
        R[5] = (-gy).toFloat()
        R[8] = (-gz).toFloat()

        R[1] = R[5] * R[6] - R[8] * R[3]
        R[4] = R[8] * R[0] - R[2] * R[6]
        R[7] = R[2] * R[3] - R[5] * R[0]

        return R
    }

    fun isReady(): Boolean = isInitialized

    fun reset() {
        isInitialized = false
        rotationMatrix = FloatArray(9)
        gravityX = 0.0
        gravityY = 0.0
        gravityZ = 0.0
    }
}
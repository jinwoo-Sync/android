package com.company.rtkgps.utils

import kotlin.math.*
import com.example.myapplication.gps_modules.MatrixUtils as MU // MatrixUtils 별칭 사용

class PreciseCoordinateTransform {
    private var isInitialized = false
    private var referenceLat = 0.0
    private var referenceLon = 0.0
    private var referenceX = 0.0
    private var referenceY = 0.0
    private var referenceZone = 51

    companion object {
        const val WGS84_A = 6378137.0
        const val WGS84_F = 1.0 / 298.257223563
        const val WGS84_E2 = 2 * WGS84_F - WGS84_F * WGS84_F
        const val WGS84_E_PRIME2 = WGS84_E2 / (1 - WGS84_E2)

        const val UTM_K0 = 0.9996
        const val UTM_FALSE_EASTING = 500000.0
        const val UTM_FALSE_NORTHING_NORTH = 0.0
        const val UTM_FALSE_NORTHING_SOUTH = 10000000.0

        val KOREA_UTM_ZONES = mapOf(
            51 to 123.0,
            52 to 129.0
        )
    }

    /** 기준 좌표를 초기화합니다. */
    fun initialize(lat: Double, lon: Double) {
        referenceLat = lat
        referenceLon = lon
        referenceZone = determineUTMZone(lon)
        val utm = preciseLatLonToUTM(lat, lon, referenceZone)
        referenceX = utm.first
        referenceY = utm.second
        isInitialized = true
    }

    /** WGS84 좌표를 기준점 대비 미터 단위로 변환합니다. */
    fun latLonToMeters(lat: Double, lon: Double): Pair<Double, Double> {
        if (!isInitialized) {
            initialize(lat, lon)
            return 0.0 to 0.0
        }
        val utm = preciseLatLonToUTM(lat, lon, referenceZone)
        val x = utm.first - referenceX
        val y = utm.second - referenceY
        return x to y
    }

    /** 미터 단위 좌표를 WGS84 좌표로 변환합니다. */
    fun metersToLatLon(x: Double, y: Double): Pair<Double, Double> {
        if (!isInitialized) throw IllegalStateException("Not initialized")
        val utmX = x + referenceX
        val utmY = y + referenceY
        return preciseUTMToLatLon(utmX, utmY, referenceZone)
    }

    /** 경도에 따라 UTM 존을 결정합니다. */
    private fun determineUTMZone(longitude: Double): Int {
        return if (longitude >= 126.0) 52 else 51
    }

    /** WGS84 좌표를 UTM 좌표로 변환합니다. */
    private fun preciseLatLonToUTM(lat: Double, lon: Double, zone: Int): Pair<Double, Double> {
        val centralMeridian = KOREA_UTM_ZONES[zone] ?: 123.0
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val centralMeridianRad = Math.toRadians(centralMeridian)

        val deltaLon = lonRad - centralMeridianRad
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val tanLat = tan(latRad)
        val nu = WGS84_A / sqrt(1 - WGS84_E2 * sinLat * sinLat)
        val p = deltaLon * cosLat
        val t = tanLat * tanLat
        val c = WGS84_E_PRIME2 * cosLat * cosLat
        val M = WGS84_A * (
                (1 - WGS84_E2/4 - 3*WGS84_E2*WGS84_E2/64 - 5*WGS84_E2*WGS84_E2*WGS84_E2/256) * latRad -
                        (3*WGS84_E2/8 + 3*WGS84_E2*WGS84_E2/32 + 45*WGS84_E2*WGS84_E2*WGS84_E2/1024) * sin(2*latRad) +
                        (15*WGS84_E2*WGS84_E2/256 + 45*WGS84_E2*WGS84_E2*WGS84_E2/1024) * sin(4*latRad) -
                        (35*WGS84_E2*WGS84_E2*WGS84_E2/3072) * sin(6*latRad)
                )
        val A = p
        val A3 = A * A * A
        val A5 = A3 * A * A
        val easting = UTM_K0 * nu * (
                A + (1 - t + c) * A3 / 6 +
                        (5 - 18*t + t*t + 72*c - 58*WGS84_E_PRIME2) * A5 / 120
                ) + UTM_FALSE_EASTING
        val northing = UTM_K0 * (
                M + nu * tanLat * (
                        A*A/2 + (5 - t + 9*c + 4*c*c) * A*A*A*A / 24 +
                                (61 - 58*t + t*t + 600*c - 330*WGS84_E_PRIME2) * A*A*A*A*A*A / 720
                        )
                ) + if (lat >= 0) UTM_FALSE_NORTHING_NORTH else UTM_FALSE_NORTHING_SOUTH
        return easting to northing
    }

    /** UTM 좌표를 WGS84 좌표로 변환합니다. */
    private fun preciseUTMToLatLon(easting: Double, northing: Double, zone: Int): Pair<Double, Double> {
        val centralMeridian = KOREA_UTM_ZONES[zone] ?: 123.0
        val centralMeridianRad = Math.toRadians(centralMeridian)
        val x = easting - UTM_FALSE_EASTING
        val y = northing - UTM_FALSE_NORTHING_NORTH
        val M = y / UTM_K0
        val e1 = (1 - sqrt(1 - WGS84_E2)) / (1 + sqrt(1 - WGS84_E2))
        val mu = M / (WGS84_A * (1 - WGS84_E2/4 - 3*WGS84_E2*WGS84_E2/64 - 5*WGS84_E2*WGS84_E2*WGS84_E2/256))
        val phi1 = mu + (3*e1/2 - 27*e1*e1*e1/32) * sin(2*mu) +
                (21*e1*e1/16 - 55*e1*e1*e1*e1/32) * sin(4*mu) +
                (151*e1*e1*e1/96) * sin(6*mu)
        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)
        val nu1 = WGS84_A / sqrt(1 - WGS84_E2 * sinPhi1 * sinPhi1)
        val rho1 = WGS84_A * (1 - WGS84_E2) / ((1 - WGS84_E2 * sinPhi1 * sinPhi1) * sqrt(1 - WGS84_E2 * sinPhi1 * sinPhi1))
        val c1 = WGS84_E_PRIME2 * cosPhi1 * cosPhi1
        val d = x / (nu1 * UTM_K0)
        val lat = phi1 - (nu1 * tanPhi1 / rho1) * (d*d/2 - (5 + 3*tanPhi1*tanPhi1 + 10*c1 - 4*c1*c1 - 9*WGS84_E_PRIME2) * d*d*d*d/24)
        val lon = centralMeridianRad + (d - (1 + 2*tanPhi1*tanPhi1 + c1) * d*d*d/6) / cosPhi1
        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }

    /** 기준점을 갱신합니다. */
    fun updateReference(lat: Double, lon: Double) {
        referenceLat = lat
        referenceLon = lon
        referenceZone = determineUTMZone(lon)
        val utm = preciseLatLonToUTM(lat, lon, referenceZone)
        referenceX = utm.first
        referenceY = utm.second
        isInitialized = true
    }

    /** ECEF 좌표를 WGS84 좌표로 변환합니다. */
    fun ecefToLatLon(ecefX: Double, ecefY: Double, ecefZ: Double): Pair<Double, Double> {
        val a = WGS84_A
        val e2 = WGS84_E2
        val p = sqrt(ecefX * ecefX + ecefY * ecefY)
        val theta = atan2(ecefZ * a, p * (1 - e2))
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)
        val lon = atan2(ecefY, ecefX)
        val lat = atan2(ecefZ + e2 * a * sinTheta * sinTheta * sinTheta,
            p - e2 * a * cosTheta * cosTheta * cosTheta)
        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }

    /** 현재 기준점을 반환합니다. */
    fun getReference(): Triple<Double, Double, Int> = Triple(referenceLat, referenceLon, referenceZone)
}

/**
 * IMU 데이터를 ENU 좌표계로 변환하는 클래스
 */
class IMUToENUTransform {
    private var rotationMatrix: Array<DoubleArray>? = null
    private var isInitialized = false
    private var gravityBufferSize = 50
    private var gravityBuffer = mutableListOf<Triple<Double, Double, Double>>()

    /** IMU 데이터를 ENU 좌표계로 변환합니다. */
    fun transformToENU(imuData: IMUData, currentQuaternion: DoubleArray): IMUData {
        if (!isInitialized) {
            estimateInitialOrientation(imuData.accelerometer)
        }

        return if (rotationMatrix != null) {
            val R = rotationMatrix!!
            val deviceAcc = doubleArrayOf(imuData.accelerometer.first, imuData.accelerometer.second, imuData.accelerometer.third)
            val enuAcc = MU.multiply(R, deviceAcc) // MatrixUtils 사용
            val deviceGyro = doubleArrayOf(imuData.gyroscope.first, imuData.gyroscope.second, imuData.gyroscope.third)
            val enuGyro = MU.multiply(R, deviceGyro) // MatrixUtils 사용

            IMUData(
                accelerometer = Triple(enuAcc[0], enuAcc[1], enuAcc[2]),
                gyroscope = Triple(enuGyro[0], enuGyro[1], enuGyro[2]),
                magnetometer = imuData.magnetometer,
                timestamp = imuData.timestamp
            )
        } else {
            imuData
        }
    }

    /** 초기 방향을 추정합니다. */
    private fun estimateInitialOrientation(acceleration: Triple<Double, Double, Double>) {
        gravityBuffer.add(acceleration)
        if (gravityBuffer.size > gravityBufferSize) {
            gravityBuffer.removeAt(0)
        }

        if (gravityBuffer.size >= gravityBufferSize && !isInitialized) {
            val avgGravity = doubleArrayOf(
                gravityBuffer.map { it.first }.average(),
                gravityBuffer.map { it.second }.average(),
                gravityBuffer.map { it.third }.average()
            )
            val gravityMagnitude = MU.vectorNorm(avgGravity) // MatrixUtils 사용
            if (gravityMagnitude in 8.0..12.0) {
                val gNorm = avgGravity.map { it / gravityMagnitude }.toDoubleArray()
                val enuGravity = doubleArrayOf(0.0, 0.0, -1.0)
                rotationMatrix = calculateRotationFromGravity(gNorm, enuGravity)
                isInitialized = true
                println("IMU->ENU 변환 초기화 완료: 중력크기=${String.format("%.2f", gravityMagnitude)}m/s²")
            }
        }
    }

    /** 중력 벡터로부터 회전 행렬을 계산합니다. */
    private fun calculateRotationFromGravity(deviceGravity: DoubleArray, targetGravity: DoubleArray): Array<DoubleArray> {
        val v = MU.crossProduct(deviceGravity, targetGravity) // MatrixUtils 사용
        val s = MU.vectorNorm(v) // MatrixUtils 사용
        val c = MU.dotProduct(deviceGravity, targetGravity) // MatrixUtils 사용
        val vx = MU.skew(v) // MatrixUtils 사용
        val R = MU.identity(3) // MatrixUtils 사용

        if (s > 1e-8) {
            val vx2 = MU.multiply(vx, vx) // MatrixUtils 사용
            val factor = (1 - c) / (s * s)
            val result = Array(3) { DoubleArray(3) }
            for (i in 0..2) {
                for (j in 0..2) {
                    result[i][j] = R[i][j] + vx[i][j] + factor * vx2[i][j]
                }
            }
            return result
        } else {
            return R
        }
    }
}
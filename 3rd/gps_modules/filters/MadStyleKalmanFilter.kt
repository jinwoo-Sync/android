package com.example.myapplication.gps_modules.filters

import android.hardware.SensorManager
import kotlin.math.*

/**
 * Mad Location Manager 스타일의 GPS + Accelerometer Kalman Filter
 * 상태 벡터: [x, y, vx, vy, ax, ay] (6차원)
 */
class MadStyleKalmanFilter(private val coordinateTransform: PreciseCoordinateTransform) {

    // 6차원 상태 벡터: [x, y, vx, vy, ax, ay]
    private var stateVector = DoubleArray(6)
    private var errorCovariance = Array(6) { DoubleArray(6) { 0.0 } }

    // 가속도계 매니저
    private val accelerometerManager = AccelerometerManager()

    // 참조점
    private var referenceLatitude = 0.0
    private var referenceLongitude = 0.0
    private var isInitialized = false

    // 시간 관리
    private var lastUpdateTime = 0L

    // 칼만 필터 파라미터 (Mad Location Manager 스타일)
    companion object {
        const val GPS_ACCURACY_FACTOR = 2.0 // GPS 불확실성 증가
        const val ACCELEROMETER_ACCURACY_FACTOR = 5.0 // 가속도계 신뢰도 증가
        const val PREDICTION_TIME_INC = 0.02
    }

    init {
        initializeState()
    }

    private fun initializeState() {
        stateVector.fill(0.0)

        // 초기 공분산 설정
        for (i in 0..5) {
            errorCovariance[i][i] = when (i) {
                0, 1 -> 100.0      // 위치 불확실성 (100m)
                2, 3 -> 50.0       // 속도 불확실성 (50m/s)
                4, 5 -> 20.0       // 가속도 불확실성 (20m/s²)
                else -> 1.0
            }
        }
    }

    /**
     * GPS 위치 업데이트 (Mad Location Manager 스타일)
     */
    fun updateWithGPS(latitude: Double, longitude: Double, accuracy: Double, timestamp: Long) {
        if (!isInitialized) {
            // 첫 번째 GPS 데이터로 초기화
            referenceLatitude = latitude
            referenceLongitude = longitude
            coordinateTransform.initialize(latitude, longitude)

            val (x, y) = LocationUtils.toMeters(referenceLatitude, referenceLongitude, latitude, longitude)
            stateVector[0] = x
            stateVector[1] = y
            // 속도와 가속도는 0으로 유지

            lastUpdateTime = timestamp
            isInitialized = true
            println("Mad Style Kalman Filter 초기화: ($latitude, $longitude)")
            return
        }

        val currentTime = timestamp
        val dt = (currentTime - lastUpdateTime) / 1000.0

        if (dt > 0 && dt < 10.0) { // 유효한 시간 간격
            // 예측 단계
            predict(dt)

            // GPS 측정으로 업데이트
            val (obsX, obsY) = LocationUtils.toMeters(referenceLatitude, referenceLongitude, latitude, longitude)
            updateWithPosition(obsX, obsY, accuracy)
        }

        lastUpdateTime = currentTime
    }

    /**
     * 가속도계 데이터 업데이트
     */
    fun updateWithAccelerometer(accX: Double, accY: Double, accZ: Double, timestamp: Long) {
        if (!isInitialized || !accelerometerManager.isReady()) {
            // IMU 데이터가 준비되지 않은 경우 로그 남기고 종료
            println("가속도계 데이터 처리 스킵: 초기화되지 않음 또는 IMU 데이터 없음")
            return
        }

        val (worldAccX, worldAccY, _) = accelerometerManager.processAcceleration(accX, accY, accZ)
        val dt = (timestamp - lastUpdateTime) / 1000.0

        if (dt > 0 && dt < 1.0) {
            predict(dt)
            updateWithAcceleration(worldAccX, worldAccY)
        }
        lastUpdateTime = timestamp
    }

    /**
     * 상태 예측 (Mad Location Manager 스타일)
     */
    private fun predict(dt: Double) {
        // 상태 전이 행렬 F
        val F = Array(6) { DoubleArray(6) { 0.0 } }

        // 단위 행렬 설정
        for (i in 0..5) F[i][i] = 1.0

        // 운동학적 관계 (가속도의 2차 효과 반영)
        F[0][2] = dt            // x += vx * dt
        F[0][4] = 0.5 * dt * dt // x += (ax * dt^2) / 2
        F[1][3] = dt            // y += vy * dt
        F[1][5] = 0.5 * dt * dt // y += (ay * dt^2) / 2
        F[2][4] = dt            // vx += ax * dt
        F[3][5] = dt            // vy += ay * dt

        // 상태 예측: x = F * x
        val newState = DoubleArray(6)
        for (i in 0..5) {
            newState[i] = 0.0
            for (j in 0..5) {
                newState[i] += F[i][j] * stateVector[j]
            }
        }
        stateVector = newState

        // 프로세스 노이즈 Q (Mad Location Manager 스타일)
        val Q = Array(6) { DoubleArray(6) { 0.0 } }
        val sigmaA = 1.0 // 튜닝 값 조정
        val sigmaA2 = sigmaA * sigmaA
        val dt2 = dt * dt

        Q[0][0] = dt2 * dt2 / 4 * sigmaA2
        Q[1][1] = dt2 * dt2 / 4 * sigmaA2
        Q[2][2] = dt2 * sigmaA2
        Q[3][3] = dt2 * sigmaA2
        Q[4][4] = sigmaA2
        Q[5][5] = sigmaA2

        // 공분산 예측: P = F * P * F^T + Q
        val FP = matrixMultiply(F, errorCovariance)
        val FPFT = matrixMultiplyTranspose(FP, F)
        errorCovariance = matrixAdd(FPFT, Q)
    }

    /**
     * GPS 위치 측정으로 업데이트
     */
    private fun updateWithPosition(x: Double, y: Double, accuracy: Double) {
        // 관측 모델 H (2x6) - 위치만 관측
        val H = Array(2) { DoubleArray(6) { 0.0 } }
        H[0][0] = 1.0  // x position
        H[1][1] = 1.0  // y position

        // 관측 노이즈 R
        val R = Array(2) { DoubleArray(2) { 0.0 } }
        val variance = (accuracy * GPS_ACCURACY_FACTOR) * (accuracy * GPS_ACCURACY_FACTOR)
        R[0][0] = variance
        R[1][1] = variance

        // 관측값
        val observation = doubleArrayOf(x, y)

        // 칼만 업데이트
        performKalmanUpdate(H, observation, R)
    }

    /**
     * 가속도계 측정으로 업데이트
     */
    private fun updateWithAcceleration(ax: Double, ay: Double) {
        // 관측 모델 H (2x6) - 가속도만 관측
        val H = Array(2) { DoubleArray(6) { 0.0 } }
        H[0][4] = 1.0  // ax
        H[1][5] = 1.0  // ay

        // 관측 노이즈 R
        val R = Array(2) { DoubleArray(2) { 0.0 } }
        val variance = ACCELEROMETER_ACCURACY_FACTOR * ACCELEROMETER_ACCURACY_FACTOR
        R[0][0] = variance
        R[1][1] = variance

        // 관측값
        val observation = doubleArrayOf(ax, ay)

        // 칼만 업데이트
        performKalmanUpdate(H, observation, R)
    }

    /**
     * 칼만 필터 업데이트 수행
     */
    private fun performKalmanUpdate(H: Array<DoubleArray>, observation: DoubleArray, R: Array<DoubleArray>) {
        val observationDim = observation.size

        // 예측된 관측값
        val predictedObs = DoubleArray(observationDim)
        for (i in 0 until observationDim) {
            for (j in 0..5) {
                predictedObs[i] += H[i][j] * stateVector[j]
            }
        }

        // 혁신 (innovation)
        val innovation = DoubleArray(observationDim)
        for (i in 0 until observationDim) {
            innovation[i] = observation[i] - predictedObs[i]
        }

        // 혁신 공분산 S = H * P * H^T + R
        val HP = matrixMultiply(H, errorCovariance)
        val HPHT = matrixMultiplyTranspose(HP, H)
        val S = matrixAdd(HPHT, R)

        // 칼만 이득 K = P * H^T * S^-1
        val HT = transposeMatrix(H)
        val PHT = matrixMultiply(errorCovariance, HT)
        val K = matrixMultiply(PHT, invertMatrix(S))

        // 상태 업데이트: x = x + K * innovation
        val Ky = matrixVectorMultiply(K, innovation)
        for (i in 0..5) {
            stateVector[i] += Ky[i]
        }

        // 공분산 업데이트: P = (I - K * H) * P
        val KH = matrixMultiply(K, H)
        val I_KH = subtractFromIdentity(KH)
        errorCovariance = matrixMultiply(I_KH, errorCovariance)
    }

    /**
     * 필터링된 위치 반환
     */
    fun getFilteredPosition(): FilteredPosition {
        if (!isInitialized) {
            return FilteredPosition(
                latitude = 0.0,
                longitude = 0.0,
                altitude = 0.0,
                accuracy = 999.0,
                fixType = "MAD_STYLE_NOT_INIT",
                timestamp = System.currentTimeMillis(),
                filterQuality = FilterQuality.VERY_POOR
            )
        }

        val (lat, lon) = LocationUtils.fromMeters(referenceLatitude, referenceLongitude, stateVector[0], stateVector[1])
        val accuracy = sqrt(errorCovariance[0][0] + errorCovariance[1][1])

        return FilteredPosition(
            latitude = lat,
            longitude = lon,
            altitude = 0.0, // 2D 필터이므로 고도는 0
            accuracy = accuracy,
            fixType = "MAD_STYLE_GPS_ACC",
            timestamp = System.currentTimeMillis(),
            filterQuality = when {
                accuracy < 5.0 -> FilterQuality.EXCELLENT
                accuracy < 10.0 -> FilterQuality.GOOD
                accuracy < 20.0 -> FilterQuality.FAIR
                accuracy < 50.0 -> FilterQuality.POOR
                else -> FilterQuality.VERY_POOR
            },
            velocity = Triple(stateVector[2], stateVector[3], 0.0),
            uncertainty = Triple(
                sqrt(errorCovariance[0][0]),
                sqrt(errorCovariance[1][1]),
                0.0
            ),
            heightAboveGround = 0.0,
            attitude = Triple(0.0, 0.0, 0.0)
        )
    }

    fun isReady(): Boolean = isInitialized && accelerometerManager.isReady()

    fun reset() {
        isInitialized = false
        accelerometerManager.reset()
        initializeState()
    }

    /**
     * AccelerometerManager: IMU 데이터를 활용한 세계 좌표계 변환
     */
    inner class AccelerometerManager {
        private var rotationMatrix = FloatArray(9)
        private var orientation = FloatArray(3)
        private var latestIMUData: IMUData? = null

        fun processAcceleration(accX: Double, accY: Double, accZ: Double): Triple<Double, Double, Double> {
            // 최신 IMU 데이터가 없으면 기본값 반환
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

                // 회전 행렬 계산
                SensorManager.getRotationMatrix(rotationMatrix, null, accel, mag)
                SensorManager.getOrientation(rotationMatrix, orientation)

                // 세계 좌표계로 변환
                val worldAcc = FloatArray(3)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, worldAcc
                )
                return Triple(worldAcc[0].toDouble(), worldAcc[1].toDouble(), worldAcc[2].toDouble())
            }
            // 기본값 반환
            return Triple(accX, accY, accZ)
        }

        fun updateIMUData(imuData: IMUData) {
            latestIMUData = imuData
        }

        fun isReady(): Boolean = latestIMUData != null

        fun reset() {
            latestIMUData = null
            rotationMatrix.fill(0f)
            orientation.fill(0f)
        }
    }

    /**
     * IMU 데이터 업데이트 (IMUCollector와 연동)
     */
    fun updateWithIMUData(imuData: IMUData) {
        accelerometerManager.updateIMUData(imuData)
    }

    // 유틸리티 함수들 (기존 코드 유지)
    private fun matrixMultiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rowsA = a.size
        val colsA = a[0].size
        val colsB = b[0].size
        val result = Array(rowsA) { DoubleArray(colsB) { 0.0 } }
        for (i in 0 until rowsA) {
            for (j in 0 until colsB) {
                for (k in 0 until colsA) {
                    result[i][j] += a[i][k] * b[k][j]
                }
            }
        }
        return result
    }

    private fun matrixMultiplyTranspose(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>
    ): Array<DoubleArray> {
        val rowsA = a.size          // 2
        val rowsB = b.size          // 2 ( H 의 행 수 )
        val cols  = a[0].size       // 6 ( H 의 열 수 = P 의 행 수 )

        val result = Array(rowsA) { DoubleArray(rowsB) { 0.0 } }   // ➜ 2 × 2

        for (i in 0 until rowsA) {
            for (j in 0 until rowsB) {
                var s = 0.0
                for (k in 0 until cols) {
                    s += a[i][k] * b[j][k]          // b 행렬의 k 열을 그대로 사용 → bᵀ
                }
                result[i][j] = s
            }
        }
        return result
    }

    private fun matrixAdd(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val cols = a[0].size
        val result = Array(rows) { DoubleArray(cols) { 0.0 } }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = a[i][j] + b[i][j]
            }
        }
        return result
    }

    private fun transposeMatrix(a: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val cols = a[0].size
        val result = Array(cols) { DoubleArray(rows) { 0.0 } }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j][i] = a[i][j]
            }
        }
        return result
    }

    private fun invertMatrix(a: Array<DoubleArray>): Array<DoubleArray> {
        // 간단한 2x2 행렬 반전 (실제로는 더 복잡한 행렬 반전 필요)
        val det = a[0][0] * a[1][1] - a[0][1] * a[1][0]
        return arrayOf(
            doubleArrayOf(a[1][1] / det, -a[0][1] / det),
            doubleArrayOf(-a[1][0] / det, a[0][0] / det)
        )
    }

    private fun matrixVectorMultiply(a: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        val rows = a.size
        val result = DoubleArray(rows) { 0.0 }
        for (i in 0 until rows) {
            for (j in 0 until v.size) {
                result[i] += a[i][j] * v[j]
            }
        }
        return result
    }

    private fun subtractFromIdentity(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val result = Array(n) { DoubleArray(n) { 0.0 } }
        for (i in 0 until n) {
            result[i][i] = 1.0
            for (j in 0 until n) {
                result[i][j] -= a[i][j]
            }
        }
        return result
    }
}
package com.example.myapplication.gps_modules.core

import kotlin.math.*

data class PreintegratedImuMeasurements(
    val deltaPosition: DoubleArray,
    val deltaVelocity: DoubleArray,
    val deltaRotation: DoubleArray,
    val deltaTime: Double,
    val preintegratedMeasurementCov: Array<DoubleArray>,
    val jacobianPosWrtBiasAcc: Array<DoubleArray>,
    val jacobianPosWrtBiasGyro: Array<DoubleArray>,
    val jacobianVelWrtBiasAcc: Array<DoubleArray>,
    val jacobianVelWrtBiasGyro: Array<DoubleArray>,
    val jacobianRotWrtBiasGyro: Array<DoubleArray>
)

/**
 * 통계적 이상치 감지기 (수학적 이론 기반)
 */
class StatisticalOutlierDetector {
    private val windowSize = 20
    private val accelerometerHistory = mutableListOf<DoubleArray>()
    private val gyroscopeHistory = mutableListOf<DoubleArray>()

    fun detectOutlier(imuData: IMUData): Triple<Boolean, Double, String> {
        val acc = doubleArrayOf(imuData.accelerometer.first, imuData.accelerometer.second, imuData.accelerometer.third)
        val gyro = doubleArrayOf(imuData.gyroscope.first, imuData.gyroscope.second, imuData.gyroscope.third)

        accelerometerHistory.add(acc)
        gyroscopeHistory.add(gyro)

        if (accelerometerHistory.size > windowSize) {
            accelerometerHistory.removeAt(0)
            gyroscopeHistory.removeAt(0)
        }

        if (accelerometerHistory.size < 10) return Triple(false, 0.0, "Insufficient data")

        try {
            val accMean = calculateMean(accelerometerHistory)
            val accCov = calculateCovariance(accelerometerHistory, accMean)
            val gyroMean = calculateMean(gyroscopeHistory)
            val gyroCov = calculateCovariance(gyroscopeHistory, gyroMean)

            val accMahalDist = calculateMahalanobisDistance(acc, accMean, accCov)
            val gyroMahalDist = calculateMahalanobisDistance(gyro, gyroMean, gyroCov)

            // χ² 분포 기반 임계값 (자유도 3, 99.9% 신뢰구간)
            val chi2Threshold = 16.266

            val isOutlier = accMahalDist > chi2Threshold || gyroMahalDist > chi2Threshold
            val maxDistance = max(accMahalDist, gyroMahalDist)
            val reason = when {
                accMahalDist > chi2Threshold -> "Accelerometer outlier: $accMahalDist"
                gyroMahalDist > chi2Threshold -> "Gyroscope outlier: $gyroMahalDist"
                else -> "Normal"
            }

            return Triple(isOutlier, maxDistance, reason)
        } catch (e: Exception) {
            return Triple(false, 0.0, "Calculation error: ${e.message}")
        }
    }

    private fun calculateMean(data: List<DoubleArray>): DoubleArray {
        val mean = DoubleArray(3)
        for (sample in data) {
            for (i in 0..2) mean[i] += sample[i]
        }
        return mean.map { it / data.size }.toDoubleArray()
    }

    private fun calculateCovariance(data: List<DoubleArray>, mean: DoubleArray): Array<DoubleArray> {
        val cov = Array(3) { DoubleArray(3) }
        for (sample in data) {
            val diff = DoubleArray(3) { sample[it] - mean[it] }
            for (i in 0..2) {
                for (j in 0..2) {
                    cov[i][j] += diff[i] * diff[j]
                }
            }
        }
        return cov.map { row -> row.map { it / (data.size - 1) }.toDoubleArray() }.toTypedArray()
    }

    private fun calculateMahalanobisDistance(x: DoubleArray, mean: DoubleArray, cov: Array<DoubleArray>): Double {
        return try {
            val diff = DoubleArray(3) { x[it] - mean[it] }
            val invCov = MatrixUtils.invert(cov)
            val temp = MatrixUtils.multiply(invCov, diff)
            sqrt(diff.zip(temp) { a, b -> a * b }.sum())
        } catch (e: Exception) {
            // 공분산 행렬이 특이행렬인 경우 유클리드 거리 사용
            sqrt(x.zip(mean) { a, b -> (a - b) * (a - b) }.sum())
        }
    }
}

/**
 * 다단계 복구 시스템
 */
class MultiLevelRecoverySystem {
    private var recoveryLevel = 0
    private val recoveryHistory = mutableListOf<String>()

    fun handleValidationFailure(reason: String) {
        logRecovery("LEVEL-1", "Input validation failed: $reason")
    }

    fun handleOutlierDetection(reason: String, distance: Double) {
        logRecovery("LEVEL-2", "Statistical outlier: $reason (distance: $distance)")
        if (distance > 50.0) {
            recoveryLevel = max(recoveryLevel, 2)
        }
    }

    fun handleConstraintViolation(reason: String) {
        recoveryLevel = max(recoveryLevel, 3)
        logRecovery("LEVEL-3", "Constraint violation: $reason")
    }

    fun handleIntegrationFailure(reason: String) {
        recoveryLevel = max(recoveryLevel, 4)
        logRecovery("LEVEL-4", "Integration failure: $reason")
    }

    fun handlePostValidationFailure(reason: String) {
        recoveryLevel = 5
        logRecovery("LEVEL-5", "Post-validation failure: $reason")
    }

    private fun logRecovery(level: String, message: String) {
        val logEntry = "[$level] ${System.currentTimeMillis()}: $message"
        recoveryHistory.add(logEntry)

        if (recoveryHistory.size > 50) {
            recoveryHistory.removeAt(0)
        }

        println("RECOVERY $logEntry")
    }

    fun getRecoveryLevel(): Int = recoveryLevel
    fun resetRecoveryLevel() { recoveryLevel = 0 }
}

class IMUPreintegration {
    // GTSAM 기본 상태
    private var theta = DoubleArray(3)
    private var pv = DoubleArray(3)
    private var va = DoubleArray(3)
    private var deltaTime = 0.0
    private var previousTime = 0L

    // Reference state
    private var referenceRotation = MatrixUtils.identity(3)
    private var referencePosition = DoubleArray(3)
    private var referenceVelocity = DoubleArray(3)
    private var gravity = doubleArrayOf(0.0, 0.0, -9.81)

    // Bias estimates
    private var accBias = DoubleArray(3)
    private var gyroBias = DoubleArray(3)

    // Covariance and Jacobians
    private var preintMeasCov = Array(9) { DoubleArray(9) { 0.0 } }
    private var jacobianPosWrtBiasAcc = Array(3) { DoubleArray(3) { 0.0 } }
    private var jacobianPosWrtBiasGyro = Array(3) { DoubleArray(3) { 0.0 } }
    private var jacobianVelWrtBiasAcc = Array(3) { DoubleArray(3) { 0.0 } }
    private var jacobianVelWrtBiasGyro = Array(3) { DoubleArray(3) { 0.0 } }
    private var jacobianRotWrtBiasGyro = Array(3) { DoubleArray(3) { 0.0 } }

    // **강화된 자동 복구 시스템**
    private val outlierDetector = StatisticalOutlierDetector()
    private val recoverySystem = MultiLevelRecoverySystem()

    // 누락된 변수들 추가
    private var consecutiveErrors = 0
    private var integrationCount = 0
    private var lastResetTime = 0L
    private var isHealthy = true
    private var lastValidState = Triple(DoubleArray(3), DoubleArray(3), DoubleArray(3))

    // 건강성 지표
    private var healthMetrics = HealthMetrics()

    data class HealthMetrics(
        var consecutiveOutliers: Int = 0,
        var totalIntegrations: Int = 0,
        var successfulIntegrations: Int = 0,
        var lastSuccessTime: Long = System.currentTimeMillis(),
        var adaptiveThresholds: AdaptiveThresholds = AdaptiveThresholds()
    )

    data class AdaptiveThresholds(
        var positionDriftThreshold: Double = 50.0,
        var velocityThreshold: Double = 30.0,
        var rotationThreshold: Double = PI/2,
        var adaptationRate: Double = 0.95
    )

    companion object {
        const val GYRO_NOISE_DENSITY = 1.6968e-04
        const val ACC_NOISE_DENSITY = 2.0000e-3
        const val GYRO_BIAS_RANDOM_WALK = 1.9393e-05
        const val ACC_BIAS_RANDOM_WALK = 3.0000e-3
        const val AUTO_RESET_THRESHOLD = 5
        const val MAX_INTEGRATION_TIME = 10.0
    }

    fun reset() {
        theta.fill(0.0)
        pv.fill(0.0)
        va.fill(0.0)
        deltaTime = 0.0
        previousTime = 0L

        preintMeasCov = Array(9) { DoubleArray(9) { 0.0 } }
        jacobianPosWrtBiasAcc = Array(3) { DoubleArray(3) { 0.0 } }
        jacobianPosWrtBiasGyro = Array(3) { DoubleArray(3) { 0.0 } }
        jacobianVelWrtBiasAcc = Array(3) { DoubleArray(3) { 0.0 } }
        jacobianVelWrtBiasGyro = Array(3) { DoubleArray(3) { 0.0 } }
        jacobianRotWrtBiasGyro = Array(3) { DoubleArray(3) { 0.0 } }

        // 자동 복구 시스템 리셋
        consecutiveErrors = 0
        integrationCount = 0
        isHealthy = true
        lastResetTime = System.currentTimeMillis()
        lastValidState = Triple(pv.copyOf(), va.copyOf(), theta.copyOf())
        recoverySystem.resetRecoveryLevel()
    }

    fun updateReference(position: DoubleArray, velocity: DoubleArray, rotation: Array<DoubleArray>) {
        if (isValidArray(position) && isValidArray(velocity) && isValidMatrix(rotation)) {
            referencePosition = position.copyOf()
            referenceVelocity = velocity.copyOf()
            referenceRotation = rotation.map { it.copyOf() }.toTypedArray()
        }
    }

    fun updateBias(newAccBias: DoubleArray, newGyroBias: DoubleArray) {
        require(newAccBias.size == 3 && newGyroBias.size == 3) { "Bias arrays must have 3 elements" }
        if (isValidArray(newAccBias) && isValidArray(newGyroBias)) {
            accBias = newAccBias.copyOf()
            gyroBias = newGyroBias.copyOf()
        }
    }

    fun integrate(imuData: IMUData, dt: Double) {
        integrateIMUMeasurement(imuData, dt)
    }

    /**
     * 강화된 IMU 적분 - 5단계 안전성 보장
     */
    fun integrateIMUMeasurement(imuData: IMUData, dt: Double) {
        healthMetrics.totalIntegrations++

        // **STAGE 1: 입력 검증 및 전처리**
        val validationResult = validateAndPreprocessInput(imuData, dt)
        if (!validationResult.first) {
            recoverySystem.handleValidationFailure(validationResult.second)
            handleError("Validation failed: ${validationResult.second}")
            return
        }
        val (processedData, safeDt) = validationResult.third!!

        // **STAGE 2: 통계적 이상치 감지**
        val outlierResult = outlierDetector.detectOutlier(processedData)
        if (outlierResult.first) {
            recoverySystem.handleOutlierDetection(outlierResult.third, outlierResult.second)
            healthMetrics.consecutiveOutliers++
            adjustAdaptiveThresholds(false)
            handleError("Outlier detected: ${outlierResult.third}")
            return
        }

        // **STAGE 3: 물리적 제약 조건 검사**
        val constraintResult = checkPhysicalConstraints(processedData, safeDt)
        if (!constraintResult.first) {
            recoverySystem.handleConstraintViolation(constraintResult.second)
            handleError("Constraint violation: ${constraintResult.second}")
            return
        }

        // **STAGE 4: 안전한 적분 수행**
        val integrationResult = performSafeIntegration(processedData, safeDt)
        if (!integrationResult.first) {
            recoverySystem.handleIntegrationFailure(integrationResult.second)
            handleError("Integration failed: ${integrationResult.second}")
            return
        }

        // **STAGE 5: 사후 검증**
        val postValidation = performPostIntegrationValidation()
        if (postValidation.first) {
            // 성공적인 적분
            healthMetrics.successfulIntegrations++
            healthMetrics.consecutiveOutliers = 0
            healthMetrics.lastSuccessTime = System.currentTimeMillis()
            adjustAdaptiveThresholds(true)
            lastValidState = Triple(pv.copyOf(), va.copyOf(), theta.copyOf())
            isHealthy = true
            consecutiveErrors = 0
        } else {
            recoverySystem.handlePostValidationFailure(postValidation.second)
            handleError("Post-validation failed: ${postValidation.second}")
        }
    }

    // **누락된 유틸리티 함수들 구현**

    private fun isValidIMUData(imuData: IMUData): Boolean {
        val acc = imuData.accelerometer
        val gyro = imuData.gyroscope

        return !(acc.first.isNaN() || acc.second.isNaN() || acc.third.isNaN() ||
                gyro.first.isNaN() || gyro.second.isNaN() || gyro.third.isNaN() ||
                abs(acc.first) > 200 || abs(acc.second) > 200 || abs(acc.third) > 200 ||
                abs(gyro.first) > 100 || abs(gyro.second) > 100 || abs(gyro.third) > 100)
    }

    private fun limitValue(value: Double, maxAbs: Double): Double {
        return max(-maxAbs, min(maxAbs, if (value.isFinite()) value else 0.0))
    }

    private fun isValidArray(arr: DoubleArray): Boolean {
        return arr.all { it.isFinite() && abs(it) < 1e6 }
    }

    private fun isValidMatrix(matrix: Array<DoubleArray>): Boolean {
        return matrix.all { row -> row.all { it.isFinite() && abs(it) < 1e6 } }
    }

    private fun safeExpmap(theta: DoubleArray): Array<DoubleArray>? {
        return try {
            val thetaMag = sqrt(theta.sumOf { it * it })
            if (thetaMag > PI) {
                val limited = theta.map { it * PI / thetaMag }.toDoubleArray()
                MatrixUtils.expmap(limited)
            } else {
                MatrixUtils.expmap(theta)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun safeMatrixVectorMultiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
        return try {
            if (isValidMatrix(matrix) && isValidArray(vector)) {
                MatrixUtils.multiply(matrix, vector)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun safeComposeRotation(theta1: DoubleArray, theta2: DoubleArray): DoubleArray {
        return try {
            val theta1Mag = sqrt(theta1.sumOf { it * it })
            val theta2Mag = sqrt(theta2.sumOf { it * it })

            if (theta1Mag < 1e-6 && theta2Mag < 1e-6) {
                doubleArrayOf(theta1[0] + theta2[0], theta1[1] + theta2[1], theta1[2] + theta2[2])
            } else {
                val R1 = safeExpmap(theta1) ?: MatrixUtils.identity(3)
                val R2 = safeExpmap(theta2) ?: MatrixUtils.identity(3)
                val Rcomposed = MatrixUtils.multiply(R1, R2)
                logSO3(Rcomposed)
            }
        } catch (e: Exception) {
            doubleArrayOf(
                limitValue(theta1[0] + theta2[0], PI),
                limitValue(theta1[1] + theta2[1], PI),
                limitValue(theta1[2] + theta2[2], PI)
            )
        }
    }

    private fun handleError(message: String) {
        consecutiveErrors++
        isHealthy = false

        if (consecutiveErrors >= AUTO_RESET_THRESHOLD) {
            performEmergencyReset()
        }
    }

    private fun performEmergencyReset() {
        if (lastValidState.first.all { it.isFinite() }) {
            pv = lastValidState.first.copyOf()
            va = lastValidState.second.copyOf()
            theta = lastValidState.third.copyOf()
        } else {
            reset()
        }
        isHealthy = true
        consecutiveErrors = 0
    }

    // **단계별 검증 함수들**

    private fun validateAndPreprocessInput(imuData: IMUData, dt: Double): Triple<Boolean, String, Pair<IMUData, Double>?> {
        if (!isValidIMUData(imuData)) {
            return Triple(false, "Invalid IMU data", null)
        }

        if (dt <= 0 || dt > 1.0) {
            return Triple(false, "Invalid dt: $dt", null)
        }

        val processedData = preprocessIMUDataAdvanced(imuData)
        val safeDt = min(0.1, dt)

        return Triple(true, "Valid", Pair(processedData, safeDt))
    }

    private fun preprocessIMUDataAdvanced(imuData: IMUData): IMUData {
        val acc = imuData.accelerometer
        val gyro = imuData.gyroscope

        // 중력 크기 정규화
        val accMag = sqrt(acc.first*acc.first + acc.second*acc.second + acc.third*acc.third)
        val gravityFactor = when {
            accMag < 5.0 -> 9.81 / 5.0
            accMag > 15.0 -> 9.81 / 15.0
            accMag in 8.0..12.0 -> 9.81 / accMag
            else -> 1.0
        }

        val filteredAcc = Triple(
            limitValue(acc.first * gravityFactor, 50.0),
            limitValue(acc.second * gravityFactor, 50.0),
            limitValue(acc.third * gravityFactor, 50.0)
        )

        val gyroMag = sqrt(gyro.first*gyro.first + gyro.second*gyro.second + gyro.third*gyro.third)
        val gyroScale = if (gyroMag > 10.0) 10.0 / gyroMag else 1.0

        val filteredGyro = Triple(
            limitValue(gyro.first * gyroScale, 10.0),
            limitValue(gyro.second * gyroScale, 10.0),
            limitValue(gyro.third * gyroScale, 10.0)
        )

        return IMUData(filteredAcc, filteredGyro, imuData.magnetometer, imuData.timestamp)
    }

    private fun checkPhysicalConstraints(imuData: IMUData, dt: Double): Pair<Boolean, String> {
        val acc = imuData.accelerometer
        val gyro = imuData.gyroscope

        val accMag = sqrt(acc.first*acc.first + acc.second*acc.second + acc.third*acc.third)
        if (accMag > 100.0) {
            return Pair(false, "Acceleration too high: $accMag")
        }

        val gyroMag = sqrt(gyro.first*gyro.first + gyro.second*gyro.second + gyro.third*gyro.third)
        if (gyroMag > 50.0) {
            return Pair(false, "Angular velocity too high: $gyroMag")
        }

        return Pair(true, "All constraints satisfied")
    }

    private fun performSafeIntegration(imuData: IMUData, dt: Double): Pair<Boolean, String> {
        return try {
            // 기존 GTSAM 적분 로직 사용
            deltaTime += dt
            integrationCount++

            // Bias correction
            val acc_corrected = doubleArrayOf(
                imuData.accelerometer.first - accBias[0],
                imuData.accelerometer.second - accBias[1],
                imuData.accelerometer.third - accBias[2]
            )
            val gyro_corrected = doubleArrayOf(
                imuData.gyroscope.first - gyroBias[0],
                imuData.gyroscope.second - gyroBias[1],
                imuData.gyroscope.third - gyroBias[2]
            )

            // Rotation matrix calculation
            val R_k = MatrixUtils.expmap(theta)
            val Hinv = MatrixUtils.computeHinv(theta)

            // Velocity integration
            val acc_world = MatrixUtils.multiply(R_k, acc_corrected)
            for (i in 0..2) {
                va[i] += acc_world[i] * dt
                va[i] = limitValue(va[i], healthMetrics.adaptiveThresholds.velocityThreshold)
            }

            // Position integration
            for (i in 0..2) {
                pv[i] += va[i] * dt + 0.5 * acc_world[i] * dt * dt
                pv[i] = limitValue(pv[i], healthMetrics.adaptiveThresholds.positionDriftThreshold)
            }

            // Rotation integration
            val omega_dt = gyro_corrected.map { it * dt }.toDoubleArray()
            theta = composeSO3(theta, omega_dt)

            // Covariance propagation
            propagateCovariance(acc_corrected, gyro_corrected, dt, R_k, Hinv)
            updateJacobians(acc_corrected, gyro_corrected, dt, R_k, Hinv)

            Pair(true, "Integration successful")
        } catch (e: Exception) {
            Pair(false, "Integration exception: ${e.message}")
        }
    }

    private fun performPostIntegrationValidation(): Pair<Boolean, String> {
        if (!pv.all { it.isFinite() } || !va.all { it.isFinite() } || !theta.all { it.isFinite() }) {
            return Pair(false, "Numerical instability")
        }

        val positionMag = sqrt(pv.sumOf { it * it })
        val velocityMag = sqrt(va.sumOf { it * it })
        val rotationMag = sqrt(theta.sumOf { it * it })

        val thresholds = healthMetrics.adaptiveThresholds

        if (positionMag > thresholds.positionDriftThreshold) {
            return Pair(false, "Position drift: $positionMag")
        }

        if (velocityMag > thresholds.velocityThreshold) {
            return Pair(false, "Velocity: $velocityMag")
        }

        if (rotationMag > thresholds.rotationThreshold) {
            return Pair(false, "Rotation: $rotationMag")
        }

        return Pair(true, "Validation passed")
    }

    private fun adjustAdaptiveThresholds(success: Boolean) {
        val thresholds = healthMetrics.adaptiveThresholds
        val rate = thresholds.adaptationRate

        if (success) {
            thresholds.positionDriftThreshold *= rate
            thresholds.velocityThreshold *= rate
            thresholds.rotationThreshold *= rate
        } else {
            thresholds.positionDriftThreshold *= (2.0 - rate)
            thresholds.velocityThreshold *= (2.0 - rate)
            thresholds.rotationThreshold *= (2.0 - rate)
        }

        thresholds.positionDriftThreshold = thresholds.positionDriftThreshold.coerceIn(10.0, 200.0)
        thresholds.velocityThreshold = thresholds.velocityThreshold.coerceIn(5.0, 100.0)
        thresholds.rotationThreshold = thresholds.rotationThreshold.coerceIn(PI/4, PI)
    }

    // **기존 함수들 유지**

    private fun composeSO3(theta1: DoubleArray, theta2: DoubleArray): DoubleArray {
        val theta1_norm = sqrt(theta1.sumOf { it * it })
        val theta2_norm = sqrt(theta2.sumOf { it * it })

        if (theta1_norm < 1e-8 && theta2_norm < 1e-8) {
            return doubleArrayOf(theta1[0] + theta2[0], theta1[1] + theta2[1], theta1[2] + theta2[2])
        }

        val R1 = MatrixUtils.expmap(theta1)
        val R2 = MatrixUtils.expmap(theta2)
        val R_composed = MatrixUtils.multiply(R1, R2)

        return logSO3(R_composed)
    }

    private fun logSO3(R: Array<DoubleArray>): DoubleArray {
        val trace = R[0][0] + R[1][1] + R[2][2]
        val theta_norm = acos(max(-1.0, min(1.0, (trace - 1.0) / 2.0)))

        if (theta_norm < 1e-8) {
            return doubleArrayOf(0.0, 0.0, 0.0)
        }

        val factor = theta_norm / (2.0 * sin(theta_norm))
        return doubleArrayOf(
            factor * (R[2][1] - R[1][2]),
            factor * (R[0][2] - R[2][0]),
            factor * (R[1][0] - R[0][1])
        )
    }

    fun predict(xi_position: DoubleArray, xi_velocity: DoubleArray,
                xi_quaternion: DoubleArray, gravity: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {

        if (!isHealthy || deltaTime < 0.01) {
            return Triple(xi_position.copyOf(), xi_velocity.copyOf(), xi_quaternion.copyOf())
        }

        val R_i = MatrixUtils.q_to_R(xi_quaternion)

        val gravity_position = gravity.map { it * 0.5 * deltaTime * deltaTime }.toDoubleArray()
        val gravity_velocity = gravity.map { it * deltaTime }.toDoubleArray()

        val imu_position_world = MatrixUtils.multiply(R_i, pv)
        val imu_velocity_world = MatrixUtils.multiply(R_i, va)

        val pred_position = DoubleArray(3)
        val pred_velocity = DoubleArray(3)

        for (i in 0..2) {
            pred_position[i] = xi_position[i] + xi_velocity[i] * deltaTime + imu_position_world[i] + gravity_position[i]
            pred_velocity[i] = xi_velocity[i] + imu_velocity_world[i] + gravity_velocity[i]
        }

        val R_delta = MatrixUtils.expmap(theta)
        val R_pred = MatrixUtils.multiply(R_i, R_delta)
        val pred_quaternion = MatrixUtils.R_to_q(R_pred)

        return Triple(pred_position, pred_velocity, pred_quaternion)
    }

    // **나머지 기존 함수들**

    private fun propagateCovariance(acc: DoubleArray, gyro: DoubleArray, dt: Double,
                                    Rk: Array<DoubleArray>, Hinv: Array<DoubleArray>) {
        // 기존 구현 유지
        val A = MatrixUtils.identity(9)
        for (i in 0..2) A[i][3 + i] = dt

        val accSkew = MatrixUtils.skew(acc)
        val RkAccSkewH = MatrixUtils.multiply(MatrixUtils.multiply(Rk, accSkew), Hinv)
        for (i in 0..2) {
            for (j in 0..2) {
                A[i][6 + j] = -RkAccSkewH[i][j] * dt * dt * 0.5
                A[3 + i][6 + j] = -RkAccSkewH[i][j] * dt
            }
        }

        val gyroSkew = MatrixUtils.skew(gyro)
        for (i in 0..2) {
            for (j in 0..2) {
                A[6 + i][6 + j] -= 0.5 * gyroSkew[i][j] * dt
            }
        }

        val B = Array(9) { DoubleArray(3) { 0.0 } }
        val C = Array(9) { DoubleArray(3) { 0.0 } }

        for (i in 0..2) {
            for (j in 0..2) {
                B[i][j] = Rk[i][j] * dt * dt * 0.5
                B[3 + i][j] = Rk[i][j] * dt
                C[6 + i][j] = Hinv[i][j] * dt
            }
        }

        val Qacc = createDiscreteAccNoiseCov(dt)
        val Qgyro = createDiscreteGyroNoiseCov(dt)

        val AP = MatrixUtils.multiply(A, preintMeasCov)
        val APAT = MatrixUtils.multiply(AP, MatrixUtils.transpose(A))

        val BQ = MatrixUtils.multiply(B, Qacc)
        val BQBT = MatrixUtils.multiply(BQ, MatrixUtils.transpose(B))

        val CQ = MatrixUtils.multiply(C, Qgyro)
        val CQCT = MatrixUtils.multiply(CQ, MatrixUtils.transpose(C))

        preintMeasCov = MatrixUtils.add(MatrixUtils.add(APAT, BQBT), CQCT)
    }

    private fun updateJacobians(acc: DoubleArray, gyro: DoubleArray, dt: Double,
                                Rk: Array<DoubleArray>, Hinv: Array<DoubleArray>) {
        // 기존 구현 유지 (간소화)
        for (i in 0..2) {
            for (j in 0..2) {
                jacobianPosWrtBiasAcc[i][j] = -Rk[i][j] * dt * dt * 0.5
                jacobianVelWrtBiasAcc[i][j] = -Rk[i][j] * dt
                jacobianRotWrtBiasGyro[i][j] = -Hinv[i][j] * dt
            }
        }
    }

    fun correctForBiasChange(biasAccChange: DoubleArray, biasGyroChange: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val posCorr = DoubleArray(3)
        val velCorr = DoubleArray(3)
        val thetaCorr = theta.copyOf()

        try {
            val dpDba = MatrixUtils.multiply(jacobianPosWrtBiasAcc, biasAccChange)
            val dpDbg = MatrixUtils.multiply(jacobianPosWrtBiasGyro, biasGyroChange)
            for (i in 0..2) posCorr[i] = pv[i] + dpDba[i] + dpDbg[i]

            val dvDba = MatrixUtils.multiply(jacobianVelWrtBiasAcc, biasAccChange)
            val dvDbg = MatrixUtils.multiply(jacobianVelWrtBiasGyro, biasGyroChange)
            for (i in 0..2) velCorr[i] = va[i] + dvDba[i] + dvDbg[i]

            val drDbg = MatrixUtils.multiply(jacobianRotWrtBiasGyro, biasGyroChange)
            for (i in 0..2) thetaCorr[i] += drDbg[i]
        } catch (e: Exception) {
            return Triple(pv.copyOf(), va.copyOf(), theta.copyOf())
        }

        return Triple(posCorr, velCorr, thetaCorr)
    }

    fun getPreintegratedMeasurements(): PreintegratedImuMeasurements {
        return PreintegratedImuMeasurements(
            deltaPosition = pv.copyOf(),
            deltaVelocity = va.copyOf(),
            deltaRotation = MatrixUtils.R_to_q(MatrixUtils.expmap(theta)),
            deltaTime = deltaTime,
            preintegratedMeasurementCov = preintMeasCov.map { it.copyOf() }.toTypedArray(),
            jacobianPosWrtBiasAcc = jacobianPosWrtBiasAcc.map { it.copyOf() }.toTypedArray(),
            jacobianPosWrtBiasGyro = jacobianPosWrtBiasGyro.map { it.copyOf() }.toTypedArray(),
            jacobianVelWrtBiasAcc = jacobianVelWrtBiasAcc.map { it.copyOf() }.toTypedArray(),
            jacobianVelWrtBiasGyro = jacobianVelWrtBiasGyro.map { it.copyOf() }.toTypedArray(),
            jacobianRotWrtBiasGyro = jacobianRotWrtBiasGyro.map { it.copyOf() }.toTypedArray()
        )
    }

    private fun createDiscreteAccNoiseCov(dt: Double): Array<DoubleArray> {
        val sigma = ACC_NOISE_DENSITY / sqrt(dt)
        val Q = MatrixUtils.identity(3)
        for (i in 0..2) Q[i][i] = sigma * sigma
        return Q
    }

    private fun createDiscreteGyroNoiseCov(dt: Double): Array<DoubleArray> {
        val sigma = GYRO_NOISE_DENSITY / sqrt(dt)
        val Q = MatrixUtils.identity(3)
        for (i in 0..2) Q[i][i] = sigma * sigma
        return Q
    }

    private fun extractSubmatrix(A: Array<DoubleArray>, startRow: Int, startCol: Int,
                                 rows: Int, cols: Int): Array<DoubleArray> {
        val result = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = A[startRow + i][startCol + j]
            }
        }
        return result
    }

    // Getters
    fun getDeltaPosition() = if (isHealthy) pv.copyOf() else DoubleArray(3)
    fun getDeltaVelocity() = if (isHealthy) va.copyOf() else DoubleArray(3)
    fun getDeltaRotation() = if (isHealthy) MatrixUtils.R_to_q(MatrixUtils.expmap(theta)) else doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    fun getDeltaTime() = deltaTime
    fun getAccumulatedTime() = deltaTime
    fun isReady() = isHealthy && deltaTime > 0.05

    fun getHealthReport(): String {
        return buildString {
            appendLine("=== IMU Preintegration Health Report ===")
            appendLine("System Healthy: $isHealthy")
            appendLine("Total Integrations: ${healthMetrics.totalIntegrations}")
            appendLine("Successful Integrations: ${healthMetrics.successfulIntegrations}")
            appendLine("Success Rate: ${if (healthMetrics.totalIntegrations > 0) healthMetrics.successfulIntegrations.toDouble() / healthMetrics.totalIntegrations else 0.0}")
            appendLine("Consecutive Errors: $consecutiveErrors")
            appendLine("Recovery Level: ${recoverySystem.getRecoveryLevel()}")
            appendLine("Adaptive Thresholds:")
            appendLine("  Position: ${healthMetrics.adaptiveThresholds.positionDriftThreshold}")
            appendLine("  Velocity: ${healthMetrics.adaptiveThresholds.velocityThreshold}")
            appendLine("  Rotation: ${healthMetrics.adaptiveThresholds.rotationThreshold}")
        }
    }
}
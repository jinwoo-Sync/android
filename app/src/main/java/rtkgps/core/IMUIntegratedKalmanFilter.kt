package com.company.rtkgps.core

import kotlin.math.*

class IMUAccumulationBuffer {
    private val targetHz = 4.0
    private val sourceHz = 50.0
    private val samplesPerUpdate = (sourceHz / targetHz).toInt() // 12-13 샘플

    private val imuBuffer = mutableListOf<IMUData>()
    private var lastProcessTime = 0L

    fun addIMUData(imuData: IMUData): IMUData? {
        imuBuffer.add(imuData)

        val currentTime = System.currentTimeMillis()
        val timeSinceLastProcess = currentTime - lastProcessTime

        // 시간 기반 또는 샘플 수 기반 트리거
        val shouldProcess = imuBuffer.size >= samplesPerUpdate ||
                timeSinceLastProcess >= 250 // 4Hz = 250ms

        if (shouldProcess && imuBuffer.isNotEmpty()) {
            val representativeIMU = createRepresentativeIMU()
            imuBuffer.clear()
            lastProcessTime = currentTime
            return representativeIMU
        }

        return null
    }

    /**
     * 누적된 IMU 샘플들의 대표값 생성
     * 수학적으로 정확한 평균과 적분 적용
     */
    private fun createRepresentativeIMU(): IMUData {
        val n = imuBuffer.size
        val dt_total = (imuBuffer.last().timestamp - imuBuffer.first().timestamp) / 1000.0
        val dt_avg = dt_total / n

        // 가속도: 평균값 사용 (노이즈 감소)
        val acc_sum = doubleArrayOf(0.0, 0.0, 0.0)
        val gyro_sum = doubleArrayOf(0.0, 0.0, 0.0)

        imuBuffer.forEach { imu ->
            acc_sum[0] += imu.accelerometer.first
            acc_sum[1] += imu.accelerometer.second
            acc_sum[2] += imu.accelerometer.third

            gyro_sum[0] += imu.gyroscope.first
            gyro_sum[1] += imu.gyroscope.second
            gyro_sum[2] += imu.gyroscope.third
        }

        // List<Double>에서 Triple로 변환
        val acc_avg_list = acc_sum.map { it / n }
        val gyro_avg_list = gyro_sum.map { it / n }

        val acc_avg = Triple(acc_avg_list[0], acc_avg_list[1], acc_avg_list[2])
        val gyro_avg = Triple(gyro_avg_list[0], gyro_avg_list[1], gyro_avg_list[2])

        return IMUData(
            accelerometer = acc_avg,
            gyroscope = gyro_avg,
            magnetometer = imuBuffer.last().magnetometer,
            timestamp = imuBuffer.last().timestamp
        )
    }
}

/**
 * 올바른 스케일의 GTSAM 방식 IMU-GPS 통합 칼만 필터
 * 기존 좌표계 변환 유틸리티들을 활용
 */
class IMUIntegratedKalmanFilter(private val coordinateTransform: PreciseCoordinateTransform) {

    // **Navigation State (Global GPS coordinates 기반)**
    private var navState = NavigationState()

    // **Reference Navigation State (마지막 GPS 업데이트 시점)**
    private var referenceNavState = NavigationState()
    private var referenceTimestamp = 0L

    // **GTSAM IMU Preintegrator (올바른 스케일 적용)**
    private val preintegrator = IMUPreintegration()

    // **IMU 좌표계 정렬기 (기존 유틸리티 활용)**
    private val coordinateAligner = IMUCoordinateAligner()

    // **State estimation covariance (15x15)**
    private var stateCovariance = Array(15) { DoubleArray(15) { 0.0 } }

    // **System state management**
    private var systemState = SystemState.UNINITIALIZED
    private var lastGPSTime = 0L
    private var lastIMUTime = 0L

    // **Quality tracking**
    private var lastGNSSQuality: PositionQuality? = null
    private var groundLevel = 0.0

    // IMU 누적 버퍼 추가
    private val imuAccumulator = IMUAccumulationBuffer()

    data class NavigationState(
        var latitude: Double = 0.0,               // GPS latitude (degrees)
        var longitude: Double = 0.0,              // GPS longitude (degrees)
        var altitude: Double = 0.0,               // GPS altitude (meters)
        var velocityENU: DoubleArray = DoubleArray(3), // ENU velocity (m/s)
        var rotationENU: Array<DoubleArray> = MatrixUtils.identity(3), // ENU rotation
        var accBias: DoubleArray = DoubleArray(3),    // IMU accelerometer bias
        var gyroBias: DoubleArray = DoubleArray(3),   // IMU gyroscope bias
        var timestamp: Long = 0L
    ) {
        fun copy(): NavigationState {
            return NavigationState(
                latitude, longitude, altitude,
                velocityENU.copyOf(),
                rotationENU.map { it.copyOf() }.toTypedArray(),
                accBias.copyOf(),
                gyroBias.copyOf(),
                timestamp
            )
        }

        // GPS 좌표를 UTM 미터로 변환 (기존 유틸리티 활용)
        fun getPositionMeters(transform: PreciseCoordinateTransform): DoubleArray {
            val (x, y) = transform.latLonToMeters(latitude, longitude)
            return doubleArrayOf(x, y, altitude)
        }

        // UTM 미터를 GPS 좌표로 변환 (기존 유틸리티 활용)
        fun setPositionFromMeters(x: Double, y: Double, z: Double, transform: PreciseCoordinateTransform) {
            val (lat, lon) = transform.metersToLatLon(x, y)
            latitude = lat
            longitude = lon
            altitude = z
        }
    }

    enum class SystemState {
        UNINITIALIZED,
        GPS_INITIALIZED,
        IMU_ALIGNED,
        FULLY_OPERATIONAL
    }

    companion object {
        const val MIN_GPS_INTERVAL_MS = 200L
        const val MAX_GPS_INTERVAL_MS = 2000L
        const val MIN_PREINTEGRATION_TIME = 0.2  // 200ms (4Hz 주기)
        val GRAVITY_ENU = doubleArrayOf(0.0, 0.0, -9.81)

        // 스케일 제한을 현실적으로 완화
        const val MAX_VELOCITY_CHANGE = 20.0     // 20m/s (현실적)
        const val MAX_POSITION_CHANGE = 100.0    // 100m (GPS 간격 고려)
        const val MAX_IMU_ACC = 100.0           // 100m/s² (급제동 고려)
        const val MAX_IMU_GYRO = 20.0           // 20rad/s (현실적)
    }

    init {
        initializeCovariance()
        preintegrator.reset()
    }

    private fun initializeCovariance() {
        // 초기 상태 불확실성 (현실적인 값들)
        for (i in 0..2) stateCovariance[i][i] = 25.0        // position uncertainty (5m std)
        for (i in 3..5) stateCovariance[i][i] = 4.0         // velocity uncertainty (2m/s std)
        for (i in 6..8) stateCovariance[i][i] = 0.01        // rotation uncertainty (0.1rad std)
        for (i in 9..11) stateCovariance[i][i] = 0.01       // acc bias uncertainty
        for (i in 12..14) stateCovariance[i][i] = 0.0001    // gyro bias uncertainty
    }

    /**
     * 개선된 IMU 처리 - 누적 샘플링 적용
     */
    fun processIMU(imuData: IMUData) {
        if (systemState == SystemState.UNINITIALIZED) return

        // IMU 데이터 누적 및 대표값 생성
        val representativeIMU = imuAccumulator.addIMUData(imuData)
        if (representativeIMU == null) return // 아직 누적 중

        val currentTime = representativeIMU.timestamp
        val dt = 0.25 // 4Hz 고정 주기

        // 검증된 IMU 데이터로 처리
        val validatedIMUData = validateAndLimitIMUData(representativeIMU)
        val enuIMUData = if (coordinateAligner.isReady()) {
            coordinateAligner.transformIMUToENU(validatedIMUData)
        } else {
            validatedIMUData
        }

        // GTSAM preintegration (정확한 dt 사용)
        preintegrator.updateBias(navState.accBias, navState.gyroBias)
        preintegrator.integrateIMUMeasurement(enuIMUData, dt)

        // 실시간 상태 전파 (개선된 스케일 관리)
        propagateStateWithAdaptiveValidation()
    }

    /**
     * 적응적 상태 전파 (GPS 품질 기반)
     */
    private fun propagateStateWithAdaptiveValidation() {
        val accTime = preintegrator.getAccumulatedTime()
        if (accTime < 0.2) return // 4Hz 주기 확보

        try {
            val refPosMeters = referenceNavState.getPositionMeters(coordinateTransform)
            val refQuat = MatrixUtils.R_to_q(referenceNavState.rotationENU)

            val (predPosMeters, predVel, predQuat) = preintegrator.predict(
                refPosMeters, referenceNavState.velocityENU, refQuat, GRAVITY_ENU
            )

            // GPS 품질 기반 적응적 제한
            val gpsQuality = lastGNSSQuality?.averageQuality ?: 0.5
            val adaptiveMaxPos = MAX_POSITION_CHANGE * (2.0 - gpsQuality) // 품질 나쁘면 더 관대
            val adaptiveMaxVel = MAX_VELOCITY_CHANGE * (2.0 - gpsQuality)

            // 위치 변화량 검증 (적응적 임계값)
            val deltaPos = sqrt(predPosMeters.zip(refPosMeters) { pred, ref ->
                (pred - ref) * (pred - ref)
            }.sum())

            if (deltaPos > adaptiveMaxPos) {
                println("위치 변화 제한: ${String.format("%.3f", deltaPos)}m → ${String.format("%.3f", adaptiveMaxPos)}m")
                val scale = adaptiveMaxPos / deltaPos
                for (i in 0..2) {
                    predPosMeters[i] = refPosMeters[i] + (predPosMeters[i] - refPosMeters[i]) * scale
                }
            }

            // 속도 변화량 검증 (적응적 임계값)
            val deltaVel = sqrt(predVel.zip(referenceNavState.velocityENU) { pred, ref ->
                (pred - ref) * (pred - ref)
            }.sum())

            if (deltaVel > adaptiveMaxVel) {
                println("속도 변화 제한: ${String.format("%.3f", deltaVel)}m/s → ${String.format("%.3f", adaptiveMaxVel)}m/s")
                val scale = adaptiveMaxVel / deltaVel
                for (i in 0..2) {
                    predVel[i] = referenceNavState.velocityENU[i] + (predVel[i] - referenceNavState.velocityENU[i]) * scale
                }
            }

            // Navigation state 업데이트
            navState.setPositionFromMeters(predPosMeters[0], predPosMeters[1], predPosMeters[2], coordinateTransform)
            navState.velocityENU = predVel.copyOf()
            navState.rotationENU = MatrixUtils.q_to_R(predQuat)
            navState.timestamp = System.currentTimeMillis()

            // 성능 로깅 (개선된)
            logPerformanceMetrics(deltaPos, deltaVel, accTime, gpsQuality)

        } catch (e: Exception) {
            println("상태 전파 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 성능 메트릭 로깅
     */
    private fun logPerformanceMetrics(deltaPos: Double, deltaVel: Double,
                                      accTime: Double, gpsQuality: Double) {
        val speed = sqrt(navState.velocityENU.sumOf { it * it })
        if (deltaPos > 0.01 || speed > 0.1) { // 1cm 이상 또는 0.1m/s 이상
            println("GTSAM 상태: Δpos=${String.format("%.2f", deltaPos*100)}cm, " +
                    "속도=${String.format("%.2f", speed)}m/s, " +
                    "누적=${String.format("%.3f", accTime)}s, " +
                    "GPS품질=${String.format("%.2f", gpsQuality)}")
        }
    }

    /**
     * **IMU 데이터 스케일 검증 및 제한**
     */
    private fun validateAndLimitIMUData(imuData: IMUData): IMUData {
        // 가속도 제한 (100m/s² 초과 시 제한)
        val accMagnitude = sqrt(imuData.accelerometer.first.pow(2) +
                imuData.accelerometer.second.pow(2) +
                imuData.accelerometer.third.pow(2))
        val accLimited = if (accMagnitude > MAX_IMU_ACC) {
            val scale = MAX_IMU_ACC / accMagnitude
            Triple(
                imuData.accelerometer.first * scale,
                imuData.accelerometer.second * scale,
                imuData.accelerometer.third * scale
            )
        } else {
            imuData.accelerometer
        }

        // 각속도 제한 (20rad/s 초과 시 제한)
        val gyroMagnitude = sqrt(imuData.gyroscope.first.pow(2) +
                imuData.gyroscope.second.pow(2) +
                imuData.gyroscope.third.pow(2))
        val gyroLimited = if (gyroMagnitude > MAX_IMU_GYRO) {
            val scale = MAX_IMU_GYRO / gyroMagnitude
            Triple(
                imuData.gyroscope.first * scale,
                imuData.gyroscope.second * scale,
                imuData.gyroscope.third * scale
            )
        } else {
            imuData.gyroscope
        }

        return IMUData(accLimited, gyroLimited, imuData.magnetometer, imuData.timestamp)
    }

    /**
     * **GPS 업데이트 시 Factor Graph 최적화**
     */
    fun processGPS(gpsPosition: RTKPosition) {
        val currentTime = System.currentTimeMillis()

        when (systemState) {
            SystemState.UNINITIALIZED -> {
                initializeWithGPS(gpsPosition, currentTime)
                return
            }
            else -> {
                val timeSinceLastGPS = currentTime - lastGPSTime

                if (timeSinceLastGPS < MIN_GPS_INTERVAL_MS) {
                    return
                }

                // **Factor Graph 최적화 수행**
                performScaleValidatedFactorGraphOptimization(gpsPosition)

                lastGPSTime = currentTime
            }
        }
    }

    /**
     * GPS 업데이트 시 적응적 가중치 적용
     */
    fun performScaleValidatedFactorGraphOptimization(gpsPosition: RTKPosition) {
        if (preintegrator.getAccumulatedTime() < MIN_PREINTEGRATION_TIME) {
            return
        }

        try {
            val (gpsX, gpsY) = coordinateTransform.latLonToMeters(gpsPosition.latitude, gpsPosition.longitude)
            val gpsObsMeters = doubleArrayOf(gpsX, gpsY, gpsPosition.altitude)
            val currentPosMeters = navState.getPositionMeters(coordinateTransform)

            val innovation = DoubleArray(3)
            for (i in 0..2) {
                innovation[i] = gpsObsMeters[i] - currentPosMeters[i]
            }
            val innovationNorm = sqrt(innovation.sumOf { it * it })

            // 적응적 GPS 가중치 (품질 기반)
            val gpsWeight = calculateAdaptiveGPSWeight(gpsPosition, innovationNorm)

            // Innovation gate (적응적)
            val maxInnovation = when (gpsPosition.fixType) {
                "RTK_FIXED" -> 20.0
                "RTK_FLOAT" -> 50.0
                "DGPS" -> 100.0
                else -> 200.0
            }

            if (innovationNorm > maxInnovation) {
                println("Innovation 초과: ${innovationNorm}m > ${maxInnovation}m - 점진적 업데이트")
                performGradualUpdate(gpsObsMeters, 0.05)
            } else {
                performWeightedUpdate(gpsObsMeters, gpsWeight)
            }

            updateReferenceState()
            resetPreintegration()

            println("GPS 융합: 가중치=${String.format("%.3f", gpsWeight)}, " +
                    "innovation=${String.format("%.2f", innovationNorm)}m, " +
                    "fix=${gpsPosition.fixType}")

        } catch (e: Exception) {
            println("GPS 융합 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 적응적 GPS 가중치 계산
     */
    private fun calculateAdaptiveGPSWeight(gpsPosition: RTKPosition, innovationNorm: Double): Double {
        // 기본 fix type 가중치
        val baseWeight = when (gpsPosition.fixType) {
            "RTK_FIXED" -> 0.9
            "RTK_FLOAT" -> 0.7
            "DGPS" -> 0.5
            "RAW_GPS" -> 0.3
            else -> 0.2
        }

        // Innovation 기반 조정
        val innovationWeight = when {
            innovationNorm < 1.0 -> 1.0
            innovationNorm < 5.0 -> 0.8
            innovationNorm < 15.0 -> 0.6
            innovationNorm < 50.0 -> 0.3
            else -> 0.1
        }

        // GNSS 품질 기반 조정
        val gnssWeight = lastGNSSQuality?.let { quality ->
            when {
                quality.averageQuality > 80 -> 1.0
                quality.averageQuality > 60 -> 0.9
                quality.averageQuality > 40 -> 0.7
                quality.averageQuality > 20 -> 0.5
                else -> 0.3
            }
        } ?: 0.7

        // 시간 기반 조정 (GPS 오래되면 가중치 감소)
        val timeSinceLastGPS = (System.currentTimeMillis() - lastGPSTime) / 1000.0
        val timeWeight = when {
            timeSinceLastGPS < 1.0 -> 1.0
            timeSinceLastGPS < 3.0 -> 0.9
            timeSinceLastGPS < 5.0 -> 0.7
            timeSinceLastGPS < 10.0 -> 0.5
            else -> 0.2
        }

        return baseWeight * innovationWeight * gnssWeight * timeWeight
    }

    /**
     * **가중 업데이트**
     */
    private fun performWeightedUpdate(gpsObsMeters: DoubleArray, weight: Double) {
        val currentPosMeters = navState.getPositionMeters(coordinateTransform)
        val updatedPosMeters = DoubleArray(3)

        for (i in 0..2) {
            updatedPosMeters[i] = (1 - weight) * currentPosMeters[i] + weight * gpsObsMeters[i]
        }

        navState.setPositionFromMeters(updatedPosMeters[0], updatedPosMeters[1], updatedPosMeters[2], coordinateTransform)

        // 속도 damping
        for (i in 0..2) {
            navState.velocityENU[i] *= (1 - weight * 0.2)
        }
    }

    /**
     * **점진적 업데이트 (큰 innovation 시)**
     */
    private fun performGradualUpdate(gpsObsMeters: DoubleArray, alpha: Double) {
        val currentPosMeters = navState.getPositionMeters(coordinateTransform)
        val updatedPosMeters = DoubleArray(3)

        for (i in 0..2) {
            updatedPosMeters[i] = (1 - alpha) * currentPosMeters[i] + alpha * gpsObsMeters[i]
        }

        navState.setPositionFromMeters(updatedPosMeters[0], updatedPosMeters[1], updatedPosMeters[2], coordinateTransform)
        navState.velocityENU.fill(0.0) // 속도 리셋
    }

    /**
     * **Reference state 업데이트 및 preintegration 리셋**
     */
    private fun updateReferenceState() {
        referenceNavState = navState.copy()
        referenceTimestamp = System.currentTimeMillis()
        println("Reference 업데이트: GPS(${String.format("%.6f", navState.latitude)}, ${String.format("%.6f", navState.longitude)})")
    }

    private fun resetPreintegration() {
        preintegrator.reset()
        val refPosMeters = referenceNavState.getPositionMeters(coordinateTransform)
        preintegrator.updateReference(refPosMeters, referenceNavState.velocityENU, referenceNavState.rotationENU)
        preintegrator.updateBias(referenceNavState.accBias, referenceNavState.gyroBias)
    }

    /**
     * **GPS로 초기화 (기존 유틸리티 활용)**
     */
    private fun initializeWithGPS(gpsPosition: RTKPosition, timestamp: Long) {
        try {
            coordinateTransform.initialize(gpsPosition.latitude, gpsPosition.longitude)

            navState.latitude = gpsPosition.latitude
            navState.longitude = gpsPosition.longitude
            navState.altitude = gpsPosition.altitude
            navState.velocityENU = doubleArrayOf(0.0, 0.0, 0.0)
            navState.rotationENU = MatrixUtils.identity(3)
            navState.accBias = doubleArrayOf(0.0, 0.0, 0.0)
            navState.gyroBias = doubleArrayOf(0.0, 0.0, 0.0)
            navState.timestamp = timestamp

            updateReferenceState()
            resetPreintegration()

            groundLevel = gpsPosition.altitude
            lastGPSTime = timestamp
            systemState = SystemState.GPS_INITIALIZED

            println("GTSAM IMU-GPS 필터 초기화 완료: (${gpsPosition.latitude}, ${gpsPosition.longitude})")

        } catch (e: Exception) {
            println("초기화 실패: ${e.message}")
            throw e
        }
    }

    // **기존 인터페이스 호환성 함수들 (수정됨)**
    fun initializeIMUAlignment(imuDataList: List<IMUData>) {
        if (systemState != SystemState.GPS_INITIALIZED) return

        if (coordinateAligner.initialize(imuDataList)) {
            systemState = SystemState.IMU_ALIGNED
            println("IMU 좌표계 정렬 완료")
        } else {
            println("IMU 좌표계 정렬 실패 - GPS 모드로 계속")
        }
    }

    fun processGNSS(gnssData: PreciseGNSSMeasurement) {
        lastGNSSQuality = gnssData.positionQuality
        if (systemState == SystemState.IMU_ALIGNED) {
            systemState = SystemState.FULLY_OPERATIONAL
        }
    }

    fun processBarometer(barometerData: BarometerData) {
        val pressureAltitude = 44330.0 * (1.0 - (barometerData.pressure / 1013.25).pow(0.1903))
        groundLevel = 0.9 * groundLevel + 0.1 * (pressureAltitude - navState.altitude)
    }

    fun getFilteredPosition(): FilteredPosition {
        if (systemState == SystemState.UNINITIALIZED) {
            return FilteredPosition(
                latitude = 0.0, longitude = 0.0, altitude = 0.0, accuracy = 999.0,
                fixType = "GTSAM_NOT_INIT", timestamp = System.currentTimeMillis(),
                filterQuality = FilterQuality.VERY_POOR, velocity = Triple(0.0, 0.0, 0.0),
                uncertainty = Triple(999.0, 999.0, 999.0), heightAboveGround = 0.0,
                attitude = Triple(0.0, 0.0, 0.0)
            )
        }

        val quat = MatrixUtils.R_to_q(navState.rotationENU)
        val (roll, pitch, yaw) = MatrixUtils.q_to_euler(quat)

        return FilteredPosition(
            latitude = navState.latitude,
            longitude = navState.longitude,
            altitude = navState.altitude,
            accuracy = calculateAccuracy(),
            fixType = "GTSAM_SCALE_FIXED",
            timestamp = System.currentTimeMillis(),
            filterQuality = determineFilterQuality(),
            velocity = Triple(navState.velocityENU[0], navState.velocityENU[1], navState.velocityENU[2]),
            uncertainty = Triple(sqrt(stateCovariance[0][0]), sqrt(stateCovariance[1][1]), sqrt(stateCovariance[2][2])),
            heightAboveGround = navState.altitude - groundLevel,
            attitude = Triple(roll, pitch, yaw)
        )
    }

    private fun calculateAccuracy(): Double = max(0.1, sqrt(stateCovariance[0][0] + stateCovariance[1][1] + stateCovariance[2][2]) / 3.0)

    private fun determineFilterQuality(): FilterQuality {
        val accuracy = calculateAccuracy()
        val accTime = preintegrator.getAccumulatedTime()
        return when {
            systemState != SystemState.GPS_INITIALIZED -> FilterQuality.POOR
            accuracy < 1.0 && accTime > 0.3 -> FilterQuality.EXCELLENT
            accuracy < 3.0 && accTime > 0.2 -> FilterQuality.GOOD
            accuracy < 8.0 -> FilterQuality.FAIR
            else -> FilterQuality.POOR
        }
    }

    fun isIMUAlignmentReady(): Boolean = systemState == SystemState.FULLY_OPERATIONAL
    fun getBiasEstimates(): Pair<DoubleArray, DoubleArray> = Pair(navState.accBias.copyOf(), navState.gyroBias.copyOf())

    fun getPerformanceReport(): String {
        val preintTime = preintegrator.getAccumulatedTime()
        val refPosMeters = referenceNavState.getPositionMeters(coordinateTransform)
        val currentPosMeters = navState.getPositionMeters(coordinateTransform)
        val deltaFromRef = sqrt(currentPosMeters.zip(refPosMeters) { curr, ref -> (curr - ref) * (curr - ref) }.sum())

        return buildString {
            appendLine("=== 스케일 수정된 GTSAM IMU-GPS 통합 필터 ===")
            appendLine("시스템 상태: $systemState")
            appendLine("Preintegration 시간: ${String.format("%.3f", preintTime)}s")
            appendLine("Reference로부터 거리: ${String.format("%.1f", deltaFromRef*100)}cm")
            appendLine("현재 속도: ${String.format("%.2f", sqrt(navState.velocityENU.sumOf { it*it }))}m/s")
            appendLine("GPS 좌표: (${String.format("%.6f", navState.latitude)}, ${String.format("%.6f", navState.longitude)})")
            appendLine("Bias 추정: acc=[${navState.accBias.joinToString { String.format("%.4f", it) }}], gyro=[${navState.gyroBias.joinToString { String.format("%.4f", it) }}]")
            appendLine("위치 불확실성: ${String.format("%.3f", calculateAccuracy())}m")
        }
    }
}
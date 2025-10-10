// PreciseGNSSDataCollector.kt - Constellation별 최적화 및 동적 품질 평가
package com.example.myapplication.data.gps

import android.content.Context
import android.location.*
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlin.math.*

class PreciseGNSSDataCollector(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var gnssCallback: ((PreciseGNSSMeasurement) -> Unit)? = null
    private var measurementsCallback: GnssMeasurementsEvent.Callback? = null
    private var statusCallback: GnssStatus.Callback? = null

    // 측정 데이터 버퍼
    private val measurementBuffer = mutableListOf<GnssMeasurement>()
    private val clockBuffer = mutableListOf<GnssClock>()

    // Constellation별 최소 품질 기준 (constellation 특성 고려)
    private val constellationMinCN0 = mapOf(
        1 to 35.0,  // GPS - 표준
        3 to 33.0,  // GLONASS - FDMA 특성으로 약간 낮게
        6 to 37.0,  // Galileo - 높은 품질 기대
        5 to 34.0,  // BeiDou - 양호
        4 to 36.0,  // QZSS - GPS 호환, 아시아 최적화
        7 to 32.0   // NavIC - 지역 위성, 관대하게
    )

    private val constellationMaxUncertainty = mapOf(
        1 to 500.0,  // GPS
        3 to 600.0,  // GLONASS - FDMA 특성
        6 to 400.0,  // Galileo - 높은 정밀도
        5 to 550.0,  // BeiDou
        4 to 450.0,  // QZSS
        7 to 700.0   // NavIC
    )

    // Constellation별 신뢰도 가중치
    private val constellationReliabilityWeights = mapOf(
        1 to 1.0,   // GPS - 기준
        6 to 1.1,   // Galileo - 높은 정확도
        4 to 1.05,  // QZSS - 아시아 최적화
        5 to 0.95,  // BeiDou - 양호
        3 to 0.9,   // GLONASS - FDMA 특성
        7 to 0.85   // NavIC - 지역 위성
    )

    // 위성 상태 추적
    private val satelliteStates = mutableMapOf<Int, SatelliteState>()
    private val constellationPerformance = mutableMapOf<Int, ConstellationPerformance>()

    // Multipath indicator constants
    companion object {
        const val MULTIPATH_INDICATOR_UNKNOWN = 0
        const val MULTIPATH_INDICATOR_PRESENT = 1
        const val MULTIPATH_INDICATOR_NOT_PRESENT = 2
    }

    fun setGNSSCallback(callback: (PreciseGNSSMeasurement) -> Unit) {
        gnssCallback = callback
    }

    // UI에 데이터 전달
    fun setGnssRawDataCallback(callback: (String) -> Unit) {
        gnssRawDataCallback = callback
    }

    private var gnssRawDataCallback: ((String) -> Unit)? = null

    @RequiresApi(Build.VERSION_CODES.N)
    fun start() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            throw Exception("GPS가 비활성화되어 있습니다")
        }

        measurementsCallback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                processGnssMeasurements(eventArgs)
            }

            override fun onStatusChanged(status: Int) {
                println("GNSS 측정 상태 변경: $status")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            statusCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    updateSatelliteStates(status)
                }
            }
        }

        try {
            locationManager.registerGnssMeasurementsCallback(measurementsCallback!!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && statusCallback != null) {
                locationManager.registerGnssStatusCallback(statusCallback!!)
            }
        } catch (e: SecurityException) {
            throw Exception("위치 권한이 필요합니다")
        }
    }

    fun stop() {
        measurementsCallback?.let {
            locationManager.unregisterGnssMeasurementsCallback(it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && statusCallback != null) {
            locationManager.unregisterGnssStatusCallback(statusCallback!!)
        }

        measurementBuffer.clear()
        clockBuffer.clear()
        satelliteStates.clear()
        constellationPerformance.clear()
    }

    private fun processGnssMeasurements(event: GnssMeasurementsEvent) {
        val clock = event.clock
        val measurements = event.measurements

        // GNSS 원시 데이터를 UI로 전송
        measurements.forEach { measurement ->
            val constellationName = getConstellationName(measurement.constellationType)
            val carrierPhase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    String.format("%.3f", measurement.accumulatedDeltaRangeMeters)
                } catch (e: Exception) {
                    "N/A"
                }
            } else {
                "N/A"
            }

            val gnssRawDataLine = "Time: ${System.currentTimeMillis()}, " +
                    "GNSS: $constellationName, " +
                    "SVID: ${measurement.svid}, " +
                    "C/N0: ${measurement.cn0DbHz}dBHz, " +
                    "PseudorangeRate: ${measurement.pseudorangeRateMetersPerSecond}, " +
                    "CarrierPhase: $carrierPhase, " +
                    "Info: State: ${measurement.state}, " +
                    "TimeOffsetNanos: ${measurement.timeOffsetNanos}"

            gnssRawDataCallback?.invoke(gnssRawDataLine)
        }
        // ===== 여기까지 추가 =====

        clockBuffer.add(clock)
        if (clockBuffer.size > 100) {
            clockBuffer.removeAt(0)
        }

        // 고급 측정 데이터 필터링
        val filteredMeasurements = measurements.filter { measurement ->
            isValidMeasurementWithConstellation(measurement)
        }

        if (filteredMeasurements.isNotEmpty()) {
            val preciseMeasurement = createAdvancedPreciseMeasurement(clock, filteredMeasurements)
            updateConstellationPerformance(preciseMeasurement)
            gnssCallback?.invoke(preciseMeasurement)
        }
    }

    /**
     * Constellation별 특성을 고려한 고급 유효성 검사
     */
    private fun isValidMeasurementWithConstellation(measurement: GnssMeasurement): Boolean {
        val constellation = measurement.constellationType

        // Constellation별 최소 CN0 기준
        val minCN0 = constellationMinCN0[constellation] ?: 35.0
        if (measurement.cn0DbHz < minCN0) return false

        // Constellation별 최대 불확실성 기준
        val maxUncertainty = constellationMaxUncertainty[constellation] ?: 500.0
        if (measurement.receivedSvTimeUncertaintyNanos > maxUncertainty) return false

        // 기본 상태 플래그 검사
        val state = measurement.state
        if ((state and GnssMeasurement.STATE_CODE_LOCK) == 0) return false
        if ((state and GnssMeasurement.STATE_TOW_DECODED) == 0) return false

        // RTK에 중요한 캐리어 페이즈 측정 가능성 확인
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasCarrierPhase = (state and GnssMeasurement.STATE_SYMBOL_SYNC) != 0 &&
                    (state and GnssMeasurement.STATE_BIT_SYNC) != 0
            if (!hasCarrierPhase) return false
        }

        // 멀티패스 지표 확인
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val multipathIndicator = measurement.multipathIndicator
                if (multipathIndicator == MULTIPATH_INDICATOR_PRESENT) {
                    return false
                }
            } catch (e: Exception) {
                // API가 지원되지 않는 경우 무시
            }
        }

        return true
    }

    /**
     * 고급 정밀 측정 데이터 생성
     */
    private fun createAdvancedPreciseMeasurement(clock: GnssClock, measurements: List<GnssMeasurement>): PreciseGNSSMeasurement {
    val processedSatellites = measurements.map { measurement ->
        val satState = satelliteStates[measurement.svid] ?: SatelliteState()

        // 의사거리 계산 (보정된 알고리즘)
        val pseudorange = calculatePrecisePseudorange(measurement, clock)

        // 캐리어 페이즈 계산
        val carrierPhase = calculateCarrierPhase(measurement)

        // 도플러 주파수 계산
        val dopplerShift = calculateDopplerShift(measurement)

        // Constellation별 가중 품질 지표 계산
        val qualityIndicator = calculateConstellationWeightedQuality(measurement, satState)

        PreciseSatelliteMeasurement(
            svid = measurement.svid,
            constellation = measurement.constellationType,
            frequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                measurement.carrierFrequencyHz.toDouble()
            } else {
                getFrequencyFromConstellation(measurement.constellationType)
            },
            pseudorange = pseudorange,
            pseudorangeUncertainty = measurement.receivedSvTimeUncertaintyNanos.toDouble() * 2.99792458e8 / 1e9,
            carrierPhase = carrierPhase,
            carrierPhaseUncertainty = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                measurement.accumulatedDeltaRangeUncertaintyMeters.toDouble()
            } else {
                1.0
            },
            dopplerShift = dopplerShift,
            cn0DbHz = measurement.cn0DbHz.toDouble(),
            qualityIndicator = qualityIndicator,
            elevationAngle = satState.elevationAngle,
            azimuthAngle = satState.azimuthAngle
        )
    }

    return PreciseGNSSMeasurement(
        timestamp = clock.timeNanos / 1_000_000L, // ✅ 위성 시간을 밀리초로 변환
        clockTime = clock.timeNanos,
        clockTimeUncertainty = clock.timeUncertaintyNanos.toDouble(),
        clockDrift = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            clock.driftNanosPerSecond
        } else {
            0.0
        },
        satellites = processedSatellites,
        positionQuality = calculateOptimizedPositionQuality(processedSatellites)
    )
}

    /**
     * Constellation별 가중 품질 지표 계산
     */
    private fun calculateConstellationWeightedQuality(measurement: GnssMeasurement, satState: SatelliteState): Double {
        var quality = 0.0
        val constellation = measurement.constellationType

        // Constellation별 가중치
        val constellationWeight = constellationReliabilityWeights[constellation] ?: 0.8

        // CN0 기반 품질 (constellation별 기준 적용)
        val minCN0 = constellationMinCN0[constellation] ?: 35.0
        val cn0Score = min(40.0, max(0.0, (measurement.cn0DbHz - minCN0) * 2.0))
        quality += cn0Score * constellationWeight

        // 고도각 기반 품질
        val elevationScore = min(20.0, max(0.0, satState.elevationAngle - 10.0) / 4.0)
        quality += elevationScore

        // 불확실성 기반 품질 (constellation별 기준)
        val maxUncertainty = constellationMaxUncertainty[constellation] ?: 500.0
        val uncertaintyScore = max(0.0, 20.0 - measurement.receivedSvTimeUncertaintyNanos / (maxUncertainty / 20.0))
        quality += min(20.0, uncertaintyScore)

        // 멀티패스 지표
        quality += getMultipathScore(measurement)

        return min(100.0, quality)
    }

    /**
     * 멀티패스 점수 계산
     */
    private fun getMultipathScore(measurement: GnssMeasurement): Double {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return when (measurement.multipathIndicator) {
                    MULTIPATH_INDICATOR_NOT_PRESENT -> 20.0
                    MULTIPATH_INDICATOR_PRESENT -> 5.0
                    else -> 10.0
                }
            } catch (e: Exception) {
                return 10.0
            }
        }
        return 10.0
    }

    /**
     * 최적화된 위치 품질 계산
     */
    private fun calculateOptimizedPositionQuality(satellites: List<PreciseSatelliteMeasurement>): PositionQuality {
        val numSatellites = satellites.size
        val avgCN0 = if (satellites.isNotEmpty()) satellites.map { it.cn0DbHz }.average() else 0.0
        val avgQuality = if (satellites.isNotEmpty()) satellites.map { it.qualityIndicator }.average() else 0.0

        // Constellation 다양성 점수
        val constellationCount = satellites.map { it.constellation }.distinct().size
        val diversityBonus = min(1.5, constellationCount / 4.0) // 4개 이상이면 최대 보너스

        // 정확한 HDOP/VDOP 계산
        val (hdop, vdop, pdop) = calculatePreciseDOP(satellites)

        // 다중 constellation 고려한 품질 계산
        val overallQuality = when {
            numSatellites >= 12 && constellationCount >= 3 && avgCN0 >= 38 && hdop < 1.5 -> FilterQuality.EXCELLENT
            numSatellites >= 8 && constellationCount >= 2 && avgCN0 >= 35 && hdop < 2.5 -> FilterQuality.GOOD
            numSatellites >= 6 && avgCN0 >= 32 && hdop < 4.0 -> FilterQuality.FAIR
            numSatellites >= 4 -> FilterQuality.POOR
            else -> FilterQuality.VERY_POOR
        }

        return PositionQuality(
            level = overallQuality,
            hdop = hdop / diversityBonus, // 다양성 보너스 적용
            vdop = vdop / diversityBonus,
            numberOfSatellites = numSatellites,
            averageCN0 = avgCN0,
            averageQuality = avgQuality * diversityBonus,
            constellationDiversity = constellationCount,
            diversityBonus = diversityBonus
        )
    }

    /**
     * Constellation 성능 업데이트
     */
    private fun updateConstellationPerformance(gnssData: PreciseGNSSMeasurement) {
        gnssData.satellites.groupBy { it.constellation }.forEach { (constellation, satellites) ->
            val perf = constellationPerformance.getOrPut(constellation) { ConstellationPerformance() }
            perf.update(satellites, gnssData.timestamp)
        }
    }

    /**
     * 개선된 의사거리 계산
     */
    private fun calculatePrecisePseudorange(measurement: GnssMeasurement, clock: GnssClock): Double {
        val tRxSeconds = clock.timeNanos.toDouble() / 1e9
        val tTxSeconds = measurement.receivedSvTimeNanos.toDouble() / 1e9
        val weekRollover = floor(tRxSeconds / 604800.0) * 604800.0

        var tTxCorrected = tTxSeconds
        if (tTxSeconds < tRxSeconds - 302400.0) {
            tTxCorrected += 604800.0
        } else if (tTxSeconds > tRxSeconds + 302400.0) {
            tTxCorrected -= 604800.0
        }

        val transitTime = tRxSeconds - tTxCorrected
        return transitTime * 2.99792458e8 // 광속
    }

    private fun calculateCarrierPhase(measurement: GnssMeasurement): Double {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            measurement.accumulatedDeltaRangeMeters.toDouble()
        } else {
            0.0
        }
    }

    private fun calculateDopplerShift(measurement: GnssMeasurement): Double {
        return measurement.pseudorangeRateMetersPerSecond.toDouble()
    }

    private fun getConstellationName(constellation: Int): String {
        return when (constellation) {
            1 -> "GPS(미국)"      // GnssStatus.CONSTELLATION_GPS
            3 -> "GLONASS(러시아)" // GnssStatus.CONSTELLATION_GLONASS
            6 -> "Galileo(유럽)"   // GnssStatus.CONSTELLATION_GALILEO
            5 -> "BeiDou(중국)"    // GnssStatus.CONSTELLATION_BEIDOU
            4 -> "QZSS(일본)"     // GnssStatus.CONSTELLATION_QZSS
            7 -> "NavIC(인도)"    // GnssStatus.CONSTELLATION_IRNSS
            else -> "알려지지 않음($constellation)"
        }
    }

    /**
     * 실시간 성능 보고서 생성
     */
    fun getConstellationPerformanceReport(): String {
        return buildString {
            appendLine("=== GNSS Constellation 성능 보고서 ===")
            constellationPerformance.forEach { (constellation, perf) ->
                val name = getConstellationName(constellation)
                val weight = constellationReliabilityWeights[constellation] ?: 0.8
                appendLine("$name: ${perf.satelliteCount}개 위성, " +
                        "평균 CN0: ${String.format("%.1f", perf.averageCN0)}dB-Hz, " +
                        "업데이트율: ${String.format("%.1f", perf.updateRate)}Hz, " +
                        "가중치: ${String.format("%.2f", weight)}")
            }
        }
    }

    // 기존 유틸리티 함수들 유지...
    private fun calculatePreciseDOP(satellites: List<PreciseSatelliteMeasurement>): Triple<Double, Double, Double> {
        if (satellites.size < 4) return Triple(99.0, 99.0, 99.0)

        try {
            val A = Array(satellites.size) { DoubleArray(4) }

            for (i in satellites.indices) {
                val sat = satellites[i]
                val elevation = Math.toRadians(sat.elevationAngle)
                val azimuth = Math.toRadians(sat.azimuthAngle)

                val cosEl = cos(elevation)
                val sinEl = sin(elevation)
                val cosAz = cos(azimuth)
                val sinAz = sin(azimuth)

                A[i][0] = cosEl * cosAz
                A[i][1] = cosEl * sinAz
                A[i][2] = sinEl
                A[i][3] = 1.0
            }

            val AT = transposeMatrix(A)
            val ATA = matrixMultiply(AT, A)
            val Q = invertMatrix(ATA)

            val hdop = sqrt(Q[0][0] + Q[1][1])
            val vdop = sqrt(Q[2][2])
            val pdop = sqrt(Q[0][0] + Q[1][1] + Q[2][2])

            val finalHdop = min(99.0, max(0.5, hdop))
            val finalVdop = min(99.0, max(0.5, vdop))
            val finalPdop = min(99.0, max(0.5, pdop))

            return Triple(finalHdop, finalVdop, finalPdop)

        } catch (e: Exception) {
            println("DOP 계산 오류: ${e.message}")
            return Triple(99.0, 99.0, 99.0)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateSatelliteStates(status: GnssStatus) {
        for (i in 0 until status.satelliteCount) {
            val svid = status.getSvid(i)
            val constellation = status.getConstellationType(i)
            val cn0 = status.getCn0DbHz(i).toDouble()
            val elevation = status.getElevationDegrees(i).toDouble()
            val azimuth = status.getAzimuthDegrees(i).toDouble()
            val hasEphemeris = status.hasEphemerisData(i)
            val hasAlmanac = status.hasAlmanacData(i)
            val usedInFix = status.usedInFix(i)

            satelliteStates[svid] = SatelliteState(
                svid = svid,
                constellation = constellation,
                cn0DbHz = cn0,
                elevationAngle = elevation,
                azimuthAngle = azimuth,
                hasEphemeris = hasEphemeris,
                hasAlmanac = hasAlmanac,
                usedInFix = usedInFix
            )
        }
    }

    private fun getFrequencyFromConstellation(constellation: Int): Double {
        return when (constellation) {
            1 -> 1575420000.0      // GPS L1
            3 -> 1602000000.0      // GLONASS L1
            6 -> 1575420000.0      // Galileo E1
            5 -> 1561098000.0      // BeiDou B1
            4 -> 1575420000.0      // QZSS L1
            7 -> 1176450000.0      // NavIC L5
            else -> 1575420000.0
        }
    }

    // Matrix utility functions (기존과 동일)
    private fun transposeMatrix(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val rows = matrix.size
        val cols = matrix[0].size
        val result = Array(cols) { DoubleArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j][i] = matrix[i][j]
            }
        }
        return result
    }

    private fun matrixMultiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rowsA = a.size
        val colsA = a[0].size
        val colsB = b[0].size
        val result = Array(rowsA) { DoubleArray(colsB) }
        for (i in 0 until rowsA) {
            for (j in 0 until colsB) {
                for (k in 0 until colsA) {
                    result[i][j] += a[i][k] * b[k][j]
                }
            }
        }
        return result
    }

    private fun invertMatrix(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val n = matrix.size
        val result = Array(n) { DoubleArray(n) }
        val temp = Array(n) { DoubleArray(2 * n) }

        for (i in 0 until n) {
            for (j in 0 until n) {
                temp[i][j] = matrix[i][j]
                temp[i][j + n] = if (i == j) 1.0 else 0.0
            }
        }

        for (i in 0 until n) {
            var maxRow = i
            for (k in i + 1 until n) {
                if (abs(temp[k][i]) > abs(temp[maxRow][i])) {
                    maxRow = k
                }
            }

            if (maxRow != i) {
                val tempRow = temp[i]
                temp[i] = temp[maxRow]
                temp[maxRow] = tempRow
            }

            val pivot = temp[i][i]
            if (abs(pivot) < 1e-10) {
                throw IllegalArgumentException("Matrix is singular")
            }

            for (j in 0 until 2 * n) {
                temp[i][j] /= pivot
            }

            for (k in 0 until n) {
                if (k != i) {
                    val factor = temp[k][i]
                    for (j in 0 until 2 * n) {
                        temp[k][j] -= factor * temp[i][j]
                    }
                }
            }
        }

        for (i in 0 until n) {
            for (j in 0 until n) {
                result[i][j] = temp[i][j + n]
            }
        }

        return result
    }
}

// 업데이트된 데이터 클래스들

// 데이터 클래스들
data class PreciseGNSSMeasurement(
    val timestamp: Long,
    val clockTime: Long,
    val clockTimeUncertainty: Double,
    val clockDrift: Double,
    val satellites: List<PreciseSatelliteMeasurement>,
    val positionQuality: PositionQuality
)

data class PreciseSatelliteMeasurement(
    val svid: Int,
    val constellation: Int,
    val frequency: Double,
    val pseudorange: Double,
    val pseudorangeUncertainty: Double,
    val carrierPhase: Double,
    val carrierPhaseUncertainty: Double,
    val dopplerShift: Double,
    val cn0DbHz: Double,
    val qualityIndicator: Double,
    val elevationAngle: Double,
    val azimuthAngle: Double
)

data class SatelliteState(
    val svid: Int = 0,
    val constellation: Int = 0,
    val cn0DbHz: Double = 0.0,
    val elevationAngle: Double = 0.0,
    val azimuthAngle: Double = 0.0,
    val hasEphemeris: Boolean = false,
    val hasAlmanac: Boolean = false,
    val usedInFix: Boolean = false
)

data class PositionQuality(
    val level: FilterQuality = FilterQuality.POOR,
    val hdop: Double = 99.0,
    val vdop: Double = 99.0,
    val numberOfSatellites: Int = 0,
    val averageCN0: Double = 0.0,
    val averageQuality: Double = 0.0,
    val constellationDiversity: Int = 0,
    val diversityBonus: Double = 1.0
)

data class ConstellationPerformance(
    var satelliteCount: Int = 0,
    var averageCN0: Double = 0.0,
    var lastUpdateTime: Long = 0L,
    var updateCount: Long = 0L,
    var updateRate: Double = 0.0
) {
    fun update(satellites: List<PreciseSatelliteMeasurement>, timestamp: Long) {
        val previousTime = lastUpdateTime

        satelliteCount = satellites.size
        averageCN0 = if (satellites.isNotEmpty()) satellites.map { it.cn0DbHz }.average() else 0.0
        lastUpdateTime = timestamp
        updateCount++

        if (previousTime > 0 && updateCount > 1) {
            val deltaTime = (timestamp - previousTime) / 1000.0
            updateRate = if (deltaTime > 0) 1.0 / deltaTime else 0.0
        }
    }

    fun calculateOptimalWeight(): Double {
        val satelliteWeight = minOf(1.0, satelliteCount / 8.0)
        val cn0Weight = minOf(1.0, averageCN0 / 40.0)
        val rateWeight = minOf(1.0, updateRate / 4.0)
        return (satelliteWeight * 0.4 + cn0Weight * 0.4 + rateWeight * 0.2)
    }
}
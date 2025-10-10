package com.company.rtkgps.core

/**
 * 동적 가중치 관리자 - GNSS/IMU 신뢰도 기반 가중치 조정
 */
class AdaptiveWeightManager {
    private var gnssWeight = 0.5
    private var imuWeight = 0.5
    private var reliabilityBuffer = mutableListOf<Double>()
    private val bufferSize = 20

    // 가중치 변화 제한 (급격한 변화 방지)
    private val maxWeightChangeRate = 0.1
    private var lastGnssWeight = 0.5

    companion object {
        const val GNSS_TIMEOUT_MS = 10000L
        const val EXCELLENT_GNSS_WEIGHT = 0.85
        const val GOOD_GNSS_WEIGHT = 0.7
        const val FAIR_GNSS_WEIGHT = 0.5
        const val POOR_GNSS_WEIGHT = 0.2
        const val IMU_ONLY_WEIGHT = 0.0
    }

    fun updateWeights(gnssQuality: PositionQuality?, timeSinceGNSS: Long) {
        val targetGnssWeight = calculateTargetWeight(gnssQuality, timeSinceGNSS)

        // 급격한 가중치 변화 방지
        val weightChange = targetGnssWeight - lastGnssWeight
        val limitedChange = weightChange.coerceIn(-maxWeightChangeRate, maxWeightChangeRate)

        gnssWeight = (lastGnssWeight + limitedChange).coerceIn(0.0, 1.0)
        imuWeight = 1.0 - gnssWeight
        lastGnssWeight = gnssWeight

        // 신뢰도 업데이트
        updateReliabilityBuffer(calculateReliability(gnssQuality))
    }

    private fun calculateTargetWeight(gnssQuality: PositionQuality?, timeSinceGNSS: Long): Double {
        return when {
            gnssQuality == null || timeSinceGNSS > GNSS_TIMEOUT_MS -> IMU_ONLY_WEIGHT
            gnssQuality.level == FilterQuality.EXCELLENT -> {
                EXCELLENT_GNSS_WEIGHT * gnssQuality.diversityBonus
            }
            gnssQuality.level == FilterQuality.GOOD -> {
                GOOD_GNSS_WEIGHT * gnssQuality.diversityBonus
            }
            gnssQuality.level == FilterQuality.FAIR -> FAIR_GNSS_WEIGHT
            gnssQuality.level == FilterQuality.POOR -> POOR_GNSS_WEIGHT
            else -> 0.05
        }.coerceIn(0.0, 0.95)
    }

    private fun calculateReliability(quality: PositionQuality?): Double {
        return when (quality?.level) {
            FilterQuality.EXCELLENT -> 0.95
            FilterQuality.GOOD -> 0.8
            FilterQuality.FAIR -> 0.6
            FilterQuality.POOR -> 0.3
            else -> 0.1
        }
    }

    private fun updateReliabilityBuffer(reliability: Double) {
        reliabilityBuffer.add(reliability)
        if (reliabilityBuffer.size > bufferSize) {
            reliabilityBuffer.removeAt(0)
        }
    }

    fun getAverageReliability(): Double {
        return if (reliabilityBuffer.isNotEmpty()) {
            reliabilityBuffer.average()
        } else 0.5
    }

    fun getGNSSWeight() = gnssWeight
    fun getIMUWeight() = imuWeight

    fun getWeightStatus(): String {
        return when {
            gnssWeight > 0.8 -> "GNSS 주도"
            gnssWeight > 0.5 -> "균형"
            gnssWeight > 0.2 -> "IMU 주도"
            else -> "IMU 전용"
        }
    }
}
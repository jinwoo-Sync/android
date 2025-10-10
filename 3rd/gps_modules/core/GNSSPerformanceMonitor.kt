package com.company.rtkgps.core

// GNSSPerformanceMonitor.kt - 새 파일
class GNSSPerformanceMonitor {

/*    private val performanceStats = mutableMapOf<Int, ConstellationPerformance>()

    fun updatePerformance(gnssData: PreciseGNSSMeasurement) {
        gnssData.satellites.groupBy { it.constellation }.forEach { (constellation, satellites) ->
            val perf = performanceStats.getOrPut(constellation) { ConstellationPerformance() }
            perf.update(satellites, gnssData.timestamp)
        }
    }

    fun getOptimalConstellationMix(): Map<Int, Double> {
        // 실시간 성능 기반 constellation 가중치 조정
        return performanceStats.mapValues { (_, perf) ->
            perf.calculateOptimalWeight()
        }
    }

    fun is4HzAchieved(): Boolean {
        val currentTime = System.currentTimeMillis()
        return performanceStats.values.all { perf ->
            currentTime - perf.lastUpdateTime < 300 // 250ms + 여유
        }
    }

    fun getConstellationReport(): String {
        return buildString {
            appendLine("=== GNSS Constellation 성능 보고서 ===")
            performanceStats.forEach { (constellation, perf) ->
                val name = getConstellationName(constellation)
                appendLine("$name: ${perf.satelliteCount}개 위성, " +
                        "평균 CN0: ${String.format("%.1f", perf.averageCN0)}dB-Hz, " +
                        "업데이트율: ${String.format("%.1f", perf.updateRate)}Hz")
            }
        }
    }

    private fun getConstellationName(constellation: Int): String {
        return when (constellation) {
            GnssStatus.CONSTELLATION_GPS -> "GPS(미국)"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS(러시아)"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo(유럽)"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou(중국)"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS(일본)"
            GnssStatus.CONSTELLATION_IRNSS -> "NavIC(인도)"
            else -> "알려지지 않음($constellation)"
        }
    }
}

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

        // 업데이트율 계산
        if (previousTime > 0 && updateCount > 1) {
            val deltaTime = (timestamp - previousTime) / 1000.0
            updateRate = if (deltaTime > 0) 1.0 / deltaTime else 0.0
        }
    }

    fun calculateOptimalWeight(): Double {
        // 위성 개수, CN0, 업데이트율 기반 가중치
        val satelliteWeight = minOf(1.0, satelliteCount / 8.0) // 8개 이상이면 최대
        val cn0Weight = minOf(1.0, averageCN0 / 40.0) // 40dB-Hz 이상이면 최대
        val rateWeight = minOf(1.0, updateRate / 4.0) // 4Hz 이상이면 최대

        return (satelliteWeight * 0.4 + cn0Weight * 0.4 + rateWeight * 0.2)
    }*/
}
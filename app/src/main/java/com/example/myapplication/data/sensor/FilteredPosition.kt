package com.example.myapplication.data.sensor

enum class FilterQuality {
    EXCELLENT,  // 매우 우수 (< 5m)
    GOOD,       // 좋음 (< 10m)
    FAIR,       // 보통 (< 20m)
    POOR,       // 나쁨 (< 50m)
    VERY_POOR   // 매우 나쁨 (>= 50m)
}

data class FilteredPosition(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Double,
    val fixType: String,
    val timestamp: Long,
    val filterQuality: FilterQuality,
    val velocity: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    val uncertainty: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    val heightAboveGround: Double = 0.0,
    val attitude: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0)
)
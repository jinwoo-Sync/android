package com.example.myapplication.gps_modules.models

data class ComparisonResult(
    val imuIntegratedResult: FilteredPosition,
    val gpsOnlyResult: FilteredPosition,
    val accuracyDifference: Double,
    val positionDifference: Double,
    val timestamp: Long,
    val coordinateReference: Triple<Double, Double, Int>
)

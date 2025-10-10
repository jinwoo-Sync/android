package com.company.rtkgps.core

data class ComparisonResult(
    val imuIntegratedResult: FilteredPosition,
    val gpsOnlyResult: FilteredPosition,
    val accuracyDifference: Double,
    val positionDifference: Double,
    val timestamp: Long,
    val coordinateReference: Triple<Double, Double, Int>
)

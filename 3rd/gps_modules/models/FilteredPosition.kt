package com.example.myapplication.gps_modules.models

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

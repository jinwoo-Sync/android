package com.example.myapplication.gps_modules.models

data class RTKPosition(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Double,
    val fixType: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
package com.example.myapplication.data.sensor

import kotlin.math.*

object LocationUtils {
    const val EARTH_RADIUS = 6371000.0 // 지구 반지름 (미터)

    /**
     * 두 GPS 좌표 간의 거리 계산 (Haversine formula)
     */
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }

    /**
     * GPS 좌표를 미터 단위 좌표로 변환 (기준점 기준)
     */
    fun toMeters(latReference: Double, lonReference: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val deltaLat = lat - latReference
        val deltaLon = lon - lonReference

        val x = deltaLon * Math.toRadians(EARTH_RADIUS) * cos(Math.toRadians(latReference))
        val y = deltaLat * Math.toRadians(EARTH_RADIUS)

        return Pair(x, y)
    }

    /**
     * 미터 단위 좌표를 GPS 좌표로 변환
     */
    fun fromMeters(latReference: Double, lonReference: Double, x: Double, y: Double): Pair<Double, Double> {
        val deltaLat = y / (Math.toRadians(EARTH_RADIUS))
        val deltaLon = x / (Math.toRadians(EARTH_RADIUS) * cos(Math.toRadians(latReference)))

        return Pair(latReference + deltaLat, lonReference + deltaLon)
    }
}
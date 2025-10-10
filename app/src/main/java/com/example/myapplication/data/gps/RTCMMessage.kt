// RTCMMessage.kt - 통합된 정의
package com.example.myapplication.data.gps

open class RTCMMessage(
    open val messageType: Int,
    open val data: ByteArray,
    open val timestamp: Long
) {
    data class StationPosition(
        override val messageType: Int,
        override val data: ByteArray,
        override val timestamp: Long,
        val stationId: Long,
        val ecefX: Double,
        val ecefY: Double,
        val ecefZ: Double
    ) : RTCMMessage(messageType, data, timestamp)

    data class GPSObservations(
        override val messageType: Int,
        override val data: ByteArray,
        override val timestamp: Long,
        val stationId: Long,
        val epochTime: Long
    ) : RTCMMessage(messageType, data, timestamp)

    data class GLONASSObservations(
        override val messageType: Int,
        override val data: ByteArray,
        override val timestamp: Long,
        val stationId: Long,
        val epochTime: Long
    ) : RTCMMessage(messageType, data, timestamp)

    data class GalileoObservations(
        override val messageType: Int,
        override val data: ByteArray,
        override val timestamp: Long,
        val stationId: Long,
        val epochTime: Long
    ) : RTCMMessage(messageType, data, timestamp)

    data class BeiDouObservations(
        override val messageType: Int,
        override val data: ByteArray,
        override val timestamp: Long,
        val stationId: Long,
        val epochTime: Long
    ) : RTCMMessage(messageType, data, timestamp)

    data class Unknown(
        override val messageType: Int,
        override val data: ByteArray,
        override val timestamp: Long
    ) : RTCMMessage(messageType, data, timestamp)
}
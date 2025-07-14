package com.example.myapplication.model

import android.location.GnssAntennaInfo
import android.location.GnssMeasurement
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.os.Build
import androidx.annotation.RequiresApi

data class ComprehensiveGnssData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,
    val isGpsTimeValid: Boolean = true,
    val gnssType: String,
    val constellationType: Int,
    val satelliteId: Int,
    val svid: Int,
    val signalStrength: Double,
    val carrierFrequencyHz: Double?,
    val multipathIndicator: Int,
    val pseudorangeRate: Double?,
    val pseudorangeRateUncertainty: Double?,
    val accumulatedDeltaRange: Double?,
    val accumulatedDeltaRangeState: Int,
    val accumulatedDeltaRangeUncertainty: Double?,
    val carrierPhase: Double?,
    val carrierPhaseUncertainty: Double?,
    val carrierCycles: Long?,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertainty: Long,
    val timeOffsetNanos: Double,
    val state: Int,
    val automaticGainControl: Double?,
    val additionalInfo: String,
    val basebandCn0DbHz: Double?,
    val fullInterSignalBiasNanos: Double?,
    val fullInterSignalBiasUncertaintyNanos: Double?,
    val satelliteInterSignalBiasNanos: Double?,
    val satelliteInterSignalBiasUncertaintyNanos: Double?,
    val codeType: String?
)

data class GnssSatelliteStatus(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,
    val satelliteIndex: Int,
    val constellationType: Int,
    val svid: Int,
    val cn0DbHz: Float,
    val hasCarrierFrequency: Boolean,
    val carrierFrequencyHz: Float?,
    val azimuthDegrees: Float,
    val elevationDegrees: Float,
    val hasAlmanacData: Boolean,
    val hasEphemerisData: Boolean,
    val usedInFix: Boolean,
    val totalSatelliteCount: Int,
    val usedSatelliteCount: Int
)

data class GnssNavigationData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,
    val messageId: Int,
    val submessageId: Int,
    val type: Int,
    val status: Int,
    val svid: Int,
    val data: ByteArray,
    val dataLength: Int,
    val additionalInfo: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GnssNavigationData
        return messageId == other.messageId &&
                submessageId == other.submessageId &&
                svid == other.svid &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = messageId
        result = 31 * result + submessageId
        result = 31 * result + svid
        result = 31 * result + data.contentHashCode()
        return result
    }
}

@RequiresApi(Build.VERSION_CODES.R)
data class GnssAntennaData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,
    val carrierFrequencyMHz: Double,
    val phaseCenterOffsetX: Double,
    val phaseCenterOffsetY: Double,
    val phaseCenterOffsetZ: Double,
    val phaseCenterOffsetUncertaintyX: Double,
    val phaseCenterOffsetUncertaintyY: Double,
    val phaseCenterOffsetUncertaintyZ: Double,
    val phaseCenterVariationCorrections: DoubleArray?,
    val phaseCenterVariationUncertainties: DoubleArray?,
    val signalGainCorrections: DoubleArray?,
    val signalGainUncertainties: DoubleArray?,
    val additionalInfo: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GnssAntennaData
        return carrierFrequencyMHz == other.carrierFrequencyMHz &&
                phaseCenterVariationCorrections.contentEquals(other.phaseCenterVariationCorrections) &&
                signalGainCorrections.contentEquals(other.signalGainCorrections)
    }

    override fun hashCode(): Int {
        var result = carrierFrequencyMHz.hashCode()
        result = 31 * result + (phaseCenterVariationCorrections?.contentHashCode() ?: 0)
        result = 31 * result + (signalGainCorrections?.contentHashCode() ?: 0)
        return result
    }
}

data class GnssClockData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,
    val timeNanos: Long,
    val timeUncertaintyNanos: Double?,
    val leapSecond: Int?,
    val biasNanos: Double?,
    val biasUncertaintyNanos: Double?,
    val driftNanosPerSecond: Double?,
    val driftUncertaintyNanosPerSecond: Double?,
    val hardwareClockDiscontinuityCount: Int?,
    val fullBiasNanos: Long?,
    val additionalInfo: String
)

data class GnssSessionSummary(
    val sessionStartTime: Long,
    val sessionEndTime: Long?,
    val firstFixTime: Long?,
    val totalSatellitesUsed: Int,
    val averageSignalStrength: Double,
    val sessionDuration: Long,
    val additionalInfo: String
)

data class GnssData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,
    val gnssType: String,
    val satelliteId: Int,
    val signalStrength: Double,
    val pseudorangeRate: Double?,
    val carrierPhase: Double?,
    val additionalInfo: String
)

/**
 * ✅ GNSS 신호 품질 등급
 */
enum class SignalQuality {
    EXCELLENT,  // C/N0 > 45 dB-Hz
    GOOD,       // C/N0 35-45 dB-Hz
    FAIR,       // C/N0 25-35 dB-Hz
    POOR,       // C/N0 15-25 dB-Hz
    VERY_POOR   // C/N0 < 15 dB-Hz
}

enum class GnssConstellationType(val id: Int, val displayName: String) {
    UNKNOWN(0, "Unknown"),
    GPS(1, "GPS"),
    SBAS(2, "SBAS"),
    GLONASS(3, "GLONASS"),
    QZSS(4, "QZSS"),
    BEIDOU(5, "BeiDou"),
    GALILEO(6, "Galileo"),
    IRNSS(7, "IRNSS");

    companion object {
        fun fromId(id: Int): GnssConstellationType {
            return values().find { it.id == id } ?: UNKNOWN
        }
    }
}
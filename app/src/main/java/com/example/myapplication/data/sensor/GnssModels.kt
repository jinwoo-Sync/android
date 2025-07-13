package com.example.myapplication.model

import android.location.GnssAntennaInfo
import android.location.GnssMeasurement
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * ✅ 완전한 GNSS 측정 데이터 (GnssMeasurementsEvent의 모든 정보)
 */
data class ComprehensiveGnssData(
    // 기본 시간 정보
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,
    val isGpsTimeValid: Boolean = true,

    // 위성 기본 정보
    val gnssType: String,
    val constellationType: Int,
    val satelliteId: Int,
    val svid: Int,

    // 신호 품질 및 강도
    val signalStrength: Double,          // C/N0 (dB-Hz)
    val carrierFrequencyHz: Double?,     // 반송파 주파수
    val multipathIndicator: Int,         // 멀티패스 지표

    // 거리 측정값
    val pseudorangeRate: Double?,        // 의사거리 변화율 (m/s)
    val pseudorangeRateUncertainty: Double?, // 의사거리 변화율 불확실성
    val accumulatedDeltaRange: Double?,  // 누적 델타 거리 (m)
    val accumulatedDeltaRangeState: Int, // 누적 델타 거리 상태
    val accumulatedDeltaRangeUncertainty: Double?, // 누적 델타 거리 불확실성

    // 반송파 위상 정보
    val carrierPhase: Double?,           // 반송파 위상
    val carrierPhaseUncertainty: Double?, // 반송파 위상 불확실성
    val carrierCycles: Long?,            // 반송파 사이클 수

    // 수신 시간 정보
    val receivedSvTimeNanos: Long,       // 위성 시간 (나노초)
    val receivedSvTimeUncertainty: Long, // 위성 시간 불확실성
    val timeOffsetNanos: Double,         // 시간 오프셋

    // 측정 상태
    val state: Int,                      // 측정 상태 플래그
    val automaticGainControl: Double?,   // 자동 이득 제어 (dB)

    // 추가 정보
    val additionalInfo: String,
    val basebandCn0DbHz: Double?,        // 기저대역 C/N0
    val fullInterSignalBiasNanos: Double?, // 신호 간 편향
    val fullInterSignalBiasUncertaintyNanos: Double?, // 신호 간 편향 불확실성
    val satelliteInterSignalBiasNanos: Double?, // 위성 신호 간 편향
    val satelliteInterSignalBiasUncertaintyNanos: Double?, // 위성 신호 간 편향 불확실성
    val codeType: String?                // 코드 타입 (API 29+)
)

/**
 * ✅ GNSS 위성 상태 데이터 (GnssStatus의 모든 정보)
 */
data class GnssSatelliteStatus(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,

    // 위성 기본 정보
    val satelliteIndex: Int,             // 위성 인덱스
    val constellationType: Int,          // 위성군 타입
    val svid: Int,                       // 위성 ID

    // 신호 품질
    val cn0DbHz: Float,                  // 신호 강도
    val hasCarrierFrequency: Boolean,    // 반송파 주파수 유무
    val carrierFrequencyHz: Float?,      // 반송파 주파수

    // 위성 위치 (하늘에서의 위치)
    val azimuthDegrees: Float,           // 방위각 (도)
    val elevationDegrees: Float,         // 고도각 (도)

    // 위성 데이터 상태
    val hasAlmanacData: Boolean,         // 알마낙 데이터 보유 여부
    val hasEphemerisData: Boolean,       // 에페메리스 데이터 보유 여부
    val usedInFix: Boolean,              // 위치 계산에 사용 여부 ⭐ 가장 중요!

    // 추가 정보
    val totalSatelliteCount: Int,        // 전체 위성 수
    val usedSatelliteCount: Int          // 사용된 위성 수
)

/**
 * ✅ GNSS 내비게이션 메시지 데이터 (GnssNavigationMessage의 모든 정보)
 */
data class GnssNavigationData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,

    // 메시지 기본 정보
    val messageId: Int,                  // 메시지 ID
    val submessageId: Int,               // 서브메시지 ID
    val type: Int,                       // 메시지 타입
    val status: Int,                     // 메시지 상태
    val svid: Int,                       // 위성 ID

    // 메시지 데이터
    val data: ByteArray,                 // 실제 내비게이션 데이터
    val dataLength: Int,                 // 데이터 길이

    // 추가 정보
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

/**
 * ✅ GNSS 안테나 정보 데이터 (GnssAntennaInfo, API 30+)
 */
@RequiresApi(Build.VERSION_CODES.R)
data class GnssAntennaData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,

    // 안테나 기본 정보
    val carrierFrequencyMHz: Double,     // 반송파 주파수 (MHz)

    // 위상 중심 오프셋 (Phase Center Offset)
    val phaseCenterOffsetX: Double,      // X축 오프셋 (mm)
    val phaseCenterOffsetY: Double,      // Y축 오프셋 (mm)
    val phaseCenterOffsetZ: Double,      // Z축 오프셋 (mm)
    val phaseCenterOffsetUncertaintyX: Double, // X축 불확실성
    val phaseCenterOffsetUncertaintyY: Double, // Y축 불확실성
    val phaseCenterOffsetUncertaintyZ: Double, // Z축 불확실성

    // 위상 중심 변동 보정 (Phase Center Variation)
    val phaseCenterVariationCorrections: DoubleArray?,
    val phaseCenterVariationUncertainties: DoubleArray?,

    // 신호 이득 보정 (Signal Gain Corrections)
    val signalGainCorrections: DoubleArray?,
    val signalGainUncertainties: DoubleArray?,

    // 추가 정보
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

/**
 * ✅ GNSS 클럭 정보 (GnssClock의 모든 정보)
 */
data class GnssClockData(
    val gpsTimestamp: Long,
    val localTimestamp: Long,
    val monoTimestamp: Long,

    // 클럭 기본 정보
    val timeNanos: Long,                 // GPS 시간 (나노초)
    val timeUncertaintyNanos: Double?,   // 시간 불확실성
    val leapSecond: Int?,                // 윤초

    // 클럭 편향 정보
    val biasNanos: Double?,              // 클럭 편향 (나노초)
    val biasUncertaintyNanos: Double?,   // 클럭 편향 불확실성
    val driftNanosPerSecond: Double?,    // 클럭 드리프트 (나노초/초)
    val driftUncertaintyNanosPerSecond: Double?, // 클럭 드리프트 불확실성

    // 하드웨어 클럭 불연속성
    val hardwareClockDiscontinuityCount: Int?, // 하드웨어 클럭 불연속 카운트

    // 전체 편향 정보
    val fullBiasNanos: Long?,            // 전체 편향 (나노초)

    // 추가 정보
    val additionalInfo: String
)

/**
 * ✅ 통합 GNSS 세션 요약 정보
 */
data class GnssSessionSummary(
    val sessionStartTime: Long,
    val sessionDuration: Long,

    // 위성 통계
    val totalSatellitesObserved: Int,    // 관측된 총 위성 수
    val averageUsedSatellites: Float,    // 평균 사용 위성 수
    val maxCn0DbHz: Float,               // 최대 신호 강도
    val avgCn0DbHz: Float,               // 평균 신호 강도
    val minCn0DbHz: Float,               // 최소 신호 강도

    // 위성군별 통계
    val gpsCount: Int,                   // GPS 위성 수
    val glonassCount: Int,               // GLONASS 위성 수
    val galileoCount: Int,               // Galileo 위성 수
    val beidouCount: Int,                // BeiDou 위성 수
    val qzssCount: Int,                  // QZSS 위성 수

    // 신호 품질 통계
    val multipathDetectedCount: Int,     // 멀티패스 감지 수
    val totalMeasurements: Int,          // 총 측정 수
    val validFixCount: Int,              // 유효한 위치 계산 수

    // 첫 번째 위치 고정 시간 (TTFF)
    val timeToFirstFixMs: Long?          // Time To First Fix (밀리초)
)
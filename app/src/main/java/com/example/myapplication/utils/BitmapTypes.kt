// app/src/main/java/com/example/myapplication/utils/BitmapTypes.kt
package com.example.myapplication.utils

enum class BitmapPurpose {
    UI_DISPLAY,       // UI 표시용 (안전한 복사본)
    VIDEO_ENCODING,   // 비디오 인코딩용 (원본 직접 사용)
    ML_INFERENCE,     // 딥러닝 추론용 (재사용 가능)
    IMAGE_PROCESSING, // 이미지 처리용 (임시 사용)
    CAMERA_PREVIEW    // 카메라 프리뷰용 (고속 처리)
}

enum class FpsDropCause {
    POOL_EXHAUSTION,        // 풀 고갈
    MEMORY_PRESSURE,        // 메모리 압박
    UI_THREAD_OVERLOAD,     // UI 스레드 과부하
    STALE_REFERENCES,       // 오래된 참조
    PROCESSING_BOTTLENECK,  // 처리 병목
    UNKNOWN                 // 원인 불명
}

enum class CriticalityLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

enum class RecoveryPriority {
    VIDEO_QUALITY_FIRST,     // 비디오 녹화 중
    UI_RESPONSIVENESS_FIRST, // 사용자 조작 중  
    INFERENCE_ACCURACY_FIRST,// 중요한 객체 탐지 중
    BALANCED_PERFORMANCE     // 일반 상황
}

data class FpsDropDiagnosis(
    val primaryCause: FpsDropCause,
    val severity: CriticalityLevel,
    val confidence: Float,
    val recommendedAction: String = "",
    val context: String = ""
)

data class PerformanceMetric(
    val timestamp: Long,
    val processingTime: Long,
    val fps: Double,
    val poolAvailable: Int,
    val memoryPressure: Float,
    val activeReferences: Int = 0
)

data class PerformanceTrend(
    val isDeterioration: Boolean,
    val predictedIssue: String,
    val confidence: Float,
    val timeToIssue: Long = 0L
)
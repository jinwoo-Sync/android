package com.example.myapplication.utils

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import java.io.File
import java.text.DecimalFormat
import kotlin.math.roundToInt

class ResourceMonitor private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ResourceMonitor"
        private val MB = 1024 * 1024
        private val formatter = DecimalFormat("#.##")

        @Volatile
        private var INSTANCE: ResourceMonitor? = null

        fun getInstance(context: Context): ResourceMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResourceMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * 🎯 앱 전용 메모리 정보 (가장 중요!) - 순환 호출 해결
     */
    fun getAppMemoryInfo(): AppMemoryInfo {
        val runtime = Runtime.getRuntime()

        // 앱 힙 메모리 (GC 관리 영역)
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val availableHeap = maxHeap - usedHeap
        val heapUsagePercent = (usedHeap * 100.0 / maxHeap)

        // 앱 프로세스 메모리 상세 (Native + Dalvik)
        val debugMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemoryInfo)

        // 메모리 압박 상태 계산 (내부에서 직접 계산 - 순환 호출 방지)
        val isLowMemory = heapUsagePercent > 85.0 || (availableHeap / MB.toDouble()) < 20.0
        val memoryPressureLevel = when {
            heapUsagePercent > 90.0 -> MemoryPressureLevel.CRITICAL
            heapUsagePercent > 80.0 -> MemoryPressureLevel.HIGH
            heapUsagePercent > 65.0 -> MemoryPressureLevel.MEDIUM
            else -> MemoryPressureLevel.LOW
        }

        return AppMemoryInfo(
            // 힙 메모리 (Java/Kotlin 객체)
            maxHeapMB = maxHeap / MB.toDouble(),
            totalHeapMB = totalHeap / MB.toDouble(),
            usedHeapMB = usedHeap / MB.toDouble(),
            freeHeapMB = freeHeap / MB.toDouble(),
            availableHeapMB = availableHeap / MB.toDouble(),
            heapUsagePercent = heapUsagePercent,

            // 프로세스 메모리 (OS 레벨)
            dalvikHeapMB = debugMemoryInfo.dalvikPrivateDirty / 1024.0,    // Java/Kotlin
            nativeHeapMB = debugMemoryInfo.nativePrivateDirty / 1024.0,   // C/C++ (NDK, 비트맵 등)
            otherMemoryMB = debugMemoryInfo.otherPrivateDirty / 1024.0,   // 기타
            totalPrivateMB = debugMemoryInfo.totalPrivateDirty / 1024.0,  // 앱 전용 총합
            totalPssMB = debugMemoryInfo.totalPss / 1024.0,               // 공유 메모리 포함

            // 메모리 압박 상태 (내부에서 계산된 값 사용)
            isLowMemory = isLowMemory,
            memoryPressureLevel = memoryPressureLevel
        )
    }

    /**
     * 🌐 시스템 전체 메모리 정보 (참고용)
     */
    fun getSystemMemoryInfo(): SystemMemoryInfo {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return SystemMemoryInfo(
            totalSystemMB = memoryInfo.totalMem / MB.toDouble(),
            availableSystemMB = memoryInfo.availMem / MB.toDouble(),
            usedSystemMB = (memoryInfo.totalMem - memoryInfo.availMem) / MB.toDouble(),
            systemMemoryLow = memoryInfo.lowMemory,
            systemThresholdMB = memoryInfo.threshold / MB.toDouble(),
            systemMemoryPressure = ((memoryInfo.totalMem - memoryInfo.availMem) * 100.0 / memoryInfo.totalMem)
        )
    }

    /**
     * 🎯 앱 메모리 위험도 분석 - 순환 호출 방지를 위해 별도 계산
     */
    fun isAppMemoryLow(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val heapUsagePercent = (usedHeap * 100.0 / maxHeap)
        val availableHeapMB = (maxHeap - usedHeap) / MB.toDouble()

        return heapUsagePercent > 85.0 || availableHeapMB < 20.0
    }

    fun getAppMemoryPressureLevel(): MemoryPressureLevel {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val heapUsagePercent = (usedHeap * 100.0 / maxHeap)

        return when {
            heapUsagePercent > 90.0 -> MemoryPressureLevel.CRITICAL
            heapUsagePercent > 80.0 -> MemoryPressureLevel.HIGH
            heapUsagePercent > 65.0 -> MemoryPressureLevel.MEDIUM
            else -> MemoryPressureLevel.LOW
        }
    }

    /**
     * 📊 앱 중심 리소스 상태 로깅
     */
    fun logAppResourceStatus(tag: String = "ResourceMonitor", context: String = "") {
        try {
            val appMemory = getAppMemoryInfo()
            val systemMemory = getSystemMemoryInfo()
            val cpu = getCpuInfo()
            val thread = getThreadInfo()

            val report = buildString {
                appendLine("📱 ==================== 앱 리소스 모니터링 ====================")
                appendLine("🎯 컨텍스트: $context")
                appendLine("⏰ 시간: ${System.currentTimeMillis()}")
                appendLine()

                appendLine("🧠 앱 힙 메모리 (GC 관리 영역):")
                appendLine("   최대 할당량: ${formatter.format(appMemory.maxHeapMB)} MB")
                appendLine("   현재 사용량: ${formatter.format(appMemory.usedHeapMB)} MB (${formatter.format(appMemory.heapUsagePercent)}%)")
                appendLine("   사용 가능: ${formatter.format(appMemory.availableHeapMB)} MB")
                appendLine("   여유 공간: ${formatter.format(appMemory.freeHeapMB)} MB")

                val pressureIcon = when(appMemory.memoryPressureLevel) {
                    MemoryPressureLevel.CRITICAL -> "🔴"
                    MemoryPressureLevel.HIGH -> "🟡"
                    MemoryPressureLevel.MEDIUM -> "🟠"
                    MemoryPressureLevel.LOW -> "🟢"
                }
                appendLine("   압박 수준: $pressureIcon ${appMemory.memoryPressureLevel}")
                appendLine()

                appendLine("🔧 앱 프로세스 메모리 (OS 레벨):")
                appendLine("   Dalvik(Java): ${formatter.format(appMemory.dalvikHeapMB)} MB")
                appendLine("   Native(C++): ${formatter.format(appMemory.nativeHeapMB)} MB")
                appendLine("   기타: ${formatter.format(appMemory.otherMemoryMB)} MB")
                appendLine("   앱 전용 총합: ${formatter.format(appMemory.totalPrivateMB)} MB")
                appendLine("   공유 포함 총합: ${formatter.format(appMemory.totalPssMB)} MB")
                appendLine()

                appendLine("🌐 시스템 전체 메모리 (참고용):")
                appendLine("   전체 RAM: ${formatter.format(systemMemory.totalSystemMB)} MB")
                appendLine("   시스템 사용가능: ${formatter.format(systemMemory.availableSystemMB)} MB")
                appendLine("   시스템 사용률: ${formatter.format(systemMemory.systemMemoryPressure)}%")
                appendLine("   시스템 메모리 부족: ${if (systemMemory.systemMemoryLow) "⚠️ 예" else "✅ 아니오"}")
                appendLine()

                appendLine("⚡ CPU & 스레드:")
                appendLine("   CPU 사용률: ${formatter.format(cpu.usagePercent)}% (${cpu.coreCount}코어)")
                appendLine("   앱 활성 스레드: ${thread.activeThreadCount}개")
                appendLine("   현재 스레드: ${thread.currentThreadName}")
                appendLine()

                // 🚨 경고 및 권장사항
                val warnings = mutableListOf<String>()
                if (appMemory.heapUsagePercent > 85) {
                    warnings.add("앱 힙 메모리 사용률 위험: ${formatter.format(appMemory.heapUsagePercent)}%")
                }
                if (appMemory.availableHeapMB < 20) {
                    warnings.add("앱 힙 여유 공간 부족: ${formatter.format(appMemory.availableHeapMB)} MB")
                }
                if (appMemory.nativeHeapMB > 100) {
                    warnings.add("Native 메모리 과다 사용: ${formatter.format(appMemory.nativeHeapMB)} MB (비트맵 확인 필요)")
                }
                if (systemMemory.systemMemoryLow) {
                    warnings.add("시스템 전체 메모리 부족 상태")
                }

                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ 메모리 경고:")
                    warnings.forEach { appendLine("   🚨 $it") }
                    appendLine()

                    // 권장사항
                    appendLine("💡 권장사항:")
                    if (appMemory.heapUsagePercent > 85) {
                        appendLine("   • 불필요한 객체 참조 해제")
                        appendLine("   • System.gc() 호출 고려")
                    }
                    if (appMemory.nativeHeapMB > 100) {
                        appendLine("   • 비트맵 재활용 확인")
                        appendLine("   • 순환 비트맵 풀 효율성 점검")
                    }
                    appendLine()
                }

                appendLine("📊 ========================================================")
            }

            Log.i(tag, report)
        } catch (e: Exception) {
            Log.e(TAG, "리소스 모니터링 중 오류 발생: ${e.message}", e)
        }
    }

    /**
     * 🎯 앱 메모리 경고 확인 (앱 중심)
     */
    fun checkAppMemoryWarnings(): List<String> {
        val warnings = mutableListOf<String>()

        try {
            val appMemory = getAppMemoryInfo()
            val systemMemory = getSystemMemoryInfo()

            // 앱 힙 메모리 경고
            if (appMemory.heapUsagePercent > 90) {
                warnings.add("🔴 앱 힙 메모리 위험: ${formatter.format(appMemory.heapUsagePercent)}%")
            } else if (appMemory.heapUsagePercent > 80) {
                warnings.add("🟡 앱 힙 메모리 주의: ${formatter.format(appMemory.heapUsagePercent)}%")
            }

            if (appMemory.availableHeapMB < 10) {
                warnings.add("🔴 앱 힙 여유공간 위험: ${formatter.format(appMemory.availableHeapMB)} MB")
            } else if (appMemory.availableHeapMB < 30) {
                warnings.add("🟡 앱 힙 여유공간 주의: ${formatter.format(appMemory.availableHeapMB)} MB")
            }

            // Native 메모리 경고 (비트맵 등)
            if (appMemory.nativeHeapMB > 200) {
                warnings.add("🔴 Native 메모리 과다: ${formatter.format(appMemory.nativeHeapMB)} MB")
            } else if (appMemory.nativeHeapMB > 100) {
                warnings.add("🟡 Native 메모리 주의: ${formatter.format(appMemory.nativeHeapMB)} MB")
            }

            // 시스템 메모리 참고 경고
            if (systemMemory.systemMemoryLow) {
                warnings.add("⚠️ 시스템 전체 메모리 부족 (앱 종료 위험)")
            }
        } catch (e: Exception) {
            warnings.add("❌ 메모리 상태 확인 중 오류: ${e.message}")
            Log.e(TAG, "메모리 경고 확인 중 오류: ${e.message}", e)
        }

        return warnings
    }

    /**
     * 🎯 비트맵 메모리 정보 (Native 영역에 할당됨)
     */
    fun getBitmapMemoryUsage(bitmap: Bitmap?): BitmapMemoryInfo {
        return try {
            if (bitmap != null && !bitmap.isRecycled) {
                val bytes = bitmap.allocationByteCount
                BitmapMemoryInfo(
                    isValid = true,
                    sizeMB = bytes / MB.toDouble(),
                    width = bitmap.width,
                    height = bitmap.height,
                    config = bitmap.config?.name ?: "Unknown",
                    isInNativeHeap = true  // Android O+ 비트맵은 Native 힙에 저장
                )
            } else {
                BitmapMemoryInfo(false, 0.0, 0, 0, "Invalid/Recycled", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "비트맵 메모리 정보 수집 실패: ${e.message}", e)
            BitmapMemoryInfo(false, 0.0, 0, 0, "Error", false)
        }
    }

    // 나머지 함수들은 기존과 동일...
    fun getCpuInfo(): CpuInfo {
        return try {
            val stat = File("/proc/stat").readText()
            val cpuLine = stat.lines().first { it.startsWith("cpu ") }
            val values = cpuLine.split("\\s+".toRegex()).drop(1).map { it.toLong() }

            val idle = values[3]
            val total = values.sum()
            val usage = ((total - idle) * 100.0 / total)

            CpuInfo(
                usagePercent = usage,
                coreCount = Runtime.getRuntime().availableProcessors()
            )
        } catch (e: Exception) {
            Log.w(TAG, "CPU 정보 수집 실패: ${e.message}")
            CpuInfo(0.0, Runtime.getRuntime().availableProcessors())
        }
    }

    fun getThreadInfo(): ThreadInfo {
        val threadGroup = Thread.currentThread().threadGroup
        val activeThreads = threadGroup?.activeCount() ?: 0

        return ThreadInfo(
            activeThreadCount = activeThreads,
            currentThreadName = Thread.currentThread().name,
            mainThreadName = "main"
        )
    }

    // 기존 호환성을 위한 함수들...
    @Deprecated("Use getAppMemoryInfo() and getSystemMemoryInfo() instead")
    fun getDetailedMemoryInfo(): MemoryInfo {
        val appMem = getAppMemoryInfo()
        val sysMem = getSystemMemoryInfo()

        return MemoryInfo(
            maxHeapMB = appMem.maxHeapMB,
            totalHeapMB = appMem.totalHeapMB,
            usedHeapMB = appMem.usedHeapMB,
            freeHeapMB = appMem.freeHeapMB,
            availableHeapMB = appMem.availableHeapMB,
            heapUsagePercent = appMem.heapUsagePercent,
            totalSystemMB = sysMem.totalSystemMB,
            availableSystemMB = sysMem.availableSystemMB,
            systemMemoryLow = sysMem.systemMemoryLow,
            systemThresholdMB = sysMem.systemThresholdMB,
            dalvikHeapMB = appMem.dalvikHeapMB,
            nativeHeapMB = appMem.nativeHeapMB,
            otherMemoryMB = appMem.otherMemoryMB,
            totalPrivateMB = appMem.totalPrivateMB,
            totalPssMB = appMem.totalPssMB,
            totalSharedMB = 0.0
        )
    }

    @Deprecated("Use logAppResourceStatus() instead")
    fun logResourceStatus(tag: String = "ResourceMonitor", context: String = "") {
        logAppResourceStatus(tag, context)
    }

    @Deprecated("Use checkAppMemoryWarnings() instead")
    fun checkMemoryWarnings(): List<String> {
        return checkAppMemoryWarnings()
    }
}

// 데이터 클래스들은 기존과 동일...
data class AppMemoryInfo(
    val maxHeapMB: Double,
    val totalHeapMB: Double,
    val usedHeapMB: Double,
    val freeHeapMB: Double,
    val availableHeapMB: Double,
    val heapUsagePercent: Double,
    val dalvikHeapMB: Double,
    val nativeHeapMB: Double,
    val otherMemoryMB: Double,
    val totalPrivateMB: Double,
    val totalPssMB: Double,
    val isLowMemory: Boolean,
    val memoryPressureLevel: MemoryPressureLevel
)

data class SystemMemoryInfo(
    val totalSystemMB: Double,
    val availableSystemMB: Double,
    val usedSystemMB: Double,
    val systemMemoryLow: Boolean,
    val systemThresholdMB: Double,
    val systemMemoryPressure: Double
)

enum class MemoryPressureLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class BitmapMemoryInfo(
    val isValid: Boolean,
    val sizeMB: Double,
    val width: Int,
    val height: Int,
    val config: String,
    val isInNativeHeap: Boolean
)

data class MemoryInfo(
    val maxHeapMB: Double,
    val totalHeapMB: Double,
    val usedHeapMB: Double,
    val freeHeapMB: Double,
    val availableHeapMB: Double,
    val heapUsagePercent: Double,
    val totalSystemMB: Double,
    val availableSystemMB: Double,
    val systemMemoryLow: Boolean,
    val systemThresholdMB: Double,
    val dalvikHeapMB: Double,
    val nativeHeapMB: Double,
    val otherMemoryMB: Double,
    val totalPrivateMB: Double,
    val totalPssMB: Double,
    val totalSharedMB: Double
)

data class CpuInfo(
    val usagePercent: Double,
    val coreCount: Int
)

data class ThreadInfo(
    val activeThreadCount: Int,
    val currentThreadName: String,
    val mainThreadName: String
)
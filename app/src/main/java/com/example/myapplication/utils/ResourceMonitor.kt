// app/src/main/java/com/example/myapplication/utils/ResourceMonitor.kt
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
     * ✅ 현재 메모리 사용량 상세 정보
     */
    fun getDetailedMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        // Debug 메모리 정보
        val debugMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemoryInfo)

        // 힙 메모리 정보
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val availableHeap = maxHeap - usedHeap

        return MemoryInfo(
            // 힙 메모리
            maxHeapMB = maxHeap / MB.toDouble(),
            totalHeapMB = totalHeap / MB.toDouble(),
            usedHeapMB = usedHeap / MB.toDouble(),
            freeHeapMB = freeHeap / MB.toDouble(),
            availableHeapMB = availableHeap / MB.toDouble(),
            heapUsagePercent = (usedHeap * 100.0 / maxHeap),

            // 시스템 메모리
            totalSystemMB = memoryInfo.totalMem / MB.toDouble(),
            availableSystemMB = memoryInfo.availMem / MB.toDouble(),
            systemMemoryLow = memoryInfo.lowMemory,
            systemThresholdMB = memoryInfo.threshold / MB.toDouble(),

            // 프로세스별 메모리
            dalvikHeapMB = debugMemoryInfo.dalvikPrivateDirty / 1024.0,
            nativeHeapMB = debugMemoryInfo.nativePrivateDirty / 1024.0,
            otherMemoryMB = debugMemoryInfo.otherPrivateDirty / 1024.0,
            totalPrivateMB = debugMemoryInfo.totalPrivateDirty / 1024.0,
            totalPssMB = debugMemoryInfo.totalPss / 1024.0,
            totalSharedMB = debugMemoryInfo.totalSharedDirty / 1024.0
        )
    }

    /**
     * ✅ CPU 사용량 정보
     */
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

    /**
     * ✅ 저장소 사용량 정보
     */
    fun getStorageInfo(): StorageInfo {
        val internalDir = context.filesDir
        val externalDir = context.getExternalFilesDir(null)

        return StorageInfo(
            internalFreeMB = (internalDir.freeSpace / MB.toDouble()),
            internalTotalMB = (internalDir.totalSpace / MB.toDouble()),
            externalFreeMB = externalDir?.let { it.freeSpace / MB.toDouble() } ?: 0.0,
            externalTotalMB = externalDir?.let { it.totalSpace / MB.toDouble() } ?: 0.0
        )
    }

    /**
     * ✅ 활성 스레드 정보
     */
    fun getThreadInfo(): ThreadInfo {
        val threadGroup = Thread.currentThread().threadGroup
        val activeThreads = threadGroup?.activeCount() ?: 0

        return ThreadInfo(
            activeThreadCount = activeThreads,
            currentThreadName = Thread.currentThread().name,
            mainThreadName = "main"
        )
    }

    /**
     * ✅ 전체 리소스 상태 로깅
     */
    fun logResourceStatus(tag: String = "ResourceMonitor", context: String = "") {
        val memory = getDetailedMemoryInfo()
        val cpu = getCpuInfo()
        val storage = getStorageInfo()
        val thread = getThreadInfo()

        val report = buildString {
            appendLine("📊 ==================== 리소스 모니터링 ====================")
            appendLine("🎯 컨텍스트: $context")
            appendLine("⏰ 시간: ${System.currentTimeMillis()}")
            appendLine()

            appendLine("🧠 힙 메모리:")
            appendLine("   최대: ${formatter.format(memory.maxHeapMB)} MB")
            appendLine("   사용중: ${formatter.format(memory.usedHeapMB)} MB (${formatter.format(memory.heapUsagePercent)}%)")
            appendLine("   사용가능: ${formatter.format(memory.availableHeapMB)} MB")
            appendLine("   여유: ${formatter.format(memory.freeHeapMB)} MB")
            appendLine()

            appendLine("💾 시스템 메모리:")
            appendLine("   전체: ${formatter.format(memory.totalSystemMB)} MB")
            appendLine("   사용가능: ${formatter.format(memory.availableSystemMB)} MB")
            appendLine("   메모리 부족 상태: ${if (memory.systemMemoryLow) "⚠️ 예" else "✅ 아니오"}")
            appendLine("   임계값: ${formatter.format(memory.systemThresholdMB)} MB")
            appendLine()

            appendLine("🔧 프로세스 메모리:")
            appendLine("   Dalvik Heap: ${formatter.format(memory.dalvikHeapMB)} MB")
            appendLine("   Native Heap: ${formatter.format(memory.nativeHeapMB)} MB")
            appendLine("   기타: ${formatter.format(memory.otherMemoryMB)} MB")
            appendLine("   총 Private: ${formatter.format(memory.totalPrivateMB)} MB")
            appendLine("   총 PSS: ${formatter.format(memory.totalPssMB)} MB")
            appendLine()

            appendLine("⚡ CPU:")
            appendLine("   사용률: ${formatter.format(cpu.usagePercent)}%")
            appendLine("   코어 수: ${cpu.coreCount}")
            appendLine()

            appendLine("💿 저장소:")
            appendLine("   내부 여유: ${formatter.format(storage.internalFreeMB)} MB / ${formatter.format(storage.internalTotalMB)} MB")
            if (storage.externalTotalMB > 0) {
                appendLine("   외부 여유: ${formatter.format(storage.externalFreeMB)} MB / ${formatter.format(storage.externalTotalMB)} MB")
            }
            appendLine()

            appendLine("🧵 스레드:")
            appendLine("   활성 스레드 수: ${thread.activeThreadCount}")
            appendLine("   현재 스레드: ${thread.currentThreadName}")
            appendLine()

            // ✅ 메모리 위험도 분석
            val memoryRisk = when {
                memory.heapUsagePercent > 90 -> "🔴 위험"
                memory.heapUsagePercent > 75 -> "🟡 주의"
                else -> "🟢 양호"
            }
            appendLine("⚠️ 메모리 위험도: $memoryRisk")

            if (memory.systemMemoryLow) {
                appendLine("🚨 시스템 메모리 부족 상태!")
            }

            appendLine("📊 ========================================================")
        }

        Log.i(tag, report)
    }

    /**
     * ✅ 비트맵 메모리 사용량 계산
     */
    fun getBitmapMemoryUsage(bitmap: Bitmap?): BitmapMemoryInfo {
        return if (bitmap != null && !bitmap.isRecycled) {
            val bytes = bitmap.allocationByteCount
            BitmapMemoryInfo(
                isValid = true,
                sizeMB = bytes / MB.toDouble(),
                width = bitmap.width,
                height = bitmap.height,
                config = bitmap.config?.name ?: "Unknown"
            )
        } else {
            BitmapMemoryInfo(false, 0.0, 0, 0, "Invalid/Recycled")
        }
    }

    /**
     * ✅ 메모리 경고 확인
     */
    fun checkMemoryWarnings(): List<String> {
        val warnings = mutableListOf<String>()
        val memory = getDetailedMemoryInfo()

        if (memory.heapUsagePercent > 85) {
            warnings.add("힙 메모리 사용률 높음: ${formatter.format(memory.heapUsagePercent)}%")
        }

        if (memory.availableHeapMB < 50) {
            warnings.add("사용 가능한 힙 메모리 부족: ${formatter.format(memory.availableHeapMB)} MB")
        }

        if (memory.systemMemoryLow) {
            warnings.add("시스템 메모리 부족 상태")
        }

        if (memory.availableSystemMB < 200) {
            warnings.add("시스템 메모리 부족: ${formatter.format(memory.availableSystemMB)} MB")
        }

        return warnings
    }
}

// 데이터 클래스들
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

data class StorageInfo(
    val internalFreeMB: Double,
    val internalTotalMB: Double,
    val externalFreeMB: Double,
    val externalTotalMB: Double
)

data class ThreadInfo(
    val activeThreadCount: Int,
    val currentThreadName: String,
    val mainThreadName: String
)

data class BitmapMemoryInfo(
    val isValid: Boolean,
    val sizeMB: Double,
    val width: Int,
    val height: Int,
    val config: String
)
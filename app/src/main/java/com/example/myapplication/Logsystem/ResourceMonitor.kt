package com.example.myapplication.Logsystem

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

// 메모리 정보 데이터 클래스
data class AppMemoryInfo(
    val usedHeapMB: Double,
    val maxHeapMB: Double,
    val heapUsagePercent: Double,
    val availableHeapMB: Double,
    val nativeHeapMB: Double,
    val memoryPressureLevel: String
)

data class SystemMemoryInfo(
    val totalMemoryMB: Double,
    val availableMemoryMB: Double,
    val usedMemoryMB: Double,
    val systemMemoryLow: Boolean
)

data class CpuInfo(
    val usagePercent: Double,
    val coreCount: Int
)

data class ThreadInfo(
    val activeThreadCount: Int,
    val currentThreadName: String
)

class ResourceMonitor private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ResourceMonitor"

        @Volatile
        private var INSTANCE: ResourceMonitor? = null

        fun getInstance(context: Context): ResourceMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResourceMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val runtime = Runtime.getRuntime()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    // 캐싱된 시스템 메모리 정보
    private val cachedSystemMemoryInfo = AtomicReference<SystemMemoryInfo?>(null)
    private val cachedCpuInfo = AtomicReference<CpuInfo?>(null)
    private var lastSystemMemoryUpdateTime = 0L
    private var lastCpuUpdateTime = 0L
    private val CACHE_DURATION_MS = 1000L // 1초 캐싱
    
    // 백그라운드 업데이트를 위한 CoroutineScope
    private val monitoringScope = CoroutineScope(Dispatchers.IO)
    
    init {
        // 백그라운드에서 주기적으로 시스템 정보 업데이트
        startBackgroundMonitoring()
    }
    
    private fun startBackgroundMonitoring() {
        monitoringScope.launch {
            while (true) {
                updateSystemMemoryCache()
                updateCpuCache()
                delay(CACHE_DURATION_MS)
            }
        }
    }
    
    private fun updateSystemMemoryCache() {
        try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val totalMemoryMB = memInfo.totalMem / (1024.0 * 1024.0)
            val availableMemoryMB = memInfo.availMem / (1024.0 * 1024.0)
            val usedMemoryMB = totalMemoryMB - availableMemoryMB
            
            cachedSystemMemoryInfo.set(SystemMemoryInfo(
                totalMemoryMB = totalMemoryMB,
                availableMemoryMB = availableMemoryMB,
                usedMemoryMB = usedMemoryMB,
                systemMemoryLow = memInfo.lowMemory
            ))
            lastSystemMemoryUpdateTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "시스템 메모리 캐싱 업데이트 실패: ${e.message}")
        }
    }
    
    private fun updateCpuCache() {
        try {
            val coreCount = Runtime.getRuntime().availableProcessors()
            val cpuUsage = getCpuUsageInternal()
            
            cachedCpuInfo.set(CpuInfo(
                usagePercent = cpuUsage,
                coreCount = coreCount
            ))
            lastCpuUpdateTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "CPU 정보 캐싱 업데이트 실패: ${e.message}")
        }
    }

    /**
     * 앱 메모리 정보 수집
     */
    fun getAppMemoryInfo(): AppMemoryInfo {
        val maxHeapMB = runtime.maxMemory() / (1024.0 * 1024.0)
        val totalHeapMB = runtime.totalMemory() / (1024.0 * 1024.0)
        val freeHeapMB = runtime.freeMemory() / (1024.0 * 1024.0)
        val usedHeapMB = totalHeapMB - freeHeapMB
        val availableHeapMB = maxHeapMB - usedHeapMB
        val heapUsagePercent = (usedHeapMB / maxHeapMB) * 100.0

        // Native 메모리 정보
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val nativeHeapMB = try {
            (memoryInfo.getTotalPss() - memoryInfo.dalvikPss) / 1024.0
        } catch (e: Exception) {
            0.0
        }

        // 메모리 압박 수준 판정
        val memoryPressureLevel = when {
            heapUsagePercent > 90 -> "CRITICAL"
            heapUsagePercent > 75 -> "HIGH"
            heapUsagePercent > 50 -> "MEDIUM"
            else -> "LOW"
        }

        return AppMemoryInfo(
            usedHeapMB = usedHeapMB,
            maxHeapMB = maxHeapMB,
            heapUsagePercent = heapUsagePercent,
            availableHeapMB = availableHeapMB,
            nativeHeapMB = nativeHeapMB,
            memoryPressureLevel = memoryPressureLevel
        )
    }

    /**
     * 시스템 메모리 정보 수집 - 캐싱된 버전
     */
    fun getSystemMemoryInfo(): SystemMemoryInfo {
        // 캐시가 있고 유효하면 캐시 반환
        val cached = cachedSystemMemoryInfo.get()
        if (cached != null && (System.currentTimeMillis() - lastSystemMemoryUpdateTime) < CACHE_DURATION_MS) {
            return cached
        }
        
        // 캐시가 없거나 만료된 경우 즉시 업데이트 후 반환
        updateSystemMemoryCache()
        return cachedSystemMemoryInfo.get() ?: SystemMemoryInfo(
            totalMemoryMB = 0.0,
            availableMemoryMB = 0.0,
            usedMemoryMB = 0.0,
            systemMemoryLow = false
        )
    }

    /**
     * CPU 정보 수집 - 캐싱된 버전
     */
    fun getCpuInfo(): CpuInfo {
        // 캐시가 있고 유효하면 캐시 반환
        val cached = cachedCpuInfo.get()
        if (cached != null && (System.currentTimeMillis() - lastCpuUpdateTime) < CACHE_DURATION_MS) {
            return cached
        }
        
        // 캐시가 없거나 만료된 경우 즉시 업데이트 후 반환
        updateCpuCache()
        return cachedCpuInfo.get() ?: CpuInfo(
            usagePercent = 0.0,
            coreCount = Runtime.getRuntime().availableProcessors()
        )
    }

    /**
     * CPU 사용률 계산 (근사치) - 내부 메서드
     */
    private fun getCpuUsageInternal(): Double {
        return try {
            val reader = BufferedReader(FileReader("/proc/stat"))
            val line = reader.readLine()
            reader.close()

            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 5) {
                val idle = parts[4].toDouble()
                val total = parts.drop(1).take(4).sumOf { it.toDouble() }
                val usage = ((total - idle) / total) * 100.0
                usage.coerceIn(0.0, 100.0)
            } else {
                0.0
            }
        } catch (e: Exception) {
            Log.w(TAG, "CPU 사용률 측정 실패: ${e.message}")
            0.0
        }
    }

    /**
     * 스레드 정보 수집
     */
    fun getThreadInfo(): ThreadInfo {
        val threadGroup = Thread.currentThread().threadGroup
        val activeCount = threadGroup?.activeCount() ?: 0
        val currentThreadName = Thread.currentThread().name

        return ThreadInfo(
            activeThreadCount = activeCount,
            currentThreadName = currentThreadName
        )
    }

    /**
     * 메모리 경고 체크
     */
    fun checkAppMemoryWarnings(): List<String> {
        val warnings = mutableListOf<String>()
        val appMemory = getAppMemoryInfo()
        val systemMemory = getSystemMemoryInfo()

        if (appMemory.heapUsagePercent > 85) {
            warnings.add("앱 힙 메모리 사용률 위험: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
        }

        if (appMemory.nativeHeapMB > 100) {
            warnings.add("Native 메모리 사용량 높음: ${String.format("%.1f", appMemory.nativeHeapMB)}MB")
        }

        if (systemMemory.systemMemoryLow) {
            warnings.add("시스템 메모리 부족 상태")
        }

        return warnings
    }

    /**
     * 완전한 리소스 상태 로깅
     */
    fun logAppResourceStatus(tag: String, context: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val appMemory = getAppMemoryInfo()
            val systemMemory = getSystemMemoryInfo()
            val cpuInfo = getCpuInfo()
            val threadInfo = getThreadInfo()

            Log.i(tag, "=== 완전한 리소스 상태: $context ===")
            Log.i(tag, "시간: $timestamp")

            // 앱 메모리 상태
            Log.i(tag, "=== 앱 메모리 ===")
            Log.i(tag, "힙 사용량: ${String.format("%.1f", appMemory.usedHeapMB)}MB / ${String.format("%.1f", appMemory.maxHeapMB)}MB")
            Log.i(tag, "힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
            Log.i(tag, "가용 힙: ${String.format("%.1f", appMemory.availableHeapMB)}MB")
            Log.i(tag, "Native 힙: ${String.format("%.1f", appMemory.nativeHeapMB)}MB")
            Log.i(tag, "메모리 압박: ${appMemory.memoryPressureLevel}")

            // 시스템 메모리 상태
            Log.i(tag, "=== 시스템 메모리 ===")
            Log.i(tag, "총 메모리: ${String.format("%.1f", systemMemory.totalMemoryMB)}MB")
            Log.i(tag, "사용 가능: ${String.format("%.1f", systemMemory.availableMemoryMB)}MB")
            Log.i(tag, "사용 중: ${String.format("%.1f", systemMemory.usedMemoryMB)}MB")
            Log.i(tag, "시스템 메모리 부족: ${systemMemory.systemMemoryLow}")

            // CPU 상태
            Log.i(tag, "=== CPU ===")
            Log.i(tag, "사용률: ${String.format("%.1f", cpuInfo.usagePercent)}%")
            Log.i(tag, "코어 수: ${cpuInfo.coreCount}개")

            // 스레드 상태
            Log.i(tag, "=== 스레드 ===")
            Log.i(tag, "활성 스레드: ${threadInfo.activeThreadCount}개")
            Log.i(tag, "현재 스레드: ${threadInfo.currentThreadName}")

            // 경고 사항
            val warnings = checkAppMemoryWarnings()
            if (warnings.isNotEmpty()) {
                Log.w(tag, "=== 메모리 경고 ===")
                warnings.forEach { warning ->
                    Log.w(tag, "경고: $warning")
                }
            }

            Log.i(tag, "=== 리소스 상태 완료 ===")

        } catch (e: Exception) {
            Log.e(tag, "리소스 상태 로깅 실패: ${e.message}", e)
        }
    }
}
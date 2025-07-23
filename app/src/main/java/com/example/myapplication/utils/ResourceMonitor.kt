package com.example.myapplication.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.StatFs
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
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // 온도 센서 관련
    private var temperatureSensor: Sensor? = null
    private var currentTemperature: Float = -1f
    private val temperatureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                currentTemperature = it.values[0]
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        // 온도 센서 초기화 (안전한 방식)
        try {
            temperatureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
            temperatureSensor?.let {
                sensorManager.registerListener(temperatureListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            Log.w(TAG, "온도 센서 초기화 실패: ${e.message}")
        }
    }

    /**
     * 🔥 크리티컬 성능 분석 정보 수집 (호환성 보장)
     */
    fun getCriticalPerformanceAnalysis(): CriticalAnalysisInfo {
        val appMemory = getAppMemoryInfo()
        val systemMemory = getSystemMemoryInfo()
        val cpu = getAdvancedCpuInfo()
        val thermal = getThermalInfo()
        val battery = getBatteryInfo()
        val storage = getStorageInfo()
        val gc = getGCInfo()
        val processes = getProcessInfo()
        val graphics = getGraphicsInfo()

        return CriticalAnalysisInfo(
            timestamp = System.currentTimeMillis(),
            monoTimestamp = System.nanoTime(),
            appMemory = appMemory,
            systemMemory = systemMemory,
            cpu = cpu,
            thermal = thermal,
            battery = battery,
            storage = storage,
            gc = gc,
            processes = processes,
            graphics = graphics
        )
    }

    /**
     * 🌡️ 안전한 온도 정보 수집
     */
    private fun getThermalInfo(): ThermalInfo {
        return try {
            val cpuTemp = getCpuTemperature()
            val batteryTemp = getBatteryTemperature()
            val sensorTemp = currentTemperature

            ThermalInfo(
                cpuTemperature = cpuTemp,
                batteryTemperature = batteryTemp,
                ambientTemperature = if (sensorTemp > -1f) sensorTemp else -1f,
                thermalState = getThermalState(cpuTemp),
                thermalThrottling = isThermalThrottling()
            )
        } catch (e: Exception) {
            Log.e(TAG, "온도 정보 수집 실패: ${e.message}", e)
            ThermalInfo(-1f, -1f, -1f, "Unknown", false)
        }
    }

    private fun getCpuTemperature(): Float {
        return try {
            val tempFiles = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )

            for (path in tempFiles) {
                try {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        val tempStr = file.readText().trim()
                        val temp = tempStr.toFloatOrNull()
                        if (temp != null && temp > 0) {
                            return if (temp > 1000) temp / 1000f else temp
                        }
                    }
                } catch (e: Exception) {
                    // 다음 파일 시도
                    continue
                }
            }
            -1f
        } catch (e: Exception) {
            Log.w(TAG, "CPU 온도 읽기 실패: ${e.message}")
            -1f
        }
    }

    private fun getBatteryTemperature(): Float {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) temp / 10f else -1f
        } catch (e: Exception) {
            Log.w(TAG, "배터리 온도 읽기 실패: ${e.message}")
            -1f
        }
    }

    private fun getThermalState(cpuTemp: Float): String {
        return try {
            when {
                cpuTemp > 85f -> "CRITICAL"
                cpuTemp > 75f -> "SEVERE"
                cpuTemp > 65f -> "MODERATE"
                cpuTemp > 50f -> "LIGHT"
                cpuTemp > 0f -> "NONE"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    private fun isThermalThrottling(): Boolean {
        return try {
            val cpuMaxFreq = getCurrentCpuMaxFreq()
            val cpuDesignFreq = getDesignCpuMaxFreq()
            if (cpuMaxFreq > 0 && cpuDesignFreq > 0) {
                cpuMaxFreq < cpuDesignFreq * 0.8f
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 🔋 안전한 배터리 정보
     */
    private fun getBatteryInfo(): BatteryInfo {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            BatteryInfo(
                level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
                voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
                temperature = getBatteryTemperature(),
                status = getBatteryStatus(batteryIntent),
                health = getBatteryHealth(batteryIntent),
                isCharging = isCharging(batteryIntent),
                powerSaveModeEnabled = isPowerSaveModeEnabled()
            )
        } catch (e: Exception) {
            Log.e(TAG, "배터리 정보 수집 실패: ${e.message}", e)
            BatteryInfo(-1, -1, -1, -1f, "Unknown", "Unknown", false, false)
        }
    }

    private fun getBatteryStatus(intent: Intent?): String {
        return try {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    private fun getBatteryHealth(intent: Intent?): String {
        return try {
            val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
            when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    private fun isCharging(intent: Intent?): Boolean {
        return try {
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                    plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
                            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS)
        } catch (e: Exception) {
            false
        }
    }

    private fun isPowerSaveModeEnabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.isPowerSaveMode
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 🖥️ 안전한 고급 CPU 정보
     */
    private fun getAdvancedCpuInfo(): AdvancedCpuInfo {
        return try {
            AdvancedCpuInfo(
                coreCount = Runtime.getRuntime().availableProcessors(),
                currentFreqs = getCpuCurrentFrequencies(),
                maxFreqs = getCpuMaxFrequencies(),
                minFreqs = getCpuMinFrequencies(),
                governor = getCpuGovernor(),
                loadAverage = getLoadAverage(),
                usagePercent = getCpuUsagePercent(),
                isThrottling = isThermalThrottling()
            )
        } catch (e: Exception) {
            Log.e(TAG, "CPU 정보 수집 실패: ${e.message}", e)
            AdvancedCpuInfo(
                coreCount = Runtime.getRuntime().availableProcessors(),
                currentFreqs = emptyList(),
                maxFreqs = emptyList(),
                minFreqs = emptyList(),
                governor = "unknown",
                loadAverage = listOf(-1.0, -1.0, -1.0),
                usagePercent = -1.0,
                isThrottling = false
            )
        }
    }

    private fun getCpuCurrentFrequencies(): List<Long> {
        val freqs = mutableListOf<Long>()
        val coreCount = Runtime.getRuntime().availableProcessors()

        for (i in 0 until coreCount) {
            try {
                val freq = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                if (freq.exists() && freq.canRead()) {
                    val freqStr = freq.readText().trim()
                    val freqValue = freqStr.toLongOrNull()
                    freqs.add(freqValue ?: -1L)
                } else {
                    freqs.add(-1L)
                }
            } catch (e: Exception) {
                freqs.add(-1L)
            }
        }
        return freqs
    }

    private fun getCpuMaxFrequencies(): List<Long> {
        val freqs = mutableListOf<Long>()
        val coreCount = Runtime.getRuntime().availableProcessors()

        for (i in 0 until coreCount) {
            try {
                val freq = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")
                if (freq.exists() && freq.canRead()) {
                    val freqStr = freq.readText().trim()
                    val freqValue = freqStr.toLongOrNull()
                    freqs.add(freqValue ?: -1L)
                } else {
                    freqs.add(-1L)
                }
            } catch (e: Exception) {
                freqs.add(-1L)
            }
        }
        return freqs
    }

    private fun getCpuMinFrequencies(): List<Long> {
        val freqs = mutableListOf<Long>()
        val coreCount = Runtime.getRuntime().availableProcessors()

        for (i in 0 until coreCount) {
            try {
                val freq = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_min_freq")
                if (freq.exists() && freq.canRead()) {
                    val freqStr = freq.readText().trim()
                    val freqValue = freqStr.toLongOrNull()
                    freqs.add(freqValue ?: -1L)
                } else {
                    freqs.add(-1L)
                }
            } catch (e: Exception) {
                freqs.add(-1L)
            }
        }
        return freqs
    }

    private fun getCurrentCpuMaxFreq(): Long {
        return try {
            getCpuMaxFrequencies().filter { it > 0 }.maxOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getDesignCpuMaxFreq(): Long {
        return try {
            val freq = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
            if (freq.exists() && freq.canRead()) {
                val freqStr = freq.readText().trim()
                freqStr.toLongOrNull() ?: -1L
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getCpuGovernor(): String {
        return try {
            val governor = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            if (governor.exists() && governor.canRead()) {
                governor.readText().trim()
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            "error"
        }
    }

    private fun getLoadAverage(): List<Double> {
        return try {
            val loadavgFile = File("/proc/loadavg")
            if (loadavgFile.exists() && loadavgFile.canRead()) {
                val loadavg = loadavgFile.readText().trim()
                loadavg.split(" ").take(3).mapNotNull {
                    it.toDoubleOrNull()
                }
            } else {
                listOf(-1.0, -1.0, -1.0)
            }
        } catch (e: Exception) {
            listOf(-1.0, -1.0, -1.0)
        }
    }

    private fun getCpuUsagePercent(): Double {
        return try {
            val stat1 = readCpuStat()
            if (stat1.isEmpty()) return -1.0

            Thread.sleep(100)
            val stat2 = readCpuStat()
            if (stat2.isEmpty()) return -1.0

            val idle1 = stat1.getOrNull(3) ?: return -1.0
            val total1 = stat1.sum()
            val idle2 = stat2.getOrNull(3) ?: return -1.0
            val total2 = stat2.sum()

            val idleDiff = idle2 - idle1
            val totalDiff = total2 - total1

            if (totalDiff > 0) {
                ((totalDiff - idleDiff) * 100.0 / totalDiff)
            } else {
                -1.0
            }
        } catch (e: Exception) {
            Log.w(TAG, "CPU 사용률 계산 실패: ${e.message}")
            -1.0
        }
    }

    private fun readCpuStat(): List<Long> {
        return try {
            val statFile = File("/proc/stat")
            if (statFile.exists() && statFile.canRead()) {
                val stat = statFile.readText()
                val cpuLine = stat.lines().firstOrNull { it.startsWith("cpu ") }
                cpuLine?.split("\\s+".toRegex())?.drop(1)?.mapNotNull {
                    it.toLongOrNull()
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 💾 안전한 스토리지 정보
     */
    private fun getStorageInfo(): StorageInfo {
        return try {
            val internal = getInternalStorageInfo()
            val external = getExternalStorageInfo()

            StorageInfo(
                internalTotal = internal.first,
                internalFree = internal.second,
                externalTotal = external.first,
                externalFree = external.second,
                cacheSize = getCacheSize()
            )
        } catch (e: Exception) {
            Log.e(TAG, "스토리지 정보 수집 실패: ${e.message}", e)
            StorageInfo(-1L, -1L, -1L, -1L, -1L)
        }
    }

    private fun getInternalStorageInfo(): Pair<Long, Long> {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.totalBytes
            } else {
                @Suppress("DEPRECATION")
                stat.blockCount.toLong() * stat.blockSize.toLong()
            }
            val free = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.freeBytes
            } else {
                @Suppress("DEPRECATION")
                stat.freeBlocks.toLong() * stat.blockSize.toLong()
            }
            Pair(total, free)
        } catch (e: Exception) {
            Pair(-1L, -1L)
        }
    }

    private fun getExternalStorageInfo(): Pair<Long, Long> {
        return try {
            val extDir = android.os.Environment.getExternalStorageDirectory()
            if (extDir?.exists() == true) {
                val stat = StatFs(extDir.absolutePath)
                val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    stat.totalBytes
                } else {
                    @Suppress("DEPRECATION")
                    stat.blockCount.toLong() * stat.blockSize.toLong()
                }
                val free = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    stat.freeBytes
                } else {
                    @Suppress("DEPRECATION")
                    stat.freeBlocks.toLong() * stat.blockSize.toLong()
                }
                Pair(total, free)
            } else {
                Pair(-1L, -1L)
            }
        } catch (e: Exception) {
            Pair(-1L, -1L)
        }
    }

    private fun getCacheSize(): Long {
        return try {
            context.cacheDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 🗑️ 안전한 GC 정보 (API 호환성 문제 해결)
     */
    private fun getGCInfo(): GCInfo {
        return try {
            // Debug.getGlobal* 메서드들은 API 레벨에 따라 다르므로 안전하게 처리
            val gcCount = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Debug.getRuntimeStat("art.gc.gc-count").toIntOrNull() ?: -1
                } else {
                    -1
                }
            } catch (e: Exception) {
                -1
            }

            val gcTime = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Debug.getRuntimeStat("art.gc.gc-time").toLongOrNull() ?: -1L
                } else {
                    -1L
                }
            } catch (e: Exception) {
                -1L
            }

            GCInfo(
                gcCount = gcCount,
                gcTime = gcTime,
                gcFreedSize = -1L, // API 제한으로 인해 사용 불가
                gcFreedCount = -1L, // API 제한으로 인해 사용 불가
                lastGcReason = getLastGcReason()
            )
        } catch (e: Exception) {
            Log.e(TAG, "GC 정보 수집 실패: ${e.message}", e)
            GCInfo(-1, -1L, -1L, -1L, "unknown")
        }
    }

    private fun getLastGcReason(): String {
        return try {
            val heapUsage = getAppMemoryInfo().heapUsagePercent
            when {
                heapUsage > 90 -> "HEAP_FULL"
                heapUsage > 80 -> "MEMORY_PRESSURE"
                else -> "CONCURRENT"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 🔧 안전한 프로세스 정보
     */
    private fun getProcessInfo(): ProcessInfo {
        return try {
            val runningProcesses = try {
                activityManager.runningAppProcesses ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val myPid = android.os.Process.myPid()
            val myProcess = runningProcesses.find { it.pid == myPid }

            ProcessInfo(
                pid = myPid,
                uid = android.os.Process.myUid(),
                processName = myProcess?.processName ?: "unknown",
                importance = myProcess?.importance ?: -1,
                totalProcessCount = runningProcesses.size,
                threadCount = getThreadCount(),
                fdCount = getFdCount()
            )
        } catch (e: Exception) {
            Log.e(TAG, "프로세스 정보 수집 실패: ${e.message}", e)
            ProcessInfo(-1, -1, "unknown", -1, -1, -1, -1)
        }
    }

    private fun getThreadCount(): Int {
        return try {
            val threadGroup = Thread.currentThread().threadGroup
            threadGroup?.activeCount() ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun getFdCount(): Int {
        return try {
            val fdDir = File("/proc/${android.os.Process.myPid()}/fd")
            if (fdDir.exists() && fdDir.canRead()) {
                fdDir.listFiles()?.size ?: -1
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 🎮 안전한 그래픽스 정보
     */
    private fun getGraphicsInfo(): GraphicsInfo {
        return try {
            GraphicsInfo(
                renderer = getGpuRenderer(),
                vendor = getGpuVendor(),
                version = getGpuVersion(),
                extensions = getGpuExtensions(),
                surfaceFlinger = "limited_access" // 시스템 레벨 접근 제한
            )
        } catch (e: Exception) {
            Log.e(TAG, "그래픽스 정보 수집 실패: ${e.message}", e)
            GraphicsInfo("unknown", "unknown", "unknown", emptyList(), "unknown")
        }
    }

    private fun getGpuRenderer(): String {
        return try {
            javax.microedition.khronos.opengles.GL10::class.java.getDeclaredField("GL_RENDERER")
            "OpenGL_Available"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getGpuVendor(): String {
        return try {
            "Android_GPU" // 안전한 기본값
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getGpuVersion(): String {
        return try {
            "OpenGL_ES_2.0+" // 안전한 기본값
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getGpuExtensions(): List<String> {
        return try {
            // GPU 확장 기능은 OpenGL 컨텍스트가 필요하므로 기본값 반환
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 기존 메서드들 유지 (수정됨)
    fun getAppMemoryInfo(): AppMemoryInfo {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val availableHeap = maxHeap - usedHeap
        val heapUsagePercent = (usedHeap * 100.0 / maxHeap)

        val debugMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemoryInfo)

        val isLowMemory = heapUsagePercent > 85.0 || (availableHeap / MB.toDouble()) < 20.0
        val memoryPressureLevel = when {
            heapUsagePercent > 90.0 -> MemoryPressureLevel.CRITICAL
            heapUsagePercent > 80.0 -> MemoryPressureLevel.HIGH
            heapUsagePercent > 65.0 -> MemoryPressureLevel.MEDIUM
            else -> MemoryPressureLevel.LOW
        }

        return AppMemoryInfo(
            maxHeapMB = maxHeap / MB.toDouble(),
            totalHeapMB = totalHeap / MB.toDouble(),
            usedHeapMB = usedHeap / MB.toDouble(),
            freeHeapMB = freeHeap / MB.toDouble(),
            availableHeapMB = availableHeap / MB.toDouble(),
            heapUsagePercent = heapUsagePercent,
            dalvikHeapMB = debugMemoryInfo.dalvikPrivateDirty / 1024.0,
            nativeHeapMB = debugMemoryInfo.nativePrivateDirty / 1024.0,
            otherMemoryMB = debugMemoryInfo.otherPrivateDirty / 1024.0,
            totalPrivateMB = debugMemoryInfo.totalPrivateDirty / 1024.0,
            totalPssMB = debugMemoryInfo.totalPss / 1024.0,
            isLowMemory = isLowMemory,
            memoryPressureLevel = memoryPressureLevel
        )
    }

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

    fun getCpuInfo(): CpuInfo {
        return try {
            CpuInfo(
                usagePercent = getCpuUsagePercent(),
                coreCount = Runtime.getRuntime().availableProcessors()
            )
        } catch (e: Exception) {
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

    // 기존 호환성 메서드들...
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


    fun getBitmapMemoryUsage(bitmap: android.graphics.Bitmap?): BitmapMemoryInfo {
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
            }

            Log.i(tag, report)
        } catch (e: Exception) {
            Log.e(TAG, "리소스 모니터링 중 오류 발생: ${e.message}", e)
        }
    }

    fun checkAppMemoryWarnings(): List<String> {
        val warnings = mutableListOf<String>()

        try {
            val appMemory = getAppMemoryInfo()
            val systemMemory = getSystemMemoryInfo()

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

            if (appMemory.nativeHeapMB > 200) {
                warnings.add("🔴 Native 메모리 과다: ${formatter.format(appMemory.nativeHeapMB)} MB")
            } else if (appMemory.nativeHeapMB > 100) {
                warnings.add("🟡 Native 메모리 주의: ${formatter.format(appMemory.nativeHeapMB)} MB")
            }

            if (systemMemory.systemMemoryLow) {
                warnings.add("⚠️ 시스템 전체 메모리 부족 (앱 종료 위험)")
            }
        } catch (e: Exception) {
            warnings.add("❌ 메모리 상태 확인 중 오류: ${e.message}")
            Log.e(TAG, "메모리 경고 확인 중 오류: ${e.message}", e)
        }

        return warnings
    }

    // 정리
    fun cleanup() {
        try {
            temperatureSensor?.let {
                sensorManager.unregisterListener(temperatureListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "센서 정리 중 오류: ${e.message}")
        }
    }
}

// 데이터 클래스들은 동일하게 유지...
data class CriticalAnalysisInfo(
    val timestamp: Long,
    val monoTimestamp: Long,
    val appMemory: AppMemoryInfo,
    val systemMemory: SystemMemoryInfo,
    val cpu: AdvancedCpuInfo,
    val thermal: ThermalInfo,
    val battery: BatteryInfo,
    val storage: StorageInfo,
    val gc: GCInfo,
    val processes: ProcessInfo,
    val graphics: GraphicsInfo
)

data class ThermalInfo(
    val cpuTemperature: Float,
    val batteryTemperature: Float,
    val ambientTemperature: Float,
    val thermalState: String,
    val thermalThrottling: Boolean
)

data class BatteryInfo(
    val level: Int,
    val scale: Int,
    val voltage: Int,
    val temperature: Float,
    val status: String,
    val health: String,
    val isCharging: Boolean,
    val powerSaveModeEnabled: Boolean
)

data class AdvancedCpuInfo(
    val coreCount: Int,
    val currentFreqs: List<Long>,
    val maxFreqs: List<Long>,
    val minFreqs: List<Long>,
    val governor: String,
    val loadAverage: List<Double>,
    val usagePercent: Double,
    val isThrottling: Boolean
)

data class StorageInfo(
    val internalTotal: Long,
    val internalFree: Long,
    val externalTotal: Long,
    val externalFree: Long,
    val cacheSize: Long
)

data class GCInfo(
    val gcCount: Int,
    val gcTime: Long,
    val gcFreedSize: Long,
    val gcFreedCount: Long,
    val lastGcReason: String
)

data class ProcessInfo(
    val pid: Int,
    val uid: Int,
    val processName: String,
    val importance: Int,
    val totalProcessCount: Int,
    val threadCount: Int,
    val fdCount: Int
)

data class GraphicsInfo(
    val renderer: String,
    val vendor: String,
    val version: String,
    val extensions: List<String>,
    val surfaceFlinger: String
)

// 기존 데이터 클래스들 유지
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

data class CpuInfo(
    val usagePercent: Double,
    val coreCount: Int
)

data class ThreadInfo(
    val activeThreadCount: Int,
    val currentThreadName: String,
    val mainThreadName: String
)

// 기존 호환성 데이터 클래스
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

data class BitmapMemoryInfo(
    val isValid: Boolean,
    val sizeMB: Double,
    val width: Int,
    val height: Int,
    val config: String,
    val isInNativeHeap: Boolean
)
// app/src/main/java/com/example/myapplication/Logsystem/PerfettoTracer.kt
package com.example.myapplication.Logsystem

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Trace
import android.util.Log
import com.example.myapplication.Logsystem.GpuMemoryMonitor
import com.example.myapplication.utils.BitmapPoolManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class PerfettoTracer private constructor(
    private val context: Context,
    private val fileLogger: FileLogger
) {
    companion object {
        private const val TAG = "PerfettoTracer"

        @Volatile
        private var INSTANCE: PerfettoTracer? = null

        fun getInstance(context: Context): PerfettoTracer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PerfettoTracer(
                    context.applicationContext,
                    FileLogger.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }

    private val tracingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isTracing = false
    private var perfettoTraceFile: File? = null
    private var methodTraceFile: File? = null
    private var startTime: Long = 0
    private var methodTracingActive = false
    private var tempMethodTraceFileName: String? = null
    
    // 순환 버퍼로 메모리 사용량 최적화 (50,000개 제한)
    private val performanceEvents = mutableListOf<PerformanceEvent>()
    private val maxEventsInMemory = 50_000
    private var eventsWrittenToDisk = 0

    data class PerformanceEvent(
        val timestamp: Long,
        val name: String,
        val category: String,
        val phase: String, // "B" (Begin), "E" (End), "I" (Instant)
        val pid: Int,
        val tid: Long,
        val args: Map<String, Any> = emptyMap()
    )

    /**
     * Perfetto UI 호환 추적 시작
     */
    fun startPerfettoTrace(category: String = "AppPerformance"): String? {
        if (isTracing) {
            fileLogger.w(TAG, "이미 추적 중입니다")
            return perfettoTraceFile?.absolutePath
        }

        try {
            isTracing = true
            startTime = System.currentTimeMillis()
            performanceEvents.clear()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())

            // Documents/save/ 폴더에 모든 파일 저장
            val saveDir = getSaveDirectory()

            // Perfetto UI에서 읽을 수 있는 파일 생성 (전체 앱 실행 기간)
            perfettoTraceFile = File(saveDir, "full_app_perfetto_trace_${timestamp}.perfetto-trace")
            methodTraceFile = File(saveDir, "full_app_method_trace_${timestamp}.trace")

            fileLogger.i(TAG, "✅ Perfetto 추적 시작")
            fileLogger.i(TAG, "📁 Perfetto 파일: ${perfettoTraceFile!!.absolutePath}")
            fileLogger.i(TAG, "📁 메서드 추적 파일: ${methodTraceFile!!.absolutePath}")

            // 1. 시스템 추적 시작
            Trace.beginSection("$category-SystemTrace")
            addPerformanceEvent("System Trace Start", "system", "B")

            // 2. 메서드 추적 시작
            startMethodTracingForPerfetto()

            // 3. 실시간 성능 데이터 수집 시작
            startRealTimePerformanceCollection()

            return perfettoTraceFile!!.absolutePath

        } catch (e: Exception) {
            fileLogger.e(TAG, "Perfetto 추적 시작 실패: ${e.message}", e)
            isTracing = false
            return null
        }
    }

    /**
     * Perfetto 호환 메서드 추적 - Documents/save/에 저장
     */
    private fun startMethodTracingForPerfetto() {
        try {
            if (methodTraceFile == null) return

            // 중복 방지
            if (methodTracingActive) {
                fileLogger.w(TAG, "이미 메서드 트레이싱 활성 상태, 먼저 중지합니다")
                try { Debug.stopMethodTracing() } catch (_: Exception) {}
                methodTracingActive = false
            }

            // 임시 파일(내부 저장소). 주의: startMethodTracing 은 확장자 없이 "베이스 경로"를 줘야 합니다.
            tempMethodTraceFileName = "temp_method_trace_${System.currentTimeMillis()}"
            val tempBase = File(context.cacheDir, tempMethodTraceFileName!!).absolutePath

            fileLogger.i(TAG, "📊 메서드 추적 시작 (임시): $tempBase.trace")
            Debug.startMethodTracing(tempBase, 64 * 1024 * 1024) // 64MB 버퍼
            methodTracingActive = true

            addPerformanceEvent(
                "Method Tracing Start", "method", "B",
                mapOf("temp_base" to tempBase, "final_file" to methodTraceFile!!.absolutePath)
            )
        } catch (e: Exception) {
            fileLogger.e(TAG, "메서드 추적 시작 실패: ${e.message}", e)
            methodTracingActive = false
        }
    }

    /**
     * 실시간 성능 데이터 수집
     */
    private fun startRealTimePerformanceCollection() {
        tracingScope.launch {
            fileLogger.i(TAG, "🔄 실시간 성능 데이터 수집 시작")

            while (isTracing) {
                try {
                    collectPerformanceSnapshot()
                    delay(2000) // 2초마다 수집 (전체 실행 기간용 최적화)
                } catch (e: Exception) {
                    fileLogger.e(TAG, "성능 데이터 수집 오류: ${e.message}", e)
                    delay(2000)
                }
            }
        }
    }

    /**
     * 성능 스냅샷 수집
     */
    private fun collectPerformanceSnapshot() {
        try {
            val currentTime = System.currentTimeMillis()
            val relativeTime = currentTime - startTime

            // 메모리 정보 수집
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMemory = runtime.maxMemory() / (1024 * 1024)

            addPerformanceEvent(
                "Memory Usage",
                "memory",
                "I",
                mapOf(
                    "used_mb" to usedMemory,
                    "max_mb" to maxMemory,
                    "usage_percent" to ((usedMemory.toDouble() / maxMemory.toDouble()) * 100).toInt()
                )
            )

            // BitmapPool 상태 수집
            try {
                val bitmapPoolManager = BitmapPoolManager.getInstance(context)
                val poolHealth = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()

                addPerformanceEvent(
                    "BitmapPool Status",
                    "bitmap",
                    "I",
                    mapOf(
                        "health" to poolHealth.healthLevel.toString(),
                        "available" to poolHealth.availableSlots,
                        "total" to poolHealth.totalSlots,
                        "active" to poolHealth.totalReferences,
                        "stale" to poolHealth.staleSlots
                    )
                )
            } catch (e: Exception) {
                fileLogger.w(TAG, "BitmapPool 상태 수집 실패: ${e.message}")
            }

            // GPU 메모리 상태 수집
            try {
                val gpuMonitor = GpuMemoryMonitor.getInstance(context)
                val gpuInfo = gpuMonitor.getCurrentGpuInfo()

                if (gpuInfo != null) {
                    addPerformanceEvent(
                        "GPU Memory",
                        "gpu",
                        "I",
                        mapOf(
                            "graphics_mb" to gpuInfo.graphicsMemoryMB,
                            "gl_mb" to gpuInfo.glMemoryMB,
                            "texture_mb" to gpuInfo.textureMemoryMB,
                            "pressure" to gpuInfo.memoryPressureLevel
                        )
                    )
                }
            } catch (e: Exception) {
                fileLogger.w(TAG, "GPU 정보 수집 실패: ${e.message}")
            }

            // 스레드 정보 수집
            val threadCount = Thread.activeCount()
            val currentThread = Thread.currentThread()

            addPerformanceEvent(
                "Thread Info",
                "thread",
                "I",
                mapOf(
                    "active_threads" to threadCount,
                    "current_thread" to currentThread.name,
                    "thread_id" to currentThread.id
                )
            )

        } catch (e: Exception) {
            fileLogger.e(TAG, "성능 스냅샷 수집 실패: ${e.message}", e)
        }
    }

    /**
     * 성능 이벤트 추가 (순환 버퍼 사용)
     */
    private fun addPerformanceEvent(
        name: String,
        category: String,
        phase: String,
        args: Map<String, Any> = emptyMap()
    ) {
        val event = PerformanceEvent(
            timestamp = (System.currentTimeMillis() - startTime) * 1000, // 마이크로초
            name = name,
            category = category,
            phase = phase,
            pid = android.os.Process.myPid(),
            tid = Thread.currentThread().id,
            args = args
        )

        synchronized(performanceEvents) {
            performanceEvents.add(event)
            
            // 순환 버퍼: 최대 크기 초과 시 오래된 이벤트 제거
            if (performanceEvents.size > maxEventsInMemory) {
                // 가장 오래된 이벤트 25% 제거
                val removeCount = maxEventsInMemory / 4
                repeat(removeCount) {
                    if (performanceEvents.isNotEmpty()) {
                        performanceEvents.removeAt(0)
                        eventsWrittenToDisk++
                    }
                }
                fileLogger.i(TAG, "🔄 순환 버퍼: ${removeCount}개 이벤트 제거, 총 ${eventsWrittenToDisk}개 처리됨")
            }
        }
    }

    /**
     * 추적 중지 및 파일 최종화
     */
    fun stopPerfettoTrace(): String? {
        if (!isTracing) {
            fileLogger.w(TAG, "추적이 실행 중이 아닙니다")
            return null
        }

        try {
            addPerformanceEvent("System Trace End", "system", "E")
            addPerformanceEvent("Method Tracing End", "method", "E")

            // 메서드 트레이스 중지 + 플러시 대기
            if (methodTracingActive) {
                fileLogger.i(TAG, "📊 메서드 추적 중지...")
                try { Debug.stopMethodTracing() } catch (e: Exception) {
                    fileLogger.e(TAG, "stopMethodTracing 실패: ${e.message}", e)
                }
                methodTracingActive = false

                // 파일 쓰기 플러시 여유 (단말별 I/O 지연 흡수)
                Thread.sleep(1500)

                // 임시 파일 → 최종 경로로 복사 (0바이트/미생성 예외 처리 포함)
                val finalFile = methodTraceFile!!
                val tempName = tempMethodTraceFileName
                val tempTrace = if (tempName != null) File(context.cacheDir, "$tempName.trace") else null

                if (tempTrace != null && tempTrace.exists() && tempTrace.length() > 0L) {
                    tempTrace.inputStream().use { input ->
                        finalFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    fileLogger.i(TAG, "✅ 메서드 트레이스 복사 완료: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                    runCatching { tempTrace.delete() }
                } else {
                    // 혹시 다른 기본 위치에 생성된 경우까지 탐색
                    fileLogger.w(TAG, "임시 trace 직접 복사 실패")
                    // 빈 파일 생성
                    methodTraceFile?.writeText("Method trace failed to generate")
                }
            } else {
                fileLogger.w(TAG, "메서드 추적이 활성 상태가 아니었습니다")
            }

            // 시스템 섹션 종료
            Trace.endSection()
            isTracing = false

            // Perfetto(Chrome trace 포맷) 파일 생성
            generatePerfettoTraceFile()

            fileLogger.i(TAG, "✅ Perfetto 추적 완료")
            fileLogger.i(TAG, "📁 최종 Perfetto 파일: ${perfettoTraceFile?.absolutePath}")
            fileLogger.i(TAG, "📁 최종 메서드 파일: ${methodTraceFile?.absolutePath}")
            fileLogger.i(TAG, "📊 수집 이벤트: ${performanceEvents.size + eventsWrittenToDisk}개")

            return perfettoTraceFile?.absolutePath
        } catch (e: Exception) {
            fileLogger.e(TAG, "Perfetto 추적 중지 실패: ${e.message}", e)
            isTracing = false
            return null
        }
    }

    /**
     * Perfetto UI 호환 추적 파일 생성
     */
    private fun generatePerfettoTraceFile() {
        try {
            val traceFile = perfettoTraceFile ?: return

            if (!traceFile.exists()) {
                traceFile.parentFile?.mkdirs()
                traceFile.createNewFile()
            }

            // Chrome Trace Event 포맷으로 데이터 생성
            val traceData = generateTraceJsonData()

            FileOutputStream(traceFile).use { fos ->
                fos.write(traceData.toByteArray())
            }

            fileLogger.i(TAG, "📊 Perfetto 추적 파일 생성 완료: ${traceFile.length()} bytes")

        } catch (e: Exception) {
            fileLogger.e(TAG, "추적 파일 생성 실패: ${e.message}", e)
        }
    }

    /**
     * Chrome Trace Event 포맷으로 데이터 생성
     */
    private fun generateTraceJsonData(): String {
        return buildString {
            appendLine("{")
            appendLine("  \"traceEvents\": [")

            synchronized(performanceEvents) {
                performanceEvents.forEachIndexed { index, event ->
                    append("    ")
                    append("{")
                    append("\"name\": \"${event.name}\", ")
                    append("\"cat\": \"${event.category}\", ")
                    append("\"ph\": \"${event.phase}\", ")
                    append("\"ts\": ${event.timestamp}, ")
                    append("\"pid\": ${event.pid}, ")
                    append("\"tid\": ${event.tid}")

                    if (event.args.isNotEmpty()) {
                        append(", \"args\": {")
                        append(event.args.entries.joinToString(", ") { (key, value) ->
                            when (value) {
                                is String -> "\"$key\": \"$value\""
                                else -> "\"$key\": $value"
                            }
                        })
                        append("}")
                    }

                    append("}")
                    if (index < performanceEvents.size - 1) appendLine(",")
                    else appendLine()
                }
            }

            appendLine("  ],")
            appendLine("  \"displayTimeUnit\": \"ms\",")
            appendLine("  \"systemTraceEvents\": \"\",")
            appendLine("  \"metadata\": {")
            appendLine("    \"trace_type\": \"android_app_trace\",")
            appendLine("    \"app_package\": \"${context.packageName}\",")
            appendLine("    \"trace_duration_ms\": ${System.currentTimeMillis() - startTime},")
            appendLine("    \"events_count\": ${performanceEvents.size},")
            appendLine("    \"total_events_processed\": ${performanceEvents.size + eventsWrittenToDisk},")
            appendLine("    \"events_in_circular_buffer\": ${performanceEvents.size}")
            appendLine("  }")
            appendLine("}")
        }
    }

    /**
     * Documents/save/ 디렉토리 반환
     */
    private fun getSaveDirectory(): File {
        return try {
            // Q+에서는 공용 Documents 대신 앱 스코프 Documents/save 사용 (권장)
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.getExternalFilesDir(null)
            File(base, "save").apply { if (!exists()) mkdirs() }
        } catch (e: Exception) {
            fileLogger.e(TAG, "save 디렉토리 생성 실패: ${e.message}", e)
            // 최후 폴백: 내부 저장소
            File(context.filesDir, "save").apply { if (!exists()) mkdirs() }
        }
    }

    /**
     * 힙 덤프 생성 - Documents/save/에 저장
     */
    fun generateHeapDump(reason: String = "Manual"): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val heapDumpFile = File(getSaveDirectory(), "heap_dump_${timestamp}_${reason}.hprof")

            android.os.Debug.dumpHprofData(heapDumpFile.absolutePath)
            fileLogger.i(TAG, "📊 힙 덤프 생성: ${heapDumpFile.absolutePath}")

            heapDumpFile.absolutePath
        } catch (e: Exception) {
            fileLogger.e(TAG, "힙 덤프 생성 실패: ${e.message}", e)
            null
        }
    }

    /**
     * 섹션별 추적
     */
    fun traceSection(sectionName: String, block: () -> Unit) {
        try {
            Trace.beginSection(sectionName)
            addPerformanceEvent(sectionName, "section", "B")

            block()

        } finally {
            addPerformanceEvent(sectionName, "section", "E")
            Trace.endSection()
        }
    }

    /**
     * 비동기 섹션 추적
     */
    suspend fun traceSectionAsync(sectionName: String, block: suspend () -> Unit) {
        try {
            Trace.beginSection(sectionName)
            addPerformanceEvent(sectionName, "async_section", "B")

            block()

        } finally {
            addPerformanceEvent(sectionName, "async_section", "E")
            Trace.endSection()
        }
    }

    /**
     * 모든 추적 파일 경로 반환 - Documents/save/에서 검색
     */
    fun getAllTraceFiles(): List<String> {
        val traceFiles = mutableListOf<String>()

        try {
            val saveDir = getSaveDirectory()

            // Perfetto 추적 파일들
            saveDir.listFiles { file ->
                file.name.endsWith(".perfetto-trace") ||
                        file.name.endsWith(".json") ||
                        file.name.startsWith("perfetto_trace")
            }?.forEach { file ->
                traceFiles.add(file.absolutePath)
            }

            // 메서드 추적 파일들
            saveDir.listFiles { file ->
                file.name.startsWith("method_trace") && file.name.endsWith(".trace")
            }?.forEach { file ->
                traceFiles.add(file.absolutePath)
            }

            // 힙 덤프 파일들
            saveDir.listFiles { file ->
                file.name.startsWith("heap_dump") && file.name.endsWith(".hprof")
            }?.forEach { file ->
                traceFiles.add(file.absolutePath)
            }

        } catch (e: Exception) {
            fileLogger.e(TAG, "추적 파일 목록 조회 실패: ${e.message}", e)
        }

        return traceFiles.sorted()
    }

    /**
     * 추적 상태 확인
     */
    fun isTracing(): Boolean = isTracing

    /**
     * 수집된 이벤트 수 반환 (순환 버퍼 + 처리된 총량)
     */
    fun getEventCount(): Int = performanceEvents.size + eventsWrittenToDisk

    /**
     * 추적 정리
     */
    fun cleanup() {
        try {
            if (isTracing) {
                stopPerfettoTrace()
            }
            tracingScope.cancel()
            performanceEvents.clear()

            fileLogger.i(TAG, "🧹 Perfetto 추적 시스템 정리 완료")
        } catch (e: Exception) {
            fileLogger.e(TAG, "Perfetto 추적 정리 실패: ${e.message}", e)
        }
    }
}
package com.example.myapplication.perfetto

import android.content.Context
import android.os.Debug
import android.os.Environment
import android.os.Trace
import android.util.Log
import com.example.myapplication.BuildConfig
import com.example.myapplication.Logsystem.FileLogger
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Perfetto 트레이싱을 위한 중앙 집중식 싱글톤 매니저
 * 앱 전역에서 단일 인스턴스로 트레이싱을 관리
 */
class PerfettoManager private constructor(
    private val context: Context,
    private val fileLogger: FileLogger? = null
) {
    companion object {
        private const val TAG = "PerfettoManager"

        @Volatile
        private var INSTANCE: PerfettoManager? = null

        fun getInstance(context: Context): PerfettoManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PerfettoManager(
                    context.applicationContext,
                    try {
                        FileLogger.getInstance(context)
                    } catch (e: Exception) {
                        null // FileLogger가 없으면 Log 사용
                    }
                ).also { INSTANCE = it }
            }
        }
    }

    private val tracingActive = AtomicBoolean(false)
    private val tracingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentTraceFile: File? = null
    private var startTime: Long = 0
    private var methodTracingActive = false
    private var eventCount: Long = 0

    /**
     * Perfetto 트레이싱 시작
     * @param sessionName 세션 이름 (파일명에 사용)
     * @return 트레이스 파일 경로 또는 null (실패 시)
     */
    fun startPerfettoTracing(sessionName: String): String? {
        // BuildConfig 플래그 체크
        if (!BuildConfig.PERFETTO_TRACING_ENABLED) {
            logW("Perfetto tracing is disabled")
            return null
        }

        // 이미 트레이싱 중이면 현재 파일 반환
        if (!tracingActive.compareAndSet(false, true)) {
            logW("Perfetto tracing already active")
            return currentTraceFile?.absolutePath
        }

        return synchronized(this) {
            try {
                startTime = System.currentTimeMillis()
                eventCount = 0

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val saveDir = getSaveDirectory()
                currentTraceFile = File(saveDir, "${sessionName}_${timestamp}.perfetto-trace")

                // 시스템 트레이싱 시작
                Trace.beginSection("PerfettoManager-$sessionName")

                // 메서드 트레이싱 시작 (PERFETTO_TRACING_ENABLED가 true일 때만)
                if (BuildConfig.PERFETTO_TRACING_ENABLED) {
                    try {
                        val methodTraceBase = File(saveDir, "${sessionName}_method_${timestamp}").absolutePath
                        Debug.startMethodTracing(methodTraceBase, 64 * 1024 * 1024)
                        methodTracingActive = true
                    } catch (e: Exception) {
                        logE("Failed to start method tracing: ${e.message}", e)
                    }
                }

                logI("✅ Perfetto tracing started: ${currentTraceFile!!.absolutePath}")
                currentTraceFile!!.absolutePath

            } catch (e: Exception) {
                logE("Failed to start Perfetto tracing: ${e.message}", e)
                tracingActive.set(false)
                null
            }
        }
    }

    /**
     * Perfetto 트레이싱 중지
     * @return 트레이스 파일 경로 또는 null (실패 시)
     */
    fun stopPerfettoTracing(): String? {
        if (!tracingActive.compareAndSet(true, false)) {
            logW("Perfetto tracing not active")
            return null
        }

        return synchronized(this) {
            try {
                // 메서드 트레이싱 중지
                if (methodTracingActive) {
                    try {
                        Debug.stopMethodTracing()
                        methodTracingActive = false
                    } catch (e: Exception) {
                        logE("Failed to stop method tracing: ${e.message}", e)
                    }
                }

                // 시스템 트레이싱 종료
                Trace.endSection()

                val filePath = currentTraceFile?.absolutePath
                logI("✅ Perfetto tracing stopped: $filePath")
                logI("📊 Total events: $eventCount")

                currentTraceFile = null
                filePath

            } catch (e: Exception) {
                logE("Failed to stop Perfetto tracing: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 트레이싱 활성 상태 확인
     */
    fun isTracingActive(): Boolean = tracingActive.get()

    /**
     * 수집된 이벤트 수 반환
     */
    fun getEventCount(): Long = eventCount

    /**
     * 모든 트레이스 파일 목록 반환
     */
    fun getAllTraceFiles(): List<String> {
        val traceFiles = mutableListOf<String>()

        try {
            val saveDir = getSaveDirectory()

            // Perfetto 트레이스 파일
            saveDir.listFiles { file ->
                file.name.endsWith(".perfetto-trace") ||
                file.name.endsWith(".trace") ||
                file.name.endsWith(".hprof")
            }?.forEach { file ->
                traceFiles.add(file.absolutePath)
            }

        } catch (e: Exception) {
            logE("Failed to list trace files: ${e.message}", e)
        }

        return traceFiles.sorted()
    }

    /**
     * 힙 덤프 생성
     * @param tag 태그 (파일명에 포함)
     * @return 힙 덤프 파일 경로 또는 null (실패 시)
     */
    suspend fun generateHeapDump(tag: String): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val heapDumpFile = File(getSaveDirectory(), "heapdump_${tag}_${timestamp}.hprof")

            Debug.dumpHprofData(heapDumpFile.absolutePath)
            logI("📊 Heap dump generated: ${heapDumpFile.absolutePath}")

            heapDumpFile.absolutePath
        } catch (e: Exception) {
            logE("Failed to generate heap dump: ${e.message}", e)
            null
        }
    }

    /**
     * 비동기 섹션 트레이싱
     * @param name 섹션 이름
     * @param block 실행할 블록
     * @return 블록 실행 결과
     */
    suspend fun <T> traceSectionAsync(name: String, block: suspend () -> T): T {
        return try {
            Trace.beginSection(name)
            eventCount++
            block()
        } finally {
            Trace.endSection()
        }
    }

    /**
     * 정리 작업
     */
    fun cleanup() {
        try {
            if (tracingActive.get()) {
                stopPerfettoTracing()
            }
            tracingScope.cancel()
            logI("🧹 PerfettoManager cleanup completed")
        } catch (e: Exception) {
            logE("Failed to cleanup PerfettoManager: ${e.message}", e)
        }
    }

    /**
     * Documents/save/ 디렉토리 반환
     */
    private fun getSaveDirectory(): File {
        return try {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.getExternalFilesDir(null)
            File(base, "save").apply { 
                if (!exists()) {
                    mkdirs()
                }
            }
        } catch (e: Exception) {
            logE("Failed to create save directory: ${e.message}", e)
            // 폴백: 내부 저장소
            File(context.filesDir, "save").apply {
                if (!exists()) {
                    mkdirs()
                }
            }
        }
    }

    // 로깅 헬퍼 메서드들
    private fun logI(message: String) {
        fileLogger?.i(TAG, message) ?: Log.i(TAG, message)
    }

    private fun logW(message: String) {
        fileLogger?.w(TAG, message) ?: Log.w(TAG, message)
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        if (fileLogger != null) {
            fileLogger.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message, throwable)
        }
    }
}
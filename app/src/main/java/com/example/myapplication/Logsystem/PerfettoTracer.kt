// app/src/main/java/com/example/myapplication/Logsystem/PerfettoTracer.kt
package com.example.myapplication.Logsystem

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Trace
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * 시스템 레벨 추적 시작
     */
    fun startSystemTrace(category: String = "AppPerformance") {
        if (isTracing) {
            fileLogger.w(TAG, "이미 추적 중입니다")
            return
        }

        try {
            isTracing = true

            // 시스템 추적 시작
            Trace.beginSection("$category-SystemTrace")

            // 메서드 추적 시작
            startMethodTracing()

            fileLogger.i(TAG, "시스템 추적 시작: $category")

        } catch (e: Exception) {
            fileLogger.e(TAG, "시스템 추적 시작 실패: ${e.message}", e)
            isTracing = false
        }
    }

    /**
     * 메서드 레벨 추적 시작
     */
    private fun startMethodTracing() {
        try {
            val traceFile = File(context.getExternalFilesDir(null), "method_trace.trace")
            Debug.startMethodTracing(traceFile.absolutePath, 8 * 1024 * 1024) // 8MB
            fileLogger.i(TAG, "메서드 추적 시작: ${traceFile.absolutePath}")
        } catch (e: Exception) {
            fileLogger.e(TAG, "메서드 추적 시작 실패: ${e.message}", e)
        }
    }

    /**
     * 시스템 추적 중지
     */
    fun stopSystemTrace(): String? {
        if (!isTracing) {
            fileLogger.w(TAG, "추적이 실행 중이 아닙니다")
            return null
        }

        try {
            // 메서드 추적 중지
            Debug.stopMethodTracing()

            // 시스템 추적 중지
            Trace.endSection()

            isTracing = false

            val traceFile = File(context.getExternalFilesDir(null), "method_trace.trace")
            fileLogger.i(TAG, "시스템 추적 중지 완료")

            return traceFile.absolutePath

        } catch (e: Exception) {
            fileLogger.e(TAG, "시스템 추적 중지 실패: ${e.message}", e)
            isTracing = false
            return null
        }
    }

    /**
     * 섹션별 추적
     */
    fun traceSection(sectionName: String, block: () -> Unit) {
        try {
            Trace.beginSection(sectionName)
            block()
        } finally {
            Trace.endSection()
        }
    }

    /**
     * 비동기 섹션 추적
     */
    suspend fun traceSectionAsync(sectionName: String, block: suspend () -> Unit) {
        try {
            Trace.beginSection(sectionName)
            block()
        } finally {
            Trace.endSection()
        }
    }
}
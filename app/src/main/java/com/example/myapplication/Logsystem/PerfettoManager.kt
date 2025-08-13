// app/src/main/java/com/example/myapplication/Logsystem/PerfettoManager.kt
package com.example.myapplication.Logsystem

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 🎯 단일 Perfetto 관리자 - 전체 앱에서 하나의 인스턴스만 사용
 */
class PerfettoManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PerfettoManager"

        @Volatile
        private var INSTANCE: PerfettoManager? = null

        fun getInstance(context: Context): PerfettoManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PerfettoManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // 단일 PerfettoTracer 인스턴스
    private val perfettoTracer = AtomicReference<PerfettoTracer?>(null)
    private val isTracingActive = AtomicBoolean(false)
    private val fileLogger = FileLogger.getInstance(context)

    // 관리 스코프
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 🎯 Perfetto 추적 시작 (전체 앱에서 단 한 번만)
     */
    fun startPerfettoTracing(sessionName: String = "AppSession"): String? {
        return synchronized(this) {
            if (isTracingActive.get()) {
                fileLogger.w(TAG, "⚠️ Perfetto 이미 실행 중 - 중복 시작 방지")
                return perfettoTracer.get()?.getAllTraceFiles()?.firstOrNull()
            }

            try {
                // 기존 tracer 정리
                perfettoTracer.get()?.cleanup()

                // 새 tracer 생성
                val newTracer = PerfettoTracer.getInstance(context)
                perfettoTracer.set(newTracer)

                // 추적 시작
                val traceFile = newTracer.startPerfettoTrace(sessionName)
                if (traceFile != null) {
                    isTracingActive.set(true)
                    fileLogger.i(TAG, "✅ Perfetto 단일 추적 시작: $traceFile")

                    // 자동 정기 상태 체크
                    startPeriodicHealthCheck()
                } else {
                    fileLogger.e(TAG, "❌ Perfetto 추적 시작 실패")
                }

                traceFile
            } catch (e: Exception) {
                fileLogger.e(TAG, "❌ Perfetto 시작 예외: ${e.message}", e)
                isTracingActive.set(false)
                null
            }
        }
    }

    /**
     * 🎯 Perfetto 추적 중지 (전체 앱에서 단 한 번만)
     */
    fun stopPerfettoTracing(): String? {
        return synchronized(this) {
            if (!isTracingActive.get()) {
                fileLogger.w(TAG, "⚠️ Perfetto 실행 중이 아님 - 중지 요청 무시")
                return null
            }

            try {
                val tracer = perfettoTracer.get()
                val finalTrace = tracer?.stopPerfettoTrace()

                isTracingActive.set(false)
                fileLogger.i(TAG, "✅ Perfetto 단일 추적 중지: $finalTrace")

                finalTrace
            } catch (e: Exception) {
                fileLogger.e(TAG, "❌ Perfetto 중지 예외: ${e.message}", e)
                isTracingActive.set(false)
                null
            }
        }
    }

    /**
     * 🎯 현재 추적 상태 확인
     */
    fun isTracingActive(): Boolean = isTracingActive.get()

    /**
     * 🎯 추적 파일 경로들 반환
     */
    fun getAllTraceFiles(): List<String> {
        return perfettoTracer.get()?.getAllTraceFiles() ?: emptyList()
    }

    /**
     * 🎯 힙 덤프 생성 (안전하게)
     */
    fun generateHeapDump(reason: String): String? {
        return if (isTracingActive.get()) {
            perfettoTracer.get()?.generateHeapDump(reason)
        } else {
            fileLogger.w(TAG, "⚠️ Perfetto 비활성 상태 - 힙 덤프 스킵")
            null
        }
    }

    /**
     * 🎯 Section 추적 (안전하게)
     */
    fun traceSection(sectionName: String, block: () -> Unit) {
        if (isTracingActive.get()) {
            perfettoTracer.get()?.traceSection(sectionName, block) ?: block()
        } else {
            block() // Perfetto 비활성화 시에도 동작 보장
        }
    }

    /**
     * 🎯 비동기 Section 추적 (안전하게)
     */
    suspend fun traceSectionAsync(sectionName: String, block: suspend () -> Unit) {
        if (isTracingActive.get()) {
            perfettoTracer.get()?.traceSectionAsync(sectionName, block) ?: block()
        } else {
            block() // Perfetto 비활성화 시에도 동작 보장
        }
    }

    /**
     * 🎯 이벤트 수 반환
     */
    fun getEventCount(): Int {
        return perfettoTracer.get()?.getEventCount() ?: 0
    }

    /**
     * 정기 건강성 체크
     */
    private fun startPeriodicHealthCheck() {
        managerScope.launch {
            while (isActive && isTracingActive.get()) {
                delay(30_000) // 30초마다

                try {
                    val tracer = perfettoTracer.get()
                    if (tracer?.isTracing() != true) {
                        fileLogger.w(TAG, "⚠️ Perfetto 추적 상태 불일치 감지 - 복구 시도")
                        // 상태 재동기화
                        isTracingActive.set(false)
                        break
                    }

                    val eventCount = tracer.getEventCount()
                    if (eventCount > 100_000) { // 이벤트 과다 시 알림
                        fileLogger.w(TAG, "⚠️ Perfetto 이벤트 과다: ${eventCount}개")
                    }

                } catch (e: Exception) {
                    fileLogger.e(TAG, "건강성 체크 실패: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 🎯 완전 정리
     */
    fun cleanup() {
        synchronized(this) {
            try {
                // 추적 중지
                if (isTracingActive.get()) {
                    stopPerfettoTracing()
                }

                // 리소스 정리
                managerScope.cancel()
                perfettoTracer.get()?.cleanup()
                perfettoTracer.set(null)

                fileLogger.i(TAG, "🧹 PerfettoManager 완전 정리 완료")
            } catch (e: Exception) {
                fileLogger.e(TAG, "정리 중 예외: ${e.message}", e)
            }
        }
    }
}
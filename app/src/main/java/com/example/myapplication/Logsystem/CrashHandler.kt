package com.example.myapplication.Logsystem

import android.content.Context
import android.util.Log
import kotlin.system.exitProcess

class CrashHandler private constructor(
    private val context: Context,
    private val fileLogger: FileLogger,
    private val resourceMonitor: ResourceMonitor
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"

        fun setup(context: Context) {
            val fileLogger = FileLogger.getInstance(context)
            val resourceMonitor = ResourceMonitor.getInstance(context)
            val crashHandler = CrashHandler(context, fileLogger, resourceMonitor)

            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
            Log.d(TAG, " 크래시 핸들러 설정 완료")
        }
    }

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            //  크래시 직전 긴급 상태 저장
            saveCrashReport(thread, throwable)

            // 기본 핸들러 호출 (시스템 크래시 다이얼로그 등)
            defaultHandler?.uncaughtException(thread, throwable)

        } catch (e: Exception) {
            Log.e(TAG, "크래시 리포트 저장 실패: ${e.message}", e)
        } finally {
            exitProcess(1)
        }
    }

    private fun saveCrashReport(thread: Thread, throwable: Throwable) {
        fileLogger.emergencyLog(TAG, "🚨🚨🚨 앱 크래시 발생 🚨🚨🚨")
        fileLogger.emergencyLog(TAG, "크래시 스레드: ${thread.name}")
        fileLogger.emergencyLog(TAG, "크래시 시간: ${System.currentTimeMillis()}")

        // 메모리 상태 긴급 저장
        try {
            val appMemory = resourceMonitor.getAppMemoryInfo()
            val systemMemory = resourceMonitor.getSystemMemoryInfo()

            fileLogger.emergencyLog(TAG, "=== 크래시 직전 메모리 상태 ===")
            fileLogger.emergencyLog(TAG, "앱 힙 사용률: ${String.format("%.1f", appMemory.heapUsagePercent)}%")
            fileLogger.emergencyLog(TAG, "앱 힙 가용: ${String.format("%.1f", appMemory.availableHeapMB)} MB")
            fileLogger.emergencyLog(TAG, "Native 메모리: ${String.format("%.1f", appMemory.nativeHeapMB)} MB")
            fileLogger.emergencyLog(TAG, "시스템 메모리 부족: ${systemMemory.systemMemoryLow}")
            fileLogger.emergencyLog(TAG, "메모리 압박 수준: ${appMemory.memoryPressureLevel}")

        } catch (e: Exception) {
            fileLogger.emergencyLog(TAG, "메모리 상태 수집 실패: ${e.message}")
        }

        // 예외 정보 저장
        fileLogger.emergencyLog(TAG, "예외 타입: ${throwable.javaClass.simpleName}")
        fileLogger.emergencyLog(TAG, "예외 메시지: ${throwable.message}")

        // 스택 트레이스 저장
        val stackTrace = throwable.stackTrace.take(20).joinToString("\n") { "    at $it" }
        fileLogger.emergencyLog(TAG, "스택 트레이스:\n$stackTrace")

        // Cause 체인 저장
        var cause = throwable.cause
        var depth = 1
        while (cause != null && depth <= 3) {
            fileLogger.emergencyLog(TAG, "Caused by ($depth): ${cause.javaClass.simpleName}: ${cause.message}")
            cause = cause.cause
            depth++
        }

        fileLogger.emergencyLog(TAG, "🚨🚨🚨 크래시 리포트 끝 🚨🚨🚨")

        // 로그 즉시 플러시
        Thread.sleep(1000) // 로그 기록 완료 대기
    }
}
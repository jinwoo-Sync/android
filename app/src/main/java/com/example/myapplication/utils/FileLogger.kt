// app/src/main/java/com/example/myapplication/utils/FileLogger.kt
package com.example.myapplication.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class FileLogger private constructor(private val context: Context) {
    companion object {
        private const val TAG = "FileLogger"
        private const val MAX_LOG_FILES = 10
        private const val MAX_FILE_SIZE_MB = 10

        @Volatile
        private var INSTANCE: FileLogger? = null

        fun getInstance(context: Context): FileLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FileLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
    private val loggingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentLogFile: File? = null
    private var isLoggingActive = false

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )

    init {
        startFileLogging()
    }

    fun startFileLogging() {
        if (isLoggingActive) return

        isLoggingActive = true
        currentLogFile = createNewLogFile()

        loggingScope.launch {
            while (isLoggingActive) {
                try {
                    processLogQueue()
                    delay(500) // 0.5초마다 로그 처리
                } catch (e: Exception) {
                    Log.e(TAG, "로그 처리 오류: ${e.message}", e)
                }
            }
        }

        Log.d(TAG, "📝 파일 로깅 시작: ${currentLogFile?.absolutePath}")
    }

    fun stopFileLogging() {
        isLoggingActive = false
        processLogQueue() // 남은 로그 처리
        Log.d(TAG, "📝 파일 로깅 중지")
    }

    // 🎯 메인 로깅 함수들
    fun d(tag: String, message: String) {
        addLog("DEBUG", tag, message)
        Log.d(tag, message) // 기존 로그도 유지
    }

    fun i(tag: String, message: String) {
        addLog("INFO", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        addLog("WARN", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        addLog("ERROR", tag, message, throwable)
        Log.e(tag, message, throwable)
    }

    // 🎯 중요한 크래시 전 상태 저장
    fun emergencyLog(tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = "EMERGENCY",
            tag = tag,
            message = message
        )

        // 즉시 파일에 기록 (큐 우회)
        loggingScope.launch {
            writeLogEntryToFile(entry)
        }

        Log.e(tag, "🚨 EMERGENCY: $message")
    }

    private fun addLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )

        logQueue.offer(entry)

        // 큐가 너무 크면 정리
        while (logQueue.size > 1000) {
            logQueue.poll()
        }
    }

    private suspend fun processLogQueue() = withContext(Dispatchers.IO) {
        val currentFile = currentLogFile ?: return@withContext

        // 파일 크기 체크 및 로테이션
        if (currentFile.length() > MAX_FILE_SIZE_MB * 1024 * 1024) {
            rotateLogFile()
        }

        val entries = mutableListOf<LogEntry>()
        while (logQueue.isNotEmpty() && entries.size < 100) {
            logQueue.poll()?.let { entries.add(it) }
        }

        if (entries.isNotEmpty()) {
            writeLogEntriesToFile(entries)
        }
    }

    private fun writeLogEntriesToFile(entries: List<LogEntry>) {
        val logFile = currentLogFile ?: return

        try {
            FileWriter(logFile, true).use { fileWriter ->
                PrintWriter(fileWriter).use { writer ->
                    for (entry in entries) {
                        writeLogEntry(writer, entry)
                    }
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "로그 파일 쓰기 실패: ${e.message}", e)
        }
    }

    private fun writeLogEntryToFile(entry: LogEntry) {
        val logFile = currentLogFile ?: return

        try {
            FileWriter(logFile, true).use { fileWriter ->
                PrintWriter(fileWriter).use { writer ->
                    writeLogEntry(writer, entry)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "응급 로그 파일 쓰기 실패: ${e.message}", e)
        }
    }

    private fun writeLogEntry(writer: PrintWriter, entry: LogEntry) {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        writer.println("$timestamp ${entry.level}/${entry.tag}: ${entry.message}")

        entry.throwable?.let { throwable ->
            writer.println("Exception: ${throwable.javaClass.simpleName}: ${throwable.message}")
            throwable.stackTrace.forEach { element ->
                writer.println("    at $element")
            }
        }
    }

    private fun createNewLogFile(): File {
        val timestamp = fileNameFormat.format(Date())
        val logDir = File(context.getExternalFilesDir(null), "crash_logs")

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        return File(logDir, "app_log_$timestamp.txt")
    }

    private fun rotateLogFile() {
        currentLogFile = createNewLogFile()
        cleanupOldLogFiles()
        Log.d(TAG, "📝 로그 파일 로테이션: ${currentLogFile?.absolutePath}")
    }

    private fun cleanupOldLogFiles() {
        try {
            val logDir = File(context.getExternalFilesDir(null), "crash_logs")
            val logFiles = logDir.listFiles { file -> file.name.startsWith("app_log_") }?.toList() ?: return

            if (logFiles.size > MAX_LOG_FILES) {
                logFiles.sortedBy { it.lastModified() }
                    .take(logFiles.size - MAX_LOG_FILES)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "오래된 로그 파일 정리 실패: ${e.message}", e)
        }
    }

    fun getCurrentLogFilePath(): String? = currentLogFile?.absolutePath

    fun getAllLogFiles(): List<File> {
        val logDir = File(context.getExternalFilesDir(null), "crash_logs")
        return logDir.listFiles { file -> file.name.startsWith("app_log_") }?.toList() ?: emptyList()
    }
}
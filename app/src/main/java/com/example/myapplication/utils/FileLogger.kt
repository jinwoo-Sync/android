// app/src/main/java/com/example/myapplication/utils/FileLogger.kt
package com.example.myapplication.utils

import android.content.Context
import android.os.Build
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

    private fun getLogDirectory(): File? {
        return try {
            // API 29+ (Android 10+) - Documents/save 경로 사용
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Documents 폴더 내 save 폴더
                val documentsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "save")
                if (!documentsDir.exists()) {
                    documentsDir.mkdirs()
                }
                documentsDir
            } else {
                // API 28 이하 - 기존 방식
                val saveDir = File(Environment.getExternalStorageDirectory(), "Documents/save")
                if (!saveDir.exists()) {
                    saveDir.mkdirs()
                }
                saveDir
            }
        } catch (e: Exception) {
            Log.e(TAG, "로그 디렉터리 생성 실패: ${e.message}", e)
            // 폴백: 앱 내부 저장소
            File(context.filesDir, "logs").apply {
                if (!exists()) mkdirs()
            }
        }
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
        runBlocking {
            processLogQueue() // 남은 로그 처리
        }
        Log.d(TAG, "📝 파일 로깅 중지")
    }

    // 🎯 메인 로깅 함수들
    fun d(tag: String, message: String) {
        addLog("DEBUG", tag, message)
        Log.d(tag, message)
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
            // 파일이 존재하지 않으면 생성
            if (!logFile.exists()) {
                logFile.parentFile?.mkdirs()
                logFile.createNewFile()
            }

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
            // 폴백으로 내부 저장소에 저장 시도
            tryWriteToInternalStorage(entries)
        }
    }

    private fun writeLogEntryToFile(entry: LogEntry) {
        val logFile = currentLogFile ?: return

        try {
            if (!logFile.exists()) {
                logFile.parentFile?.mkdirs()
                logFile.createNewFile()
            }

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

    private fun tryWriteToInternalStorage(entries: List<LogEntry>) {
        try {
            val internalLogDir = File(context.filesDir, "logs")
            if (!internalLogDir.exists()) internalLogDir.mkdirs()

            val timestamp = fileNameFormat.format(Date())
            val fallbackFile = File(internalLogDir, "fallback_log_$timestamp.txt")

            FileWriter(fallbackFile, true).use { fileWriter ->
                PrintWriter(fileWriter).use { writer ->
                    writer.println("=== 외부 저장소 접근 실패로 내부 저장소에 기록 ===")
                    for (entry in entries) {
                        writeLogEntry(writer, entry)
                    }
                    writer.flush()
                }
            }

            Log.w(TAG, "폴백으로 내부 저장소에 로그 저장: ${fallbackFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "폴백 로그 저장도 실패: ${e.message}", e)
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

    private fun createNewLogFile(): File? {
        val timestamp = fileNameFormat.format(Date())
        val logDir = getLogDirectory() ?: return null

        return File(logDir, "app_crash_log_$timestamp.txt")
    }

    private fun rotateLogFile() {
        currentLogFile = createNewLogFile()
        cleanupOldLogFiles()
        Log.d(TAG, "📝 로그 파일 로테이션: ${currentLogFile?.absolutePath}")
    }

    private fun cleanupOldLogFiles() {
        try {
            val logDir = getLogDirectory() ?: return
            val logFiles = logDir.listFiles { file ->
                file.name.startsWith("app_crash_log_") && file.name.endsWith(".txt")
            }?.toList() ?: return

            if (logFiles.size > MAX_LOG_FILES) {
                logFiles.sortedBy { it.lastModified() }
                    .take(logFiles.size - MAX_LOG_FILES)
                    .forEach {
                        val deleted = it.delete()
                        Log.d(TAG, "오래된 로그 파일 삭제: ${it.name}, 성공: $deleted")
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "오래된 로그 파일 정리 실패: ${e.message}", e)
        }
    }

    fun getCurrentLogFilePath(): String? = currentLogFile?.absolutePath

    fun getAllLogFiles(): List<File> {
        val logDir = getLogDirectory() ?: return emptyList()
        return logDir.listFiles { file ->
            file.name.startsWith("app_crash_log_") && file.name.endsWith(".txt")
        }?.toList() ?: emptyList()
    }

    // 🎯 로그 파일 위치 확인용
    fun getLogDirectoryPath(): String? = getLogDirectory()?.absolutePath
}
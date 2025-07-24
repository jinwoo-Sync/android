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
        private const val MAX_FILE_SIZE_MB = 50  // 파일 크기 증가
        private const val MAX_SINGLE_LOG_SIZE = 100000  // 로그 크기 증가

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
            // 외부 저장소의 Documents/save 디렉토리 사용
            val documentsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "save")
            } else {
                File(Environment.getExternalStorageDirectory(), "Documents/save")
            }

            if (!documentsDir.exists()) {
                val created = documentsDir.mkdirs()
                Log.d(TAG, "로그 디렉토리 생성: ${documentsDir.absolutePath}, 성공: $created")
            }

            // 디렉토리가 쓰기 가능한지 확인
            if (!documentsDir.canWrite()) {
                Log.e(TAG, "로그 디렉토리 쓰기 권한 없음: ${documentsDir.absolutePath}")
                return getInternalLogDirectory()
            }

            documentsDir
        } catch (e: Exception) {
            Log.e(TAG, "외부 저장소 접근 실패: ${e.message}", e)
            getInternalLogDirectory()
        }
    }

    private fun getInternalLogDirectory(): File {
        val internalDir = File(context.filesDir, "logs")
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        return internalDir
    }

    fun startFileLogging() {
        if (isLoggingActive) return

        isLoggingActive = true
        currentLogFile = createNewLogFile()

        loggingScope.launch {
            while (isLoggingActive) {
                try {
                    processLogQueue()
                    delay(100)  // 딜레이 감소 (500ms -> 100ms)
                } catch (e: Exception) {
                    Log.e(TAG, "로그 처리 오류: ${e.message}", e)
                }
            }
        }

        Log.d(TAG, "📝 파일 로깅 시작: ${currentLogFile?.absolutePath}")
    }

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

    // 종합 분석용 로그 - 즉시 파일에 기록
    fun comprehensiveLog(tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = "COMPREHENSIVE",
            tag = tag,
            message = message
        )

        // 큐에 추가
        logQueue.offer(entry)

        // 중요한 로그는 즉시 파일에 기록
        loggingScope.launch {
            writeLogEntryToFileDirectly(entry)
        }

        Log.i(tag, "📊 COMPREHENSIVE: ${message.take(200)}...")  // 로그캣에는 요약만
    }

    // 긴급 로그 - 즉시 파일에 기록
    fun emergencyLog(tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = "EMERGENCY",
            tag = tag,
            message = message
        )

        // 즉시 파일에 기록
        runBlocking {
            writeLogEntryToFileDirectly(entry)
        }

        Log.e(tag, "🚨 EMERGENCY: ${message.take(200)}...")
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

        // 큐 크기 제한 증가
        while (logQueue.size > 5000) {
            logQueue.poll()
        }
    }

    private suspend fun processLogQueue() = withContext(Dispatchers.IO) {
        val currentFile = currentLogFile ?: return@withContext

        // 파일 크기 체크
        if (currentFile.length() > MAX_FILE_SIZE_MB * 1024 * 1024) {
            rotateLogFile()
        }

        // 한 번에 더 많은 로그 처리
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
            // 파일이 없으면 생성
            if (!logFile.exists()) {
                logFile.parentFile?.mkdirs()
                logFile.createNewFile()
                Log.d(TAG, "새 로그 파일 생성: ${logFile.absolutePath}")
            }

            // 파일에 쓰기
            FileWriter(logFile, true).use { fileWriter ->
                PrintWriter(fileWriter).use { writer ->
                    for (entry in entries) {
                        try {
                            writeLogEntry(writer, entry)
                        } catch (e: Exception) {
                            Log.e(TAG, "로그 엔트리 쓰기 실패: ${e.message}", e)
                        }
                    }
                    writer.flush()  // 강제 플러시
                }
            }

            Log.v(TAG, "✅ ${entries.size}개 로그 저장 완료 (파일: ${logFile.name})")

        } catch (e: Exception) {
            Log.e(TAG, "파일 쓰기 실패: ${e.message}", e)
            tryWriteToInternalStorage(entries)
        }
    }

    // 직접 파일에 쓰기 (중요한 로그용)
    fun writeLogEntryToFileDirectly(entry: LogEntry) {
        val logFile = currentLogFile ?: run {
            Log.e(TAG, "로그 파일이 없음!")
            return
        }

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

            Log.d(TAG, "✅ 직접 로그 저장 완료: ${entry.level}/${entry.tag}")

        } catch (e: Exception) {
            Log.e(TAG, "직접 로그 쓰기 실패: ${e.message}", e)
            // 실패 시 내부 저장소로 시도
            tryWriteToInternalStorage(listOf(entry))
        }
    }

    private fun tryWriteToInternalStorage(entries: List<LogEntry>) {
        try {
            val internalLogDir = getInternalLogDirectory()
            val timestamp = fileNameFormat.format(Date())
            val fallbackFile = File(internalLogDir, "fallback_log_$timestamp.txt")

            FileWriter(fallbackFile, true).use { fileWriter ->
                PrintWriter(fileWriter).use { writer ->
                    writer.println("=== 폴백 로그 (외부 저장소 실패) ===")
                    writer.println("시간: ${dateFormat.format(Date())}")
                    writer.println()

                    for (entry in entries) {
                        writeLogEntry(writer, entry)
                    }
                    writer.flush()
                }
            }

            Log.w(TAG, "폴백 저장 성공: ${fallbackFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "폴백 저장도 실패: ${e.message}", e)
        }
    }

    private fun writeLogEntry(writer: PrintWriter, entry: LogEntry) {
        val timestamp = dateFormat.format(Date(entry.timestamp))

        when (entry.level) {
            "EMERGENCY" -> {
                writer.println()
                writer.println("🚨🚨🚨 EMERGENCY LOG 🚨🚨🚨")
                writer.println("$timestamp ${entry.level}/${entry.tag}:")
                writer.println(entry.message)
                writer.println("🚨🚨🚨 EMERGENCY END 🚨🚨🚨")
                writer.println()
            }
            "COMPREHENSIVE" -> {
                writer.println()
                writer.println("📊 COMPREHENSIVE ANALYSIS 📊")
                writer.println("$timestamp ${entry.level}/${entry.tag}:")
                writer.println(entry.message)
                writer.println("📊 COMPREHENSIVE END 📊")
                writer.println()
            }
            "ERROR" -> {
                writer.println()
                writer.println("❌ ERROR LOG ❌")
                writer.println("$timestamp ${entry.level}/${entry.tag}:")
                writer.println(entry.message)
                entry.throwable?.let {
                    writer.println("Exception: ${it.javaClass.simpleName}: ${it.message}")
                    it.printStackTrace(writer)
                }
                writer.println()
            }
            else -> {
                writer.println("$timestamp ${entry.level}/${entry.tag}: ${entry.message}")
            }
        }
    }

    private fun createNewLogFile(): File? {
        val timestamp = fileNameFormat.format(Date())
        val logDir = getLogDirectory() ?: return null

        val newFile = File(logDir, "app_performance_log_$timestamp.txt")

        try {
            if (!newFile.exists()) {
                newFile.createNewFile()
                Log.i(TAG, "새 로그 파일 생성: ${newFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "로그 파일 생성 실패: ${e.message}", e)
            return null
        }

        return newFile
    }

    private fun rotateLogFile() {
        Log.i(TAG, "로그 파일 로테이션 시작")
        currentLogFile = createNewLogFile()
        cleanupOldLogFiles()
    }

    // 나머지 메서드들은 그대로...

    fun getCurrentLogFilePath(): String? = currentLogFile?.absolutePath

    fun getAllLogFiles(): List<File> {
        val logDir = getLogDirectory() ?: return emptyList()
        return logDir.listFiles { file ->
            file.name.startsWith("app_") && file.name.endsWith(".txt")
        }?.toList() ?: emptyList()
    }

    fun getLogDirectoryPath(): String? = getLogDirectory()?.absolutePath

    fun getLogFileStatus(): String {
        return try {
            val currentFile = currentLogFile
            if (currentFile != null) {
                "파일: ${currentFile.name}, 크기: ${currentFile.length() / 1024}KB, 큐: ${logQueue.size}"
            } else {
                "로그 파일 없음, 큐: ${logQueue.size}"
            }
        } catch (e: Exception) {
            "상태 확인 실패: ${e.message}"
        }
    }

    fun stopFileLogging() {
        isLoggingActive = false
        runBlocking {
            processLogQueue()  // 남은 로그 모두 처리
        }
        Log.d(TAG, "📝 파일 로깅 중지")
    }

    private fun cleanupOldLogFiles() {
        try {
            val logDir = getLogDirectory() ?: return
            val logFiles = logDir.listFiles { file ->
                file.name.startsWith("app_") && file.name.endsWith(".txt")
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
}
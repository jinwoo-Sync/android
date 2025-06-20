package com.example.myapplication.data.logging

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.model.GnssData
import com.example.myapplication.model.SensorData
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.data.streaming.StreamingClientFactory
import com.example.myapplication.data.streaming.test.WebSocketStreamingClient
import com.example.myapplication.model.BoundingBoxLog
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/** 고정 크기 순환 큐 */
class CircularQueue<T>(private val capacity: Int) : Iterable<T> {
    private val deque = ArrayDeque<T>(capacity)

    // push 메서드를 synchronized 블록으로 감싸 스레드 안전하게 만듭니다.
    fun push(item: T) {
        synchronized(deque) { // deque 객체를 락으로 사용하여 동기화
            if (deque.size >= capacity) {
                deque.removeFirst()
            }
            deque.addLast(item)
        }
    }
    fun isNotEmpty(): Boolean = deque.isNotEmpty()
    fun poll(): T? = if (deque.isEmpty()) null else deque.removeFirst()
    fun isEmpty(): Boolean = deque.isEmpty()
    fun snapshot(): List<T> = deque.toList()
    fun clear() = deque.clear()
    fun size(): Int = deque.size
    fun removeLast(): T? = if (deque.isEmpty()) null else deque.removeLast()
    override fun iterator(): Iterator<T> = deque.iterator()
}

object LoggerManager {
    private const val TAG = "LoggerManager"
    private const val QUEUE_CAPACITY   = 1_000
    private const val TEXT_BATCH_SIZE  = 100

    private var isLogSavingEnabled = false
    private var isStreamingEnabled = false

    private inline fun shouldSave()   = isLogSavingEnabled
    private inline fun shouldStream() = isStreamingEnabled

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sendingJob: Job? = null

    private val cameraQueue = CircularQueue<SensorData>(QUEUE_CAPACITY)
    private val gpsQueue    = CircularQueue<Triple<Location, Long, Long>>(QUEUE_CAPACITY)
    private val imuQueue    = CircularQueue<Triple<FloatArray, Long, Long>>(QUEUE_CAPACITY)
    private val gnssQueue   = CircularQueue<GnssData>(QUEUE_CAPACITY)
    private val boundingBoxQueue = CircularQueue<List<BoundingBoxLog>>(QUEUE_CAPACITY) // New queue for bounding boxes

    private var currentBatchTs: Long = 0L

    private lateinit var streamingClient: StreamingClient
    private var currentTransportType: String? = null

    fun setTransportType(transportType: String) {
        if (currentTransportType != transportType || !::streamingClient.isInitialized) {
            streamingClient = StreamingClientFactory.createStreamingClient(transportType)
            currentTransportType = transportType
            Log.d(TAG, "통신 방식 설정됨: $transportType")
        }
    }

    fun enableLogSaving() 
    { 
        isLogSavingEnabled = true; 
        Log.d(TAG, "Local log saving ENABLED")
    }

    fun disableLogSaving(ctx: Context) {
        isLogSavingEnabled = false
        saveAllTextBatches(ctx, force = true)
        Log.d(TAG, "Local log saving DISABLED & flushed")
    }

    suspend fun enableStreaming(context: Context) {
        if (isStreamingEnabled) return
        if (!::streamingClient.isInitialized) {
            throw IllegalStateException("스트리밍을 시작하기 전에 통신 방식을 설정해야 합니다. setTransportType()을 호출하세요.")
        }
        isStreamingEnabled = true
        streamingClient.startStreaming(context)
        scope.launch {
            delay(2000)
            if (sendingJob?.isActive != true) startSendingLoop()
            Log.d(TAG, "Streaming ENABLED")
        }
    }

    suspend fun disableStreaming() {
        if (!isStreamingEnabled) return
        isStreamingEnabled = false
        streamingClient.stopStreaming()
        sendingJob?.cancel()
        sendingJob = null
        Log.d(TAG, "Streaming DISABLED")
    }

    fun pushGps(ctx: Context, loc: Location, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        gpsQueue.push(Triple(loc, sysTs, monoTs))
        if (shouldSave() && gpsQueue.size() >= TEXT_BATCH_SIZE) saveAllTextBatches(ctx)
    }

    fun pushGnss(ctx: Context, g: GnssData) {
        Log.d(TAG, "Pushing GNSS data: $g")
        initBatchTsIfNeeded(g.timestamp)
        gnssQueue.push(g)
        if (shouldSave() && gnssQueue.size() >= TEXT_BATCH_SIZE) saveAllTextBatches(ctx)
    }

    fun pushImu(ctx: Context, imu: FloatArray, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        initBatchTsIfNeeded(sysTs)
        imuQueue.push(Triple(imu.clone(), sysTs, monoTs))
        if (shouldSave() && imuQueue.size() >= TEXT_BATCH_SIZE) saveAllTextBatches(ctx)
    }

    fun pushCamera(ctx: Context, data: SensorData) {
        initBatchTsIfNeeded(data.timestamp)
        cameraQueue.push(data)
        if (shouldSave()) saveSingleCameraFrame(ctx, data)
    }

    fun pushBoundingBox(ctx: Context, bboxes: List<BoundingBoxLog>) {
        if (bboxes.isNotEmpty()) {
            initBatchTsIfNeeded(bboxes[0].timestamp)
            boundingBoxQueue.push(bboxes)
            if (shouldSave() && boundingBoxQueue.size() >= TEXT_BATCH_SIZE) saveAllTextBatches(ctx)
        }
    }

    private fun startSendingLoop() {
        sendingJob = scope.launch {
            while (shouldStream()) {
                if (cameraQueue.isNotEmpty()) {
                    val lastCamera = cameraQueue.removeLast()
                    if (lastCamera != null) {
                        Log.d(TAG, "Dequeuing camera data: frameId=${lastCamera.frameId}")
                        streamingClient.sendCameraData(lastCamera)
                    }
                }
                if (gpsQueue.isNotEmpty()) {
                    val lastGps = gpsQueue.removeLast()
                    if (lastGps != null) {
                        streamingClient.sendGpsData(lastGps.first, lastGps.second, lastGps.third)
                    }
                }
                if (imuQueue.isNotEmpty()) {
                    val lastImu = imuQueue.removeLast()
                    if (lastImu != null) {
                        streamingClient.sendImuData(lastImu.first, lastImu.second, lastImu.third)
                    }
                }
                if (gnssQueue.isNotEmpty()) {
                    val lastGnss = gnssQueue.removeLast()
                    if (lastGnss != null) {
                        Log.d(TAG, "Sending GNSS data: $lastGnss")
                        streamingClient.sendGnssData(lastGnss)
                    }
                } else {
                    Log.d(TAG, "GNSS queue is empty")
                }
                // --- 바운딩 박스 데이터 전송 로직 추가 시작 ---
                if (boundingBoxQueue.isNotEmpty()) {
                    val bboxesForFrame = boundingBoxQueue.removeLast() // List<BoundingBoxLog>
                    if (bboxesForFrame != null) {
                        Log.d(TAG, "Sending Bounding Box data for frame: ${bboxesForFrame.firstOrNull()?.frameId}, count: ${bboxesForFrame.size}")
                        streamingClient.sendBoundingBoxData(bboxesForFrame) // List<BoundingBoxLog> 전달
                    }
                } else {
                    Log.d(TAG, "BoundingBox queue is empty")
                }
                // --- 바운딩 박스 데이터 전송 로직 추가 끝 ---
                delay(100) // 10 Hz
            }
        }
    }

    private fun saveSingleCameraFrame(ctx: Context, d: SensorData) {
        scope.launch {
            runCatching {
                val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(currentBatchTs))
                val relativePath = "Pictures/CameraLogger/$date"
                val fileName = "${d.timestamp}_${d.frameId}.jpeg"

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = ctx.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: run {
                    Log.e(TAG, "Failed to create MediaStore entry for image")
                    return@runCatching
                }

                val bmp: Bitmap = d.bitmap ?: return@runCatching
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)

                MediaScannerConnection.scanFile(
                    ctx,
                    arrayOf("$relativePath/$fileName"),
                    arrayOf("image/jpeg"),
                    null
                )

                Log.d(TAG, "CAMERA saved → $relativePath/$fileName via MediaStore")
            }.onFailure {
                Log.e(TAG, "saveSingleCameraFrame error", it)
            }
        }
    }

    private fun saveAllTextBatches(ctx: Context, force: Boolean = false) {
        if (!force && gpsQueue.isEmpty() && imuQueue.isEmpty() && gnssQueue.isEmpty()) return
        val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(currentBatchTs))
        if (gpsQueue.isNotEmpty()) { saveText(ctx, gpsQueue.snapshot(), "gps", date) { (loc, sys, mono) -> "sys_ts=$sys,mono_ts=$mono,lat=${loc.latitude},lon=${loc.longitude},alt=${loc.altitude},time=${loc.time}" }; gpsQueue.clear() }
        if (imuQueue.isNotEmpty()) { saveText(ctx, imuQueue.snapshot(), "imu", date) { (imu, sys, mono) -> "sys_ts=$sys,mono_ts=$mono,${imu.joinToString(",")}" }; imuQueue.clear() }
        if (gnssQueue.isNotEmpty()) { saveText(ctx, gnssQueue.snapshot(), "gnss", date) { g -> "sys_ts=${g.timestamp},mono_ts=${g.monoTimestamp},gnss_type=${g.gnssType},svid=${g.satelliteId},cn0=${g.signalStrength},pseudorange_rate=${g.pseudorangeRate ?: "null"},carrier_phase=${g.carrierPhase ?: "null"},info=${g.additionalInfo}" }; gnssQueue.clear() }
        // --- 바운딩 박스 데이터 저장 로직 추가 시작 ---
        if (boundingBoxQueue.isNotEmpty()) {
            val allBoundingBoxes = boundingBoxQueue.snapshot().flatten() // List<List<BoundingBoxLog>>를 List<BoundingBoxLog>로 평탄화
            saveText(ctx, allBoundingBoxes, "bounding_boxes", date) { bbox -> "frame_id=${bbox.frameId},sys_ts=${bbox.timestamp},mono_ts=${bbox.monoTimestamp},x1=${bbox.x1},y1=${bbox.y1},x2=${bbox.x2},y2=${bbox.y2},cnf=${bbox.cnf},cls_name=${bbox.clsName}" }
            boundingBoxQueue.clear()
        }
        // --- 바운딩 박스 데이터 저장 로직 추가 끝 ---
        currentBatchTs = 0L
    }

    private fun getLoggerDir(ctx: Context, sensor: String, date: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            ?: throw IllegalStateException("External storage unavailable")
        return File(base, "CameraLogger/$date")
    }

    private fun <T> saveText(
        ctx: Context,
        list: List<T>,
        sensor: String,
        date: String,
        transform: (T) -> String
    ) {
        scope.launch {
            runCatching {
                val content = list.joinToString("\n", transform = transform) + "\n"
                val relativePath = "Documents/IMULogger/$date"
                val fileName = "$sensor.txt"

                val header = when (sensor) {
                    "gps" -> """
                    # GPS Data (WGS84 Coordinate System)
                    # sys_ts: System timestamp (milliseconds since epoch, UTC)
                    # mono_ts: Monotonic timestamp (nanoseconds since device boot)
                    # lat: Latitude (degrees, WGS84)
                    # lon: Longitude (degrees, WGS84)
                    # alt: Altitude (meters above WGS84 ellipsoid)
                    # time: Location fix timestamp (milliseconds since epoch, UTC)
                """.trimIndent()
                    "gnss" -> """
                    # GNSS Raw Measurements
                    # sys_ts: System timestamp (milliseconds since epoch, UTC)
                    # mono_ts: Monotonic timestamp (nanoseconds since device boot)
                    # gnss_type: Constellation type (e.g., GPS, Galileo, BeiDou, GLONASS, QZSS)
                    # svid: Satellite vehicle ID (integer)
                    # cn0: Carrier-to-noise density (dB-Hz)
                    # pseudorange_rate: Pseudorange rate (meters per second, null if unavailable)
                    # carrier_phase: Carrier phase measurement (cycles, null if unavailable)
                    # info: Additional metadata (e.g., State, TimeOffsetNanos in nanoseconds)
                """.trimIndent()
                    "imu" -> """
                    # IMU Data (Accelerometer, Gyroscope, Magnetometer)
                    # sys_ts: System timestamp (milliseconds since epoch, UTC)
                    # mono_ts: Monotonic timestamp (nanoseconds since device boot)
                    # acc_x,acc_y,acc_z: Accelerometer (m/s²)
                    # gyro_x,gyro_y,gyro_z: Gyroscope (radians per second)
                    # mag_x,mag_y,mag_z: Magnetometer (microteslas)
                """.trimIndent()
                    "bounding_boxes" -> """
                    # Bounding Box Detections
                    # frame_id: Unique ID for the camera frame the detection belongs to
                    # sys_ts: System timestamp (milliseconds since epoch, UTC)
                    # mono_ts: Monotonic timestamp (nanoseconds since device boot)
                    # x1,y1,x2,y2: Bounding box coordinates (normalized, 0-1 range, top-left and bottom-right)
                    # cnf: Confidence score (0-1)
                    # cls_name: Class name of the detected object
                """.trimIndent()
                    else -> ""
                }

                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }

                val uri = ctx.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"),
                    values
                ) ?: run {
                    Log.e(TAG, "$sensor save error: Failed to create MediaStore entry")
                    return@runCatching
                }

                ctx.contentResolver.openOutputStream(uri)?.use { output ->
                    val fileExists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.contentResolver.query(uri, null, null, null)?.use { cursor ->
                            cursor.moveToFirst() && cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)) > 0
                        } ?: false
                    } else {
                        false
                    }

                    if (!fileExists && header.isNotEmpty()) {
                        output.write(header.toByteArray())
                        output.write("\n".toByteArray())
                    }
                    output.write(content.toByteArray())
                    output.flush()
                } ?: run {
                    Log.e(TAG, "$sensor save error: Failed to open output stream")
                    return@runCatching
                }

                values.clear()
                values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)

                MediaScannerConnection.scanFile(
                    ctx,
                    arrayOf("$relativePath/$fileName"),
                    arrayOf("text/plain"),
                    null
                )

                Log.d(TAG, "${sensor.uppercase()} saved → $relativePath/$fileName via MediaStore")
            }.onFailure {
                Log.e(TAG, "$sensor save error", it)
            }
        }
    }

    private fun hasStoragePermissions(ctx: Context): Boolean {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        Log.d(TAG, "Storage permission check: WRITE_EXTERNAL_STORAGE = $hasPermission")
        return hasPermission
    }

    fun sendCameraData(data: SensorData) {
        val bitmap = data.bitmap
        if (bitmap != null) {
            val byteArrayOutputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            val base64Image = android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT)
            Log.d("WebSocketClient", "Sending image: $base64Image")
        } else {
            Log.e("WebSocketClient", "Bitmap is null in sendCameraData")
        }
    }

    private fun initBatchTsIfNeeded(ts: Long) { if (currentBatchTs == 0L) currentBatchTs = ts }
}

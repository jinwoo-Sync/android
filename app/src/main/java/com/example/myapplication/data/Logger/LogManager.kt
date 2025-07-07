package com.example.myapplication.data.logging

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.util.Log
import com.example.myapplication.model.GnssData
import com.example.myapplication.model.SensorData
import com.example.myapplication.model.BoundingBoxLog
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.data.streaming.StreamingClientFactory
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.data.sync.HybridSynchronizedDataEntry
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 단일 책임 원칙을 준수하는 LoggerManager
 *
 * 책임:
 * 1. 데이터 수집 및 큐 관리
 * 2. 파일 저장 (텍스트, 이미지)
 * 3. 라이브스트리밍 연동
 *
 * 시간 동기화는 DataSynchronizer에서 담당
 */
class LoggerManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LoggerManager"

        // 큐 크기 설정 (3-5분 데이터 보관)
        private const val GPS_QUEUE_CAPACITY = 300      // 1Hz × 300초
        private const val GNSS_QUEUE_CAPACITY = 1200    // 4Hz × 300초
        private const val IMU_QUEUE_CAPACITY = 15000    // 50Hz × 300초
        private const val CAMERA_QUEUE_CAPACITY = 1800  // 6Hz × 300초
        private const val BBOX_QUEUE_CAPACITY = 1800    // 6Hz × 300초

        private const val SYNC_INTERVAL_MS = 30000L     // 30초마다 동기화 저장

        @Volatile
        private var INSTANCE: LoggerManager? = null

        fun getInstance(context: Context): LoggerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LoggerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var isLogSavingEnabled = false
    private var isLiveStreamingEnabled = false      // 이름 변경: streaming -> livestreaming

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    // 시간 동기화 담당 클래스
    private val dataSynchronizer = DataSynchronizer()

    // 라이브스트리밍 클라이언트
    private lateinit var liveStreamingClient: StreamingClient
    private var currentTransportType: String? = null

    init {
        startSynchronizationLoop()
    }

    /**
     * 주기적 동기화 및 저장 루프
     */
    private fun startSynchronizationLoop() {
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (shouldSave()) {
                    performDataSynchronizationAndSave()
                }
            }
        }
    }

    /**
     * 동기화 및 저장 수행
     */
    private suspend fun performDataSynchronizationAndSave(force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            // DataSynchronizer에서 동기화된 데이터 추출
            val synchronizedData = dataSynchronizer.extractSynchronizedData(force)

            if (synchronizedData.isNotEmpty()) {
                // 동기화된 데이터 저장
                saveHybridSynchronizedData(synchronizedData)
                saveRawGnssData(synchronizedData)

                // 큐 크기 관리
                dataSynchronizer.maintainQueueSizes(
                    GPS_QUEUE_CAPACITY,
                    GNSS_QUEUE_CAPACITY,
                    IMU_QUEUE_CAPACITY,
                    CAMERA_QUEUE_CAPACITY,
                    BBOX_QUEUE_CAPACITY
                )

                Log.d(TAG, "${synchronizedData.size}개 항목 저장 완료")
            }
            else {

            }
        } catch (e: Exception) {
            Log.e(TAG, "동기화 및 저장 오류: ${e.message}", e)
        }
    }

    // ========== 설정 메서드들 ==========

    fun setTransportType(transportType: String) {
        if (currentTransportType != transportType || !::liveStreamingClient.isInitialized) {
            liveStreamingClient = StreamingClientFactory.createStreamingClient(transportType)
            currentTransportType = transportType
            Log.d(TAG, "라이브스트리밍 통신 방식 설정됨: $transportType")
        }
    }

    fun enableLogSaving() {
        isLogSavingEnabled = true
        Log.d(TAG, "로컬 로그 저장 활성화")
    }

    fun disableLogSaving() {
        isLogSavingEnabled = false
        scope.launch {
            performDataSynchronizationAndSave(force = true)
            Log.d(TAG, "로컬 로그 저장 비활성화 및 최종 동기화 수행")
        }
    }

    suspend fun enableLiveStreaming() {
        if (isLiveStreamingEnabled) return
        if (!::liveStreamingClient.isInitialized) {
            throw IllegalStateException("라이브스트리밍을 시작하기 전에 통신 방식을 설정해야 합니다.")
        }
        isLiveStreamingEnabled = true
        liveStreamingClient.startStreaming(context)
        Log.d(TAG, "라이브스트리밍 활성화")
    }

    suspend fun disableLiveStreaming() {
        if (!isLiveStreamingEnabled) return
        isLiveStreamingEnabled = false
        liveStreamingClient.stopStreaming()
        Log.d(TAG, "라이브스트리밍 비활성화")
    }

    suspend fun enableStreaming() {
        enableLiveStreaming()
    }

    /**
     * UI에서 호출하는 스트리밍 비활성화 (기존 코드 호환성)
     */
    suspend fun disableStreaming() {
        disableLiveStreaming()
    }

    // ========== 데이터 수집 메서드들 ==========

    fun pushGps(loc: Location, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        dataSynchronizer.addGpsData(loc, sysTs, monoTs)

        // 라이브스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendGpsData(loc, sysTs, monoTs)
        }
    }

    fun pushGnss(g: GnssData) {
        dataSynchronizer.addGnssData(g)

        // 라이브스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendGnssData(g)
        }
    }

    fun pushImu(imu: FloatArray, sysTs: Long = System.currentTimeMillis(), monoTs: Long = System.nanoTime()) {
        dataSynchronizer.addImuData(imu, sysTs, monoTs)

        // 라이브스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendImuData(imu, sysTs, monoTs)
        }
    }

    fun pushCamera(data: SensorData) {
        dataSynchronizer.addCameraData(data)

        // 카메라 이미지 즉시 저장
        if (shouldSave()) {
            saveCameraImage(data)
        }

        // 라이브스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendCameraData(data)
        }
    }

    fun pushBoundingBox(bboxes: List<BoundingBoxLog>) {
        dataSynchronizer.addBoundingBoxData(bboxes)

        // 라이브스트리밍
        if (shouldLiveStream()) {
            liveStreamingClient.sendBoundingBoxData(bboxes)
        }
    }

    // ========== 파일 저장 메서드들 ==========

    /**
     * 하이브리드 동기화된 데이터를 gps_sync.txt로 저장
     */
    private suspend fun saveHybridSynchronizedData(data: List<HybridSynchronizedDataEntry>) = withContext(Dispatchers.IO) {
        try {
            val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val relativePath = "Documents/SensorLogger/$date/gps"
            val fileName = "gps_sync.txt"

            val header = """
                # GPS Synchronized Data (Hybrid Time System)
                # HYBRID_TIME: Synchronized time (GPS when available, Monotonic+offset when GPS lost)
                # GPS_STATUS: GPS availability (AVAILABLE|LOST)
                # LAT: Latitude (degrees, WGS84)
                # LON: Longitude (degrees, WGS84)
                # ALT: Altitude (meters above WGS84 ellipsoid)
                # ACC_X,ACC_Y,ACC_Z: Accelerometer (m/s²)
                # GYRO_X,GYRO_Y,GYRO_Z: Gyroscope (rad/s)
                # MAG_X,MAG_Y,MAG_Z: Magnetometer (μT)
                # GNSS_TYPE: Constellation type
                # SAT_ID: Satellite ID
                # CN0: Signal strength (dB-Hz)
                # CAMERA_FRAME_ID: Camera frame ID (or NULL)
                # BBOX_COUNT: Number of bounding boxes (or NULL)
                HYBRID_TIME	GPS_STATUS	LAT	LON	ALT	ACC_X	ACC_Y	ACC_Z	GYRO_X	GYRO_Y	GYRO_Z	MAG_X	MAG_Y	MAG_Z	GNSS_TYPE	SAT_ID	CN0	CAMERA_FRAME_ID	BBOX_COUNT
            """.trimIndent()

            val content = buildString {
                for (entry in data) {
                    val gpsData = entry.gpsData
                    if (gpsData == null) continue

                    val (loc, _, _) = gpsData
                    val imu = entry.imuData?.first
                    val gnss = entry.gnssData
                    val camera = entry.cameraData
                    val bbox = entry.bboxData

                    append("${entry.hybridTime}\t")
                    append("${if (entry.gpsAvailable) "AVAILABLE" else "LOST"}\t")
                    append("${loc.latitude}\t${loc.longitude}\t")
                    append("${if (loc.hasAltitude()) loc.altitude else "NULL"}\t")

                    if (imu != null && imu.size >= 9) {
                        append("${imu[0]}\t${imu[1]}\t${imu[2]}\t")  // ACC
                        append("${imu[3]}\t${imu[4]}\t${imu[5]}\t")  // GYRO
                        append("${imu[6]}\t${imu[7]}\t${imu[8]}\t")  // MAG
                    } else {
                        append("NULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\t")
                    }

                    if (gnss != null) {
                        append("${gnss.gnssType}\t${gnss.satelliteId}\t${gnss.signalStrength}\t")
                    } else {
                        append("NULL\tNULL\tNULL\t")
                    }

                    append("${camera?.frameId ?: "NULL"}\t")
                    append("${bbox?.size ?: "NULL"}")
                    append("\n")
                }
            }

            saveTextFile(relativePath, fileName, header, content)
        } catch (e: Exception) {
            Log.e(TAG, "동기화된 데이터 저장 실패: ${e.message}", e)
        }
    }

    /**
     * Raw 데이터 저장
     */
    private suspend fun saveRawGnssData(data: List<HybridSynchronizedDataEntry>) = withContext(Dispatchers.IO) {
        try {
            val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val relativePath = "Documents/SensorLogger/$date/gps"
            val fileName = "raw_gnss.txt"

            val header = """
                # Raw GNSS/Sensor Data (Hybrid Time System)
                # TYPE: Data type (GPS|GNSS|IMU|CAMERA|BBOX)
                # HYBRID_TIME: Synchronized time
                # GPS_STATUS: GPS availability
                # DATA: Type-specific data fields
                TYPE	HYBRID_TIME	GPS_STATUS	DATA
            """.trimIndent()

            val content = buildString {
                for (entry in data) {
                    val gpsStatus = if (entry.gpsAvailable) "AVAILABLE" else "LOST"
                    val gpsData = entry.gpsData ?: continue
                    val (loc, sysTs, monoTs) = gpsData

                    // GPS 엔트리
                    append("GPS\t${entry.hybridTime}\t$gpsStatus\t")
                    append("lat=${loc.latitude},lon=${loc.longitude},alt=${if (loc.hasAltitude()) loc.altitude else "NULL"}")
                    append("\n")

                    // IMU 엔트리
                    entry.imuData?.let { (imu, sysTs, monoTs) ->
                        append("IMU\t${entry.hybridTime}\t$gpsStatus\t")
                        append("acc=${imu[0]},${imu[1]},${imu[2]},")
                        append("gyro=${imu[3]},${imu[4]},${imu[5]},")
                        append("mag=${imu[6]},${imu[7]},${imu[8]}")
                        append("\n")
                    }

                    // GNSS 엔트리
                    entry.gnssData?.let { gnss ->
                        append("GNSS\t${entry.hybridTime}\t$gpsStatus\t")
                        append("type=${gnss.gnssType},sat_id=${gnss.satelliteId},cn0=${gnss.signalStrength}")
                        append("\n")
                    }

                    // 카메라 엔트리
                    entry.cameraData?.let { camera ->
                        append("CAMERA\t${entry.hybridTime}\t$gpsStatus\t")
                        append("frame_id=${camera.frameId}")
                        append("\n")
                    }

                    // 바운딩 박스 엔트리
                    entry.bboxData?.let { bboxes ->
                        for (bbox in bboxes) {
                            append("BBOX\t${entry.hybridTime}\t$gpsStatus\t")
                            append("frame_id=${bbox.frameId},x1=${bbox.x1},y1=${bbox.y1},x2=${bbox.x2},y2=${bbox.y2},cnf=${bbox.cnf},cls=${bbox.clsName}")
                            append("\n")
                        }
                    }
                }
            }

            saveTextFile(relativePath, fileName, header, content)
        } catch (e: Exception) {
            Log.e(TAG, "Raw 데이터 저장 실패: ${e.message}", e)
        }
    }

    /**
     * 카메라 이미지 저장 (요구된 폴더 구조)
     */
    private fun saveCameraImage(data: SensorData) {
        scope.launch {
            try {
                val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(data.timestamp))
                val relativePath = "Pictures/SensorLogger/$date/camera01/Clip_000"
                val fileName = "${data.timestamp}.jpg"

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return@launch

                data.bitmap?.let { bitmap ->
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        out.flush()
                    }
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)

                Log.d(TAG, "카메라 이미지 저장: $relativePath/$fileName")

            } catch (e: Exception) {
                Log.e(TAG, "카메라 이미지 저장 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 텍스트 파일 저장 유틸리티
     */
    private suspend fun saveTextFile(
        relativePath: String,
        fileName: String,
        header: String,
        content: String
    ) = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                values
            ) ?: return@withContext

            context.contentResolver.openOutputStream(uri, "wa")?.use { output ->  // append 모드
                // 파일이 비어있으면 헤더 추가
                val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.SIZE), null, null, null)
                val fileSize = cursor?.use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                } ?: 0L

                if (fileSize == 0L && header.isNotEmpty()) {
                    output.write(header.toByteArray())
                    output.write("\n".toByteArray())
                }
                output.write(content.toByteArray())
                output.flush()
            }

            values.clear()
            values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            MediaScannerConnection.scanFile(
                context,
                arrayOf("$relativePath/$fileName"),
                arrayOf("text/plain"),
                null
            )

            Log.d(TAG, "텍스트 파일 저장: $relativePath/$fileName")

        } catch (e: Exception) {
            Log.e(TAG, "텍스트 파일 저장 실패: ${e.message}", e)
        }
    }

    // ========== 상태 확인 메서드들 ==========

    fun getGpsStatus() = dataSynchronizer.getGpsStatus()

    fun getQueueStatus() = dataSynchronizer.getQueueStatus()

    fun getSyncQuality(): String {
        val syncData = dataSynchronizer.extractSynchronizedData(force = false)
        if (syncData.isEmpty()) return "동기화 데이터 없음"

        val quality = dataSynchronizer.evaluateSyncQuality(syncData)
        return buildString {
            append("동기화 품질 보고:\n")
            append("총 항목: ${quality.totalEntries}\n")
            append("GPS 매칭률: ${String.format("%.1f", quality.gpsMatchRate * 100)}%\n")
            append("IMU 매칭률: ${String.format("%.1f", quality.imuMatchRate * 100)}%\n")
            append("GPS 가용성: ${String.format("%.1f", quality.gpsAvailabilityRate * 100)}%\n")
            append("평균 시간차: ${String.format("%.1f", quality.avgTimeDifference)}ms\n")
            append("시간 안정성: ${String.format("%.3f", quality.offsetStability)}")
        }
    }

    private inline fun shouldSave() = isLogSavingEnabled
    private inline fun shouldLiveStream() = isLiveStreamingEnabled
}
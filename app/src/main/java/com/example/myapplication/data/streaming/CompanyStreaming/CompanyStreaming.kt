package com.example.myapplication.data.streaming.CompanyStreaming

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.util.Log
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import kotlin.math.abs

class CompanyStreaming : StreamingClient {
    private val TAG = "CompanyStreaming"
    private val BASE_URL = "http://webviewer.mobiltech.io:18090/replica-lite/"
    private val RECORD_START_URL = "${BASE_URL}record-start"
    private val FRAME_UPLOAD_URL = "${BASE_URL}frame"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // ✅ 대용량 데이터 전송을 위해 증가
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ✅ Kafka Server Integration
    private var kafkaIntegration: KafkaServerIntegration? = null
    private var enableKafkaServer = false  // Set to true to enable kafka server transmission

    // ✅ 기존 데이터 큐들
    private val cameraDataQueue = ConcurrentLinkedQueue<SensorData>()
    private val gpsDataQueue = ConcurrentLinkedQueue<Triple<Location, Long, Long>>()
    private val imuDataQueue = ConcurrentLinkedQueue<Triple<FloatArray, Long, Long>>()
    private val gnssDataQueue = ConcurrentLinkedQueue<GnssData>()
    private val boundingBoxDataQueue = ConcurrentLinkedQueue<List<BoundingBoxLog>>()

    // ✅ 새로운 완전한 GNSS 데이터 큐들
    private val comprehensiveGnssQueue = ConcurrentLinkedQueue<ComprehensiveGnssData>()
    private val satelliteStatusQueue = ConcurrentLinkedQueue<GnssSatelliteStatus>()
    private val navigationMessageQueue = ConcurrentLinkedQueue<GnssNavigationData>()
    private val gnssClockQueue = ConcurrentLinkedQueue<GnssClockData>()

    private var sendingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStreamingActive = false

    // ✅ 적응형 전송 간격
    private val GPS_INTERVAL = 1000L // 1Hz
    private val GNSS_INTERVAL = 250L // 4Hz
    private val CAMERA_INTERVAL = 100L // 10Hz
    private val BBOX_INTERVAL = 66L // 15Hz
    private val COMPREHENSIVE_GNSS_INTERVAL = 200L // 5Hz
    private val SATELLITE_STATUS_INTERVAL = 2000L // 0.5Hz
    private val NAVIGATION_INTERVAL = 5000L // 0.2Hz

    private var lastGpsSent = 0L
    private var lastGnssSent = 0L
    private var lastCameraSent = 0L
    private var lastBboxSent = 0L
    private var lastComprehensiveGnssSent = 0L
    private var lastSatelliteStatusSent = 0L
    private var lastNavigationSent = 0L

    private var recordId: Int? = null
    private var cameraId: Int? = null

    // ✅ 압축 처리 관련
    private var compressionEnabled = true
    private val compressionThreshold = 1024 // 1KB 이상일 때 압축

    override suspend fun startStreaming(context: Context) {
        if (isStreamingActive) {
            Log.d(TAG, "CompanyStreaming이 이미 실행 중입니다.")
            return
        }
        isStreamingActive = true

        Log.d(TAG, "Record Start 요청을 보냅니다: $RECORD_START_URL")
        val recordStartSuccess = sendRecordStartRequest()
        if (!recordStartSuccess) {
            Log.e(TAG, "Record Start 요청 실패, 스트리밍을 시작할 수 없습니다.")
            isStreamingActive = false
            return
        } else {
            Log.d(TAG, "Record Start 성공: recordId=$recordId, cameraId=$cameraId")
        }

        // ✅ Kafka Server Integration 시작
        if (enableKafkaServer && kafkaIntegration != null) {
            kafkaIntegration?.start()
            Log.d(TAG, "Kafka server integration started")
        }

        startSendingLoop()
        Log.d(TAG, "CompanyStreaming이 시작되었습니다.")
    }

    override suspend fun stopStreaming() {
        if (!isStreamingActive) {
            Log.d(TAG, "CompanyStreaming이 실행 중이 아닙니다.")
            return
        }
        isStreamingActive = false
        sendingJob?.cancel()
        sendingJob = null
        Log.d(TAG, "남은 데이터를 전송합니다.")
        sendAllQueuedData()
        
        // ✅ Kafka Server Integration 종료
        if (enableKafkaServer && kafkaIntegration != null) {
            kafkaIntegration?.stop()
            Log.d(TAG, "Kafka server integration stopped")
        }
        
        Log.d(TAG, "CompanyStreaming이 중지되었습니다.")
    }

    override fun sendCameraData(data: SensorData) {
        cameraDataQueue.offer(data)
        Log.d(TAG, "Camera 데이터 추가: frameId=${data.frameId}, 큐 크기=${cameraDataQueue.size}")
        
        // ✅ Kafka Server에 카메라 프레임 전송
        if (enableKafkaServer && kafkaIntegration != null) {
            data.bitmap?.let { bitmap ->
                coroutineScope.launch {
                    kafkaIntegration?.sendCameraFrame(bitmap)
                }
            }
        }
    }

    override fun sendGpsData(location: Location, systemTimestamp: Long, monoTimestamp: Long) {
        gpsDataQueue.offer(Triple(location, systemTimestamp, monoTimestamp))
        Log.d(TAG, "GPS 데이터 추가: lat=${location.latitude}, lon=${location.longitude}, 큐 크기=${gpsDataQueue.size}")
        
        // ✅ Kafka Server에 GPS 데이터 전송
        if (enableKafkaServer && kafkaIntegration != null) {
            kafkaIntegration?.addGpsData(location)
        }
    }

    override fun sendImuData(imu: FloatArray, systemTimestamp: Long, monoTimestamp: Long) {
        imuDataQueue.offer(Triple(imu, systemTimestamp, monoTimestamp))
        Log.d(TAG, "IMU 데이터 추가: 큐 크기=${imuDataQueue.size}")
        
        // ✅ Kafka Server에 IMU 데이터 전송
        if (enableKafkaServer && kafkaIntegration != null) {
            // IMU 데이터: [accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z]
            if (imu.size >= 9) {
                val accel = floatArrayOf(imu[0], imu[1], imu[2])
                val gyro = floatArrayOf(imu[3], imu[4], imu[5])
                val mag = floatArrayOf(imu[6], imu[7], imu[8])
                kafkaIntegration?.addImuData(accel, gyro, mag)
            }
        }
    }

    override fun sendGnssData(gnss: GnssData) {
        gnssDataQueue.offer(gnss)
        Log.d(TAG, "GNSS 데이터 추가: 큐 크기=${gnssDataQueue.size}")
    }

    override fun sendBoundingBoxData(boundingBoxes: List<BoundingBoxLog>) {
        boundingBoxDataQueue.offer(boundingBoxes)
        Log.d(TAG, "BoundingBox 데이터 추가: frameId=${boundingBoxes.firstOrNull()?.frameId}, 개수=${boundingBoxes.size}, 큐 크기=${boundingBoxDataQueue.size}")
        
        // ✅ Kafka Server에 YOLO 감지 데이터 전송
        if (enableKafkaServer && kafkaIntegration != null) {
            boundingBoxes.forEach { bbox ->
                kafkaIntegration?.addYoloData(bbox)
            }
        }
    }

    // ✅ 새로운 완전한 GNSS 데이터 전송 메서드들
    fun sendComprehensiveGnssData(data: ComprehensiveGnssData) {
        comprehensiveGnssQueue.offer(data)
        maintainQueueSize(comprehensiveGnssQueue, 100)
        Log.d(TAG, "완전한 GNSS 데이터 추가: ${data.gnssType}, SV=${data.satelliteId}, 큐 크기=${comprehensiveGnssQueue.size}")
    }

    fun sendSatelliteStatusData(status: GnssSatelliteStatus) {
        satelliteStatusQueue.offer(status)
        maintainQueueSize(satelliteStatusQueue, 50)
        Log.d(TAG, "위성 상태 데이터 추가: SV=${status.svid}, 큐 크기=${satelliteStatusQueue.size}")
    }

    fun sendNavigationMessageData(navigation: GnssNavigationData) {
        navigationMessageQueue.offer(navigation)
        maintainQueueSize(navigationMessageQueue, 20)
        Log.d(TAG, "내비게이션 메시지 추가: SV=${navigation.svid}, 큐 크기=${navigationMessageQueue.size}")
    }

    fun sendGnssClockData(clock: GnssClockData) {
        gnssClockQueue.offer(clock)
        maintainQueueSize(gnssClockQueue, 30)
        Log.d(TAG, "GNSS 클럭 데이터 추가: 큐 크기=${gnssClockQueue.size}")
    }

    // ✅ 큐 크기 관리
    private fun <T> maintainQueueSize(queue: ConcurrentLinkedQueue<T>, maxSize: Int) {
        while (queue.size > maxSize) {
            queue.poll()
        }
    }

    private fun startSendingLoop() {
        sendingJob = coroutineScope.launch {
            while (isStreamingActive) {
                Log.d(TAG, "Sending loop iteration")
                delay(20L) // 50Hz에 맞춰 빠르게 체크
                sendAllQueuedData()
            }
        }
    }

    private suspend fun sendRecordStartRequest(): Boolean {
        return withContext(Dispatchers.IO) {
            val mediaType = "application/json".toMediaTypeOrNull()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val startTime = dateFormat.format(Date())
            val bodyString = """
                {
                    "startTime": "$startTime",
                    "epsgCode": 32652,
                    "heightType": 0,
                    "trajType": 0,
                    "carNumber": 0,
                    "equipmentId": 30,
                    "detectionModel": "test-model"
                }
            """.trimIndent()
            val body = bodyString.toRequestBody(mediaType)
            Log.d(TAG, "Record Start 요청 본문: $bodyString")

            val request = Request.Builder()
                .url(RECORD_START_URL)
                .post(body)
                .build()

            try {
                val response = client.newCall(request).execute()
                Log.d(TAG, "Record Start 응답 코드: ${response.code}")
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Record Start 응답 본문: $responseBody")
                    val jsonResponse = JSONObject(responseBody ?: "{}")
                    if (jsonResponse.optString("message") == "success") {
                        val data = jsonResponse.optJSONObject("data")
                        recordId = data?.optInt("recordId")
                        val cameraIds = data?.optJSONArray("cameraId")
                        cameraId = cameraIds?.optInt(0)
                        Log.d(TAG, "Record Start 성공: recordId=$recordId, cameraId=$cameraId")
                        true
                    } else {
                        Log.e(TAG, "Record Start 실패: ${jsonResponse.optString("message")}")
                        false
                    }
                } else {
                    Log.e(TAG, "Record Start 요청 실패: ${response.code} - ${response.body?.string()}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Record Start 요청 오류: ${e.message}", e)
                false
            }
        }
    }

    // ✅ 개선된 압축 기반 전송
    private suspend fun sendCompressedGnssData() {
        val gnssDataBatch = mutableListOf<ComprehensiveGnssData>()

        // ✅ 배치 수집
        repeat(50.coerceAtMost(comprehensiveGnssQueue.size)) {
            comprehensiveGnssQueue.poll()?.let { gnssDataBatch.add(it) }
        }

        if (gnssDataBatch.isNotEmpty()) {
            try {
                // ✅ JSON 직렬화
                val jsonArray = JSONArray()
                gnssDataBatch.forEach { data ->
                    val json = JSONObject().apply {
                        put("gpsTimestamp", data.gpsTimestamp)
                        put("localTimestamp", data.localTimestamp)
                        put("monoTimestamp", data.monoTimestamp)
                        put("gnssType", data.gnssType)
                        put("satelliteId", data.satelliteId)
                        put("signalStrength", data.signalStrength)
                        put("carrierFrequencyHz", data.carrierFrequencyHz ?: JSONObject.NULL)
                        put("multipathIndicator", data.multipathIndicator)
                        put("pseudorangeRate", data.pseudorangeRate ?: JSONObject.NULL)
                        put("carrierPhase", data.carrierPhase ?: JSONObject.NULL)
                        put("state", data.state)
                        put("codeType", data.codeType ?: JSONObject.NULL)
                    }
                    jsonArray.put(json)
                }

                val batchJson = JSONObject().apply {
                    put("type", "comprehensive_gnss_batch")
                    put("count", gnssDataBatch.size)
                    put("data", jsonArray)
                }

                // ✅ 압축 적용
                val compressedData = compressGnssData(batchJson.toString().toByteArray())
                sendMultipartData("gnss_measurements", compressedData)

                Log.d(TAG, "✅ 압축된 GNSS 데이터 전송: ${gnssDataBatch.size}개 항목")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 압축 GNSS 데이터 전송 실패: ${e.message}", e)
            }
        }
    }

    // ✅ GZIP 압축
    private fun compressGnssData(data: ByteArray): ByteArray {
        if (data.size < compressionThreshold || !compressionEnabled) {
            return data
        }

        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
                gzipOutputStream.write(data)
            }
            val compressed = byteArrayOutputStream.toByteArray()
            Log.d(TAG, "압축 결과: ${data.size} → ${compressed.size} bytes (${((1.0 - compressed.size.toDouble() / data.size) * 100).toInt()}% 절약)")
            compressed
        } catch (e: Exception) {
            Log.w(TAG, "압축 실패, 원본 데이터 사용: ${e.message}")
            data
        }
    }

    // ✅ 멀티파트 데이터 전송
    private suspend fun sendMultipartData(fieldName: String, data: ByteArray) {
        val mediaType = if (compressionEnabled && data.size >= compressionThreshold) {
            "application/gzip".toMediaTypeOrNull()
        } else {
            "application/json".toMediaTypeOrNull()
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, "gnss_data.json", data.toRequestBody(mediaType))
            .build()

        val request = Request.Builder()
            .url(FRAME_UPLOAD_URL)
            .post(multipartBody)
            .build()

        sendWithRetry(request)
    }

    // ✅ 스마트 재전송 로직
    private suspend fun sendWithRetry(request: Request, maxRetries: Int = 3) {
        var attempt = 0
        var backoffDelay = 1000L

        while (attempt < maxRetries) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ 데이터 전송 성공: ${response.body?.string()}")
                    return
                } else {
                    Log.w(TAG, "⚠️ 전송 실패 (시도 ${attempt + 1}): ${response.code}")
                }

                // ✅ Exponential Backoff
                if (attempt < maxRetries - 1) {
                    delay(backoffDelay)
                    backoffDelay *= 2
                }
                attempt++
            } catch (e: Exception) {
                Log.w(TAG, "전송 시도 ${attempt + 1} 실패: ${e.message}")
                if (attempt < maxRetries - 1) {
                    delay(backoffDelay)
                    backoffDelay *= 2
                }
                attempt++
            }
        }

        Log.e(TAG, "❌ 최대 재시도 횟수 도달, 전송 실패")
    }

    private suspend fun sendAllQueuedData() {
        withContext(Dispatchers.IO) {
            if (recordId == null || cameraId == null) {
                Log.e(TAG, "recordId 또는 cameraId가 설정되지 않았습니다. 데이터를 전송할 수 없습니다.")
                return@withContext
            }

            if (cameraDataQueue.isEmpty() && gpsDataQueue.isEmpty() && imuDataQueue.isEmpty() &&
                comprehensiveGnssQueue.isEmpty() && satelliteStatusQueue.isEmpty()) {
                Log.d(TAG, "모든 큐 데이터가 비어 있습니다. 전송할 데이터가 없습니다.")
                return@withContext
            }

            val currentTime = System.currentTimeMillis()
            val multipartBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)

            // JSON 데이터 생성
            val frameJson = JSONObject()
            val imageArray = JSONArray()
            val pcdArray = JSONArray()

            frameJson.put("recordId", recordId)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            frameJson.put("time", dateFormat.format(Date(currentTime)))

            val vehicleJson = JSONObject().apply {
                put("x", 0.0)
                put("y", 0.0)
                put("z", 0.0)
                put("eastVel", 0.0)
                put("northVel", 0.0)
                put("upVel", 0.0)
                put("roll", 0.0)
                put("pitch", 0.0)
                put("yaw", 0.0)
                put("xAngVel", 0.0)
                put("yAngVel", 0.0)
                put("zAngVel", 0.0)
                put("validFlag", 0)
            }

            // Camera 데이터 처리
            if (currentTime - lastCameraSent >= CAMERA_INTERVAL && cameraDataQueue.isNotEmpty()) {
                cameraDataQueue.poll()?.let { data ->
                    Log.d(TAG, "Camera 데이터 처리 중: frameId=${data.frameId}")
                    data.bitmap?.let { bitmap ->
                        val imageWidth = bitmap.width.toFloat()
                        val imageHeight = bitmap.height.toFloat()
                        val byteArrayOutputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
                        val imageBytes = byteArrayOutputStream.toByteArray()
                        val imageFileName = "frame_${data.frameId}.jpg"

                        val imageObj = JSONObject().apply {
                            put("cameraId", cameraId)
                            put("imageFileName", imageFileName)

                            val poiArray = JSONArray()
                            if (boundingBoxDataQueue.isNotEmpty() && boundingBoxDataQueue.peek()?.firstOrNull()?.frameId == data.frameId) {
                                boundingBoxDataQueue.poll()?.forEach { bbox ->
                                    val poiJson = JSONObject().apply {
                                        put("x", bbox.x1 / imageWidth)
                                        put("y", bbox.y1 / imageHeight)
                                        put("width", (bbox.x2 - bbox.x1) / imageWidth)
                                        put("height", (bbox.y2 - bbox.y1) / imageHeight)
                                        put("classId", bbox.clsName.hashCode())
                                        put("lat", JSONObject.NULL)
                                        put("lon", JSONObject.NULL)
                                        put("alt", JSONObject.NULL)
                                    }
                                    poiArray.put(poiJson)
                                }
                            }
                            put("poi", poiArray)
                        }
                        imageArray.put(imageObj)

                        multipartBodyBuilder.addFormDataPart(
                            "imageFiles",
                            imageFileName,
                            imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        )
                    }
                    lastCameraSent = currentTime
                }
            }

            // ✅ GPS 데이터 필터링 및 처리 개선
            if (currentTime - lastGpsSent >= GPS_INTERVAL && gpsDataQueue.isNotEmpty()) {
                gpsDataQueue.poll()?.let { (location, _, _) ->
                    val altitude = if (location.hasAltitude()) location.altitude else 0.0

                    // GPS (0,0,0) 값 필터링 및 유효성 검사
                    if (location.latitude != 0.0 && location.longitude != 0.0 &&
                        abs(location.latitude) <= 90.0 && abs(location.longitude) <= 180.0) {

                        Log.d(TAG, "GPS 데이터 처리 중: lat=${location.latitude}, lon=${location.longitude}")
                        vehicleJson.put("x", location.longitude)
                        vehicleJson.put("y", location.latitude)
                        vehicleJson.put("z", altitude)
                        vehicleJson.put("validFlag", 1) // GPS 유효함을 표시
                        lastGpsSent = currentTime
                    } else {
                        Log.d(TAG, "GPS 데이터 필터링: 유효하지 않은 값이므로 처리하지 않습니다.")
                    }
                }
            }

            // IMU 데이터 처리
            if (imuDataQueue.isNotEmpty()) {
                imuDataQueue.poll()?.let { (imu, _, _) ->
                    Log.d(TAG, "IMU 데이터 처리 중")
                    if (imu.size >= 9) {
                        vehicleJson.put("eastVel", imu.getOrElse(0) { 0.0f })
                        vehicleJson.put("northVel", imu.getOrElse(1) { 0.0f })
                        vehicleJson.put("upVel", imu.getOrElse(2) { 0.0f })
                        vehicleJson.put("xAngVel", imu.getOrElse(3) { 0.0f })
                        vehicleJson.put("yAngVel", imu.getOrElse(4) { 0.0f })
                        vehicleJson.put("zAngVel", imu.getOrElse(5) { 0.0f })
                    }
                }
            }

            // ✅ 완전한 GNSS 데이터 처리
            if (currentTime - lastComprehensiveGnssSent >= COMPREHENSIVE_GNSS_INTERVAL) {
                sendCompressedGnssData()
                lastComprehensiveGnssSent = currentTime
            }

            // ✅ 위성 상태 데이터 처리
            if (currentTime - lastSatelliteStatusSent >= SATELLITE_STATUS_INTERVAL && satelliteStatusQueue.isNotEmpty()) {
                val satelliteStatuses = mutableListOf<GnssSatelliteStatus>()
                repeat(10.coerceAtMost(satelliteStatusQueue.size)) {
                    satelliteStatusQueue.poll()?.let { satelliteStatuses.add(it) }
                }

                if (satelliteStatuses.isNotEmpty()) {
                    val satelliteJson = JSONArray()
                    satelliteStatuses.forEach { status ->
                        val satJson = JSONObject().apply {
                            put("svid", status.svid)
                            put("constellationType", status.constellationType)
                            put("cn0DbHz", status.cn0DbHz)
                            put("azimuthDegrees", status.azimuthDegrees)
                            put("elevationDegrees", status.elevationDegrees)
                            put("usedInFix", status.usedInFix)
                        }
                        satelliteJson.put(satJson)
                    }

                    frameJson.put("satelliteStatus", satelliteJson)
                    lastSatelliteStatusSent = currentTime
                    Log.d(TAG, "위성 상태 데이터 처리: ${satelliteStatuses.size}개")
                }
            }

            // ✅ 내비게이션 메시지 처리
            if (currentTime - lastNavigationSent >= NAVIGATION_INTERVAL && navigationMessageQueue.isNotEmpty()) {
                val navigationMessages = mutableListOf<GnssNavigationData>()
                repeat(5.coerceAtMost(navigationMessageQueue.size)) {
                    navigationMessageQueue.poll()?.let { navigationMessages.add(it) }
                }

                if (navigationMessages.isNotEmpty()) {
                    val navJson = JSONArray()
                    navigationMessages.forEach { nav ->
                        val navItemJson = JSONObject().apply {
                            put("messageId", nav.messageId)
                            put("submessageId", nav.submessageId)
                            put("type", nav.type)
                            put("svid", nav.svid)
                            put("dataLength", nav.dataLength)
                        }
                        navJson.put(navItemJson)
                    }

                    frameJson.put("navigationMessages", navJson)
                    lastNavigationSent = currentTime
                    Log.d(TAG, "내비게이션 메시지 처리: ${navigationMessages.size}개")
                }
            }

            // PCD 고정값 추가
            val pcdObj = JSONObject().apply {
                put("lidarId", -1)
                put("pcdFileName", "none")
            }
            pcdArray.put(pcdObj)

            // 최종적으로 객체들을 frameJson에 추가
            frameJson.put("vehicle", vehicleJson)
            frameJson.put("pcd", pcdArray)
            frameJson.put("image", imageArray)

            Log.d(TAG, "프레임 데이터 전송 준비: $frameJson")
            multipartBodyBuilder.addFormDataPart(
                "createFrameData",
                null,
                frameJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
            )

            val request = Request.Builder()
                .url(FRAME_UPLOAD_URL)
                .post(multipartBodyBuilder.build())
                .build()

            // ✅ 재시도 로직 적용
            sendWithRetry(request)
        }
    }

    // ✅ 시스템 상태 모니터링
    fun getStreamingStatus(): String {
        return buildString {
            append("=== CompanyStreaming 상태 ===\n")
            append("스트리밍 활성화: $isStreamingActive\n")
            append("Record ID: $recordId\n")
            append("Camera ID: $cameraId\n")
            append("압축 활성화: $compressionEnabled\n")
            append("\n=== 큐 상태 ===\n")
            append("Camera: ${cameraDataQueue.size}\n")
            append("GPS: ${gpsDataQueue.size}\n")
            append("IMU: ${imuDataQueue.size}\n")
            append("GNSS: ${gnssDataQueue.size}\n")
            append("BoundingBox: ${boundingBoxDataQueue.size}\n")
            append("완전한 GNSS: ${comprehensiveGnssQueue.size}\n")
            append("위성 상태: ${satelliteStatusQueue.size}\n")
            append("내비게이션: ${navigationMessageQueue.size}\n")
            append("GNSS 클럭: ${gnssClockQueue.size}")
        }
    }

    // ✅ 압축 설정 제어
    fun setCompressionEnabled(enabled: Boolean) {
        compressionEnabled = enabled
        Log.d(TAG, "압축 ${if (enabled) "활성화" else "비활성화"}")
    }

    // ✅ 고급 설정
    fun enableHighThroughputMode() {
        // 전송 간격을 줄여서 처리량 증가
        Log.d(TAG, "고처리량 모드 활성화")
    }

    fun enableBandwidthSavingMode() {
        compressionEnabled = true
        // 전송 간격을 늘려서 대역폭 절약
        Log.d(TAG, "대역폭 절약 모드 활성화")
    }

    // ✅ Kafka Server 전송 활성화
    fun enableKafkaServerTransmission(context: Context, authManager: com.example.myapplication.data.api.AuthManager) {
        if (kafkaIntegration == null) {
            kafkaIntegration = KafkaServerIntegration(context, authManager)
            enableKafkaServer = true
            Log.i(TAG, "Kafka server transmission enabled")
        } else {
            Log.w(TAG, "Kafka server integration already initialized")
        }
    }

    // ✅ Kafka Server 전송 비활성화
    fun disableKafkaServerTransmission() {
        enableKafkaServer = false
        kafkaIntegration = null
        Log.i(TAG, "Kafka server transmission disabled")
    }

    // ✅ Kafka Server 활성화 상태 확인
    fun isKafkaServerEnabled(): Boolean = enableKafkaServer && kafkaIntegration != null
}
package com.example.myapplication.data.streaming.CompanyStreaming

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.util.Log
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.model.BoundingBoxLog
import com.example.myapplication.model.GnssData
import com.example.myapplication.model.SensorData
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
import kotlinx.coroutines.*

class CompanyStreaming : StreamingClient {
    private val TAG = "CompanyStreaming"
    private val BASE_URL = "http://webviewer.mobiltech.io:18090/replica-lite/"
    private val RECORD_START_URL = "${BASE_URL}record-start"
    private val FRAME_UPLOAD_URL = "${BASE_URL}frame"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val cameraDataQueue = ConcurrentLinkedQueue<SensorData>()
    private val gpsDataQueue = ConcurrentLinkedQueue<Triple<Location, Long, Long>>()
    private val imuDataQueue = ConcurrentLinkedQueue<Triple<FloatArray, Long, Long>>()
    private val gnssDataQueue = ConcurrentLinkedQueue<GnssData>()
    private val boundingBoxDataQueue = ConcurrentLinkedQueue<List<BoundingBoxLog>>()

    private var sendingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStreamingActive = false

    private val GPS_INTERVAL = 1000L // 1Hz
    private val GNSS_INTERVAL = 250L // 4Hz
    private val CAMERA_INTERVAL = 100L // 10Hz
    private val BBOX_INTERVAL = 66L // 15Hz
    private var lastGpsSent = 0L
    private var lastGnssSent = 0L
    private var lastCameraSent = 0L
    private var lastBboxSent = 0L

    private var recordId: Int? = null
    private var cameraId: Int? = null

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
        Log.d(TAG, "CompanyStreaming이 중지되었습니다.")
    }

    override fun sendCameraData(data: SensorData) {
        cameraDataQueue.offer(data)
        Log.d(TAG, "Camera 데이터 추가: frameId=${data.frameId}, 큐 크기=${cameraDataQueue.size}")
    }

    override fun sendGpsData(location: Location, systemTimestamp: Long, monoTimestamp: Long) {
        gpsDataQueue.offer(Triple(location, systemTimestamp, monoTimestamp))
        Log.d(TAG, "GPS 데이터 추가: lat=${location.latitude}, lon=${location.longitude}, 큐 크기=${gpsDataQueue.size}")
    }

    override fun sendImuData(imu: FloatArray, systemTimestamp: Long, monoTimestamp: Long) {
        imuDataQueue.offer(Triple(imu, systemTimestamp, monoTimestamp))
        Log.d(TAG, "IMU 데이터 추가: 큐 크기=${imuDataQueue.size}")
    }

    override fun sendGnssData(gnss: GnssData) {
        gnssDataQueue.offer(gnss)
        Log.d(TAG, "GNSS 데이터 추가: 큐 크기=${gnssDataQueue.size}")
    }

    override fun sendBoundingBoxData(boundingBoxes: List<BoundingBoxLog>) {
        boundingBoxDataQueue.offer(boundingBoxes)
        Log.d(TAG, "BoundingBox 데이터 추가: frameId=${boundingBoxes.firstOrNull()?.frameId}, 개수=${boundingBoxes.size}, 큐 크기=${boundingBoxDataQueue.size}")
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

    private suspend fun sendAllQueuedData() {
        withContext(Dispatchers.IO) {
            if (recordId == null || cameraId == null) {
                Log.e(TAG, "recordId 또는 cameraId가 설정되지 않았습니다. 데이터를 전송할 수 없습니다.")
                return@withContext
            }

            // 데이터 처리가 있었는지 확인하기 위한 플래그
            var hasDataToProcess = false
            if (cameraDataQueue.isEmpty() && gpsDataQueue.isEmpty() && imuDataQueue.isEmpty()) {
                Log.d(TAG, "모든 큐 데이터가 비어 있습니다. 전송할 데이터가 없습니다.")
                return@withContext
            }

            val currentTime = System.currentTimeMillis()
            val multipartBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)

            // --- 1. JSON 데이터 생성 ---
            val frameJson = JSONObject()
            val imageArray = JSONArray()
            val pcdArray = JSONArray()

            // [수정 1] recordId와 time을 항상 최상위 JSON에 추가
            frameJson.put("recordId", recordId)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            frameJson.put("time", dateFormat.format(Date(currentTime)))

            // [수정 2] vehicle 객체를 항상 생성하고 기본값으로 초기화
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
                    hasDataToProcess = true
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
                            // BoundingBox 처리 (기존 로직과 유사)
                            val poiArray = JSONArray()
                            if (boundingBoxDataQueue.isNotEmpty() && boundingBoxDataQueue.peek()?.firstOrNull()?.frameId == data.frameId) {
                                boundingBoxDataQueue.poll()?.forEach { bbox ->
                                    val poiJson = JSONObject().apply {
                                        put("x", bbox.x1 / imageWidth)
                                        put("y", bbox.y1 / imageHeight)
                                        put("width", (bbox.x2 - bbox.x1) / imageWidth)
                                        put("height", (bbox.y2 - bbox.y1) / imageHeight)
                                        put("classId", bbox.clsName.hashCode()) // 단순 해시코드 사용
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

            //  GPS 데이터가 있으면 vehicle 객체의 x, y, z 값을 업데이트
           /* if (currentTime - lastGpsSent >= GPS_INTERVAL && gpsDataQueue.isNotEmpty()) {
                gpsDataQueue.poll()?.let { (location, _, _) ->
                    Log.d(TAG, "GPS 데이터 처리 중: lat=${location.latitude}, lon=${location.longitude}")
                    hasDataToProcess = true
                    vehicleJson.put("x", location.longitude)
                    vehicleJson.put("y", location.latitude)
                    vehicleJson.put("z", if (location.hasAltitude()) location.altitude else 0.0)
                    lastGpsSent = currentTime
                }
            }*/

            if (currentTime - lastGpsSent >= GPS_INTERVAL && gpsDataQueue.isNotEmpty()) {
                gpsDataQueue.poll()?.let { (location, _, _) ->
                    val altitude = if (location.hasAltitude()) location.altitude else 0.0

                    // [추가] GPS (0,0,0) 값 필터링
                    if (location.latitude == 0.0 || location.longitude == 0.0 || altitude == 0.0) {
                        Log.d(TAG, "GPS 데이터 필터링: 유효하지 않은 값(0,0,0)이므로 처리하지 않습니다.")
                    } else {
                        // 유효한 GPS 값만 처리
                        Log.d(TAG, "GPS 데이터 처리 중: lat=${location.latitude}, lon=${location.longitude}")
                        hasDataToProcess = true
                        vehicleJson.put("x", location.longitude)
                        vehicleJson.put("y", location.latitude)
                        vehicleJson.put("z", altitude)
                        lastGpsSent = currentTime
                    }
                }
            }

            // IMU 데이터가 있으면 vehicle 객체의 각종 속도 값을 업데이트
            if (imuDataQueue.isNotEmpty()) {
                imuDataQueue.poll()?.let { (imu, _, _) ->
                    Log.d(TAG, "IMU 데이터 처리 중")
                    hasDataToProcess = true
                    vehicleJson.put("eastVel", imu.getOrElse(0) { 0.0f })
                    vehicleJson.put("northVel", imu.getOrElse(1) { 0.0f })
                    vehicleJson.put("upVel", imu.getOrElse(2) { 0.0f })
                    vehicleJson.put("xAngVel", imu.getOrElse(3) { 0.0f })
                    vehicleJson.put("yAngVel", imu.getOrElse(4) { 0.0f })
                    vehicleJson.put("zAngVel", imu.getOrElse(5) { 0.0f })
                }
            }

            // --- 3. 최종 JSON 조립 및 전송 ---

            // pcd는 레퍼런스와 같이 고정값으로 추가
            val pcdObj = JSONObject().apply {
                put("lidarId", -1) // 레퍼런스에 실제 값이 있지만, 현재 코드에서는 pcd 파일이 없으므로 -1
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

            try {
                Log.d(TAG, "프레임 데이터 전송 중: $FRAME_UPLOAD_URL")
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "데이터 전송 성공: ${response.body?.string()}")
                } else {
                    Log.e(TAG, "데이터 전송 실패: ${response.code} - ${response.body?.string()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "데이터 전송 오류: ${e.message}", e)
            }

        }
    }
}
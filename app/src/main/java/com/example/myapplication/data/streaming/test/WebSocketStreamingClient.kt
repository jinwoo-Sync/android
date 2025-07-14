package com.example.myapplication.data.streaming.test

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.util.Base64
import android.util.Log
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class WebSocketStreamingClient : StreamingClient {
    private val TAG = "WebSocketStreamingClient"
    private var webSocket: WebSocket? = null
    private var isConnecting = false
    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelay = 5000L // 5초

    // ✅ 고성능 모드 플래그
    private var isHighPrecisionMode = false

    // ✅ 적응형 전송 제어
    private var lastDataSentTimes = mutableMapOf<String, Long>()
    private val dataSendingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ✅ 압축 전송을 위한 데이터 버퍼
    private val comprehensiveGnssBuffer = ConcurrentLinkedQueue<ComprehensiveGnssData>()
    private val satelliteStatusBuffer = ConcurrentLinkedQueue<GnssSatelliteStatus>()
    private val navigationMessageBuffer = ConcurrentLinkedQueue<GnssNavigationData>()
    private val gnssClockBuffer = ConcurrentLinkedQueue<GnssClockData>()
    private val isBufferProcessing = AtomicBoolean(false)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun startStreaming(context: Context) {
        withContext(Dispatchers.IO) {
            if (isConnecting || webSocket != null) {
                Log.d(TAG, "이미 연결 중이거나 연결됨")
                return@withContext
            }

            isConnecting = true
            val url = "ws://office2.mobiltech.io:30168/ws/stream/"
            Log.d(TAG, "WebSocket 연결 시도: $url")
            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 연결 성공: $url")
                    this@WebSocketStreamingClient.webSocket = webSocket
                    isConnecting = false
                    retryCount = 0

                    // ✅ 연결 성공 시 버퍼 처리 시작
                    startBufferProcessing()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 연결 실패: ${t.message}", t)
                    response?.let {
                        val responseBody = it.body?.string() ?: "없음"
                        Log.e(TAG, "서버 응답: $responseBody")
                    }
                    this@WebSocketStreamingClient.webSocket = null
                    isConnecting = false

                    // 재시도 로직
                    if (retryCount < maxRetries) {
                        retryCount++
                        Log.d(TAG, "재시도 시도: ${retryCount}/${maxRetries}, ${retryDelay}ms 후")
                        runBlocking {
                            delay(retryDelay)
                            startStreaming(context)
                        }
                    } else {
                        Log.e(TAG, "최대 재시도 횟수 도달, 연결 포기")
                        retryCount = 0
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "서버로부터 메시지 수신: $text")
                    try {
                        val response = JSONObject(text)
                        val status = response.optString("status", "알 수 없음")
                        val message = response.optString("message", "메시지 없음")

                        // ✅ 서버 제어 명령 처리
                        when (status) {
                            "high_precision_mode" -> {
                                isHighPrecisionMode = response.optBoolean("enabled", false)
                                Log.d(TAG, "고정밀도 모드 ${if (isHighPrecisionMode) "활성화" else "비활성화"}")
                            }
                            "buffer_control" -> {
                                val bufferSize = response.optInt("buffer_size", 10)
                                // 버퍼 크기 조절 로직
                            }
                        }

                        Log.d(TAG, "응답 파싱 - 상태: $status, 메시지: $message")
                    } catch (e: Exception) {
                        Log.e(TAG, "서버 메시지 파싱 실패: ${e.message}", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 연결 종료 중 - 코드: $code, 이유: $reason")
                    this@WebSocketStreamingClient.webSocket = null
                    stopBufferProcessing()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 연결 종료 - 코드: $code, 이유: $reason")
                    this@WebSocketStreamingClient.webSocket = null
                    stopBufferProcessing()
                }
            })
        }
    }

    override suspend fun stopStreaming() {
        withContext(Dispatchers.IO) {
            stopBufferProcessing()
            webSocket?.let {
                it.close(1000, "클라이언트 요청으로 스트리밍 중지")
                Log.d(TAG, "WebSocket 스트리밍 중지 요청 완료")
                webSocket = null
            } ?: Log.w(TAG, "WebSocket이 이미 닫혀 있거나 초기화되지 않음")
            isConnecting = false
            retryCount = 0
        }
    }

    // ✅ 버퍼 처리 시작
    private fun startBufferProcessing() {
        dataSendingScope.launch {
            while (webSocket != null && isActive) {
                try {
                    processComprehensiveGnssBuffer()
                    processSatelliteStatusBuffer()
                    processNavigationMessageBuffer()
                    processGnssClockBuffer()
                    delay(100) // 10Hz로 버퍼 처리
                } catch (e: Exception) {
                    Log.e(TAG, "버퍼 처리 중 오류: ${e.message}", e)
                }
            }
        }
    }

    // ✅ 버퍼 처리 중지
    private fun stopBufferProcessing() {
        dataSendingScope.coroutineContext.cancelChildren()
    }

    // ✅ 완전한 GNSS 데이터 버퍼 처리
    private suspend fun processComprehensiveGnssBuffer() {
        if (comprehensiveGnssBuffer.isEmpty() || isBufferProcessing.getAndSet(true)) return

        try {
            val batchSize = if (isHighPrecisionMode) 5 else 10
            val gnssDataBatch = mutableListOf<ComprehensiveGnssData>()

            repeat(batchSize.coerceAtMost(comprehensiveGnssBuffer.size)) {
                comprehensiveGnssBuffer.poll()?.let { gnssDataBatch.add(it) }
            }

            if (gnssDataBatch.isNotEmpty()) {
                sendComprehensiveGnssBatch(gnssDataBatch)
            }
        } finally {
            isBufferProcessing.set(false)
        }
    }

    // ✅ 위성 상태 버퍼 처리
    private suspend fun processSatelliteStatusBuffer() {
        if (satelliteStatusBuffer.isEmpty()) return

        val satelliteStatuses = mutableListOf<GnssSatelliteStatus>()
        repeat(20.coerceAtMost(satelliteStatusBuffer.size)) {
            satelliteStatusBuffer.poll()?.let { satelliteStatuses.add(it) }
        }

        if (satelliteStatuses.isNotEmpty()) {
            sendSatelliteStatusBatch(satelliteStatuses)
        }
    }

    // ✅ 내비게이션 메시지 버퍼 처리
    private suspend fun processNavigationMessageBuffer() {
        if (navigationMessageBuffer.isEmpty()) return

        val navigationMessages = mutableListOf<GnssNavigationData>()
        repeat(10.coerceAtMost(navigationMessageBuffer.size)) {
            navigationMessageBuffer.poll()?.let { navigationMessages.add(it) }
        }

        if (navigationMessages.isNotEmpty()) {
            sendNavigationMessageBatch(navigationMessages)
        }
    }

    // ✅ GNSS 클럭 버퍼 처리
    private suspend fun processGnssClockBuffer() {
        if (gnssClockBuffer.isEmpty()) return

        val clockDataList = mutableListOf<GnssClockData>()
        repeat(5.coerceAtMost(gnssClockBuffer.size)) {
            gnssClockBuffer.poll()?.let { clockDataList.add(it) }
        }

        if (clockDataList.isNotEmpty()) {
            sendGnssClockBatch(clockDataList)
        }
    }

    // ✅ Binary Frame 사용으로 대역폭 최적화 - 완전한 모든 필드 포함
    private fun sendComprehensiveGnssDataBinary(data: ComprehensiveGnssData) {
        val binaryData = ByteBuffer.allocate(512).apply { // 크기 증가
            // 기본 타임스탬프 정보
            putLong(data.gpsTimestamp)
            putLong(data.localTimestamp)
            putLong(data.monoTimestamp)
            put(if (data.isGpsTimeValid) 1.toByte() else 0.toByte())

            // GNSS 타입 및 위성 정보
            val gnssTypeBytes = data.gnssType.toByteArray()
            putInt(gnssTypeBytes.size)
            put(gnssTypeBytes)
            putInt(data.constellationType)
            putInt(data.satelliteId)
            putInt(data.svid)

            // 신호 강도 및 주파수
            putDouble(data.signalStrength)
            putDouble(data.carrierFrequencyHz ?: Double.NaN)

            // 멀티패스 및 측정값
            putInt(data.multipathIndicator)
            putDouble(data.pseudorangeRate ?: Double.NaN)
            putDouble(data.pseudorangeRateUncertainty ?: Double.NaN)
            putDouble(data.accumulatedDeltaRange ?: Double.NaN)
            putInt(data.accumulatedDeltaRangeState)
            putDouble(data.accumulatedDeltaRangeUncertainty ?: Double.NaN)

            // 캐리어 위상 정보
            putDouble(data.carrierPhase ?: Double.NaN)
            putDouble(data.carrierPhaseUncertainty ?: Double.NaN)
            putLong(data.carrierCycles ?: -1L)

            // 시간 정보
            putLong(data.receivedSvTimeNanos)
            putLong(data.receivedSvTimeUncertainty)
            putDouble(data.timeOffsetNanos)

            // 상태 및 제어 정보
            putInt(data.state)
            putDouble(data.automaticGainControl ?: Double.NaN)

            // 추가 신호 정보
            putDouble(data.basebandCn0DbHz ?: Double.NaN)
            putDouble(data.fullInterSignalBiasNanos ?: Double.NaN)
            putDouble(data.fullInterSignalBiasUncertaintyNanos ?: Double.NaN)
            putDouble(data.satelliteInterSignalBiasNanos ?: Double.NaN)
            putDouble(data.satelliteInterSignalBiasUncertaintyNanos ?: Double.NaN)

            // 코드 타입
            val codeTypeBytes = (data.codeType ?: "NULL").toByteArray()
            putInt(codeTypeBytes.size)
            put(codeTypeBytes)

            // 추가 정보
            val additionalInfoBytes = data.additionalInfo.toByteArray()
            putInt(additionalInfoBytes.size)
            put(additionalInfoBytes)
        }.array()

        webSocket?.send(ByteString.of(*binaryData))
    }

    // ✅ 배치 전송으로 효율성 개선 - 완전한 모든 필드 포함
    private fun sendComprehensiveGnssBatch(dataList: List<ComprehensiveGnssData>) {
        val jsonArray = JSONArray()
        dataList.forEach { data ->
            val json = JSONObject().apply {
                put("type", "comprehensive_gnss")

                // 기본 타임스탬프 정보
                put("gpsTimestamp", data.gpsTimestamp)
                put("localTimestamp", data.localTimestamp)
                put("monoTimestamp", data.monoTimestamp)
                put("isGpsTimeValid", data.isGpsTimeValid)

                // GNSS 타입 및 위성 정보
                put("gnssType", data.gnssType)
                put("constellationType", data.constellationType)
                put("satelliteId", data.satelliteId)
                put("svid", data.svid)

                // 신호 강도 및 주파수
                put("signalStrength", data.signalStrength)
                put("carrierFrequencyHz", data.carrierFrequencyHz ?: JSONObject.NULL)

                // 멀티패스 및 측정값
                put("multipathIndicator", data.multipathIndicator)
                put("pseudorangeRate", data.pseudorangeRate ?: JSONObject.NULL)
                put("pseudorangeRateUncertainty", data.pseudorangeRateUncertainty ?: JSONObject.NULL)
                put("accumulatedDeltaRange", data.accumulatedDeltaRange ?: JSONObject.NULL)
                put("accumulatedDeltaRangeState", data.accumulatedDeltaRangeState)
                put("accumulatedDeltaRangeUncertainty", data.accumulatedDeltaRangeUncertainty ?: JSONObject.NULL)

                // 캐리어 위상 정보
                put("carrierPhase", data.carrierPhase ?: JSONObject.NULL)
                put("carrierPhaseUncertainty", data.carrierPhaseUncertainty ?: JSONObject.NULL)
                put("carrierCycles", data.carrierCycles ?: JSONObject.NULL)

                // 시간 정보
                put("receivedSvTimeNanos", data.receivedSvTimeNanos)
                put("receivedSvTimeUncertainty", data.receivedSvTimeUncertainty)
                put("timeOffsetNanos", data.timeOffsetNanos)

                // 상태 및 제어 정보
                put("state", data.state)
                put("automaticGainControl", data.automaticGainControl ?: JSONObject.NULL)

                // 추가 신호 정보
                put("basebandCn0DbHz", data.basebandCn0DbHz ?: JSONObject.NULL)
                put("fullInterSignalBiasNanos", data.fullInterSignalBiasNanos ?: JSONObject.NULL)
                put("fullInterSignalBiasUncertaintyNanos", data.fullInterSignalBiasUncertaintyNanos ?: JSONObject.NULL)
                put("satelliteInterSignalBiasNanos", data.satelliteInterSignalBiasNanos ?: JSONObject.NULL)
                put("satelliteInterSignalBiasUncertaintyNanos", data.satelliteInterSignalBiasUncertaintyNanos ?: JSONObject.NULL)

                // 코드 타입 및 추가 정보
                put("codeType", data.codeType ?: JSONObject.NULL)
                put("additionalInfo", data.additionalInfo)
            }
            jsonArray.put(json)
        }

        val batchJson = JSONObject().apply {
            put("type", "gnss_batch")
            put("count", dataList.size)
            put("data", jsonArray)
            put("timestamp", System.currentTimeMillis())
        }

        sendData(batchJson, "GNSS 배치", System.currentTimeMillis())
    }

    // ✅ 위성 상태 배치 전송 - 완전한 모든 필드 포함
    private fun sendSatelliteStatusBatch(statusList: List<GnssSatelliteStatus>) {
        val jsonArray = JSONArray()
        statusList.forEach { status ->
            val json = JSONObject().apply {
                put("gpsTimestamp", status.gpsTimestamp)
                put("localTimestamp", status.localTimestamp)
                put("monoTimestamp", status.monoTimestamp)
                put("satelliteIndex", status.satelliteIndex)
                put("constellationType", status.constellationType)
                put("svid", status.svid)
                put("cn0DbHz", status.cn0DbHz)
                put("hasCarrierFrequency", status.hasCarrierFrequency)
                put("carrierFrequencyHz", status.carrierFrequencyHz ?: JSONObject.NULL)
                put("azimuthDegrees", status.azimuthDegrees)
                put("elevationDegrees", status.elevationDegrees)
                put("hasAlmanacData", status.hasAlmanacData)
                put("hasEphemerisData", status.hasEphemerisData)
                put("usedInFix", status.usedInFix)
                put("totalSatelliteCount", status.totalSatelliteCount)
                put("usedSatelliteCount", status.usedSatelliteCount)
            }
            jsonArray.put(json)
        }

        val batchJson = JSONObject().apply {
            put("type", "satellite_status_batch")
            put("count", statusList.size)
            put("data", jsonArray)
            put("timestamp", System.currentTimeMillis())
        }

        sendData(batchJson, "위성 상태 배치", System.currentTimeMillis())
    }

    // ✅ 내비게이션 메시지 배치 전송 - 완전한 모든 필드 포함
    private fun sendNavigationMessageBatch(navigationList: List<GnssNavigationData>) {
        val jsonArray = JSONArray()
        navigationList.forEach { navigation ->
            val json = JSONObject().apply {
                put("gpsTimestamp", navigation.gpsTimestamp)
                put("localTimestamp", navigation.localTimestamp)
                put("monoTimestamp", navigation.monoTimestamp)
                put("messageId", navigation.messageId)
                put("submessageId", navigation.submessageId)
                put("type", navigation.type)
                put("status", navigation.status)
                put("svid", navigation.svid)
                put("dataLength", navigation.dataLength)
                put("hexData", navigation.data.joinToString("") { "%02X".format(it) })
                put("additionalInfo", navigation.additionalInfo)
            }
            jsonArray.put(json)
        }

        val batchJson = JSONObject().apply {
            put("type", "navigation_message_batch")
            put("count", navigationList.size)
            put("data", jsonArray)
            put("timestamp", System.currentTimeMillis())
        }

        sendData(batchJson, "내비게이션 메시지 배치", System.currentTimeMillis())
    }

    // ✅ GNSS 클럭 배치 전송 - 완전한 모든 필드 포함
    private fun sendGnssClockBatch(clockList: List<GnssClockData>) {
        val jsonArray = JSONArray()
        clockList.forEach { clock ->
            val json = JSONObject().apply {
                put("gpsTimestamp", clock.gpsTimestamp)
                put("localTimestamp", clock.localTimestamp)
                put("monoTimestamp", clock.monoTimestamp)
                put("timeNanos", clock.timeNanos)
                put("timeUncertaintyNanos", clock.timeUncertaintyNanos ?: JSONObject.NULL)
                put("leapSecond", clock.leapSecond ?: JSONObject.NULL)
                put("biasNanos", clock.biasNanos ?: JSONObject.NULL)
                put("biasUncertaintyNanos", clock.biasUncertaintyNanos ?: JSONObject.NULL)
                put("driftNanosPerSecond", clock.driftNanosPerSecond ?: JSONObject.NULL)
                put("driftUncertaintyNanosPerSecond", clock.driftUncertaintyNanosPerSecond ?: JSONObject.NULL)
                put("hardwareClockDiscontinuityCount", clock.hardwareClockDiscontinuityCount ?: JSONObject.NULL)
                put("fullBiasNanos", clock.fullBiasNanos ?: JSONObject.NULL)
                put("additionalInfo", clock.additionalInfo)
            }
            jsonArray.put(json)
        }

        val batchJson = JSONObject().apply {
            put("type", "gnss_clock_batch")
            put("count", clockList.size)
            put("data", jsonArray)
            put("timestamp", System.currentTimeMillis())
        }

        sendData(batchJson, "GNSS 클럭 배치", System.currentTimeMillis())
    }

    // ✅ 적응형 전송 주기
    private fun adaptiveSendingRate(dataType: String): Long {
        return when (dataType) {
            "comprehensive_gnss" -> if (isHighPrecisionMode) 50L else 200L
            "satellite_status" -> 1000L
            "navigation" -> 5000L
            "camera" -> 100L
            "gps" -> 1000L
            "imu" -> 200L
            else -> 100L
        }
    }

    // ✅ 전송 주기 제어
    private fun shouldSendData(dataType: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastSent = lastDataSentTimes[dataType] ?: 0L
        val interval = adaptiveSendingRate(dataType)

        return (currentTime - lastSent) >= interval
    }

    override fun sendCameraData(data: SensorData) {
        if (!shouldSendData("camera")) return

        data.bitmap?.let { bitmap ->
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
            val base64Image = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)
            val json = JSONObject().apply {
                put("type", "camera")
                put("image", base64Image)
                put("timestamp", data.timestamp)
                put("monoTimestamp", data.monoTimestamp)
                put("frameId", data.frameId)
                put("value", data.value)
            }
            if (sendData(json, "카메라", data.timestamp)) {
                lastDataSentTimes["camera"] = System.currentTimeMillis()
            }
        } ?: Log.w(TAG, "카메라 데이터 전송 실패: Bitmap이 null입니다")
    }

    override fun sendGpsData(location: Location, systemTimestamp: Long, monoTimestamp: Long) {
        if (!shouldSendData("gps")) return

        val json = JSONObject().apply {
            put("type", "gps")
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            if (location.hasAltitude()) put("altitude", location.altitude)
            if (location.hasAccuracy()) put("accuracy", location.accuracy)
            if (location.hasSpeed()) put("speed", location.speed)
            if (location.hasBearing()) put("bearing", location.bearing)
            put("provider", location.provider)
            put("timestamp", systemTimestamp)
            put("monoTimestamp", monoTimestamp)
            put("gpsTime", location.time)
        }
        if (sendData(json, "GPS", systemTimestamp)) {
            lastDataSentTimes["gps"] = System.currentTimeMillis()
        }
    }

    override fun sendImuData(imu: FloatArray, systemTimestamp: Long, monoTimestamp: Long) {
        if (!shouldSendData("imu")) return

        val json = JSONObject().apply {
            put("type", "imu")
            put("accelerometer", JSONArray().apply {
                put(imu.getOrElse(0) { 0.0f })
                put(imu.getOrElse(1) { 0.0f })
                put(imu.getOrElse(2) { 0.0f })
            })
            put("gyroscope", JSONArray().apply {
                put(imu.getOrElse(3) { 0.0f })
                put(imu.getOrElse(4) { 0.0f })
                put(imu.getOrElse(5) { 0.0f })
            })
            put("magnetometer", JSONArray().apply {
                put(imu.getOrElse(6) { 0.0f })
                put(imu.getOrElse(7) { 0.0f })
                put(imu.getOrElse(8) { 0.0f })
            })
            put("timestamp", systemTimestamp)
            put("monoTimestamp", monoTimestamp)
        }
        if (sendData(json, "IMU", systemTimestamp)) {
            lastDataSentTimes["imu"] = System.currentTimeMillis()
        }
    }

    override fun sendGnssData(gnss: GnssData) {
        val json = JSONObject().apply {
            put("type", "gnss")
            put("gpsTimestamp", gnss.gpsTimestamp)
            put("localTimestamp", gnss.localTimestamp)
            put("monoTimestamp", gnss.monoTimestamp)
            put("gnssType", gnss.gnssType)
            put("satelliteId", gnss.satelliteId)
            put("signalStrength", gnss.signalStrength)
            put("pseudorangeRate", gnss.pseudorangeRate ?: JSONObject.NULL)
            put("carrierPhase", gnss.carrierPhase ?: JSONObject.NULL)
            put("additionalInfo", gnss.additionalInfo)
        }
        sendData(json, "GNSS", gnss.localTimestamp)
    }

    // ✅ 새로운 완전한 GNSS 데이터 전송 메서드들 - 메서드명 변경
    fun addComprehensiveGnssData(data: ComprehensiveGnssData) {
        comprehensiveGnssBuffer.offer(data)
        // 버퍼가 가득 차면 오래된 데이터 제거
        while (comprehensiveGnssBuffer.size > 100) {
            comprehensiveGnssBuffer.poll()
        }
        Log.d(TAG, "완전한 GNSS 데이터 버퍼 추가: ${data.gnssType}, SV=${data.satelliteId}, 버퍼 크기=${comprehensiveGnssBuffer.size}")
    }

    fun addSatelliteStatusData(status: GnssSatelliteStatus) {
        satelliteStatusBuffer.offer(status)
        while (satelliteStatusBuffer.size > 50) {
            satelliteStatusBuffer.poll()
        }
        Log.d(TAG, "위성 상태 데이터 버퍼 추가: SV=${status.svid}, 버퍼 크기=${satelliteStatusBuffer.size}")
    }

    fun addNavigationMessageData(navigation: GnssNavigationData) {
        navigationMessageBuffer.offer(navigation)
        while (navigationMessageBuffer.size > 30) {
            navigationMessageBuffer.poll()
        }
        Log.d(TAG, "내비게이션 메시지 버퍼 추가: SV=${navigation.svid}, 버퍼 크기=${navigationMessageBuffer.size}")
    }

    fun addGnssClockData(clock: GnssClockData) {
        gnssClockBuffer.offer(clock)
        while (gnssClockBuffer.size > 20) {
            gnssClockBuffer.poll()
        }
        Log.d(TAG, "GNSS 클럭 데이터 버퍼 추가: 버퍼 크기=${gnssClockBuffer.size}")
    }

    private fun sendData(json: JSONObject, dataType: String, timestamp: Long): Boolean {
        if (webSocket == null || isConnecting) {
            Log.e(TAG, "$dataType 데이터 전송 실패: WebSocket이 연결되지 않음 또는 연결 중")
            return false
        }
        val success = webSocket!!.send(json.toString())
        if (success) {
            Log.d(TAG, "$dataType 데이터 전송 성공: sys_ts=$timestamp")
        } else {
            Log.e(TAG, "$dataType 데이터 전송 실패: WebSocket 상태 이상")
        }
        return success
    }

    override fun sendBoundingBoxData(boundingBoxes: List<BoundingBoxLog>) {
        if (boundingBoxes.isEmpty()) {
            Log.d(TAG, "No bounding boxes to send for this frame.")
            return
        }

        val boundingBoxArray = JSONArray()
        boundingBoxes.forEach { bbox ->
            val bboxJson = JSONObject().apply {
                put("frameId", bbox.frameId)
                put("x1", bbox.x1)
                put("y1", bbox.y1)
                put("x2", bbox.x2)
                put("y2", bbox.y2)
                put("confidence", bbox.cnf)
                put("className", bbox.clsName)
                put("timestamp", bbox.timestamp)
                put("monoTimestamp", bbox.monoTimestamp)
            }
            boundingBoxArray.put(bboxJson)
        }

        val json = JSONObject().apply {
            put("type", "bounding_boxes")
            put("frameId", boundingBoxes[0].frameId)
            put("timestamp", boundingBoxes[0].timestamp)
            put("monoTimestamp", boundingBoxes[0].monoTimestamp)
            put("detections", boundingBoxArray)
        }
        sendData(json, "바운딩 박스", boundingBoxes[0].timestamp)
    }

    // ✅ 스트리밍 상태 정보
    fun getStreamingStatus(): String {
        return buildString {
            append("=== WebSocket Streaming 상태 ===\n")
            append("연결 상태: ${if (webSocket != null) "연결됨" else "연결 안됨"}\n")
            append("고정밀도 모드: $isHighPrecisionMode\n")
            append("재시도 횟수: $retryCount/$maxRetries\n")
            append("\n=== 버퍼 상태 ===\n")
            append("완전한 GNSS: ${comprehensiveGnssBuffer.size}/100\n")
            append("위성 상태: ${satelliteStatusBuffer.size}/50\n")
            append("내비게이션: ${navigationMessageBuffer.size}/30\n")
            append("GNSS 클럭: ${gnssClockBuffer.size}/20\n")
            append("버퍼 처리 중: $isBufferProcessing")
        }
    }
}
package com.example.myapplication.data.streaming.test

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.util.Base64
import android.util.Log
import com.example.myapplication.data.streaming.StreamingClient
import com.example.myapplication.model.BoundingBoxLog
import com.example.myapplication.model.GnssData
import com.example.myapplication.model.SensorData
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class WebSocketStreamingClient : StreamingClient {
    private val TAG = "WebSocketStreamingClient"
    private var webSocket: WebSocket? = null
    private var isConnecting = false
    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelay = 5000L // 5초

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
            //val url = "ws://10.1.2.168:30168/ws/stream/"
            val url = "ws://office2.mobiltech.io:30168/ws/stream/"
            Log.d(TAG, "WebSocket 연결 시도: $url")
            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 연결 성공: $url")
                    this@WebSocketStreamingClient.webSocket = webSocket
                    isConnecting = false
                    retryCount = 0
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
                            delay(retryDelay) // Thread.sleep 대신 delay 사용
                        }
                        runBlocking {
                            startStreaming(context) // 재시도
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
                        Log.d(TAG, "응답 파싱 - 상태: $status, 메시지: $message")
                    } catch (e: Exception) {
                        Log.e(TAG, "서버 메시지 파싱 실패: ${e.message}", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 연결 종료 중 - 코드: $code, 이유: $reason")
                    this@WebSocketStreamingClient.webSocket = null
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 연결 종료 - 코드: $code, 이유: $reason")
                    this@WebSocketStreamingClient.webSocket = null
                }
            })
        }
    }

    override suspend fun stopStreaming() {
        withContext(Dispatchers.IO) {
            webSocket?.let {
                it.close(1000, "클라이언트 요청으로 스트리밍 중지")
                Log.d(TAG, "WebSocket 스트리밍 중지 요청 완료")
                webSocket = null
            } ?: Log.w(TAG, "WebSocket이 이미 닫혀 있거나 초기화되지 않음")
            isConnecting = false
            retryCount = 0
        }
    }

    override fun sendCameraData(data: SensorData) {
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
            }
            sendData(json, "카메라", data.timestamp)
        } ?: Log.w(TAG, "카메라 데이터 전송 실패: Bitmap이 null입니다")
    }

    override fun sendGpsData(location: Location, systemTimestamp: Long, monoTimestamp: Long) {
        val json = JSONObject().apply {
            put("type", "gps")
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            if (location.hasAltitude()) put("altitude", location.altitude)
            put("timestamp", systemTimestamp)
            put("monoTimestamp", monoTimestamp)
        }
        sendData(json, "GPS", systemTimestamp)
    }

    override fun sendImuData(imu: FloatArray, systemTimestamp: Long, monoTimestamp: Long) {
        val json = JSONObject().apply {
            put("type", "imu")
            put("accelerometer", imu.sliceArray(0..2).contentToString())
            put("gyroscope", imu.sliceArray(3..5).contentToString())
            put("magnetometer", imu.sliceArray(6..8).contentToString())
            put("timestamp", systemTimestamp)
            put("monoTimestamp", monoTimestamp)
        }
        sendData(json, "IMU", systemTimestamp)
    }

    override fun sendGnssData(gnss: GnssData) {
        val json = JSONObject().apply {
            put("type", "gnss")
            put("gnssType", gnss.gnssType)
            put("satelliteId", gnss.satelliteId)
            put("signalStrength", gnss.signalStrength)
            put("pseudorangeRate", gnss.pseudorangeRate ?: "N/A")
            put("carrierPhase", gnss.carrierPhase ?: "N/A")
            put("additionalInfo", gnss.additionalInfo)
            put("timestamp", gnss.timestamp)
            put("monoTimestamp", gnss.monoTimestamp)
        }
        if (!sendData(json, "GNSS", gnss.timestamp)) {
            Log.e("WebSocketStreamingClient", "GNSS 데이터 전송 실패")
        }
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
                put("x1", bbox.x1)
                put("y1", bbox.y1)
                put("x2", bbox.x2)
                put("y2", bbox.y2)
                put("confidence", bbox.cnf)
                put("className", bbox.clsName)
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
        sendData(json, "바운딩 박스", boundingBoxes[0].timestamp) // sendData 함수 사용
    }
}
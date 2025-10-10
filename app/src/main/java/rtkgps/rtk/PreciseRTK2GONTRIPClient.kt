// PreciseRTK2GONTRIPClient.kt
package com.example.myapplication.gps_modules.rtk

import android.util.Base64  // 이 import 추가
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
//import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

class PreciseRTK2GONTRIPClient {

    private var socket: Socket? = null
    private var isConnected = false
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    private val rtcmDataQueue = ConcurrentLinkedQueue<RTCMMessage>()
    private var rtcmCallback: ((RTCMMessage) -> Unit)? = null

    // NTRIP 설정
    private val ntripHost = "rtk2go.com"
    private val ntripPort = 2101
    private var deviceEmail = "sju5629@gmail.com"
    private var selectedMountpoint = "SEOUL9991_RTCM3"

    // RTCM 메시지 파서
    private val rtcmParser = RTCMParser()

    // 연결 통계
    private var bytesReceived = 0L
    private var messagesReceived = 0L
    private var lastDataTime = 0L

    fun setRTCMCallback(callback: (RTCMMessage) -> Unit) {
        rtcmCallback = callback
    }

    fun setDeviceInfo(email: String, mountpoint: String) {
        deviceEmail = email
        selectedMountpoint = mountpoint
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            disconnect() // 기존 연결 정리

            socket = Socket(ntripHost, ntripPort)
            socket?.soTimeout = 30000 // 30초 타임아웃

            // NTRIP 요청 전송
            val request = buildNTRIPRequest()
            val writer = OutputStreamWriter(socket?.getOutputStream())
            writer.write(request)
            writer.flush()

            // 응답 확인
            val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
            val response = reader.readLine()

            if (response?.contains("200 OK") == true) {
                isConnected = true
                startDataReception()
                startHeartbeat()
                println("RTK2GO 연결 성공: $selectedMountpoint")
            } else {
                throw Exception("NTRIP 연결 실패: $response")
            }

        } catch (e: Exception) {
            isConnected = false
            throw Exception("RTK2GO 연결 오류: ${e.message}")
        }
    }

    // 마운트포인트 목록을 먼저 가져오는 함수 추가
    /*suspend fun getKoreaMountpoints(): List<String> = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(ntripHost, ntripPort)
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // 소스테이블 요청
            writer.write("GET / HTTP/1.1\r\n")
            writer.write("Host: $ntripHost:$ntripPort\r\n")
            writer.write("Ntrip-Version: Ntrip/2.0\r\n")
            writer.write("User-Agent: PreciseRTK-Android/1.0\r\n")
            writer.write("\r\n")
            writer.flush()

            val sourceTable = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sourceTable.appendLine(line)
            }
            socket.close()

            // 한국 관련 마운트포인트 필터링
            val koreanMountpoints = mutableListOf<String>()
            sourceTable.toString().split("\n").forEach { sourceLine ->
                if (sourceLine.startsWith("STR;") &&
                    (sourceLine.contains("KOR", true) ||
                            sourceLine.contains("SEOUL", true) ||
                            sourceLine.contains("BUSAN", true) ||
                            sourceLine.contains("Korea", true))) {
                    val parts = sourceLine.split(";")
                    if (parts.size > 1) {
                        koreanMountpoints.add(parts[1])
                    }
                }
            }

            println("사용 가능한 한국 마운트포인트: $koreanMountpoints")
            koreanMountpoints

        } catch (e: Exception) {
            println("마운트포인트 조회 오류: ${e.message}")
            // 대체 마운트포인트 목록
            listOf("RTCM3", "NEAR", "CLOSEST")
        }
    }*/

    private fun buildNTRIPRequest(): String {
        val credentials = Base64.encodeToString("$deviceEmail:none".toByteArray(), Base64.NO_WRAP)

        return "GET /$selectedMountpoint HTTP/1.1\r\n" +
                "Host: $ntripHost:$ntripPort\r\n" +
                "Ntrip-Version: Ntrip/2.0\r\n" +
                "User-Agent: PreciseRTK-Android/1.0\r\n" +
                "Authorization: Basic $credentials\r\n" +
                "Connection: close\r\n" +
                "\r\n"
    }

    private fun startDataReception() {
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            val inputStream = socket?.getInputStream()
            val buffer = ByteArray(4096)

            while (isConnected && !currentCoroutineContext().isActive.not()) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        bytesReceived += bytesRead
                        lastDataTime = System.currentTimeMillis()

                        // RTCM 메시지 파싱
                        val rtcmData = buffer.copyOf(bytesRead)
                        parseRTCMData(rtcmData)

                    } else if (bytesRead == -1) {
                        break // 연결 종료
                    }
                } catch (e: Exception) {
                    if (isConnected) {
                        println("데이터 수신 오류: ${e.message}")
                        // 재연결 시도
                        delay(5000)
                        reconnect()
                    }
                    break
                }
            }
        }
    }

    private fun parseRTCMData(data: ByteArray) {
        try {
            val messages = rtcmParser.parseMessages(data)
            messages.forEach { message ->
                messagesReceived++
                rtcmCallback?.invoke(message)
            }
        } catch (e: Exception) {
            println("RTCM 파싱 오류: ${e.message}")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                delay(30000) // 30초마다 하트비트

                // 데이터 수신 확인
                val timeSinceLastData = System.currentTimeMillis() - lastDataTime
                if (timeSinceLastData > 60000) { // 1분 이상 데이터 없음
                    println("데이터 수신 중단 감지, 재연결 시도")
                    reconnect()
                }
            }
        }
    }

    private suspend fun reconnect() {
        disconnect()
        delay(2000)
        try {
            connect()
        } catch (e: Exception) {
            println("재연결 실패: ${e.message}")
        }
    }

    fun disconnect() {
        isConnected = false
        connectionJob?.cancel()
        heartbeatJob?.cancel()

        try {
            socket?.close()
        } catch (e: Exception) {
            // 무시
        }
        socket = null
    }

    suspend fun getAvailableMountpoints(): List<MountpointInfo> = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(ntripHost, ntripPort)
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // 소스테이블 요청
            writer.write("GET / HTTP/1.1\r\n")
            writer.write("Host: $ntripHost:$ntripPort\r\n")
            writer.write("Ntrip-Version: Ntrip/2.0\r\n")
            writer.write("User-Agent: PreciseRTK-Android/1.0\r\n")
            writer.write("\r\n")
            writer.flush()

            // 응답 읽기
            val sourceTable = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sourceTable.appendLine(line)
            }

            socket.close()

            // 마운트포인트 파싱
            parseMountpoints(sourceTable.toString())

        } catch (e: Exception) {
            println("마운트포인트 조회 오류: ${e.message}")
            emptyList()
        }
    }

    private fun parseMountpoints(sourceTable: String): List<MountpointInfo> {
        val mountpoints = mutableListOf<MountpointInfo>()
        val lines = sourceTable.split("\n")

        for (line in lines) {
            if (line.startsWith("STR;")) {
                try {
                    val parts = line.split(";")
                    if (parts.size >= 19) {
                        val info = MountpointInfo(
                            name = parts[1],
                            identifier = parts[2],
                            format = parts[3],
                            formatDetails = parts[4],
                            carrier = parts[5],
                            navigation = parts[6],
                            network = parts[7],
                            country = parts[8],
                            latitude = parts[9].toDoubleOrNull() ?: 0.0,
                            longitude = parts[10].toDoubleOrNull() ?: 0.0,
                            nmea = parts[11] == "1",
                            solution = parts[12] == "1",
                            generator = parts[13],
                            compression = parts[14],
                            authentication = parts[15],
                            fee = parts[16] == "Y",
                            bitrate = parts[17].toIntOrNull() ?: 0,
                            miscellaneous = parts[18]
                        )
                        mountpoints.add(info)
                    }
                } catch (e: Exception) {
                    // 파싱 오류 무시
                }
            }
        }

        return mountpoints.sortedBy { it.name }
    }

    fun getConnectionStats(): ConnectionStats {
        return ConnectionStats(
            isConnected = isConnected,
            bytesReceived = bytesReceived,
            messagesReceived = messagesReceived,
            timeSinceLastData = if (lastDataTime > 0) System.currentTimeMillis() - lastDataTime else -1
        )
    }
}

// RTCM 메시지 파서
class RTCMParser {

    private val buffer = mutableListOf<Byte>()
    private val preamble = 0xD3.toByte()

    fun parseMessages(data: ByteArray): List<RTCMMessage> {
        val messages = mutableListOf<RTCMMessage>()

        // 버퍼에 데이터 추가
        buffer.addAll(data.toList())

        while (buffer.size >= 3) {
            // 프리앰블 찾기
            val preambleIndex = buffer.indexOf(preamble)
            if (preambleIndex == -1) {
                buffer.clear()
                break
            }

            // 프리앰블 이전 데이터 제거
            if (preambleIndex > 0) {
                repeat(preambleIndex) { buffer.removeAt(0) }
            }

            if (buffer.size < 3) break

            // 길이 계산
            val length = ((buffer[1].toInt() and 0x03) shl 8) or (buffer[2].toInt() and 0xFF)
            val totalLength = length + 6 // 헤더(3) + 데이터(length) + CRC(3)

            if (buffer.size < totalLength) break

            // 메시지 추출
            val messageData = ByteArray(totalLength)
            for (i in 0 until totalLength) {
                messageData[i] = buffer[i]
            }

            // CRC 검증
            if (verifyCRC(messageData)) {
                try {
                    val message = parseRTCMMessage(messageData)
                    messages.add(message)
                } catch (e: Exception) {
                    println("RTCM 메시지 파싱 오류: ${e.message}")
                }
            }

            // 처리된 데이터 제거
            repeat(totalLength) { buffer.removeAt(0) }
        }

        return messages
    }

    private fun parseRTCMMessage(data: ByteArray): RTCMMessage {
        val messageType = ((data[3].toInt() and 0xFF) shl 4) or ((data[4].toInt() and 0xF0) shr 4)

        return when (messageType) {
            1005 -> parseRTCM1005(data)
            1077 -> parseRTCM1077(data)
            1087 -> parseRTCM1087(data)
            1097 -> parseRTCM1097(data)
            1127 -> parseRTCM1127(data)
            else -> RTCMMessage.Unknown(
                messageType = messageType,
                data = data,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun parseRTCM1005(data: ByteArray): RTCMMessage.StationPosition {
        // RTCM 1005: 기준국 위치 (Stationary RTK Reference Station ARP)
        var bitIndex = 24 // 헤더 후 시작

        val stationId = extractBits(data, bitIndex, 12)
        bitIndex += 12

        val itrfYear = extractBits(data, bitIndex, 6)
        bitIndex += 6

        val gpsIndicator       = extractBits(data, bitIndex, 1) == 1L
        bitIndex += 1

        val glonassIndicator   = extractBits(data, bitIndex, 1) == 1L
        bitIndex += 1

        val galileoIndicator   = extractBits(data, bitIndex, 1) == 1L
        bitIndex += 1

        val refStationIndicator= extractBits(data, bitIndex, 1) == 1L
        bitIndex += 1

        val ecefX = extractBits(data, bitIndex, 38).toDouble() * 0.0001 // mm to m
        bitIndex += 38

        val ecefY = extractBits(data, bitIndex, 38).toDouble() * 0.0001
        bitIndex += 38

        val ecefZ = extractBits(data, bitIndex, 38).toDouble() * 0.0001
        bitIndex += 38

        return RTCMMessage.StationPosition(
            messageType = 1005,
            data = data,
            timestamp = System.currentTimeMillis(),
            stationId = stationId,
            ecefX = ecefX,
            ecefY = ecefY,
            ecefZ = ecefZ
        )
    }

    private fun parseRTCM1077(data: ByteArray): RTCMMessage.GPSObservations {
        // GPS MSM7 메시지 파싱 (간소화된 버전)
        return RTCMMessage.GPSObservations(
            messageType = 1077,
            data = data,
            timestamp = System.currentTimeMillis(),
            stationId = extractBits(data, 24, 12),
            epochTime = extractBits(data, 36, 30)
        )
    }

    private fun parseRTCM1087(data: ByteArray): RTCMMessage.GLONASSObservations {
        // GLONASS MSM7 메시지 파싱
        return RTCMMessage.GLONASSObservations(
            messageType = 1087,
            data = data,
            timestamp = System.currentTimeMillis(),
            stationId = extractBits(data, 24, 12),
            epochTime = extractBits(data, 36, 27)
        )
    }

    private fun parseRTCM1097(data: ByteArray): RTCMMessage.GalileoObservations {
        // Galileo MSM7 메시지 파싱
        return RTCMMessage.GalileoObservations(
            messageType = 1097,
            data = data,
            timestamp = System.currentTimeMillis(),
            stationId = extractBits(data, 24, 12),
            epochTime = extractBits(data, 36, 30)
        )
    }

    private fun parseRTCM1127(data: ByteArray): RTCMMessage.BeiDouObservations {
        // BeiDou MSM7 메시지 파싱
        return RTCMMessage.BeiDouObservations(
            messageType = 1127,
            data = data,
            timestamp = System.currentTimeMillis(),
            stationId = extractBits(data, 24, 12),
            epochTime = extractBits(data, 36, 30)
        )
    }

    private fun extractBits(data: ByteArray, startBit: Int, numBits: Int): Long {
        var result = 0L
        for (i in 0 until numBits) {
            val byteIndex = (startBit + i) / 8
            val bitIndex = 7 - ((startBit + i) % 8)
            if (byteIndex < data.size) {
                val bit = (data[byteIndex].toInt() shr bitIndex) and 1
                result = (result shl 1) or bit.toLong()
            }
        }
        return result
    }

    private fun verifyCRC(data: ByteArray): Boolean {
        // CRC-24Q 검증 (간소화된 버전)
        if (data.size < 6) return false

        val length = data.size
        val providedCRC = ((data[length-3].toInt() and 0xFF) shl 16) or
                ((data[length-2].toInt() and 0xFF) shl 8) or
                (data[length-1].toInt() and 0xFF)

        val calculatedCRC = calculateCRC24Q(data, length - 3)

        return providedCRC == calculatedCRC
    }

    private fun calculateCRC24Q(data: ByteArray, length: Int): Int {
        // CRC-24Q 계산 (다항식: 0x1864CFB)
        var crc = 0
        val polynomial = 0x1864CFB

        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 16)
            for (j in 0 until 8) {
                if ((crc and 0x800000) != 0) {
                    crc = ((crc shl 1) and 0xFFFFFF) xor polynomial
                } else {
                    crc = (crc shl 1) and 0xFFFFFF
                }
            }
        }

        return crc
    }
}

// 데이터 클래스들
data class MountpointInfo(
    val name: String,
    val identifier: String,
    val format: String,
    val formatDetails: String,
    val carrier: String,
    val navigation: String,
    val network: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val nmea: Boolean,
    val solution: Boolean,
    val generator: String,
    val compression: String,
    val authentication: String,
    val fee: Boolean,
    val bitrate: Int,
    val miscellaneous: String
)

data class ConnectionStats(
    val isConnected: Boolean,
    val bytesReceived: Long,
    val messagesReceived: Long,
    val timeSinceLastData: Long
)

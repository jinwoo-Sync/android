// KMLDataLogger.kt - 5개 트랙 지원
package com.example.myapplication.data.gps

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class KMLDataLogger(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())

    // 5개 데이터 세트를 위한 버퍼
    private val rawGPSData = mutableListOf<FilteredPosition>()
    private val gpsOnlyData = mutableListOf<FilteredPosition>()
    private val imuIntegratedData = mutableListOf<FilteredPosition>()
    private val madStyleData = mutableListOf<FilteredPosition>()
    private val ntripData = mutableListOf<FilteredPosition>()

    private var isLogging = false
    private var sessionStartTime = ""

    fun startLogging() {
        isLogging = true
        sessionStartTime = dateFormat.format(Date())

        rawGPSData.clear()
        gpsOnlyData.clear()
        imuIntegratedData.clear()
        madStyleData.clear()
        ntripData.clear()
    }

    fun stopLogging() {
        isLogging = false

        if (rawGPSData.isNotEmpty() || gpsOnlyData.isNotEmpty() ||
            imuIntegratedData.isNotEmpty() || madStyleData.isNotEmpty() || ntripData.isNotEmpty()) {
            saveAllDataToKML()
        }
    }

    fun addRawGPSData(position: FilteredPosition) {
        if (isLogging) {
            rawGPSData.add(position)
        }
    }

    fun addGPSOnlyData(position: FilteredPosition) {
        if (isLogging) {
            gpsOnlyData.add(position)
        }
    }

    fun addIMUIntegratedData(position: FilteredPosition) {
        if (isLogging) {
            imuIntegratedData.add(position)
        }
    }

    fun addMadStyleData(position: FilteredPosition) {
        if (isLogging) {
            madStyleData.add(position)
        }
    }

    fun addNTRIPData(position: FilteredPosition) {
        if (isLogging) {
            ntripData.add(position)
        }
    }

    private fun saveAllDataToKML() {
        try {
            val baseDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "RTK_GPS_Data")
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            // 5개 분리된 KML 파일 생성
            saveDataSetToKML(rawGPSData, "Raw_GPS", baseDir)
            saveDataSetToKML(gpsOnlyData, "GPS_GNSS_Kalman", baseDir)
            saveDataSetToKML(imuIntegratedData, "IMU_GPS_Kalman", baseDir)
            saveDataSetToKML(madStyleData, "Mad_Style_Kalman", baseDir)
            saveDataSetToKML(ntripData, "NTRIP_RTK_GPS", baseDir)

            // 통합 KML 파일 생성
            saveCombinedKML(baseDir)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveDataSetToKML(dataSet: List<FilteredPosition>, dataType: String, baseDir: File) {
        if (dataSet.isEmpty()) return

        val fileName = "${dataType}_${sessionStartTime}.kml"
        val file = File(baseDir, fileName)

        val kmlContent = generateKMLContent(dataSet, dataType)

        FileWriter(file).use { writer ->
            writer.write(kmlContent)
        }
    }

    private fun saveCombinedKML(baseDir: File) {
        val fileName = "Combined_5Track_Data_${sessionStartTime}.kml"
        val file = File(baseDir, fileName)

        val kmlContent = generateCombinedKMLContent()

        FileWriter(file).use { writer ->
            writer.write(kmlContent)
        }
    }

    private fun generateKMLContent(dataSet: List<FilteredPosition>, dataType: String): String {
        val kml = StringBuilder()

        kml.append("""
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
            <Document>
                <name>$dataType Data - $sessionStartTime</name>
                <description>RTK GPS 데이터 수집 - $dataType</description>
                
                <Style id="${dataType}Style">
                    <LineStyle>
                        <color>${getColorForDataType(dataType)}</color>
                        <width>3</width>
                    </LineStyle>
                    <PointStyle>
                        <color>${getColorForDataType(dataType)}</color>
                        <scale>0.8</scale>
                    </PointStyle>
                </Style>
                
                <!-- 경로 라인 -->
                <Placemark>
                    <name>$dataType 경로</name>
                    <description>총 ${dataSet.size}개 포인트</description>
                    <styleUrl>#${dataType}Style</styleUrl>
                    <LineString>
                        <extrude>1</extrude>
                        <tessellate>1</tessellate>
                        <altitudeMode>absolute</altitudeMode>
                        <coordinates>
        """.trimIndent())

        // 좌표 데이터 추가
        dataSet.forEach { position ->
            kml.append("${position.longitude},${position.latitude},${position.altitude}\n")
        }

        kml.append("""
                        </coordinates>
                    </LineString>
                </Placemark>
                
        """.trimIndent())

        // 개별 포인트들 (정확도 정보 포함)
        dataSet.forEachIndexed { index, position ->
            if (index % 10 == 0) {  // 10개마다 포인트 마커 생성 (파일 크기 관리)
                val timestamp = timestampFormat.format(Date(position.timestamp))

                kml.append("""
                    <Placemark>
                        <name>Point ${index + 1}</name>
                        <description><![CDATA[
                            시간: $timestamp<br/>
                            정확도: ${String.format("%.3f", position.accuracy)}m<br/>
                            Fix 타입: ${position.fixType}<br/>
                            고도: ${String.format("%.3f", position.altitude)}m<br/>
                            지면높이: ${String.format("%.3f", position.heightAboveGround)}m<br/>
                            속도: ${String.format("%.2f", calculateSpeed(position.velocity))}m/s<br/>
                            품질: ${position.filterQuality}
                        ]]></description>
                        <styleUrl>#${dataType}Style</styleUrl>
                        <Point>
                            <coordinates>${position.longitude},${position.latitude},${position.altitude}</coordinates>
                        </Point>
                    </Placemark>
                    
                """.trimIndent())
            }
        }

        kml.append("""
            </Document>
            </kml>
        """.trimIndent())

        return kml.toString()
    }

    private fun generateCombinedKMLContent(): String {
        val kml = StringBuilder()

        kml.append("""
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
            <Document>
                <name>Combined 5-Track GPS Data - $sessionStartTime</name>
                <description>5가지 GPS/GNSS 처리 방법 비교 데이터</description>
                
                <Style id="RawGPSStyle">
                    <LineStyle>
                        <color>ff0000ff</color>
                        <width>3</width>
                    </LineStyle>
                </Style>
                
                <Style id="GPSGNSSStyle">
                    <LineStyle>
                        <color>ff00ff00</color>
                        <width>3</width>
                    </LineStyle>
                </Style>
                
                <Style id="IMUGPSStyle">
                    <LineStyle>
                        <color>ffff0000</color>
                        <width>4</width>
                    </LineStyle>
                </Style>
                
                <Style id="MadStyleStyle">
                    <LineStyle>
                        <color>ffff00ff</color>
                        <width>3</width>
                    </LineStyle>
                </Style>
                
                <Style id="NTRIPStyle">
                    <LineStyle>
                        <color>ff00a5ff</color>
                        <width>5</width>
                    </LineStyle>
                </Style>
                
        """.trimIndent())

        // 각 데이터 세트별로 경로 추가
        addPathToKML(kml, rawGPSData, "Raw GPS", "RawGPSStyle")
        addPathToKML(kml, gpsOnlyData, "GPS+GNSS Kalman", "GPSGNSSStyle")
        addPathToKML(kml, imuIntegratedData, "IMU+GPS Kalman", "IMUGPSStyle")
        addPathToKML(kml, madStyleData, "Mad Style Kalman", "MadStyleStyle")
        addPathToKML(kml, ntripData, "NTRIP RTK GPS", "NTRIPStyle")

        kml.append("""
            </Document>
            </kml>
        """.trimIndent())

        return kml.toString()
    }

    private fun addPathToKML(kml: StringBuilder, dataSet: List<FilteredPosition>, name: String, styleId: String) {
        if (dataSet.isEmpty()) return

        kml.append("""
            <Placemark>
                <name>$name (${dataSet.size} points)</name>
                <description>평균 정확도: ${String.format("%.3f", dataSet.map { it.accuracy }.average())}m</description>
                <styleUrl>#$styleId</styleUrl>
                <LineString>
                    <extrude>1</extrude>
                    <tessellate>1</tessellate>
                    <altitudeMode>absolute</altitudeMode>
                    <coordinates>
        """.trimIndent())

        dataSet.forEach { position ->
            kml.append("${position.longitude},${position.latitude},${position.altitude}\n")
        }

        kml.append("""
                    </coordinates>
                </LineString>
            </Placemark>
            
        """.trimIndent())
    }

    private fun getColorForDataType(dataType: String): String {
        return when (dataType) {
            "Raw_GPS" -> "ff0000ff"                    // 빨간색
            "GPS_GNSS_Kalman" -> "ff00ff00"            // 초록색
            "IMU_GPS_Kalman" -> "ffff0000"             // 파란색
            "Mad_Style_Kalman" -> "ffff00ff"           // 보라색
            "NTRIP_RTK_GPS" -> "ff00a5ff"              // 주황색
            else -> "ffffffff"                          // 흰색
        }
    }

    private fun calculateSpeed(velocity: Triple<Double, Double, Double>): Double {
        return sqrt(velocity.first * velocity.first +
                velocity.second * velocity.second +
                velocity.third * velocity.third)
    }

    fun getDataCounts(): Quintuple<Int, Int, Int, Int, Int> {
        return Quintuple(rawGPSData.size, gpsOnlyData.size, imuIntegratedData.size, madStyleData.size, ntripData.size)
    }

    fun isCurrentlyLogging(): Boolean = isLogging
}
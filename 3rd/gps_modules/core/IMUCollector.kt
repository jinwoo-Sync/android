package com.example.myapplication.gps_modules.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class IMUCollector(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var imuCallback: ((IMUData) -> Unit)? = null
    private var barometerCallback: ((BarometerData) -> Unit)? = null

    // 최신 센서 데이터 저장
    private var latestAccel = Triple(0.0, 0.0, 0.0)
    private var latestGyro = Triple(0.0, 0.0, 0.0)
    private var latestMag = Triple(0.0, 0.0, 0.0)

    fun setIMUCallback(cb: (IMUData) -> Unit) { imuCallback = cb }
    fun setBarometerCallback(cb: (BarometerData) -> Unit) { barometerCallback = cb }

    fun start(targetFrequencyHz: Int = 50) {
        println("===== IMU 센서 시작 (${targetFrequencyHz}Hz) =====")

        // 목표 주파수에 따른 센서 딜레이 계산
        val sensorDelay = when {
            targetFrequencyHz >= 100 -> SensorManager.SENSOR_DELAY_FASTEST  // ~200Hz
            targetFrequencyHz >= 50 -> SensorManager.SENSOR_DELAY_GAME      // ~50Hz
            targetFrequencyHz >= 20 -> SensorManager.SENSOR_DELAY_UI        // ~20Hz
            else -> SensorManager.SENSOR_DELAY_NORMAL                       // ~5Hz
        }

        println("센서 상태:")
        println("가속도계: ${accelerometer != null}")
        println("자이로스코프: ${gyroscope != null}")
        println("자력계: ${magnetometer != null}")
        println("기압계: ${barometer != null}")
        println("목표 주파수: ${targetFrequencyHz}Hz, 센서 딜레이: $sensorDelay")

        accelerometer?.let {
            val success = sensorManager.registerListener(this, it, sensorDelay)
            println("가속도계 등록: $success")
        }
        gyroscope?.let {
            val success = sensorManager.registerListener(this, it, sensorDelay)
            println("자이로스코프 등록: $success")
        }
        magnetometer?.let {
            val success = sensorManager.registerListener(this, it, sensorDelay)
            println("자력계 등록: $success")
        }
        barometer?.let {
            val success = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            println("기압계 등록: $success")
        }
    }

    fun stop() {
        println("IMU 센서 정지")
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        println("센서 정확도 변경: ${sensor?.type}, 정확도: $accuracy")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val timestamp = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = Triple(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
                sendCombinedIMUData(timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = Triple(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
                sendCombinedIMUData(timestamp)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                latestMag = Triple(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0].toDouble()
                barometerCallback?.invoke(BarometerData(pressure, 0.0, timestamp))
            }
        }
    }

    private fun sendCombinedIMUData(timestamp: Long) {
        // 가속도계와 자이로스코프 데이터가 모두 있을 때만 전송
        val imu = IMUData(
            accelerometer = latestAccel,
            gyroscope = latestGyro,
            magnetometer = latestMag,
            timestamp = timestamp
        )
        imuCallback?.invoke(imu)
    }
}
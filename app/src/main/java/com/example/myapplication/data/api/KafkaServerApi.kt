package com.example.myapplication.data.api

import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API Interface for Kafka Server Communication
 * Endpoints match kafka_server/services/go-server/main.go
 */
interface KafkaServerApi {

    // ========== Authentication Endpoints ==========

    /**
     * Login to get JWT token
     * Endpoint: POST /api/auth/login (via API Gateway)
     */
    @POST("/api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    // ========== Session Management ==========

    /**
     * Start a new sensor data collection session
     * Endpoint: POST /api/sensor/session/start
     * Requires: Authorization header with JWT token
     */
    @POST("/api/sensor/session/start")
    suspend fun startSession(
        @Header("Authorization") token: String,
        @Body request: SessionStartRequest
    ): Response<SensorDataResponse>

    /**
     * End an active sensor data collection session
     * Endpoint: POST /api/sensor/session/end
     * Requires: Authorization header with JWT token
     */
    @POST("/api/sensor/session/end")
    suspend fun endSession(
        @Header("Authorization") token: String,
        @Body request: SessionEndRequest
    ): Response<SensorDataResponse>

    // ========== Sensor Data Transmission ==========

    /**
     * Send a batch of sensor data (camera, GPS, IMU, YOLO)
     * Endpoint: POST /api/sensor/data
     * Requires: Authorization header with JWT token
     */
    @POST("/api/sensor/data")
    suspend fun sendSensorData(
        @Header("Authorization") token: String,
        @Body batch: SensorDataBatch
    ): Response<SensorDataResponse>

    // ========== Health Check ==========

    /**
     * Check server health status
     * Endpoint: GET /health
     */
    @GET("/health")
    suspend fun healthCheck(): Response<Map<String, Any>>
}

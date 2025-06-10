package com.example.myapplication.data.streaming

import com.example.myapplication.data.streaming.CompanyStreaming.CompanyStreaming
import com.example.myapplication.data.streaming.test.WebSocketStreamingClient

object StreamingClientFactory {
    fun createStreamingClient(transportType: String): StreamingClient {
        return when (transportType.lowercase()) {
            "websocket" -> WebSocketStreamingClient()
            "http" -> CompanyStreaming()
            else -> throw IllegalArgumentException("지원되지 않는 전송 방식: $transportType")
        }
    }
}
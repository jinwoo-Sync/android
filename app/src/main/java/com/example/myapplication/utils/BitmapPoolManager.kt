package com.example.myapplication.utils

import android.content.Context
import android.util.Log
import com.example.myapplication.utils.AdvancedTaggedBitmapPool
import com.example.myapplication.utils.HighSpeedZeroCopyProcessor

/**
 * 🏛️ Application-level BitmapPool Manager - 전역 싱글톤 관리
 */
class BitmapPoolManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "BitmapPoolManager"

        @Volatile
        private var INSTANCE: BitmapPoolManager? = null

        fun getInstance(context: Context): BitmapPoolManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BitmapPoolManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // 🎯 전역 싱글톤 BitmapPool
    val advancedTaggedBitmapPool = AdvancedTaggedBitmapPool(
        poolSize = 15,  // 좀 더 여유롭게
        width = 840,
        height = 840
    )

    // 🎯 전역 프로세서
    val highSpeedProcessor = HighSpeedZeroCopyProcessor(
        bitmapPool = advancedTaggedBitmapPool
    )

    init {
        Log.d(TAG, "🏛️ 전역 BitmapPoolManager 초기화 완료")
        Log.d(TAG, "📊 초기 풀 상태: ${advancedTaggedBitmapPool.getStatus()}")
    }

    /**
     * 🧹 풀 상태 정리 요청
     */
    fun requestPoolCleanup() {
        try {
            advancedTaggedBitmapPool.forceCleanupStaleReferences()
            Log.d(TAG, "🧹 BitmapPoolManager: 풀 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ BitmapPoolManager: 풀 정리 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * 📊 풀 상세 상태 조회
     */
    fun getPoolDetailedStatus(): String {
        return try {
            val status = advancedTaggedBitmapPool.getDetailedStatus()
            Log.d(TAG, "📊 BitmapPoolManager: 풀 상태 조회 완료")
            status
        } catch (e: Exception) {
            Log.e(TAG, "❌ BitmapPoolManager: 풀 상태 조회 실패: ${e.message}", e)
            "풀 상태 조회 실패: ${e.message}"
        }
    }

    /**
     * 🚨 응급 복구
     */
    fun performEmergencyReset() {
        try {
            advancedTaggedBitmapPool.performEmergencyReset()
            Log.w(TAG, "🚨 BitmapPoolManager: 응급 복구 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ BitmapPoolManager: 응급 복구 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * 📈 풀 건강성 체크
     */
/*    fun getPoolHealthStatus(): AdvancedTaggedBitmapPool.PoolHealthStatus {
        return advancedTaggedBitmapPool.getPoolHealthStatus()
    }*/

    /**
     * 🔄 풀 준비 상태 확인
     */
    fun isReady(): Boolean {
        return advancedTaggedBitmapPool.isReady()
    }

    /**
     * 🗑️ 최종 정리 (앱 종료시)
     */
    fun shutdown() {
        try {
            advancedTaggedBitmapPool.shutdown()
            INSTANCE = null
            Log.d(TAG, "🗑️ BitmapPoolManager 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ BitmapPoolManager 정리 실패: ${e.message}", e)
        }
    }
}
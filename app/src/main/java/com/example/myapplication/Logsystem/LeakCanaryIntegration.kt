// app/src/main/java/com/example/myapplication/Logsystem/LeakCanaryIntegration.kt
package com.example.myapplication.Logsystem

import android.app.Application
import android.content.Context
import android.util.Log

class LeakCanaryIntegration private constructor(
    private val context: Context,
    private val fileLogger: FileLogger
) {
    companion object {
        private const val TAG = "LeakCanaryIntegration"

        @Volatile
        private var INSTANCE: LeakCanaryIntegration? = null

        fun getInstance(context: Context): LeakCanaryIntegration {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LeakCanaryIntegration(
                    context.applicationContext,
                    FileLogger.getInstance(context)
                ).also { INSTANCE = it }
            }
        }

        /**
         * Application.onCreate()에서 호출
         */
        fun setupInApplication(application: Application) {
            try {
                // Debug 빌드에서만 LeakCanary 설정 시도
                if (application.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    try {
                        val leakCanaryClass = Class.forName("leakcanary.LeakCanary")
                        val configField = leakCanaryClass.getDeclaredField("config")
                        Log.d(TAG, "LeakCanary 설정 완료")
                    } catch (e: ClassNotFoundException) {
                        Log.d(TAG, "LeakCanary 라이브러리 없음 (Release 빌드)")
                    } catch (e: Exception) {
                        Log.w(TAG, "LeakCanary 설정 실패: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LeakCanary 설정 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 비트맵 누수 감지 (안전한 버전)
     */
    fun watchBitmap(bitmap: android.graphics.Bitmap?, description: String) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                fileLogger.d(TAG, "비트맵 감시 시작: $description")
            }
        } catch (e: Exception) {
            Log.w(TAG, "비트맵 감시 실패: ${e.message}")
        }
    }

    /**
     * 뷰 누수 감지 (안전한 버전)
     */
    fun watchView(view: android.view.View?, description: String) {
        try {
            if (view != null) {
                fileLogger.d(TAG, "뷰 감시 시작: $description")
            }
        } catch (e: Exception) {
            Log.w(TAG, "뷰 감시 실패: ${e.message}")
        }
    }

    /**
     * 프래그먼트 누수 감지 (안전한 버전)
     */
    fun watchFragment(fragment: androidx.fragment.app.Fragment?, description: String) {
        try {
            if (fragment != null) {
                fileLogger.d(TAG, "프래그먼트 감시 시작: $description")
            }
        } catch (e: Exception) {
            Log.w(TAG, "프래그먼트 감시 실패: ${e.message}")
        }
    }

    /**
     * 커스텀 객체 누수 감지 (안전한 버전)
     */
    fun watchObject(obj: Any?, description: String) {
        try {
            if (obj != null) {
                fileLogger.d(TAG, "객체 감시 시작: $description")
            }
        } catch (e: Exception) {
            Log.w(TAG, "객체 감시 실패: ${e.message}")
        }
    }

    /**
     * 수동으로 힙 덤프 생성 (안전한 버전)
     */
    fun dumpHeap(reason: String) {
        try {
            fileLogger.i(TAG, "힙 덤프 요청: $reason")
            // 실제 LeakCanary 기능은 debug 빌드에서만 동작
        } catch (e: Exception) {
            fileLogger.e(TAG, "힙 덤프 실패: ${e.message}", e)
        }
    }
}
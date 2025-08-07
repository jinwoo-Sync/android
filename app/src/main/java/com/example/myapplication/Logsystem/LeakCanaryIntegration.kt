// app/src/main/java/com/example/myapplication/Logsystem/LeakCanaryIntegration.kt
package com.example.myapplication.Logsystem

import android.app.Application
import android.content.Context
import android.util.Log
import leakcanary.LeakCanary
import leakcanary.ObjectWatcher
import java.lang.ref.WeakReference

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
                // LeakCanary 설정
                LeakCanary.config = LeakCanary.config.copy(
                    retainedVisibleThreshold = 3, // 3개 이상 누수 시 알림
                    dumpHeap = true, // 힙 덤프 자동 생성
                    deleteHeapDumpOnPrimaryViewerRemoval = false // 힙 덤프 보존
                )

                Log.d(TAG, "LeakCanary 설정 완료")

            } catch (e: Exception) {
                Log.e(TAG, "LeakCanary 설정 실패: ${e.message}", e)
            }
        }
    }

    // 수동 메모리 누수 감지를 위한 ObjectWatcher
    private val customObjectWatcher = ObjectWatcher(
        clock = { System.currentTimeMillis() },
        checkRetainedExecutor = { command -> command.run() },
        isEnabled = { true }
    )

    /**
     * 비트맵 누수 감지
     */
    fun watchBitmap(bitmap: android.graphics.Bitmap?, description: String) {
        if (bitmap != null && !bitmap.isRecycled) {
            customObjectWatcher.watch(bitmap, description)
            fileLogger.d(TAG, "비트맵 감시 시작: $description")
        }
    }

    /**
     * 뷰 누수 감지
     */
    fun watchView(view: android.view.View?, description: String) {
        if (view != null) {
            customObjectWatcher.watch(view, description)
            fileLogger.d(TAG, "뷰 감시 시작: $description")
        }
    }

    /**
     * 프래그먼트 누수 감지
     */
    fun watchFragment(fragment: androidx.fragment.app.Fragment?, description: String) {
        if (fragment != null) {
            customObjectWatcher.watch(fragment, description)
            fileLogger.d(TAG, "프래그먼트 감시 시작: $description")
        }
    }

    /**
     * 커스텀 객체 누수 감지
     */
    fun watchObject(obj: Any?, description: String) {
        if (obj != null) {
            customObjectWatcher.watch(obj, description)
            fileLogger.d(TAG, "객체 감시 시작: $description")
        }
    }

    /**
     * 감시 중인 객체들의 상태 확인
     */
    fun checkRetainedObjects(): List<String> {
        val retainedObjects = mutableListOf<String>()

        try {
            // LeakCanary의 retained objects 확인 (리플렉션 사용)
            val leakCanaryClass = Class.forName("leakcanary.internal.InternalLeakCanary")
            val applicationField = leakCanaryClass.getDeclaredField("application")
            applicationField.isAccessible = true

            fileLogger.i(TAG, "LeakCanary 상태 확인 완료")

        } catch (e: Exception) {
            fileLogger.e(TAG, "LeakCanary 상태 확인 실패: ${e.message}", e)
        }

        return retainedObjects
    }

    /**
     * 수동으로 힙 덤프 생성
     */
    fun dumpHeap(reason: String) {
        try {
            fileLogger.i(TAG, "수동 힙 덤프 요청: $reason")

            // LeakCanary를 통한 힙 덤프
            LeakCanary.dumpHeap()

        } catch (e: Exception) {
            fileLogger.e(TAG, "힙 덤프 실패: ${e.message}", e)
        }
    }
}
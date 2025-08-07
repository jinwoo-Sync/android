package com.example.myapplication

import android.app.Application
import com.example.myapplication.Logsystem.CrashHandler
import com.example.myapplication.Logsystem.LeakCanaryIntegration

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Debug 빌드 여부를 ApplicationInfo로 체크
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            initializeLeakCanary()
        }

        // LeakCanary 애플리케이션 레벨 설정
        LeakCanaryIntegration.setupInApplication(this)

        // 크래시 핸들러 설정
        CrashHandler.setup(this)
    }

    private fun initializeLeakCanary() {
        try {
            Class.forName("leakcanary.LeakCanary").let {
                android.util.Log.d("MyApplication", "LeakCanary initialized")
            }
        } catch (e: ClassNotFoundException) {
            android.util.Log.d("MyApplication", "LeakCanary not available (expected in release)")
        }
    }
}
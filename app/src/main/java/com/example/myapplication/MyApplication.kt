package com.example.myapplication

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Debug 빌드 여부를 ApplicationInfo로 체크
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            initializeLeakCanary()
        }
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
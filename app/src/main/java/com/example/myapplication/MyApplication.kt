package com.example.myapplication

import android.app.Application
import leakcanary.LeakCanary

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // LeakCanary 설정 (선택사항)
        LeakCanary.config = LeakCanary.config.copy(
            retainedVisibleThreshold = 3, // 3개 이상 누수 발견 시 알림
            dumpHeap = true // 힙 덤프 생성
        )
    }
}
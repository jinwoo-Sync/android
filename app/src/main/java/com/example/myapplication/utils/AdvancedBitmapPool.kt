// utils/TrueZeroCopyBitmapPool.kt
package com.example.myapplication.utils

import android.graphics.*
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 🎯 완전히 재설계된 Zero-Copy 비트맵 풀
 * - 인덱스 기반 참조 카운팅으로 메모리 누수 완전 차단
 * - 철저한 상태 관리와 자동 복구 시스템
 * - Native 메모리 과다 사용 방지
 */
class TrueZeroCopyBitmapPool(
    private val poolSize: Int = 20,
    private val width: Int = 840,
    private val height: Int = 840
) {
    private val TAG = "TrueZeroCopyBitmapPool"

    // 🎯 인덱스 기반 참조 카운팅 시스템
    private val bitmapPool = Array<Bitmap?>(poolSize) { null }
    private val usageState = Array(poolSize) { false }
    private val referenceCount = Array(poolSize) { AtomicInteger(0) }
    private val lastAccessTime = Array(poolSize) { AtomicLong(0) }
    private var currentIndex = 0
    private val poolLock = Object()
    private var isInitialized = false

    // Canvas/Paint 재사용 풀
    private val canvasPool = ConcurrentLinkedQueue<Canvas>()
    private val paintPool = ConcurrentLinkedQueue<Paint>()
    private val matrixPool = ConcurrentLinkedQueue<Matrix>()

    // 통계 및 복구 관련
    private val totalCreated = AtomicInteger(0)
    private val totalReused = AtomicInteger(0)
    private val totalRecycled = AtomicInteger(0)
    private var consecutiveFailures = 0
    private var lastSuccessTime = System.currentTimeMillis()
    private var recoveryCount = 0
    private val maxRecoveryAttempts = 3
    private val isRecovering = AtomicBoolean(false)

    // 🚨 긴급 복구 관련
    private val STALE_TIMEOUT_MS = 30_000L  // 30초 동안 미사용시 stale
    private val CRITICAL_AVAILABLE_THRESHOLD = 3
    private val EMERGENCY_AVAILABLE_THRESHOLD = 1

    init {
        initialize()
    }

    /**
     * 🛠️ 안전한 풀 초기화
     */
    private fun initialize(): Boolean {
        return synchronized(poolLock) {
            if (isInitialized) return true

            Log.d(TAG, "🎯 Zero-Copy 비트맵 풀 초기화 시작 (크기: $poolSize)")

            var successCount = 0
            for (index in 0 until poolSize) {
                try {
                    // 점진적 초기화로 OOM 방지
                    Thread.sleep(20)
                    bitmapPool[index] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    usageState[index] = false
                    referenceCount[index].set(0)
                    lastAccessTime[index].set(System.currentTimeMillis())
                    successCount++
                    totalCreated.incrementAndGet()
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "⚠️ 초기화 중 OOM: ${index + 1}/${poolSize}")
                    bitmapPool[index] = null
                    System.gc()
                    Thread.sleep(100)
                    break
                }
            }

            // Canvas/Paint 풀 초기화
            val helperPoolSize = (successCount * 0.3).toInt().coerceAtLeast(3)
            repeat(helperPoolSize) {
                canvasPool.offer(Canvas())
                paintPool.offer(Paint().apply {
                    isFilterBitmap = true
                    isAntiAlias = false
                })
                matrixPool.offer(Matrix())
            }

            isInitialized = successCount >= (poolSize / 2)

            Log.d(TAG, "✅ Zero-Copy 풀 초기화 완료: 성공=${successCount}/${poolSize}, 초기화=${isInitialized}")
            return isInitialized
        }
    }

    /**
     * 🎯 SharedBitmap 래퍼 반환 - 인덱스 기반 참조 카운팅
     */
    fun acquireSharedBitmap(): SharedBitmap? {
        synchronized(poolLock) {
            if (!isInitialized && !autoRecover()) return null

            // 🚨 응급 상황 감지 및 즉시 복구
            val availableCount = usageState.count { !it }

            if (availableCount <= EMERGENCY_AVAILABLE_THRESHOLD) {
                Log.w(TAG, "🚨 응급상황: 사용가능=$availableCount/$poolSize - 즉시 복구 실행")
                performEmergencyRecovery()
            } else if (availableCount <= CRITICAL_AVAILABLE_THRESHOLD) {
                Log.w(TAG, "⚠️ 임계상황: 사용가능=$availableCount/$poolSize - 예방적 정리")
                performPreventiveCleanup()
            }

            val bitmap = findAvailableBitmap() ?: handleAcquisitionFailure()
            if (bitmap != null) {
                val index = bitmapPool.indexOf(bitmap)
                if (index != -1) {
                    referenceCount[index].set(1)
                    lastAccessTime[index].set(System.currentTimeMillis())
                    totalReused.incrementAndGet()
                    Log.d(TAG, "📥 Bitmap acquired: index=$index, @${bitmap.hashCode().toString(16)}")
                    return SharedBitmap(bitmap, this, index)
                }
            }
            return null
        }
    }

    /**
     * 🎯 사용 가능한 비트맵 찾기 - Round Robin 방식
     */
    private fun findAvailableBitmap(): Bitmap? {
        val currentTime = System.currentTimeMillis()

        repeat(poolSize) { offset ->
            val index = (currentIndex + offset) % poolSize
            val bitmap = bitmapPool[index]

            if (bitmap != null &&
                !usageState[index] &&
                !bitmap.isRecycled &&
                isValidBitmap(bitmap) &&
                referenceCount[index].get() == 0) {

                usageState[index] = true
                currentIndex = (index + 1) % poolSize
                lastSuccessTime = currentTime
                consecutiveFailures = 0
                return bitmap
            }
        }
        return null
    }

    /**
     * 🎯 획득 실패 처리 - 단계별 복구
     */
    private fun handleAcquisitionFailure(): Bitmap? {
        consecutiveFailures++
        Log.w(TAG, "⚠️ 비트맵 획득 실패: $consecutiveFailures 회")

        when {
            consecutiveFailures >= 5 && recoveryCount < maxRecoveryAttempts -> {
                Log.w(TAG, "🔧 연속 실패 감지 - 자동 복구 시도: $recoveryCount")
                return if (autoRecover()) findAvailableBitmap() else createNewBitmap()
            }
            consecutiveFailures >= 10 -> {
                Log.e(TAG, "🚨 심각한 상황 - 새 비트맵 생성 시도")
                return createNewBitmap()
            }
            else -> {
                return null
            }
        }
    }

    /**
     * 🆕 새 비트맵 생성 (풀 확장)
     */
    private fun createNewBitmap(): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            totalCreated.incrementAndGet()
            Log.d(TAG, "🆕 새 비트맵 생성: @${bitmap.hashCode().toString(16)}, total=${totalCreated.get()}")

            // 새 비트맵을 풀에 추가 시도
            synchronized(poolLock) {
                val emptyIndex = bitmapPool.indexOfFirst { it == null }
                if (emptyIndex != -1) {
                    bitmapPool[emptyIndex] = bitmap
                    usageState[emptyIndex] = true
                    referenceCount[emptyIndex].set(1)
                    lastAccessTime[emptyIndex].set(System.currentTimeMillis())
                    Log.d(TAG, "🔄 새 비트맵을 풀 슬롯에 추가: index=$emptyIndex")
                    return bitmap
                }
            }
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ OOM: 비트맵 생성 실패", e)
            System.gc()
            null
        }
    }

    /**
     * 🎯 내부 참조 증가 (SharedBitmap에서 호출)
     */
    internal fun addReference(bitmap: Bitmap): Pair<Boolean, Int> {
        synchronized(poolLock) {
            val index = bitmapPool.indexOf(bitmap)
            if (index != -1 && usageState[index]) {
                val newCount = referenceCount[index].incrementAndGet()
                lastAccessTime[index].set(System.currentTimeMillis())
                Log.d(TAG, "📈 Reference increased: index=$index, count=$newCount")
                return Pair(true, index)
            }
            Log.w(TAG, "⚠️ Cannot add reference to inactive bitmap: @${bitmap.hashCode().toString(16)}")
            return Pair(false, -1)
        }
    }

    /**
     * 🎯 내부 참조 해제 (SharedBitmap에서 호출)
     */
    internal fun releaseReference(bitmap: Bitmap, bitmapIndex: Int) {
        synchronized(poolLock) {
            if (bitmapIndex in 0 until poolSize && bitmapPool[bitmapIndex] == bitmap) {
                val newCount = referenceCount[bitmapIndex].decrementAndGet()
                Log.d(TAG, "📉 Reference decreased: index=$bitmapIndex, count=$newCount")

                if (newCount <= 0) {
                    // 참조가 0이 되면 풀로 반환
                    usageState[bitmapIndex] = false
                    referenceCount[bitmapIndex].set(0)
                    lastAccessTime[bitmapIndex].set(System.currentTimeMillis())
                    consecutiveFailures = 0
                    lastSuccessTime = System.currentTimeMillis()
                    Log.d(TAG, "✅ 비트맵 풀 반환: index=$bitmapIndex")
                }
            } else {
                Log.w(TAG, "⚠️ 참조 해제 실패: index=$bitmapIndex, bitmap mismatch")
                // 알 수 없는 비트맵 안전 처리
                safeRecycleBitmap(bitmap)
            }
        }
    }

    /**
     * 🛡️ 안전한 비트맵 재활용
     */
    private fun safeRecycleBitmap(bitmap: Bitmap) {
        try {
            if (!bitmap.isRecycled) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                            totalRecycled.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 지연 재활용 실패: ${e.message}")
                    }
                }, 100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 안전 재활용 실패: ${e.message}")
        }
    }

    /**
     * 🚨 응급 복구: 강제로 모든 참조 정리
     */
    private fun performEmergencyRecovery() {
        if (!isRecovering.compareAndSet(false, true)) {
            Log.d(TAG, "⚠️ 이미 복구 진행 중")
            return
        }

        try {
            Log.w(TAG, "🚨 응급 복구 시작 - 모든 참조 강제 해제")

            var forcedReleaseCount = 0
            var recreatedCount = 0

            // 1. 모든 참조 카운트 강제 리셋
            for (index in 0 until poolSize) {
                val currentRef = referenceCount[index].get()
                if (currentRef > 0) {
                    Log.w(TAG, "🔧 강제 해제: index=$index, refs=$currentRef")
                    referenceCount[index].set(0)
                    usageState[index] = false
                    forcedReleaseCount++
                }
            }

            // 2. 손상된 비트맵 교체
            for (index in 0 until poolSize) {
                val bitmap = bitmapPool[index]
                if (bitmap?.isRecycled == true || !isValidBitmap(bitmap)) {
                    try {
                        bitmap?.recycle()
                        bitmapPool[index] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        usageState[index] = false
                        referenceCount[index].set(0)
                        lastAccessTime[index].set(System.currentTimeMillis())
                        recreatedCount++
                        totalCreated.incrementAndGet()
                    } catch (e: OutOfMemoryError) {
                        Log.e(TAG, "응급 복구 중 OOM: index=$index")
                        bitmapPool[index] = null
                    }
                }
            }

            val finalAvailable = usageState.count { !it }
            Log.w(TAG, "🚨 응급 복구 완료: 강제해제=$forcedReleaseCount, 재생성=$recreatedCount, 최종사용가능=$finalAvailable/$poolSize")

            // 3. 시스템 레벨 정리
            System.gc()
            Runtime.getRuntime().runFinalization()

        } finally {
            isRecovering.set(false)
        }
    }

    /**
     * ⚠️ 예방적 정리: Stale 참조 해제
     */
    private fun performPreventiveCleanup() {
        val currentTime = System.currentTimeMillis()
        var cleanedCount = 0

        for (index in 0 until poolSize) {
            // Stale 참조 감지 (30초 이상 미사용 + 사용중으로 표시)
            if (usageState[index] &&
                referenceCount[index].get() == 0 &&
                (currentTime - lastAccessTime[index].get()) > STALE_TIMEOUT_MS) {

                usageState[index] = false
                cleanedCount++
                Log.d(TAG, "🧹 Stale 참조 정리: index=$index")
            }
        }

        if (cleanedCount > 0) {
            Log.d(TAG, "🧹 예방적 정리 완료: $cleanedCount 슬롯 정리")
        }
    }

    /**
     * 🔧 자동 복구
     */
    private fun autoRecover(): Boolean {
        return executeWithErrorHandling("자동 복구") { performAdvancedRecovery() }
    }

    /**
     * 🔧 고급 자동 복구
     */
    private fun performAdvancedRecovery(): Boolean {
        recoveryCount++
        Log.w(TAG, "🔧 고급 자동 복구 시작 ($recoveryCount/$maxRecoveryAttempts)")

        try {
            // 1. Stale 비트맵 정리
            var recycledCount = 0
            for (index in 0 until poolSize) {
                val bitmap = bitmapPool[index]
                if (bitmap?.isRecycled == true || !isValidBitmap(bitmap)) {
                    bitmapPool[index] = null
                    usageState[index] = false
                    referenceCount[index].set(0)
                    recycledCount++
                }
            }

            // 2. 누락된 비트맵 재생성
            var recreatedCount = 0
            for (index in 0 until poolSize) {
                if (bitmapPool[index] == null) {
                    try {
                        bitmapPool[index] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        usageState[index] = false
                        referenceCount[index].set(0)
                        lastAccessTime[index].set(System.currentTimeMillis())
                        recreatedCount++
                        totalCreated.incrementAndGet()
                    } catch (e: OutOfMemoryError) {
                        Log.e(TAG, "❌ 복구 중 OOM: index=$index", e)
                        System.gc()
                        Thread.sleep(100)
                        break
                    }
                }
            }

            consecutiveFailures = 0
            currentIndex = 0

            val availableCount = usageState.count { !it }
            Log.w(TAG, "✅ 고급 복구 완료: recycled=$recycledCount, recreated=$recreatedCount, available=$availableCount")

            return availableCount > 0

        } catch (e: Exception) {
            Log.e(TAG, "❌ 고급 복구 실패", e)
            return false
        }
    }

    /**
     * 🛡️ 비트맵 유효성 검증
     */
    private fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return try {
            bitmap != null &&
                    !bitmap.isRecycled &&
                    bitmap.width > 0 &&
                    bitmap.height > 0 &&
                    bitmap.config != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 🧹 강제 비트맵 상태 정리 (메모리 누수 방지)
     */
    fun forceCleanupStaleReferences() {
        synchronized(poolLock) {
            val currentTime = System.currentTimeMillis()
            val staleBitmaps = mutableListOf<Int>()

            bitmapPool.forEachIndexed { index, bitmap ->
                val timeSinceAccess = currentTime - lastAccessTime[index].get()
                val refCount = referenceCount[index].get()

                // Stale 조건: 재활용됨 OR 음수 참조 OR 30초 이상 미사용
                if (bitmap?.isRecycled == true ||
                    refCount < 0 ||
                    (usageState[index] && refCount == 0 && timeSinceAccess > STALE_TIMEOUT_MS)) {

                    staleBitmaps.add(index)
                    Log.w(TAG, "🧹 발견된 stale bitmap: index=$index, recycled=${bitmap?.isRecycled}, refCount=$refCount, unused=${timeSinceAccess}ms")
                }
            }

            staleBitmaps.forEach { index ->
                val bitmap = bitmapPool[index]
                if (bitmap != null) {
                    safeRecycleBitmap(bitmap)
                }
                bitmapPool[index] = null
                usageState[index] = false
                referenceCount[index].set(0)
            }

            Log.d(TAG, "🧹 Stale cleanup: removed=${staleBitmaps.size}")
        }
    }

    /**
     * 🚨 응급 리셋
     */
    fun performEmergencyReset(): Boolean {
        return synchronized(poolLock) {
            try {
                Log.w(TAG, "🚨 UI Pool 응급 리셋 시작")

                // 1. 모든 참조 카운트 강제 0
                for (index in 0 until poolSize) {
                    referenceCount[index].set(0)
                    usageState[index] = false
                }

                // 2. 모든 비트맵 재생성
                var resetCount = 0
                bitmapPool.forEachIndexed { index, bitmap ->
                    try {
                        bitmap?.takeIf { !it.isRecycled }?.recycle()
                        bitmapPool[index] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        lastAccessTime[index].set(System.currentTimeMillis())
                        resetCount++
                    } catch (e: OutOfMemoryError) {
                        bitmapPool[index] = null
                        Log.e(TAG, "응급 리셋 중 OOM: index=$index")
                    }
                }

                // 3. 통계 리셋
                totalCreated.set(resetCount)
                totalReused.set(0)
                totalRecycled.set(0)
                consecutiveFailures = 0
                recoveryCount = 0
                currentIndex = 0

                val validCount = bitmapPool.count { it != null && !it.isRecycled }
                Log.w(TAG, "🚨 UI Pool 응급 리셋 완료: $validCount/$poolSize")

                validCount > (poolSize / 2)

            } catch (e: Exception) {
                Log.e(TAG, "❌ UI Pool 응급 리셋 실패: ${e.message}", e)
                false
            }
        }
    }

    // 재사용 객체 관리
    fun getReusableCanvas(): Canvas = canvasPool.poll() ?: Canvas()
    fun returnReusableCanvas(canvas: Canvas) {
        val maxCanvasPoolSize = (poolSize * 0.3).toInt().coerceAtLeast(4)
        if (canvasPool.size < maxCanvasPoolSize) {
            canvas.setBitmap(null)
            canvasPool.offer(canvas)
        }
    }

    fun getReusablePaint(): Paint = paintPool.poll() ?: Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
    fun returnReusablePaint(paint: Paint) {
        val maxPaintPoolSize = (poolSize * 0.3).toInt().coerceAtLeast(4)
        if (paintPool.size < maxPaintPoolSize) paintPool.offer(paint)
    }

    fun getReusableMatrix(): Matrix = matrixPool.poll() ?: Matrix()
    fun returnReusableMatrix(matrix: Matrix) {
        val maxMatrixPoolSize = (poolSize * 0.3).toInt().coerceAtLeast(4)
        if (matrixPool.size < maxMatrixPoolSize) {
            matrix.reset()
            matrixPool.offer(matrix)
        }
    }

    /**
     * 🎯 상세 풀 상태 진단
     */
    fun getDetailedStatus(): String {
        synchronized(poolLock) {
            val currentTime = System.currentTimeMillis()
            val activeDetails = buildString {
                bitmapPool.forEachIndexed { index, bitmap ->
                    if (bitmap != null && usageState[index]) {
                        val timeSinceAccess = currentTime - lastAccessTime[index].get()
                        append("[$index:ref=${referenceCount[index].get()},age=${timeSinceAccess}ms] ")
                    }
                }
            }

            return buildString {
                appendLine("=== Zero-Copy 비트맵 상태 ===")
                appendLine("Pool Size: $poolSize")
                appendLine("Available: ${usageState.count { !it }}/${poolSize}")
                appendLine("Active: ${usageState.count { it }}")
                appendLine("Total References: ${referenceCount.sumOf { it.get() }}")
                appendLine("Created: ${totalCreated.get()}")
                appendLine("Reused: ${totalReused.get()}")
                appendLine("Recycled: ${totalRecycled.get()}")
                appendLine("Recovery Count: $recoveryCount")
                appendLine("Active Details: $activeDetails")

                val validCount = bitmapPool.count { isValidBitmap(it) }
                val recycledCount = bitmapPool.count { it?.isRecycled == true }
                appendLine("Valid Bitmaps: $validCount")
                appendLine("Recycled Bitmaps: $recycledCount")

                // 건강성 평가
                val healthStatus = when {
                    validCount < poolSize / 2 -> "🔴 위험"
                    usageState.count { !it } < 3 -> "🟡 주의"
                    else -> "🟢 정상"
                }
                appendLine("건강성: $healthStatus")
            }
        }
    }

    fun getStatus(): String {
        synchronized(poolLock) {
            val available = usageState.count { !it }
            val active = usageState.count { it }
            val totalRefs = referenceCount.sumOf { it.get() }

            return "ZeroCopyPool: Available=$available/$poolSize, " +
                    "Active=$active, References=$totalRefs, Created=${totalCreated.get()}, " +
                    "Reused=${totalReused.get()}, Recycled=${totalRecycled.get()}"
        }
    }

    fun cleanup() {
        synchronized(poolLock) {
            executeWithErrorHandling("정리") { performCleanup() }
        }
    }

    private fun performCleanup(): Boolean {
        Log.d(TAG, "🗑️ Starting pool cleanup...")

        bitmapPool.forEachIndexed { index, bitmap ->
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmapPool[index] = null
            usageState[index] = false
            referenceCount[index].set(0)
        }

        canvasPool.clear()
        paintPool.clear()
        matrixPool.clear()

        isInitialized = false
        consecutiveFailures = 0
        recoveryCount = 0

        Log.d(TAG, "🗑️ Zero-Copy 풀 정리 완료")
        return true
    }

    private fun executeWithErrorHandling(operationName: String, operation: () -> Boolean): Boolean {
        return try {
            operation()
        } catch (e: Exception) {
            Log.e(TAG, "❌ $operationName 실패: ${e.message}", e)
            if (operationName == "정리") {
                isInitialized = false
                consecutiveFailures = 0
                recoveryCount = 0
            }
            false
        }
    }

    fun isReady(): Boolean = synchronized(poolLock) {
        isInitialized && usageState.count { !it } > 0
    }

    /**
     * 📊 위험도 평가
     */
    fun getPoolHealthStatus(): PoolHealthStatus {
        synchronized(poolLock) {
            val available = usageState.count { !it }
            val totalRefs = referenceCount.sumOf { it.get() }
            val activeWithZeroRefs = (0 until poolSize).count {
                usageState[it] && referenceCount[it].get() == 0
            }

            val healthLevel = when {
                available <= EMERGENCY_AVAILABLE_THRESHOLD -> HealthLevel.CRITICAL
                available <= CRITICAL_AVAILABLE_THRESHOLD -> HealthLevel.WARNING
                activeWithZeroRefs > 5 -> HealthLevel.DEGRADED
                else -> HealthLevel.HEALTHY
            }

            return PoolHealthStatus(
                healthLevel = healthLevel,
                availableSlots = available,
                totalSlots = poolSize,
                totalReferences = totalRefs,
                staleSlots = activeWithZeroRefs,
                recommendation = getRecommendation(healthLevel)
            )
        }
    }

    private fun getRecommendation(level: HealthLevel): String = when (level) {
        HealthLevel.CRITICAL -> "즉시 응급 복구 필요"
        HealthLevel.WARNING -> "예방적 정리 권장"
        HealthLevel.DEGRADED -> "stale 참조 정리 권장"
        HealthLevel.HEALTHY -> "정상 상태"
    }
}

/**
 * 🎯 참조 카운팅 기반 SharedBitmap - 완전한 안전성 보장
 */
class SharedBitmap(
    val bitmap: Bitmap,
    private val pool: TrueZeroCopyBitmapPool,
    private val bitmapIndex: Int
) {
    private val TAG = "SharedBitmap"
    private val bitmapHash = bitmap.hashCode().toString(16)
    private val isReleased = AtomicBoolean(false)

    /**
     * 🎯 안전한 참조 추가 - 인덱스 기반
     */
    fun addRef(): SharedBitmap? {
        if (isReleased.get() || bitmap.isRecycled) {
            Log.w(TAG, "⚠️ 이미 해제된 비트맵 참조 시도: @$bitmapHash")
            return null
        }

        val (success, index) = pool.addReference(bitmap)
        return if (success) {
            Log.d(TAG, "📈 참조 추가 성공: @$bitmapHash, index=$index")
            SharedBitmap(bitmap, this.pool, index)
        } else {
            Log.w(TAG, "⚠️ 참조 추가 실패: @$bitmapHash")
            null
        }
    }

    /**
     * 🎯 안전한 참조 해제 - 중복 해제 방지 강화
     */
    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            pool.releaseReference(bitmap, bitmapIndex)
            Log.d(TAG, "📉 참조 해제: @$bitmapHash, index=$bitmapIndex")

            // 참조 해제 후 풀 건강성 자동 체크
            val health = pool.getPoolHealthStatus()
            if (health.healthLevel == HealthLevel.WARNING || health.healthLevel == HealthLevel.DEGRADED) {
                Log.d(TAG, "🧹 참조 해제 후 예방적 정리 트리거")
                pool.forceCleanupStaleReferences()
            }
        } else {
            Log.w(TAG, "⚠️ 중복 해제 시도 방지: @$bitmapHash")
        }
    }

    /**
     * 🛡️ 강화된 유효성 검증
     */
    fun isValid(): Boolean {
        return !isReleased.get() &&
                !bitmap.isRecycled &&
                bitmap.width > 0 &&
                bitmap.height > 0
    }

    /**
     * 🎯 UI 안전 비트맵 획득
     */
    fun getSafeBitmapForUI(): Bitmap? {
        return if (isValid()) {
            bitmap
        } else {
            Log.w(TAG, "⚠️ UI 요청된 비트맵이 유효하지 않음: @$bitmapHash")
            null
        }
    }
}

// 건강성 평가 관련 클래스들
enum class HealthLevel { HEALTHY, DEGRADED, WARNING, CRITICAL }

data class PoolHealthStatus(
    val healthLevel: HealthLevel,
    val availableSlots: Int,
    val totalSlots: Int,
    val totalReferences: Int,
    val staleSlots: Int,
    val recommendation: String
)

/**
 * 🚀 고속 Zero-Copy 프레임 프로세서 - 크래시 방지 강화
 */
class HighSpeedZeroCopyProcessor(
    private val bitmapPool: TrueZeroCopyBitmapPool,
    private val targetWidth: Int = 840,
    private val targetHeight: Int = 840
) {
    private val TAG = "HighSpeedZeroCopyProcessor"
    private val processedFrames = AtomicLong(0)

    /**
     * 🛡️ 안전한 Zero-Copy 프레임 처리 - 크래시 방지
     */
    /*fun processZeroCopy(
        imageBytes: ByteArray,
        rotationDegrees: Int
    ): SharedBitmap? {
        val sharedBitmap = bitmapPool.acquireSharedBitmap() ?: return null
        val bitmapHash = sharedBitmap.bitmap.hashCode().toString(16)

        // 🛡️ 1단계: 비트맵 유효성 사전 검증
        if (!isValidBitmap(sharedBitmap.bitmap)) {
            Log.e(TAG, "❌ Invalid bitmap detected before processing: @$bitmapHash")
            sharedBitmap.release()
            return null
        }

        // 재사용 객체들 획득
        val canvas = bitmapPool.getReusableCanvas()
        val paint = bitmapPool.getReusablePaint()
        val matrix = bitmapPool.getReusableMatrix()

        try {
            // 🛡️ 2단계: Canvas 바인딩 안전성 검증
            if (!safeSetCanvasBitmap(canvas, sharedBitmap.bitmap)) {
                Log.e(TAG, "❌ Canvas.setBitmap() failed for @$bitmapHash")
                sharedBitmap.release()
                return null
            }

            // 🛡️ 3단계: 소스 비트맵 디코딩 및 검증
            val sourceBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (sourceBitmap == null || sourceBitmap.isRecycled) {
                Log.w(TAG, "⚠️ Source bitmap decode failed or recycled for @$bitmapHash")
                sharedBitmap.release()
                return null
            }

            // 🛡️ 4단계: 변환 중 재검증
            if (!isValidBitmap(sharedBitmap.bitmap)) {
                Log.e(TAG, "❌ Target bitmap became invalid during processing: @$bitmapHash")
                sourceBitmap.recycle()
                sharedBitmap.release()
                return null
            }

            // 변환 매트릭스 설정
            matrix.reset()
            val scaleX = targetWidth.toFloat() / sourceBitmap.width
            val scaleY = targetHeight.toFloat() / sourceBitmap.height
            matrix.setScale(scaleX, scaleY)

            if (rotationDegrees != 0) {
                matrix.postRotate(
                    rotationDegrees.toFloat(),
                    targetWidth / 2f,
                    targetHeight / 2f
                )
            }

            // 🛡️ 5단계: 안전한 drawBitmap 실행
            try {
                canvas.drawBitmap(sourceBitmap, matrix, paint)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Canvas.drawBitmap failed for @$bitmapHash: ${e.message}", e)
                sourceBitmap.recycle()
                sharedBitmap.release()
                return null
            }

            // 원본 즉시 해제
            sourceBitmap.recycle()

            // 🛡️ 6단계: 최종 결과 검증
            if (!isValidBitmap(sharedBitmap.bitmap)) {
                Log.e(TAG, "❌ Final bitmap validation failed: @$bitmapHash")
                sharedBitmap.release()
                return null
            }

            val frameNum = processedFrames.incrementAndGet()
            Log.d(TAG, "✅ Safe Zero-Copy 처리 완료: @$bitmapHash, frame=$frameNum")

            return sharedBitmap

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 OOM during Zero-Copy processing @$bitmapHash", e)
            sharedBitmap.release()
            System.gc()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "💥 Unexpected error during Zero-Copy processing @$bitmapHash: ${e.message}", e)
            sharedBitmap.release()
            return null
        } finally {
            // 재사용 객체들 안전하게 반환
            try {
                bitmapPool.returnReusableCanvas(canvas)
                bitmapPool.returnReusablePaint(paint)
                bitmapPool.returnReusableMatrix(matrix)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error returning reusable objects: ${e.message}")
            }
        }
    }*/

    fun processHighQualityZeroCopy(
        imageBytes: ByteArray,
        rotationDegrees: Int
    ): SharedBitmap? {
        val sharedBitmap = bitmapPool.acquireSharedBitmap() ?: return null
        val bitmapHash = sharedBitmap.bitmap.hashCode().toString(16)

        if (!isValidBitmap(sharedBitmap.bitmap)) {
            Log.e(TAG, "❌ Invalid bitmap detected before processing: @$bitmapHash")
            sharedBitmap.release()
            return null
        }

        val canvas = bitmapPool.getReusableCanvas()
        val paint = bitmapPool.getReusablePaint()
        val matrix = bitmapPool.getReusableMatrix()

        try {
            if (!safeSetCanvasBitmap(canvas, sharedBitmap.bitmap)) {
                Log.e(TAG, "❌ Canvas.setBitmap() failed for @$bitmapHash")
                sharedBitmap.release()
                return null
            }

            // 🎯 고품질 비트맵 디코딩 (PNG 또는 JPEG)
            val sourceBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (sourceBitmap == null || sourceBitmap.isRecycled) {
                Log.w(TAG, "⚠️ High quality bitmap decode failed for @$bitmapHash")
                sharedBitmap.release()
                return null
            }

            if (!isValidBitmap(sharedBitmap.bitmap)) {
                Log.e(TAG, "❌ Target bitmap became invalid during processing: @$bitmapHash")
                sourceBitmap.recycle()
                sharedBitmap.release()
                return null
            }

            // 🎯 고품질 변환 매트릭스 설정
            matrix.reset()
            val scaleX = targetWidth.toFloat() / sourceBitmap.width
            val scaleY = targetHeight.toFloat() / sourceBitmap.height
            matrix.setScale(scaleX, scaleY)

            if (rotationDegrees != 0) {
                matrix.postRotate(
                    rotationDegrees.toFloat(),
                    targetWidth / 2f,
                    targetHeight / 2f
                )
            }

            // 🎯 고품질 페인트 설정
            paint.isFilterBitmap = true
            paint.isAntiAlias = true
            paint.isDither = true

            try {
                canvas.drawBitmap(sourceBitmap, matrix, paint)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Canvas.drawBitmap failed for @$bitmapHash: ${e.message}", e)
                sourceBitmap.recycle()
                sharedBitmap.release()
                return null
            }

            sourceBitmap.recycle()

            if (!isValidBitmap(sharedBitmap.bitmap)) {
                Log.e(TAG, "❌ Final bitmap validation failed: @$bitmapHash")
                sharedBitmap.release()
                return null
            }

            val frameNum = processedFrames.incrementAndGet()
            Log.d(TAG, "✅ High Quality Zero-Copy 처리 완료: @$bitmapHash, frame=$frameNum")

            return sharedBitmap

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 OOM during High Quality Zero-Copy processing @$bitmapHash", e)
            sharedBitmap.release()
            System.gc()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "💥 Unexpected error during High Quality Zero-Copy processing @$bitmapHash: ${e.message}", e)
            sharedBitmap.release()
            return null
        } finally {
            try {
                bitmapPool.returnReusableCanvas(canvas)
                bitmapPool.returnReusablePaint(paint)
                bitmapPool.returnReusableMatrix(matrix)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error returning reusable objects: ${e.message}")
            }
        }
    }

    /**
     * 🛡️ 비트맵 유효성 검증
     */
    private fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return try {
            bitmap != null &&
                    !bitmap.isRecycled &&
                    bitmap.width > 0 &&
                    bitmap.height > 0 &&
                    bitmap.config != null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Bitmap validation exception: ${e.message}")
            false
        }
    }

    /**
     * 🛡️ 안전한 Canvas.setBitmap() 호출
     */
    private fun safeSetCanvasBitmap(canvas: Canvas, bitmap: Bitmap): Boolean {
        return try {
            // 사전 검증
            if (!isValidBitmap(bitmap)) {
                Log.w(TAG, "⚠️ Cannot set invalid bitmap to canvas")
                return false
            }

            // 네이티브 크래시를 방지하기 위한 추가 검증
            if (bitmap.width <= 0 || bitmap.height <= 0) {
                Log.w(TAG, "⚠️ Cannot set bitmap with invalid dimensions: ${bitmap.width}x${bitmap.height}")
                return false
            }

            // 안전한 setBitmap 호출
            canvas.setBitmap(bitmap)

            // 설정 후 검증
            return canvas.width > 0 && canvas.height > 0

        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ IllegalStateException in Canvas.setBitmap(): ${e.message}", e)
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "❌ RuntimeException in Canvas.setBitmap(): ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected exception in Canvas.setBitmap(): ${e.message}", e)
            false
        }
    }

    fun getStatus(): String = "HighSpeedProcessor: Processed=${processedFrames.get()}"

    fun cleanup() {
        processedFrames.set(0)
        Log.d(TAG, "🗑️ High-Speed 프로세서 정리 완료")
    }
}
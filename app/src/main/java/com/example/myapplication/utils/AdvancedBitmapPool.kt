// utils/TrueZeroCopyBitmapPool.kt - 버그 수정 버전
package com.example.myapplication.utils

import android.graphics.*
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val STALE_TIMEOUT_MS = 30_000L
    private val CRITICAL_AVAILABLE_THRESHOLD = 3
    private val EMERGENCY_AVAILABLE_THRESHOLD = 1

    init {
        initialize()
    }

    private fun initialize(): Boolean {
        return synchronized(poolLock) {
            if (isInitialized) return true

            Log.d(TAG, "🎯 Zero-Copy 비트맵 풀 초기화 시작 (크기: $poolSize)")

            var successCount = 0
            for (index in 0 until poolSize) {
                try {
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
     * 🎯 SharedBitmap 래퍼 반환 - 수정된 버전
     */
    fun acquireSharedBitmap(): SharedBitmap? {
        synchronized(poolLock) {
            if (!isInitialized && !autoRecover()) {
                Log.w(TAG, "⚠️ 비트맵 획득 실패: 풀이 초기화되지 않음")
                return null
            }

            val bitmap = findAvailableBitmap()
            if (bitmap != null) {
                val index = bitmapPool.indexOf(bitmap)
                if (index != -1) {
                    // 🔧 수정: 상태 설정을 먼저 하고 참조 카운트 설정
                    usageState[index] = true
                    referenceCount[index].set(1)
                    lastAccessTime[index].set(System.currentTimeMillis())
                    totalReused.incrementAndGet()

                    val sharedBitmap = SharedBitmap.create(bitmap, this, index)
                    Log.d(TAG, "📥 SharedBitmap acquired: index=$index, @${bitmap.hashCode().toString(16)}")
                    return sharedBitmap
                }
            }

            // 실패시 새 비트맵 생성 시도
            return handleAcquisitionFailure()
        }
    }

    /**
     * 🔧 수정된 사용 가능한 비트맵 찾기
     */
    private fun findAvailableBitmap(): Bitmap? {
        val currentTime = System.currentTimeMillis()

        repeat(poolSize) { offset ->
            val index = (currentIndex + offset) % poolSize
            val bitmap = bitmapPool[index]

            // 🔧 수정: 조건을 더 엄격하게 검사
            if (bitmap != null &&
                !bitmap.isRecycled &&
                !usageState[index] && // 사용중이 아니고
                referenceCount[index].get() == 0 && // 참조 카운트가 0이고
                isValidBitmap(bitmap)) { // 유효한 비트맵

                currentIndex = (index + 1) % poolSize
                lastSuccessTime = currentTime
                consecutiveFailures = 0

                Log.d(TAG, "✅ Available bitmap found: index=$index, @${bitmap.hashCode().toString(16)}")
                return bitmap
            }
        }

        Log.w(TAG, "⚠️ No available bitmap found in pool")
        logPoolState()
        return null
    }

    /**
     * 🔧 풀 상태 로깅 (디버깅용)
     */
    private fun logPoolState() {
        val state = buildString {
            append("Pool state: ")
            for (i in 0 until poolSize) {
                val bitmap = bitmapPool[i]
                val used = usageState[i]
                val refs = referenceCount[i].get()
                val valid = bitmap != null && !bitmap.isRecycled
                append("[$i:${if(valid) "V" else "X"}${if(used) "U" else "A"}$refs] ")
            }
        }
        Log.d(TAG, state)
    }

    /**
     * 🔧 수정된 내부 참조 해제
     */
    internal fun releaseReference(bitmap: Bitmap, bitmapIndex: Int) {
        synchronized(poolLock) {
            if (bitmapIndex in 0 until poolSize && bitmapPool[bitmapIndex] == bitmap) {
                val currentCount = referenceCount[bitmapIndex].get()

                if (currentCount > 0) {
                    val newCount = referenceCount[bitmapIndex].decrementAndGet()
                    Log.d(TAG, "📉 Reference decreased: index=$bitmapIndex, count=$currentCount→$newCount")

                    // 🔧 수정: 참조 카운트가 정확히 0일 때만 해제
                    if (newCount == 0) {
                        usageState[bitmapIndex] = false
                        lastAccessTime[bitmapIndex].set(System.currentTimeMillis())
                        consecutiveFailures = 0
                        lastSuccessTime = System.currentTimeMillis()

                        // 비트맵 초기화
                        try {
                            if (!bitmap.isRecycled && bitmap.isMutable) {
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ 비트맵 클리어 실패 (무시): ${e.message}")
                        }

                        Log.d(TAG, "✅ 비트맵 풀 반환 완료: index=$bitmapIndex")
                    } else if (newCount < 0) {
                        // 🔧 음수 참조 카운트 복구
                        Log.e(TAG, "❌ 음수 참조 카운트 감지 - 강제 복구: index=$bitmapIndex, count=$newCount")
                        referenceCount[bitmapIndex].set(0)
                        usageState[bitmapIndex] = false
                    } else {

                    }
                } else {
                    Log.w(TAG, "⚠️ 이미 참조 카운트가 0인 비트맵 해제 시도: index=$bitmapIndex")
                    usageState[bitmapIndex] = false
                }
            } else {
                Log.e(TAG, "❌ 참조 해제 실패 - 비트맵 불일치: index=$bitmapIndex")
                performEmergencyBitmapCleanup(bitmap)
            }
        }
    }

    /**
     * 🎯 획득 실패 처리 - 개선된 버전
     */
    private fun handleAcquisitionFailure(): SharedBitmap? {
        consecutiveFailures++
        Log.w(TAG, "⚠️ 비트맵 획득 실패: $consecutiveFailures 회")

        return when {
            consecutiveFailures >= 5 && recoveryCount < maxRecoveryAttempts -> {
                Log.w(TAG, "🔧 연속 실패 감지 - 자동 복구 시도: $recoveryCount")
                if (performAdvancedRecovery()) {
                    findAvailableBitmap()?.let { bitmap ->
                        val index = bitmapPool.indexOf(bitmap)
                        if (index != -1) {
                            usageState[index] = true
                            referenceCount[index].set(1)
                            lastAccessTime[index].set(System.currentTimeMillis())
                            SharedBitmap.create(bitmap, this, index)
                        } else null
                    }
                } else {
                    createNewBitmap()
                }
            }
            consecutiveFailures >= 10 -> {
                Log.e(TAG, "🚨 심각한 상황 - 새 비트맵 생성 시도")
                createNewBitmap()
            }
            else -> null
        }
    }

    /**
     * 🔧 수정된 새 비트맵 생성
     */
    private fun createNewBitmap(): SharedBitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            totalCreated.incrementAndGet()
            Log.d(TAG, "🆕 새 비트맵 생성: @${bitmap.hashCode().toString(16)}, total=${totalCreated.get()}")

            // 🔧 수정: 풀에 추가 시도를 더 신중하게
            synchronized(poolLock) {
                val emptyIndex = bitmapPool.indexOfFirst { it == null || it.isRecycled }
                if (emptyIndex != -1) {
                    // 기존 비트맵이 재활용되었다면 정리
                    bitmapPool[emptyIndex]?.takeIf { it.isRecycled }?.let {
                        Log.d(TAG, "🗑️ 재활용된 비트맵 슬롯 교체: index=$emptyIndex")
                    }

                    bitmapPool[emptyIndex] = bitmap
                    usageState[emptyIndex] = true
                    referenceCount[emptyIndex].set(1)
                    lastAccessTime[emptyIndex].set(System.currentTimeMillis())

                    Log.d(TAG, "🔄 새 비트맵을 풀 슬롯에 추가: index=$emptyIndex")
                    return SharedBitmap.create(bitmap, this, emptyIndex)
                }
            }

            // 풀에 추가할 수 없으면 임시 비트맵으로 사용
            SharedBitmap.create(bitmap, this, -1)

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ OOM: 비트맵 생성 실패", e)
            System.gc()
            null
        }
    }

    /**
     * 🔧 수정된 고급 자동 복구
     */
    private fun performAdvancedRecovery(): Boolean {
        recoveryCount++
        Log.w(TAG, "🔧 고급 자동 복구 시작 ($recoveryCount/$maxRecoveryAttempts)")

        try {
            var recycledCount = 0
            var freedCount = 0

            // 1. 상태 불일치 수정
            for (index in 0 until poolSize) {
                val bitmap = bitmapPool[index]
                val refCount = referenceCount[index].get()
                val inUse = usageState[index]

                // 재활용된 비트맵 정리
                if (bitmap?.isRecycled == true) {
                    bitmapPool[index] = null
                    usageState[index] = false
                    referenceCount[index].set(0)
                    recycledCount++
                    continue
                }

                // 참조 카운트 0인데 사용중으로 표시된 경우 수정
                if (refCount == 0 && inUse) {
                    usageState[index] = false
                    freedCount++
                    Log.d(TAG, "🔧 상태 불일치 수정: index=$index (ref=0 but inUse=true)")
                }

                // 음수 참조 카운트 수정
                if (refCount < 0) {
                    referenceCount[index].set(0)
                    usageState[index] = false
                    freedCount++
                    Log.d(TAG, "🔧 음수 참조 카운트 수정: index=$index")
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
            Log.w(TAG, "✅ 고급 복구 완료: recycled=$recycledCount, freed=$freedCount, recreated=$recreatedCount, available=$availableCount")

            return availableCount > 0

        } catch (e: Exception) {
            Log.e(TAG, "❌ 고급 복구 실패", e)
            return false
        }
    }

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

    private fun performEmergencyBitmapCleanup(bitmap: Bitmap) {
        try {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
                totalRecycled.incrementAndGet()
                Log.w(TAG, "🚨 응급 비트맵 즉시 정리: @${bitmap.hashCode().toString(16)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 응급 정리 실패: ${e.message}", e)
        }
    }

    private fun autoRecover(): Boolean {
        return executeWithErrorHandling("자동 복구") { performAdvancedRecovery() }
    }

    private fun executeWithErrorHandling(operationName: String, operation: () -> Boolean): Boolean {
        return try {
            operation()
        } catch (e: Exception) {
            Log.e(TAG, "❌ $operationName 실패: ${e.message}", e)
            false
        }
    }

    // 기존 메서드들 (getDetailedStatus, cleanup 등)은 그대로 유지
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

    fun getDetailedStatus(): String {
        synchronized(poolLock) {
            val currentTime = System.currentTimeMillis()
            val activeDetails = buildString {
                bitmapPool.forEachIndexed { index, bitmap ->
                    if (bitmap != null) {
                        val timeSinceAccess = currentTime - lastAccessTime[index].get()
                        val used = usageState[index]
                        val refs = referenceCount[index].get()
                        val recycled = bitmap.isRecycled
                        append("[$index:${if(recycled) "R" else "V"}${if(used) "U" else "A"}$refs:${timeSinceAccess}ms] ")
                    } else {
                        append("[$index:NULL] ")
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
                appendLine("Consecutive Failures: $consecutiveFailures")
                appendLine("Details: $activeDetails")

                val validCount = bitmapPool.count { isValidBitmap(it) }
                val recycledCount = bitmapPool.count { it?.isRecycled == true }
                appendLine("Valid Bitmaps: $validCount")
                appendLine("Recycled Bitmaps: $recycledCount")

                val healthStatus = when {
                    validCount < poolSize / 2 -> "🔴 위험"
                    usageState.count { !it } < 3 -> "🟡 주의"
                    else -> "🟢 정상"
                }
                appendLine("건강성: $healthStatus")
            }
        }
    }

    fun forceCleanupStaleReferences() {
        synchronized(poolLock) {
            val currentTime = System.currentTimeMillis()
            val staleBitmaps = mutableListOf<Int>()

            bitmapPool.forEachIndexed { index, bitmap ->
                val timeSinceAccess = currentTime - lastAccessTime[index].get()
                val refCount = referenceCount[index].get()

                if (bitmap?.isRecycled == true ||
                    refCount < 0 ||
                    (usageState[index] && refCount == 0 && timeSinceAccess > 15_000L)) {

                    staleBitmaps.add(index)
                    Log.w(TAG, "🧹 Aggressive stale cleanup: index=$index, age=${timeSinceAccess}ms")
                }
            }

            staleBitmaps.forEach { index ->
                val bitmap = bitmapPool[index]
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                    totalRecycled.incrementAndGet()
                }
                bitmapPool[index] = null
                usageState[index] = false
                referenceCount[index].set(0)
            }

            Log.d(TAG, "🧹 Aggressive cleanup completed: ${staleBitmaps.size} bitmaps")
        }
    }

    fun performEmergencyReset(): Boolean {
        return synchronized(poolLock) {
            try {
                Log.w(TAG, "🚨 UI Pool 응급 리셋 시작")

                for (index in 0 until poolSize) {
                    referenceCount[index].set(0)
                    usageState[index] = false
                }

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

    fun isReady(): Boolean = synchronized(poolLock) {
        isInitialized && usageState.count { !it } > 0
    }

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
 * 🔧 수정된 SharedBitmap - 중복 해제 문제 해결
 */
class SharedBitmap private constructor(
    val bitmap: Bitmap,
    private val pool: TrueZeroCopyBitmapPool,
    private val bitmapIndex: Int,
    private val creationId: Long = System.nanoTime()
) {
    private val TAG = "SharedBitmap"
    private val bitmapHash = bitmap.hashCode().toString(16)
    private val isReleased = AtomicBoolean(false)
    private val refCount = AtomicInteger(1)
    private val lastAccessTime = AtomicLong(System.currentTimeMillis())

    companion object {
        internal fun create(
            bitmap: Bitmap,
            pool: TrueZeroCopyBitmapPool,
            index: Int
        ): SharedBitmap {
            return SharedBitmap(bitmap, pool, index)
        }
    }

    /**
     * 🔧 수정된 참조 해제 - 중복 해제 문제 해결
     */
    fun release(): Boolean {
        // 🔧 수정: isReleased 체크와 pool.releaseReference 호출을 분리
        val wasAlreadyReleased = isReleased.getAndSet(true)

        if (wasAlreadyReleased) {
            Log.w(TAG, "⚠️ 중복 해제 시도 차단: @$bitmapHash")
            return false
        }

        val finalCount = refCount.decrementAndGet()
        Log.d(TAG, "📉 참조 해제: @$bitmapHash, finalCount=$finalCount")

        // 🔧 수정: 풀에 해제 알림은 항상 호출
        try {
            pool.releaseReference(bitmap, bitmapIndex)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 풀 해제 알림 실패: ${e.message}", e)
        }

        return true
    }

    fun addRef(): Boolean {
        if (isReleased.get() || bitmap.isRecycled) {
            Log.w(TAG, "⚠️ 이미 해제된 비트맵 참조 시도: @$bitmapHash")
            return false
        }

        val newCount = refCount.incrementAndGet()
        lastAccessTime.set(System.currentTimeMillis())

        Log.d(TAG, "📈 참조 증가: @$bitmapHash, count=$newCount")
        return true
    }

    fun getCurrentRefCount(): Int = refCount.get()

    fun getAgeMillis(): Long = System.currentTimeMillis() - (creationId / 1_000_000)

    fun isValid(): Boolean {
        return !isReleased.get() &&
                !bitmap.isRecycled &&
                bitmap.width > 0 &&
                bitmap.height > 0 &&
                refCount.get() > 0
    }

    fun getSafeBitmapForUI(): Bitmap? {
        lastAccessTime.set(System.currentTimeMillis())
        return if (isValid()) bitmap else null
    }

    /**
     * 🆕 UI용 안전한 복사본 생성
     */
    fun createSafeCopyForUI(): Bitmap? {
        if (!isValid()) return null

        return try {
            lastAccessTime.set(System.currentTimeMillis())
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.e(TAG, "❌ UI 복사본 생성 실패: ${e.message}", e)
            null
        }
    }
}

// 기존 enum과 data class들은 그대로 유지
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
 * 🔧 수정된 고속 Zero-Copy 프로세서
 */
class HighSpeedZeroCopyProcessor(
    private val bitmapPool: TrueZeroCopyBitmapPool,
    private val targetWidth: Int = 840,
    private val targetHeight: Int = 840
) {
    private val TAG = "HighSpeedZeroCopyProcessor"
    private val processedFrames = AtomicLong(0)

    fun processHighQualityZeroCopy(
        imageBytes: ByteArray,
        rotationDegrees: Int
    ): SharedBitmap? {
        val sharedBitmap = bitmapPool.acquireSharedBitmap()

        if (sharedBitmap == null) {
            Log.w(TAG, "⚠️ SharedBitmap 획득 실패")
            return null
        }

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

    private fun safeSetCanvasBitmap(canvas: Canvas, bitmap: Bitmap): Boolean {
        return try {
            if (!isValidBitmap(bitmap)) {
                Log.w(TAG, "⚠️ Cannot set invalid bitmap to canvas")
                return false
            }

            if (bitmap.width <= 0 || bitmap.height <= 0) {
                Log.w(TAG, "⚠️ Cannot set bitmap with invalid dimensions: ${bitmap.width}x${bitmap.height}")
                return false
            }

            canvas.setBitmap(bitmap)
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
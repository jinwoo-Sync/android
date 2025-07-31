// /utils/AdvancedTaggedBitmapPool.kt
package com.example.myapplication.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.Log
import com.example.myapplication.DataStructure.CircularQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// 기존 enum과 data class 유지
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
 * CircularQueue와 Tagged 소유권 시스템, 그래픽 객체 풀을 결합한 고성능 비트맵 풀.
 *
 * @param poolSize 풀이 관리할 총 비트맵 개수
 * @param width, height 생성될 비트맵의 크기
 * @param autoCleanupIntervalMs 자동 정리 스레드의 실행 간격(ms)
 * @param staleTimeoutMs 비트맵이 반납되지 않고 버텨주는 최대 시간(ms). 이 시간이 지나면 강제 회수.
 */
class AdvancedTaggedBitmapPool(
    private val poolSize: Int = 50,
    private val width: Int = 840,
    private val height: Int = 840,
    private val autoCleanupIntervalMs: Long = 2000L,
    private val staleTimeoutMs: Long = 5000L
) {
    private val TAG = "AdvTaggedBitmapPool"

    // --- 데이터 구조 ---
    private val bitmapPool = Array<Bitmap?>(poolSize) { null }
    private val availableIndices = CircularQueue<Int>(poolSize)
    internal val activeTags = ConcurrentHashMap<Int, TagInfo>() // Key: poolIndex
    private val poolLock = ReentrantReadWriteLock()

    // --- 그래픽 객체 풀 ---
    private val canvasPool = CircularQueue<Canvas>(poolSize)
    private val paintPool = CircularQueue<Paint>(poolSize)
    private val matrixPool = CircularQueue<Matrix>(poolSize)

    // --- 자동 복구 (Stale-Checking) 스레드 ---
    @Volatile
    private var currentScheduler: ScheduledExecutorService = createNewScheduler()
    private val schedulerLock = java.util.concurrent.locks.ReentrantLock()
    private val isShutdown = AtomicBoolean(false)

    // --- 통계 ---
    private val totalAcquired = AtomicLong(0)
    private val totalReleased = AtomicLong(0)
    private val totalForceCleaned = AtomicLong(0)

    // 내부 관리용 데이터 클래스
    internal data class TagInfo(
        val tag: String,
        val managedBitmap: ManagedBitmap,
        val acquireTime: Long = System.currentTimeMillis(),
        val lastAccessTime: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    init {
        initializePool()
        startPeriodicCleanup()
    }

    /**
     * 🔧 새 스케줄러 생성
     */
    private fun createNewScheduler(): ScheduledExecutorService {
        return Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AdvTaggedPool-Cleanup-${System.currentTimeMillis()}").apply {
                isDaemon = true
            }
        }
    }

    private fun initializePool() = poolLock.write {
        Log.d(TAG, "🚀 비트맵 풀 초기화 시작 (크기: $poolSize)")
        var successCount = 0
        for (index in 0 until poolSize) {
            try {
                bitmapPool[index] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                availableIndices.push(index) // 사용 가능한 인덱스를 큐에 추가
                successCount++
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "❌ 초기화 중 OOM 발생: ${index + 1}/$poolSize", e)
                break
            }
        }
        // 그래픽 객체들도 미리 생성
        repeat(poolSize) {
            canvasPool.push(Canvas())
            paintPool.push(Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            matrixPool.push(Matrix())
        }
        Log.d(TAG, "✅ 비트맵 풀 초기화 완료: $successCount/$poolSize")
    }

    /**
     * 태그를 지정하여 관리되는 비트맵을 획득합니다.
     * @param tag 비트맵의 소유권과 목적을 나타내는 태그
     * @return 사용 가능한 ManagedBitmap 객체, 없으면 null
     */
    fun acquire(tag: String): ManagedBitmap? = poolLock.read {
        if (isShutdown.get()) {
            Log.w(TAG, "⚠️ 풀이 종료된 상태에서 acquire 시도")
            return null
        }

        val index = availableIndices.poll() ?: run {
            Log.w(TAG, "⚠️ 사용 가능한 비트맵이 없습니다. 현재 활성: ${activeTags.size}개")
            return null
        }

        val bitmap = bitmapPool[index]
        if (bitmap == null || bitmap.isRecycled) {
            Log.e(TAG, "❌ 풀에 저장된 비트맵이 손상되었습니다. (index: $index). 응급 복구를 시도합니다.")
            availableIndices.push(index) // 사용 불가하므로 다시 넣어두고, Stale 체커가 정리하도록 둠
            return null
        }

        val managedBitmap = ManagedBitmap(bitmap, tag, this, index)
        val tagInfo = TagInfo(tag, managedBitmap)
        activeTags[index] = tagInfo

        totalAcquired.incrementAndGet()
        Log.d(TAG, "📥 [ACQUIRE] index: $index, owner: $tag, active: ${activeTags.size}")
        return managedBitmap
    }

    /**
     * ManagedBitmap을 통해 호출되는 내부 반납 메서드입니다.
     */
    internal fun release(managedBitmap: ManagedBitmap) {
        val index = managedBitmap.poolIndex
        val tag = managedBitmap.tag

        val removedInfo = activeTags.remove(index)

        if (removedInfo == null) {
            Log.e(TAG, "‼️ [RELEASE-FAIL] 이미 반납되었거나 존재하지 않는 비트맵(index:$index)에 대한 반납 시도. owner: $tag")
            return
        }

        if (removedInfo.tag != tag) {
            Log.e(TAG, "‼️ [RELEASE-FAIL] 비트맵 소유권 불일치! index:$index, expected owner: ${removedInfo.tag}, actual owner: $tag")
            // 소유권이 다른 경우, 원래 주인을 다시 넣어주고 현재 인덱스는 유효하지 않은 것으로 간주
            activeTags[index] = removedInfo
            return
        }

        // 비트맵을 재사용하기 위해 초기화
        clearBitmap(bitmapPool[index])

        // 사용 가능한 인덱스 큐에 다시 추가
        availableIndices.push(index)
        totalReleased.incrementAndGet()
        Log.d(TAG, "📤 [RELEASE] index: $index, owner: $tag, available: ${availableIndices.size()}")
    }

    private fun clearBitmap(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled && bitmap.isMutable) {
                val canvas = getReusableCanvas()
                canvas.setBitmap(bitmap)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                returnReusableCanvas(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 비트맵 클리어 실패: ${e.message}")
        }
    }

    // --- 자동 복구 및 정리 ---

    /**
     * 🔧 주기적 정리 시작
     */
    private fun startPeriodicCleanup() {
        schedulerLock.lock()
        try {
            if (!currentScheduler.isShutdown && !isShutdown.get()) {
                currentScheduler.scheduleAtFixedRate(
                    this::performStaleCheck,
                    autoCleanupIntervalMs,
                    autoCleanupIntervalMs,
                    TimeUnit.MILLISECONDS
                )
                Log.d(TAG, "✅ 주기적 정리 스케줄링 시작")
            }
        } catch (e: Exception) {
            Log.e(TAG, "주기적 정리 시작 실패: ${e.message}", e)
        } finally {
            schedulerLock.unlock()
        }
    }

    /**
     * 🔧 현재 스케줄러 안전 종료
     */
    private fun shutdownCurrentScheduler() {
        schedulerLock.lock()
        try {
            if (!currentScheduler.isShutdown) {
                currentScheduler.shutdown()
                try {
                    if (!currentScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                        Log.w(TAG, "스케줄러 정상 종료 실패, 강제 종료 시도")
                        currentScheduler.shutdownNow()
                        if (!currentScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                            Log.e(TAG, "스케줄러 강제 종료도 실패")
                        }
                    }
                } catch (e: InterruptedException) {
                    currentScheduler.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            }
        } finally {
            schedulerLock.unlock()
        }
    }

    /**
     * 🔧 새 스케줄러 생성 및 시작
     */
    private fun recreateScheduler() {
        schedulerLock.lock()
        try {
            currentScheduler = createNewScheduler()
            Log.d(TAG, "✅ 새 cleanup 스케줄러 생성 완료")
        } finally {
            schedulerLock.unlock()
        }
    }

    private fun performStaleCheck() {
        if (activeTags.isEmpty() || isShutdown.get()) return

        val currentTime = System.currentTimeMillis()
        val staleEntries = activeTags.filter { (_, tagInfo) ->
            currentTime - tagInfo.lastAccessTime.get() > staleTimeoutMs
        }

        if (staleEntries.isNotEmpty()) {
            Log.w(TAG, "🗑️ ${staleEntries.size}개의 오래된(Stale) 비트맵을 감지하여 강제 회수를 시작합니다.")
            staleEntries.forEach { (index, tagInfo) ->
                Log.w(TAG, "   - 강제 회수 대상: index: $index, owner: ${tagInfo.tag}, age: ${currentTime - tagInfo.acquireTime}ms")
                // release() 메서드는 스레드에 안전하며, isReleased 플래그 덕분에 중복 호출이 방지됩니다.
                tagInfo.managedBitmap.release()
                totalForceCleaned.incrementAndGet()
            }
        }
    }

    /**
     * 🚨 안전한 응급 복구 - 스케줄러 재생성 포함
     */
    fun performEmergencyReset() = poolLock.write {
        Log.w(TAG, "🚨🚨 응급 풀 리셋을 시작합니다! 🚨🚨")

        try {
            // ✅ 1. 현재 스케줄러 안전 종료
            shutdownCurrentScheduler()

            // ✅ 2. 활성 참조 안전 해제
            val activeRefs = activeTags.values.toList()
            activeTags.clear()

            activeRefs.forEach { tagInfo ->
                try {
                    // ManagedBitmap의 isReleased 플래그만 설정
                    if (!tagInfo.managedBitmap.isReleased()) {
                        tagInfo.managedBitmap.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "활성 참조 해제 중 오류: ${e.message}")
                }
            }

            // ✅ 3. 풀 상태 재구성 (비트맵 재활용 없이)
            availableIndices.clear()
            for (index in 0 until poolSize) {
                val bitmap = bitmapPool[index]
                if (bitmap != null && !bitmap.isRecycled) {
                    availableIndices.push(index)
                } else if (bitmap == null || bitmap.isRecycled) {
                    // 손상된 비트맵 재생성 시도
                    try {
                        bitmapPool[index] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        availableIndices.push(index)
                    } catch (e: OutOfMemoryError) {
                        Log.w(TAG, "비트맵 재생성 실패 (OOM): index $index")
                        bitmapPool[index] = null
                    }
                }
            }

            // ✅ 4. 그래픽 객체 풀 정리
            canvasPool.clear()
            paintPool.clear()
            matrixPool.clear()

            // 재생성
            repeat(availableIndices.size()) {
                canvasPool.push(Canvas())
                paintPool.push(Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                matrixPool.push(Matrix())
            }

            // ✅ 5. 새 스케줄러 생성 및 시작
            recreateScheduler()
            startPeriodicCleanup()

            Log.w(TAG, "✅✅ 응급 풀 리셋 완료! Available: ${availableIndices.size()}/${poolSize} ✅✅")

        } catch (e: Exception) {
            Log.e(TAG, "응급 리셋 중 오류: ${e.message}", e)
            throw e
        }
    }

    /**
     * 🗑️ 안전한 종료
     */
    fun shutdown() {
        isShutdown.set(true)
        shutdownCurrentScheduler()

        poolLock.write {
            activeTags.clear()
            availableIndices.clear()
            bitmapPool.forEachIndexed { index, bitmap ->
                bitmap?.recycle()
                bitmapPool[index] = null
            }
            canvasPool.clear()
            paintPool.clear()
            matrixPool.clear()
            Log.d(TAG, "AdvancedTaggedBitmapPool gracefully shut down.")
        }
    }

    // --- 그래픽 객체 풀 관련 메서드 ---
    fun getReusableCanvas(): Canvas = canvasPool.poll() ?: Canvas()
    fun returnReusableCanvas(canvas: Canvas) {
        canvas.setBitmap(null)
        canvasPool.push(canvas)
    }

    fun getReusablePaint(): Paint = paintPool.poll() ?: Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    fun returnReusablePaint(paint: Paint) = paintPool.push(paint)

    fun getReusableMatrix(): Matrix = matrixPool.poll() ?: Matrix()
    fun returnReusableMatrix(matrix: Matrix) {
        matrix.reset()
        matrixPool.push(matrix)
    }

    // --- 상태 조회 메서드 ---
    fun getDetailedStatus(): String = poolLock.read {
        buildString {
            appendLine("====== AdvancedTaggedBitmapPool Status ======")
            appendLine("🔹 Pool Size: ${availableIndices.size()}/$poolSize Available")
            appendLine("🔸 Active Bitmaps: ${activeTags.size}")
            appendLine("📈 Stats: Acquired=${totalAcquired.get()}, Released=${totalReleased.get()}, ForceCleaned=${totalForceCleaned.get()}")
            if (activeTags.isNotEmpty()) {
                appendLine("📋 Active Tags:")
                val currentTime = System.currentTimeMillis()
                activeTags.forEach { (index, info) ->
                    appendLine("  - [Idx:$index] Tag: ${info.tag}, Age: ${currentTime - info.acquireTime}ms")
                }
            }
            appendLine("==============================================")
        }
    }

    fun getStatus(): String = poolLock.read {
        "TaggedPool: Available=${availableIndices.size()}/$poolSize, Active=${activeTags.size}, " +
                "Acquired=${totalAcquired.get()}, Released=${totalReleased.get()}"
    }

    fun getPoolHealthStatus(): PoolHealthStatus = poolLock.read {
        val available = availableIndices.size()
        val active = activeTags.size
        val staleCount = activeTags.count { (_, tagInfo) ->
            System.currentTimeMillis() - tagInfo.lastAccessTime.get() > staleTimeoutMs
        }

        val healthLevel = when {
            available <= 1 -> HealthLevel.CRITICAL
            available <= 3 -> HealthLevel.WARNING
            staleCount > 3 -> HealthLevel.DEGRADED
            else -> HealthLevel.HEALTHY
        }

        PoolHealthStatus(
            healthLevel = healthLevel,
            availableSlots = available,
            totalSlots = poolSize,
            totalReferences = active,
            staleSlots = staleCount,
            recommendation = when (healthLevel) {
                HealthLevel.CRITICAL -> "즉시 응급 복구 필요"
                HealthLevel.WARNING -> "예방적 정리 권장"
                HealthLevel.DEGRADED -> "stale 참조 정리 권장"
                HealthLevel.HEALTHY -> "정상 상태"
            }
        )
    }

    fun forceCleanupStaleReferences() {
        performStaleCheck()
    }

    fun isReady(): Boolean = poolLock.read {
        availableIndices.size() > 0 && !isShutdown.get()
    }
}

/**
 * ManagedBitmap - Tagged 소유권 시스템을 제공하는 래퍼 클래스
 */
class ManagedBitmap internal constructor(
    val bitmap: Bitmap,
    val tag: String,
    private val pool: AdvancedTaggedBitmapPool,
    internal val poolIndex: Int
) {
    private val TAG = "ManagedBitmap"
    @Volatile
    private var isReleased = false
    private val creationTime = System.currentTimeMillis()

    // ✅ 스레드 안전성을 위한 락 추가
    private val accessLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /**
     * 🔒 스레드 안전한 비트맵 유효성 검사
     */
    fun isValid(): Boolean {
        return accessLock.readLock().let { lock ->
            lock.lock()
            try {
                !isReleased && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 비트맵 유효성 검사 중 예외: ${e.message}")
                false
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * 🔒 스레드 안전한 반납
     */
    fun release() {
        accessLock.writeLock().let { lock ->
            lock.lock()
            try {
                if (isReleased) {
                    Log.w(TAG, "⚠️ 이미 반납된 비트맵 중복 반납 시도: $tag (index: $poolIndex)")
                    return
                }

                isReleased = true
                pool.release(this)
                Log.d(TAG, "✅ ManagedBitmap 반납: $tag (index: $poolIndex)")
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * 🔒 UI용 안전한 복사본 생성
     */
    fun createSafeCopyForUI(): Bitmap? {
        return accessLock.readLock().let { lock ->
            lock.lock()
            try {
                if (!isValid()) return null

                updateLastAccess()
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.e(TAG, "❌ UI 복사본 생성 실패: ${e.message}", e)
                null
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * ✅ 반납 상태 확인 메서드 추가
     */
    fun isReleased(): Boolean = isReleased

    /**
     * 비트맵의 사용 시간을 업데이트합니다. (Stale-Check 방지)
     */
    fun updateLastAccess() {
        pool.activeTags[poolIndex]?.lastAccessTime?.set(System.currentTimeMillis())
    }

    fun getAgeMillis(): Long = System.currentTimeMillis() - creationTime
}

/**
 * AdvancedTaggedBitmapPool을 사용하는 고속 프로세서
 */
class HighSpeedZeroCopyProcessor(
    private val bitmapPool: AdvancedTaggedBitmapPool,
    private val targetWidth: Int = 840,
    private val targetHeight: Int = 840
) {
    private val TAG = "HighSpeedZeroCopyProcessor"
    private val processedFrames = AtomicLong(0)

    fun processHighQualityZeroCopy(
        imageBytes: ByteArray,
        rotationDegrees: Int
    ): ManagedBitmap? {
        val managedBitmap = bitmapPool.acquire("PROCESSING_${System.nanoTime()}")

        if (managedBitmap == null) {
            Log.w(TAG, "⚠️ ManagedBitmap 획득 실패")
            return null
        }

        val bitmap = managedBitmap.bitmap
        val bitmapHash = bitmap.hashCode().toString(16)

        if (!isValidBitmap(bitmap)) {
            Log.e(TAG, "❌ Invalid bitmap detected before processing: @$bitmapHash")
            managedBitmap.release()
            return null
        }

        val canvas = bitmapPool.getReusableCanvas()
        val paint = bitmapPool.getReusablePaint()
        val matrix = bitmapPool.getReusableMatrix()

        try {
            if (!safeSetCanvasBitmap(canvas, bitmap)) {
                Log.e(TAG, "❌ Canvas.setBitmap() failed for @$bitmapHash")
                managedBitmap.release()
                return null
            }

            val sourceBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (sourceBitmap == null || sourceBitmap.isRecycled) {
                Log.w(TAG, "⚠️ High quality bitmap decode failed for @$bitmapHash")
                managedBitmap.release()
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
                managedBitmap.updateLastAccess() // 사용 시간 업데이트
            } catch (e: Exception) {
                Log.e(TAG, "❌ Canvas.drawBitmap failed for @$bitmapHash: ${e.message}", e)
                sourceBitmap.recycle()
                managedBitmap.release()
                return null
            }

            sourceBitmap.recycle()

            val frameNum = processedFrames.incrementAndGet()
            Log.d(TAG, "✅ Tagged Bitmap 처리 완료: @$bitmapHash, frame=$frameNum, tag=${managedBitmap.tag}")

            return managedBitmap

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 OOM during Tagged Bitmap processing @$bitmapHash", e)
            managedBitmap.release()
            System.gc()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "💥 Unexpected error during Tagged Bitmap processing @$bitmapHash: ${e.message}", e)
            managedBitmap.release()
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

            canvas.setBitmap(bitmap)
            return canvas.width > 0 && canvas.height > 0

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in Canvas.setBitmap(): ${e.message}", e)
            false
        }
    }

    fun getStatus(): String = "HighSpeedProcessor: Processed=${processedFrames.get()}"

    fun cleanup() {
        processedFrames.set(0)
        Log.d(TAG, "🗑️ High-Speed 프로세서 정리 완료")
    }
}
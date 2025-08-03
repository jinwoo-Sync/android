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
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import java.util.concurrent.locks.ReentrantLock

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
 * 인덱스 정보를 포함한 비트맵 래퍼
 */
private data class IndexedBitmap(
    val bitmap: Bitmap,
    val index: Int,
    val creationTime: Long = System.currentTimeMillis()
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
    private val autoCleanupIntervalMs: Long = 1000L,
    private val staleTimeoutMs: Long = 3000L
) {
    private val TAG = "AdvTaggedBitmapPool"

    //  원형큐 기반 데이터 구조
    private val bitmapPool = CircularQueue<IndexedBitmap>(poolSize)
    internal val activeTags = ConcurrentHashMap<Int, TagInfo>()
    private val poolLock = ReentrantReadWriteLock()

    // 성능 통계
    private val acquisitionCounter = AtomicLong(0)
    private val releaseCounter = AtomicLong(0)
    private val forceCleanupCounter = AtomicLong(0)
    private val recycleCounter = AtomicLong(0)

    // 스케줄러 관리
    @Volatile
    private var currentScheduler: ScheduledExecutorService = createNewScheduler()
    private val schedulerLock = ReentrantLock()
    private val isShutdown = AtomicBoolean(false)

    // 그래픽 객체 풀 (원형큐로 관리)
    private val canvasPool = CircularQueue<Canvas>(poolSize * 2)
    private val paintPool = CircularQueue<Paint>(poolSize * 2)
    private val matrixPool = CircularQueue<Matrix>(poolSize * 2)

    internal data class TagInfo(
        val tag: String,
        val managedBitmap: ManagedBitmap,
        val acquireTime: Long = System.currentTimeMillis(),
        val lastAccessTime: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val threadName: String = Thread.currentThread().name
    )

    init {
        initializePool()
        startPeriodicCleanup()
        Log.d(TAG, " 원형큐 기반 BitmapPool 초기화: poolSize=$poolSize")
    }

    private fun initializePool() = poolLock.write {
        Log.d(TAG, "비트맵 풀 초기화 시작 (크기: $poolSize)")
        var successCount = 0

        // 비트맵 풀 초기화 - 원형큐가 내부적으로 용량 관리
        for (index in 0 until poolSize) {
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val indexedBitmap = IndexedBitmap(bitmap, index)
                bitmapPool.push(indexedBitmap) // 원형큐에 추가 - 용량 초과 시 자동 처리
                successCount++
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, " 초기화 중 OOM 발생: ${index + 1}/$poolSize", e)
                break
            }
        }

        // 그래픽 객체 풀 초기화
        repeat(poolSize * 2) {
            canvasPool.push(Canvas())
            paintPool.push(Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            matrixPool.push(Matrix())
        }

        Log.d(TAG, " 원형큐 기반 풀 초기화 완료: $successCount/$poolSize")
    }

    fun acquire(tag: String): ManagedBitmap? = poolLock.read {
        if (isShutdown.get()) {
            Log.w(TAG, "풀이 종료된 상태에서 acquire 시도")
            return null
        }

        // 원형큐에서 사용 가능한 비트맵 가져오기
        var indexedBitmap = bitmapPool.poll()

        // 풀이 비어있으면 응급 정리 시도
        if (indexedBitmap == null) {
            Log.w(TAG, " 풀 고갈 - 응급 정리 시도: Active=${activeTags.size}")
            performEmergencyStaleCleanup()

            indexedBitmap = bitmapPool.poll()
            if (indexedBitmap == null) {
                Log.e(TAG, " 응급 정리 후에도 풀 고갈")
                return null
            }
        }

        // 비트맵 유효성 검사 및 복구
        if (indexedBitmap.bitmap.isRecycled) {
            Log.w(TAG, "손상된 비트맵 감지 - 재생성: idx=${indexedBitmap.index}")
            try {
                val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                indexedBitmap = IndexedBitmap(newBitmap, indexedBitmap.index)
                recycleCounter.incrementAndGet()
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "비트맵 재생성 실패 (OOM)", e)
                // 실패한 비트맵도 다시 풀에 넣어서 나중에 재시도 가능
                bitmapPool.push(indexedBitmap)
                return null
            }
        }

        val managedBitmap = ManagedBitmap(indexedBitmap.bitmap, tag, this, indexedBitmap.index)
        val tagInfo = TagInfo(tag, managedBitmap, threadName = Thread.currentThread().name)
        activeTags[indexedBitmap.index] = tagInfo

        acquisitionCounter.incrementAndGet()

        val activeCount = activeTags.size
        val availableCount = bitmapPool.size()
        Log.d(TAG, "[ACQUIRE] tag=$tag, idx=${indexedBitmap.index}, active=$activeCount/$poolSize, available=$availableCount")

        // 위험 상태 감지
        if (availableCount < 5) {
            Log.w(TAG, "풀 위험 상태: Available=$availableCount, 예방적 정리 트리거")
            performEmergencyStaleCleanup()
        }

        return managedBitmap
    }

    internal fun release(managedBitmap: ManagedBitmap) {
        val index = managedBitmap.poolIndex
        val tag = managedBitmap.tag

        val removedInfo = activeTags.remove(index)
        if (removedInfo == null) {
            Log.e(TAG, "[RELEASE-FAIL] 이미 반납된 비트맵: idx=$index, tag=$tag")
            return
        }

        if (removedInfo.tag != tag) {
            Log.e(TAG, "[RELEASE-FAIL] 소유권 불일치: idx=$index, expected=${removedInfo.tag}, actual=$tag")
            activeTags[index] = removedInfo // 원래 소유자 복원
            return
        }

        // 비트맵 안전 초기화
        val bitmap = managedBitmap.bitmap
        if (!bitmap.isRecycled && bitmap.isMutable) {
            try {
                clearBitmap(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "비트맵 클리어 실패: idx=$index, ${e.message}")
            }
        }

        //  원형큐에 반납 - 내부에서 용량 관리됨
        val indexedBitmap = IndexedBitmap(bitmap, index)
        bitmapPool.push(indexedBitmap)
        releaseCounter.incrementAndGet()

        val activeCount = activeTags.size
        val availableCount = bitmapPool.size()
        Log.d(TAG, "[RELEASE] tag=$tag, idx=$index, active=$activeCount, available=$availableCount")
    }

    private fun performEmergencyStaleCleanup() {
        if (activeTags.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        val staleEntries = activeTags.filter { (_, tagInfo) ->
            currentTime - tagInfo.lastAccessTime.get() > 2000L // 2초 이상 미사용
        }

        if (staleEntries.isNotEmpty()) {
            Log.w(TAG, "응급 Stale 정리: ${staleEntries.size}개")
            staleEntries.forEach { (index, tagInfo) ->
                val age = currentTime - tagInfo.acquireTime
                Log.w(TAG, "  강제 해제: idx=$index, tag=${tagInfo.tag}, age=${age}ms")
                tagInfo.managedBitmap.forceRelease()
                forceCleanupCounter.incrementAndGet()
            }
        }
    }

    private fun performStaleCheck() {
        if (activeTags.isEmpty() || isShutdown.get()) return

        val currentTime = System.currentTimeMillis()

        //  더 짧은 타임아웃으로 변경 (3초→1.5초)
        val staleEntries = activeTags.filter { (_, tagInfo) ->
            currentTime - tagInfo.lastAccessTime.get() > 1500L
        }

        if (staleEntries.isNotEmpty()) {
            Log.w(TAG, " 적극적 Stale 정리: ${staleEntries.size}개")
            staleEntries.forEach { (index, tagInfo) ->
                tagInfo.managedBitmap.forceRelease()
                forceCleanupCounter.incrementAndGet()
            }
        }
    }

    private fun startPeriodicCleanup() {
        schedulerLock.lock()
        try {
            if (!currentScheduler.isShutdown && !isShutdown.get()) {
                currentScheduler.scheduleAtFixedRate(
                    {
                        try {
                            performStaleCheck()
                        } catch (e: Exception) {
                            Log.e(TAG, "주기적 정리 중 예외: ${e.message}", e)
                        }
                    },
                    autoCleanupIntervalMs,
                    autoCleanupIntervalMs,
                    TimeUnit.MILLISECONDS
                )
                Log.d(TAG, "✅ 주기적 정리 시작: ${autoCleanupIntervalMs}ms 간격")
            }
        } catch (e: Exception) {
            Log.e(TAG, "주기적 정리 시작 실패: ${e.message}", e)
        } finally {
            schedulerLock.unlock()
        }
    }

    fun performEmergencyReset() = poolLock.write {
        Log.w(TAG, " 응급 풀 리셋 시작")

        try {
            // 1. 스케줄러 안전 종료
            shutdownCurrentScheduler()

            // 2. 활성 참조 강제 해제
            val activeRefs = activeTags.values.toList()
            activeTags.clear()

            activeRefs.forEach { tagInfo ->
                try {
                    if (!tagInfo.managedBitmap.isReleased()) {
                        tagInfo.managedBitmap.forceRelease()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "활성 참조 해제 오류: ${e.message}")
                }
            }

            // 3. 원형큐 완전 재구성
            bitmapPool.clear()
            var recoveredCount = 0

            for (index in 0 until poolSize) {
                try {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val indexedBitmap = IndexedBitmap(bitmap, index)
                    bitmapPool.push(indexedBitmap)
                    recoveredCount++
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "비트맵 재생성 실패: idx=$index")
                    break
                }
            }

            // 4. 그래픽 객체 풀 재생성
            canvasPool.clear()
            paintPool.clear()
            matrixPool.clear()

            repeat(recoveredCount) {
                canvasPool.push(Canvas())
                paintPool.push(Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                matrixPool.push(Matrix())
            }

            // 5. 새 스케줄러 시작
            recreateScheduler()
            startPeriodicCleanup()

            // 6. 통계 초기화
            acquisitionCounter.set(0)
            releaseCounter.set(0)
            forceCleanupCounter.set(0)
            recycleCounter.set(0)

            Log.w(TAG, "✅ 응급 풀 리셋 완료: $recoveredCount/$poolSize 복구")

        } catch (e: Exception) {
            Log.e(TAG, "응급 리셋 중 오류: ${e.message}", e)
            throw e
        }
    }

    private fun clearBitmap(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled && bitmap.isMutable) {
                val canvas = getReusableCanvas()
                try {
                    canvas.setBitmap(bitmap)
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                } finally {
                    returnReusableCanvas(canvas)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "비트맵 클리어 실패: ${e.message}")
        }
    }

    private fun shutdownCurrentScheduler() {
        schedulerLock.lock()
        try {
            if (!currentScheduler.isShutdown) {
                currentScheduler.shutdown()
                try {
                    if (!currentScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                        currentScheduler.shutdownNow()
                        if (!currentScheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                            Log.e(TAG, "스케줄러 강제 종료 실패")
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

    private fun recreateScheduler() {
        schedulerLock.lock()
        try {
            currentScheduler = createNewScheduler()
            Log.d(TAG, "✅ 새 cleanup 스케줄러 생성")
        } finally {
            schedulerLock.unlock()
        }
    }

    private fun createNewScheduler(): ScheduledExecutorService {
        return Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AdvTaggedPool-Cleanup-${System.currentTimeMillis()}").apply {
                isDaemon = true
            }
        }
    }

    // 그래픽 객체 풀 관리 (원형큐 기반)
    fun getReusableCanvas(): Canvas = canvasPool.poll() ?: Canvas()
    fun returnReusableCanvas(canvas: Canvas) {
        try {
            canvas.setBitmap(null)
            canvasPool.push(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "Canvas 반환 실패: ${e.message}")
        }
    }

    fun getReusablePaint(): Paint = paintPool.poll() ?: Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    fun returnReusablePaint(paint: Paint) = paintPool.push(paint)

    fun getReusableMatrix(): Matrix = matrixPool.poll() ?: Matrix()
    fun returnReusableMatrix(matrix: Matrix) {
        matrix.reset()
        matrixPool.push(matrix)
    }

    fun getDetailedStatus(): String = poolLock.read {
        buildString {
            appendLine("====== 원형큐 기반 AdvancedTaggedBitmapPool Status ======")
            appendLine("Pool: ${bitmapPool.size()}/$poolSize Available")
            appendLine("Active: ${activeTags.size} (${String.format("%.1f", activeTags.size * 100.0 / poolSize)}%)")
            appendLine("통계: Acquired=${acquisitionCounter.get()}, Released=${releaseCounter.get()}")
            appendLine("     ForceCleaned=${forceCleanupCounter.get()}, Recycled=${recycleCounter.get()}")

            if (activeTags.isNotEmpty()) {
                appendLine("Active Tags (최대 10개):")
                val currentTime = System.currentTimeMillis()
                activeTags.entries.take(10).forEach { (index, info) ->
                    val age = currentTime - info.acquireTime
                    val access = currentTime - info.lastAccessTime.get()
                    appendLine("  [${index}] ${info.tag} | Age:${age}ms, Access:${access}ms")
                }
                if (activeTags.size > 10) {
                    appendLine("  ... 그 외 ${activeTags.size - 10}개")
                }
            }
            appendLine("===================================================")
        }
    }

    fun getStatus(): String = poolLock.read {
        "원형큐Pool: ${bitmapPool.size()}/$poolSize available, ${activeTags.size} active, " +
                "acquired=${acquisitionCounter.get()}, released=${releaseCounter.get()}"
    }

    fun getPoolHealthStatus(): PoolHealthStatus = poolLock.read {
        val available = bitmapPool.size()
        val active = activeTags.size
        val currentTime = System.currentTimeMillis()
        val staleCount = activeTags.count { (_, tagInfo) ->
            currentTime - tagInfo.lastAccessTime.get() > staleTimeoutMs
        }

        val healthLevel = when {
            available == 0 -> HealthLevel.CRITICAL
            available <= 3 -> HealthLevel.WARNING
            staleCount > 5 -> HealthLevel.DEGRADED
            else -> HealthLevel.HEALTHY
        }

        PoolHealthStatus(
            healthLevel = healthLevel,
            availableSlots = available,
            totalSlots = poolSize,
            totalReferences = active,
            staleSlots = staleCount,
            recommendation = when (healthLevel) {
                HealthLevel.CRITICAL -> "즉시 응급 복구 필요 - 풀 완전 고갈"
                HealthLevel.WARNING -> "예방적 정리 필요 - 가용 슬롯 부족"
                HealthLevel.DEGRADED -> "stale 참조 정리 필요"
                HealthLevel.HEALTHY -> "정상 상태"
            }
        )
    }

    fun forceCleanupStaleReferences() = performStaleCheck()
    fun isReady(): Boolean = poolLock.read { bitmapPool.size() > 0 && !isShutdown.get() }

    fun shutdown() {
        isShutdown.set(true)
        shutdownCurrentScheduler()

        poolLock.write {
            activeTags.clear()

            // 원형큐의 모든 비트맵 해제
            val snapshot = bitmapPool.snapshot()
            snapshot.forEach { indexedBitmap ->
                try {
                    indexedBitmap.bitmap.recycle()
                } catch (e: Exception) {
                    Log.w(TAG, "비트맵 해제 실패: ${e.message}")
                }
            }

            bitmapPool.clear()
            canvasPool.clear()
            paintPool.clear()
            matrixPool.clear()

            Log.d(TAG, "원형큐 기반 AdvancedTaggedBitmapPool 완전 종료")
        }
    }
}

/**
 * ManagedBitmap - Tagged 소유권 시스템 래퍼
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
    private val accessLock = ReentrantReadWriteLock()
    private val releaseCallCount = AtomicLong(0)

    // 논블로킹 업데이트를 위한 별도 스레드풀 (companion object에서 관리)
    companion object {
        private val accessTimeUpdateExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "AccessTimeUpdater").apply { isDaemon = true }
        }
    }

    fun updateLastAccess() {
        // 현재 구조에 맞는 논블로킹 업데이트
        accessTimeUpdateExecutor.execute {
            try {
                pool.activeTags[poolIndex]?.lastAccessTime?.set(System.currentTimeMillis())
            } catch (e: Exception) {
                // Access time 업데이트 실패는 치명적이지 않으므로 무시
                Log.v(TAG, "Access time 업데이트 무시: ${e.message}")
            }
        }
    }

    fun isValid(): Boolean {
        return accessLock.readLock().let { lock ->
            lock.lock()
            try {
                !isReleased &&
                        !bitmap.isRecycled &&
                        bitmap.width > 0 &&
                        bitmap.height > 0 &&
                        bitmap.config != null
            } catch (e: Exception) {
                Log.w(TAG, " 비트맵 유효성 검사 예외: ${e.message}")
                false
            } finally {
                lock.unlock()
            }
        }
    }

    fun release() {
        val callCount = releaseCallCount.incrementAndGet()
        if (callCount > 1) {
            Log.w(TAG, " 중복 release 호출 무시: $tag (호출 ${callCount}회)")
            return
        }

        accessLock.writeLock().let { lock ->
            lock.lock()
            try {
                if (isReleased) {
                    Log.w(TAG, " 이미 반납된 비트맵 무시: $tag (idx: $poolIndex)")
                    return
                }

                isReleased = true
                pool.release(this)
                Log.d(TAG, "ManagedBitmap 반납: $tag (idx: $poolIndex, age: ${getAgeMillis()}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "ManagedBitmap 반납 실패: $tag, ${e.message}", e)
            } finally {
                lock.unlock()
            }
        }
    }

    // 강제 해제 (내부용)
    internal fun forceRelease() {
        accessLock.writeLock().let { lock ->
            lock.lock()
            try {
                if (!isReleased) {
                    isReleased = true
                    pool.release(this)
                } else {

                }
            } catch (e: Exception) {
                Log.e(TAG, "강제 반납 실패: $tag, ${e.message}")
            } finally {
                lock.unlock()
            }
        }
    }

    fun createSafeCopyForUI(): Bitmap? {
        return accessLock.readLock().let { lock ->
            lock.lock()
            try {
                if (!isValid()) {
                    Log.w(TAG, "무효한 비트맵에서 UI 복사 시도: $tag")
                    return null
                }

                updateLastAccess()
                val config = bitmap.config ?: Bitmap.Config.ARGB_8888
                bitmap.copy(config, false)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "UI 복사본 생성 OOM: $tag", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "UI 복사본 생성 실패: $tag, ${e.message}", e)
                null
            } finally {
                lock.unlock()
            }
        }
    }

    fun isReleased(): Boolean = isReleased
    fun getAgeMillis(): Long = System.currentTimeMillis() - creationTime
}

/**
 * 원형큐 기반 고속 프로세서
 */
class HighSpeedZeroCopyProcessor(
    private val bitmapPool: AdvancedTaggedBitmapPool,
    private val targetWidth: Int = 840,
    private val targetHeight: Int = 840
) {
    private val TAG = "HighSpeedZeroCopyProcessor"
    private val processedFrames = AtomicLong(0)

    fun HighSpeedZeroCopyProcessor(
        imageBytes: ByteArray,
        rotationDegrees: Int,
        purpose: BitmapPurpose
    ): ManagedBitmap? {
        return processHighQualityZeroCopy(imageBytes, rotationDegrees, purpose)
    }

    // 목적별 비트맵 획득 (간단하게)
    fun acquireOptimized(tag: String, purpose: BitmapPurpose): ManagedBitmap? {
        val callerClass = Thread.currentThread().stackTrace[3].className.substringAfterLast('.')
        val callerMethod = Thread.currentThread().stackTrace[3].methodName
        val purposeTag = purpose.name
        val timestamp = System.currentTimeMillis()

        val finalTag = "${purposeTag}_${callerClass}_${callerMethod}_${timestamp}"
        return bitmapPool.acquire(finalTag)
    }

    fun processHighQualityZeroCopy(
        imageBytes: ByteArray,
        rotationDegrees: Int,
        purpose: BitmapPurpose = BitmapPurpose.IMAGE_PROCESSING
    ): ManagedBitmap? {
        val callerClass = Thread.currentThread().stackTrace[3].className.substringAfterLast('.')
        val callerMethod = Thread.currentThread().stackTrace[3].methodName
        val purposeTag = purpose.name
        val timestamp = System.currentTimeMillis()

        val processingTag = "${purposeTag}_${callerClass}_${callerMethod}_${timestamp}"
        val managedBitmap = bitmapPool.acquire(processingTag)

        if (managedBitmap == null) {
            Log.w(TAG, "ManagedBitmap 획득 실패: $processingTag")
            return null
        }

        val bitmap = managedBitmap.bitmap
        val bitmapHash = bitmap.hashCode().toString(16)

        if (!isValidBitmap(bitmap)) {
            Log.e(TAG, "Invalid bitmap detected: @$bitmapHash, tag: $processingTag")
            managedBitmap.release()
            return null
        }

        val canvas = bitmapPool.getReusableCanvas()
        val paint = bitmapPool.getReusablePaint()
        val matrix = bitmapPool.getReusableMatrix()

        try {
            if (!safeSetCanvasBitmap(canvas, bitmap)) {
                Log.e(TAG, "Canvas.setBitmap() failed: @$bitmapHash, tag: $processingTag")
                managedBitmap.release()
                return null
            }

            val sourceBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (sourceBitmap == null || sourceBitmap.isRecycled) {
                Log.w(TAG, "Bitmap decode failed: @$bitmapHash, tag: $processingTag")
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
                managedBitmap.updateLastAccess()
            } catch (e: Exception) {
                Log.e(TAG, "Canvas.drawBitmap failed: @$bitmapHash, tag: $processingTag, ${e.message}", e)
                sourceBitmap.recycle()
                managedBitmap.release()
                return null
            }

            sourceBitmap.recycle()

            val frameNum = processedFrames.incrementAndGet()
            Log.d(TAG, "비트맵 처리 완료: @$bitmapHash, frame=$frameNum, tag=$processingTag")

            return managedBitmap

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during processing @$bitmapHash, tag: $processingTag", e)
            managedBitmap.release()
            System.gc()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error @$bitmapHash, tag: $processingTag, ${e.message}", e)
            managedBitmap.release()
            return null
        } finally {
            try {
                bitmapPool.returnReusableCanvas(canvas)
                bitmapPool.returnReusablePaint(paint)
                bitmapPool.returnReusableMatrix(matrix)
            } catch (e: Exception) {
                Log.w(TAG, "Error returning reusable objects: ${e.message}")
            }
        }
    }

    // FPS 드롭 처리 (단순하게)
    fun handleSurfaceFpsDegrade(currentFps: Double, context: String = "") {
        if (currentFps <= 5.0) {
            Log.w(TAG, "치명적 FPS 드롭 감지: ${currentFps}fps, context: $context")
            bitmapPool.performEmergencyReset()
        } else if (currentFps <= 10.0) {
            Log.w(TAG, "FPS 드롭 감지: ${currentFps}fps, context: $context")
            bitmapPool.forceCleanupStaleReferences()
        }
    }

    // 풀 관리 위임
    fun getPoolHealthStatus(): PoolHealthStatus = bitmapPool.getPoolHealthStatus()
    fun requestPoolCleanup() = bitmapPool.forceCleanupStaleReferences()
    fun performEmergencyReset() = bitmapPool.performEmergencyReset()
    fun performPoolMaintenance() = bitmapPool.forceCleanupStaleReferences()

    private fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return try {
            bitmap != null &&
                    !bitmap.isRecycled &&
                    bitmap.width > 0 &&
                    bitmap.height > 0 &&
                    bitmap.config != null
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap validation exception: ${e.message}")
            false
        }
    }

    private fun safeSetCanvasBitmap(canvas: Canvas, bitmap: Bitmap): Boolean {
        return try {
            if (!isValidBitmap(bitmap)) {
                Log.w(TAG, "Cannot set invalid bitmap to canvas")
                return false
            }

            canvas.setBitmap(bitmap)
            return canvas.width > 0 && canvas.height > 0

        } catch (e: Exception) {
            Log.e(TAG, "Exception in Canvas.setBitmap(): ${e.message}", e)
            false
        }
    }

    fun getDetailedStatus(): String {
        return buildString {
            appendLine("=== HighSpeedZeroCopyProcessor Status ===")
            appendLine("Processed Frames: ${processedFrames.get()}")
            appendLine()
            append(bitmapPool.getDetailedStatus())
        }
    }

    fun getStatus(): String = "Processor: Processed=${processedFrames.get()}"

    fun cleanup() {
        processedFrames.set(0)
        Log.d(TAG, "프로세서 정리 완료")
    }
}
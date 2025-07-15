package com.example.myapplication.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.Log
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 🎯 Lock-Free 고급 비트맵 풀 - 30Hz 고속 처리 최적화
 */
class AdvancedBitmapPool(
    private val poolSize: Int = 8,
    private val width: Int = 840,
    private val height: Int = 840,
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888
) {
    private val TAG = "AdvancedBitmapPool"

    // Lock-Free 구조체
    private val available = ConcurrentLinkedQueue<ManagedBitmap>()
    private val tracking = ConcurrentHashMap<Long, ManagedBitmap>()
    private val idGenerator = AtomicLong(0)

    @Volatile
    private var isInitialized = false

    // 메모리 관리 통계
    private val totalAllocated = AtomicInteger(0)
    private val totalReleased = AtomicInteger(0)
    private val activeReferences = AtomicInteger(0)

    /**
     * 🚀 Lock-Free Reference Counting ManagedBitmap
     */
    inner class ManagedBitmap(
        val bitmap: Bitmap,
        val id: Long
    ) {
        private val STATE_ACTIVE = 0
        private val STATE_RELEASING = 1
        private val STATE_RELEASED = 2

        private val refCount = AtomicInteger(1)
        private val state = AtomicInteger(STATE_ACTIVE)
        /**
         * Lock-Free Reference Addition with Double-Checked Locking
         */
        fun addRef(): ManagedBitmap? {
            var attempts = 0
            while (attempts < 100) { // Prevent infinite loops
                val currentState = state.get()
                if (currentState != STATE_ACTIVE) {
                    Log.w(TAG, "⚠️ 비활성 상태 비트맵 참조 시도: id=$id, state=$currentState")
                    return null
                }

                val currentCount = refCount.get()
                if (currentCount <= 0) {
                    Log.w(TAG, "⚠️ 제로 참조 카운트 비트맵 접근: id=$id")
                    return null
                }

                // Atomic increment with CAS
                if (refCount.compareAndSet(currentCount, currentCount + 1)) {
                    // Double-check state after successful increment
                    if (state.get() == STATE_ACTIVE) {
                        activeReferences.incrementAndGet()
                        Log.d(TAG, "📈 참조 카운트 증가: id=$id, count=${currentCount + 1}")
                        return this
                    } else {
                        // State changed during increment, rollback atomically
                        val rolledBack = refCount.decrementAndGet()
                        activeReferences.decrementAndGet()
                        Log.w(TAG, "🔄 상태 변경으로 롤백: id=$id, count=$rolledBack")
                        return null
                    }
                }

                // CAS failed, exponential backoff
                attempts++
                if (attempts > 10) Thread.yield()
            }

            Log.e(TAG, "❌ 참조 획득 시도 초과: id=$id")
            return null
        }

        /**
         * Thread-Safe Release with State Management
         */
        fun release() {
            val newCount = refCount.decrementAndGet()
            activeReferences.decrementAndGet()
            Log.d(TAG, "📉 참조 카운트 감소: id=$id, count=$newCount")

            if (newCount == 0) {
                // Only one thread can transition from ACTIVE to RELEASING
                if (state.compareAndSet(STATE_ACTIVE, STATE_RELEASING)) {
                    try {
                        returnToPool()
                        Log.d(TAG, "🔄 비트맵 풀 반환: id=$id")
                    } finally {
                        state.set(STATE_RELEASED)
                    }
                }
            } else if (newCount < 0) {
                // Defensive recovery for over-release
                Log.e(TAG, "❌ 과도한 release 호출: id=$id, count=$newCount")
                refCount.set(0)
                if (state.compareAndSet(STATE_ACTIVE, STATE_RELEASED)) {
                    forceRecycle()
                }
            }
        }

        /**
         * Attempt to reactivate bitmap from pool
         */
        fun tryReactivate(): Boolean {
            return if (state.compareAndSet(STATE_RELEASED, STATE_ACTIVE)) {
                refCount.set(1)
                activeReferences.incrementAndGet()
                Log.d(TAG, "🔄 비트맵 재활성화: id=$id")
                true
            } else {
                false
            }
        }

        private fun returnToPool() {
            if (!bitmap.isRecycled && available.size < poolSize) {
                try {
                    // Clear canvas for reuse
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                    // Return to available pool
                    available.offer(this)
                    totalReleased.incrementAndGet()
                    Log.d(TAG, "✅ 비트맵 풀 반환 완료: id=$id")
                } catch (e: Exception) {
                    Log.e(TAG, "비트맵 클리어 실패: id=$id, ${e.message}")
                    forceRecycle()
                }
            } else {
                // Pool full or bitmap damaged
                forceRecycle()
            }
        }

        fun forceRecycle() {
            try {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    Log.d(TAG, "🗑️ 비트맵 강제 해제: id=$id")
                }
            } catch (e: Exception) {
                Log.e(TAG, "비트맵 해제 오류: id=$id, ${e.message}")
            } finally {
                tracking.remove(id)
            }
        }

        fun isValid(): Boolean =
            !bitmap.isRecycled &&
                    state.get() == STATE_ACTIVE &&
                    refCount.get() > 0
    }

    /**
     * Pool 초기화 - Thread-Safe
     */
    fun initialize(): Boolean {
        if (isInitialized && available.size > 0) {
            Log.d(TAG, "✅ 비트맵 풀 이미 초기화됨 (${available.size}개 사용가능)")
            return true
        }

        synchronized(this) {
            if (isInitialized) {
                cleanup()
            }

            try {
                repeat(poolSize) { index ->
                    val bitmap = Bitmap.createBitmap(width, height, config)
                    val id = idGenerator.incrementAndGet()
                    val managedBitmap = ManagedBitmap(bitmap, id)

                    tracking[id] = managedBitmap
                    managedBitmap.release() // Initial release to pool
                    Log.d(TAG, "✅ 관리형 비트맵 초기화: id=$id")
                }

                isInitialized = true
                totalAllocated.set(poolSize)
                Log.d(TAG, "🎯 Lock-Free 비트맵 풀 초기화 완료: $poolSize")
                return true

            } catch (e: Exception) {
                Log.e(TAG, "❌ 비트맵 풀 초기화 실패: ${e.message}", e)
                cleanup()
                return false
            }
        }
    }

    /**
     * Lock-Free Bitmap Acquisition
     */
    fun acquireBitmap(): ManagedBitmap? {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ 풀이 초기화되지 않음")
            return null
        }

        // Memory pressure check
        if (!canAllocateMemory()) {
            Log.w(TAG, "⚠️ 메모리 부족으로 비트맵 할당 불가")
            return null
        }

        // Try to reuse from available pool
        while (true) {
            val managedBitmap = available.poll() ?: break
            if (managedBitmap.tryReactivate() && !managedBitmap.bitmap.isRecycled) {
                Log.d(TAG, "🎯 풀에서 비트맵 재사용 성공: id=${managedBitmap.id}")
                return managedBitmap
            }
        }

        // Create new bitmap if pool is empty
        return createNewBitmap()
    }

    private fun createNewBitmap(): ManagedBitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, config)
            val id = idGenerator.incrementAndGet()
            val managedBitmap = ManagedBitmap(bitmap, id)

            tracking[id] = managedBitmap
            totalAllocated.incrementAndGet()
            activeReferences.incrementAndGet()

            Log.d(TAG, "🆕 새 관리형 비트맵 생성: id=$id")
            managedBitmap

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ OOM: 새 비트맵 생성 실패", e)
            forceGC()
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ 비트맵 생성 실패: ${e.message}", e)
            null
        }
    }

    private fun canAllocateMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val requiredMemory = width * height * 4L // ARGB_8888
        val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return availableMemory > requiredMemory * 3 // 3x safety margin
    }

    private fun forceGC() {
        System.gc()
        System.runFinalization()
        Thread.yield()
    }

    /**
     * 풀 상태 정보
     */
    fun getPoolStatus(): String {
        val availableCount = available.size
        val trackingCount = tracking.size
        val activeRefs = activeReferences.get()

        val memoryInfo = Runtime.getRuntime().let {
            val used = (it.totalMemory() - it.freeMemory()) / (1024 * 1024)
            val max = it.maxMemory() / (1024 * 1024)
            "$used/${max}MB"
        }

        return "AdvancedBitmapPool: Available=$availableCount, Tracking=$trackingCount, " +
                "ActiveRefs=$activeRefs, Allocated=${totalAllocated.get()}, " +
                "Released=${totalReleased.get()}, Memory=$memoryInfo"
    }

    /**
     * 풀 정리 - Force cleanup all resources
     */
    fun cleanup() {
        synchronized(this) {
            // Force release all tracked bitmaps
            tracking.values.forEach { managedBitmap ->
                try {
                    managedBitmap.forceRecycle()
                } catch (e: Exception) {
                    Log.w(TAG, "추적 비트맵 정리 실패: id=${managedBitmap.id}, ${e.message}")
                }
            }
            tracking.clear()

            // Clear available pool
            while (available.isNotEmpty()) {
                val managedBitmap = available.poll()
                managedBitmap?.forceRecycle()
            }

            isInitialized = false
            totalAllocated.set(0)
            totalReleased.set(0)
            activeReferences.set(0)

            Log.d(TAG, "🗑️ Lock-Free 비트맵 풀 정리 완료")
        }
    }
}

class SafeZeroCopyFrameProcessor(
    private val bitmapPool: AdvancedBitmapPool,
    private val targetWidth: Int = 840,
    private val targetHeight: Int = 840
) {
    private val TAG = "SafeZeroCopyFrameProcessor"

    // Object Pooling for high-frequency operations
    private val matrixPool = ConcurrentLinkedQueue<Matrix>()
    private val paintPool = ConcurrentLinkedQueue<Paint>()

    // 후처리용 Executor (Detection, UI 업데이트 등)
    private val postProcessingExecutor: Executor = Executors.newFixedThreadPool(2) {
        Thread(it, "PostProcessor-${it.hashCode()}").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }

    private val activeProcessing = AtomicInteger(0)
    private val processedFrames = AtomicLong(0)

    init {
        // Pre-populate object pools
        repeat(4) {
            matrixPool.offer(Matrix())
            paintPool.offer(Paint().apply {
                isFilterBitmap = true
                isAntiAlias = false
            })
        }
    }

    /**
     * 🚀 Image Lifecycle Safe 처리 - 동기 변환 + 비동기 후처리
     */
    fun processSafely(
        sourceImage: android.media.Image,
        rotationDegrees: Int,
        imageTimestamp: Long,
        callback: (ProcessedFrame?) -> Unit
    ) {
        try {
            // 🎯 Step 1: 동기적으로 이미지를 비트맵으로 변환 (Image 생존 중)
            val imageData = extractImageData(sourceImage)
            if (imageData == null) {
                callback(null)
                return
            }

            // 🎯 Step 2: 비동기로 비트맵 처리 (Image 독립적)
            if (activeProcessing.get() >= 3) {
                Log.w(TAG, "🔴 처리 큐 포화 - 프레임 스킵")
                callback(null)
                return
            }

            activeProcessing.incrementAndGet()
            postProcessingExecutor.execute {
                try {
                    val result = processImageData(imageData, rotationDegrees, imageTimestamp)
                    processedFrames.incrementAndGet()
                    callback(result)
                } catch (e: Exception) {
                    Log.e(TAG, "후처리 실패: ${e.message}", e)
                    callback(null)
                } finally {
                    activeProcessing.decrementAndGet()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Safe 처리 실패: ${e.message}", e)
            callback(null)
        }
    }

    /**
     * 🎯 Image 생존 중에 필요한 모든 데이터 추출
     */
    private fun extractImageData(image: android.media.Image): ImageData? {
        return try {
            when (image.format) {
                android.graphics.ImageFormat.JPEG -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    ImageData.JpegData(
                        bytes = bytes,
                        width = image.width,
                        height = image.height,
                        timestamp = image.timestamp
                    )
                }
                android.graphics.ImageFormat.YUV_420_888 -> {
                    val yuvData = extractYuvData(image)
                    ImageData.YuvData(
                        yuvBytes = yuvData,
                        width = image.width,
                        height = image.height,
                        timestamp = image.timestamp
                    )
                }
                else -> {
                    Log.w(TAG, "지원하지 않는 이미지 포맷: ${image.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "이미지 데이터 추출 실패: ${e.message}", e)
            null
        }
    }

    /**
     * 🎯 YUV 데이터 추출 (Image 생존 중)
     */
    private fun extractYuvData(image: android.media.Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    /**
     * 🎯 추출된 데이터로 비트맵 처리 (Image 독립적)
     */
    private fun processImageData(
        imageData: ImageData,
        rotationDegrees: Int,
        originalTimestamp: Long
    ): ProcessedFrame? {
        val managedBitmap = bitmapPool.acquireBitmap() ?: return null

        try {
            val matrix = getPooledMatrix()
            val paint = getPooledPaint()

            try {
                // Canvas 설정
                val canvas = Canvas(managedBitmap.bitmap)
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)

                // 이미지 데이터로부터 비트맵 생성
                val sourceBitmap = when (imageData) {
                    is ImageData.JpegData -> {
                        android.graphics.BitmapFactory.decodeByteArray(
                            imageData.bytes, 0, imageData.bytes.size
                        )
                    }
                    is ImageData.YuvData -> {
                        createBitmapFromYuv(imageData)
                    }
                }

                if (sourceBitmap == null) {
                    managedBitmap.release()
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

                // 비트맵 그리기
                canvas.drawBitmap(sourceBitmap, matrix, paint)

                // 즉시 정리
                if (!sourceBitmap.isRecycled) {
                    sourceBitmap.recycle()
                }

                Log.d(TAG, "✅ Safe 프레임 처리 완료: frame=${processedFrames.get()}")

                return ProcessedFrame(
                    managedBitmap = managedBitmap,
                    frameId = System.nanoTime(),
                    originalTimestamp = originalTimestamp,
                    processedTimestamp = System.currentTimeMillis()
                )

            } finally {
                returnPooledMatrix(matrix)
                returnPooledPaint(paint)
            }

        } catch (e: Exception) {
            Log.e(TAG, "이미지 데이터 처리 실패: ${e.message}", e)
            managedBitmap.release()
            return null
        }
    }

    private fun createBitmapFromYuv(yuvData: ImageData.YuvData): android.graphics.Bitmap {
        val yuvImage = android.graphics.YuvImage(
            yuvData.yuvBytes,
            android.graphics.ImageFormat.NV21,
            yuvData.width,
            yuvData.height,
            null
        )

        return java.io.ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, yuvData.width, yuvData.height),
                90,
                out
            )
            val bytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("YUV 비트맵 디코딩 실패")
        }
    }

    // Object pooling methods (기존과 동일)
    private fun getPooledMatrix(): Matrix = matrixPool.poll() ?: Matrix()
    private fun returnPooledMatrix(matrix: Matrix) {
        if (matrixPool.size < 4) {
            matrix.reset()
            matrixPool.offer(matrix)
        }
    }

    private fun getPooledPaint(): Paint = paintPool.poll() ?: Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    private fun returnPooledPaint(paint: Paint) {
        if (paintPool.size < 4) {
            paintPool.offer(paint)
        }
    }

    fun getProcessorStatus(): String {
        return "SafeZeroCopyProcessor: Active=${activeProcessing.get()}/3, " +
                "Processed=${processedFrames.get()}, " +
                "MatrixPool=${matrixPool.size}, PaintPool=${paintPool.size}"
    }

    fun cleanup() {
        matrixPool.clear()
        paintPool.clear()
        processedFrames.set(0)
        Log.d(TAG, "🗑️ Safe ZeroCopy 프로세서 정리 완료")
    }
}

/**
 * 🎯 이미지 데이터 클래스
 */
sealed class ImageData {
    data class JpegData(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long
    ) : ImageData()

    data class YuvData(
        val yuvBytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long
    ) : ImageData()
}

/**
 * 🎯 처리된 프레임 클래스
 */
data class ProcessedFrame(
    val managedBitmap: AdvancedBitmapPool.ManagedBitmap,
    val frameId: Long,
    val originalTimestamp: Long,
    val processedTimestamp: Long
)
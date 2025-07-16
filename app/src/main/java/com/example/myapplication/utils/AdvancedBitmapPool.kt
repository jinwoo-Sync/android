package com.example.myapplication.utils

import android.graphics.*
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 🎯 확장된 Zero-Copy 비트맵 풀 - 단순히 크기만 늘린 안전한 버전
 */
class TrueZeroCopyBitmapPool(
    private val poolSize: Int = 20, // 기본 20개로 증가
    private val width: Int = 840,
    private val height: Int = 840
) {
    private val TAG = "TrueZeroCopyBitmapPool"

    // 🚀 Simple Pool without complex state management
    private val availableBitmaps = ConcurrentLinkedQueue<Bitmap>()
    private val activeBitmaps = ConcurrentHashMap<Bitmap, AtomicInteger>()

    // Canvas/Paint 재사용 (GC 방지)
    private val canvasPool = ConcurrentLinkedQueue<Canvas>()
    private val paintPool = ConcurrentLinkedQueue<Paint>()
    private val matrixPool = ConcurrentLinkedQueue<Matrix>()

    // 통계
    private val totalCreated = AtomicInteger(0)
    private val totalReused = AtomicInteger(0)

    init {
        // 🛠️ 풀 사전 초기화 - for문으로 변경 (break 에러 해결)
        for (i in 0 until poolSize) {
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                availableBitmaps.offer(bitmap)
                totalCreated.incrementAndGet()
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "⚠️ 초기화 중 OOM: ${i + 1}/${poolSize}")
                System.gc()
                break // ✅ for문에서는 break 사용 가능
            }
        }

        // Canvas/Paint 풀 초기화 - 풀 크기에 맞춰 증가
        val helperPoolSize = (poolSize * 0.3).toInt().coerceAtLeast(4)
        for (i in 0 until helperPoolSize) {
            canvasPool.offer(Canvas())
            paintPool.offer(Paint().apply {
                isFilterBitmap = true
                isAntiAlias = false
            })
            matrixPool.offer(Matrix())
        }

        Log.d(TAG, "🎯 확장된 Zero-Copy 풀 초기화 완료: ${availableBitmaps.size}/${poolSize} bitmaps")
    }

    /**
     * 🚀 단순하고 빠른 비트맵 획득
     */
    fun acquireSharedBitmap(): SharedBitmap? {
        val bitmap = availableBitmaps.poll() ?: createNewBitmap()
        if (bitmap != null && !bitmap.isRecycled) {
            activeBitmaps[bitmap] = AtomicInteger(1)
            totalReused.incrementAndGet()
            Log.d(TAG, "📥 Bitmap acquired: @${bitmap.hashCode().toString(16)}, available=${availableBitmaps.size}")
            return SharedBitmap(bitmap, this)
        }
        return null
    }

    private fun createNewBitmap(): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            totalCreated.incrementAndGet()
            Log.d(TAG, "🆕 새 비트맵 생성: @${bitmap.hashCode().toString(16)}, total=${totalCreated.get()}")
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ OOM: 비트맵 생성 실패", e)
            System.gc()
            null
        }
    }

    /**
     * 🎯 내부 참조 증가 (Thread-Safe)
     */
    internal fun addReference(bitmap: Bitmap): Boolean {
        val refCount = activeBitmaps[bitmap]
        return if (refCount != null) {
            val newCount = refCount.incrementAndGet()
            Log.d(TAG, "📈 Reference increased: @${bitmap.hashCode().toString(16)} -> $newCount")
            true
        } else {
            Log.w(TAG, "⚠️ Cannot add reference to inactive bitmap: @${bitmap.hashCode().toString(16)}")
            false
        }
    }

    /**
     * 🎯 개선된 참조 해제 - 더 안전한 정리
     */
    internal fun releaseReference(bitmap: Bitmap) {
        val refCount = activeBitmaps[bitmap]
        if (refCount != null) {
            val newCount = refCount.decrementAndGet()
            Log.d(TAG, "📉 Reference decreased: @${bitmap.hashCode().toString(16)} -> $newCount")

            if (newCount <= 0) {
                // ✅ 안전한 제거
                synchronized(activeBitmaps) {
                    activeBitmaps.remove(bitmap)
                }
                // ✅ 무조건 반환 시도 (조건 실패 시에도 안전 처리)
                returnToPool(bitmap)
            }
        } else {
            Log.w(TAG, "⚠️ Unknown bitmap release attempt: @${bitmap.hashCode().toString(16)}")
            // ✅ 알 수 없는 비트맵도 안전하게 처리
            if (!bitmap.isRecycled) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }, 100)
            }
        }
    }

    /**
     * 🎯 비트맵 풀 반환 - 클리어하지 않고 다음 프레임에서 덮어씌우기
     */
    private fun returnToPool(bitmap: Bitmap) {
        try {
            // ✅ 1차 검증: 기본적인 비트맵 상태
            if (bitmap.isRecycled) {
                Log.w(TAG, "⚠️ 이미 재활용된 비트맵 반환 시도")
                return
            }

            // ✅ 2차 검증: 크기 및 설정
            if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.config == null) {
                Log.w(TAG, "⚠️ 무효한 비트맵 감지 - 안전하게 재활용: ${bitmap.width}x${bitmap.height}")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }, 50)
                return
            }

            // ✅ 3차 검증: 풀 용량 및 상태
            if (availableBitmaps.size >= poolSize) {
                Log.d(TAG, "📦 풀이 가득참 - 비트맵 재활용: ${availableBitmaps.size}/${poolSize}")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }, 100)
                return
            }

            // ✅ 안전한 풀 반환
            availableBitmaps.offer(bitmap)
            Log.d(TAG, "✅ 비트맵 풀 반환 성공: available=${availableBitmaps.size}/${poolSize}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 비트맵 반환 중 예외: ${e.message}", e)
            // 예외 발생 시에도 안전하게 재활용
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (recycleException: Exception) {
                    Log.e(TAG, "❌ 비트맵 재활용 실패: ${recycleException.message}")
                }
            }, 200)
        }
    }

    /**
     * 🎯 강제 비트맵 상태 정리 (메모리 누수 방지)
     */
    fun forceCleanupStaleReferences() {
        val staleBitmaps = mutableListOf<Bitmap>()

        activeBitmaps.forEach { (bitmap, refCount) ->
            if (bitmap.isRecycled || refCount.get() <= 0) {
                staleBitmaps.add(bitmap)
                Log.w(TAG, "🧹 발견된 stale bitmap: @${bitmap.hashCode().toString(16)}, recycled=${bitmap.isRecycled}, refCount=${refCount.get()}")
            }
        }

        staleBitmaps.forEach { bitmap ->
            activeBitmaps.remove(bitmap)
            if (!bitmap.isRecycled) {
                returnToPool(bitmap)
            }
        }

        Log.d(TAG, "🧹 Stale cleanup: removed=${staleBitmaps.size}, current available=${availableBitmaps.size}")
    }

    /**
     * 🎯 상세 풀 상태 진단
     */
    fun getDetailedStatus(): String {
        val activeDetails = activeBitmaps.entries.joinToString(", ") { (bitmap, refCount) ->
            "Bitmap@${bitmap.hashCode().toString(16)}:ref=${refCount.get()}"
        }

        return buildString {
            appendLine("=== 확장된 비트맵 풀 상태 ===")
            appendLine("Pool Size: $poolSize")
            appendLine("Available: ${availableBitmaps.size}/${poolSize}")
            appendLine("Active: ${activeBitmaps.size}")
            appendLine("Created: ${totalCreated.get()}")
            appendLine("Reused: ${totalReused.get()}")
            appendLine("Active Details: [$activeDetails]")

            // 추가 진단 정보
            val availableHashes = availableBitmaps.map { "@${it.hashCode().toString(16)}" }
            appendLine("Available Bitmaps: [${availableHashes.joinToString(", ")}]")
        }
    }

    /**
     * 🎯 재사용 가능한 Canvas 획득
     */
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

    fun getStatus(): String {
        return "ZeroCopyPool: Available=${availableBitmaps.size}/${poolSize}, " +
                "Active=${activeBitmaps.size}, Created=${totalCreated.get()}, " +
                "Reused=${totalReused.get()}"
    }

    fun cleanup() {
        Log.d(TAG, "🗑️ Starting pool cleanup...")

        // 모든 활성 비트맵 강제 해제
        activeBitmaps.keys.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
                Log.d(TAG, "♻️ Force recycled active bitmap: @${bitmap.hashCode().toString(16)}")
            }
        }
        activeBitmaps.clear()

        // 사용 가능한 비트맵들 해제
        var recycledCount = 0
        while (availableBitmaps.isNotEmpty()) {
            val bitmap = availableBitmaps.poll()
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
                recycledCount++
            }
        }

        canvasPool.clear()
        paintPool.clear()
        matrixPool.clear()

        Log.d(TAG, "🗑️ Zero-Copy 풀 정리 완료: recycled=$recycledCount bitmaps")
    }
}

/**
 * 🎯 공유 비트맵 래퍼 - 단순한 참조 카운팅 + 개선된 로깅
 */
class SharedBitmap(
    val bitmap: Bitmap,
    private val pool: TrueZeroCopyBitmapPool
) {
    private val TAG = "SharedBitmap"
    private val bitmapHash = bitmap.hashCode().toString(16)
    private val isReleased = AtomicBoolean(false)  // 🎯 추가

    /**
     * 🛡️ 안전한 참조 추가 - Double-Check Locking
     */
    fun addRef(): SharedBitmap? {
        // 이미 해제된 경우 즉시 실패
        if (isReleased.get() || bitmap.isRecycled) {
            Log.w(TAG, "⚠️ 이미 해제된 비트맵 참조 시도: @$bitmapHash")
            return null
        }

        return if (pool.addReference(bitmap)) {
            Log.d(TAG, "📈 참조 추가 성공: @$bitmapHash")
            SharedBitmap(bitmap, pool)
        } else {
            Log.w(TAG, "⚠️ 참조 추가 실패: @$bitmapHash")
            null
        }
    }

    /**
     * 🛡️ 안전한 참조 해제 - 중복 해제 방지
     */
    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            pool.releaseReference(bitmap)
            Log.d(TAG, "📉 참조 해제: @$bitmapHash")
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
     * 🎯 UI 안전 비트맵 획득 - Canvas 크래시 방지
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
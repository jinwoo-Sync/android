package com.example.myapplication.utils

import android.graphics.*
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 🎯 진짜 Zero-Copy 비트맵 풀 - GC Pressure 최소화 + 개선된 디버깅
 */
class TrueZeroCopyBitmapPool(
    private val poolSize: Int = 6,
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
        // 풀 사전 초기화
        repeat(poolSize) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            availableBitmaps.offer(bitmap)
            totalCreated.incrementAndGet()
        }

        // Canvas/Paint 풀 초기화
        repeat(4) {
            canvasPool.offer(Canvas())
            paintPool.offer(Paint().apply {
                isFilterBitmap = true
                isAntiAlias = false
            })
            matrixPool.offer(Matrix())
        }

        Log.d(TAG, "🎯 Zero-Copy 풀 초기화 완료: $poolSize bitmaps")
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
                activeBitmaps.remove(bitmap)
                returnToPool(bitmap)
                Log.d(TAG, "🔄 Bitmap returned to pool: available=${availableBitmaps.size}")
            }
        } else {
            Log.w(TAG, "⚠️ Attempted to release unknown bitmap: @${bitmap.hashCode().toString(16)}")
        }
    }

    /**
     * 🎯 비트맵 풀 반환 - 클리어하지 않고 다음 프레임에서 덮어씌우기
     */
    private fun returnToPool(bitmap: Bitmap) {
        if (!bitmap.isRecycled && availableBitmaps.size < poolSize) {
            // 🚀 클리어 제거: 다음 프레임에서 새 데이터로 덮어씌우므로 불필요
            availableBitmaps.offer(bitmap)
            Log.d(TAG, "✅ 비트맵 풀 반환: @${bitmap.hashCode().toString(16)}, available=${availableBitmaps.size}")
        } else {
            // 풀이 가득 찼거나 비트맵 손상 시 해제
            if (!bitmap.isRecycled) {
                bitmap.recycle()
                Log.d(TAG, "♻️ 비트맵 해제: @${bitmap.hashCode().toString(16)} (풀 가득참 또는 손상)")
            }
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
            appendLine("=== 비트맵 풀 상세 상태 ===")
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
        if (canvasPool.size < 4) {
            canvas.setBitmap(null)
            canvasPool.offer(canvas)
        }
    }

    fun getReusablePaint(): Paint = paintPool.poll() ?: Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
    fun returnReusablePaint(paint: Paint) {
        if (paintPool.size < 4) paintPool.offer(paint)
    }

    fun getReusableMatrix(): Matrix = matrixPool.poll() ?: Matrix()
    fun returnReusableMatrix(matrix: Matrix) {
        if (matrixPool.size < 4) {
            matrix.reset()
            matrixPool.offer(matrix)
        }
    }

    fun getStatus(): String {
        return "ZeroCopyPool: Available=${availableBitmaps.size}, " +
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

    /**
     * 🚀 빠른 참조 추가 (UI/Detection 동시 사용)
     */
    fun addRef(): SharedBitmap? {
        return if (pool.addReference(bitmap)) {
            Log.d(TAG, "📈 참조 추가 성공: @$bitmapHash")
            SharedBitmap(bitmap, pool)
        } else {
            Log.w(TAG, "⚠️ 참조 추가 실패: @$bitmapHash")
            null
        }
    }

    /**
     * 🎯 참조 해제
     */
    fun release() {
        pool.releaseReference(bitmap)
        Log.d(TAG, "📉 참조 해제: @$bitmapHash")
    }

    fun isValid(): Boolean = !bitmap.isRecycled
}

/**
 * 🚀 고속 Zero-Copy 프레임 프로세서 - 개선된 로깅
 */
class HighSpeedZeroCopyProcessor(
    private val bitmapPool: TrueZeroCopyBitmapPool,
    private val targetWidth: Int = 840,
    private val targetHeight: Int = 840
) {
    private val TAG = "HighSpeedZeroCopyProcessor"
    private val processedFrames = AtomicLong(0)

    /**
     * 🚀 Zero-Copy 프레임 처리 - 모든 객체 재사용
     */
    fun processZeroCopy(
        imageBytes: ByteArray,
        rotationDegrees: Int
    ): SharedBitmap? {
        val sharedBitmap = bitmapPool.acquireSharedBitmap() ?: return null
        val bitmapHash = sharedBitmap.bitmap.hashCode().toString(16)

        // 재사용 객체들 획득
        val canvas = bitmapPool.getReusableCanvas()
        val paint = bitmapPool.getReusablePaint()
        val matrix = bitmapPool.getReusableMatrix()

        try {
            // Canvas를 공유 비트맵에 바인딩
            canvas.setBitmap(sharedBitmap.bitmap)

            // 🚀 클리어 제거: 새 프레임으로 완전히 덮어씌우므로 불필요
            // canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)

            // 원본 비트맵 디코딩 (이것만 새로 생성)
            val sourceBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (sourceBitmap == null) {
                Log.w(TAG, "⚠️ Source bitmap decode failed for @$bitmapHash")
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

            // 🎯 핵심: 한 번의 drawBitmap으로 모든 변환 완료 (이전 내용 완전 덮어씌움)
            canvas.drawBitmap(sourceBitmap, matrix, paint)

            // 원본 즉시 해제 (GC 압박 최소화)
            sourceBitmap.recycle()

            val frameNum = processedFrames.incrementAndGet()
            Log.d(TAG, "✅ Zero-Copy 처리 완료: @$bitmapHash, frame=$frameNum")

            return sharedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Zero-Copy 처리 실패 @$bitmapHash: ${e.message}", e)
            sharedBitmap.release()
            return null
        } finally {
            // 재사용 객체들 반환
            bitmapPool.returnReusableCanvas(canvas)
            bitmapPool.returnReusablePaint(paint)
            bitmapPool.returnReusableMatrix(matrix)
        }
    }

    fun getStatus(): String = "HighSpeedProcessor: Processed=${processedFrames.get()}"

    fun cleanup() {
        processedFrames.set(0)
        Log.d(TAG, "🗑️ High-Speed 프로세서 정리 완료")
    }
}
package com.example.myapplication.utils

import android.graphics.*
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 🏷️ 비트맵 사용 태그 시스템
 */
data class BitmapTag(
    val id: String,                    // 고유 ID (UUID 또는 nanoTime)
    val owner: String,                 // 소유자 (UI, Detection, Logger 등)
    val createTime: Long = System.currentTimeMillis(),
    val purpose: BitmapPurpose         // 사용 목적
) {
    override fun equals(other: Any?): Boolean {
        return other is BitmapTag && other.id == this.id
    }

    override fun hashCode(): Int = id.hashCode()
}

enum class BitmapPurpose {
    UI_DISPLAY,      // UI 표시용
    DETECTION,       // 딥러닝 추론용
    LOGGING,         // 로깅/저장용
    VIDEO_ENCODING,  // 비디오 인코딩용
    TEMPORARY        // 임시 작업용
}

/**
 * 🎯 태그된 비트맵 래퍼
 */
class TaggedBitmap private constructor(
    val bitmap: Bitmap,
    private val pool: TaggedBitmapPool,
    private val poolIndex: Int,
    var currentTag: BitmapTag
) {
    private val TAG = "TaggedBitmap"
    private val bitmapHash = bitmap.hashCode().toString(16)
    private val isReleased = AtomicBoolean(false)
    private var lastAccessTime = AtomicLong(System.currentTimeMillis())

    companion object {
        internal fun create(
            bitmap: Bitmap,
            pool: TaggedBitmapPool,
            index: Int,
            tag: BitmapTag
        ): TaggedBitmap {
            return TaggedBitmap(bitmap, pool, index, tag)
        }
    }

    /**
     * 🔄 태그 소유권 이전 (안전한 핸드오버)
     */
    fun transferOwnership(newTag: BitmapTag): Boolean {
        if (isReleased.get() || bitmap.isRecycled) {
            Log.w(TAG, "⚠️ 해제된 비트맵 소유권 이전 시도: @$bitmapHash")
            return false
        }

        val oldTag = currentTag
        currentTag = newTag
        lastAccessTime.set(System.currentTimeMillis())

        // 풀에 소유권 변경 알림
        pool.notifyOwnershipTransfer(poolIndex, oldTag, newTag)

        Log.d(TAG, "🔄 소유권 이전: @$bitmapHash, ${oldTag.owner} → ${newTag.owner}")
        return true
    }

    /**
     * 🔒 현재 태그로 비트맵 사용 (접근 시간 갱신)
     */
    fun useWithCurrentTag(): Bitmap? {
        if (isReleased.get() || bitmap.isRecycled) {
            Log.w(TAG, "⚠️ 사용 불가능한 비트맵 접근: @$bitmapHash")
            return null
        }

        lastAccessTime.set(System.currentTimeMillis())
        pool.recordBitmapUsage(poolIndex, currentTag)
        return bitmap
    }

    /**
     * 🏷️ 특정 태그로만 접근 허용 (태그 검증)
     */
    fun useWithTag(requiredTag: BitmapTag): Bitmap? {
        if (currentTag != requiredTag) {
            Log.w(TAG, "⚠️ 태그 불일치 접근 차단: @$bitmapHash, 요구=${requiredTag.owner}, 현재=${currentTag.owner}")
            return null
        }

        return useWithCurrentTag()
    }

    /**
     * 📉 태그 기반 해제 (올바른 소유자만 해제 가능)
     */
    fun releaseWithTag(releaseTag: BitmapTag): Boolean {
        if (currentTag != releaseTag) {
            Log.w(TAG, "⚠️ 잘못된 태그로 해제 시도 차단: @$bitmapHash, 해제태그=${releaseTag.owner}, 현재태그=${currentTag.owner}")
            return false
        }

        if (!isReleased.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ 이미 해제된 비트맵: @$bitmapHash")
            return false
        }

        pool.releaseBitmap(poolIndex, releaseTag)
        Log.d(TAG, "📉 태그 기반 해제 성공: @$bitmapHash, 태그=${releaseTag.owner}")
        return true
    }

    fun getCurrentTag(): BitmapTag = currentTag
    fun getLastAccessTime(): Long = lastAccessTime.get()
    fun isValidForUse(): Boolean = !isReleased.get() && !bitmap.isRecycled
}

/**
 * 🎯 태그 기반 비트맵 풀 관리자
 */
class TaggedBitmapPool(
    private val poolSize: Int = 20,
    private val width: Int = 840,
    private val height: Int = 840,
    private val autoCleanupIntervalMs: Long = 3000L,    // 3초마다 자동 정리
    private val staleTimeoutMs: Long = 5000L            // 5초 이상 미사용시 강제 해제
) {
    private val TAG = "TaggedBitmapPool"

    // 🏗️ 풀 기본 구조
    private val bitmapPool = Array<Bitmap?>(poolSize) { null }
    private val taggedBitmaps = Array<TaggedBitmap?>(poolSize) { null }
    private val usageState = Array(poolSize) { false }
    private val poolLock = ReentrantReadWriteLock()

    // 🏷️ 태그 관리
    private val activeTagMap = ConcurrentHashMap<String, TagUsageInfo>()  // tagId -> 사용 정보
    private val poolTagHistory = Array<MutableList<TagUsageRecord>>(poolSize) { mutableListOf() }

    // ⏰ 시간 기반 관리
    private var lastCleanupTime = AtomicLong(System.currentTimeMillis())
    private val cleanupThread = Thread(::performPeriodicCleanup).apply {
        isDaemon = true
        name = "TaggedBitmapPool-Cleanup"
        start()
    }

    // 📊 통계
    private val totalAcquired = AtomicInteger(0)
    private val totalReleased = AtomicInteger(0)
    private val forceReleasedCount = AtomicInteger(0)
    private val tagMismatchCount = AtomicInteger(0)

    data class TagUsageInfo(
        val tag: BitmapTag,
        val poolIndex: Int,
        val startTime: Long,
        var lastUsedTime: Long,
        var usageCount: Int = 0
    )

    data class TagUsageRecord(
        val tag: BitmapTag,
        val startTime: Long,
        val endTime: Long?,
        val usageCount: Int,
        val endReason: String  // "NORMAL_RELEASE", "FORCE_CLEANUP", "TAG_MISMATCH"
    )

    init {
        initializePool()
    }

    private fun initializePool() {
        poolLock.write {
            Log.d(TAG, "🏷️ 태그 기반 비트맵 풀 초기화 시작 (크기: $poolSize)")

            var successCount = 0
            for (index in 0 until poolSize) {
                try {
                    bitmapPool[index] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    usageState[index] = false
                    successCount++
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "⚠️ 초기화 중 OOM: ${index + 1}/${poolSize}")
                    bitmapPool[index] = null
                    System.gc()
                    break
                }
            }

            Log.d(TAG, "✅ 태그 기반 풀 초기화 완료: 성공=${successCount}/${poolSize}")
        }
    }

    /**
     * 🎯 태그를 지정하여 비트맵 획득
     */
    fun acquireWithTag(tag: BitmapTag): TaggedBitmap? {
        return poolLock.write {
            val availableIndex = findAvailableSlot()
            if (availableIndex == -1) {
                Log.w(TAG, "⚠️ 사용 가능한 슬롯 없음 - 자동 정리 시도")
                performEmergencyCleanup()
                val retryIndex = findAvailableSlot()
                if (retryIndex == -1) {
                    Log.e(TAG, "❌ 긴급 정리 후에도 슬롯 없음")
                    return null
                }
                return createTaggedBitmap(retryIndex, tag)
            }

            createTaggedBitmap(availableIndex, tag)
        }
    }

    private fun findAvailableSlot(): Int {
        for (index in 0 until poolSize) {
            val bitmap = bitmapPool[index]
            if (bitmap != null && !bitmap.isRecycled && !usageState[index]) {
                return index
            }
        }
        return -1
    }

    private fun createTaggedBitmap(index: Int, tag: BitmapTag): TaggedBitmap {
        val bitmap = bitmapPool[index]!!

        // 슬롯 점유
        usageState[index] = true

        // TaggedBitmap 생성
        val taggedBitmap = TaggedBitmap.create(bitmap, this, index, tag)
        taggedBitmaps[index] = taggedBitmap

        // 태그 정보 등록
        val usageInfo = TagUsageInfo(
            tag = tag,
            poolIndex = index,
            startTime = System.currentTimeMillis(),
            lastUsedTime = System.currentTimeMillis()
        )
        activeTagMap[tag.id] = usageInfo

        totalAcquired.incrementAndGet()
        Log.d(TAG, "🎯 태그 비트맵 획득: index=$index, 태그=${tag.owner}, ID=${tag.id}")

        return taggedBitmap
    }

    /**
     * 🔄 소유권 이전 알림
     */
    internal fun notifyOwnershipTransfer(poolIndex: Int, oldTag: BitmapTag, newTag: BitmapTag) {
        // 기존 태그 기록 종료
        activeTagMap.remove(oldTag.id)?.let { oldUsage ->
            poolTagHistory[poolIndex].add(
                TagUsageRecord(
                    tag = oldTag,
                    startTime = oldUsage.startTime,
                    endTime = System.currentTimeMillis(),
                    usageCount = oldUsage.usageCount,
                    endReason = "OWNERSHIP_TRANSFER"
                )
            )
        }

        // 새 태그 등록
        val newUsageInfo = TagUsageInfo(
            tag = newTag,
            poolIndex = poolIndex,
            startTime = System.currentTimeMillis(),
            lastUsedTime = System.currentTimeMillis()
        )
        activeTagMap[newTag.id] = newUsageInfo

        Log.d(TAG, "🔄 소유권 이전 기록: index=$poolIndex, ${oldTag.owner} → ${newTag.owner}")
    }

    /**
     * 📝 비트맵 사용 기록
     */
    internal fun recordBitmapUsage(poolIndex: Int, tag: BitmapTag) {
        activeTagMap[tag.id]?.let { usageInfo ->
            usageInfo.lastUsedTime = System.currentTimeMillis()
            usageInfo.usageCount++
        }
    }

    /**
     * 📉 비트맵 해제
     */
    internal fun releaseBitmap(poolIndex: Int, releaseTag: BitmapTag) {
        poolLock.write {
            if (poolIndex !in 0 until poolSize) return@write

            // 태그 기록 종료
            activeTagMap.remove(releaseTag.id)?.let { usageInfo ->
                poolTagHistory[poolIndex].add(
                    TagUsageRecord(
                        tag = releaseTag,
                        startTime = usageInfo.startTime,
                        endTime = System.currentTimeMillis(),
                        usageCount = usageInfo.usageCount,
                        endReason = "NORMAL_RELEASE"
                    )
                )
            }

            // 슬롯 해제
            usageState[poolIndex] = false
            taggedBitmaps[poolIndex] = null

            // 비트맵 초기화
            val bitmap = bitmapPool[poolIndex]
            if (bitmap != null && !bitmap.isRecycled) {
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 비트맵 클리어 실패: ${e.message}")
                }
            }

            totalReleased.incrementAndGet()
            Log.d(TAG, "📉 비트맵 해제 완료: index=$poolIndex, 태그=${releaseTag.owner}")
        }
    }

    /**
     * 🧹 주기적 자동 정리 (백그라운드 스레드)
     */
    private fun performPeriodicCleanup() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(autoCleanupIntervalMs)

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastCleanupTime.get() >= autoCleanupIntervalMs) {
                    cleanupStaleEntries(currentTime)
                    lastCleanupTime.set(currentTime)
                }

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "❌ 자동 정리 오류: ${e.message}", e)
            }
        }
    }

    /**
     * 🗑️ 만료된 태그 정리
     */
    private fun cleanupStaleEntries(currentTime: Long) {
        val staleEntries = mutableListOf<Pair<Int, TagUsageInfo>>()

        // 만료된 태그 찾기
        for ((tagId, usageInfo) in activeTagMap) {
            if (currentTime - usageInfo.lastUsedTime > staleTimeoutMs) {
                staleEntries.add(usageInfo.poolIndex to usageInfo)
                Log.w(TAG, "🗑️ 만료된 태그 발견: index=${usageInfo.poolIndex}, 태그=${usageInfo.tag.owner}, 미사용시간=${currentTime - usageInfo.lastUsedTime}ms")
            }
        }

        // 강제 해제
        if (staleEntries.isNotEmpty()) {
            poolLock.write {
                for ((poolIndex, usageInfo) in staleEntries) {
                    try {
                        // 태그 기록 종료
                        activeTagMap.remove(usageInfo.tag.id)
                        poolTagHistory[poolIndex].add(
                            TagUsageRecord(
                                tag = usageInfo.tag,
                                startTime = usageInfo.startTime,
                                endTime = currentTime,
                                usageCount = usageInfo.usageCount,
                                endReason = "FORCE_CLEANUP_STALE"
                            )
                        )

                        // 슬롯 해제
                        usageState[poolIndex] = false
                        taggedBitmaps[poolIndex] = null

                        // 비트맵 초기화
                        val bitmap = bitmapPool[poolIndex]
                        if (bitmap != null && !bitmap.isRecycled) {
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        }

                        forceReleasedCount.incrementAndGet()
                        Log.w(TAG, "🗑️ 만료 태그 강제 해제: index=$poolIndex, 태그=${usageInfo.tag.owner}")

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 강제 해제 오류: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * 🚨 긴급 정리 (사용 가능한 슬롯이 없을 때)
     */
    private fun performEmergencyCleanup() {
        val currentTime = System.currentTimeMillis()

        // 1. 매우 오래된 항목들 강제 해제 (임계값 단축)
        cleanupStaleEntries(currentTime)

        // 2. 그래도 부족하면 가장 오래된 항목 해제
        if (findAvailableSlot() == -1) {
            val oldestEntry = activeTagMap.values.minByOrNull { it.lastUsedTime }
            oldestEntry?.let { usage ->
                Log.w(TAG, "🚨 긴급 상황 - 가장 오래된 태그 강제 해제: 태그=${usage.tag.owner}")
                releaseBitmap(usage.poolIndex, usage.tag)
            }
        }
    }

    /**
     * 📊 풀 상태 조회
     */
    fun getPoolStatus(): String {
        return poolLock.read {
            val available = usageState.count { !it }
            val activeTags = activeTagMap.size

            buildString {
                appendLine("=== 태그 기반 비트맵 풀 상태 ===")
                appendLine("사용 가능: $available/$poolSize")
                appendLine("활성 태그: $activeTags")
                appendLine("총 획득: ${totalAcquired.get()}")
                appendLine("총 해제: ${totalReleased.get()}")
                appendLine("강제 해제: ${forceReleasedCount.get()}")
                appendLine("태그 불일치: ${tagMismatchCount.get()}")

                if (activeTags > 0) {
                    appendLine()
                    appendLine("활성 태그 목록:")
                    val currentTime = System.currentTimeMillis()
                    for ((tagId, usage) in activeTagMap) {
                        val ageMs = currentTime - usage.lastUsedTime
                        appendLine("  - ${usage.tag.owner} (${usage.tag.purpose}): 미사용 ${ageMs}ms, 사용횟수 ${usage.usageCount}")
                    }
                }
            }
        }
    }

    /**
     * 🧹 풀 완전 정리
     */
    fun cleanup() {
        cleanupThread.interrupt()

        poolLock.write {
            // 모든 활성 태그 기록 종료
            val currentTime = System.currentTimeMillis()
            for ((tagId, usageInfo) in activeTagMap) {
                poolTagHistory[usageInfo.poolIndex].add(
                    TagUsageRecord(
                        tag = usageInfo.tag,
                        startTime = usageInfo.startTime,
                        endTime = currentTime,
                        usageCount = usageInfo.usageCount,
                        endReason = "POOL_CLEANUP"
                    )
                )
            }

            activeTagMap.clear()

            // 비트맵 해제
            for (index in 0 until poolSize) {
                bitmapPool[index]?.takeIf { !it.isRecycled }?.recycle()
                bitmapPool[index] = null
                taggedBitmaps[index] = null
                usageState[index] = false
            }

            Log.d(TAG, "🧹 태그 기반 풀 완전 정리 완료")
        }
    }
}

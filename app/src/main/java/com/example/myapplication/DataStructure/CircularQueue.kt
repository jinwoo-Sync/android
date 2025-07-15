package com.example.myapplication.DataStructure
import java.util.ArrayDeque
import kotlin.math.abs

/** 고정 크기 순환 큐 - 스레드 안전하고 메모리 효율적 */
class CircularQueue<T>(private val capacity: Int) : Iterable<T> {
    private val deque = ArrayDeque<T>(capacity)

    fun push(item: T) {
        synchronized(deque) {
            if (deque.size >= capacity) {
                deque.removeFirst()
            }
            deque.addLast(item)
        }
    }

    fun isNotEmpty(): Boolean = synchronized(deque) { deque.isNotEmpty() }
    fun poll(): T? = synchronized(deque) { if (deque.isEmpty()) null else deque.removeFirst() }
    fun isEmpty(): Boolean = synchronized(deque) { deque.isEmpty() }
    fun snapshot(): List<T> = synchronized(deque) { deque.toList() }
    fun clear() = synchronized(deque) { deque.clear() }
    fun size(): Int = synchronized(deque) { deque.size }
    fun removeLast(): T? = synchronized(deque) { if (deque.isEmpty()) null else deque.removeLast() }

    override fun iterator(): Iterator<T> = synchronized(deque) { deque.toList().iterator() }

    // 큐에서 조건에 맞는 항목 찾기 (동기화 수행을 위한 참조 접근)
    fun findClosest(predicate: (T) -> Long, targetTime: Long, windowMs: Long): T? {
        return synchronized(deque) {
            val windowStart = targetTime - windowMs
            val windowEnd = targetTime + windowMs

            deque.minByOrNull { entry ->
                val entryTime = predicate(entry)
                if (entryTime in windowStart..windowEnd) {
                    abs(entryTime - targetTime)
                } else {
                    Long.MAX_VALUE
                }
            }?.takeIf { entry ->
                val entryTime = predicate(entry)
                entryTime in windowStart..windowEnd
            }
        }
    }
}
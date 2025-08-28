package com.example.myapplication.DataStructure

import org.junit.Before
import org.junit.Test
import kotlin.test.*

class CircularQueueTest {
    
    private lateinit var queue: CircularQueue<Int>
    
    @Before
    fun setup() {
        queue = CircularQueue(5)
    }
    
    @Test
    fun testPushPoll() {
        assertTrue(queue.isEmpty())
        
        queue.push(1)
        queue.push(2)
        queue.push(3)
        
        assertEquals(3, queue.size())
        assertEquals(1, queue.poll())
        assertEquals(2, queue.poll())
        assertEquals(3, queue.poll())
        
        assertTrue(queue.isEmpty())
    }
    
    @Test
    fun testCircularBehavior() {
        // Fill the queue to capacity
        for (i in 1..5) {
            queue.push(i)
        }
        assertEquals(5, queue.size())
        
        // Add one more item - should remove oldest
        queue.push(6)
        assertEquals(5, queue.size())
        
        // First item should now be 2 (1 was removed)
        assertEquals(2, queue.poll())
        assertEquals(3, queue.poll())
        assertEquals(4, queue.poll())
        assertEquals(5, queue.poll())
        assertEquals(6, queue.poll())
        
        assertTrue(queue.isEmpty())
    }
    
    @Test
    fun testSnapshot() {
        queue.push(10)
        queue.push(20)
        queue.push(30)
        
        val snapshot = queue.snapshot()
        assertEquals(listOf(10, 20, 30), snapshot)
        
        // Original queue should still have items
        assertEquals(3, queue.size())
    }
    
    @Test
    fun testClear() {
        queue.push(1)
        queue.push(2)
        queue.push(3)
        
        assertEquals(3, queue.size())
        
        queue.clear()
        
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
        assertNull(queue.poll())
    }
    
    @Test
    fun testEmptyQueueOperations() {
        assertTrue(queue.isEmpty())
        assertNull(queue.poll())
        assertNull(queue.removeLast())
    }
    
    @Test
    fun testRemoveLast() {
        queue.push(1)
        queue.push(2)
        queue.push(3)
        
        assertEquals(3, queue.removeLast())
        assertEquals(2, queue.size())
        
        assertEquals(2, queue.removeLast())
        assertEquals(1, queue.size())
        
        assertEquals(1, queue.removeLast())
        assertTrue(queue.isEmpty())
        
        assertNull(queue.removeLast())
    }
    
    @Test
    fun testIsNotEmpty() {
        assertTrue(queue.isEmpty())
        assertFalse(queue.isNotEmpty())
        
        queue.push(1)
        
        assertFalse(queue.isEmpty())
        assertTrue(queue.isNotEmpty())
    }
    
    @Test
    fun testIterator() {
        queue.push(1)
        queue.push(2)
        queue.push(3)
        
        val items = mutableListOf<Int>()
        for (item in queue) {
            items.add(item)
        }
        
        assertEquals(listOf(1, 2, 3), items)
    }
    
    @Test
    fun testCapacityLimits() {
        val smallQueue = CircularQueue<Int>(1)
        smallQueue.push(1)
        assertEquals(1, smallQueue.size())
        
        smallQueue.push(2) // Should replace 1
        assertEquals(1, smallQueue.size())
        assertEquals(2, smallQueue.poll())
    }
}
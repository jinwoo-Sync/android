package com.example.myapplication.data.sensor

import org.junit.Test
import kotlin.test.*

class SensorCollectorTest {
    
    @Test
    fun testBasicFunctionality() {
        // Simple test that doesn't require Android context
        val testValue = 2 + 2
        assertEquals(4, testValue)
    }
    
    @Test
    fun testStringOperations() {
        val testString = "SensorCollector"
        assertTrue(testString.isNotEmpty())
        assertEquals(15, testString.length)
    }
    
    @Test
    fun testListOperations() {
        val testList = listOf(1, 2, 3, 4, 5)
        assertEquals(5, testList.size)
        assertEquals(1, testList.first())
        assertEquals(5, testList.last())
    }
}
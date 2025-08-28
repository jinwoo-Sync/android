package com.example.myapplication

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.myapplication.data.sensor.SensorCollector
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.DataStructure.CircularQueue
import com.example.myapplication.utils.BitmapPoolManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@LargeTest
@RunWith(AndroidJUnit4::class)
class PerformanceInstrumentedTest {
    
    @get:Rule
    val benchmarkRule = BenchmarkRule()
    
    private lateinit var context: Context
    private lateinit var sensorCollector: SensorCollector
    private lateinit var dataSynchronizer: DataSynchronizer
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sensorCollector = SensorCollector.getInstance(context)
        dataSynchronizer = DataSynchronizer()
    }
    
    @Test
    fun benchmarkSensorDataCollection() {
        benchmarkRule.measureRepeated {
            sensorCollector.startSensorCollection()
            Thread.sleep(100)
            val data = sensorCollector.getSensorData()
            sensorCollector.stopSensorCollection()
            
            assertTrue(data.timestamp > 0)
        }
    }
    
    @Test
    fun benchmarkDataSynchronization() {
        benchmarkRule.measureRepeated {
            for (i in 1..100) {
                dataSynchronizer.addSensorTimestamp(System.currentTimeMillis() + i)
                dataSynchronizer.addGpsTimestamp(System.currentTimeMillis() + i * 2)
            }
            
            val syncedTimestamp = dataSynchronizer.getSynchronizedTimestamp()
            dataSynchronizer.reset()
            
            assertTrue(syncedTimestamp > 0)
        }
    }
    
    @Test
    fun benchmarkCircularQueue() {
        val queue = CircularQueue<Int>(1000)
        
        benchmarkRule.measureRepeated {
            for (i in 1..1000) {
                queue.enqueue(i)
            }
            
            for (i in 1..500) {
                queue.dequeue()
            }
            
            for (i in 1..500) {
                queue.enqueue(i)
            }
            
            queue.clear()
        }
    }
    
    @Test
    fun benchmarkBitmapPoolManager() {
        val poolManager = BitmapPoolManager.getInstance(context)
        
        benchmarkRule.measureRepeated {
            val bitmaps = mutableListOf<android.graphics.Bitmap>()
            
            for (i in 1..10) {
                val bitmap = poolManager.getBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888, "test")
                bitmaps.add(bitmap)
            }
            
            bitmaps.forEach { bitmap ->
                poolManager.recycleBitmap(bitmap, "test")
            }
        }
    }
    
    @Test
    fun testMemoryLeaks() {
        val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        
        repeat(100) {
            sensorCollector.startSensorCollection()
            Thread.sleep(10)
            sensorCollector.stopSensorCollection()
        }
        
        System.gc()
        Thread.sleep(100)
        
        val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryIncrease = finalMemory - initialMemory
        
        assertTrue(memoryIncrease < 10 * 1024 * 1024, "Memory leak detected: $memoryIncrease bytes")
    }
    
    @Test
    fun testConcurrentSensorAccess() {
        val threads = mutableListOf<Thread>()
        
        repeat(10) { threadIndex ->
            threads.add(Thread {
                repeat(100) {
                    val data = sensorCollector.getSensorData()
                    assertTrue(data.timestamp > 0)
                }
            })
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
    
    @Test
    fun testLongRunningOperation() {
        sensorCollector.startSensorCollection()
        sensorCollector.startLocationUpdates()
        
        val startTime = System.currentTimeMillis()
        Thread.sleep(5000)
        
        val sensorData = sensorCollector.getSensorData()
        val location = sensorCollector.getLastLocation()
        
        sensorCollector.stopSensorCollection()
        sensorCollector.stopLocationUpdates()
        
        val duration = System.currentTimeMillis() - startTime
        assertTrue(duration >= 5000)
        assertTrue(sensorData.timestamp > 0)
    }
}
package com.example.myapplication.ui.home

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.example.myapplication.R
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class HomeFragmentTest {
    
    @Before
    fun setup() {
    }
    
    @Test
    fun testFragmentLaunch() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.text_home))
            .check(matches(isDisplayed()))
    }
    
    @Test
    fun testStartRecordingButton() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.startRecordingButton))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
            .perform(click())
        
        onView(withId(R.id.stopRecordingButton))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }
    
    @Test
    fun testStopRecordingButton() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.startRecordingButton))
            .perform(click())
        
        onView(withId(R.id.stopRecordingButton))
            .perform(click())
        
        onView(withId(R.id.startRecordingButton))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }
    
    @Test
    fun testSensorDataDisplay() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.sensorDataTextView))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.startRecordingButton))
            .perform(click())
        
        Thread.sleep(2000)
        
        onView(withId(R.id.sensorDataTextView))
            .check(matches(not(withText(""))))
    }
    
    @Test
    fun testGpsStatusIndicator() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.gpsStatusIndicator))
            .check(matches(isDisplayed()))
    }
    
    @Test
    fun testPerformanceMetrics() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.performanceMetricsView))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.startRecordingButton))
            .perform(click())
        
        Thread.sleep(3000)
        
        onView(withId(R.id.fpsTextView))
            .check(matches(isDisplayed()))
            .check(matches(not(withText("FPS: 0"))))
    }
    
    @Test
    fun testMemoryUsageDisplay() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.memoryUsageTextView))
            .check(matches(isDisplayed()))
        
        val initialMemory = onView(withId(R.id.memoryUsageTextView))
        
        onView(withId(R.id.startRecordingButton))
            .perform(click())
        
        Thread.sleep(2000)
        
        onView(withId(R.id.memoryUsageTextView))
            .check(matches(not(withText(""))))
    }
    
    @Test
    fun testCameraPreview() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.cameraPreview))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.startRecordingButton))
            .perform(click())
        
        Thread.sleep(1000)
        
        onView(withId(R.id.cameraPreview))
            .check(matches(isDisplayed()))
    }
    
    @Test
    fun testErrorHandling() {
        val scenario = launchFragmentInContainer<HomeFragment>()
        
        onView(withId(R.id.startRecordingButton))
            .perform(click())
        
        onView(withId(R.id.startRecordingButton))
            .perform(click())
        
        onView(withId(R.id.errorMessageTextView))
            .check(matches(withText("Recording already in progress")))
    }
}
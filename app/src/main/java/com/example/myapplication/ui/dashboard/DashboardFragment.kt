package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.MainActivity
import com.example.myapplication.databinding.FragmentDashboardBinding
import com.example.myapplication.data.sensor.KalmanFilteredData
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    // UI components
    private lateinit var kalmanFilterSwitch: Switch
    private lateinit var kalmanFilterPanel: LinearLayout
    private lateinit var kalmanRawDataText: TextView
    private lateinit var kalmanFilteredDataText: TextView
    private lateinit var kalmanStatsText: TextView
    
    private lateinit var gpsStatusText: TextView
    private lateinit var gpsCoordinatesText: TextView
    private lateinit var gpsAccuracyText: TextView
    private lateinit var satelliteCountText: TextView
    
    private lateinit var buttonStartGps: Button
    private lateinit var buttonStopGps: Button
    private lateinit var buttonClearTracks: Button
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var isGpsActive = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dashboardViewModel = ViewModelProvider(this).get(DashboardViewModel::class.java)
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

        initializeViews()
        setupKalmanFilter()
        setupGpsControls()
        setupKalmanDataCallback()

        return root
    }

    private fun initializeViews() {
        // Kalman filter components
        kalmanFilterSwitch = binding.kalmanFilterSwitch
        kalmanFilterPanel = binding.kalmanFilterPanel
        kalmanRawDataText = binding.kalmanRawDataText
        kalmanFilteredDataText = binding.kalmanFilteredDataText
        kalmanStatsText = binding.kalmanStatsText
        
        // GPS data display components
        gpsStatusText = binding.gpsStatusText
        gpsCoordinatesText = binding.gpsCoordinatesText
        gpsAccuracyText = binding.gpsAccuracyText
        satelliteCountText = binding.satelliteCountText
        
        // Control buttons
        buttonStartGps = binding.buttonStartGps
        buttonStopGps = binding.buttonStopGps
        buttonClearTracks = binding.buttonClearTracks
        
        // Initial states
        buttonStopGps.isEnabled = false
    }

    private fun setupKalmanFilter() {
        kalmanFilterSwitch.setOnCheckedChangeListener { _, isChecked ->
            val mainActivity = activity as? MainActivity
            mainActivity?.let { activity ->
                activity.sensorCollector.setKalmanFilterEnabled(isChecked)
                kalmanFilterPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
                
                if (isChecked) {
                    Toast.makeText(context, "Kalman 필터 활성화됨", Toast.LENGTH_SHORT).show()
                    kalmanStatsText.text = "Stats: 필터 활성화됨"
                    kalmanStatsText.setTextColor(resources.getColor(android.R.color.holo_green_dark))
                } else {
                    Toast.makeText(context, "Kalman 필터 비활성화됨", Toast.LENGTH_SHORT).show()
                    kalmanStatsText.text = "Stats: 필터 비활성화됨"
                    kalmanStatsText.setTextColor(resources.getColor(android.R.color.darker_gray))
                    kalmanRawDataText.text = "Raw GPS: 대기 중..."
                    kalmanFilteredDataText.text = "Filtered GPS: 대기 중..."
                }
            }
        }
    }

    private fun setupGpsControls() {
        buttonStartGps.setOnClickListener {
            startGpsTracking()
        }
        
        buttonStopGps.setOnClickListener {
            stopGpsTracking()
        }
        
        buttonClearTracks.setOnClickListener {
            clearGpsTracks()
        }
    }

    private fun setupKalmanDataCallback() {
        val mainActivity = activity as? MainActivity
        mainActivity?.let { activity ->
            activity.sensorCollector.setKalmanDataCallback { kalmanData ->
                activity.runOnUiThread {
                    updateKalmanDisplay(kalmanData)
                    updateGpsDisplay(kalmanData)
                }
            }
        }
    }

    private fun startGpsTracking() {
        val mainActivity = activity as? MainActivity
        mainActivity?.let { activity ->
            // Start sensor streaming with GPS focus
            activity.sensorCollector.startSensorStreaming(
                gpsCallback = { gpsData ->
                    activity.runOnUiThread {
                        updateGpsStatusDisplay(gpsData.message)
                    }
                },
                imuCallback = null,
                gnssCallback = null,
                detectionCallback = null
            )
            
            isGpsActive = true
            buttonStartGps.isEnabled = false
            buttonStopGps.isEnabled = true
            gpsStatusText.text = "GPS Status: 활성화됨 (${dateFormat.format(Date())})"
            Toast.makeText(context, "GPS 추적 시작됨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopGpsTracking() {
        val mainActivity = activity as? MainActivity
        mainActivity?.let { activity ->
            activity.sensorCollector.stopSensorStreaming()
            
            isGpsActive = false
            buttonStartGps.isEnabled = true
            buttonStopGps.isEnabled = false
            gpsStatusText.text = "GPS Status: 정지됨"
            gpsCoordinatesText.text = "Coordinates: 대기 중..."
            gpsAccuracyText.text = "Accuracy: 대기 중..."
            satelliteCountText.text = "Satellites: 대기 중..."
            
            // Reset Kalman filter display
            kalmanRawDataText.text = "Raw GPS: 대기 중..."
            kalmanFilteredDataText.text = "Filtered GPS: 대기 중..."
            
            Toast.makeText(context, "GPS 추적 정지됨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearGpsTracks() {
        // Reset all displays
        gpsCoordinatesText.text = "Coordinates: 대기 중..."
        gpsAccuracyText.text = "Accuracy: 대기 중..."
        satelliteCountText.text = "Satellites: 대기 중..."
        kalmanRawDataText.text = "Raw GPS: 대기 중..."
        kalmanFilteredDataText.text = "Filtered GPS: 대기 중..."
        
        Toast.makeText(context, "GPS 트랙 초기화됨", Toast.LENGTH_SHORT).show()
    }

    private fun updateKalmanDisplay(data: KalmanFilteredData) {
        if (kalmanFilterSwitch.isChecked) {
            kalmanRawDataText.text = "Raw GPS: ${String.format("%.6f", data.rawLatitude)}, ${String.format("%.6f", data.rawLongitude)} (±${String.format("%.1f", data.rawAccuracy)}m)"
            
            if (data.filterEnabled) {
                kalmanFilteredDataText.text = "Filtered GPS: ${String.format("%.6f", data.filteredLatitude)}, ${String.format("%.6f", data.filteredLongitude)} (±${String.format("%.1f", data.filteredAccuracy)}m)"
                
                val mainActivity = activity as? MainActivity
                val stats = mainActivity?.sensorCollector?.getKalmanFilterStats()
                val covTrace = stats?.get("covarianceTrace") as? Double ?: 0.0
                val improvement = (1 - data.filteredAccuracy/data.rawAccuracy) * 100
                kalmanStatsText.text = "Stats: Covariance=${String.format("%.2f", covTrace)}, Improvement=${String.format("%.1f%%", improvement)}"
                kalmanStatsText.setTextColor(resources.getColor(android.R.color.holo_green_dark))
            } else {
                kalmanFilteredDataText.text = "Filtered GPS: 필터 비활성화"
                kalmanStatsText.text = "Stats: 필터 비활성화됨"
                kalmanStatsText.setTextColor(resources.getColor(android.R.color.darker_gray))
            }
        }
    }

    private fun updateGpsDisplay(data: KalmanFilteredData) {
        if (isGpsActive) {
            gpsCoordinatesText.text = "Coordinates: ${String.format("%.6f", data.rawLatitude)}, ${String.format("%.6f", data.rawLongitude)}"
            gpsAccuracyText.text = "Accuracy: ±${String.format("%.1f", data.rawAccuracy)}m"
            
            // Update timestamp
            gpsStatusText.text = "GPS Status: 활성화됨 (${dateFormat.format(Date(data.timestamp))})"
        }
    }

    private fun updateGpsStatusDisplay(statusMessage: String) {
        // Parse status message for additional information
        if (statusMessage.contains("satellites", ignoreCase = true)) {
            satelliteCountText.text = "Satellites: $statusMessage"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
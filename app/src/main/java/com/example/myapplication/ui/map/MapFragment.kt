package com.example.myapplication.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.MainActivity
import com.example.myapplication.databinding.FragmentMapBinding
import com.example.myapplication.ui.RtkSettingsDialogFragment
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MapFragment : Fragment(), OnMapReadyCallback {
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MapViewModel by viewModels {
        MapViewModelFactory(
            (requireActivity() as MainActivity).sensorCollector
        )
    }
    
    private lateinit var googleMap: GoogleMap
    
    // Map markers for different position sources
    private var rawGpsMarker: Marker? = null
    private var kalmanFilteredMarker: Marker? = null
    private var rtkMarker: Marker? = null
    
    // Polylines for tracking paths
    private var rawGpsPolyline: Polyline? = null
    private var kalmanFilteredPolyline: Polyline? = null
    private var rtkPolyline: Polyline? = null
    
    // Path points
    private val rawGpsPoints = mutableListOf<LatLng>()
    private val kalmanFilteredPoints = mutableListOf<LatLng>()
    private val rtkPoints = mutableListOf<LatLng>()
    
    // Tracking mode
    private var currentTrackingMode = TrackingMode.RAW_GPS
    
    // Log file for position data
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    enum class TrackingMode {
        RAW_GPS,
        MAD_KALMAN_FILTER,
        RTK_NTRIP
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        
        setupMapFragment()
        setupModeSelector()
        setupObservers()
        setupClickListeners()
        initializeLogFile()
        
        return binding.root
    }
    
    private fun setupMapFragment() {
        val mapFragment = childFragmentManager.findFragmentById(
            binding.mapContainer.id
        ) as? SupportMapFragment ?: SupportMapFragment.newInstance().also {
            childFragmentManager.beginTransaction()
                .replace(binding.mapContainer.id, it)
                .commit()
        }
        mapFragment.getMapAsync(this)
    }
    
    private fun setupModeSelector() {
        val modes = arrayOf(
            "Raw GPS/GNSS Data",
            "MAD Kalman Filter",
            "MAD Kalman + NTRIP/RTK"
        )
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modeSpinner.adapter = adapter
        
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                currentTrackingMode = when (position) {
                    0 -> TrackingMode.RAW_GPS
                    1 -> TrackingMode.MAD_KALMAN_FILTER
                    2 -> TrackingMode.RTK_NTRIP
                    else -> TrackingMode.RAW_GPS
                }
                updateVisibility()
                Log.d("MapFragment", "Tracking mode changed to: $currentTrackingMode")
            }
            
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
    
    private fun setupObservers() {
        // Raw GPS position observer
        viewModel.rawGpsPosition.observe(viewLifecycleOwner) { location ->
            location?.let {
                updateRawGpsPosition(it)
                if (currentTrackingMode == TrackingMode.RAW_GPS) {
                    updatePositionText(it, "Raw GPS/GNSS")
                    logPosition("RAW_GPS", it)
                }
            }
        }
        
        // Kalman filtered position observer
        viewModel.kalmanFilteredPosition.observe(viewLifecycleOwner) { location ->
            location?.let {
                updateKalmanFilteredPosition(it)
                if (currentTrackingMode == TrackingMode.MAD_KALMAN_FILTER) {
                    updatePositionText(it, "MAD Kalman Filter")
                    logPosition("MAD_KALMAN", it)
                }
            }
        }
        
        // RTK position observer
        viewModel.rtkPosition.observe(viewLifecycleOwner) { location ->
            location?.let {
                updateRtkPosition(it)
                if (currentTrackingMode == TrackingMode.RTK_NTRIP) {
                    updatePositionText(it, "RTK/NTRIP")
                    logPosition("RTK_NTRIP", it)
                }
            }
        }
        
        // Filter quality observer
        viewModel.filterQuality.observe(viewLifecycleOwner) { quality ->
            binding.qualityText.text = "Filter Quality: $quality"
        }
        
        // RTK status observer
        viewModel.rtkStatus.observe(viewLifecycleOwner) { status ->
            binding.rtkStatusText.text = "RTK Status: $status"
        }
    }
    
    private fun setupClickListeners() {
        binding.startTrackingButton.setOnClickListener {
            if (checkLocationPermissions()) {
                viewModel.startTracking()
                binding.startTrackingButton.isEnabled = false
                binding.stopTrackingButton.isEnabled = true
                Toast.makeText(context, "Tracking started", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.stopTrackingButton.setOnClickListener {
            viewModel.stopTracking()
            binding.startTrackingButton.isEnabled = true
            binding.stopTrackingButton.isEnabled = false
            Toast.makeText(context, "Tracking stopped", Toast.LENGTH_SHORT).show()
        }
        
        binding.clearPathsButton.setOnClickListener {
            clearAllPaths()
        }
        
        binding.centerMapButton.setOnClickListener {
            centerOnCurrentPosition()
        }
        
        binding.togglePathsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            updatePathVisibility(isChecked)
        }

        // RTK Settings button
        binding.fabRtkSettings.setOnClickListener {
            showRtkSettingsDialog()
        }
    }
    
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Set map settings
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isCompassEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = false
        
        // Check location permission and enable my location if granted
        if (checkLocationPermissions()) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                googleMap.isMyLocationEnabled = true
            }
        }
        
        // Set initial camera position (Seoul)
        val seoul = LatLng(37.5665, 126.9780)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(seoul, 15f))
    }
    
    private fun updateRawGpsPosition(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        
        // Update marker
        if (rawGpsMarker == null) {
            rawGpsMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Raw GPS")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
        } else {
            rawGpsMarker?.position = latLng
        }
        
        // Update path
        rawGpsPoints.add(latLng)
        if (rawGpsPolyline == null) {
            rawGpsPolyline = googleMap.addPolyline(
                PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
            )
        }
        rawGpsPolyline?.points = rawGpsPoints
    }
    
    private fun updateKalmanFilteredPosition(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        
        // Update marker
        if (kalmanFilteredMarker == null) {
            kalmanFilteredMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Kalman Filtered")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
        } else {
            kalmanFilteredMarker?.position = latLng
        }
        
        // Update path
        kalmanFilteredPoints.add(latLng)
        if (kalmanFilteredPolyline == null) {
            kalmanFilteredPolyline = googleMap.addPolyline(
                PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
            )
        }
        kalmanFilteredPolyline?.points = kalmanFilteredPoints
    }
    
    private fun updateRtkPosition(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        
        // Update marker
        if (rtkMarker == null) {
            rtkMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("RTK")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
        } else {
            rtkMarker?.position = latLng
        }
        
        // Update path
        rtkPoints.add(latLng)
        if (rtkPolyline == null) {
            rtkPolyline = googleMap.addPolyline(
                PolylineOptions()
                    .color(Color.GREEN)
                    .width(5f)
            )
        }
        rtkPolyline?.points = rtkPoints
    }
    
    private fun updatePositionText(location: Location, source: String) {
        val text = buildString {
            appendLine("Source: $source")
            appendLine("Latitude: ${String.format("%.8f", location.latitude)}°")
            appendLine("Longitude: ${String.format("%.8f", location.longitude)}°")
            appendLine("Altitude: ${String.format("%.2f", location.altitude)} m")
            appendLine("Accuracy: ${String.format("%.2f", location.accuracy)} m")
            appendLine("Speed: ${String.format("%.2f", location.speed)} m/s")
            appendLine("Bearing: ${String.format("%.2f", location.bearing)}°")
            appendLine("Time: ${dateFormat.format(Date(location.time))}")
        }
        binding.positionText.text = text
    }
    
    private fun updateVisibility() {
        when (currentTrackingMode) {
            TrackingMode.RAW_GPS -> {
                rawGpsMarker?.isVisible = true
                kalmanFilteredMarker?.isVisible = false
                rtkMarker?.isVisible = false
                rawGpsPolyline?.isVisible = binding.togglePathsCheckbox.isChecked
                kalmanFilteredPolyline?.isVisible = false
                rtkPolyline?.isVisible = false
            }
            TrackingMode.MAD_KALMAN_FILTER -> {
                rawGpsMarker?.isVisible = false
                kalmanFilteredMarker?.isVisible = true
                rtkMarker?.isVisible = false
                rawGpsPolyline?.isVisible = false
                kalmanFilteredPolyline?.isVisible = binding.togglePathsCheckbox.isChecked
                rtkPolyline?.isVisible = false
            }
            TrackingMode.RTK_NTRIP -> {
                rawGpsMarker?.isVisible = false
                kalmanFilteredMarker?.isVisible = false
                rtkMarker?.isVisible = true
                rawGpsPolyline?.isVisible = false
                kalmanFilteredPolyline?.isVisible = false
                rtkPolyline?.isVisible = binding.togglePathsCheckbox.isChecked
            }
        }
    }
    
    private fun updatePathVisibility(visible: Boolean) {
        when (currentTrackingMode) {
            TrackingMode.RAW_GPS -> rawGpsPolyline?.isVisible = visible
            TrackingMode.MAD_KALMAN_FILTER -> kalmanFilteredPolyline?.isVisible = visible
            TrackingMode.RTK_NTRIP -> rtkPolyline?.isVisible = visible
        }
    }
    
    private fun clearAllPaths() {
        rawGpsPoints.clear()
        kalmanFilteredPoints.clear()
        rtkPoints.clear()
        
        rawGpsPolyline?.points = emptyList()
        kalmanFilteredPolyline?.points = emptyList()
        rtkPolyline?.points = emptyList()
        
        Toast.makeText(context, "Paths cleared", Toast.LENGTH_SHORT).show()
    }
    
    private fun centerOnCurrentPosition() {
        val position = when (currentTrackingMode) {
            TrackingMode.RAW_GPS -> rawGpsMarker?.position
            TrackingMode.MAD_KALMAN_FILTER -> kalmanFilteredMarker?.position
            TrackingMode.RTK_NTRIP -> rtkMarker?.position
        }
        
        position?.let {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 17f))
        } ?: Toast.makeText(context, "No position available", Toast.LENGTH_SHORT).show()
    }
    
    private fun checkLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun initializeLogFile() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "gps_log_$timestamp.txt"
            logFile = File(requireContext().getExternalFilesDir(null), fileName)
            
            // Write header
            logFile?.appendText("GPS Position Log - Started at ${dateFormat.format(Date())}\n")
            logFile?.appendText("Format: [Timestamp] [Source] [Lat] [Lon] [Alt] [Accuracy] [Speed] [Bearing]\n")
            logFile?.appendText("=" .repeat(80) + "\n")
            
            Log.d("MapFragment", "Log file created: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("MapFragment", "Failed to create log file", e)
        }
    }
    
    private fun logPosition(source: String, location: Location) {
        try {
            val logEntry = buildString {
                append("${dateFormat.format(Date(location.time))},")
                append("$source,")
                append("${location.latitude},")
                append("${location.longitude},")
                append("${location.altitude},")
                append("${location.accuracy},")
                append("${location.speed},")
                append("${location.bearing}\n")
            }
            logFile?.appendText(logEntry)
        } catch (e: Exception) {
            Log.e("MapFragment", "Failed to log position", e)
        }
    }
    
    private fun showRtkSettingsDialog() {
        val dialog = RtkSettingsDialogFragment.newInstance()
        dialog.show(parentFragmentManager, "RtkSettingsDialog")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopTracking()
        _binding = null
    }
}
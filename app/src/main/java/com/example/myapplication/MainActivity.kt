package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.myapplication.data.repository.HomeRepository
import com.example.myapplication.data.sensor.SensorCollector
import com.example.myapplication.data.sync.DataSynchronizer
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager

class MainActivity : AppCompatActivity() {
    private val PERMISSION_REQUEST_CODE = 100
    private lateinit var binding: ActivityMainBinding
    lateinit var sensorCollector: SensorCollector
    lateinit var homeRepository: HomeRepository
    private var isCameraPermissionGranted = false
    private var isLocationPermissionGranted = false
    private var isBackgroundLocationPermissionGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorCollector = SensorCollector(this)
        val dataSynchronizer = DataSynchronizer()
        homeRepository = HomeRepository(sensorCollector, dataSynchronizer)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        requestBatteryOptimizationDisable() // 배터리 최적화 비활성화 요청
        checkLocationServiceEnabled()

        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        Log.d("MainActivity", "Initialization completed: SensorCollector and HomeRepository set up")
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        } else {
            isCameraPermissionGranted = true
            Log.d("MainActivity", "Camera permission already granted")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            isLocationPermissionGranted = true
            Log.d("MainActivity", "Fine location permission already granted")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isBackgroundLocationPermissionGranted = true
            Log.d("MainActivity", "Background location permission already granted")
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
        } else {
            Log.d("MainActivity", "All required permissions are granted")
            sensorCollector.startSensorStreaming(
                gpsCallback = null,
                imuCallback = null,
                gnssCallback = null,
                detectionCallback = null
            )
        }
    }

    private fun requestBatteryOptimizationDisable() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setMessage("GNSS 데이터 수집을 위해 배터리 최적화를 비활성화해야 합니다. 설정으로 이동하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            permissions.forEachIndexed { index, permission ->
                val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
                when (permission) {
                    Manifest.permission.CAMERA -> {
                        isCameraPermissionGranted = granted
                        if (!granted) {
                            Toast.makeText(this, "카메라 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                            Log.w("MainActivity", "Camera permission denied")
                        } else {
                            Log.d("MainActivity", "Camera permission granted after request")
                        }
                    }
                    Manifest.permission.ACCESS_FINE_LOCATION -> {
                        isLocationPermissionGranted = granted
                        if (!granted) {
                            Toast.makeText(this, "위치 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                            Log.w("MainActivity", "Location permission denied")
                        } else {
                            Log.d("MainActivity", "Location permission granted after request")
                        }
                    }
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> {
                        isBackgroundLocationPermissionGranted = granted
                        if (!granted) {
                            Toast.makeText(this, "백그라운드 위치 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
                            Log.w("MainActivity", "Background location permission denied")
                        } else {
                            Log.d("MainActivity", "Background location permission granted after request")
                        }
                    }
                }
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    private fun checkLocationServiceEnabled() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            AlertDialog.Builder(this)
                .setMessage("위치 서비스가 비활성화되어 있습니다. 설정으로 이동하여 활성화하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    fun isCameraPermissionGranted(): Boolean = isCameraPermissionGranted
    fun isLocationPermissionGranted(): Boolean = isLocationPermissionGranted
    fun isBackgroundLocationPermissionGranted(): Boolean = isBackgroundLocationPermissionGranted
}
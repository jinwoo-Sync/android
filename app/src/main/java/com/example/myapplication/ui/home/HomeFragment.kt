package com.example.myapplication.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.MainActivity
import com.example.myapplication.databinding.FragmentHomeBinding
import com.example.myapplication.utils.ResourceMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            (requireActivity() as MainActivity).sensorCollector,
            (requireActivity() as MainActivity).homeRepository
        )
    }

    private var resourceMonitor: ResourceMonitor? = null
    private var frameUpdateCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        try {
            resourceMonitor = ResourceMonitor.getInstance(requireContext())
            resourceMonitor?.logAppResourceStatus("HomeFragment", "Fragment 생성 시작")

            checkGpsAndPermissions()
            setupObservers()
            setupClickListeners()
            viewModel.startSensorStreaming()

            setupPeriodicMemoryCheck()

        } catch (e: Exception) {
            Log.e("HomeFragment", "Error initializing fragment: ${e.message}", e)
            resourceMonitor?.logAppResourceStatus("HomeFragment", "초기화 오류: ${e.message}")
            Toast.makeText(requireContext(), "초기화 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return root
    }

    /**
     * ✅ 단순화된 비트맵 설정
     */
    private fun safeSetImageBitmap(bitmap: Bitmap?) {
        try {
            binding.imageView.setImageBitmap(bitmap)

            if (bitmap != null && !bitmap.isRecycled) {
                frameUpdateCount++
                Log.d("HomeFragment", "✅ UI 업데이트 성공: ${bitmap.width}x${bitmap.height} (총 ${frameUpdateCount}프레임)")

                if (frameUpdateCount % 100 == 0) {
                    Log.i("HomeFragment", "📊 총 UI 프레임 업데이트: ${frameUpdateCount}회")
                    resourceMonitor?.logAppResourceStatus("HomeFragment", "UI 프레임 업데이트: ${frameUpdateCount}회")
                }
            } else {
                Log.d("HomeFragment", "⚠️ UI 클리어됨 (null 또는 recycled bitmap)")
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "UI 업데이트 오류: ${e.message}", e)
            try {
                binding.imageView.setImageBitmap(null)
            } catch (e2: Exception) {
                Log.e("HomeFragment", "UI 클리어 실패: ${e2.message}", e2)
            }
        }
    }

    private fun setupPeriodicMemoryCheck() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15000)
                resourceMonitor?.let { monitor ->
                    val warnings = monitor.checkAppMemoryWarnings()
                    if (warnings.isNotEmpty()) {
                        Log.w("HomeFragment", "메모리 경고: ${warnings.joinToString()}")
                        performEmergencyCleanup()
                    }
                }
            }
        }
    }

    private fun performEmergencyCleanup() {
        Log.w("HomeFragment", "🚨 메모리 부족 경고! 응급 정리 작업 수행")
        binding.imageView.setImageBitmap(null)
        viewModel.forceCleanupBitmapPool() // 🎯 ViewModel을 통한 풀 정리
        System.gc()
        Log.w("HomeFragment", "🧹 응급 정리 완료")
    }

    private fun checkGpsAndPermissions() {
        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(requireContext())
                .setMessage("GNSS 데이터 수집을 위해 GPS를 활성화해주세요.")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        val mainActivity = requireActivity() as MainActivity

        if (!mainActivity.isBackgroundLocationPermissionGranted()) {
            Toast.makeText(requireContext(), "백그라운드 위치 권한이 필요합니다. 설정에서 '항상 허용'을 선택해주세요.", Toast.LENGTH_LONG).show()
        }

        if (mainActivity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            binding.gpsLogText.text = "GNSS 데이터 수집을 위해 위치 권한이 필요합니다."
            AlertDialog.Builder(requireContext())
                .setMessage("GNSS 데이터 수집을 위해 위치 권한이 필요합니다. 설정 화면으로 이동하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", requireActivity().packageName, null)
                    intent.data = uri
                    startActivity(intent)
                }
                .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun setupObservers() {
        val textView: TextView = binding.textHome
        viewModel.text.observe(viewLifecycleOwner) { message ->
            textView.text = message
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        // ✅ 단순화된 카메라 프레임 처리 (ViewModel에서 이미 15fps 제한됨)
        viewModel.cameraFrame.observe(viewLifecycleOwner) { bitmap ->
            safeSetImageBitmap(bitmap)
            Log.d("HomeFragment", "✅ Observer received frame: ${bitmap != null}")
        }

        // 🎯 풀 상태 관찰자 추가
        viewModel.poolStatus.observe(viewLifecycleOwner) { status ->
            binding.poolStatusText.text = status
            Log.d("HomeFragment", "📊 Pool status updated: $status")
        }

        viewModel.boundingBoxes.observe(viewLifecycleOwner) { boundingBoxes ->
            try {
                binding.overlayView.setResults(boundingBoxes)
                binding.overlayView.invalidate()

                if (boundingBoxes.isNotEmpty()) {
                    Log.d("HomeFragment", "🎯 UI에서 ${boundingBoxes.size}개 객체 표시")
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "바운딩 박스 처리 오류: ${e.message}", e)
                binding.overlayView.clear()
            }
        }

        viewModel.inferenceTime.observe(viewLifecycleOwner) { time ->
            binding.inferenceTime.text = "Inference: $time"
        }

        viewModel.effectiveInterval.observe(viewLifecycleOwner) { interval ->
            val currentText = binding.editTextFrameSkip.text.toString()
            val parsed = currentText.toIntOrNull()
            if (!binding.editTextFrameSkip.isFocused && parsed != interval) {
                binding.editTextFrameSkip.setText(interval.toString())
            }
        }

        viewModel.gpsData.observe(viewLifecycleOwner) { data ->
            binding.gpsLogText.text = data
            Log.d("HomeFragment", "📍 GPS 데이터 UI 업데이트: $data")
        }

        viewModel.gnssData.observe(viewLifecycleOwner) { data ->
            if (data == "GNSS: 대기 중") {
                binding.gnssLogText.text = "GNSS 데이터가 수신되지 않습니다."
            } else {
                binding.gnssLogText.text = data
            }
            Log.d("HomeFragment", "🛰️ GNSS 데이터 UI 업데이트: $data")
        }

        viewModel.imuData.observe(viewLifecycleOwner) { data ->
            binding.imuLogText.text = data
        }

        viewModel.isStreaming.observe(viewLifecycleOwner) { isStreaming ->
            binding.buttonOpenCamera.text = if (isStreaming) "스트리밍 중지" else "스트리밍 시작"
            binding.buttonCaptureFrame.text = if (isStreaming) "현재 프레임 저장" else "프레임 캡처"

            if (!isStreaming) {
                binding.overlayView.clear()
                binding.inferenceTime.text = "Inference: 0ms"
                safeSetImageBitmap(null)
                Log.d("HomeFragment", "🔴 Camera streaming stopped - UI cleared")
            } else {
                Log.d("HomeFragment", "🟢 Camera streaming started - UI ready")
            }
            Log.d("HomeFragment", "✅ Camera streaming status changed: $isStreaming")
        }

        viewModel.isSensorStreaming.observe(viewLifecycleOwner) { isSensorStreaming ->
            Log.d("HomeFragment", "🔧 Sensor streaming status: $isSensorStreaming")
            if (!isSensorStreaming) {
                binding.gpsLogText.text = "GPS: 대기 중"
                binding.gnssLogText.text = "GNSS: 대기 중"
                binding.imuLogText.text = "IMU: 대기 중"
            }
        }

        viewModel.isServerTransmissionEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.streamingCheckbox.isChecked = enabled
            Log.d("HomeFragment", "Server streaming checkbox updated: $enabled")
        }
    }

    private fun setupClickListeners() {
        val mainActivity = requireActivity() as MainActivity

        binding.buttonOpenCamera.setOnClickListener {
            if (mainActivity.isCameraPermissionGranted() &&
                mainActivity.isLocationPermissionGranted() &&
                mainActivity.isBackgroundLocationPermissionGranted()) {
                lifecycleScope.launch {
                    viewModel.toggleStreaming(requireContext())
                    Log.d("HomeFragment", "✅ Camera streaming toggle requested")
                }
            } else {
                Toast.makeText(requireContext(), "카메라와 위치 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                Log.w("HomeFragment", "⚠️ Missing permissions for camera streaming")
            }
        }

        binding.buttonCaptureFrame.setOnClickListener {
            if (mainActivity.isCameraPermissionGranted()) {
                lifecycleScope.launch {
                    viewModel.fetchCameraData()
                    Log.d("HomeFragment", "✅ Frame capture requested")
                }
            } else {
                Toast.makeText(requireContext(), "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                Log.w("HomeFragment", "⚠️ Missing camera permission for frame capture")
            }
        }

        // 🎯 풀 정리 버튼
        binding.buttonCleanupPool.setOnClickListener {
            viewModel.forceCleanupBitmapPool()
            Toast.makeText(requireContext(), "비트맵 풀 정리 요청", Toast.LENGTH_SHORT).show()
            Log.d("HomeFragment", "🧹 Pool cleanup requested")
        }

        // 🎯 풀 상태 업데이트 버튼
        binding.buttonPoolStatus.setOnClickListener {
            viewModel.updatePoolStatus()
            Toast.makeText(requireContext(), "풀 상태 업데이트", Toast.LENGTH_SHORT).show()
            Log.d("HomeFragment", "📊 Pool status update requested")
        }

        binding.loggingCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleLogSaving(requireContext(), isChecked)
            Log.d("HomeFragment", "Logging checkbox changed: $isChecked")
        }

        binding.streamingCheckbox.setOnCheckedChangeListener { _, isChecked ->
            Log.d("HomeFragment", "Streaming checkbox changed: $isChecked")
            lifecycleScope.launch {
                viewModel.setServerStreamingEnabled(requireContext(), isChecked)
                Log.d("HomeFragment", "Server streaming checkbox updated: $isChecked")
            }
        }

        binding.buttonSetFrameSkip.setOnClickListener {
            val intervalText = binding.editTextFrameSkip.text.toString()
            val interval = intervalText.toIntOrNull()
            if (interval != null && interval in 2..15) {
                viewModel.setUserFrameSkipInterval(interval)
                Toast.makeText(requireContext(), "Frame skip interval set to $interval", Toast.LENGTH_SHORT).show()
                Log.d("HomeFragment", "Frame skip interval set to: $interval")
            } else {
                Toast.makeText(requireContext(), "Please enter a valid integer between 2 and 15", Toast.LENGTH_SHORT).show()
                Log.w("HomeFragment", "Invalid frame skip interval: $intervalText")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("HomeFragment", "✅ 모든 권한이 부여되었습니다")
                viewModel.startSensorStreaming()
            } else {
                Toast.makeText(requireContext(), "필요한 권한이 부여되지 않았습니다", Toast.LENGTH_LONG).show()
                Log.w("HomeFragment", "⚠️ 일부 권한이 거부되었습니다")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("HomeFragment", "📱 Fragment resumed - starting sensor streaming only")
        resourceMonitor?.logAppResourceStatus("HomeFragment", "Fragment resumed")
        viewModel.startSensorStreaming()

        // 🎯 풀 상태 자동 업데이트
        viewModel.updatePoolStatus()
    }

    override fun onPause() {
        super.onPause()
        Log.d("HomeFragment", "📱 Fragment paused - sensor streaming continues")
        resourceMonitor?.logAppResourceStatus("HomeFragment", "Fragment paused")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("HomeFragment", "📱 Fragment destroying")
        resourceMonitor?.logAppResourceStatus("HomeFragment", "Fragment 종료 시작")

        binding.imageView.setImageBitmap(null)

        val total = frameUpdateCount
        Log.i("HomeFragment", "📊 최종 통계 - 총 UI 프레임: ${total}회")

        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor?.logAppResourceStatus("HomeFragment", "Fragment 종료 완료")
        resourceMonitor = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
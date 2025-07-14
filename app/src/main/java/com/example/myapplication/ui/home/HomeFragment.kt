package com.example.myapplication.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.MainActivity
import com.example.myapplication.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import java.lang.ref.WeakReference

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            (requireActivity() as MainActivity).sensorCollector,
            (requireActivity() as MainActivity).homeRepository
        )
    }

    // ✅ 비트맵 메모리 관리를 위한 약한 참조
    private var currentBitmapRef: WeakReference<Bitmap>? = null
    private val bitmapLock = Object()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        try {
            // ✅ 권한 및 GPS 설정 체크
            checkGpsAndPermissions()

            // ✅ UI 관찰자 설정
            setupObservers()

            // ✅ 버튼 클릭 리스너 설정
            setupClickListeners()

            // ✅ 센서 스트리밍은 백그라운드에서 자동 시작
            viewModel.startSensorStreaming()

        } catch (e: Exception) {
            Log.e("HomeFragment", "Error initializing fragment: ${e.message}", e)
            Toast.makeText(requireContext(), "초기화 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return root
    }

    /**
     * ✅ 안전한 비트맵 설정 메서드
     */
    private fun safeSetImageBitmap(newBitmap: Bitmap?) {
        synchronized(bitmapLock) {
            try {
                // ✅ 이전 비트맵 안전하게 해제
                val currentDrawable = binding.imageView.drawable
                if (currentDrawable is BitmapDrawable) {
                    val oldBitmap = currentDrawable.bitmap
                    val currentBitmap = currentBitmapRef?.get()

                    // 이전 비트맵이 새 비트맵과 다르고 재활용되지 않았으면 재활용
                    if (oldBitmap != null && oldBitmap != newBitmap &&
                        !oldBitmap.isRecycled && oldBitmap == currentBitmap) {
                        try {
                            oldBitmap.recycle()
                            Log.d("HomeFragment", "✅ 이전 비트맵 재활용 완료")
                        } catch (e: Exception) {
                            Log.w("HomeFragment", "이전 비트맵 재활용 실패: ${e.message}")
                        }
                    }
                }

                // ✅ 새 비트맵 설정
                if (newBitmap != null && !newBitmap.isRecycled) {
                    binding.imageView.setImageBitmap(newBitmap)
                    currentBitmapRef = WeakReference(newBitmap)
                    Log.d("HomeFragment", "✅ 새 비트맵 설정 완료: ${newBitmap.width}x${newBitmap.height}")
                } else {
                    binding.imageView.setImageBitmap(null)
                    currentBitmapRef = null
                    Log.d("HomeFragment", "⚠️ 비트맵 클리어 (null 또는 재활용됨)")
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "비트맵 설정 중 오류: ${e.message}", e)
                // 오류 발생 시 안전하게 null 설정
                try {
                    binding.imageView.setImageBitmap(null)
                    currentBitmapRef = null
                } catch (ex: Exception) {
                    Log.e("HomeFragment", "비트맵 null 설정도 실패: ${ex.message}")
                }
            }
        }
    }

    /**
     * ✅ GPS 및 권한 상태 체크
     */
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

    /**
     * ✅ UI 관찰자 설정 - 센서와 카메라 분리
     */
    private fun setupObservers() {
        // 상태 메시지 관찰
        val textView: TextView = binding.textHome
        viewModel.text.observe(viewLifecycleOwner) { message ->
            textView.text = message
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        // ✅ 카메라 프레임 관찰 - 안전한 비트맵 처리
        viewModel.cameraFrame.observe(viewLifecycleOwner) { bitmap ->
            try {
                safeSetImageBitmap(bitmap)

                if (bitmap != null && !bitmap.isRecycled) {
                    Log.d("HomeFragment", "✅✅✅ Camera frame SUCCESSFULLY updated in UI! Size: ${bitmap.width}x${bitmap.height}")
                } else {
                    Log.d("HomeFragment", "⚠️ Camera frame cleared (null or recycled bitmap)")
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Camera frame 처리 오류: ${e.message}", e)
                // 오류 발생 시 안전하게 처리
                safeSetImageBitmap(null)
            }
        }

        // ✅ 바운딩 박스 관찰 - 강화된 로깅
        viewModel.boundingBoxes.observe(viewLifecycleOwner) { boundingBoxes ->
            try {
                Log.d("HomeFragment", "🎯 UI에서 바운딩 박스 수신: ${boundingBoxes.size}개")

                if (boundingBoxes.isNotEmpty()) {
                    Log.d("HomeFragment", "🎯 UI에서 표시할 객체들: ${boundingBoxes.map { "${it.clsName}(${it.cnf})" }}")
                    boundingBoxes.forEachIndexed { index, bbox ->
                        Log.d("HomeFragment", "   📦 객체 $index: ${bbox.clsName}, 신뢰도: ${bbox.cnf}, 위치: (${bbox.x1}, ${bbox.y1}) - (${bbox.x2}, ${bbox.y2})")
                    }
                } else {
                    Log.d("HomeFragment", "🎯 UI: 표시할 객체 없음")
                }

                binding.overlayView.setResults(boundingBoxes)
                binding.overlayView.invalidate()
                Log.d("HomeFragment", "🎯 OverlayView 업데이트 완료")
            } catch (e: Exception) {
                Log.e("HomeFragment", "바운딩 박스 처리 오류: ${e.message}", e)
                // 오류 발생 시 오버레이 클리어
                binding.overlayView.clear()
            }
        }

        // 추론 시간 관찰
        viewModel.inferenceTime.observe(viewLifecycleOwner) { time ->
            binding.inferenceTime.text = "Inference: $time"
        }

        // 프레임 스킵 간격 관찰
        viewModel.effectiveInterval.observe(viewLifecycleOwner) { interval ->
            val currentText = binding.editTextFrameSkip.text.toString()
            val parsed = currentText.toIntOrNull()

            if (!binding.editTextFrameSkip.isFocused && parsed != interval) {
                binding.editTextFrameSkip.setText(interval.toString())
            }
        }

        // ✅ GPS 데이터 관찰 - 강화된 로깅
        viewModel.gpsData.observe(viewLifecycleOwner) { data ->
            binding.gpsLogText.text = data
            Log.d("HomeFragment", "📍 GPS 데이터 UI 업데이트: $data")
        }

        // ✅ GNSS 데이터 관찰 - 강화된 로깅
        viewModel.gnssData.observe(viewLifecycleOwner) { data ->
            if (data == "GNSS: 대기 중") {
                binding.gnssLogText.text = "GNSS 데이터가 수신되지 않습니다."
            } else {
                binding.gnssLogText.text = data
            }
            Log.d("HomeFragment", "🛰️ GNSS 데이터 UI 업데이트: $data")
        }

        // ✅ IMU 데이터 관찰 - 강화된 로깅
        viewModel.imuData.observe(viewLifecycleOwner) { data ->
            binding.imuLogText.text = data
            Log.d("HomeFragment", "📊 IMU 데이터 UI 업데이트: $data")
        }

        // ✅ 카메라 스트리밍 상태 관찰
        viewModel.isStreaming.observe(viewLifecycleOwner) { isStreaming ->
            binding.buttonOpenCamera.text = if (isStreaming) "스트리밍 중지" else "스트리밍 시작"
            binding.buttonCaptureFrame.text = if (isStreaming) "현재 프레임 저장" else "프레임 캡처"

            if (!isStreaming) {
                // ✅ 스트리밍 중지 시 안전하게 UI 클리어
                binding.overlayView.clear()
                binding.inferenceTime.text = "Inference: 0ms"
                safeSetImageBitmap(null)
                Log.d("HomeFragment", "🔴 Camera streaming stopped - UI cleared")
            } else {
                Log.d("HomeFragment", "🟢 Camera streaming started - UI ready for frames")
            }

            Log.d("HomeFragment", "✅ Camera streaming status changed: $isStreaming")
        }

        // ✅ 센서 스트리밍 상태 관찰 (추가)
        viewModel.isSensorStreaming.observe(viewLifecycleOwner) { isSensorStreaming ->
            Log.d("HomeFragment", "🔧 Sensor streaming status: $isSensorStreaming")

            if (!isSensorStreaming) {
                // 센서 스트리밍이 중지되면 센서 데이터 UI 초기화
                binding.gpsLogText.text = "GPS: 대기 중"
                binding.gnssLogText.text = "GNSS: 대기 중"
                binding.imuLogText.text = "IMU: 대기 중"
            }
        }

        // 서버 전송 상태 관찰
        viewModel.isServerTransmissionEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.streamingCheckbox.isChecked = enabled
            Log.d("HomeFragment", "Server streaming checkbox updated: $enabled")
        }
    }

    /**
     * ✅ 버튼 클릭 리스너 설정
     */
    private fun setupClickListeners() {
        val mainActivity = requireActivity() as MainActivity

        // ✅ 카메라 스트리밍 버튼 (UI 전용)
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

        // ✅ 프레임 캡처 버튼
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

        // 로그 저장 체크박스
        binding.loggingCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleLogSaving(requireContext(), isChecked)
            Log.d("HomeFragment", "Logging checkbox changed: $isChecked")
        }

        // 서버 스트리밍 체크박스
        binding.streamingCheckbox.setOnCheckedChangeListener { _, isChecked ->
            Log.d("HomeFragment", "Streaming checkbox changed: $isChecked")
            lifecycleScope.launch {
                viewModel.setServerStreamingEnabled(requireContext(), isChecked)
                Log.d("HomeFragment", "Server streaming checkbox updated: $isChecked")
            }
        }

        // 프레임 스킵 설정 버튼
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

    /**
     * ✅ 권한 체크 및 요청
     */
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
        // ✅ 센서 스트리밍만 자동 시작 (카메라는 수동 제어)
        viewModel.startSensorStreaming()
    }

    override fun onPause() {
        super.onPause()
        Log.d("HomeFragment", "📱 Fragment paused - sensor streaming continues")
        // ✅ Fragment pause 시 센서 스트리밍은 유지 (백그라운드 동작)
        // 필요시에만 중지: viewModel.stopSensorStreaming()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("HomeFragment", "📱 Fragment destroying - cleaning up")

        // ✅ 비트맵 메모리 정리
        synchronized(bitmapLock) {
            try {
                currentBitmapRef?.get()?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                        Log.d("HomeFragment", "✅ Fragment 종료 시 비트맵 재활용 완료")
                    }
                }
                currentBitmapRef = null
            } catch (e: Exception) {
                Log.w("HomeFragment", "Fragment 종료 시 비트맵 정리 실패: ${e.message}")
            }
        }

        // ✅ Fragment 완전 종료 시에만 센서 스트리밍 중지
        viewModel.stopSensorStreaming()
        _binding = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
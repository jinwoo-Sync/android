package com.example.myapplication.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.example.myapplication.learning.yolo.OverlayView
import kotlinx.coroutines.launch
import android.net.Uri
import android.provider.Settings
import android.widget.TextView

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            (requireActivity() as MainActivity).sensorCollector,
            (requireActivity() as MainActivity).homeRepository
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        try {

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

            val textView: TextView = binding.textHome
            viewModel.text.observe(viewLifecycleOwner) {
                textView.text = it
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }

            viewModel.cameraFrame.observe(viewLifecycleOwner) { bitmap ->
                if (bitmap != null) {
                    binding.imageView.setImageBitmap(bitmap)
                } else {
                    binding.imageView.setImageBitmap(null)
                    Log.d("HomeFragment", "Camera frame cleared")
                }
            }

            viewModel.boundingBoxes.observe(viewLifecycleOwner) { boundingBoxes ->
                Log.d("HomeFragment", "Received bounding boxes: ${boundingBoxes.size}")
                binding.overlayView.setResults(boundingBoxes)
                binding.overlayView.invalidate()
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
                binding.gpsLogText.text = "GPS: $data"
                Log.d("HomeFragment", "GPS 데이터 UI 업데이트: $data")
            }

            viewModel.gnssData.observe(viewLifecycleOwner) { data ->
                if (data == "GNSS: 대기 중") {
                    binding.gnssLogText.text = "GNSS 데이터가 수신되지 않습니다."
                } else {
                    binding.gnssLogText.text = data
                }
                Log.d("HomeFragment", "GNSS 데이터 UI 업데이트: $data")
            }

            viewModel.imuData.observe(viewLifecycleOwner) { data ->
                binding.imuLogText.text = data
                Log.d("HomeFragment", "IMU 데이터 UI 업데이트: $data")
            }

            viewModel.isStreaming.observe(viewLifecycleOwner) { isStreaming ->
                binding.buttonOpenCamera.text = if (isStreaming) "스트리밍 중지" else "스트리밍 시작"
                binding.buttonCaptureFrame.text = if (isStreaming) "현재 프레임 저장" else "프레임 캡처"
                if (!isStreaming) {
                    binding.overlayView.clear()
                    binding.inferenceTime.text = "Inference: 0ms"
                }
            }

            viewModel.isServerTransmissionEnabled.observe(viewLifecycleOwner) { enabled ->
                binding.streamingCheckbox.isChecked = enabled
                Log.d("HomeFragment", "Server streaming checkbox inclusionupdated: $enabled")
            }

            binding.buttonOpenCamera.setOnClickListener {
                if (mainActivity.isCameraPermissionGranted() && mainActivity.isLocationPermissionGranted() && mainActivity.isBackgroundLocationPermissionGranted()) {
                    lifecycleScope.launch {
                        viewModel.toggleStreaming(requireContext())
                        Log.d("HomeFragment", "All streaming toggled")
                    }
                } else {
                    Toast.makeText(requireContext(), "카메라와 위치 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                }
            }

            binding.buttonCaptureFrame.setOnClickListener {
                if (mainActivity.isCameraPermissionGranted()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.fetchCameraData()
                    }
                } else {
                    Toast.makeText(requireContext(), "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                }
            }

            binding.loggingCheckbox.setOnCheckedChangeListener { _, isChecked ->
                viewModel.toggleLogSaving(requireContext(), isChecked)
                Log.d("HomeFragment", "Logging checkbox changed: $isChecked")
            }

            binding.streamingCheckbox.setOnCheckedChangeListener { _, isChecked ->
                Log.d("HomeFragment", "Streaming checkbox changed: $isChecked")
                lifecycleScope.launch {
                    viewModel.setServerStreamingEnabled(requireContext(), isChecked)
                    Log.d("HomeFragment", "Server streaming checkbox inclusionupdated: $isChecked")
                }
            }

            binding.buttonSetFrameSkip.setOnClickListener {
                val intervalText = binding.editTextFrameSkip.text.toString()
                val interval = intervalText.toIntOrNull()
                if (interval != null && interval in 2..15) {
                    viewModel.setUserFrameSkipInterval(interval)
                    Toast.makeText(requireContext(), "Frame skip interval set to $interval", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Please enter a valid integer between 2 and 15", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            Log.e("HomeFragment", "Error initializing fragment: ${e.message}", e)
            Toast.makeText(requireContext(), "초기화 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }

        viewModel.startSensorStreaming()
        return root
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
                Log.d("HomeFragment", "모든 권한이 부여되었습니다")
                viewModel.startSensorStreaming()
            } else {
                Toast.makeText(requireContext(), "필요한 권한이 부여되지 않았습니다", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startSensorStreaming() // GNSS 콜백 재등록
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopSensorStreaming() // GNSS 콜백 해제
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopSensorStreaming()
        _binding = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
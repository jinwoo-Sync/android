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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.MainActivity
import com.example.myapplication.databinding.FragmentHomeBinding
import com.example.myapplication.utils.AdvancedTaggedBitmapPool
import com.example.myapplication.utils.BitmapPoolManager
import com.example.myapplication.utils.HealthLevel
import com.example.myapplication.utils.ManagedBitmap
import com.example.myapplication.utils.PoolHealthStatus
import com.example.myapplication.utils.ResourceMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * 공통 유틸리티 모음
 */
private object FragmentUtils {
    fun logEvent(tag: String, level: String, message: String, exception: Exception? = null) {
        when (level.uppercase()) {
            "ERROR" -> Log.e(tag, message, exception)
            "WARN" -> Log.w(tag, message)
            "DEBUG" -> Log.d(tag, message)
            "INFO" -> Log.i(tag, message)
        }
    }

    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
    }
}

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            (requireActivity() as MainActivity).sensorCollector,
            (requireActivity() as MainActivity).homeRepository
        )
    }

    // ✅ 전역 BitmapPoolManager 참조 (MainActivity에서 생성된 싱글톤)
    private lateinit var bitmapPoolManager: BitmapPoolManager

    // ✅ 현재 UI에 표시 중인 ManagedBitmap 참조 (복사 없음)
    private var currentManagedBitmap: ManagedBitmap? = null

    private var frameSkipCount = 0
    private var successfulFrameCount = 0
    private var resourceMonitor: ResourceMonitor? = null

    // 복구 관련 변수
    private var lastRecoveryTime = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        try {
            initializeComponents()
            checkGpsAndPermissions()
            setupObservers()
            setupClickListeners()
            viewModel.startSensorStreaming()
            startHealthMonitoring()

        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "초기화 오류: ${e.message}", e)
            showToast("초기화 오류: ${e.message}", true)
        }

        return root
    }

    private fun initializeComponents() {
        resourceMonitor = ResourceMonitor.getInstance(requireContext())

        // ✅ MainActivity에서 생성된 BitmapPoolManager 싱글톤 참조
        bitmapPoolManager = BitmapPoolManager.getInstance(requireContext())

        FragmentUtils.logEvent("HomeFragment", "DEBUG", "✅ 전역 BitmapPoolManager 참조 완료")
        Log.d("HomeFragment", "📊 초기 풀 상태: ${bitmapPoolManager.advancedTaggedBitmapPool.getStatus()}")
    }

    private fun startHealthMonitoring() {
        lifecycleScope.launch {
            while (isActive) {
                delay(5000)

                // ✅ 전역 풀 건강성 체크
                val healthStatus: PoolHealthStatus = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
                if (healthStatus.healthLevel != HealthLevel.HEALTHY) {
                    FragmentUtils.logEvent("HomeFragment", "WARN", "전역 풀 건강성 문제 감지: ${healthStatus.healthLevel}")

                    // 필요시 ViewModel을 통해 복구 요청
                    if (healthStatus.healthLevel == HealthLevel.CRITICAL) {
                        requestPoolRecovery()
                    }
                }

                // UI 상태 업데이트
                binding.poolStatusText.text = bitmapPoolManager.advancedTaggedBitmapPool.getStatus()
            }
        }
    }

    /**
     * 🚨 전역 풀 복구 요청
     */
    private fun requestPoolRecovery() {
        try {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastRecoveryTime < 5000) {
                FragmentUtils.logEvent("HomeFragment", "WARN", "복구 시도 너무 빈번 - 스킵")
                return
            }
            lastRecoveryTime = currentTime

            FragmentUtils.logEvent("HomeFragment", "WARN", "🔧 전역 풀 복구 요청")

            // ✅ ViewModel을 통해 전역 풀 정리 요청
            viewModel.forceCleanupBitmapPool()

            showToast("전역 풀 복구 완료")

        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "전역 풀 복구 중 예외: ${e.message}", e)
        }
    }

    /**
     * 🎯 핵심 함수: Advanced Tagged Pool의 ManagedBitmap을 직접 UI에 표시
     * - 복사 없음, 원본 참조 사용
     * - 딥러닝 오버레이와 함께 표시
     */
    private fun displayManagedBitmapDirectly(sensorData: com.example.myapplication.model.SensorData) {
        try {
            // ✅ 1단계: 유효성 검증
            if (sensorData.bitmap == null || sensorData.bitmap.isRecycled) {
                Log.w("HomeFragment", "⚠️ 무효한 비트맵: frameId=${sensorData.frameId}")
                clearCurrentDisplay()
                return
            }

            Log.d("HomeFragment", "🎯 ManagedBitmap 직접 표시: frameId=${sensorData.frameId}, size=${sensorData.bitmap.width}x${sensorData.bitmap.height}")

            // ✅ 2단계: 이전 참조 해제 (ManagedBitmap의 참조 카운팅 활용)
            currentManagedBitmap?.release()

            // ✅ 3단계: 새로운 ManagedBitmap 참조 (Advanced Tagged Pool의 원본)
            // 여기서는 SensorData의 bitmap이 이미 ManagedBitmap.bitmap이므로 직접 사용
            // 실제로는 ManagedBitmap 객체 자체를 받아야 하지만, 현재 구조상 bitmap만 전달됨

            // UI 스레드에서 안전하게 업데이트
            binding.imageView.post {
                try {
                    if (!sensorData.bitmap.isRecycled) {
                        // ✅ 원본 비트맵을 직접 ImageView에 설정 (복사 없음)
                        binding.imageView.setImageBitmap(sensorData.bitmap)

                        successfulFrameCount++
                        frameSkipCount = 0

                        Log.d("HomeFragment", "✅ 직접 UI 업데이트 성공: frameId=${sensorData.frameId}")
                    } else {
                        Log.e("HomeFragment", "❌ UI 스레드에서 비트맵 재검증 실패: frameId=${sensorData.frameId}")
                        handleUIUpdateFailure()
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "❌ UI 업데이트 예외: ${e.message}", e)
                    handleUIUpdateFailure()
                }
            }

        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ ManagedBitmap 직접 표시 실패: ${e.message}", e)
            clearCurrentDisplay()
        }
    }

    private fun clearCurrentDisplay() {
        // ✅ ManagedBitmap 참조 해제 (복사본이 아니므로 pool 반납은 하지 않음)
        currentManagedBitmap?.release()
        currentManagedBitmap = null

        binding.imageView.setImageBitmap(null)
        Log.d("HomeFragment", "🧹 UI 디스플레이 클리어 완료")
    }

    private fun handleUIUpdateFailure() {
        binding.imageView.setImageBitmap(null)
        frameSkipCount++

        if (frameSkipCount > 30) {
            Log.w("HomeFragment", "🚨 지속적인 UI 업데이트 실패 - 전역 풀 복구 요청")
            requestPoolRecovery()
        }
    }

    // 권한 관련 메서드들
    private fun checkGpsAndPermissions() {
        checkGpsStatus()
        checkLocationPermissions()
    }

    private fun checkGpsStatus() {
        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGpsDialog()
        }
    }

    private fun showGpsDialog() {
        AlertDialog.Builder(requireContext())
            .setMessage("GNSS 데이터 수집을 위해 GPS를 활성화해주세요.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun checkLocationPermissions() {
        val mainActivity = requireActivity() as MainActivity

        if (!mainActivity.isBackgroundLocationPermissionGranted()) {
            showToast("백그라운드 위치 권한이 필요합니다. 설정에서 '항상 허용'을 선택해주세요.", true)
        }

        if (mainActivity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            binding.gpsLogText.text = "GNSS 데이터 수집을 위해 위치 권한이 필요합니다."
            showLocationPermissionDialog()
        }
    }

    private fun showLocationPermissionDialog() {
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

    // UI 업데이트 헬퍼 메서드들
    private fun showToast(message: String, isLong: Boolean = false) {
        FragmentUtils.showToast(requireContext(), message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
    }

    private fun updateStreamingUI(isStreaming: Boolean) {
        binding.buttonOpenCamera.text = if (isStreaming) "스트리밍 중지" else "스트리밍 시작"
        binding.buttonCaptureFrame.text = if (isStreaming) "현재 프레임 저장" else "프레임 캡처"

        if (!isStreaming) {
            clearStreamingState()
        }
    }

    private fun clearStreamingState() {
        binding.overlayView.clear()
        binding.inferenceTime.text = "Inference: 0ms"
        clearCurrentDisplay()
    }

    private fun resetSensorDisplays() {
        binding.gpsLogText.text = "GPS: 대기 중"
        binding.gnssLogText.text = "GNSS: 대기 중"
        binding.imuLogText.text = "IMU: 대기 중"
    }

    private fun updateFrameSkipIfNeeded(interval: Int) {
        val currentText = binding.editTextFrameSkip.text.toString()
        val parsed = currentText.toIntOrNull()
        if (!binding.editTextFrameSkip.isFocused && parsed != interval) {
            binding.editTextFrameSkip.setText(interval.toString())
        }
    }

    private fun setupObservers() {
        setupBasicObservers()
        setupCameraFrameObserver()
        setupSensorObservers()
        setupStatusObservers()
    }

    private fun setupBasicObservers() {
        val textView: TextView = binding.textHome
        viewModel.text.observe(viewLifecycleOwner) { message ->
            textView.text = message
            showToast(message)
        }
    }

    private fun setupCameraFrameObserver() {
        viewModel.cameraFrame.observe(viewLifecycleOwner) { sensorData ->
            try {
                Log.d("HomeFragment", "📱 Camera Frame Observer: sensorData=${sensorData != null}")

                if (sensorData?.bitmap != null && !sensorData.bitmap.isRecycled) {
                    Log.d("HomeFragment", "✅ 유효한 프레임 수신: frameId=${sensorData.frameId}")

                    // ✅ ManagedBitmap을 직접 UI에 표시 (복사 없음)
                    displayManagedBitmapDirectly(sensorData)

                } else {
                    Log.w("HomeFragment", "⚠️ 무효한 프레임 수신 - UI 클리어")
                    clearCurrentDisplay()
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ 프레임 Observer 처리 오류: ${e.message}", e)
                clearCurrentDisplay()
            }
        }
    }

    private fun setupSensorObservers() {
        viewModel.gpsData.observe(viewLifecycleOwner) { data ->
            binding.gpsLogText.text = data
        }

        viewModel.gnssData.observe(viewLifecycleOwner) { data ->
            binding.gnssLogText.text = if (data == "GNSS: 대기 중") {
                "GNSS 데이터가 수신되지 않습니다."
            } else {
                data
            }
        }

        viewModel.imuData.observe(viewLifecycleOwner) { data ->
            binding.imuLogText.text = data
        }
    }

    private fun setupStatusObservers() {
        // ✅ 전역 Advanced Tagged Pool 상태 표시
        viewModel.poolStatus.observe(viewLifecycleOwner) { poolStatus ->
            binding.poolStatusText.text = poolStatus
        }

        viewModel.boundingBoxes.observe(viewLifecycleOwner) { boundingBoxes ->
            binding.overlayView.setResults(boundingBoxes)
            binding.overlayView.invalidate()
        }

        viewModel.inferenceTime.observe(viewLifecycleOwner) { time ->
            binding.inferenceTime.text = "Inference: $time"
        }

        viewModel.effectiveInterval.observe(viewLifecycleOwner) { interval ->
            updateFrameSkipIfNeeded(interval)
        }

        viewModel.isStreaming.observe(viewLifecycleOwner) { isStreaming ->
            updateStreamingUI(isStreaming)
        }

        viewModel.isSensorStreaming.observe(viewLifecycleOwner) { isSensorStreaming ->
            if (!isSensorStreaming) {
                resetSensorDisplays()
            }
        }

        viewModel.isServerTransmissionEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.streamingCheckbox.isChecked = enabled
        }

        // 🎯 전역 풀 복구 신호 Observer
        viewModel.shouldRecoverUIPool.observe(viewLifecycleOwner) { shouldRecover ->
            if (shouldRecover) {
                Log.w("HomeFragment", "🚨 ViewModel에서 전역 풀 복구 신호 수신")
                requestPoolRecovery()
                showToast("전역 풀 자동 복구 완료")
            }
        }
    }

    private fun setupClickListeners() {
        val mainActivity = requireActivity() as MainActivity

        binding.buttonOpenCamera.setOnClickListener {
            handleCameraButtonClick(mainActivity)
        }

        binding.buttonCaptureFrame.setOnClickListener {
            handleCaptureButtonClick(mainActivity)
        }

        binding.buttonCleanupPool.setOnClickListener {
            // ✅ 전역 Advanced Tagged Pool 정리
            viewModel.forceCleanupBitmapPool()
            showToast("전역 비트맵 풀 정리 완료")
        }

        binding.buttonPoolStatus.setOnClickListener {
            viewModel.updatePoolStatus()
            showToast("풀 상태 업데이트")
        }

        binding.loggingCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleLogSaving(requireContext(), isChecked)
        }

        binding.streamingCheckbox.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                viewModel.setServerStreamingEnabled(requireContext(), isChecked)
            }
        }

        binding.buttonSetFrameSkip.setOnClickListener {
            handleFrameSkipButtonClick()
        }
    }

    private fun handleCameraButtonClick(mainActivity: MainActivity) {
        if (hasRequiredPermissions(mainActivity)) {
            lifecycleScope.launch {
                viewModel.toggleStreaming(requireContext())
            }
        } else {
            showToast("카메라와 위치 권한이 필요합니다")
        }
    }

    private fun handleCaptureButtonClick(mainActivity: MainActivity) {
        if (mainActivity.isCameraPermissionGranted()) {
            lifecycleScope.launch {
                viewModel.fetchCameraData()
            }
        } else {
            showToast("카메라 권한이 필요합니다")
        }
    }

    private fun handleFrameSkipButtonClick() {
        val intervalText = binding.editTextFrameSkip.text.toString()
        val interval = intervalText.toIntOrNull()

        if (interval != null && interval in 2..15) {
            viewModel.setUserFrameSkipInterval(interval)
            showToast("Frame skip interval set to $interval")
        } else {
            showToast("Please enter a valid integer between 2 and 15")
        }
    }

    private fun hasRequiredPermissions(mainActivity: MainActivity): Boolean {
        return mainActivity.isCameraPermissionGranted() &&
                mainActivity.isLocationPermissionGranted() &&
                mainActivity.isBackgroundLocationPermissionGranted()
    }

    override fun onResume() {
        super.onResume()
        viewModel.startSensorStreaming()
        viewModel.updatePoolStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // ✅ ManagedBitmap 참조 해제 (전역 풀은 MainActivity에서 관리)
        currentManagedBitmap?.release()
        currentManagedBitmap = null

        // 성능 통계
        val total = frameSkipCount + successfulFrameCount
        if (total > 0) {
            val successRate = (successfulFrameCount.toFloat() / total * 100)
            val formatter = DecimalFormat("#.#")
            FragmentUtils.logEvent("HomeFragment", "INFO",
                "전역 풀 직접 사용 성공률: ${formatter.format(successRate)}% (성공: $successfulFrameCount, 스킵: $frameSkipCount)")
        }

        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor = null

        Log.d("HomeFragment", "✅ HomeFragment 정리 완료 (전역 풀 유지)")
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
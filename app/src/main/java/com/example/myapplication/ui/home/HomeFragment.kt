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
            // ✅ 새로운 ManagedBitmap 유효성 확인
            val newManagedBitmap = sensorData.managedBitmap
            if (newManagedBitmap == null || !newManagedBitmap.isValid()) {
                Log.w("HomeFragment", "⚠️ 무효한 ManagedBitmap: frameId=${sensorData.frameId}")
                // ❌ 무효한 프레임이라고 이전 프레임을 지우지 않음!
                return
            }

            Log.d("HomeFragment", "🎯 ManagedBitmap 직접 표시: frameId=${sensorData.frameId}, size=${newManagedBitmap.bitmap.width}x${newManagedBitmap.bitmap.height}")

            // UI 스레드에서 안전하게 업데이트
            binding.imageView.post {
                try {
                    // ✅ 새로운 프레임이 UI에 완전히 적용된 후에만 이전 프레임 해제
                    if (newManagedBitmap.isValid()) {
                        // 🎯 1단계: 새로운 비트맵을 UI에 먼저 설정
                        binding.imageView.setImageBitmap(newManagedBitmap.bitmap)

                        // 🎯 2단계: UI 업데이트가 성공한 후에만 이전 프레임 해제
                        val previousManagedBitmap = currentManagedBitmap
                        currentManagedBitmap = newManagedBitmap

                        // ✅ 사용 시간 업데이트 (Stale 방지)
                        currentManagedBitmap!!.updateLastAccess()

                        // 🎯 3단계: 이전 프레임을 안전하게 해제 (깜빡임 없음!)
                        previousManagedBitmap?.release()

                        successfulFrameCount++
                        frameSkipCount = 0

                        Log.d("HomeFragment", "✅ UI 업데이트 성공: frameId=${sensorData.frameId} (이전 프레임 안전 해제)")
                    } else {
                        Log.e("HomeFragment", "❌ UI 스레드에서 새로운 ManagedBitmap 무효화: frameId=${sensorData.frameId}")
                        handleUIUpdateFailure()
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "❌ UI 업데이트 예외: ${e.message}", e)
                    handleUIUpdateFailure()
                }
            }

        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ ManagedBitmap 처리 실패: ${e.message}", e)
            // ❌ 예외 상황에서도 이전 프레임을 지우지 않음 (깜빡임 방지)
        }
    }

    private fun clearCurrentDisplay() {
        currentManagedBitmap?.release()
        currentManagedBitmap = null
        binding.imageView.setImageBitmap(null)
        Log.d("HomeFragment", "🧹 UI 디스플레이 명시적 클리어 및 풀 반납 완료")
    }


    private fun handleUIUpdateFailure() {
        frameSkipCount++
        if (frameSkipCount > 30) {
            Log.w("HomeFragment", "🚨 지속적인 UI 업데이트 실패 - 전역 풀 복구 요청")
            requestPoolRecovery()
        }

        // 🎯 이전 프레임을 그대로 유지하여 깜빡임 방지
        Log.w("HomeFragment", "⚠️ UI 업데이트 실패 - 이전 프레임 유지 (스킵 카운트: $frameSkipCount)")
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

        // ✅ 스트리밍 중지 시에도 마지막 프레임 유지 (검은 화면 방지)
        if (!isStreaming) {
            // clearStreamingState() 호출하지 않음 - 마지막 프레임 유지!
            binding.overlayView.clear() // 바운딩박스만 클리어
            binding.inferenceTime.text = "Inference: 0ms"
            Log.d("HomeFragment", "🎯 스트리밍 중지 - 마지막 프레임 유지")
        }
    }


    private fun clearStreamingState() {
        // ✅ 이 함수는 완전한 종료시에만 호출 (Fragment 종료 등)
        binding.overlayView.clear()
        binding.inferenceTime.text = "Inference: 0ms"
        clearCurrentDisplay() // 여기서만 실제 클리어
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

                if (sensorData?.managedBitmap?.isValid() == true) {
                    Log.d("HomeFragment", "✅ 유효한 ManagedBitmap 수신: frameId=${sensorData.frameId}")

                    // ✅ 깜빡임 방지 - 다음 프레임 준비 후 이전 프레임 해제
                    displayManagedBitmapDirectly(sensorData)

                } else if (sensorData?.bitmap != null && !sensorData.bitmap.isRecycled) {
                    // 폴백: 기존 bitmap 방식 (하위 호환성) - 여기서도 깜빡임 방지
                    Log.w("HomeFragment", "⚠️ ManagedBitmap 없음, 기존 bitmap 사용: frameId=${sensorData.frameId}")

                    binding.imageView.post {
                        binding.imageView.setImageBitmap(sensorData.bitmap)
                        // 🎯 폴백에서도 이전 ManagedBitmap만 해제하고 UI는 유지
                        val previousManagedBitmap = currentManagedBitmap
                        currentManagedBitmap = null
                        previousManagedBitmap?.release()
                    }
                } else {
                    // ❌ null이나 무효한 프레임이어도 이전 프레임을 지우지 않음!
                    Log.w("HomeFragment", "⚠️ 무효한 프레임 수신 - 이전 프레임 유지 (깜빡임 방지)")
                    // clearCurrentDisplay() 호출하지 않음!
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ 프레임 Observer 처리 오류: ${e.message}", e)
                // ❌ 예외 상황에서도 이전 프레임 유지
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

        // ✅ Fragment 종료 시에만 마지막 ManagedBitmap 반납
        clearCurrentDisplay() // 완전 종료이므로 클리어 수행

        // 성능 통계
        val total = frameSkipCount + successfulFrameCount
        if (total > 0) {
            val successRate = (successfulFrameCount.toFloat() / total * 100)
            val formatter = DecimalFormat("#.#")
            FragmentUtils.logEvent("HomeFragment", "INFO",
                "깜빡임 방지 ManagedBitmap 성공률: ${formatter.format(successRate)}% (성공: $successfulFrameCount, 스킵: $frameSkipCount)")
        }

        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor = null

        Log.d("HomeFragment", "✅ HomeFragment 정리 완료 - 깜빡임 방지 적용됨")
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
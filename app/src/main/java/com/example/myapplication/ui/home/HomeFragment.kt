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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.MainActivity
import com.example.myapplication.databinding.FragmentHomeBinding
import com.example.myapplication.utils.ResourceMonitor
import com.example.myapplication.utils.SharedBitmap
import com.example.myapplication.utils.PoolHealthStatus // Import PoolHealthStatus
import com.example.myapplication.utils.HealthLevel // Import HealthLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.myapplication.utils.FileLogger

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
    // Assuming HomeViewModelFactory correctly initializes the ViewModel with dependencies
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            (requireActivity() as MainActivity).sensorCollector,
            (requireActivity() as MainActivity).homeRepository
        )
    }

    // 🎯 SharedBitmap 기반 컴포넌트들
    private var currentSharedBitmap: SharedBitmap? = null
    private var frameSkipCount = 0
    private var successfulFrameCount = 0
    private var resourceMonitor: ResourceMonitor? = null

    // 복구 관련 변수
    private var lastRecoveryTime = 0L
    private var surfaceDropRecoveryCount = 0

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
        Log.d("HomeFragment", "🎯 TrueZeroCopyBitmapPool 기반 초기화 완료")
    }

    private fun startHealthMonitoring() {
        // 🎯 UI Pool 모니터링을 TrueZeroCopyBitmapPool 모니터링으로 변경
        lifecycleScope.launch {
            while (isActive) {
                delay(5000) // 5초마다 체크
                try {
                    // HomeRepository를 통해 TrueZeroCopyBitmapPool 상태 체크
                    val health: PoolHealthStatus = (requireActivity() as MainActivity).homeRepository.getPoolHealthStatus()
                    // Update UI status text based on PoolHealthStatus
                    binding.poolStatusText.text = health.let {
                        "Pool: ${it.availableSlots}/${it.totalSlots} available, ${it.healthLevel}, refs=${it.totalReferences}" // Or use it.recommendation
                    }

                    // Check health level and trigger recovery if needed
                    if (health.healthLevel == HealthLevel.WARNING ||
                        health.healthLevel == HealthLevel.CRITICAL) {
                        FragmentUtils.logEvent("HomeFragment", "WARN", "TrueZeroCopyBitmapPool 건강성 문제 감지 - 복구 시도")
                        requestPoolRecovery()
                    }
                } catch (e: Exception) {
                    FragmentUtils.logEvent("HomeFragment", "ERROR", "Pool 모니터링 실패: ${e.message}", e)
                }
            }
        }
        // 🎯 15초 주기 TrueZeroCopyBitmapPool 정보 로깅
        lifecycleScope.launch {
            while (isActive) {
                delay(15000) // 15초마다 로깅
                try {
                    val fileLogger = FileLogger.getInstance(requireContext())
                    val poolReport = buildAdvancedPoolReport() // This function name is kept, but uses TrueZeroCopyBitmapPool methods
                    fileLogger.i("TrueZeroCopyBitmapPool", poolReport)
                    Log.d("HomeFragment", "📊 TrueZeroCopyBitmapPool 상태 로깅 완료")
                } catch (e: Exception) {
                    FragmentUtils.logEvent("HomeFragment", "ERROR", "TrueZeroCopyBitmapPool 로깅 실패: ${e.message}", e)
                }
            }
        }
    }

    // This function now uses TrueZeroCopyBitmapPool methods for status/detailed status
    private fun buildAdvancedPoolReport(): String {
        return buildString {
            appendLine("╔═══════════════════════════════════════════════════════════════╗")
            appendLine("║                  TrueZeroCopyBitmapPool 상태 리포트 (15초 주기)            ║")
            appendLine("╠═══════════════════════════════════════════════════════════════╣")
            appendLine("║ ⏰ 시간: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
            appendLine("║")
            try {
                // Use HomeRepository to get health status (assuming it calls pool.getPoolHealthStatus())
                val health: PoolHealthStatus = (requireActivity() as MainActivity).homeRepository.getPoolHealthStatus()
                appendLine("║ 🎯 TrueZeroCopyBitmapPool 상태:")
                appendLine("║   ├─ 건강성: ${health.healthLevel}")
                appendLine("║   ├─ 사용 가능: ${health.availableSlots}/${health.totalSlots}")
                appendLine("║   ├─ 총 참조: ${health.totalReferences}")
                appendLine("║   ├─ Stale 슬롯: ${health.staleSlots}")
                appendLine("║   └─ 권장사항: ${health.recommendation}")
                appendLine("║")
                // Use HomeRepository to get detailed status string (assuming it calls pool.getDetailedStatus())
                val detailedStatus: String = (requireActivity() as MainActivity).homeRepository.getPoolDetailedStatus()
                appendLine("║ 📊 상세 통계:")
                detailedStatus.lines().take(10).forEach { line -> // Limit lines for readability
                    if (line.isNotBlank()) {
                        appendLine("║   $line")
                    }
                }
            } catch (e: Exception) {
                appendLine("║   ❌ 상태 조회 실패: ${e.message}")
            }
            appendLine("║")
            appendLine("║ 🎨 UI 처리 통계:")
            appendLine("║   ├─ 성공한 프레임: ${successfulFrameCount}개")
            appendLine("║   ├─ 스킵된 프레임: ${frameSkipCount}개")
            appendLine("║   ├─ Surface 복구: ${surfaceDropRecoveryCount}회")
            appendLine("║   └─ 성공률: ${if (successfulFrameCount + frameSkipCount > 0)
                String.format("%.1f", (successfulFrameCount.toFloat() / (successfulFrameCount + frameSkipCount) * 100)) + "%"
            else "N/A"}")
            appendLine("║")
            appendLine("║ 🧠 메모리 사용량:")
            val resourceMonitor = ResourceMonitor.getInstance(requireContext())
            val memInfo = resourceMonitor.getAppMemoryInfo()
            appendLine("║   ├─ 앱 힙 사용률: ${String.format("%.1f", memInfo.heapUsagePercent)}%")
            appendLine("║   ├─ Native 힙: ${String.format("%.1f", memInfo.nativeHeapMB)} MB")
            appendLine("║   └─ 메모리 압박: ${memInfo.memoryPressureLevel}")
            appendLine("╚═══════════════════════════════════════════════════════════════╝")
        }
    }

    /**
     * 🎯 SharedBitmap을 UI에 안전하게 표시 - TrueZeroCopyBitmapPool 연동
     */
    private fun displaySharedBitmap(sensorData: com.example.myapplication.model.SensorData) {
        try {
            // 🛡️ 1단계: SensorData 검증
            if (sensorData.bitmap == null || sensorData.bitmap.isRecycled) {
                Log.w("HomeFragment", "⚠️ Invalid bitmap in SensorData: frameId=${sensorData.frameId}")
                return
            }
            // 🛡️ 2단계: 이전 SharedBitmap 안전 해제
            currentSharedBitmap?.let { oldShared ->
                try {
                    oldShared.release()
                    Log.d("HomeFragment", "📉 Previous SharedBitmap released")
                } catch (e: Exception) {
                    Log.w("HomeFragment", "⚠️ Previous SharedBitmap release failed: ${e.message}")
                }
            }
            // 🛡️ 3단계: HomeRepository를 통해 SharedBitmap 획득
            // Assuming HomeRepository handles the pool interaction correctly
            val sharedBitmap = (requireActivity() as MainActivity).homeRepository
                .acquireSharedBitmapForUI(sensorData.bitmap) // This logic depends on how HomeRepository implements this

            if (sharedBitmap?.isValid() == true) {
                // 🎯 4단계: UI 스레드에서 안전한 표시
                binding.imageView.post {
                    try {
                        val safeBitmap = sharedBitmap.getSafeBitmapForUI()
                        if (safeBitmap != null && !safeBitmap.isRecycled) {
                            binding.imageView.setImageBitmap(safeBitmap)
                            currentSharedBitmap = sharedBitmap
                            successfulFrameCount++
                            frameSkipCount = 0
                            Log.d("HomeFragment", "✅ SharedBitmap UI 표시 성공: frameId=${sensorData.frameId}")
                        } else {
                            Log.w("HomeFragment", "⚠️ SafeBitmap validation failed")
                            sharedBitmap.release()
                            handleDisplayFailure()
                        }
                    } catch (e: Exception) {
                        Log.e("HomeFragment", "❌ UI 표시 예외: ${e.message}", e)
                        sharedBitmap.release()
                        handleDisplayFailure()
                    }
                }
            } else {
                Log.w("HomeFragment", "⚠️ SharedBitmap 획득 실패 또는 무효")
                handleDisplayFailure()
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ SharedBitmap 표시 전체 실패: ${e.message}", e)
            handleDisplayFailure()
        }
    }

    private fun handleDisplayFailure() {
        frameSkipCount++
        binding.imageView.setImageBitmap(null)
        if (frameSkipCount > 30) { // 관대한 임계값
            Log.w("HomeFragment", "🚨 지속적인 표시 실패 - 자동 복구 트리거")
            requestPoolRecovery()
        }
    }

    /**
     * 🚀 HomeRepository를 통한 TrueZeroCopyBitmapPool 복구 요청
     */
    private fun requestPoolRecovery() {
        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastRecoveryTime < 3000) {
                Log.d("HomeFragment", "복구 요청 간격 제한 - 스킵")
                return
            }
            lastRecoveryTime = currentTime
            Log.w("HomeFragment", "🔧 TrueZeroCopyBitmapPool 복구 요청 시작")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 🎯 TrueZeroCopyBitmapPool 복구
                    // Assuming HomeRepository calls the appropriate pool recovery method (e.g., performEmergencyReset or autoRecover)
                    val recovered = (requireActivity() as MainActivity).homeRepository
                        .performEmergencyPoolRecovery() // Name might need adjustment based on HomeRepository
                    launch(Dispatchers.Main) {
                        if (recovered) {
                            // 현재 표시된 내용 정리
                            currentSharedBitmap?.release()
                            currentSharedBitmap = null
                            binding.imageView.setImageBitmap(null)
                            frameSkipCount = 0
                            successfulFrameCount = 0
                            showToast("TrueZeroCopyBitmapPool 복구 완료")
                            Log.d("HomeFragment", "✅ TrueZeroCopyBitmapPool 복구 성공")
                        } else {
                            showToast("TrueZeroCopyBitmapPool 복구 실패")
                            Log.e("HomeFragment", "❌ TrueZeroCopyBitmapPool 복구 실패")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "❌ TrueZeroCopyBitmapPool 복구 중 예외: ${e.message}", e)
                    launch(Dispatchers.Main) {
                        showToast("Pool 복구 중 오류: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ Pool 복구 요청 실패: ${e.message}", e)
        }
    }

    /**
     * 🔧 Observer 설정 - SharedBitmap 기반으로 수정
     */
    private fun setupCameraFrameObserver() {
        viewModel.cameraFrame.observe(viewLifecycleOwner) { sensorData ->
            try {
                if (sensorData?.bitmap != null && !sensorData.bitmap.isRecycled) {
                    displaySharedBitmap(sensorData)
                } else {
                    // 데이터가 없을 때 현재 표시 정리
                    currentSharedBitmap?.release()
                    currentSharedBitmap = null
                    binding.imageView.setImageBitmap(null)
                    Log.d("HomeFragment", "🧹 UI 표시 정리")
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ 프레임 Observer 오류: ${e.message}", e)
                handleDisplayFailure()
            }
        }
    }

    // 권한 관련 메서드들 (unchanged)
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

    // UI 업데이트 헬퍼 메서드들 (unchanged)
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
        currentSharedBitmap?.release()
        currentSharedBitmap = null
        binding.imageView.setImageBitmap(null)
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
        // Observe the pool status string (assuming viewModel.poolStatus emits a string from pool.getStatus())
        viewModel.poolStatus.observe(viewLifecycleOwner) { status ->
            binding.poolStatusText.text = status // This will now be the string from pool.getStatus() or formatted PoolHealthStatus
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
        // 🎯 TrueZeroCopyBitmapPool 복구 신호 Observer (Logic might be internal to ViewModel/Repository)
        // This observer might be triggered by the ViewModel when it detects issues or is signaled by the processor/pool.
        // If it's just a boolean flag, the logic below is fine, assuming the ViewModel sets it appropriately.
        viewModel.shouldRecoverUIPool.observe(viewLifecycleOwner) { shouldRecover ->
            if (shouldRecover) {
                surfaceDropRecoveryCount++
                Log.w("HomeFragment", "🚨 Surface FPS 드롭으로 인한 TrueZeroCopyBitmapPool 자동 복구 실행 ($surfaceDropRecoveryCount 회)")
                requestPoolRecovery()
                showToast("Surface FPS 드롭 감지 - TrueZeroCopyBitmapPool 자동 복구 완료")
                // Reset the flag in ViewModel if necessary (depends on implementation)
                // viewModel.resetRecoveryFlag()
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
            // Assuming HomeRepository calls pool.cleanup() or similar
            viewModel.forceCleanupBitmapPool()
            // TrueZeroCopyBitmapPool에서 UI 복구도 자동으로 처리됨 (if cleanup includes re-initialization)
            showToast("TrueZeroCopyBitmapPool 정리 완료")
        }
        binding.buttonPoolStatus.setOnClickListener {
            viewModel.updatePoolStatus() // This should trigger fetching the latest status from the pool
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
        viewModel.updatePoolStatus() // Refresh status on resume
    }

    /**
     * 🧹 강화된 정리 작업 - SharedBitmap 안전 해제
     */
    override fun onDestroyView() {
        super.onDestroyView()
        // SharedBitmap 안전 해제
        currentSharedBitmap?.let {
            try {
                it.release()
                Log.d("HomeFragment", "📉 Final SharedBitmap released")
            } catch (e: Exception) {
                Log.w("HomeFragment", "⚠️ Final SharedBitmap release failed: ${e.message}")
            }
        }
        currentSharedBitmap = null
        // UI 정리
        binding.imageView.setImageBitmap(null)
        // 성능 통계
        val total = frameSkipCount + successfulFrameCount
        if (total > 0) {
            val successRate = (successfulFrameCount.toFloat() / total * 100)
            Log.i("HomeFragment",
                "📊 TrueZeroCopyBitmapPool UI 표시 성공률: ${String.format("%.1f", successRate)}% " +
                        "(성공: $successfulFrameCount, 스킵: $frameSkipCount, 복구: $surfaceDropRecoveryCount)")
        }
        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
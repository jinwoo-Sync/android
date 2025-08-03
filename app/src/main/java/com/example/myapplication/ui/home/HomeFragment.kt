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
import com.example.myapplication.model.SensorData
import com.example.myapplication.utils.AdvancedTaggedBitmapPool
import com.example.myapplication.utils.BitmapPoolManager
import com.example.myapplication.utils.HealthLevel
import com.example.myapplication.utils.ManagedBitmap
import com.example.myapplication.utils.PoolHealthStatus
import com.example.myapplication.utils.ResourceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            // 🎯 센서 스트리밍을 늦게 시작 (UI 준비 후)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(500) // UI 준비 대기
                viewModel.startSensorStreaming()
                Log.d("HomeFragment", "🎯 지연된 센서 스트리밍 시작")
            }
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
            val newManagedBitmap = sensorData.managedBitmap
            if (newManagedBitmap == null || !newManagedBitmap.isValid()) {
                Log.w("HomeFragment", "⚠️ 무효한 ManagedBitmap: frameId=${sensorData.frameId}")
                return
            }

            // ✅ 추가 비트맵 검증
            val bitmap = newManagedBitmap.bitmap
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                Log.w("HomeFragment", "⚠️ 무효한 비트맵 상태: recycled=${bitmap.isRecycled}, size=${bitmap.width}x${bitmap.height}")
                return
            }

            Log.d("HomeFragment", "🎯 ManagedBitmap 직접 표시: frameId=${sensorData.frameId}, size=${bitmap.width}x${bitmap.height}")

            // ✅ UI 스레드에서 안전하게 업데이트 - 이중 검증
            binding.imageView.post {
                try {
                    // 🚨 UI 스레드에서 다시 한번 검증 (중요!)
                    if (newManagedBitmap.isValid() && !bitmap.isRecycled) {

                        // 🎯 1단계: 이전 프레임 먼저 해제 (UI 업데이트 전)
                        val previousManagedBitmap = currentManagedBitmap
                        currentManagedBitmap = newManagedBitmap

                        // 🎯 2단계: 새로운 비트맵을 UI에 설정
                        binding.imageView.setImageBitmap(bitmap)

                        // 🎯 3단계: 이전 프레임 해제 (UI 업데이트 후)
                        previousManagedBitmap?.release()

                        // ✅ 사용 시간 업데이트 (Stale 방지)
                        currentManagedBitmap!!.updateLastAccess()

                        successfulFrameCount++
                        frameSkipCount = 0

                        Log.d("HomeFragment", "✅ UI 업데이트 성공: frameId=${sensorData.frameId}")
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
            Log.e("HomeFragment", "❌ ManagedBitmap 처리 실패: ${e.message}", e)
        }
    }

    private fun clearCurrentDisplay() {
        // ✅ UI 복사본은 recycle, ManagedBitmap은 release
        binding.imageView.setImageBitmap(null)
        currentManagedBitmap?.release()
        currentManagedBitmap = null
        Log.d("HomeFragment", "🧹 UI 디스플레이 안전 클리어 완료")
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
                //  기존 프레임 즉시 해제 - 최신것만 처리
                currentManagedBitmap?.release()
                currentManagedBitmap = null

                if (sensorData?.managedBitmap?.isValid() == true) {
                    //  Surface 병목 상관없이 최신 프레임만 즉시 표시
                    displayLatestFrameOnly(sensorData)
                } else {
                    handleUIUpdateFailure()
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", " 최신 프레임 Observer 오류: ${e.message}", e)
                sensorData?.managedBitmap?.release()
            }
        }
    }

    private fun displayLatestFrameOnly(sensorData: SensorData) {
        try {
            val newManagedBitmap = sensorData.managedBitmap!!

            if (!newManagedBitmap.isValid()) {
                Log.w("HomeFragment", " 최신 프레임 무효: frameId=${sensorData.frameId}")
                newManagedBitmap.release()
                return
            }

            val bitmap = newManagedBitmap.bitmap
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                Log.w("HomeFragment", " 최신 비트맵 상태 불량: frameId=${sensorData.frameId}")
                newManagedBitmap.release()
                return
            }

            Log.d("HomeFragment", " 최신 프레임 즉시 표시: frameId=${sensorData.frameId}")

            // UI 스레드에서 즉시 업데이트 (큐잉 없음)
            binding.imageView.post {
                try {
                    if (newManagedBitmap.isValid() && !bitmap.isRecycled) {
                        // 안전한 UI 복사본 생성 (최신 프레임만)
                        val safeCopy = newManagedBitmap.createSafeCopyForUI()

                        if (safeCopy != null && !safeCopy.isRecycled) {
                            binding.imageView.setImageBitmap(safeCopy)
                            successfulFrameCount++
                            frameSkipCount = 0
                            Log.d("HomeFragment", "✅ 최신 프레임 UI 표시 완료: frameId=${sensorData.frameId}")
                        } else {
                            handleUIUpdateFailure()
                        }

                        // 원본 ManagedBitmap 즉시 해제 (핵심!)
                        newManagedBitmap.release()

                    } else {
                        Log.e("HomeFragment", " UI 스레드에서 최신 비트맵 재검증 실패: frameId=${sensorData.frameId}")
                        newManagedBitmap.release()
                        handleUIUpdateFailure()
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", " 최신 프레임 UI 업데이트 예외: ${e.message}", e)
                    newManagedBitmap.release()
                    handleUIUpdateFailure()
                }
            }

        } catch (e: Exception) {
            Log.e("HomeFragment", " 최신 프레임 처리 실패: ${e.message}", e)
            sensorData.managedBitmap?.release()
        }
    }

    private fun setupSensorObservers() {
        //  GPS 데이터 Observer 강화
        viewModel.gpsData.observe(viewLifecycleOwner) { data ->
            Log.d("HomeFragment", " GPS Observer 호출: $data")
            try {
                binding.gpsLogText.text = data
                Log.d("HomeFragment", " GPS UI 텍스트 업데이트 완료")
            } catch (e: Exception) {
                Log.e("HomeFragment", " GPS UI 업데이트 실패: ${e.message}", e)
            }
        }

        //  GNSS 데이터 Observer 강화
        viewModel.gnssData.observe(viewLifecycleOwner) { data ->
            Log.d("HomeFragment", " GNSS Observer 호출: $data")
            try {
                val displayText = if (data == "GNSS: 대기 중") {
                    "GNSS 데이터가 수신되지 않습니다."
                } else {
                    data
                }
                binding.gnssLogText.text = displayText
                Log.d("HomeFragment", " GNSS UI 텍스트 업데이트 완료: $displayText")
            } catch (e: Exception) {
                Log.e("HomeFragment", " GNSS UI 업데이트 실패: ${e.message}", e)
            }
        }

        //  IMU 데이터 Observer 강화
        viewModel.imuData.observe(viewLifecycleOwner) { data ->
            Log.d("HomeFragment", " IMU Observer 호출: $data")
            try {
                binding.imuLogText.text = data
                Log.d("HomeFragment", " IMU UI 텍스트 업데이트 완료")
            } catch (e: Exception) {
                Log.e("HomeFragment", " IMU UI 업데이트 실패: ${e.message}", e)
            }
        }
    }

    private fun setupStatusObservers() {
        //  전역 Advanced Tagged Pool 상태 표시
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
        Log.d("HomeFragment", "🎯 onResume - 센서 데이터 Observer 재활성화")

        // 🎯 onResume에서 센서 재시작 (중요!)
        viewModel.startSensorStreaming()
        viewModel.updatePoolStatus()

        // 🎯 UI 강제 새로고침
        viewLifecycleOwner.lifecycleScope.launch {
            delay(100)
            // 현재 LiveData 값들을 강제로 다시 observe
            viewModel.gpsData.value?.let {
                binding.gpsLogText.text = it
                Log.d("HomeFragment", "🎯 onResume GPS 강제 업데이트: $it")
            }
            viewModel.gnssData.value?.let {
                binding.gnssLogText.text = it
                Log.d("HomeFragment", "🎯 onResume GNSS 강제 업데이트: $it")
            }
            viewModel.imuData.value?.let {
                binding.imuLogText.text = it
                Log.d("HomeFragment", "🎯 onResume IMU 강제 업데이트: $it")
            }
        }
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
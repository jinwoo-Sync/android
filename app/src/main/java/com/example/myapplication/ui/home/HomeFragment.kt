package com.example.myapplication.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.opengl.GLSurfaceView
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
import com.example.myapplication.model.SensorData
import com.example.myapplication.utils.BitmapPoolManager
import com.example.myapplication.utils.HealthLevel
import com.example.myapplication.utils.ManagedBitmap
import com.example.myapplication.utils.PoolHealthStatus
import com.example.myapplication.Logsystem.ResourceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import android.os.Handler
import android.os.Looper
import android.view.Choreographer

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

    // 전역 BitmapPoolManager 참조
    private lateinit var bitmapPoolManager: BitmapPoolManager

    //  GLSurfaceView 관련 변수들
    private lateinit var glRenderer: CameraGLRenderer
    private var isGLReady = false

    // 현재 UI에 표시 중인 ManagedBitmap 참조
    private var currentManagedBitmap: ManagedBitmap? = null
    private var lastGoodUiFrame: HomeViewModel.UiFrame? = null

    private var frameSkipCount = 0
    private var successfulFrameCount = 0
    private var resourceMonitor: ResourceMonitor? = null

    // 복구 관련 변수
    private var lastRecoveryTime = 0L
    
    // 1fps 배치 UI 업데이터 (단일 Job으로 3개 TextView 동시 갱신)
    @Volatile private var latestGpsText: String? = null
    @Volatile private var latestGnssText: String? = null
    @Volatile private var latestImuText: String? = null
    private var uiBatchJob: Job? = null
    private var textUpdatesEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        try {
            initializeComponents()
            setupGLSurfaceView()  // GLSurfaceView 설정
            checkGpsAndPermissions()
            setupObservers()
            setupClickListeners()

            // 센서 스트리밍을 늦게 시작 (UI 준비 후)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(500) // UI 준비 대기
                viewModel.startSensorStreaming()
                Log.d("HomeFragment", " 지연된 센서 스트리밍 시작")
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
        bitmapPoolManager = BitmapPoolManager.getInstance(requireContext())

        FragmentUtils.logEvent("HomeFragment", "DEBUG", "✅ 전역 BitmapPoolManager 참조 완료")
        Log.d("HomeFragment", " 초기 풀 상태: ${bitmapPoolManager.advancedTaggedBitmapPool.getStatus()}")
    }

    // GLSurfaceView 설정 (완전한 OpenGL 사용)
    private fun setupGLSurfaceView() {
        try {
            // OpenGL ES 2.0 설정
            binding.glSurfaceView.setEGLContextClientVersion(2)

            // 렌더러 생성
            glRenderer = CameraGLRenderer()
            binding.glSurfaceView.setRenderer(glRenderer)

            // 렌더 모드 설정 (필요할 때만 렌더링)
            binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

            isGLReady = true

            Log.d("HomeFragment", " GLSurfaceView 설정 완료 - 완전한 OpenGL 사용")
        } catch (e: Exception) {
            Log.e("HomeFragment", "GLSurfaceView 설정 실패: ${e.message}", e)
            isGLReady = false
        }
    }

    private fun startHealthMonitoring() {
        lifecycleScope.launch {
            while (isActive) {
                delay(5000)

                val healthStatus: PoolHealthStatus = bitmapPoolManager.advancedTaggedBitmapPool.getPoolHealthStatus()
                if (healthStatus.healthLevel != HealthLevel.HEALTHY) {
                    FragmentUtils.logEvent("HomeFragment", "WARN", "전역 풀 건강성 문제 감지: ${healthStatus.healthLevel}")

                    if (healthStatus.healthLevel == HealthLevel.CRITICAL) {
                        requestPoolRecovery()
                    }
                }

                binding.poolStatusText.text = bitmapPoolManager.advancedTaggedBitmapPool.getStatus()
            }
        }
    }

    private fun requestPoolRecovery() {
        try {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastRecoveryTime < 5000) {
                FragmentUtils.logEvent("HomeFragment", "WARN", "복구 시도 너무 빈번 - 스킵")
                return
            }
            lastRecoveryTime = currentTime

            FragmentUtils.logEvent("HomeFragment", "WARN", "🔧 전역 풀 복구 요청")
            viewModel.forceCleanupBitmapPool()
            showToast("전역 풀 복구 완료")

        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "전역 풀 복구 중 예외: ${e.message}", e)
        }
    }

    /**
     *  핵심 함수: GLSurfaceView를 통한 순수 OpenGL 렌더링
     * - Surface Compositor 완전 우회
     * - GPU에서 직접 처리
     * - 10분 후에도 FPS 유지
     */
    private fun displayLatestFrameViaGLSurfaceView(sensorData: SensorData) {
        try {
            if (!isGLReady) {
                Log.w("HomeFragment", " GLSurfaceView 준비되지 않음 - 즉시 해제")
                sensorData.managedBitmap?.release()
                return
            }

            val managedBitmap = sensorData.managedBitmap
            if (managedBitmap == null || !managedBitmap.isValid()) {
                Log.w("HomeFragment", " 무효한 ManagedBitmap: frameId=${sensorData.frameId}")
                managedBitmap?.release()
                return
            }

            val bitmap = managedBitmap.bitmap
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                Log.w("HomeFragment", " 무효한 비트맵 상태: recycled=${bitmap.isRecycled}, size=${bitmap.width}x${bitmap.height}")
                managedBitmap.release()
                return
            }

            Log.d("HomeFragment", " GLSurfaceView 순수 OpenGL 렌더링 시작: frameId=${sensorData.frameId}, size=${bitmap.width}x${bitmap.height}")

            try {
                //  이전 프레임 즉시 해제
                currentManagedBitmap?.release()
                currentManagedBitmap = managedBitmap

                //  OpenGL 렌더러에 비트맵 전달
                glRenderer.updateBitmap(bitmap)

                //  GLSurfaceView 렌더링 요청 (GPU에서 직접 처리)
                binding.glSurfaceView.requestRender()

                // 사용 시간 업데이트
                managedBitmap.updateLastAccess()

                successfulFrameCount++
                frameSkipCount = 0

                Log.d("HomeFragment", "✅ GLSurfaceView 순수 OpenGL 렌더링 완료: frameId=${sensorData.frameId} - Surface 우회!")

            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ GLSurfaceView 렌더링 실패 - 즉시 해제: ${e.message}", e)
                managedBitmap.release()
                currentManagedBitmap = null
                handleUIUpdateFailure()
            }

        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ GLSurfaceView 처리 전체 실패 - 즉시 해제: ${e.message}", e)
            sensorData.managedBitmap?.release()
            currentManagedBitmap = null
        }
    }

    private fun clearCurrentDisplay() {
        // GLSurfaceView 클리어
        glRenderer.updateBitmap(null)
        binding.glSurfaceView.requestRender()

        currentManagedBitmap?.release()
        currentManagedBitmap = null
        Log.d("HomeFragment", " GLSurfaceView 디스플레이 안전 클리어 완료")
    }

    private fun handleUIUpdateFailure() {
        frameSkipCount++
        if (frameSkipCount > 30) {
            Log.w("HomeFragment", " 지속적인 GLSurfaceView 업데이트 실패 - 전역 풀 복구 요청")
            requestPoolRecovery()
        }

        Log.w("HomeFragment", "️ GLSurfaceView 업데이트 실패 - 이전 프레임 유지 (스킵 카운트: $frameSkipCount)")
    }

    // 권한 관련 메서드들 (기존과 동일)
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
            binding.overlayView.clear()
            binding.inferenceTime.text = "Inference: 0ms"
            Log.d("HomeFragment", " 스트리밍 중지 - GLSurfaceView 마지막 프레임 유지")
        }
    }

    private fun clearStreamingState() {
        binding.overlayView.clear()
        binding.inferenceTime.text = "Inference: 0ms"
        clearCurrentDisplay()
    }

    private fun resetSensorDisplays() {
        // 최신 데이터 초기화
        latestGpsText = null
        latestGnssText = null
        latestImuText = null
        
        binding.gpsLogText.text = "GPS: 대기 중"
        binding.gnssLogText.text = "GNSS: 대기 중"
        binding.imuLogText.text = "IMU: 대기 중"
    }
    
    /**
     * 1fps 배치 UI 업데이터 시작 (onStart에서 호출)
     */
    private fun startUiBatchUpdater() {
        stopUiBatchUpdater()
        uiBatchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && textUpdatesEnabled) {
                // 백그라운드에서 스냅샷 (최신값 캡처)
                val gps = latestGpsText
                val gnss = latestGnssText
                val imu = latestImuText

                // 단일 UI 커밋으로 3개 TextView 동시 갱신
                withContext(Dispatchers.Main.immediate) {
                    gps?.let { binding.gpsLogText.text = it }
                    gnss?.let { binding.gnssLogText.text = it }
                    imu?.let { binding.imuLogText.text = it }
                }

                delay(1000L) // 1fps 고정
            }
        }
    }

    /**
     * 1fps 배치 UI 업데이터 중지 (onStop에서 호출)
     */
    private fun stopUiBatchUpdater() {
        uiBatchJob?.cancel()
        uiBatchJob = null
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
        viewModel.cameraFrame.observe(viewLifecycleOwner) { uiFrame ->
            try {
                renderSafeFrame(uiFrame)
            } catch (e: Exception) {
                Log.e("HomeFragment", "Frame observer 오류: ${e.message}", e)
                uiFrame?.release?.let { it() }
            }
        }
    }
    
    /**
     * 마지막 정상 프레임 유지로 깜빡임 방지
     */
    private fun renderSafeFrame(uiFrame: HomeViewModel.UiFrame?) {
        if (uiFrame != null) {
            // 이전 프레임 정리 (다음 프레임에 예약)
            lastGoodUiFrame?.let { oldFrame ->
                Choreographer.getInstance().postFrameCallback {
                    oldFrame.release()
                }
            }
            lastGoodUiFrame = uiFrame
            
            // GLSurfaceView에 렌더링
            if (::glRenderer.isInitialized && isGLReady) {
                displayFrameViaGL(uiFrame)
            }
        } else {
            // null일 때 검은 화면 방지 - 마지막 프레임 유지
            // 아무것도 하지 않음 (현재 이미지 유지)
        }
    }
    
    /**
     * UiFrame을 GL에 안전하게 렌더링
     */
    private fun displayFrameViaGL(uiFrame: HomeViewModel.UiFrame) {
        try {
            if (!uiFrame.bitmap.isRecycled && uiFrame.bitmap.width > 0) {
                // GL 렌더링
                glRenderer.updateBitmap(uiFrame.bitmap)
                binding.glSurfaceView.requestRender()
                
                successfulFrameCount++
                Log.d("HomeFragment", "GL 렌더링 성공: frameId=${uiFrame.frameId}")
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "GL 렌더링 실패: ${e.message}", e)
            frameSkipCount++
        }
    }

    private fun setupSensorObservers() {
        // GPS 데이터 수신 - 최신값만 보존 (1fps 배처가 주기적으로 처리)
        viewModel.gpsData.observe(viewLifecycleOwner) { data ->
            if (textUpdatesEnabled) {
                latestGpsText = data
            }
        }

        // GNSS 데이터 수신 - 최신값만 보존 (1fps 배처가 주기적으로 처리)
        viewModel.gnssData.observe(viewLifecycleOwner) { data ->
            if (textUpdatesEnabled) {
                val displayText = if (data == "GNSS: 대기 중") {
                    "GNSS 데이터가 수신되지 않습니다."
                } else {
                    data
                }
                latestGnssText = displayText
            }
        }

        // IMU 데이터 수신 - 최신값만 보존 (1fps 배처가 주기적으로 처리)
        viewModel.imuData.observe(viewLifecycleOwner) { data ->
            if (textUpdatesEnabled) {
                latestImuText = data
            }
        }
    }

    private fun setupStatusObservers() {
        viewModel.poolStatus.observe(viewLifecycleOwner) { poolStatus ->
            binding.poolStatusText.text = poolStatus
        }

        viewModel.boundingBoxes.observe(viewLifecycleOwner) { boundingBoxes ->
            if (::glRenderer.isInitialized) {
                glRenderer.updateBoundingBoxes(boundingBoxes)
                binding.glSurfaceView.requestRender()
                Log.d("HomeFragment", "바운딩 박스를 GLRenderer로 전달: ${boundingBoxes.size}개")
            }

            // OverlayView는 클리어 (더 이상 사용하지 않음)
            binding.overlayView.clear()
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

        viewModel.shouldRecoverUIPool.observe(viewLifecycleOwner) { shouldRecover ->
            if (shouldRecover) {
                Log.w("HomeFragment", " ViewModel에서 전역 풀 복구 신호 수신")
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

        binding.buttonPauseSensors.setOnClickListener {
            viewModel.pauseSensors()
            showToast("IMU 센서 일시정지")
        }

        binding.buttonResumeSensors.setOnClickListener {
            viewModel.resumeSensors()
            showToast("IMU 센서 재개")
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

    override fun onStart() {
        super.onStart()
        startUiBatchUpdater()
        Log.d("HomeFragment", "onStart - 1fps 배치 UI 업데이터 시작")
    }

    override fun onStop() {
        super.onStop()
        stopUiBatchUpdater()
        Log.d("HomeFragment", "onStop - 1fps 배치 UI 업데이터 중지")
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()  //  GLSurfaceView Resume

        Log.d("HomeFragment", " onResume - GLSurfaceView 재활성화")
        viewModel.startSensorStreaming()
        viewModel.updatePoolStatus()
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()  //  GLSurfaceView Pause
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // 1fps 배치 UI 업데이터 정리
        stopUiBatchUpdater()
        
        // GLSurfaceView 정리
        clearCurrentDisplay()
        
        // 마지막 UiFrame 정리
        lastGoodUiFrame?.release?.let { it() }
        lastGoodUiFrame = null

        // 성능 통계
        val total = frameSkipCount + successfulFrameCount
        if (total > 0) {
            val successRate = (successfulFrameCount.toFloat() / total * 100)
            val formatter = DecimalFormat("#.#")
            FragmentUtils.logEvent("HomeFragment", "INFO",
                " GLSurfaceView 순수 OpenGL 렌더링 성공률: ${formatter.format(successRate)}% (성공: $successfulFrameCount, 스킵: $frameSkipCount)")
        }

        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor = null

        Log.d("HomeFragment", "✅ HomeFragment 정리 완료 - GLSurfaceView 순수 OpenGL 적용됨")
    }

    /**
     * TextView 업데이트 일시정지
     */
    fun pauseTextUpdates() {
        textUpdatesEnabled = false
        Log.d("HomeFragment", "TextView 업데이트 일시정지")
    }
    
    /**
     * TextView 업데이트 재개
     */
    fun resumeTextUpdates() {
        textUpdatesEnabled = true
        Log.d("HomeFragment", "TextView 업데이트 재개")
    }
    
    /**
     * FPS 드랍 시 응급 복구
     */
    fun onLowFpsDetected(fps: Float) {
        if (fps < 5) {
            Log.w("HomeFragment", "FPS 5 이하 감지: ${fps}fps - 배치 TextView 업데이트 중지")
            pauseTextUpdates()
            
            // requestLayout 및 GC 호출로 복구
            lifecycleScope.launch(Dispatchers.Main) {
                binding.root.requestLayout()
                delay(100)
                System.gc()
                delay(500)
                
                // 복구 후 재개
                resumeTextUpdates()
                Log.d("HomeFragment", "FPS 복구 루틴 완료 - 1fps 배치 TextView 업데이트 재개")
            }
        }
    }
    
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
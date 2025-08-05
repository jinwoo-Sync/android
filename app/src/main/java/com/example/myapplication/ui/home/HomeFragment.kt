package com.example.myapplication.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.opengl.GLES20
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
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentHomeBinding
import com.example.myapplication.model.SensorData
import com.example.myapplication.utils.BitmapPoolManager
import com.example.myapplication.utils.HealthLevel
import com.example.myapplication.utils.ManagedBitmap
import com.example.myapplication.utils.PoolHealthStatus
import com.example.myapplication.utils.ResourceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // 전역 BitmapPoolManager 참조
    private lateinit var bitmapPoolManager: BitmapPoolManager

    //  GLSurfaceView 관련 변수들
    private lateinit var glRenderer: CameraGLRenderer
    private var isGLReady = false

    // 현재 UI에 표시 중인 ManagedBitmap 참조
    private var currentManagedBitmap: ManagedBitmap? = null
    private var previousManagedBitmap: ManagedBitmap? = null

    private var frameSkipCount = 0
    private var successfulFrameCount = 0
    private var resourceMonitor: ResourceMonitor? = null

    // 복구 관련 변수
    private var lastRecoveryTime = 0L

    // 🎯 GPU 충돌 방지 변수들
    private val maxConsecutiveDrops = 3
    private var consecutiveDrops = 0
    private var lastRenderTime = 0L
    private val minRenderInterval = 16L // 60fps 기준 최소 간격

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        try {
            initializeComponents()
            setupGLSurfaceView()  // 🎯 수정된 GLSurfaceView 설정
            checkGpsAndPermissions()
            setupObservers()
            setupClickListeners()

            // 센서 스트리밍을 늦게 시작 (UI 준비 후)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(500) // UI 준비 대기
                viewModel.startSensorStreaming()
                Log.d("HomeFragment", "✅ 지연된 센서 스트리밍 시작")
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

        FragmentUtils.logEvent("HomeFragment", "DEBUG", " 전역 BitmapPoolManager 참조 완료")
        Log.d("HomeFragment", " 초기 풀 상태: ${bitmapPoolManager.advancedTaggedBitmapPool.getStatus()}")
    }

    //  GLSurfaceView 설정 (연속 렌더링 + 충돌 방지)
    private fun setupGLSurfaceView() {
        try {
            // OpenGL ES 2.0 설정
            binding.glSurfaceView.setEGLContextClientVersion(2)

            // 렌더러 생성
            glRenderer = CameraGLRenderer()
            binding.glSurfaceView.setRenderer(glRenderer)

            // 해결책 1: 연속 렌더링 모드로 변경 (FPS 0% 구간 방지)
            binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
           // binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

            isGLReady = true

            Log.d("HomeFragment", " GLSurfaceView 연속 렌더링 모드 설정 완료 - GPU 충돌 방지")
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

                // GPU 상태도 주기적으로 체크
                //checkGpuState("주기_모니터링")
            }
        }
    }

/*    *//**
     * GPU 상태 확인 (GLSurfaceView 컨텍스트 사용)
     *//*
    private fun checkGpuState(context: String) {
        if (!isOpenGlInitialized || !isGLReady) {
            Log.w("HomeFragment", "GPU 상태 확인 불가 - OpenGL 미초기화 ($context)")
            return
        }

        try {
            binding.glSurfaceView.queueEvent {
                try {
                    val glRenderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER) ?: "Unknown"
                    val glVendor = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR) ?: "Unknown"
                    val glVersion = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION) ?: "Unknown"

                    Log.w("HomeFragment", "GPU 상태 - $context")
                    Log.w("HomeFragment", "GPU 렌더러: $glRenderer")
                    Log.w("HomeFragment", "GPU 벤더: $glVendor")
                    Log.w("HomeFragment", "GPU 버전: $glVersion")

                    // OpenGL 에러 체크
                    val glError = android.opengl.GLES20.glGetError()
                    if (glError != android.opengl.GLES20.GL_NO_ERROR) {
                        Log.e("HomeFragment", "OpenGL 에러 감지: $glError")
                    }

                    // Native 메모리로 GPU 사용량 추정
                    val appMemory = resourceMonitor?.getAppMemoryInfo()
                    if (appMemory != null) {
                        Log.w("HomeFragment", "Native 메모리 (GPU 포함): ${String.format("%.1f", appMemory.nativeHeapMB)} MB")

                        if (appMemory.nativeHeapMB > 250) {
                            Log.e("HomeFragment", "Native 메모리 과다 사용 - GPU 메모리 누수 의심")
                        }
                    }

                } catch (e: Exception) {
                    Log.e("HomeFragment", "GPU 상태 로깅 실패: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "GPU 상태 확인 예외: ${e.message}", e)
        }
    }*/

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
     * 해결책 3: GPU 충돌 방지가 적용된 GLSurfaceView 렌더링
     * - YOLO 실행 중 체크
     * - 연속 드롭 제한
     * - 최소 렌더링 간격 보장
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

            val currentTime = System.currentTimeMillis()
            val frameId = sensorData.frameId

            // GPU 충돌 방지 로직 (기존과 동일)
            val isGpuBusy = viewModel.isDetectionRunning()
            val tooFastRender = (currentTime - lastRenderTime) < minRenderInterval

            if (isGpuBusy && consecutiveDrops < maxConsecutiveDrops) {
                Log.w("HomeFragment", "️ GPU 사용 중 - 렌더링 지연: frameId=$frameId, drops=$consecutiveDrops")
                managedBitmap.release()
                consecutiveDrops++
                handleUIUpdateFailure()
                return
            }

            if (tooFastRender && consecutiveDrops < maxConsecutiveDrops) {
                Log.d("HomeFragment", "️ 렌더링 속도 제한: frameId=$frameId")
                managedBitmap.release()
                consecutiveDrops++
                return
            }

            if (consecutiveDrops >= maxConsecutiveDrops) {
                Log.w("HomeFragment", " 강제 렌더링 시작: 연속 드롭 ${consecutiveDrops}회, frameId=$frameId")
            }

            Log.d("HomeFragment", " GLSurfaceView 실시간 렌더링 시작: frameId=$frameId, size=${bitmap.width}x${bitmap.height}")

            try {
                // GPU 추적 시작 (비동기, 렌더링에 영향 없음)
                trackUIRenderingStartAsync(frameId)

                // 프레임 교체 로직 수정 - 안전한 프레임 전환
                // 1. 이전 프레임을 임시 보관 (즉시 해제하지 않음)
                previousManagedBitmap = currentManagedBitmap

                // 2. 새 프레임을 현재 프레임으로 설정
                currentManagedBitmap = managedBitmap

                // 3. OpenGL 렌더러에 새 비트맵 전달
                glRenderer.updateBitmap(bitmap)

                // 4. 실시간 렌더링 강제 요청
                binding.glSurfaceView.requestRender()

                // 5. 새 프레임 사용 시간 업데이트
                managedBitmap.updateLastAccess()

                // 6. 이전 프레임을 지연 해제 (렌더링 완료 후)
                previousManagedBitmap?.let { prevBitmap ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        delay(33) // 약 2프레임 대기 (30fps 기준)
                        try {
                            prevBitmap.release()
                            Log.d("HomeFragment", "이전 프레임 지연 해제: ${prevBitmap.tag}")
                        } catch (e: Exception) {
                            Log.w("HomeFragment", "이전 프레임 해제 실패: ${e.message}")
                        }
                    }
                }
                previousManagedBitmap = null

                // 성공 통계 업데이트
                successfulFrameCount++
                consecutiveDrops = 0 // 성공 시 드롭 카운터 리셋
                frameSkipCount = 0
                lastRenderTime = currentTime

                // 🎮 GPU 추적 완료 (비동기, 렌더링에 영향 없음)
                trackUIRenderingEndAsync(frameId)

                Log.d("HomeFragment", "✅ GLSurfaceView 실시간 렌더링 완료: frameId=$frameId")

            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ GLSurfaceView 렌더링 실패 - 즉시 해제: ${e.message}", e)
                managedBitmap.release()
                currentManagedBitmap = null
                consecutiveDrops++
                handleUIUpdateFailure()
            }

        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ GLSurfaceView 처리 전체 실패 - 즉시 해제: ${e.message}", e)
            sensorData.managedBitmap?.release()
            currentManagedBitmap = null
            consecutiveDrops++
        }
    }

    /**
     * 🎮 UI 렌더링 GPU 추적 시작 - 완전 비동기 처리
     */
    private fun trackUIRenderingStartAsync(frameId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mainActivity = requireActivity() as? MainActivity
                val gpuMonitor = mainActivity?.getGpuMonitor()

                gpuMonitor?.trackUIRenderStart(frameId)
            } catch (e: Exception) {
                // 에러가 나도 로그만 남기고 UI에는 영향 없음
                Log.w("HomeFragment", "GPU 추적 실패 (무시됨): ${e.message}")
            }
        }
    }

    /**
     * 🎮 UI 렌더링 GPU 추적 완료 - 완전 비동기 처리
     */
    private fun trackUIRenderingEndAsync(frameId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mainActivity = requireActivity() as? MainActivity
                val gpuMonitor = mainActivity?.getGpuMonitor()

                gpuMonitor?.trackUIRenderEnd(frameId)
            } catch (e: Exception) {
                // 에러가 나도 로그만 남기고 UI에는 영향 없음
                Log.w("HomeFragment", "GPU 추적 완료 실패 (무시됨): ${e.message}")
            }
        }
    }

    private fun clearCurrentDisplay() {
        // GLSurfaceView 클리어 (null 비트맵으로 클리어)
        glRenderer.updateBitmap(null)
        binding.glSurfaceView.requestRender()

        //
        currentManagedBitmap?.release()
        previousManagedBitmap?.release()

        currentManagedBitmap = null
        previousManagedBitmap = null
        consecutiveDrops = 0

        Log.d("HomeFragment", "🗑️ GLSurfaceView 디스플레이 안전 클리어 완료")
    }

    private fun handleUIUpdateFailure() {
        frameSkipCount++
        if (frameSkipCount > 30) {
            Log.w("HomeFragment", "⚠️ 지속적인 GLSurfaceView 업데이트 실패 - 전역 풀 복구 요청")
            requestPoolRecovery()
        }

        Log.w("HomeFragment", "⚠️ GLSurfaceView 업데이트 실패 - 이전 프레임 유지 (스킵 카운트: $frameSkipCount)")
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
            Log.d("HomeFragment", "🎯 스트리밍 중지 - GLSurfaceView 연속 렌더링 유지")
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

                if (sensorData?.managedBitmap?.isValid() == true) {
                    //  🎯 GPU 충돌 방지가 적용된 GLSurfaceView 렌더링
                    displayLatestFrameViaGLSurfaceView(sensorData)
                } else {
                    sensorData?.managedBitmap?.release()
                    handleUIUpdateFailure()
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ GLSurfaceView Observer 오류: ${e.message}", e)
                sensorData?.managedBitmap?.release()
                currentManagedBitmap?.release()
                currentManagedBitmap = null
            }
        }
    }

    private fun setupSensorObservers() {
        viewModel.gpsData.observe(viewLifecycleOwner) { data ->
            binding.gpsLogText.text = data
        }

        viewModel.gnssData.observe(viewLifecycleOwner) { data ->
            val displayText = if (data == "GNSS: 대기 중") {
                "GNSS 데이터가 수신되지 않습니다."
            } else {
                data
            }
            binding.gnssLogText.text = displayText
        }

        viewModel.imuData.observe(viewLifecycleOwner) { data ->
            binding.imuLogText.text = data
        }
    }

    private fun setupStatusObservers() {
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

        viewModel.shouldRecoverUIPool.observe(viewLifecycleOwner) { shouldRecover ->
            if (shouldRecover) {
                Log.w("HomeFragment", "⚠️ ViewModel에서 전역 풀 복구 신호 수신")
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
    }


/*
     * GPU 모니터링 전용 GLSurfaceView 설정

    private fun setupGpuMonitoringGLSurfaceView() {
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(2) // OpenGL ES 2.0 사용
        glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                isOpenGlInitialized = true
                fileLogger.i("MainActivity", "GPU 모니터링용 OpenGL 컨텍스트 초기화 완료")

                // 초기 GPU 상태 로깅
                logGpuMemoryState("OpenGL_초기화")
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                // 필요시 구현
            }

            override fun onDrawFrame(gl: GL10?) {
                // GPU 모니터링용이므로 실제 렌더링은 하지 않음
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        })

        // 보이지 않는 위치에 1x1 크기로 추가 (모니터링 전용)
        glSurfaceView.layoutParams = ViewGroup.LayoutParams(1, 1)
        glSurfaceView.visibility = View.GONE

        // 메인 레이아웃에 추가
        findViewById<ViewGroup>(R.id.container).addView(glSurfaceView)
    }
*/


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
        binding.glSurfaceView.onResume()  //  GLSurfaceView Resume

        Log.d("HomeFragment", "🎯 onResume - GLSurfaceView 연속 렌더링 재활성화")
        viewModel.startSensorStreaming()
        viewModel.updatePoolStatus()
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()  //  GLSurfaceView Pause
    }

    override fun onDestroyView() {
        super.onDestroyView()

        //
        clearCurrentDisplay()

        //
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                currentManagedBitmap?.release()
                previousManagedBitmap?.release()
            } catch (e: Exception) {
                Log.w("HomeFragment", "최종 프레임 해제 실패: ${e.message}")
            }
        }

        // 성능 통계
        val total = frameSkipCount + successfulFrameCount
        if (total > 0) {
            val successRate = (successfulFrameCount.toFloat() / total * 100)
            val formatter = DecimalFormat("#.#")
            FragmentUtils.logEvent("HomeFragment", "INFO",
                " GLSurfaceView 연속 렌더링 성공률: ${formatter.format(successRate)}% (성공: $successfulFrameCount, 스킵: $frameSkipCount)")
        }

        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor = null

        Log.d("HomeFragment", "✅ HomeFragment 정리 완료 - 프레임 깜빡임 해결")
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
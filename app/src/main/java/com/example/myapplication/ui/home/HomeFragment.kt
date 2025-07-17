package com.example.myapplication.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

    fun createOptimizedPaint(): Paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
}

/**
 * 간소화된 자가치유 비트맵 풀
 */
class CircularBitmapPool(
    private val poolSize: Int = 6,
    private val width: Int = 840,
    private val height: Int = 840,
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888
) {
    private val bitmapPool = Array<Bitmap?>(poolSize) { null }
    private val usageState = Array(poolSize) { false }
    private var currentIndex = 0
    private val poolLock = Object()
    private var isInitialized = false

    // 상태 추적
    private var consecutiveFailures = 0
    private var lastSuccessTime = System.currentTimeMillis()

    fun initialize(): Boolean {
        return synchronized(poolLock) {
            if (isInitialized) return true
            executeWithErrorHandling("초기화") { performInitialization() }
        }
    }

    private fun performInitialization(): Boolean {
        repeat(poolSize) { index ->
            bitmapPool[index] = Bitmap.createBitmap(width, height, config)
            usageState[index] = false
        }
        isInitialized = true
        consecutiveFailures = 0
        FragmentUtils.logEvent("BitmapPool", "DEBUG", "풀 초기화 완료 (크기: $poolSize)")
        return true
    }

    fun acquireBitmap(): Bitmap? {
        synchronized(poolLock) {
            if (!isInitialized && !autoRecover()) return null

            return findAvailableBitmap() ?: handleAcquisitionFailure()
        }
    }

    private fun findAvailableBitmap(): Bitmap? {
        repeat(poolSize) { offset ->
            val index = (currentIndex + offset) % poolSize
            val bitmap = bitmapPool[index]

            if (bitmap != null && !usageState[index] && !bitmap.isRecycled) {
                usageState[index] = true
                currentIndex = (index + 1) % poolSize
                lastSuccessTime = System.currentTimeMillis()
                consecutiveFailures = 0
                return bitmap
            }
        }
        return null
    }

    private fun handleAcquisitionFailure(): Bitmap? {
        consecutiveFailures++
        if (consecutiveFailures >= 3) {
            FragmentUtils.logEvent("BitmapPool", "WARN", "연속 실패 $consecutiveFailures 회 - 자동 복구 시도")
            return if (autoRecover()) acquireBitmap() else null
        }
        return null
    }

    private fun autoRecover(): Boolean {
        return executeWithErrorHandling("자동 복구") { performRecovery() }
    }

    private fun performRecovery(): Boolean {
        FragmentUtils.logEvent("BitmapPool", "WARN", "자동 복구 시작")

        // 손상된 비트맵 제거
        bitmapPool.forEachIndexed { index, bitmap ->
            if (bitmap?.isRecycled == true) {
                bitmapPool[index] = null
                usageState[index] = false
            }
        }

        // 응급상황시 모든 비트맵 해제
        if (consecutiveFailures >= 5) {
            usageState.fill(false)
        }

        // 누락된 비트맵 재생성
        repeat(poolSize) { index ->
            if (bitmapPool[index] == null) {
                bitmapPool[index] = Bitmap.createBitmap(width, height, config)
                usageState[index] = false
            }
        }

        isInitialized = true
        consecutiveFailures = 0
        currentIndex = 0

        val availableCount = usageState.count { !it }
        FragmentUtils.logEvent("BitmapPool", "WARN", "자동 복구 완료 - 사용가능: $availableCount")

        return availableCount > 0
    }

    fun releaseBitmap(bitmap: Bitmap?) {
        if (bitmap == null || !isInitialized) return

        synchronized(poolLock) {
            val index = bitmapPool.indexOf(bitmap)
            if (index != -1 && usageState[index]) {
                usageState[index] = false
            }
        }
    }

    fun isHealthy(): Boolean {
        synchronized(poolLock) {
            if (!isInitialized) return false
            val available = usageState.count { !it }
            val recycled = bitmapPool.count { it?.isRecycled == true }
            return available > 0 && recycled == 0
        }
    }

    fun getStatus(): String {
        synchronized(poolLock) {
            if (!isInitialized) return "미초기화"

            val available = usageState.count { !it }
            val inUse = usageState.count { it }
            val healthy = if (isHealthy()) "정상" else "문제"

            return "$healthy | 가용:$available 사용:$inUse"
        }
    }

    fun cleanup() {
        synchronized(poolLock) {
            executeWithErrorHandling("정리") { performCleanup() }
        }
    }

    private fun performCleanup(): Boolean {
        bitmapPool.forEachIndexed { index, bitmap ->
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmapPool[index] = null
            usageState[index] = false
        }
        isInitialized = false
        consecutiveFailures = 0
        return true
    }

    fun isReady(): Boolean = synchronized(poolLock) { isInitialized }

    private fun executeWithErrorHandling(operationName: String, operation: () -> Boolean): Boolean {
        return try {
            operation()
        } catch (e: Exception) {
            FragmentUtils.logEvent("BitmapPool", "ERROR", "$operationName 실패: ${e.message}", e)
            if (operationName == "정리") {
                isInitialized = false
                consecutiveFailures = 0
            }
            false
        }
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

    // 컴포넌트들
    private var bitmapPool: CircularBitmapPool? = null
    private var currentDisplayBitmap: Bitmap? = null
    private var frameSkipCount = 0
    private var successfulFrameCount = 0
    private var resourceMonitor: ResourceMonitor? = null

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

        // UI 풀 초기화
        bitmapPool = CircularBitmapPool(poolSize = 6, width = 840, height = 840)
        if (bitmapPool?.initialize() != true) {
            throw RuntimeException("UI 비트맵 풀 초기화 실패")
        }
    }

    private fun startHealthMonitoring() {
        lifecycleScope.launch {
            while (isActive) {
                delay(5000) // 5초마다 체크

                val pool = bitmapPool
                if (pool != null && !pool.isHealthy()) {
                    FragmentUtils.logEvent("HomeFragment", "WARN", "풀 건강성 문제 감지 - 복구 시도")
                    recoverPool()
                }

                // UI 상태 업데이트
                binding.poolStatusText.text = pool?.getStatus() ?: "풀 없음"
            }
        }
    }

    private fun recoverPool() {
        try {
            // 현재 비트맵 해제
            currentDisplayBitmap?.let {
                bitmapPool?.releaseBitmap(it)
            }
            currentDisplayBitmap = null
            binding.imageView.setImageBitmap(null)

            // 풀 재구축
            bitmapPool?.cleanup()
            bitmapPool = CircularBitmapPool(poolSize = 6, width = 840, height = 840)

            if (bitmapPool?.initialize() == true) {
                frameSkipCount = 0
                showToast("풀 자동 복구 완료")
                FragmentUtils.logEvent("HomeFragment", "WARN", "풀 복구 성공")
            } else {
                FragmentUtils.logEvent("HomeFragment", "ERROR", "풀 복구 실패")
            }
        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "풀 복구 중 오류: ${e.message}", e)
        }
    }

    private fun performUIPoolEmergencyRecovery() {
        FragmentUtils.logEvent("HomeFragment", "WARN", "UI Pool 응급 복구 시작")
        recoverPool()
    }

    /**
     * 🎯 핵심 함수: Raw 비트맵을 UI Pool로 안전하게 복사
     */
    private fun copyRawBitmapToUIPool(rawBitmap: Bitmap, frameId: Long) {
        val pool = bitmapPool
        if (pool == null || !pool.isReady()) {
            Log.w("HomeFragment", "⚠️ UI 비트맵 풀이 준비되지 않음")
            return
        }

        try {
            // 🛡️ 1단계: Raw 비트맵 유효성 사전 검증
            if (!isValidBitmap(rawBitmap)) {
                Log.w("HomeFragment", "⚠️ Raw 비트맵이 유효하지 않음: frameId=$frameId")
                return
            }

            // 2. UI Pool에서 사용 가능한 비트맵 획득
            val uiPoolBitmap = pool.acquireBitmap()
            if (uiPoolBitmap == null) {
                frameSkipCount++
                if (frameSkipCount % 10 == 0) {
                    Log.w("HomeFragment", "⚠️ UI Pool 포화 - 프레임 스킵: $frameSkipCount")
                    performUIPoolEmergencyRecovery()
                }
                return
            }

            // 🛡️ 3단계: UI Pool 비트맵 유효성 검증
            if (!isValidBitmap(uiPoolBitmap)) {
                Log.e("HomeFragment", "❌ UI Pool에서 무효한 비트맵 획득")
                pool.releaseBitmap(uiPoolBitmap)
                return
            }

            // 4. 안전한 Canvas 작업
            val canvas = Canvas(uiPoolBitmap)
            canvas.drawColor(android.graphics.Color.BLACK) // 배경 클리어

            val srcRect = android.graphics.Rect(0, 0, rawBitmap.width, rawBitmap.height)
            val dstRect = android.graphics.Rect(0, 0, uiPoolBitmap.width, uiPoolBitmap.height)

            val paint = android.graphics.Paint().apply {
                isFilterBitmap = true
                isAntiAlias = false
            }

            // 🛡️ 5단계: drawBitmap 전 재검증
            if (!isValidBitmap(rawBitmap) || !isValidBitmap(uiPoolBitmap)) {
                Log.e("HomeFragment", "❌ drawBitmap 직전 비트맵 무효화 감지")
                pool.releaseBitmap(uiPoolBitmap)
                return
            }

            canvas.drawBitmap(rawBitmap, srcRect, dstRect, paint)

            // 🛡️ 6단계: UI 업데이트 전 최종 검증
            if (!isValidBitmap(uiPoolBitmap)) {
                Log.e("HomeFragment", "❌ UI 업데이트 직전 비트맵 무효화 감지")
                pool.releaseBitmap(uiPoolBitmap)
                return
            }

            // 7. 안전한 UI 업데이트
            val previousBitmap = currentDisplayBitmap
            binding.imageView.post {
                try {
                    // 🛡️ UI 스레드에서 한번 더 검증
                    if (isValidBitmap(uiPoolBitmap)) {
                        binding.imageView.setImageBitmap(uiPoolBitmap)
                        currentDisplayBitmap = uiPoolBitmap

                        // 이전 UI Pool 비트맵 반환
                        previousBitmap?.let {
                            pool.releaseBitmap(it)
                        }

                        successfulFrameCount++
                        Log.d("HomeFragment", "✅ 안전한 UI 업데이트 완료: frameId=$frameId")
                    } else {
                        Log.e("HomeFragment", "❌ UI 스레드에서 비트맵 무효화 감지")
                        pool.releaseBitmap(uiPoolBitmap)
                        binding.imageView.setImageBitmap(null)
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "❌ UI 업데이트 중 예외: ${e.message}", e)
                    pool.releaseBitmap(uiPoolBitmap)
                    binding.imageView.setImageBitmap(null)
                }
            }

        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ Raw → UI Pool 복사 실패: ${e.message}", e)
        }
    }

    private fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
    }

    // 권한 관련 메서드들 (Fragment 내부로 이동)
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
        currentDisplayBitmap?.let { bitmapPool?.releaseBitmap(it) }
        currentDisplayBitmap = null
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

    private fun setupCameraFrameObserver() {
        viewModel.cameraFrame.observe(viewLifecycleOwner) { sensorData ->
            try {
                if (sensorData?.bitmap != null && !sensorData.bitmap.isRecycled) {
                    copyRawBitmapToUIPool(sensorData.bitmap, sensorData.frameId)
                } else {
                    currentDisplayBitmap?.let { bitmapPool?.releaseBitmap(it) }
                    currentDisplayBitmap = null
                    binding.imageView.setImageBitmap(null)
                }
            } catch (e: Exception) {
                FragmentUtils.logEvent("HomeFragment", "ERROR", "프레임 처리 오류: ${e.message}", e)
                binding.imageView.setImageBitmap(null)
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
        viewModel.poolStatus.observe(viewLifecycleOwner) { status ->
            binding.poolStatusText.text = status
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

        // 🎯 UI 풀 복구 신호 Observer 추가
        viewModel.shouldRecoverUIPool.observe(viewLifecycleOwner) { shouldRecover ->
            if (shouldRecover) {
                Log.w("HomeFragment", "🚨 Surface FPS 드롭으로 인한 UI 풀 자동 복구 실행")
                recoverPool() // 기존 함수 그대로 호출
                showToast("Surface FPS 드롭 감지 - UI 풀 자동 복구 완료")
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
            recoverPool() // UI Pool도 정리
            showToast("비트맵 풀 정리 완료")
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

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // 정리 작업
        currentDisplayBitmap?.let {
            bitmapPool?.releaseBitmap(it)
        }
        currentDisplayBitmap = null

        // 성능 통계
        val total = frameSkipCount + successfulFrameCount
        if (total > 0) {
            val successRate = (successfulFrameCount.toFloat() / total * 100)
            FragmentUtils.logEvent("HomeFragment", "INFO",
                "성공률: %.1f%% (성공: %d, 스킵: %d)".format(successRate, successfulFrameCount, frameSkipCount))
        }

        bitmapPool?.cleanup()
        bitmapPool = null
        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
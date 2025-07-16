package com.example.myapplication.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * 개선된 순환 비트맵 풀 - 실시간 카메라 스트리밍 최적화
 */
class CircularBitmapPool(
    private val poolSize: Int = 3,
    private val width: Int = 840,
    private val height: Int = 840,
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888
) {
    private val bitmapPool = Array<Bitmap?>(poolSize) { null }
    private val usageState = Array(poolSize) { false }  // false: available, true: in-use
    private var currentIndex = 0
    private val poolLock = Object()
    private var isInitialized = false

    fun initialize(): Boolean {
        return synchronized(poolLock) {
            if (isInitialized) {
                Log.d("BitmapPool", "✅ 비트맵 풀 이미 초기화됨")
                return true
            }

            try {
                repeat(poolSize) { index ->
                    bitmapPool[index] = Bitmap.createBitmap(width, height, config)
                    usageState[index] = false
                    Log.d("BitmapPool", "✅ 비트맵 $index 초기화 완료: ${width}x${height}")
                }
                isInitialized = true
                Log.d("BitmapPool", "🎯 비트맵 풀 전체 초기화 완료")
                true
            } catch (e: Exception) {
                Log.e("BitmapPool", "❌ 비트맵 풀 초기화 실패: ${e.message}", e)
                cleanup()
                false
            }
        }
    }

    fun acquireBitmap(): Bitmap? {
        synchronized(poolLock) {
            if (!isInitialized) {
                Log.w("BitmapPool", "⚠️ 풀이 초기화되지 않음")
                return null
            }

            repeat(poolSize) { offset ->
                val index = (currentIndex + offset) % poolSize
                val bitmap = bitmapPool[index]

                if (bitmap != null && !usageState[index] && !bitmap.isRecycled) {
                    usageState[index] = true
                    currentIndex = (index + 1) % poolSize
                    Log.d("BitmapPool", "🎯 비트맵 $index 할당 성공")
                    return bitmap
                }
            }

            Log.w("BitmapPool", "⚠️ 사용 가능한 비트맵 없음 - 모든 풀이 사용 중")
            return null
        }
    }

    fun releaseBitmap(bitmap: Bitmap?) {
        if (bitmap == null || !isInitialized) return

        synchronized(poolLock) {
            val index = bitmapPool.indexOf(bitmap)
            if (index != -1 && usageState[index]) {
                usageState[index] = false
                Log.d("BitmapPool", "🔄 비트맵 $index 반환 완료 - 재사용 준비")
            } else {
                Log.w("BitmapPool", "⚠️ 풀에 없는 비트맵 반환 시도")
            }
        }
    }

    fun getPoolStatus(): String {
        synchronized(poolLock) {
            if (!isInitialized) return "Pool Status: NOT_INITIALIZED"

            val available = usageState.count { !it }
            val inUse = usageState.count { it }
            val recycled = bitmapPool.count { it?.isRecycled == true }
            return "Pool Status: Available=$available, InUse=$inUse, Recycled=$recycled, Total=$poolSize"
        }
    }

    fun cleanup() {
        synchronized(poolLock) {
            if (!isInitialized) return

            bitmapPool.forEachIndexed { index, bitmap ->
                if (bitmap != null && !bitmap.isRecycled) {
                    try {
                        bitmap.recycle()
                        Log.d("BitmapPool", "🗑️ 비트맵 $index 정리 완료")
                    } catch (e: Exception) {
                        Log.w("BitmapPool", "비트맵 $index 정리 실패: ${e.message}")
                    }
                }
                bitmapPool[index] = null
                usageState[index] = false
            }
            isInitialized = false
            Log.d("BitmapPool", "🗑️ 비트맵 풀 전체 정리 완료")
        }
    }

    fun isReady(): Boolean = synchronized(poolLock) { isInitialized }
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

    // 🎯 UI 전용 CircularBitmapPool
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
            // 1. 리소스 모니터 초기화
            resourceMonitor = ResourceMonitor.getInstance(requireContext())

            // 2. UI 전용 순환 비트맵 풀 초기화
            bitmapPool = CircularBitmapPool(
                poolSize = 3,
                width = 840,
                height = 840
            )

            val poolInitialized = bitmapPool?.initialize() ?: false
            if (!poolInitialized) {
                throw RuntimeException("UI 비트맵 풀 초기화 실패")
            }

            resourceMonitor?.logAppResourceStatus("HomeFragment", "Fragment 생성 - UI 순환 비트맵 풀 초기화 완료")

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
     * 🎯 새로 추가: Raw 비트맵을 UI Pool로 안전하게 복사
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

    /**
     * 🛡️ 비트맵 유효성 검증 헬퍼
     */
    private fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return try {
            bitmap != null &&
                    !bitmap.isRecycled &&
                    bitmap.width > 0 &&
                    bitmap.height > 0 &&
                    bitmap.config != null
        } catch (e: Exception) {
            Log.w("HomeFragment", "⚠️ 비트맵 검증 중 예외: ${e.message}")
            false
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

        // 현재 표시 비트맵을 풀로 반환
        currentDisplayBitmap?.let {
            bitmapPool?.releaseBitmap(it)
        }
        currentDisplayBitmap = null
        binding.imageView.setImageBitmap(null)

        // UI Pool 정리 후 재초기화
        bitmapPool?.cleanup()
        bitmapPool?.initialize()

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

        // 🎯 Raw 비트맵을 UI Pool로 복사하는 카메라 프레임 처리
        viewModel.cameraFrame.observe(viewLifecycleOwner) { sensorData ->
            try {
                if (sensorData?.bitmap != null && !sensorData.bitmap.isRecycled) {
                    // 🔥 핵심: Raw 비트맵을 UI Pool로 안전하게 복사
                    copyRawBitmapToUIPool(sensorData.bitmap, sensorData.frameId)
                    Log.d("HomeFragment", "✅ Raw bitmap copied to UI pool: frameId=${sensorData.frameId}")
                } else {
                    // Raw 비트맵이 없으면 UI 클리어
                    currentDisplayBitmap?.let {
                        bitmapPool?.releaseBitmap(it)
                    }
                    currentDisplayBitmap = null
                    binding.imageView.setImageBitmap(null)
                    Log.d("HomeFragment", "⚠️ Raw frame cleared (null or recycled bitmap)")
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Raw → UI Pool 복사 오류: ${e.message}", e)
                binding.imageView.setImageBitmap(null)
            }
        }

        // 🎯 풀 상태 관찰자
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
            //Log.d("HomeFragment", "📍 GPS 데이터 UI 업데이트: $data")
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

                // UI Pool 비트맵 정리
                currentDisplayBitmap?.let {
                    bitmapPool?.releaseBitmap(it)
                }
                currentDisplayBitmap = null
                binding.imageView.setImageBitmap(null)

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
            // UI Pool도 정리
            bitmapPool?.cleanup()
            bitmapPool?.initialize()
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

        // 현재 표시 비트맵을 풀로 반환
        currentDisplayBitmap?.let {
            bitmapPool?.releaseBitmap(it)
            Log.d("HomeFragment", "🔄 현재 표시 비트맵 풀 반환 완료")
        }
        currentDisplayBitmap = null

        // 성능 통계 로그
        val total = frameSkipCount + successfulFrameCount
        if (total > 0) {
            val successRate = (successfulFrameCount.toFloat() / total * 100)
            Log.i("HomeFragment", "📊 최종 성능 통계 - 성공률: %.1f%% (성공: %d, 스킵: %d, 총: %d)".format(
                successRate, successfulFrameCount, frameSkipCount, total))
        }

        // UI 비트맵 풀 정리
        bitmapPool?.cleanup()
        bitmapPool = null
        Log.d("HomeFragment", "🗑️ UI 순환 비트맵 풀 정리 완료")

        viewModel.stopSensorStreaming()
        _binding = null
        resourceMonitor?.logAppResourceStatus("HomeFragment", "Fragment 종료 완료 - UI 순환 비트맵 풀 정리됨")
        resourceMonitor = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
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

    fun createOptimizedPaint(): Paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
}

/**
 * 🚀 강화된 자가치유 비트맵 풀 - 완전한 복구 지원
 */
class CircularBitmapPool(
    private val poolSize: Int = 20,
    private val width: Int = 840,
    private val height: Int = 840,
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888
) {
    private val TAG = "CircularBitmapPool"
    private val bitmapPool = Array<Bitmap?>(poolSize) { null }
    private val usageState = Array(poolSize) { false }
    private var currentIndex = 0
    private val poolLock = Object()
    private var isInitialized = false

    // 상태 추적 강화
    private var consecutiveFailures = 0
    private var lastSuccessTime = System.currentTimeMillis()
    private var recoveryCount = 0
    private val maxRecoveryAttempts = 5

    fun initialize(): Boolean {
        return synchronized(poolLock) {
            if (isInitialized) return true

            // ✅ 더 보수적인 초기화
            repeat(poolSize) { index ->
                try {
                    Thread.sleep(50) // 비트맵 생성 간 지연
                    bitmapPool[index] = Bitmap.createBitmap(width, height, config)
                    usageState[index] = false
                } catch (e: OutOfMemoryError) {
                    FragmentUtils.logEvent(TAG, "ERROR", "초기화 중 OOM: $index", e as Exception)
                    // 생성 실패시 중단하지 않고 계속 시도
                    System.gc()
                    Thread.sleep(100)
                    return@repeat
                }
            }

            val validBitmaps = bitmapPool.count { it != null && !it.isRecycled }
            isInitialized = validBitmaps >= (poolSize / 2) // 절반 이상 성공하면 OK

            FragmentUtils.logEvent(TAG, "DEBUG", "풀 초기화 완료 (유효: $validBitmaps/$poolSize)")
            return isInitialized
        }
    }

    private fun performInitialization(): Boolean {
        repeat(poolSize) { index ->
            try {
                bitmapPool[index] = Bitmap.createBitmap(width, height, config)
                usageState[index] = false
            } catch (e: OutOfMemoryError) {
                FragmentUtils.logEvent(TAG, "ERROR", "초기화 중 OOM: $index", e as Exception)
                return false
            }
        }
        isInitialized = true
        consecutiveFailures = 0
        recoveryCount = 0
        FragmentUtils.logEvent(TAG, "DEBUG", "풀 초기화 완료 (크기: $poolSize)")
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

            if (bitmap != null && !usageState[index] && !bitmap.isRecycled && isValidBitmap(bitmap)) {
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
        FragmentUtils.logEvent(TAG, "WARN", "비트맵 획득 실패: $consecutiveFailures 회")

        if (consecutiveFailures >= 3 && recoveryCount < maxRecoveryAttempts) {
            FragmentUtils.logEvent(TAG, "WARN", "연속 실패 감지 - 자동 복구 시도: $recoveryCount")
            return if (autoRecover()) acquireBitmap() else null
        }
        return null
    }

    private fun autoRecover(): Boolean {
        return executeWithErrorHandling("자동 복구") { performAdvancedRecovery() }
    }

    private fun performAdvancedRecovery(): Boolean {
        recoveryCount++
        FragmentUtils.logEvent(TAG, "WARN", "고급 자동 복구 시작 ($recoveryCount/$maxRecoveryAttempts)")

        try {
            // 1. 손상된 비트맵 감지 및 제거
            var recycledCount = 0
            bitmapPool.forEachIndexed { index, bitmap ->
                if (bitmap?.isRecycled == true || !isValidBitmap(bitmap)) {
                    bitmapPool[index] = null
                    usageState[index] = false
                    recycledCount++
                }
            }

            // 2. 심각한 상황에서는 모든 비트맵 해제
            if (consecutiveFailures >= 5) {
                FragmentUtils.logEvent(TAG, "ERROR", "심각한 상황 - 모든 비트맵 강제 해제")
                usageState.fill(false)
                bitmapPool.forEachIndexed { index, bitmap ->
                    if (bitmap != null && !bitmap.isRecycled) {
                        try {
                            bitmap.recycle()
                        } catch (e: Exception) {
                            FragmentUtils.logEvent(TAG, "ERROR", "비트맵 강제 해제 실패: $index", e)
                        }
                    }
                    bitmapPool[index] = null
                }
            }

            // 3. 누락된 비트맵 재생성 (단계적)
            var recreatedCount = 0
            repeat(poolSize) { index ->
                if (bitmapPool[index] == null) {
                    try {
                        bitmapPool[index] = Bitmap.createBitmap(width, height, config)
                        usageState[index] = false
                        recreatedCount++
                    } catch (e: OutOfMemoryError) {
                        FragmentUtils.logEvent(TAG, "ERROR", "복구 중 OOM: $index", e as Exception)
                        // GC 후 재시도
                        System.gc()
                        Thread.sleep(100)
                        try {
                            bitmapPool[index] = Bitmap.createBitmap(width, height, config)
                            usageState[index] = false
                            recreatedCount++
                        } catch (e2: OutOfMemoryError) {
                            FragmentUtils.logEvent(TAG, "ERROR", "복구 재시도 실패: $index", e2 as Exception)
                            return@repeat  // ✅ 현재 iteration만 종료
                        }
                    }
                }
            }

            // 4. 상태 정리
            isInitialized = true
            consecutiveFailures = 0
            currentIndex = 0

            val availableCount = usageState.count { !it }
            FragmentUtils.logEvent(TAG, "WARN",
                "고급 복구 완료: recycled=$recycledCount, recreated=$recreatedCount, available=$availableCount")

            return availableCount > 0

        } catch (e: Exception) {
            FragmentUtils.logEvent(TAG, "ERROR", "고급 복구 실패", e)
            return false
        }
    }

    private fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return try {
            bitmap != null &&
                    !bitmap.isRecycled &&
                    bitmap.width > 0 &&
                    bitmap.height > 0 &&
                    bitmap.config != null
        } catch (e: Exception) {
            false
        }
    }

    fun releaseBitmap(bitmap: Bitmap?) {
        if (bitmap == null || !isInitialized) return

        synchronized(poolLock) {
            val index = bitmapPool.indexOf(bitmap)
            if (index != -1 && usageState[index]) {
                usageState[index] = false
                FragmentUtils.logEvent(TAG, "DEBUG", "비트맵 해제: index=$index")
            }
        }
    }

    fun isHealthy(): Boolean {
        synchronized(poolLock) {
            if (!isInitialized) return false
            val available = usageState.count { !it }
            val validBitmaps = bitmapPool.count { isValidBitmap(it) }
            val recycled = bitmapPool.count { it?.isRecycled == true }

            return available > 0 && validBitmaps >= poolSize/2 && recycled == 0
        }
    }

    fun getStatus(): String {
        synchronized(poolLock) {
            if (!isInitialized) return "미초기화"

            val available = usageState.count { !it }
            val inUse = usageState.count { it }
            val valid = bitmapPool.count { isValidBitmap(it) }
            val recycled = bitmapPool.count { it?.isRecycled == true }
            val healthy = if (isHealthy()) "정상" else "문제"

            return "$healthy | 가용:$available 사용:$inUse 유효:$valid 재활용:$recycled 복구:$recoveryCount"
        }
    }

    fun forceEmergencyRecovery(): Boolean {
        synchronized(poolLock) {
            FragmentUtils.logEvent(TAG, "ERROR", "응급 복구 시작")
            consecutiveFailures = 10 // 강제로 심각한 상황으로 설정
            return performAdvancedRecovery()
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
        recoveryCount = 0
        return true
    }

    fun isReady(): Boolean = synchronized(poolLock) { isInitialized && isHealthy() }

    private fun executeWithErrorHandling(operationName: String, operation: () -> Boolean): Boolean {
        return try {
            operation()
        } catch (e: Exception) {
            FragmentUtils.logEvent(TAG, "ERROR", "$operationName 실패: ${e.message}", e)
            if (operationName == "정리") {
                isInitialized = false
                consecutiveFailures = 0
                recoveryCount = 0
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

        // UI 풀 초기화
        bitmapPool = CircularBitmapPool(poolSize = 20, width = 840, height = 840)
        if (bitmapPool?.initialize() != true) {
            throw RuntimeException("UI 비트맵 풀 초기화 실패")
        }
    }

    private fun startHealthMonitoring() {
        lifecycleScope.launch {
            while (isActive) {
                delay(5000) // 3초마다 체크

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

    /**
     * 🚀 강화된 풀 복구 시스템 - 3단계 복구
     */
    private fun recoverPool() {
        try {
            val currentTime = System.currentTimeMillis()

            // ✅ 복구 빈도를 더 엄격하게 제한 (5초에 1회)
            if (currentTime - lastRecoveryTime < 5000) {
                FragmentUtils.logEvent("HomeFragment", "WARN", "복구 시도 너무 빈번 - 스킵")
                return
            }
            lastRecoveryTime = currentTime

            FragmentUtils.logEvent("HomeFragment", "WARN", "🔧 UI Pool 복구 시작")

            // ✅ 단순하고 빠른 복구
            performQuickUIRecovery()

        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "UI Pool 복구 중 예외: ${e.message}", e)
            showCriticalError()
        }
    }

    /**
     * ✅ 빠른 UI 복구 - 복잡한 로직 제거
     */
    private fun performQuickUIRecovery() {
        try {
            // 1. 현재 비트맵만 해제
            currentDisplayBitmap?.let { bitmap ->
                bitmapPool?.releaseBitmap(bitmap)
            }
            currentDisplayBitmap = null

            // 2. ImageView 클리어
            binding.imageView.setImageBitmap(null)

            // 3. 강제 GC
            System.gc()

            // 4. 간단한 풀 재초기화
            val oldPool = bitmapPool
            bitmapPool = CircularBitmapPool(poolSize = 20, width = 840, height = 840) // 풀 크기 축소

            if (bitmapPool?.initialize() == true) {
                oldPool?.cleanup()
                frameSkipCount = 0
                successfulFrameCount = 0

                binding.poolStatusText.text = bitmapPool?.getStatus() ?: "풀 없음"
                showToast("UI Pool 빠른 복구 완료")
                FragmentUtils.logEvent("HomeFragment", "WARN", "✅ UI Pool 빠른 복구 성공")
            } else {
                FragmentUtils.logEvent("HomeFragment", "ERROR", "UI Pool 빠른 복구 실패")
                showCriticalError()
            }

        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "UI Pool 빠른 복구 중 예외: ${e.message}", e)
            showCriticalError()
        }
    }

    private fun cleanupUIStateCompletely() {
        try {
            FragmentUtils.logEvent("HomeFragment", "WARN", "UI 상태 완전 정리 시작")

            // 1. ImageView 완전 정리
            binding.imageView.setImageDrawable(null)
            binding.imageView.setImageBitmap(null)

            // 2. 현재 비트맵 안전 해제
            currentDisplayBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    try {
                        bitmapPool?.releaseBitmap(bitmap)
                    } catch (e: Exception) {
                        FragmentUtils.logEvent("HomeFragment", "WARN", "현재 비트맵 해제 중 예외: ${e.message}")
                    }
                }
            }
            currentDisplayBitmap = null

            // 3. 기존 풀 완전 파괴
            bitmapPool?.cleanup()
            bitmapPool = null

            // 4. 강제 GC
            System.gc()

            FragmentUtils.logEvent("HomeFragment", "DEBUG", "✅ UI 상태 완전 정리 완료")

        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "UI 상태 정리 중 예외: ${e.message}", e)
        }
    }

    private fun recreateUIPoolSafely() {
        try {
            FragmentUtils.logEvent("HomeFragment", "DEBUG", "🆕 새 UI Pool 생성 시작")

            // ✅ GPU 메모리 상태 안정화 대기
            Thread.sleep(100)

            // 새 풀 생성
            bitmapPool = CircularBitmapPool(poolSize = 20, width = 840, height = 840)

            if (bitmapPool?.initialize() == true) {
                frameSkipCount = 0
                successfulFrameCount = 0

                // ✅ 상태 텍스트 업데이트 지연
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.poolStatusText.text = bitmapPool?.getStatus() ?: "풀 없음"
                }, 100)

                showToast("UI Pool 복구 완료")
                FragmentUtils.logEvent("HomeFragment", "WARN", "✅ UI Pool 복구 성공")
            } else {
                FragmentUtils.logEvent("HomeFragment", "ERROR", "새 UI Pool 초기화 실패")
                showCriticalError()
            }

        } catch (e: Exception) {
            FragmentUtils.logEvent("HomeFragment", "ERROR", "UI Pool 재생성 중 예외: ${e.message}", e)
            showCriticalError()
        }
    }

    private fun showCriticalError() {
        AlertDialog.Builder(requireContext())
            .setTitle("메모리 시스템 오류")
            .setMessage("UI 메모리 풀 복구에 실패했습니다.\n앱을 재시작하시겠습니까?")
            .setPositiveButton("재시작") { _, _ ->
                // 앱 재시작 로직
                val intent = requireActivity().intent
                requireActivity().finish()
                startActivity(intent)
            }
            .setNegativeButton("계속") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 🎯 핵심 함수: Raw 비트맵을 UI Pool로 안전하게 복사 - 강화된 버전
     */
    private fun copyRawBitmapToUIPool(rawBitmap: Bitmap, frameId: Long) {
        val pool = bitmapPool
        if (pool == null || !pool.isReady()) {
            Log.w("HomeFragment", "⚠️ UI Pool 준비되지 않음")
            return
        }

        try {
            // 🛡️ 1단계: Raw 비트맵 다중 검증
            if (!isValidBitmapMultiple(rawBitmap)) {
                Log.w("HomeFragment", "⚠️ Raw 비트맵 검증 실패: frameId=$frameId")
                return
            }

            // 2. UI Pool에서 사용 가능한 비트맵 획득
            val uiPoolBitmap = pool.acquireBitmap()
            if (uiPoolBitmap == null) {
                frameSkipCount++
                if (frameSkipCount % 20 == 0) {  // 더 관대한 임계값
                    Log.w("HomeFragment", "⚠️ UI Pool 포화 감지 - 복구 대기: $frameSkipCount")
                    // 즉시 복구하지 않고 대기
                }
                return
            }

            // 🛡️ 3단계: UI Pool 비트맵 다중 검증
            if (!isValidBitmapMultiple(uiPoolBitmap)) {
                Log.e("HomeFragment", "❌ UI Pool 비트맵 검증 실패")
                pool.releaseBitmap(uiPoolBitmap)
                return
            }

            // 🛡️ 4단계: 안전한 Canvas 작업 (예외 처리 강화)
            var canvasSuccess = false
            try {
                val canvas = Canvas(uiPoolBitmap)
                canvas.drawColor(android.graphics.Color.BLACK)

                val srcRect = android.graphics.Rect(0, 0, rawBitmap.width, rawBitmap.height)
                val dstRect = android.graphics.Rect(0, 0, uiPoolBitmap.width, uiPoolBitmap.height)
                val paint = createOptimizedPaint()

                // 최종 검증 후 그리기
                if (isValidBitmapMultiple(rawBitmap) && isValidBitmapMultiple(uiPoolBitmap)) {
                    canvas.drawBitmap(rawBitmap, srcRect, dstRect, paint)
                    canvasSuccess = true
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ Canvas 작업 실패: ${e.message}", e)
                canvasSuccess = false
            }

            if (!canvasSuccess) {
                pool.releaseBitmap(uiPoolBitmap)
                return
            }

            // 🛡️ 5단계: 안전한 UI 업데이트 (더 강화된 검증)
            updateUIWithBitmap(uiPoolBitmap, pool, frameId)

        } catch (e: Exception) {
            Log.e("HomeFragment", "❌ UI Pool 복사 전체 실패: ${e.message}", e)
        }
    }

    private fun isValidBitmapMultiple(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false

        return try {
            bitmap.width > 0 &&
                    bitmap.height > 0 &&
                    !bitmap.isRecycled &&
                    bitmap.config != null &&
                    bitmap.hasAlpha() != null  // 추가 검증
        } catch (e: Exception) {
            Log.w("HomeFragment", "비트맵 검증 중 예외: ${e.message}")
            false
        }
    }

    private fun updateUIWithBitmap(uiPoolBitmap: Bitmap, pool: CircularBitmapPool, frameId: Long) {
        val previousBitmap = currentDisplayBitmap

        // UI 스레드에서 안전하게 업데이트
        binding.imageView.post {
            try {
                // UI 스레드에서 한번 더 검증
                if (isValidBitmapMultiple(uiPoolBitmap)) {
                    binding.imageView.setImageBitmap(uiPoolBitmap)
                    currentDisplayBitmap = uiPoolBitmap

                    // 이전 비트맵 안전하게 해제
                    previousBitmap?.let {
                        if (it != uiPoolBitmap) {  // 같은 비트맵 중복 해제 방지
                            pool.releaseBitmap(it)
                        }
                    }

                    successfulFrameCount++
                    frameSkipCount = 0  // 성공 시 스킵 카운트 리셋
                    Log.d("HomeFragment", "✅ UI 업데이트 성공: frameId=$frameId")
                } else {
                    Log.e("HomeFragment", "❌ UI 스레드에서 비트맵 재검증 실패")
                    handleUIUpdateFailure(uiPoolBitmap, pool)
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "❌ UI 업데이트 예외: ${e.message}", e)
                handleUIUpdateFailure(uiPoolBitmap, pool)
            }
        }
    }

    private fun handleUIUpdateFailure(bitmap: Bitmap, pool: CircularBitmapPool) {
        pool.releaseBitmap(bitmap)
        binding.imageView.setImageBitmap(null)

        frameSkipCount++
        if (frameSkipCount > 50) {  // 더 관대한 임계값
            Log.w("HomeFragment", "🚨 지속적인 UI 업데이트 실패 - 복구 트리거")
            recoverPool()
        }
    }

    private fun createOptimizedPaint(): Paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    // 나머지 메서드들은 기존과 동일...
    // [권한 관련 메서드들, UI 업데이트 메서드들, Observer 설정 등은 동일하므로 생략]

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

        // 🎯 UI 풀 복구 신호 Observer 강화
        viewModel.shouldRecoverUIPool.observe(viewLifecycleOwner) { shouldRecover ->
            if (shouldRecover) {
                surfaceDropRecoveryCount++
                Log.w("HomeFragment", "🚨 Surface FPS 드롭으로 인한 UI 풀 자동 복구 실행 ($surfaceDropRecoveryCount 회)")
                recoverPool()
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
            val formatter = DecimalFormat("#.#")
            FragmentUtils.logEvent("HomeFragment", "INFO",
                "UI Pool 성공률: ${formatter.format(successRate)}% (성공: $successfulFrameCount, 스킵: $frameSkipCount, Surface복구: $surfaceDropRecoveryCount)")
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
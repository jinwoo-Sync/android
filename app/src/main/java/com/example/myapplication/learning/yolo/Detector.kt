package com.example.myapplication.learning.yolo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.myapplication.learning.yolo.MetaData.extractNamesFromLabelFile
import com.example.myapplication.learning.yolo.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.util.concurrent.atomic.AtomicBoolean



class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: DetectorListener,
    private val message: (String) -> Unit
) {
    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, frameId: Long)
    }

    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()
    private var gpuDelegate: GpuDelegate? = null

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val isClosed = AtomicBoolean(false)
    private val isInferring = AtomicBoolean(false)


    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    private val TAG = "Detector"

    init {
        // 오픈소스 코드와 같은 방식으로 GPU 호환성 확인 및 설정
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
                // GPU 지원 시 bestOptionsForThisDevice 사용
                val delegateOptions = compatList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
                this.addDelegate(gpuDelegate)
                message("GPU Delegate enabled with best options")
                Log.d(TAG, "GPU Delegate enabled with best options")
            } else {
                // GPU를 지원하지 않는 경우 CPU 스레드 수 설정
                this.setNumThreads(4)
                message("GPU not supported, using CPU")
                Log.d(TAG, "GPU not supported, using CPU")
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        labels.addAll(extractNamesFromMetadata(model))
        if (labels.isEmpty()) {
            if (labelPath == null) {
                message("Model not contains metadata, provide LABELS_PATH in Constants.kt")
                labels.addAll(MetaData.TEMP_CLASSES)
            } else {
                labels.addAll(extractNamesFromLabelFile(context, labelPath))
            }
        }

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            // 입력 형식이 [1, 3, ..., ...] 인 경우 처리
            if (inputShape.size > 3 && inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }

        Log.d(TAG, "Detector initialized: width=$tensorWidth, height=$tensorHeight, channels=$numChannel, elements=$numElements")
    }

    fun restart(isGpu: Boolean) {
        // 이미 닫혔으면 재시작 방지
        if (isClosed.get()) {
            Log.w(TAG, "Cannot restart a closed detector")
            return
        }

        // 현재 추론 중이면 완료될 때까지 대기
        while (isInferring.get()) {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }

        // 기존 리소스 정리
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null

        val options = if (isGpu) {
            // GPU 모드로 재시작
            val compatList = CompatibilityList()
            Interpreter.Options().apply {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    gpuDelegate = GpuDelegate(delegateOptions)
                    this.addDelegate(gpuDelegate)
                    message("GPU mode activated")
                    Log.d(TAG, "GPU mode activated")
                } else {
                    this.setNumThreads(4)
                    message("GPU not supported, using CPU instead")
                    Log.d(TAG, "GPU not supported, using CPU instead")
                }
            }
        } else {
            // CPU 모드로 재시작
            Interpreter.Options().apply {
                this.setNumThreads(4)
                message("CPU mode activated")
                Log.d(TAG, "CPU mode activated")
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    fun close() {
        // 이미 닫았으면 다시 닫지 않음
        if (isClosed.getAndSet(true)) {
            return
        }

        Log.d(TAG, "Detector closing, waiting for any inference to complete...")

        // 현재 추론 중이면 완료될 때까지 대기 - 최대 500ms
        var waitCount = 0
        while (isInferring.get() && waitCount < 50) {
            try {
                Thread.sleep(10)
                waitCount++
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        try {
            // 인터프리터 먼저 닫기
            val interpreterInstance = interpreter

            // GPU 델리게이트
            val delegateInstance = gpuDelegate
            gpuDelegate = null  // 참조 제거

            // 인터프리터 종료
            try {
                interpreterInstance?.close()
                Log.d(TAG, "Interpreter closed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "인터프리터 종료 오류: ${e.message}", e)
            }

            // 최종적으로 GPU 델리게이트 종료
            try {
                delegateInstance?.close()
                Log.d(TAG, "GPU delegate closed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "GPU 델리게이트 종료 오류: ${e.message}", e)
            }

            Log.d(TAG, "Detector resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing detector: ${e.message}", e)
        }
    }


    fun detect(frame: Bitmap, frameId: Long = -1L) {
        if (isClosed.get()) {
            Log.w(TAG, "Cannot detect on a closed detector")
            detectorListener.onEmptyDetect()
            return
        }
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            Log.e(TAG, "Invalid tensor dimensions: width=$tensorWidth, height=$tensorHeight, channels=$numChannel, elements=$numElements")
            detectorListener.onEmptyDetect()
            return
        }
        if (!isInferring.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping detection - another inference in progress")
            detectorListener.onEmptyDetect()
            return
        }

        try {
            // 🎯 전처리 단계 (시간 측정 제외)
            val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
            val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
            tensorImage.load(resizedBitmap)
            val processedImage = imageProcessor.process(tensorImage)
            val imageBuffer = processedImage.buffer
            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)

            // ✅ 순수 GPU 추론 시간만 측정
            val pureInferenceStart = SystemClock.uptimeMillis()
            interpreter.run(imageBuffer, output.buffer)
            val pureInferenceTime = SystemClock.uptimeMillis() - pureInferenceStart

            // 🎯 후처리 단계 (시간 측정 제외)
            val bestBoxes = bestBox(output.floatArray)

            if (bestBoxes == null) {
                detectorListener.onEmptyDetect()
                return
            }

            // ✅ 순수 추론 시간만 전달
            detectorListener.onDetect(bestBoxes, pureInferenceTime, frameId)

        } catch (e: Exception) {
            Log.e(TAG, "Error in detection process: ${e.message}", e)
            detectorListener.onEmptyDetect()
        } finally {
            isInferring.set(false)
        }
    }

    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()

        try {
            for (c in 0 until numElements) {
                var maxConf = CONFIDENCE_THRESHOLD
                var maxIdx = -1
                var j = 4
                var arrayIdx = c + numElements * j

                while (j < numChannel) {
                    if (arrayIdx < array.size && array[arrayIdx] > maxConf) {
                        maxConf = array[arrayIdx]
                        maxIdx = j - 4
                    }
                    j++
                    arrayIdx += numElements
                }

                if (maxConf > CONFIDENCE_THRESHOLD && maxIdx >= 0 && maxIdx < labels.size) {
                    val clsName = labels[maxIdx]

                    // 범위 체크를 추가하여 안전성 향상
                    if (c >= array.size || c + numElements >= array.size ||
                        c + numElements * 2 >= array.size || c + numElements * 3 >= array.size) {
                        continue
                    }

                    val cx = array[c]
                    val cy = array[c + numElements]
                    val w = array[c + numElements * 2]
                    val h = array[c + numElements * 3]

                    // 유효한 값인지 검사
                    if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() ||
                        w <= 0 || h <= 0) {
                        continue
                    }

                    val x1 = cx - (w / 2F)
                    val y1 = cy - (h / 2F)
                    val x2 = cx + (w / 2F)
                    val y2 = cy + (h / 2F)

                    // 범위 검사 (0-1 사이)
                    if (x1 < 0F || x1 > 1F) continue
                    if (y1 < 0F || y1 > 1F) continue
                    if (x2 < 0F || x2 > 1F) continue
                    if (y2 < 0F || y2 > 1F) continue

                    boundingBoxes.add(
                        BoundingBox(
                            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                            cx = cx, cy = cy, w = w, h = h,
                            cnf = maxConf, cls = maxIdx, clsName = clsName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing detection results: ${e.message}", e)
            return null
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.3F
        private const val IOU_THRESHOLD = 0.5F
    }
}
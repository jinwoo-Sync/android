package com.example.myapplication.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.example.myapplication.learning.yolo.BoundingBox
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraGLRenderer : GLSurfaceView.Renderer {
    private val TAG = "CameraGLRenderer"

    // 이미지 렌더링용 셰이더 프로그램
    private var imageShaderProgram: Int = 0
    private var textureId: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureHandle: Int = 0

    // 바운딩 박스 렌더링용 셰이더 프로그램
    private var lineShaderProgram: Int = 0
    private var linePositionHandle: Int = 0
    private var colorHandle: Int = 0

    // 현재 상태
    @Volatile
    private var currentBitmap: Bitmap? = null
    @Volatile
    private var boundingBoxes = listOf<BoundingBox>()

    // 뷰포트 크기
    private var viewWidth = 0
    private var viewHeight = 0

    // 이미지용 정점 버퍼
    private val imageVertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // 이미지 정점 좌표 (전체 화면)
    private val imageVertices = floatArrayOf(
        -1.0f, -1.0f,  // 왼쪽 아래
        1.0f, -1.0f,   // 오른쪽 아래
        -1.0f,  1.0f,  // 왼쪽 위
        1.0f,  1.0f    // 오른쪽 위
    )

    // 텍스처 좌표
    private val texCoords = floatArrayOf(
        0.0f, 1.0f,  // 왼쪽 아래
        1.0f, 1.0f,  // 오른쪽 아래
        0.0f, 0.0f,  // 왼쪽 위
        1.0f, 0.0f   // 오른쪽 위
    )

    // 이미지용 셰이더
    private val imageVertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vPosition;
            vTexCoord = aTexCoord;
        }
    """

    private val imageFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // 라인용 셰이더
    private val lineVertexShaderCode = """
        attribute vec4 vPosition;
        void main() {
            gl_Position = vPosition;
        }
    """

    private val lineFragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """

    // 텍스트 렌더링용
    private var textTextureIds = mutableListOf<Int>()
    private var textBitmaps = mutableListOf<Bitmap>()

    init {
        // 이미지용 버퍼 초기화
        imageVertexBuffer = ByteBuffer.allocateDirect(imageVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(imageVertices)
            .apply { position(0) }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
            .apply { position(0) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 이미지 셰이더 프로그램 생성
        val imageVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, imageVertexShaderCode)
        val imageFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, imageFragmentShaderCode)

        imageShaderProgram = GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, imageVertexShader)
            GLES20.glAttachShader(program, imageFragmentShader)
            GLES20.glLinkProgram(program)
        }

        // 이미지용 핸들 가져오기
        positionHandle = GLES20.glGetAttribLocation(imageShaderProgram, "vPosition")
        texCoordHandle = GLES20.glGetAttribLocation(imageShaderProgram, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(imageShaderProgram, "uTexture")

        // 라인 셰이더 프로그램 생성
        val lineVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, lineVertexShaderCode)
        val lineFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, lineFragmentShaderCode)

        lineShaderProgram = GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, lineVertexShader)
            GLES20.glAttachShader(program, lineFragmentShader)
            GLES20.glLinkProgram(program)
        }

        // 라인용 핸들 가져오기
        linePositionHandle = GLES20.glGetAttribLocation(lineShaderProgram, "vPosition")
        colorHandle = GLES20.glGetUniformLocation(lineShaderProgram, "vColor")

        // 이미지용 텍스처 생성
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 블렌딩 활성화 (텍스트 렌더링용)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        Log.d(TAG, "OpenGL 초기화 완료 - 이미지 + 바운딩박스 렌더링")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        Log.d(TAG, "OpenGL 뷰포트 크기 변경: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val bitmap = currentBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            // 1. 비트맵을 OpenGL로 렌더링
            renderBitmap(bitmap)

            // 2. 바운딩 박스를 OpenGL로 렌더링
            renderBoundingBoxes()
        }
    }

    /**
     * 이미지 렌더링
     */
    private fun renderBitmap(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES20.glUseProgram(imageShaderProgram)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, imageVertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * 바운딩 박스 렌더링
     */
    private fun renderBoundingBoxes() {
        if (boundingBoxes.isEmpty()) return

        // 라인 렌더링을 위한 설정
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glLineWidth(8.0f) // 라인 두께 설정

        boundingBoxes.forEach { box ->
            renderSingleBoundingBox(box)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * 개별 바운딩 박스 렌더링
     */
    private fun renderSingleBoundingBox(box: BoundingBox) {
        // YOLO 정규화 좌표(0-1)를 OpenGL 좌표(-1 ~ 1)로 변환
        // 주의: OpenGL은 Y축이 위쪽이 +1, 아래쪽이 -1
        val left = box.x1 * 2f - 1f
        val right = box.x2 * 2f - 1f
        val top = (1f - box.y1) * 2f - 1f    // Y축 올바른 변환
        val bottom = (1f - box.y2) * 2f - 1f // Y축 올바른 변환

        // 바운딩 박스 사각형의 정점들 (닫힌 사각형)
        val boxVertices = floatArrayOf(
            left, top,      // 왼쪽 위
            right, top,     // 오른쪽 위
            right, bottom,  // 오른쪽 아래
            left, bottom,   // 왼쪽 아래
            left, top       // 다시 왼쪽 위 (닫힌 사각형)
        )

        val boxBuffer = ByteBuffer.allocateDirect(boxVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(boxVertices)
            .apply { position(0) }

        // 라인 셰이더 프로그램 사용
        GLES20.glUseProgram(lineShaderProgram)

        // 색상 설정 (빨간색)
        GLES20.glUniform4f(colorHandle, 1.0f, 0.0f, 0.0f, 1.0f)

        // 정점 전달
        GLES20.glEnableVertexAttribArray(linePositionHandle)
        GLES20.glVertexAttribPointer(linePositionHandle, 2, GLES20.GL_FLOAT, false, 0, boxBuffer)

        // 라인 스트립으로 그리기
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, 5)

        GLES20.glDisableVertexAttribArray(linePositionHandle)

        // 텍스트 렌더링 (라벨) - 바운딩 박스 아래쪽에 위치
        renderBoundingBoxLabel(box, left, bottom)
    }

    /**
     * 바운딩 박스 라벨 렌더링
     */
    private fun renderBoundingBoxLabel(box: BoundingBox, x: Float, bottomY: Float) {
        val label = "${box.clsName} ${String.format("%.2f", box.cnf)}"

        // 텍스트를 비트맵으로 렌더링
        val textBitmap = createTextBitmap(label)

        if (textBitmap != null) {
            // 텍스트 비트맵을 OpenGL 텍스처로 변환하여 렌더링
            // bottomY에서 아래쪽으로 오프셋 적용
            val textY = bottomY - 0.05f  // 바운딩 박스 아래쪽에 약간의 여백
            renderTextTexture(textBitmap, x, textY)

            // 텍스트 비트맵 정리
            if (!textBitmap.isRecycled) {
                textBitmap.recycle()
            }
        }
    }

    /**
     * 텍스트 비트맵 생성
     */
    private fun createTextBitmap(text: String): Bitmap? {
        try {
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 48f  // 텍스트 크기 증가
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val bounds = android.graphics.Rect()
            paint.getTextBounds(text, 0, text.length, bounds)

            val padding = 12
            val textWidth = bounds.width() + padding * 2
            val textHeight = bounds.height() + padding * 2

            val bitmap = Bitmap.createBitmap(textWidth, textHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 배경 그리기 (반투명 검은색)
            val backgroundPaint = Paint().apply {
                color = Color.argb(200, 0, 0, 0)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                0f, 0f,
                textWidth.toFloat(), textHeight.toFloat(),
                8f, 8f,  // 둥근 모서리
                backgroundPaint
            )

            // 텍스트 그리기
            canvas.drawText(
                text,
                padding.toFloat(),
                textHeight - padding.toFloat(),
                paint
            )

            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "텍스트 비트맵 생성 실패: ${e.message}", e)
            return null
        }
    }

    /**
     * 텍스트 텍스처 렌더링
     */
    private fun renderTextTexture(textBitmap: Bitmap, x: Float, y: Float) {
        try {
            // 텍스처 생성
            val textureIds = IntArray(1)
            GLES20.glGenTextures(1, textureIds, 0)
            val textTextureId = textureIds[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // 텍스트 비트맵을 텍스처로 업로드
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textBitmap, 0)

            // 텍스트 크기 계산 (화면 비율 고려)
            val aspectRatio = if (viewWidth > 0 && viewHeight > 0) {
                viewWidth.toFloat() / viewHeight.toFloat()
            } else {
                1.0f
            }

            val textWidth = 0.25f / aspectRatio  // 가로세로 비율 고려
            val textHeight = 0.06f

            // 텍스트 정점 좌표 (y 좌표 순서 수정)
            val textVertices = floatArrayOf(
                x, y + textHeight,           // 왼쪽 위
                x + textWidth, y + textHeight, // 오른쪽 위
                x, y,                        // 왼쪽 아래
                x + textWidth, y             // 오른쪽 아래
            )

            val textVertexBuffer = ByteBuffer.allocateDirect(textVertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(textVertices)
                .apply { position(0) }

            // 텍스처 좌표 (올바른 방향)
            val textTexCoords = floatArrayOf(
                0.0f, 0.0f,  // 왼쪽 위
                1.0f, 0.0f,  // 오른쪽 위
                0.0f, 1.0f,  // 왼쪽 아래
                1.0f, 1.0f   // 오른쪽 아래
            )

            val textTexCoordBuffer = ByteBuffer.allocateDirect(textTexCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(textTexCoords)
                .apply { position(0) }

            // 이미지 셰이더 프로그램으로 텍스트 렌더링
            GLES20.glUseProgram(imageShaderProgram)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, textVertexBuffer)

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textTexCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId)
            GLES20.glUniform1i(textureHandle, 0)

            // 블렌딩 활성화 (텍스트 투명도)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            // 텍스처 정리
            GLES20.glDeleteTextures(1, intArrayOf(textTextureId), 0)

        } catch (e: Exception) {
            Log.e(TAG, "텍스트 텍스처 렌더링 실패: ${e.message}", e)
        }
    }


    /**
     * 새로운 비트맵 업데이트 (외부에서 호출)
     */
    fun updateBitmap(bitmap: Bitmap?) {
        currentBitmap = bitmap
    }

    /**
     * 바운딩 박스 업데이트 (HomeFragment에서 호출)
     */
    fun updateBoundingBoxes(boxes: List<BoundingBox>) {
        boundingBoxes = boxes
        Log.d(TAG, "바운딩 박스 업데이트: ${boxes.size}개")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)

            // 컴파일 상태 확인
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                Log.e(TAG, "셰이더 컴파일 실패: $error")
                GLES20.glDeleteShader(shader)
                return 0
            }
        }
    }
}
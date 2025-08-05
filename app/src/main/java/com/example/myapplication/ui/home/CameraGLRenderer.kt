package com.example.myapplication.ui.home

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraGLRenderer : GLSurfaceView.Renderer {
    private val TAG = "CameraGLRenderer"

    // 셰이더 프로그램
    private var shaderProgram: Int = 0
    private var textureId: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureHandle: Int = 0

    // 텍스처 상태 관리
    @Volatile
    private var currentTexture: Bitmap? = null
    @Volatile
    private var pendingTexture: Bitmap? = null
    private val textureLock = Any()

    // 뷰포트 크기
    private var viewWidth = 0
    private var viewHeight = 0

    // 정점 버퍼
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // 정점 좌표 (전체 화면)
    private val vertices = floatArrayOf(
        -1.0f, -1.0f,  // 왼쪽 아래
        1.0f, -1.0f,   // 오른쪽 아래
        -1.0f, 1.0f,   // 왼쪽 위
        1.0f, 1.0f     // 오른쪽 위
    )

    // 텍스처 좌표
    private val texCoords = floatArrayOf(
        0.0f, 1.0f,  // 왼쪽 아래
        1.0f, 1.0f,  // 오른쪽 아래
        0.0f, 0.0f,  // 왼쪽 위
        1.0f, 0.0f   // 오른쪽 위
    )

    // 버텍스 셰이더
    private val vertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vPosition;
            vTexCoord = aTexCoord;
        }
    """

    // 프래그먼트 셰이더
    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    init {
        // 버퍼 초기화
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
            .apply { position(0) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 셰이더 프로그램 생성
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        shaderProgram = GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }

        // 핸들 가져오기
        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition")
        texCoordHandle = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(shaderProgram, "uTexture")

        // 초기 텍스처 생성 (빈 텍스처로 시작)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        Log.d(TAG, "GLSurfaceView 초기화 완료")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        Log.d(TAG, "GLSurfaceView 크기 변경: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        synchronized(textureLock) {
            // 새로운 텍스처가 준비되었는지 확인
            if (pendingTexture != null) {
                // 이전 텍스처 정리 (렌더링 완료 후 안전하게)
                if (currentTexture != null && currentTexture != pendingTexture && !currentTexture!!.isRecycled) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    val textures = IntArray(1)
                    GLES20.glGenTextures(1, textures, 0)
                    textureId = textures[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                }

                // 새 텍스처 적용
                currentTexture = pendingTexture
                pendingTexture = null

                // 새 텍스처 업로드
                if (currentTexture != null && !currentTexture!!.isRecycled) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, currentTexture, 0)
                }
            }
        }

        // 렌더링
        if (currentTexture != null && !currentTexture!!.isRecycled) {
            GLES20.glUseProgram(shaderProgram)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(textureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        } else {
            // 텍스처가 없으면 이전 프레임 유지 또는 기본 배경 표시
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f) // 검은 배경
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    /**
     * 새로운 비트맵 업데이트
     */
    fun updateBitmap(bitmap: Bitmap?) {
        synchronized(textureLock) {
            pendingTexture = bitmap?.copy(Bitmap.Config.ARGB_8888, false) // 깊은 복사로 안전성 확보
            if (pendingTexture == null) {
                Log.w(TAG, "업데이트된 비트맵이 null입니다.")
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
package com.shiliu.stream.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 相机 SurfaceTexture -> OpenGL 旋转 -> 编码器输入 Surface / 预览 Surface。
 *
 * 预览与编码共用同一套旋转与镜像逻辑：编码输出按整帧铺满，预览按画面比例
 * 居中裁剪铺满控件，因此两路画面在方向和比例上始终一致。
 */
class RotatingGlPipeline(
    cameraWidth: Int,
    cameraHeight: Int,
    rotationDegrees: Int,
    mirror: Boolean,
    outputSurface: Surface?,
    outputWidth: Int,
    outputHeight: Int,
    previewSurface: Surface?,
    previewWidth: Int,
    previewHeight: Int
) {

    @Volatile
    private var cameraWidth = cameraWidth

    @Volatile
    private var cameraHeight = cameraHeight

    @Volatile
    private var mirror = mirror

    @Volatile
    private var rotationDegrees = normalize(rotationDegrees)

    @Volatile
    private var outputWidth = outputWidth

    @Volatile
    private var outputHeight = outputHeight

    @Volatile
    private var previewWidth = previewWidth

    @Volatile
    private var previewHeight = previewHeight

    /** GL 线程上发现该标记后重建顶点纹理坐标。 */
    @Volatile
    private var verticesDirty = false

    /** 屏幕方向变化：只更新旋转角，不用重建管线，切换几乎无延迟。 */
    fun setRotation(degrees: Int) {
        val normalized = normalize(degrees)
        if (rotationDegrees == normalized) return
        rotationDegrees = normalized
        verticesDirty = true
    }

    /** 换镜头后更新画面尺寸与镜像方向。 */
    fun configure(cameraWidth: Int, cameraHeight: Int, mirror: Boolean) {
        if (cameraWidth <= 0 || cameraHeight <= 0) return
        runOnGl {
            this.cameraWidth = cameraWidth
            this.cameraHeight = cameraHeight
            if (this.mirror != mirror) {
                this.mirror = mirror
                verticesDirty = true
            }
            surfaceTexture?.setDefaultBufferSize(cameraWidth, cameraHeight)
        }
    }

    /**
     * 切换推流输出：传入编码器的输入 Surface 时开始送帧，传 null 时停止送帧。
     * 预览不受影响，因此开播/停播/切换方向都不需要重建相机会话。
     */
    fun setOutput(surface: Surface?, width: Int, height: Int) {
        runOnGl {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && width > 0 && height > 0) {
                val created = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
                if (created == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "编码输出 eglCreateWindowSurface 失败: ${EGL14.eglGetError()}")
                } else {
                    eglSurface = created
                    outputWidth = width
                    outputHeight = height
                }
            }
            makeCurrentDefault()
        }
    }

    /** 预览 Surface（TextureView 重建）切换。 */
    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        runOnGl {
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                runCatching { EGL14.eglDestroySurface(eglDisplay, previewEglSurface) }
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && width > 0 && height > 0) {
                val created = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
                if (created == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "预览 eglCreateWindowSurface 失败: ${EGL14.eglGetError()}")
                } else {
                    previewEglSurface = created
                    previewWidth = width
                    previewHeight = height
                }
            }
            makeCurrentDefault()
        }
    }

    /** 预览控件尺寸变化。 */
    fun setPreviewSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            previewWidth = width
            previewHeight = height
        }
    }

    /** 在 GL 线程上同步执行，确保 EGL Surface 的销毁/创建不会与送帧并发。 */
    private fun runOnGl(block: () -> Unit) {
        if (released) return
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                if (!released) block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun makeCurrentDefault() {
        val current = when {
            eglSurface != EGL14.EGL_NO_SURFACE -> eglSurface
            else -> previewEglSurface
        }
        if (current != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, current, current, eglContext)
        }
    }

    companion object {
        private const val TAG = "RotatingGl"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
    }

    /** 构造时传入的输出 Surface，仅在 EGL 初始化阶段使用一次。 */
    private val initialOutputSurface = outputSurface
    private val initialPreviewSurface = previewSurface

    private val thread = HandlerThread("shiliu-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val readyLatch = CountDownLatch(1)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var vertexBuffer: FloatBuffer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private var setupError: String? = null

    @Volatile
    private var released = false

    /** 相机写入的目标 Surface，交给 CameraSession。 */
    @Volatile
    var inputSurface: Surface? = null
        private set

    init {
        handler.post { setup() }
        try {
            readyLatch.await()
        } catch (e: InterruptedException) {
            setupError = "初始化被中断"
        }
        if (setupError != null || inputSurface == null) {
            val reason = setupError ?: "无法创建方向转换管线"
            release()
            throw IllegalStateException(reason)
        }
    }

    private fun setup() {
        try {
            initEgl()
            initProgram()
            initTexture()
        } catch (e: Exception) {
            setupError = e.message ?: "GL 初始化失败"
            Log.e(TAG, "初始化失败: ${e.message}", e)
        } finally {
            readyLatch.countDown()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay 失败")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize 失败")
        }

        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw IllegalStateException("没有可用的 EGL 配置")
        }
        val config = configs[0] ?: throw IllegalStateException("没有可用的 EGL 配置")
        eglConfig = config

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext 失败")

        if (initialOutputSurface != null) {
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, config, initialOutputSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("编码输出 eglCreateWindowSurface 失败")
        }
        if (initialPreviewSurface != null) {
            previewEglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, config, initialPreviewSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (previewEglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("预览 eglCreateWindowSurface 失败")
        }

        val current = if (eglSurface != EGL14.EGL_NO_SURFACE) eglSurface else previewEglSurface
        if (current == EGL14.EGL_NO_SURFACE) throw IllegalStateException("没有可用的输出 Surface")
        if (!EGL14.eglMakeCurrent(eglDisplay, current, current, eglContext)) {
            throw IllegalStateException("eglMakeCurrent 失败")
        }
    }

    private fun initProgram() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glBindAttribLocation(program, 1, "aTexCoord")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            program = 0
            throw IllegalStateException("着色器链接失败：$log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        vertexBuffer = buildVertices()
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("着色器编译失败：$log")
        }
        return shader
    }

    private fun initTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        val texture = SurfaceTexture(false)
        texture.attachToGLContext(textureId)
        texture.setDefaultBufferSize(cameraWidth, cameraHeight)
        texture.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = texture
        inputSurface = Surface(texture)
    }

    private fun drawFrame() {
        if (released) return
        val texture = surfaceTexture ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
        } catch (e: Exception) {
            Log.w(TAG, "取帧失败: ${e.message}")
            return
        }

        if (verticesDirty) {
            verticesDirty = false
            vertexBuffer = buildVertices()
        }

        val timestamp = if (texture.timestamp > 0L) texture.timestamp else System.nanoTime()
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            // 编码输出：整帧铺满，保持此前已验证的推流几何不变。
            drawTo(eglSurface, outputWidth, outputHeight, 0, 0, outputWidth, outputHeight, timestamp)
        }
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            val viewWidth = previewWidth
            val viewHeight = previewHeight
            if (viewWidth > 0 && viewHeight > 0) {
                // 预览输出：按画面自身比例等比缩放并居中裁剪，铺满控件，
                // 与编码输出使用同一份纹理坐标，方向与比例完全一致。
                val aspect = if (rotationDegrees % 180 == 0) {
                    cameraHeight.toFloat() / cameraWidth
                } else {
                    cameraWidth.toFloat() / cameraHeight
                }
                var drawWidth = viewWidth
                var drawHeight = viewHeight
                if (viewWidth.toFloat() / viewHeight > aspect) {
                    drawHeight = (viewWidth / aspect).toInt()
                } else {
                    drawWidth = (viewHeight * aspect).toInt()
                }
                val x = (viewWidth - drawWidth) / 2
                val y = (viewHeight - drawHeight) / 2
                drawTo(previewEglSurface, viewWidth, viewHeight, x, y, drawWidth, drawHeight, timestamp)
            }
        }
        // 恢复默认输出，供下一帧 updateTexImage 使用
        makeCurrentDefault()
    }

    private fun drawTo(
        surface: EGLSurface,
        viewWidth: Int,
        viewHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        timestamp: Long
    ) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            Log.w(TAG, "eglMakeCurrent 失败: ${EGL14.eglGetError()}")
            return
        }
        GLES20.glViewport(x, y, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        val buffer = vertexBuffer ?: return
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, buffer)
        buffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, timestamp)
        if (!EGL14.eglSwapBuffers(eglDisplay, surface)) {
            Log.w(TAG, "eglSwapBuffers 失败: ${EGL14.eglGetError()}")
        }
    }

    /**
     * 每个顶点的 (x, y, s, t)：顶点铺满整个输出视口，旋转通过纹理坐标实现。
     * 90/270 时输出宽高已经互换，画面按顺时针方向旋正。
     * 前置镜像作用在输出坐标上（与预览的 postScale(-1, 1) 一致），必须在旋转前翻转 x。
     */
    private fun buildVertices(): FloatBuffer {
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val data = FloatArray(16)
        for (i in 0 until 4) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            val dx = if (mirror) 1f - (x + 1f) / 2f else (x + 1f) / 2f
            val dy = (y + 1f) / 2f
            var s: Float
            var t: Float
            when (rotationDegrees) {
                90 -> {
                    s = 1f - dy
                    t = dx
                }
                180 -> {
                    s = 1f - dx
                    t = 1f - dy
                }
                270 -> {
                    s = dy
                    t = 1f - dx
                }
                else -> {
                    s = dx
                    t = dy
                }
            }
            data[i * 4] = x
            data[i * 4 + 1] = y
            data[i * 4 + 2] = s
            data[i * 4 + 3] = t
        }
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }
    }

    /** 在 GL 线程上同步释放全部资源，确保不会再有帧写入编码器。 */
    fun release() {
        if (released || !thread.isAlive) return
        val latch = CountDownLatch(1)
        handler.post {
            if (!released) {
                released = true
                releaseGl()
            }
            latch.countDown()
            thread.quitSafely()
        }
        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun releaseGl() {
        val texture = surfaceTexture
        surfaceTexture = null
        if (texture != null) {
            texture.setOnFrameAvailableListener(null)
            runCatching { texture.detachFromGLContext() }
            runCatching { texture.release() }
        }
        inputSurface?.let { runCatching { it.release() } }
        inputSurface = null

        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
        vertexBuffer = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            }
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, previewEglSurface) }
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            }
            runCatching { EGL14.eglReleaseThread() }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        previewEglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }
}

package com.shiliu.stream.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface

/**
 * 负责 Camera2 的打开与会话：相机画面始终写入同一个目标 Surface（GL 管线的输入），
 * 预览与编码都由 GL 管线分发。所有相机操作都串行在单一 HandlerThread 上。
 */
class CameraSession(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onCameraStarted()
        fun onCameraError(message: String)
    }

    private val thread = HandlerThread("shiliu-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var generation = 0
    private var opening = false

    private var lens: CameraLens? = null
    private var targetSurface: Surface? = null
    private var targetFps = 30

    /** 会话真正开始送帧时触发，让调用方把渲染参数与新相机帧对齐。 */
    private var pendingOnConfigured: (() -> Unit)? = null

    @Volatile
    private var released = false

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            if (released) {
                camera.close()
                return
            }
            device = camera
            rebuildSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            camera.close()
            if (device !== camera) return
            device = null
            session = null
            if (!released) listener.onCameraError("相机连接已断开")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            camera.close()
            if (device !== camera) return
            device = null
            session = null
            if (!released) listener.onCameraError("相机发生错误（$error）")
        }
    }

    /** 切换相机的目标 Surface；旧 Surface 的所有者通过 onClosed 释放。 */
    fun setSurface(surface: Surface?, onClosed: (() -> Unit)? = null) {
        handler.post {
            if (released) {
                onClosed?.invoke()
                return@post
            }
            if (targetSurface === surface) return@post
            closeSessionLocked()
            targetSurface = surface
            onClosed?.invoke()
            if (surface != null) {
                if (device == null) openCameraLocked() else rebuildSession()
            }
        }
    }

    fun setLens(newLens: CameraLens?, onConfigured: (() -> Unit)? = null) {
        handler.post {
            if (released || lens?.id == newLens?.id) {
                onConfigured?.invoke()
                return@post
            }
            closeSessionLocked()
            closeDeviceLocked()
            lens = newLens
            pendingOnConfigured = onConfigured
            if (targetSurface != null) openCameraLocked()
        }
    }

    /** 推流帧率变化：重建会话让 AE 目标帧率跟随。 */
    fun setFps(fps: Int) {
        handler.post {
            if (released || fps == targetFps) return@post
            targetFps = fps
            if (device != null && targetSurface != null) rebuildSession()
        }
    }

    /** 权限恢复或错误后重试打开相机。 */
    fun restart() {
        handler.post {
            if (released) return@post
            closeSessionLocked()
            closeDeviceLocked()
            if (targetSurface != null) openCameraLocked()
        }
    }

    fun release(onClosed: (() -> Unit)? = null) {
        released = true
        handler.post {
            generation++
            closeSessionLocked()
            closeDeviceLocked()
            targetSurface = null
            onClosed?.invoke()
            thread.quitSafely()
        }
    }

    private fun openCameraLocked() {
        val activeLens = lens ?: return
        if (targetSurface == null) return
        if (device != null || opening) return
        opening = true
        try {
            manager.openCamera(activeLens.id, deviceCallback, handler)
        } catch (e: Exception) {
            opening = false
            listener.onCameraError("无法打开镜头：${e.message ?: "未知错误"}")
        }
    }

    private fun rebuildSession() {
        val camera = device ?: return
        val surface = targetSurface ?: run {
            closeSessionLocked()
            return
        }

        closeSessionLocked()
        val gen = generation
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(created: CameraCaptureSession) {
                if (released || gen != generation || device !== camera) {
                    created.close()
                    return
                }
                session = created
                startRepeating(camera, created, surface)
            }

            override fun onConfigureFailed(created: CameraCaptureSession) {
                if (gen == generation) listener.onCameraError("无法建立相机会话")
            }
        }
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), callback, handler)
        } catch (e: Exception) {
            listener.onCameraError("建立相机会话失败：${e.message}")
        }
    }

    private fun startRepeating(
        camera: CameraDevice,
        captureSession: CameraCaptureSession,
        surface: Surface
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            request.addTarget(surface)
            request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)

            val afModes = lens?.afModes ?: intArrayOf()
            if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            pickFpsRange(lens?.fpsRanges.orEmpty(), targetFps)?.let {
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }

            captureSession.setRepeatingRequest(request.build(), null, handler)
            pendingOnConfigured?.invoke()
            pendingOnConfigured = null
            listener.onCameraStarted()
        } catch (e: Exception) {
            listener.onCameraError("启动相机预览失败：${e.message}")
        }
    }

    private fun pickFpsRange(ranges: List<IntRange>, fps: Int): Range<Int>? {
        if (ranges.isEmpty()) return null
        val exact = ranges.firstOrNull { it.first == fps && it.last == fps }
        val covering = ranges.filter { fps in it }.maxByOrNull { it.first }
        val below = ranges.filter { it.last <= fps }.maxByOrNull { it.last }
        val chosen = exact ?: covering ?: below ?: ranges.minByOrNull { it.last }
        return chosen?.let { Range(it.first, it.last) }
    }

    private fun closeSessionLocked() {
        generation++
        val current = session
        session = null
        try {
            current?.close()
        } catch (e: Exception) {
            Log.w("CameraSession", "关闭会话失败: ${e.message}")
        }
    }

    private fun closeDeviceLocked() {
        val current = device
        device = null
        opening = false
        try {
            current?.close()
        } catch (e: Exception) {
            Log.w("CameraSession", "关闭相机失败: ${e.message}")
        }
    }
}

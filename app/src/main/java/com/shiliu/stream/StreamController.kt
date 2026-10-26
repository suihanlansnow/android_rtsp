package com.shiliu.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.shiliu.stream.camera.CameraCatalog
import com.shiliu.stream.camera.CameraLens
import com.shiliu.stream.camera.CameraSession
import com.shiliu.stream.camera.VideoOption
import com.shiliu.stream.media.H264Encoder
import com.shiliu.stream.media.RotatingGlPipeline
import com.shiliu.stream.media.RtpBroadcaster
import com.shiliu.stream.rtsp.RtspServer

data class ToastMessage(val id: Int, val text: String)

/**
 * 应用层状态与推流编排：镜头/分辨率选择、Camera2 + MediaCodec + RTSP 生命周期。
 */
class StreamController private constructor(context: Context) {

    companion object {
        const val RTSP_PORT = 8554
        const val RTP_BROADCAST_PORT = 9998
        private const val START_TIMEOUT_MS = 6000L

        @Volatile
        private var instance: StreamController? = null

        /** 进程级单例：Activity 与前台服务共用同一套相机、编码与推流状态。 */
        fun get(context: Context): StreamController =
            instance ?: synchronized(this) {
                instance ?: StreamController(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var cameraSession: CameraSession? = null
    private var encoder: H264Encoder? = null
    private var pipeline: RotatingGlPipeline? = null
    private var server: RtspServer? = null
    private var rtpBroadcaster: RtpBroadcaster? = null
    private var startTimeout: Runnable? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var displayRotation = 0

    var lenses by mutableStateOf<List<CameraLens>>(emptyList())
        private set
    var loadingLenses by mutableStateOf(true)
        private set
    var selectedLens by mutableStateOf<CameraLens?>(null)
        private set
    var selectedSize by mutableStateOf<VideoOption?>(null)
        private set
    var password by mutableStateOf("")
        private set
    var streaming by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var cameraReady by mutableStateOf(false)
        private set
    var cameraError by mutableStateOf<String?>(null)
        private set
    var startElapsed by mutableLongStateOf(0L)
        private set
    var localIp by mutableStateOf("--")
        private set
    var toast by mutableStateOf<ToastMessage?>(null)
        private set
    var clientCount by mutableStateOf(0)
        private set
    var rtpClientCount by mutableStateOf(0)
        private set
    var statFps by mutableStateOf(0)
        private set
    var statBitrate by mutableLongStateOf(0L)
        private set
    var droppedPackets by mutableLongStateOf(0L)
        private set

    private var toastId = 0
    private val statFrames = AtomicInteger(0)
    private val statBytes = AtomicLong(0L)
    private var statWindowMs = 0L

    val hasMultipleLenses: Boolean get() = lenses.size > 1
    val hasCameraPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    val streamPath: String get() = "live/cam${selectedLens?.index ?: 0}"

    /** 可复制、带凭据的真实地址。 */
    val rtspUrl: String
        get() {
            val host = if (localIp == "--") "127.0.0.1" else localIp
            val base = "rtsp://$host:$RTSP_PORT/$streamPath"
            return if (password.isEmpty()) base
            else "rtsp://admin:${Uri.encode(password)}@$host:$RTSP_PORT/$streamPath"
        }

    /** 界面上展示的地址，密码部分打码。 */
    val displayRtspUrl: String
        get() {
            val host = if (localIp == "--") "127.0.0.1" else localIp
            return if (password.isEmpty()) "rtsp://$host:$RTSP_PORT/$streamPath"
            else "rtsp://admin:••••@$host:$RTSP_PORT/$streamPath"
        }

    /** 裸 RTP over UDP 广播地址（无信令、无密码，局域网内直接 ffmpeg/VLC 拉）。 */
    val rtpUrl: String
        get() {
            val host = if (localIp == "--") "127.0.0.1" else localIp
            return "udp://@$host:$RTP_BROADCAST_PORT"
        }

    /** 显示版 RTP 地址（跟真实值相同，因为 UDP 没有鉴权）。 */
    val displayRtpUrl: String get() = rtpUrl

    init {
        localIp = NetUtils.localIpv4() ?: "--"
        loadLenses()
    }

    fun notify(text: String) {
        toast = ToastMessage(++toastId, text)
    }

    fun clearToast(id: Int) {
        if (toast?.id == id) toast = null
    }

    private fun loadLenses() {
        Thread {
            val result = try {
                CameraCatalog.query(appContext)
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                loadingLenses = false
                lenses = result
                val first = result.firstOrNull()
                selectedLens = first
                selectedSize = first?.defaultSize()
                if (first != null) {
                    val session = ensureCameraSession()
                    session.setLens(first)
                    if (previewSurface != null) ensurePipeline()
                } else {
                    notify("未检测到可用摄像头")
                }
            }
        }.start()
    }

    fun onCameraPermissionGranted() {
        localIp = NetUtils.localIpv4() ?: localIp
        if (pipeline == null) ensurePipeline() else ensureCameraSession().restart()
    }

    fun onPreviewSurface(surface: Surface?, width: Int, height: Int) {
        previewSurface = surface
        if (surface == null) {
            cameraReady = false
            // 只摘掉预览输出，相机与推流继续运行
            pipeline?.setPreviewSurface(null, 0, 0)
            return
        }
        previewWidth = width
        previewHeight = height
        val activePipeline = pipeline
        if (activePipeline != null) {
            activePipeline.setPreviewSurface(surface, width, height)
        } else {
            ensurePipeline()
        }
    }

    fun onPreviewLayoutChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        previewWidth = width
        previewHeight = height
        pipeline?.setPreviewSize(width, height)
    }

    /**
     * 屏幕方向变化：GL 管线立即按新方向旋转（预览与码流同源，因此两者同时生效）；
     * 推流中只需要按新方向重建编码器与 RTSP 服务，相机会话不重启。
     */
    fun onDisplayRotationChanged(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == displayRotation) return
        displayRotation = normalized
        val activePipeline = pipeline ?: return
        // 相机与手机一起转动：按屏幕旋转角反向补偿，画面在世界坐标中保持正向
        activePipeline.setRotation((360 - normalized) % 360)
        if (!streaming && !connecting) return
        val lens = selectedLens ?: return
        val size = selectedSize ?: return
        startEncoderAndServer(activePipeline, lens, size)
    }

    fun retryCamera() {
        cameraError = null
        cameraReady = false
        cameraSession?.restart()
    }

    fun cycleLens() {
        if (streaming || connecting) return
        if (lenses.size <= 1) return
        val currentIndex = lenses.indexOfFirst { it.id == selectedLens?.id }.coerceAtLeast(0)
        selectLens(lenses[(currentIndex + 1) % lenses.size])
    }

    fun selectLens(lens: CameraLens) {
        if (streaming || connecting) return
        selectedLens = lens
        selectedSize = lens.defaultSize()
        cameraReady = false
        cameraError = null
        ensureCameraSession().setLens(lens) {
            // 只在新相机真正开始送帧后再切换镜像/尺寸，
            // 避免用新参数渲染旧镜头残留帧，出现一帧镜像。
            pipeline?.configure(
                lens.previewSize.width,
                lens.previewSize.height,
                lens.facing == CameraCharacteristics.LENS_FACING_FRONT
            )
        }
    }

    fun selectSize(option: VideoOption) {
        if (streaming || connecting) return
        selectedSize = option
    }

    fun updatePassword(value: String) {
        if (streaming || connecting) return
        password = value
    }

    fun start() {
        if (streaming || connecting) return
        val lens = selectedLens ?: run {
            notify("请选择镜头")
            return
        }
        val size = selectedSize ?: run {
            notify("请选择分辨率")
            return
        }
        if (!hasCameraPermission) {
            notify("需要相机权限")
            return
        }
        if (pipeline == null && previewSurface == null) {
            notify("预览尚未就绪，请稍后重试")
            return
        }

        connecting = true
        cameraError = null
        localIp = NetUtils.localIpv4() ?: localIp

        try {
            val activePipeline = ensurePipeline()
                ?: throw IllegalStateException("预览管线未就绪")
            startEncoderAndServer(activePipeline, lens, size)
            ensureCameraSession().setFps(size.fps)
            scheduleStartTimeout()
        } catch (e: Exception) {
            teardownStream()
            notify("启动失败：${e.message ?: "无法创建编码器"}")
        }
    }

    /**
     * 建立编码器与 RTSP 服务，并把编码输出接到 GL 管线上。
     * 相机与预览保持不动，因此开播、停播、切换方向都是百毫秒级操作。
     */
    private fun startEncoderAndServer(
        activePipeline: RotatingGlPipeline,
        lens: CameraLens,
        size: VideoOption
    ) {
        val rotation = lens.rotationFor(displayRotation)
        val swap = rotation % 180 != 0
        val encodeWidth = if (swap) size.height else size.width
        val encodeHeight = if (swap) size.width else size.height

        // 先断开 GL 输出，确保旧编码器不会再收到帧
        activePipeline.setOutput(null, 0, 0)
        server?.stop()
        server = null
        rtpBroadcaster?.stop()
        rtpBroadcaster = null
        encoder?.stop()
        encoder = null

        val newEncoder = H264Encoder(
            width = encodeWidth,
            height = encodeHeight,
            fps = size.fps,
            bitrate = bitrateFor(size),
            listener = encoderListener
        )
        newEncoder.start()
        encoder = newEncoder

        val newServer = RtspServer(
            port = RTSP_PORT,
            path = streamPath,
            host = if (localIp == "--") "127.0.0.1" else localIp,
            username = "admin",
            password = password,
            width = encodeWidth,
            height = encodeHeight,
            fps = size.fps,
            onClientJoined = { encoder?.requestKeyFrame() }
        )
        newServer.start()
        server = newServer

        val newBroadcaster = RtpBroadcaster(
            port = RTP_BROADCAST_PORT,
            onNewClient = { encoder?.requestKeyFrame() }
        )
        newBroadcaster.start()
        rtpBroadcaster = newBroadcaster

        activePipeline.setOutput(newEncoder.inputSurface, encodeWidth, encodeHeight)
    }

    fun stop() {
        if (!streaming && !connecting) return
        teardownStream()
        notify("已停止推流")
    }

    /** 停止推流链路，保留相机会话与预览管线。 */
    private fun teardownStream() {
        cancelStartTimeout()
        streaming = false
        connecting = false
        startElapsed = 0L
        clientCount = 0

        pipeline?.setOutput(null, 0, 0)

        server?.stop()
        server = null
        rtpBroadcaster?.stop()
        rtpBroadcaster = null
        encoder?.stop()
        encoder = null
        StreamService.stop(appContext)
        resetStats()
    }

    /** 每秒刷新一次：实际帧率、实际码率、两路客户端数与丢弃包数。 */
    fun refreshStats() {
        val now = SystemClock.elapsedRealtime()
        val frames = statFrames.getAndSet(0)
        val bytes = statBytes.getAndSet(0)
        val elapsed = now - statWindowMs
        statWindowMs = now
        if (elapsed >= 500) {
            statFps = ((frames * 1000L + elapsed / 2) / elapsed).toInt()
            statBitrate = bytes * 8000L / elapsed
        }
        clientCount = server?.clientCount() ?: 0
        rtpClientCount = rtpBroadcaster?.clientCount() ?: 0
        droppedPackets = server?.droppedPackets() ?: 0L
    }

    private fun resetStats() {
        statFrames.set(0)
        statBytes.set(0)
        statWindowMs = SystemClock.elapsedRealtime()
        statFps = 0
        statBitrate = 0L
        droppedPackets = 0L
        rtpClientCount = 0
    }

    /**
     * Activity 销毁：推流中只解绑预览 Surface，相机与编码继续在前台服务里运行；
     * 未推流时释放相机资源，避免后台占用摄像头。
     */
    fun onActivityDestroyed() {
        if (streaming) {
            onPreviewSurface(null, 0, 0)
        } else {
            // 未推流（含正在建立连接）时直接释放，避免后台空占摄像头
            release()
        }
    }

    fun release() {
        cancelStartTimeout()
        server?.stop()
        server = null
        rtpBroadcaster?.stop()
        rtpBroadcaster = null
        encoder?.stop()
        encoder = null
        val activePipeline = pipeline
        pipeline = null
        previewSurface?.release()
        previewSurface = null
        val session = cameraSession
        cameraSession = null
        if (session != null) {
            session.release { activePipeline?.release() }
        } else {
            activePipeline?.release()
        }
        streaming = false
        connecting = false
    }

    private fun ensureCameraSession(): CameraSession {
        var session = cameraSession
        if (session == null) {
            session = CameraSession(appContext, cameraListener)
            cameraSession = session
        }
        return session
    }

    /** 预览与编码共用的 GL 管线；相机画面只经由它分发。 */
    private fun ensurePipeline(): RotatingGlPipeline? {
        pipeline?.let { return it }
        val lens = selectedLens ?: return null
        val created = RotatingGlPipeline(
            cameraWidth = lens.previewSize.width,
            cameraHeight = lens.previewSize.height,
            rotationDegrees = (360 - displayRotation) % 360,
            mirror = lens.facing == CameraCharacteristics.LENS_FACING_FRONT,
            outputSurface = null,
            outputWidth = 0,
            outputHeight = 0,
            previewSurface = previewSurface,
            previewWidth = previewWidth,
            previewHeight = previewHeight
        )
        pipeline = created
        val surface = created.inputSurface
            ?: run {
                pipeline = null
                created.release()
                return null
            }
        ensureCameraSession().setSurface(surface)
        return created
    }

    private fun scheduleStartTimeout() {
        val runnable = Runnable {
            if (connecting) {
                teardownStream()
                notify("该分辨率没有输出视频，请更换分辨率")
            }
        }
        startTimeout = runnable
        main.postDelayed(runnable, START_TIMEOUT_MS)
    }

    private fun cancelStartTimeout() {
        startTimeout?.let { main.removeCallbacks(it) }
        startTimeout = null
    }

    private fun bitrateFor(size: VideoOption): Int {
        val raw = (size.width.toLong() * size.height * size.fps * 0.07).toInt()
        return raw.coerceIn(800_000, 12_000_000)
    }

    private val encoderListener = object : H264Encoder.Listener {
        override fun onCodecConfig(sps: ByteArray, pps: ByteArray) {
            server?.setCodecConfig(sps, pps)
            rtpBroadcaster?.setCodecConfig(sps, pps)
            main.post {
                if (connecting) {
                    cancelStartTimeout()
                    connecting = false
                    streaming = true
                    startElapsed = SystemClock.elapsedRealtime()
                    resetStats()
                    StreamService.start(appContext, displayRtspUrl, displayRtpUrl)
                    notify("推流已就绪 · RTSP / RTP 服务运行中")
                }
            }
        }

        override fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
            statFrames.incrementAndGet()
            statBytes.addAndGet(data.size.toLong())
            server?.publish(data, ptsUs, keyFrame)
            rtpBroadcaster?.publish(data, ptsUs, keyFrame)
        }
    }

    private val cameraListener = object : CameraSession.Listener {
        override fun onCameraStarted() {
            main.post {
                cameraReady = true
                cameraError = null
            }
        }

        override fun onCameraError(message: String) {
            main.post {
                cameraReady = false
                cameraError = message
                notify(message)
                if (streaming || connecting) {
                    teardownStream()
                }
            }
        }
    }
}

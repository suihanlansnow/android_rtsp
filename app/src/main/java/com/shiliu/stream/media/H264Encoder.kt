package com.shiliu.stream.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 摄像头 Surface -> MediaCodec(H.264) 编码器。输出 Annex-B 码流。
 */
class H264Encoder(
    width: Int,
    height: Int,
    private val fps: Int,
    bitrate: Int,
    private val listener: Listener
) {
    interface Listener {
        fun onCodecConfig(sps: ByteArray, pps: ByteArray)
        fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        applyLowLatencyOptions(format)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    /**
     * 直播场景的编码参数：恒定码率、无 B 帧、关键帧携带参数集、编码器实时优先。
     * 不同机型支持度差异大，全部按需忽略。
     */
    private fun applyLowLatencyOptions(format: MediaFormat) {
        runCatching {
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 直播不使用 B 帧，消除帧重排延迟
            runCatching { format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
            // 关键帧前附加 SPS/PPS，新客户端可直接解码
            runCatching { format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { format.setInteger(MediaFormat.KEY_LATENCY, 0) }
            runCatching { format.setInteger(MediaFormat.KEY_PRIORITY, 0) }
        }
    }

    fun start() {
        codec.start()
        running.set(true)
        drainThread = Thread({ drainLoop() }, "shiliu-h264").apply { start() }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        } catch (e: Exception) {
            Log.w("H264Encoder", "请求关键帧失败: ${e.message}")
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            drainThread?.join(1200)
        } catch (_: InterruptedException) {
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(codec.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (index < 0) continue
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buffer.get(data)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                readConfigBuffer(data)
                            } else {
                                listener.onFrame(
                                    data,
                                    info.presentationTimeUs,
                                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                )
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("H264Encoder", "编码线程异常: ${e.message}", e)
        }
    }

    private fun readFormat(format: MediaFormat) {
        val nals = ArrayList<ByteArray>()
        for (key in listOf("csd-0", "csd-1")) {
            val source = format.getByteBuffer(key) ?: continue
            val copy = source.duplicate()
            val bytes = ByteArray(copy.remaining())
            copy.get(bytes)
            nals.addAll(H264Utils.splitAnnexB(bytes))
        }
        val sps = nals.firstOrNull { H264Utils.nalType(it) == 7 }
        val pps = nals.firstOrNull { H264Utils.nalType(it) == 8 }
        if (sps != null && pps != null) listener.onCodecConfig(sps, pps)
    }

    private fun readConfigBuffer(data: ByteArray) {
        val nals = H264Utils.splitAnnexB(data)
        val sps = nals.firstOrNull { H264Utils.nalType(it) == 7 }
        val pps = nals.firstOrNull { H264Utils.nalType(it) == 8 }
        if (sps != null && pps != null) listener.onCodecConfig(sps, pps)
    }
}

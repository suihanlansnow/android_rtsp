package com.shiliu.stream.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.util.Size
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 一个可选的分辨率/帧率组合，全部来源于摄像头 + 硬件编码器实际支持的能力。
 */
data class VideoOption(
    val width: Int,
    val height: Int,
    val fps: Int
) {
    val label: String
        get() = when {
            width * height >= 3840 * 2160 -> "4K"
            width * height >= 2560 * 1440 -> "2K"
            width >= 1920 && height >= 1080 -> "1080P"
            width >= 1280 && height >= 720 -> "720P"
            width == 640 && height == 480 -> "VGA"
            width == 352 && height == 288 -> "CIF"
            else -> ratio()
        }

    val text: String get() = "$label · ${fps}fps"
    val detail: String get() = "${width}×${height} · $label"

    private fun ratio(): String {
        val g = gcd(width, height)
        return "${width / g}:${height / g}"
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0) 1 else x
    }
}

/**
 * 设备上的一个物理/逻辑镜头。名称、朝向、分辨率列表均来自 Camera2 的
 * CameraCharacteristics，没有写死的镜头数量。
 */
data class CameraLens(
    val id: String,
    val facing: Int,
    val index: Int,
    val shortName: String,
    val name: String,
    val sensorOrientation: Int,
    val fpsRanges: List<IntRange>,
    val afModes: IntArray,
    val previewSize: Size,
    val sizes: List<VideoOption>
) {
    val facingText: String
        get() = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "后置"
            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
            else -> "镜头"
        }

    val displayName: String get() = "$facingText · $name"

    /** 当前屏幕方向下，相机画面需要顺时针旋转的角度。 */
    fun rotationFor(displayRotation: Int): Int {
        val display = ((displayRotation % 360) + 360) % 360
        val raw = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            sensorOrientation + display
        } else {
            sensorOrientation - display
        }
        return ((raw % 360) + 360) % 360
    }

    fun defaultSize(): VideoOption? =
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.firstOrNull { it.width * it.height <= 1920 * 1080 }
            ?: sizes.firstOrNull()
}

/**
 * 读取系统相机配置：
 * 1. 有几个镜头就用几个，单镜头时 UI 不再提供切换；
 * 2. 每个镜头的分辨率取 Camera2 输出尺寸 ∩ H.264 硬件编码器能力；
 * 3. 帧率取该尺寸的最小帧间隔与 AE 支持的帧率范围。
 */
object CameraCatalog {

    private const val TAG = "CameraCatalog"

    fun query(context: Context): List<CameraLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val encoders = avcEncoders()
        val candidates = ArrayList<Candidate>()

        for (id in manager.cameraIdList) {
            try {
                val ch = manager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_BACK
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                val sizes = supportedSizes(ch, map, encoders)
                if (sizes.isEmpty()) continue

                val fpsRanges = (ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?: arrayOf(android.util.Range(15, 30)))
                    .map { it.lower..it.upper }
                val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val previewSize = choosePreviewSize(map)
                val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val fov = diagonalFov(ch)

                candidates.add(
                    Candidate(
                        id = id,
                        facing = facing,
                        sensorOrientation = sensorOrientation,
                        fpsRanges = fpsRanges,
                        afModes = afModes,
                        previewSize = previewSize,
                        sizes = sizes,
                        fov = fov
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "读取相机 $id 失败: ${e.message}")
            }
        }

        return buildLenses(candidates)
    }

    private data class Candidate(
        val id: String,
        val facing: Int,
        val sensorOrientation: Int,
        val fpsRanges: List<IntRange>,
        val afModes: IntArray,
        val previewSize: Size,
        val sizes: List<VideoOption>,
        val fov: Double?
    )

    private fun buildLenses(candidates: List<Candidate>): List<CameraLens> {
        val rear = candidates.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedWith(compareBy({ rearRank(it) }, { it.id }))
        val front = candidates.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedBy { it.id }
        val external = candidates.filter {
            it.facing != CameraCharacteristics.LENS_FACING_BACK &&
                it.facing != CameraCharacteristics.LENS_FACING_FRONT
        }.sortedBy { it.id }

        val ordered = rear + front + external
        val usedNames = HashMap<String, Int>()
        val result = ArrayList<CameraLens>(ordered.size)

        ordered.forEachIndexed { index, c ->
            val baseName = when (c.facing) {
                CameraCharacteristics.LENS_FACING_BACK -> {
                    if (rear.size == 1) "广角" else rearName(c.fov)
                }
                CameraCharacteristics.LENS_FACING_FRONT -> "自拍"
                else -> "外接"
            }
            // 同名镜头追加序号，避免两个“广角”无法区分
            val used = usedNames.getOrDefault(baseName, 0) + 1
            usedNames[baseName] = used
            val name = if (used > 1) "$baseName $used" else baseName

            result.add(
                CameraLens(
                    id = c.id,
                    facing = c.facing,
                    index = index,
                    shortName = if (!c.sizes.isEmpty()) name else name,
                    name = name,
                    sensorOrientation = c.sensorOrientation,
                    fpsRanges = c.fpsRanges,
                    afModes = c.afModes,
                    previewSize = c.previewSize,
                    sizes = c.sizes
                )
            )
        }
        return result
    }

    private fun rearRank(c: Candidate): Int = when (rearName(c.fov)) {
        "广角" -> 0
        "超广角" -> 1
        "长焦" -> 2
        else -> 3
    }

    private fun rearName(fov: Double?): String = when {
        fov == null -> "广角"
        fov >= 95.0 -> "超广角"
        fov >= 55.0 -> "广角"
        else -> "长焦"
    }

    private fun diagonalFov(ch: CameraCharacteristics): Double? {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (focal <= 0f) return null
        val diagonal = hypot(sensor.width.toDouble(), sensor.height.toDouble())
        return Math.toDegrees(2.0 * atan(diagonal / (2.0 * focal)))
    }

    private fun supportedSizes(
        ch: CameraCharacteristics,
        map: android.hardware.camera2.params.StreamConfigurationMap,
        encoders: List<MediaCodecInfo.VideoCapabilities>
    ): List<VideoOption> {
        val raw = map.getOutputSizes(MediaCodec::class.java)?.toList()
            ?: map.getOutputSizes(ImageFormat.PRIVATE)?.toList()
            ?: emptyList()
        // 相机实际写入的是 SurfaceTexture（再经 GL 旋转送编码器），
        // 因此尺寸还必须被 SurfaceTexture 通道支持。
        val textureSizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { it.width to it.height }
            ?.toHashSet()
            .orEmpty()

        val aeMax = (ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: arrayOf(android.util.Range(15, 30)))
            .maxOfOrNull { it.upper } ?: 30

        val seen = HashSet<Long>()
        return raw.asSequence()
            .filter { it.width >= 320 && it.height >= 240 && it.width >= it.height }
            .filter { textureSizes.isEmpty() || textureSizes.contains(it.width to it.height) }
            .filter { size ->
                encoders.any { caps ->
                    runCatching { caps.isSizeSupported(size.width, size.height) }.getOrDefault(false)
                }
            }
            .filter { seen.add(it.width.toLong() shl 32 or it.height.toLong()) }
            .map { size ->
                VideoOption(
                    width = size.width,
                    height = size.height,
                    fps = fpsFor(map, size, aeMax)
                )
            }
            .sortedWith(compareByDescending<VideoOption> { it.width * it.height }.thenByDescending { it.fps })
            .toList()
    }

    private fun fpsFor(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size,
        aeMax: Int
    ): Int {
        val minDuration = runCatching {
            map.getOutputMinFrameDuration(MediaCodec::class.java, size)
        }.getOrDefault(0L)
        val sizeMax = if (minDuration > 0L) (1_000_000_000L / minDuration).toInt() else aeMax
        val raw = min(aeMax, sizeMax).coerceAtLeast(1)
        return when {
            raw >= 30 -> 30
            raw >= 24 -> 24
            raw >= 15 -> 15
            else -> raw
        }
    }

    private fun choosePreviewSize(
        map: android.hardware.camera2.params.StreamConfigurationMap
    ): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        return sizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    private fun avcEncoders(): List<MediaCodecInfo.VideoCapabilities> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
            .sortedBy { info ->
                val name = info.name.lowercase()
                if (name.contains("google") || name.contains(".sw.")) 1 else 0
            }
            .mapNotNull { info ->
                runCatching { info.getCapabilitiesForType("video/avc").videoCapabilities }.getOrNull()
            }
    }
}

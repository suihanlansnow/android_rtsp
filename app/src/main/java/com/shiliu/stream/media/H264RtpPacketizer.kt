package com.shiliu.stream.media

import kotlin.math.min
import kotlin.random.Random

/**
 * H.264 Annex-B NAL -> RTP 包（单包 / FU-A 分片）。
 *
 * 每个实例管理自己的 RTP 会话状态（sequence / timestamp / ssrc），
 * 因此不同的 Client / Broadcaster 可以各自独立 sequence 空间。
 *
 * 实现：
 * - 单包 NAL <= 1400 字节直接塞进一个 RTP 包；
 * - 大 NAL 用 FU-A 分片（FU indicator + FU header 取代原始 NAL 头，payload 从 nal[1] 开始）；
 * - 关键帧前自动补发 SPS/PPS，保证中途加入的接收端能解码；
 * - PT 固定 96，时钟频率 90kHz。
 */
class H264RtpPacketizer {

    companion object {
        private const val MAX_PACKET = 1400
        private const val RTP_PAYLOAD = MAX_PACKET - 12   // 12 字节 RTP header
        private const val FU_A_HEADER = 2                   // FU indicator + FU header
    }

    private var sequence = Random.nextInt(0x10000) and 0xFFFF
    private val ssrc = Random.nextInt()

    /** 最近一次产出的最后一个 RTP 包的 sequence（16 位）和 timestamp（90kHz 时钟）。 */
    @Volatile private var lastSeq = 0
    @Volatile private var lastTimestamp = 0L

    fun latestSequence(): Int = lastSeq
    fun latestTimestamp(): Long = lastTimestamp
    fun ssrc(): Int = ssrc

    @Volatile
    private var sps: ByteArray? = null

    @Volatile
    private var pps: ByteArray? = null

    /** 缓存的最新一个 NAL 访问单元（含 keyFrame 前后），用于新客户端加入时补发完整参数集。 */
    private val pendingSps = mutableListOf<ByteArray>()
    private val pendingPps = mutableListOf<ByteArray>()

    fun setCodecConfig(spsBytes: ByteArray, ppsBytes: ByteArray) {
        sps = spsBytes.copyOf()
        pps = ppsBytes.copyOf()
        synchronized(pendingSps) {
            pendingSps.clear()
            pendingPps.clear()
            pendingSps.add(spsBytes.copyOf())
            pendingPps.add(ppsBytes.copyOf())
        }
    }

    /**
     * 把一次访问单元（Annex B H264 buffer）打包成若干 RTP 包。
     * @param newClient 是否有新客户端需要额外补发一次 SPS/PPS（任意帧都能触发，不等 keyFrame）。
     */
    fun packetize(
        buffer: ByteArray,
        ptsUs: Long,
        keyFrame: Boolean,
        newClient: Boolean = false
    ): List<ByteArray> {
        val nals = H264Utils.splitAnnexB(buffer).filter { it.isNotEmpty() }
        if (nals.isEmpty()) return emptyList()

        // 缓存当前帧里可能带的 SPS/PPS
        for (nal in nals) {
            when (H264Utils.nalType(nal)) {
                7 -> {
                    sps = nal.copyOf()
                    synchronized(pendingSps) {
                        pendingSps.clear()
                        pendingSps.add(nal.copyOf())
                    }
                }
                8 -> {
                    pps = nal.copyOf()
                    synchronized(pendingPps) {
                        pendingPps.clear()
                        pendingPps.add(nal.copyOf())
                    }
                }
            }
        }

        val timestamp = ptsUs * 90L / 1000L
        val outgoing = ArrayList<ByteArray>(nals.size + 2)

        // 关键帧前补发 SPS/PPS（RTP 里缺了参数集客户端会丢关键帧）
        val hasSpsPpsInFrame = nals.any { H264Utils.nalType(it) == 7 }
        if (keyFrame && !hasSpsPpsInFrame) {
            synchronized(pendingSps) {
                pendingSps.firstOrNull()?.let { outgoing.add(it) }
                pendingPps.firstOrNull()?.let { outgoing.add(it) }
            }
        }
        // 新客户端补发：不是关键帧但有新客户端加入，也补发一次 SPS/PPS
        // 避免新客户端等下一关键帧才拿到参数
        if (newClient && !hasSpsPpsInFrame && !keyFrame) {
            synchronized(pendingSps) {
                pendingSps.firstOrNull()?.let { outgoing.add(it) }
                pendingPps.firstOrNull()?.let { outgoing.add(it) }
            }
        }

        outgoing.addAll(nals)
        if (outgoing.isEmpty()) return emptyList()

        val rtpPackets = ArrayList<ByteArray>(outgoing.size + 2)
        for ((index, nal) in outgoing.withIndex()) {
            val marker = index == outgoing.lastIndex
            if (nal.size <= RTP_PAYLOAD) {
                rtpPackets.add(buildSinglePacket(nal, timestamp, marker))
            } else {
                rtpPackets.addAll(buildFragmentedPackets(nal, timestamp, marker))
            }
        }
        return rtpPackets
    }

    private fun buildSinglePacket(nal: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
        val packet = ByteArray(12 + nal.size)
        writeRtpHeader(packet, marker, timestamp)
        System.arraycopy(nal, 0, packet, 12, nal.size)
        return packet
    }

    private fun buildFragmentedPackets(
        nal: ByteArray,
        timestamp: Long,
        finalMarker: Boolean
    ): List<ByteArray> {
        val chunkSize = RTP_PAYLOAD - FU_A_HEADER
        val payloadSize = nal.size - 1
        val packets = ArrayList<ByteArray>()
        var offset = 0

        while (offset < payloadSize) {
            val size = min(chunkSize, payloadSize - offset)
            val start = offset == 0
            val end = offset + size >= payloadSize
            val marker = end && finalMarker

            val packet = ByteArray(12 + FU_A_HEADER + size)
            writeRtpHeader(packet, marker, timestamp)
            // FU indicator：forbidden_zero_bit + nal_ref_idc + type 28 (FU-A)
            packet[12] = ((nal[0].toInt() and 0xE0) or 28).toByte()
            // FU header：S/E/R + 原 nal type
            packet[13] = (
                (if (start) 0x80 else 0) or
                    (if (end) 0x40 else 0) or
                    (nal[0].toInt() and 0x1F)
                ).toByte()
            // payload 从 nal[1 + offset] 开始（跳过原始 NAL 头）
            System.arraycopy(nal, 1 + offset, packet, 14, size)
            packets.add(packet)
            offset += size
        }
        return packets
    }

    private fun writeRtpHeader(packet: ByteArray, marker: Boolean, pts90: Long) {
        sequence = (sequence + 1) and 0xFFFF
        lastSeq = sequence
        lastTimestamp = pts90
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0x00) or 96).toByte()
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((pts90 shr 24) and 0xFF).toByte()
        packet[5] = ((pts90 shr 16) and 0xFF).toByte()
        packet[6] = ((pts90 shr 8) and 0xFF).toByte()
        packet[7] = (pts90 and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
    }
}

package com.shiliu.stream.media

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * H264 over RTP 无握手 UDP 广播：
 * - App 在指定端口监听 DatagramSocket；
 * - 任何客户端先发一个 UDP 包（内容随便，可以是空包）告知 App 其 IP:Port；
 * - App 用 RTP 打包器把编码器输出封成 RTP 包，循环发给所有已知客户端；
 * - 新客户端加入或中途丢失后重连，都能在几秒内拿到完整 SPS/PPS（通过 packetizer.newClient 补发）；
 * - 超过 10 秒没有发过任何 UDP 包的客户端会被清理出列表。
 */
class RtpBroadcaster(
    private val port: Int,
    private val onNewClient: () -> Unit = {}
) {

    companion object {
        private const val TAG = "RtpBroadcaster"
        private val CLIENT_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(15)
        private val CLEANUP_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5)
    }

    data class ClientAddress(
        val address: InetAddress,
        val port: Int,
        @Volatile var lastSeenNs: Long = System.nanoTime()
    ) {
        val key: String
            get() = "${address.hostAddress}:$port"
    }

    private val packetizer = H264RtpPacketizer()
    private val clients = CopyOnWriteArrayList<ClientAddress>()
    private val writeLock = Any()

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var cleanupThread: Thread? = null

    /** 自上次编码器帧被 publish 以来，是否有新客户端加入还没吃到补发 SPS/PPS 的那一套。 */
    @Volatile private var newClientSinceLastFrame = false

    fun start() {
        if (running) return
        running = true
        socket = DatagramSocket(port).apply { reuseAddress = true }
        receiveThread = Thread({ receiveLoop() }, "rtp-bcast-recv").apply {
            isDaemon = true
            start()
        }
        cleanupThread = Thread({ cleanupLoop() }, "rtp-bcast-cleanup").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "RTP 广播已启动，监听 UDP 端口 $port")
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        clients.clear()
        receiveThread = null
        cleanupThread = null
    }

    fun setCodecConfig(sps: ByteArray, pps: ByteArray) = packetizer.setCodecConfig(sps, pps)

    fun clientCount(): Int = clients.size

    fun publish(buffer: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (!running) return
        val shouldReissueSps = newClientSinceLastFrame
        newClientSinceLastFrame = false
        val packets = packetizer.packetize(buffer, ptsUs, keyFrame, newClient = shouldReissueSps)
        if (packets.isEmpty()) return
        synchronized(writeLock) {
            for (client in clients) {
                sendPackets(packets, client)
            }
        }
    }

    private fun sendPackets(packets: List<ByteArray>, client: ClientAddress) {
        val s = socket ?: return
        for (pkt in packets) {
            try {
                s.send(DatagramPacket(pkt, pkt.size, client.address, client.port))
            } catch (e: IOException) {
                Log.w(TAG, "RTP 发送失败给 ${client.address.hostAddress}:${client.port}: ${e.message}")
            }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(64)
        val dp = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                socket?.receive(dp)
                val now = System.nanoTime()
                val key = "${dp.address.hostAddress}:${dp.port}"
                val existing = clients.firstOrNull { it.key == key }
                if (existing != null) {
                    existing.lastSeenNs = now
                } else {
                    clients.add(ClientAddress(dp.address, dp.port, now))
                    newClientSinceLastFrame = true
                    Log.i(TAG, "RTP 新客户端加入: ${dp.address.hostAddress}:${dp.port} (共 ${clients.size})")
                    onNewClient()
                }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(CLEANUP_INTERVAL_MS)
                val cutoff = System.nanoTime() - CLIENT_TIMEOUT_NS
                val removed = clients.removeAll { it.lastSeenNs < cutoff }
                if (removed) Log.i(TAG, "清理离线客户端，剩 ${clients.size} 个")
            } catch (_: InterruptedException) {
                break
            }
        }
    }
}

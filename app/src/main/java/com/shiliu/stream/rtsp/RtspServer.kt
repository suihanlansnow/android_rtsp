package com.shiliu.stream.rtsp

import android.util.Log
import com.shiliu.stream.media.H264RtpPacketizer
import com.shiliu.stream.media.H264Utils
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlin.random.Random

/**
 * 轻量 RTSP/1.0 服务端：
 * - H.264 over RTP，支持 TCP interleaved 与 UDP 两种传输；
 * - SDP 直接从编码器产出的 SPS/PPS 生成；
 * - 可选 Basic 鉴权，多客户端共享同一路码流；
 * - 新客户端加入时主动请求 IDR 帧。
 */
class RtspServer(
    private val port: Int,
    private val path: String,
    private val host: String,
    private val username: String,
    private val password: String,
    width: Int,
    height: Int,
    fps: Int,
    private val onClientJoined: () -> Unit
) {
    companion object {
        private const val TAG = "RtspServer"
        /** 单客户端发送队列上限，超出后整帧丢弃，避免慢客户端拖住编码线程。 */
        private const val QUEUE_MAX_BYTES = 1_500_000
        private const val QUEUE_MAX_PACKETS = 512
    }

    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile
    private var sps: ByteArray? = null

    @Volatile
    private var pps: ByteArray? = null

    @Volatile
    private var videoWidth: Int = width

    @Volatile
    private var videoHeight: Int = height

    @Volatile
    private var frameRate: Int = fps

    @Throws(IOException::class)
    fun start() {
        if (running) return
        running = true
        serverSocket = ServerSocket(port).apply { reuseAddress = true }
        acceptThread = Thread({ acceptLoop() }, "rtsp-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "RTSP 服务已启动: rtsp://$host:$port/$path")
    }

    fun setCodecConfig(spsBytes: ByteArray, ppsBytes: ByteArray) {
        sps = spsBytes.copyOf()
        pps = ppsBytes.copyOf()
    }

    fun clientCount(): Int = clients.count { it.playing }

    /** 累计因客户端发送太慢而丢弃的 RTP 包数，供统计面板展示。 */
    fun droppedPackets(): Long = clients.sumOf { it.droppedPackets() }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        sps = null
        pps = null
        acceptThread = null
    }

    /** 编码线程调用：把单个访问单元广播给所有正在播放的客户端。 */
    fun publish(buffer: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        // 维护全局 SPS/PPS 缓存（供 buildSdp() 生成 SDP）
        val nals = H264Utils.splitAnnexB(buffer).filter { it.isNotEmpty() }
        for (nal in nals) {
            when (H264Utils.nalType(nal)) {
                7 -> sps = nal.copyOf()
                8 -> pps = nal.copyOf()
            }
        }

        for (client in clients) {
            if (client.playing) client.publish(buffer, ptsUs, keyFrame)
        }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket?.accept() ?: break
                val client = Client(socket)
                clients.add(client)
                client.start()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "接受连接失败: ${e.message}")
                break
            }
        }
    }

    private fun buildSdp(): String? {
        val spsBytes = sps ?: return null
        val ppsBytes = pps ?: return null
        val encoder = Base64.getEncoder()
        val spsText = encoder.encodeToString(spsBytes)
        val ppsText = encoder.encodeToString(ppsBytes)
        val profile = H264Utils.profileLevelId(spsBytes)
        return buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 $host\r\n")
            append("s=ShiLiu Camera\r\n")
            append("c=IN IP4 $host\r\n")
            append("t=0 0\r\n")
            append("a=control:*\r\n")
            append("m=video 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            append("a=fmtp:96 packetization-mode=1;profile-level-id=$profile;")
            append("sprop-parameter-sets=$spsText,$ppsText\r\n")
            append("a=framerate:$frameRate\r\n")
            append("a=control:trackID=0\r\n")
        }
    }

    private val contentBase: String get() = "rtsp://$host:$port/$path/"

    private fun randomHex(length: Int): String {
        val chars = "0123456789ABCDEF"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun ssrcHex(ssrc: Int): String = String.format("%08X", ssrc)

    private inner class Client(private val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        @Volatile
        var playing = false
            private set

        @Volatile
        private var closed = false

        @Volatile
        private var interleaved = true
        private var rtpChannel = 0
        private var rtcpChannel = 1

        // 每客户端独立发送队列：编码线程只入队，实际写 socket 在独立线程完成，
        // 慢客户端不会再阻塞编码和其余客户端。
        private val queueLock = ReentrantLock()
        private val queueSignal = queueLock.newCondition()
        private val sendQueue = ArrayDeque<ByteArray>()
        private var queuedBytes = 0

        @Volatile
        private var droppedPacketsCount = 0L

        private var rtpSocket: DatagramSocket? = null
        private var rtcpSocket: DatagramSocket? = null
        private var clientAddress: InetAddress? = null

        @Volatile
        private var clientRtpPort = 0
        private var clientRtcpPort = 0
        private var serverRtpPort = 0
        private var serverRtcpPort = 0

        private val sessionId = randomHex(16)
        private val packetizer = H264RtpPacketizer()
        private var reader: Thread? = null
        private var sender: Thread? = null

        fun start() {
            clientAddress = socket.inetAddress
            reader = Thread({ readLoop() }, "rtsp-client").apply {
                isDaemon = true
                start()
            }
            sender = Thread({ sendLoop() }, "rtsp-send").apply {
                isDaemon = true
                start()
            }
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            playing = false
            queueLock.withLock {
                sendQueue.clear()
                queuedBytes = 0
                queueSignal.signalAll()
            }
            val current = sender
            if (current != null && current !== Thread.currentThread()) {
                current.interrupt()
                try {
                    current.join(300)
                } catch (_: InterruptedException) {
                }
            }
            sender = null
            try {
                socket.close()
            } catch (_: IOException) {
            }
            try {
                rtpSocket?.close()
            } catch (_: Exception) {
            }
            try {
                rtcpSocket?.close()
            } catch (_: Exception) {
            }
            clients.remove(this)
        }

        private fun readLoop() {
            try {
                while (!closed) {
                    val requestLine = readLine(input) ?: break
                    if (requestLine.isBlank()) continue
                    val parts = requestLine.split(" ").filter { it.isNotBlank() }
                    if (parts.size < 3) continue
                    val method = parts[0].uppercase()
                    val uri = parts[1]
                    val headers = readHeaders(input)
                    handle(method, uri, headers)
                }
            } catch (e: Exception) {
                if (!closed) Log.d(TAG, "客户端结束: ${e.message}")
            } finally {
                close()
            }
        }

        private fun handle(method: String, uri: String, headers: Map<String, String>) {
            val cseq = headers["cseq"] ?: "0"
            when (method) {
                "OPTIONS" -> respond(
                    200, "OK", cseq,
                    "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"
                )

                "DESCRIBE" -> {
                    if (!authorized(headers)) return unauthorized(cseq)
                    val sdp = buildSdp()
                    if (sdp == null) {
                        respond(503, "Service Unavailable", cseq, "Retry-After: 1")
                    } else {
                        respond(
                            200, "OK", cseq,
                            "Content-Type: application/sdp\r\nContent-Base: $contentBase",
                            sdp
                        )
                    }
                }

                "SETUP" -> {
                    if (!authorized(headers)) return unauthorized(cseq)
                    setup(headers, cseq, uri)
                }

                "PLAY" -> {
                    if (!authorized(headers)) return unauthorized(cseq)
                    if (sps == null || pps == null) {
                        respond(503, "Service Unavailable", cseq, "Retry-After: 1")
                        return
                    }
                    playing = true
                    respond(
                        200, "OK", cseq,
                        "Session: $sessionId;timeout=60\r\n" +
                            "RTP-Info: url=${trackUrl(uri)};seq=${packetizer.latestSequence()};rtptime=${packetizer.latestTimestamp()}"
                    )
                    // 同步最新的 SPS/PPS 给这个 Client 的 packetizer，
                    // 让新加入的客户端能立刻补发参数集。
                    synchronized(this@RtspServer) {
                        val localSps = sps
                        val localPps = pps
                        if (localSps != null && localPps != null) {
                            packetizer.setCodecConfig(localSps, localPps)
                        }
                    }
                    onClientJoined()
                }

                "PAUSE" -> {
                    if (!authorized(headers)) return unauthorized(cseq)
                    playing = false
                    clearQueue()
                    respond(200, "OK", cseq, "Session: $sessionId")
                }

                "TEARDOWN" -> {
                    respond(200, "OK", cseq, "Session: $sessionId")
                    close()
                }

                "GET_PARAMETER", "SET_PARAMETER" -> {
                    if (!authorized(headers)) return unauthorized(cseq)
                    respond(200, "OK", cseq, "Session: $sessionId")
                }

                else -> respond(501, "Not Implemented", cseq)
            }
        }

        private fun setup(headers: Map<String, String>, cseq: String, uri: String) {
            val transport = headers["transport"] ?: ""
            val lower = transport.lowercase()

            if (lower.contains("tcp") || lower.contains("interleaved")) {
                interleaved = true
                Regex("interleaved=(\\d+)-(\\d+)").find(lower)?.let { match ->
                    rtpChannel = match.groupValues[1].toIntOrNull() ?: 0
                    rtcpChannel = match.groupValues[2].toIntOrNull() ?: (rtpChannel + 1)
                }
                closeUdp()
                respond(
                    200, "OK", cseq,
                    "Session: $sessionId;timeout=60\r\n" +
                        "Transport: RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel;ssrc=${ssrcHex(packetizer.ssrc())}"
                )
            } else {
                val match = Regex("client_port=(\\d+)-(\\d+)").find(lower)
                if (match == null) {
                    respond(400, "Bad Request", cseq)
                    return
                }
                interleaved = false
                clientRtpPort = match.groupValues[1].toIntOrNull() ?: 0
                clientRtcpPort = match.groupValues[2].toIntOrNull() ?: (clientRtpPort + 1)
                clientAddress = socket.inetAddress
                closeUdp()
                rtpSocket = DatagramSocket(0)
                rtcpSocket = DatagramSocket(0)
                serverRtpPort = rtpSocket?.localPort ?: 0
                serverRtcpPort = rtcpSocket?.localPort ?: 0
                respond(
                    200, "OK", cseq,
                    "Session: $sessionId;timeout=60\r\n" +
                        "Transport: RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;" +
                        "server_port=$serverRtpPort-$serverRtcpPort;ssrc=${ssrcHex(packetizer.ssrc())}"
                )
            }

            // 记录 track 地址，PLAY 时回填
            trackUri = if (uri.endsWith("/")) "${uri}trackID=0" else "$uri/trackID=0"
        }

        private var trackUri: String = ""

        private fun trackUrl(requestUri: String): String {
            if (trackUri.isNotEmpty()) return trackUri
            return if (requestUri.endsWith("/")) "${requestUri}trackID=0" else "$requestUri/trackID=0"
        }

        private fun authorized(headers: Map<String, String>): Boolean {
            if (password.isEmpty()) return true
            val header = headers["authorization"] ?: return false
            if (!header.startsWith("Basic ", ignoreCase = true)) return false
            return try {
                val decoded = String(Base64.getDecoder().decode(header.substring(6).trim()))
                decoded == "$username:$password"
            } catch (e: Exception) {
                false
            }
        }

        private fun unauthorized(cseq: String) {
            respond(
                401, "Unauthorized", cseq,
                "WWW-Authenticate: Basic realm=\"ShiLiu Camera\""
            )
        }

        private fun respond(
            code: Int,
            reason: String,
            cseq: String,
            extraHeaders: String = "",
            body: String? = null
        ) {
            val builder = StringBuilder()
            builder.append("RTSP/1.0 ").append(code).append(' ').append(reason).append("\r\n")
            builder.append("CSeq: ").append(cseq).append("\r\n")
            builder.append("Server: ShiLiu/1.0\r\n")
            if (extraHeaders.isNotEmpty()) builder.append(extraHeaders).append("\r\n")
            if (body != null) {
                builder.append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
            }
            builder.append("\r\n")
            if (body != null) builder.append(body)
            writeRaw(builder.toString().toByteArray(Charsets.UTF_8))
        }

        private fun writeRaw(data: ByteArray) {
            if (closed) return
            try {
                synchronized(writeLock) {
                    output.write(data)
                    output.flush()
                }
            } catch (e: IOException) {
                close()
            }
        }

        fun droppedPackets(): Long = droppedPacketsCount

        /** 编码线程调用：整帧入队，不在这里做任何阻塞写。 */
        fun publish(buffer: ByteArray, ptsUs: Long, keyFrame: Boolean) {
            if (!playing || closed) return
            val packets = packetizer.packetize(buffer, ptsUs, keyFrame)
            if (packets.isEmpty()) return
            var frameBytes = 0
            for (pkt in packets) frameBytes += pkt.size
            queueLock.withLock {
                if (closed) return
                val overflow = queuedBytes + frameBytes > QUEUE_MAX_BYTES ||
                    sendQueue.size + packets.size > QUEUE_MAX_PACKETS
                if (overflow) {
                    if (!keyFrame) {
                        // 非关键帧整帧丢弃，保证已入队的帧仍然完整
                        droppedPacketsCount += packets.size
                        return
                    }
                    // 关键帧优先：清掉积压，让客户端尽快拿到新的 IDR
                    droppedPacketsCount += sendQueue.size
                    sendQueue.clear()
                    queuedBytes = 0
                }
                for (pkt in packets) {
                    sendQueue.addLast(pkt)
                    queuedBytes += pkt.size
                }
                queueSignal.signalAll()
            }
        }

        /** 独立发送线程：从队列取包写 socket，慢客户端只会让自己积压丢帧。 */
        private fun sendLoop() {
            try {
                while (true) {
                    var packet: ByteArray? = null
                    queueLock.withLock {
                        while (!closed && sendQueue.isEmpty()) {
                            queueSignal.await(200, TimeUnit.MILLISECONDS)
                        }
                        if (!closed) {
                            packet = sendQueue.pollFirst()
                            if (packet != null) queuedBytes -= packet!!.size
                        }
                    }
                    if (closed) break
                    val current = packet ?: continue
                    writePacket(current)
                }
            } catch (_: InterruptedException) {
            } finally {
                close()
            }
        }

        private fun writePacket(packet: ByteArray) {
            if (closed) return
            try {
                synchronized(writeLock) {
                    if (closed) return
                    if (interleaved) {
                        output.write(0x24)
                        output.write(rtpChannel)
                        output.write((packet.size shr 8) and 0xFF)
                        output.write(packet.size and 0xFF)
                        output.write(packet)
                        output.flush()
                    } else {
                        val address = clientAddress ?: return
                        rtpSocket?.send(DatagramPacket(packet, packet.size, address, clientRtpPort))
                    }
                }
            } catch (e: IOException) {
                close()
            }
        }

        private fun clearQueue() {
            queueLock.withLock {
                droppedPacketsCount += sendQueue.size
                sendQueue.clear()
                queuedBytes = 0
            }
        }

        private fun closeUdp() {
            try {
                rtpSocket?.close()
            } catch (_: Exception) {
            }
            try {
                rtcpSocket?.close()
            } catch (_: Exception) {
            }
            rtpSocket = null
            rtcpSocket = null
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    /**
     * 读取一行文本；遇到客户端发来的 RTCP interleaved 帧（以 '$' 开头）时跳过。
     */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return null
            if (byte == 0x24) { // '$'
                val channel = input.read()
                val hi = input.read()
                val lo = input.read()
                if (channel < 0 || hi < 0 || lo < 0) return null
                val length = (hi shl 8) or lo
                var remaining = length
                val buffer = ByteArray(1024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, min(buffer.size, remaining))
                    if (read <= 0) return null
                    remaining -= read
                }
                continue
            }
            if (byte == '\n'.code) return builder.toString().trimEnd('\r')
            if (byte != '\r'.code) builder.append(byte.toChar())
        }
    }
}

package com.shiliu.stream

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetUtils {
    /** 获取可供局域网拉流的 IPv4 地址，优先 Wi-Fi/有线网卡。 */
    fun localIpv4(): String? {
        val candidates = ArrayList<Pair<String, Inet4Address>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.inetAddresses.toList()) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        candidates.add(nif.name to address)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        val preferred = candidates.firstOrNull { (name, _) ->
            name.startsWith("wlan") || name.startsWith("eth")
        }
        val chosen = preferred?.second ?: candidates.firstOrNull()?.second
        return chosen?.hostAddress
    }
}

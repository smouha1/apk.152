package com.smouha.call

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun localIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name?.lowercase() ?: ""
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress ?: continue
                        if (host.startsWith("192.168.") ||
                            host.startsWith("10.") ||
                            host.startsWith("172.")
                        ) {
                            if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                                return host
                            }
                            if (fallback == null) fallback = host
                        }
                    }
                }
            }
            if (fallback != null) return fallback
        } catch (_: Exception) {
        }
        return null
    }

    /** Short 2-part code (e.g. "1.104" for "192.168.1.104") for instant zero-scan entry on PC */
    fun quickCode(ip: String? = localIpv4()): String? {
        if (ip.isNullOrBlank()) return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return if (parts[0] == "192" && parts[1] == "168") {
            "${parts[2]}.${parts[3]}"
        } else {
            ip
        }
    }
}

package com.khereba1.app.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                            return ip
                        }
                        if (fallback == null) fallback = ip
                    }
                }
            }
            fallback
        } catch (_: Throwable) {
            null
        }
    }

    /** Finds the active Hotspot / Wi-Fi Direct gateway IPv4 address on this device. */
    fun findHotspotIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return getLocalIpAddress() ?: "192.168.49.1"
            var p2pIp: String? = null
            var apIp: String? = null
            var wlanIp: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        when {
                            name.startsWith("p2p") || ip.startsWith("192.168.49.") -> p2pIp = ip
                            name.startsWith("ap") || name.startsWith("swlan") || ip.startsWith("192.168.43.") -> apIp = ip
                            name.startsWith("wlan") -> wlanIp = ip
                        }
                    }
                }
            }
            apIp ?: p2pIp ?: wlanIp ?: getLocalIpAddress() ?: "192.168.49.1"
        } catch (_: Throwable) {
            getLocalIpAddress() ?: "192.168.49.1"
        }
    }
}

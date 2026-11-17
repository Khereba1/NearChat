package com.khereba1.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finds the real (non-VPN) default network and watches it for changes.
 * Used by the VPN service (underlying networks) and by the tunnel core
 * (default interface + reload when Wi-Fi <-> mobile data switches).
 */
object PhysicalNetwork {

    data class Info(
        val network: Network,
        val interfaceName: String,
        val index: Int = 1,
        val mtu: Int = 1500,
        val cidrAddresses: List<String> = listOf("10.0.0.2/24"),
        val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1")
    )

    private fun connectivity(context: Context): ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun score(caps: NetworkCapabilities): Int {
        var s = 0
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 100
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            s += 10
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            s += 5
        }
        return s
    }

    private fun buildInfo(cm: ConnectivityManager, n: Network, fallbackIndex: Int): Info? {
        val caps = cm.getNetworkCapabilities(n) ?: return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val lp = cm.getLinkProperties(n) ?: return null
        val name = lp.interfaceName?.takeIf { it.isNotBlank() } ?: return null

        val ni = runCatching { NetworkInterface.getByName(name) }.getOrNull()
        val idx = ni?.index?.takeIf { it > 0 } ?: fallbackIndex.coerceAtLeast(1)
        val linkMtu = runCatching { lp.mtu }.getOrDefault(0)
        val niMtu = runCatching { ni?.mtu ?: 0 }.getOrDefault(0)
        val mtu = when {
            linkMtu in 1280..9000 -> linkMtu
            niMtu in 1280..9000 -> niMtu
            else -> 1500
        }

        val cidrs = mutableListOf<String>()
        runCatching {
            for (la in lp.linkAddresses) {
                val addr = la.address ?: continue
                val host = addr.hostAddress?.substringBefore('%') ?: continue
                if (host.isNotBlank()) {
                    cidrs.add("$host/${la.prefixLength}")
                }
            }
        }
        if (cidrs.isEmpty()) {
            runCatching {
                ni?.interfaceAddresses?.forEach { ia ->
                    val host = ia.address?.hostAddress?.substringBefore('%')
                    if (!host.isNullOrBlank()) {
                        cidrs.add("$host/${ia.networkPrefixLength}")
                    }
                }
            }
        }
        if (cidrs.isEmpty()) {
            cidrs.add("10.0.0.2/24")
        }

        val dnsIpv4 = mutableListOf<String>()
        val dnsOther = mutableListOf<String>()
        runCatching {
            for (d in lp.dnsServers) {
                val host = d.hostAddress?.substringBefore('%') ?: continue
                if (host.isBlank()) continue
                if (d is Inet4Address) dnsIpv4.add(host) else dnsOther.add(host)
            }
        }
        val dnsList = (dnsIpv4 + dnsOther).ifEmpty { listOf("8.8.8.8", "1.1.1.1") }

        return Info(
            network = n,
            interfaceName = name,
            index = idx,
            mtu = mtu,
            cidrAddresses = cidrs,
            dnsServers = dnsList
        )
    }

    @Suppress("DEPRECATION")
    fun current(context: Context): Info? {
        val cm = connectivity(context) ?: return null
        return try {
            var best: Info? = null
            var bestScore = -1
            var seq = 1
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val info = buildInfo(cm, n, seq++) ?: continue
                val sc = score(caps)
                if (sc > bestScore) {
                    bestScore = sc
                    best = info
                }
            }
            best
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun allInterfaces(context: Context): List<Info> {
        val cm = connectivity(context) ?: return emptyList()
        return try {
            val scored = mutableListOf<Pair<Int, Info>>()
            val seenNames = HashSet<String>()
            var seq = 1
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val info = buildInfo(cm, n, seq++) ?: continue
                if (seenNames.add(info.interfaceName)) {
                    scored.add(score(caps) to info)
                }
            }
            scored.sortByDescending { it.first }
            // Ensure unique positive index per interface so libbox always matches updateDefaultInterface
            val usedIndices = HashSet<Int>()
            var nextFallback = 1
            scored.map { (_, info) ->
                var idx = info.index
                if (idx <= 0 || !usedIndices.add(idx)) {
                    while (usedIndices.contains(nextFallback)) nextFallback++
                    idx = nextFallback
                    usedIndices.add(idx)
                }
                info.copy(index = idx)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Resolves [host] to an IPv4 literal using the active non-VPN physical network first.
     * Returns null if [host] cannot be resolved to IPv4 so callers can keep the original hostname.
     */
    fun resolveIpv4(context: Context?, host: String): String? {
        val h = host.trim()
        if (h.isEmpty()) return null
        val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        if (ipv4Regex.matches(h)) return h
        if (h.contains(":")) return null // IPv6 literal
        val physNet = context?.let { current(it)?.network }
        return runCatching {
            val addrs = if (physNet != null) {
                runCatching { physNet.getAllByName(h) }.getOrNull() ?: InetAddress.getAllByName(h)
            } else {
                InetAddress.getAllByName(h)
            }
            addrs.firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }

    /** [onChange] runs on the main thread, debounced (networks flap a lot while switching). */
    fun watch(context: Context, onChange: () -> Unit): ConnectivityManager.NetworkCallback? {
        val cm = connectivity(context) ?: return null
        val handler = Handler(Looper.getMainLooper())
        val fire = Runnable { onChange() }
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun schedule() {
                handler.removeCallbacks(fire)
                handler.postDelayed(fire, 800L)
            }

            override fun onAvailable(network: Network) {
                schedule()
            }

            override fun onLost(network: Network) {
                schedule()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                schedule()
            }
        }
        return try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, cb)
            cb
        } catch (_: Throwable) {
            null
        }
    }

    fun unwatch(context: Context, callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        try {
            connectivity(context)?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
    }
}

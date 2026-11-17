package com.khereba1.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Finds the real (non-VPN) default network and watches it for changes.
 * Used by the VPN service (underlying networks) and by the tunnel core
 * (default interface + reload when Wi-Fi <-> mobile data switches).
 */
object PhysicalNetwork {

    data class Info(val network: Network, val interfaceName: String)

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

    @Suppress("DEPRECATION")
    fun current(context: Context): Info? {
        val cm = connectivity(context) ?: return null
        return try {
            var best: Info? = null
            var bestScore = -1
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val name = cm.getLinkProperties(n)?.interfaceName ?: continue
                val sc = score(caps)
                if (sc > bestScore) {
                    bestScore = sc
                    best = Info(n, name)
                }
            }
            best
        } catch (_: Throwable) {
            null
        }
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

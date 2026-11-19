package com.khereba1.app.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

data class LocalHotspotInfo(val ssid: String, val passphrase: String)

object LocalHotspotManager {
    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @SuppressLint("MissingPermission")
    fun start(context: Context, onResult: (LocalHotspotInfo?, String) -> Unit) {
        if (!isSupported()) {
            onResult(null, "Local hotspot requires Android 8+")
            return
        }
        val existing = reservation
        if (existing != null) {
            onResult(readInfo(existing), "")
            return
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            onResult(null, "WifiManager unavailable")
            return
        }
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    onResult(readInfo(res), "")
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    onResult(null, "Local hotspot failed (code $reason)")
                }

                override fun onStopped() {
                    reservation = null
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Throwable) {
            onResult(null, "Local hotspot error: ${e.message}")
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (_: Throwable) {
        }
        reservation = null
    }

    @Suppress("DEPRECATION")
    private fun readInfo(res: WifiManager.LocalOnlyHotspotReservation): LocalHotspotInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = res.softApConfiguration
            LocalHotspotInfo(cfg.ssid?.toString() ?: "", cfg.passphrase ?: "")
        } else {
            val cfg = res.wifiConfiguration
            LocalHotspotInfo(
                cfg?.SSID?.trim('"') ?: "",
                cfg?.preSharedKey?.trim('"') ?: ""
            )
        }
    }
}
